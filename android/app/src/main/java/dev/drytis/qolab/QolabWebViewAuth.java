package dev.drytis.qolab;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/**
 * WebView-backed authentication bridge (v1.4.6.1 — hardened).
 *
 * Authenticates the user through the EXISTING Laravel/Fortify browser
 * flow, executed inside a hidden WebView whose document origin is the
 * Development server. Cookies are therefore established by the platform
 * in the SAME global CookieManager that Capacitor/MainActivity use —
 * no Java-side cookie reconstruction, no manual CSRF jar.
 *
 * v1.4.6.1 fixes (device: infinite spinner, no callback):
 *  - 45s watchdog guarantees a callback — the spinner can never hang.
 *  - JS-side AbortController (30s) cancels stalled fetches.
 *  - XSRF header value is produced in JAVA (from CookieManager) instead
 *    of document.cookie — loadDataWithBaseURL documents don't reliably
 *    expose cookies to JS on all WebView builds.
 *  - 419 fallback: retry once with the raw (undecoded) representation.
 *  - Progress stages reported through the bridge for diagnostics.
 *  - JS console piped to QolabAuth logs (debug builds).
 *
 * Security: the WebView loads an EMPTY document with the app origin as
 * base URL (no remote scripts). Credentials reach the fetch ONLY via
 * @JavascriptInterface; the interface is removed and the WebView
 * destroyed after the flow. CSRF/TLS/Fortify fully intact.
 */
public final class QolabWebViewAuth {

    /** Callbacks on the UI thread. */
    public interface Listener {
        void onAuthResult(Result r);
    }

    /** Safe result — statuses, timings, final URL, 422 body. Never values. */
    public static final class Result {
        public final boolean success;
        /** "csrf" | "login" | "fetch-io" | "timeout" | "bridge" | "done" */
        public final String stage;
        public final int csrfStatus;
        public final int loginStatus;
        public final long csrfMs;
        public final long loginMs;
        public final String finalUrl;
        public final String errorBody;
        public final String ioError;

        Result(boolean success, String stage, int csrfStatus, int loginStatus,
               long csrfMs, long loginMs, String finalUrl, String errorBody, String ioError) {
            this.success = success;
            this.stage = stage;
            this.csrfStatus = csrfStatus;
            this.loginStatus = loginStatus;
            this.csrfMs = csrfMs;
            this.loginMs = loginMs;
            this.finalUrl = finalUrl;
            this.errorBody = errorBody;
            this.ioError = ioError;
        }
    }

    // ── pure helpers (unit tested) ──────────────────────────────────

    /** Parse the JSON blob the JS bridge reports. Returns null if invalid. */
    public static Result parseBridgeJson(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(stripJsonWrapper(json));
            String stage = o.optString("stage", "done");
            String io = o.has("err") && !o.isNull("err") ? o.optString("err") : null;
            int csrf = o.optInt("csrfStatus", 0);
            int status = o.optInt("status", 0);
            long csrfMs = o.optLong("csrfMs", -1);
            long loginMs = o.optLong("loginMs", -1);
            String url = o.has("finalUrl") ? o.optString("finalUrl") : null;
            String body = o.has("body422") ? o.optString("body422") : null;
            boolean ok = "done".equals(stage) && (status / 100 == 2 || status / 100 == 3);
            return new Result(ok, stage, csrf, status, csrfMs, loginMs, url, body, io);
        } catch (Exception e) {
            return null;
        }
    }

    /** evaluateJavascript returns the value as a quoted JSON string. */
    static String stripJsonWrapper(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n");
        }
        return s;
    }

    /**
     * Extract the XSRF-TOKEN value from a CookieManager cookie header and
     * return the DECODED representation Laravel expects in X-XSRF-TOKEN
     * (verified A/B: decoded → accepted, raw → 419). Null if absent.
     */
    public static String xsrfHeaderValue(String cookieHeader) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String p = part.trim();
            if (p.startsWith("XSRF-TOKEN=")) {
                return QolabAuthClient.urlDecode(p.substring("XSRF-TOKEN=".length()));
            }
        }
        return null;
    }

    /** The empty document — zero scripts, origin comes from base URL. */
    private static final String BLANK_DOC =
            "<!DOCTYPE html><html><head><meta charset='utf-8'></head><body></body></html>";

    private static final long WATCHDOG_MS = 45_000;

    // ── runtime ─────────────────────────────────────────────────────

    private final Context context;
    private final String origin; // no trailing slash
    private final String email;
    private final String password;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private volatile boolean finished = false;
    private volatile String lastStage = "start";
    private String rawXsrf = null;
    private volatile String xsrfSnapshot = null;

    public QolabWebViewAuth(Context context, String origin, String email,
                            String password, Listener listener) {
        // Keep the ACTIVITY context if it is one — the WebView must be
        // attachable to the activity's content view (see start()).
        this.context = (context instanceof android.app.Activity)
                ? context : context.getApplicationContext();
        this.origin = trimSlash(origin);
        this.email = email;
        this.password = password;
        this.listener = listener;
    }

    private static String trimSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private final Runnable watchdog = () -> {
        if (!finished) {
            android.util.Log.w("QolabAuth", "webview-auth watchdog fired, lastStage=" + lastStage);
            deliver(new Result(false, "timeout", 0, 0, -1, -1, null, null, "WatchdogTimeout"));
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    public void start() {
        main.post(() -> {
            if (finished) return;
            // v1.4.8: attach the hidden WebView to the activity's content
            // view (1dp, GONE). A detached WebView never runs View.post()
            // runnables and can silently skip load/evaluate steps on some
            // builds — attachment is what guarantees the whole chain works
            // like a normal WebView.
            webView = new WebView(context);
            android.view.ViewGroup contentParent =
                    (android.view.ViewGroup) ((android.app.Activity) context)
                            .findViewById(android.R.id.content);
            android.widget.FrameLayout host = new android.widget.FrameLayout(context);
            host.setVisibility(android.view.View.GONE);
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(1, 1);
            host.addView(webView, lp);
            contentParent.addView(host);

            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setSavePassword(false);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            CookieManager.getInstance().setAcceptCookie(true);

            webView.setWebChromeClient(new android.webkit.WebChromeClient() {
                @Override
                public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                    android.util.Log.i("QolabAuth", "js-console: " + cm.message());
                    return true;
                }
            });

            webView.addJavascriptInterface(new Bridge(), "QolabBridge");
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return true; // never navigate anywhere — headless tool
                }

                private int injected = 0;

                @Override
                public void onPageFinished(WebView view, String url) {
                    // Inject once (page-finished can fire multiple times).
                    if (injected++ == 0) {
                        android.util.Log.i("QolabAuth", "auth doc loaded, injecting bridge js");
                        view.evaluateJavascript(AUTH_JS, null);
                        // Re-inject guard: if the first injection silently
                        // failed (JS not ready), a second attempt shortly
                        // after still helps and is harmless (idempotent —
                        // the bridge ignores repeats once finished).
                        main.postDelayed(() -> {
                            if (!finished && webView != null) {
                                webView.evaluateJavascript(AUTH_JS, null);
                            }
                        }, 1500);
                    }
                }
            });
            main.postDelayed(watchdog, WATCHDOG_MS);
            // Empty document with the app origin as base → same-origin
            // fetch privileges, no remote scripts in the document.
            webView.loadDataWithBaseURL(origin + "/", BLANK_DOC, "text/html", "utf-8", null);
        });
    }

    public void destroy() {
        main.post(() -> {
            if (webView != null) {
                try {
                    webView.removeJavascriptInterface("QolabBridge");
                    webView.stopLoading();
                    android.view.ViewGroup parent = (android.view.ViewGroup) webView.getParent();
                    if (parent != null) {
                        android.view.ViewGroup grand = (android.view.ViewGroup) parent.getParent();
                        if (grand != null) grand.removeView(parent);
                    }
                    webView.destroy();
                } catch (Exception ignored) {
                }
                webView = null;
            }
        });
    }

    private void deliver(final Result r) {
        if (finished) return;
        finished = true;
        main.removeCallbacks(watchdog);
        final WebView wv = webView;
        // v1.4.8: deliver through the MAIN HANDLER, never View.post() —
        // an unattached view silently never runs posted runnables, which
        // is what caused the eternal spinner with no error.
        main.post(() -> {
            if (wv != null) {
                try {
                    wv.removeJavascriptInterface("QolabBridge");
                    wv.stopLoading();
                    android.view.ViewGroup parent = (android.view.ViewGroup) wv.getParent();
                    if (parent != null) {
                        android.view.ViewGroup grand = (android.view.ViewGroup) parent.getParent();
                        if (grand != null) grand.removeView(parent);
                    }
                    wv.destroy();
                } catch (Exception ignored) {
                }
            }
            if (webView == wv) webView = null;
            listener.onAuthResult(r);
        });
    }

    /** JS→Java bridge. Credentials leave ONLY through getEmail/getPassword. */
    private final class Bridge {
        @JavascriptInterface
        public String getEmail() {
            return email;
        }

        @JavascriptInterface
        public String getPassword() {
            return password;
        }

        /** Decoded XSRF header value, read from the shared CookieManager. */
        @JavascriptInterface
        public String getXsrfToken() {
            try {
                String raw = readRawXsrfFromCookieManager(50);
                if (raw == null) return "";
                rawXsrf = raw;
                return QolabAuthClient.urlDecode(raw);
            } catch (Exception e) {
                return "";
            }
        }

        /** Raw (undecoded) representation — 419 fallback. */
        @JavascriptInterface
        public String getXsrfTokenRaw() {
            return rawXsrf == null ? "" : rawXsrf;
        }

        /**
         * v1.5.2: SNAPSHOT the current raw XSRF-TOKEN BEFORE the csrf GET.
         * getFreshXsrf() only accepts a value that has CHANGED since this
         * snapshot (bounded wait) — reading CookieManager the instant the
         * fetch resolves can return the STALE value from a previous run
         * while the fetch carries the freshly rotated session →
         * token/session mismatch → Laravel 419 (proven on the device).
         */
        @JavascriptInterface
        public void beginXsrf() {
            rawXsrf = readRawXsrfFromCookieManager(0);
            xsrfSnapshot = rawXsrf;
        }

        /** Fresh decoded token: waits (≤2s) until the value differs from the snapshot. */
        @JavascriptInterface
        public String getFreshXsrf() {
            try {
                String raw = XsrfFreshness.awaitChanged(xsrfSnapshot,
                        () -> readRawXsrfFromCookieManager(0), 2000, 50);
                if (raw == null) {
                    // No change within the bound — fall back to the best
                    // available value rather than failing the whole login.
                    raw = readRawXsrfFromCookieManager(0);
                }
                if (raw == null) return "";
                rawXsrf = raw;
                return QolabAuthClient.urlDecode(raw);
            } catch (Exception e) {
                return "";
            }
        }

        /** Fresh RAW token for the 419 retry (short re-read wait). */
        @JavascriptInterface
        public String getFreshXsrfRaw() {
            String raw = XsrfFreshness.awaitChanged(xsrfSnapshot,
                    () -> readRawXsrfFromCookieManager(0), 300, 50);
            if (raw == null) {
                raw = rawXsrf;
            }
            rawXsrf = raw;
            return raw == null ? "" : raw;
        }

        private String readRawXsrfFromCookieManager(int maxWaitMs) {
            if (maxWaitMs <= 0) {
                return extractRawXsrf(readCookieHeader());
            }
            return XsrfFreshness.awaitPresent(() -> extractRawXsrf(readCookieHeader()),
                    maxWaitMs, 25);
        }

        private String readCookieHeader() {
            try {
                return CookieManager.getInstance().getCookie(origin);
            } catch (Exception e) {
                return null;
            }
        }

        /** Raw XSRF-TOKEN value from a CookieManager header string, or null. */
        private static String extractRawXsrf(String header) {
            if (header == null) return null;
            for (String part : header.split(";")) {
                String p = part.trim();
                if (p.startsWith("XSRF-TOKEN=")) {
                    return p.substring("XSRF-TOKEN=".length());
                }
            }
            return null;
        }

        /** Progress beacon for diagnostics (stage names only). */
        @JavascriptInterface
        public void onStage(String stage) {
            lastStage = stage == null ? "?" : stage;
            android.util.Log.i("QolabAuth", "webview-auth stage: " + lastStage);
        }

        @JavascriptInterface
        public void onDone(String json) {
            Result r = parseBridgeJson(json);
            if (r == null) {
                r = new Result(false, "bridge", 0, 0, -1, -1, null, null, "BridgeJsonError");
            }
            deliver(r);
        }
    }

    /**
     * Browser-equivalent login: csrf-cookie GET → X-XSRF-TOKEN (decoded,
     * supplied from Java's CookieManager) → POST /login with the platform
     * attaching cookies natively. 419 → single retry with the raw value.
     * AbortController + the Java watchdog guarantee termination.
     */
    private static final String AUTH_JS =
            "(function(){try{var b=window.QolabBridge;if(!b){return;}" +
            "var ctl=new AbortController();setTimeout(function(){try{ctl.abort();}catch(e){}},30000);" +
            "function done(o){try{b.onDone(JSON.stringify(o));}catch(e){}}" +
            "var t0=performance.now();b.onStage('csrf');b.beginXsrf();" +
            "fetch('/sanctum/csrf-cookie',{credentials:'same-origin',signal:ctl.signal})" +
            ".then(function(r1){" +
            " var csrfStatus=r1.status,csrfMs=Math.round(performance.now()-t0);" +
            " b.onStage('login');var t1=performance.now();var tok=b.getFreshXsrf();" +
            " function post(token){return fetch('/login',{method:'POST',credentials:'same-origin',signal:ctl.signal," +
            "  headers:{'Accept':'application/json','Content-Type':'application/x-www-form-urlencoded','X-XSRF-TOKEN':token}," +
            "  body:'email='+encodeURIComponent(b.getEmail())+'&password='+encodeURIComponent(b.getPassword())+'&remember='});}" +
            " return post(tok).then(function(r2){" +
            "  var attempt=function(r){if(r.status===419){b.onStage('login-retry-fresh-raw');return post(b.getFreshXsrfRaw());}return Promise.resolve(r);};" +
            "  return attempt(r2).then(function(r3){" +
            "   var loginMs=Math.round(performance.now()-t1);" +
            "   var p=(r3.status===422)?r3.text():Promise.resolve('');" +
            "   return p.then(function(body){b.onStage('done');" +
            "    done({stage:'done',status:r3.status,csrfStatus:csrfStatus,csrfMs:csrfMs,loginMs:loginMs,finalUrl:(r3.url||''),body422:body});});" +
            "  });" +
            " });" +
            "})" +
            ".catch(function(e){done({stage:'fetch-io',err:((e&&e.name)||'FetchError')});});" +
            "}catch(ex){try{window.QolabBridge.onDone(JSON.stringify({stage:'bridge',err:'JsError'}));}catch(e2){}}})()";
}
