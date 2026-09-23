package dev.drytis.qolab;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/**
 * WebView-backed authentication bridge (v1.4.6).
 *
 * Authenticates the user through the EXISTING Laravel/Fortify browser
 * flow, executed inside a hidden WebView whose document origin is the
 * Development server. Cookies are therefore established by the platform
 * in the SAME global CookieManager that Capacitor/MainActivity use —
 * no Java-side cookie reconstruction, no manual CSRF jar.
 *
 * Security notes:
 *  - The WebView loads a minimal EMPTY document with the app origin as
 *    base URL — no remote page scripts run, so no third-party JS can
 *    observe the fetch or the bridge.
 *  - Credentials reach the fetch ONLY through a @JavascriptInterface
 *    bridge method call; they never appear in any evaluateJavascript
 *    source string, log, or persisted storage.
 *  - The bridge object is removed as soon as the flow completes and the
 *    WebView is destroyed.
 *  - CSRF, TLS and the Fortify contract are fully intact — this is a
 *    browser login, nothing weakened.
 *
 * Not unit-testable end-to-end (needs a real WebView + network); the
 * result parsing and URL classification have dedicated unit tests.
 */
public final class QolabWebViewAuth {

    /** Callbacks on the UI thread. */
    public interface Listener {
        void onAuthResult(Result r);
    }

    /**
     * Safe result — statuses, durations, final URL and (for 422 only)
     * the server's error body for friendly-message parsing. Never any
     * credential, cookie, or token value.
     */
    public static final class Result {
        public final boolean success;
        /** "csrf-io" | "csrf-cookie" | "login-io" | "done" */
        public final String stage;
        public final int csrfStatus;
        public final int loginStatus;
        public final long csrfMs;
        public final long loginMs;
        public final String finalUrl;
        /** Raw 422 body (Laravel JSON), or null. Used for the friendly message. */
        public final String errorBody;
        /** Exception-ish name reported by the fetch layer, or null. */
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
            String io = o.has("err") ? o.optString("err") : null;
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
            // unescape minimal JSON string escapes
            s = s.substring(1, s.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n");
        }
        return s;
    }

    /** The empty document — zero scripts, origin comes from base URL. */
    private static final String BLANK_DOC =
            "<!DOCTYPE html><html><head><meta charset='utf-8'></head><body></body></html>";

    // ── runtime ─────────────────────────────────────────────────────

    private final Context context;
    private final String origin; // no trailing slash
    private final String email;
    private final String password;
    private final Listener listener;
    private WebView webView;
    private boolean finished = false;

    public QolabWebViewAuth(Context context, String origin, String email,
                            String password, Listener listener) {
        this.context = context.getApplicationContext();
        this.origin = trimSlash(origin);
        this.email = email;
        this.password = password;
        this.listener = listener;
    }

    private static String trimSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void start() {
        webView = new WebView(context);
        webView.setVisibility(android.view.View.GONE);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setSavePassword(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);

        webView.addJavascriptInterface(new Bridge(), "QolabBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // never navigate anywhere — this is a headless tool
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(AUTH_JS, null);
            }
        });
        // Empty document with the app origin as base → same-origin fetch
        // privileges, no remote scripts in the document.
        webView.loadDataWithBaseURL(origin + "/", BLANK_DOC, "text/html", "utf-8", null);
    }

    public void destroy() {
        if (webView != null) {
            try {
                webView.removeJavascriptInterface("QolabBridge");
                webView.stopLoading();
                webView.destroy();
            } catch (Exception ignored) {
            }
            webView = null;
        }
    }

    private void deliver(final Result r) {
        if (finished) return;
        finished = true;
        final WebView wv = webView;
        if (wv != null) {
            wv.post(() -> {
                destroy();
                listener.onAuthResult(r);
            });
        }
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
     * Replicates the working web login exactly: csrf-cookie GET →
     * X-XSRF-TOKEN from document.cookie (decoded) → POST /login with the
     * platform attaching cookies natively. Same request Inertia makes.
     */
    private static final String AUTH_JS =
            "(function(){var b=window.QolabBridge;if(!b){b&&b.onDone('{\"stage\":\"bridge\"}');return;}" +
            "var t0=performance.now();" +
            "fetch('/sanctum/csrf-cookie',{credentials:'same-origin'})" +
            ".then(function(r1){" +
            " var csrfStatus=r1.status,csrfMs=Math.round(performance.now()-t0);" +
            " var m=document.cookie.match(/(?:^|;\\\\s*)XSRF-TOKEN=([^;]+)/);" +
            " if(!m){b.onDone(JSON.stringify({stage:'csrf-cookie',csrfStatus:csrfStatus,csrfMs:csrfMs}));return;}" +
            " var token=decodeURIComponent(m[1]);var t1=performance.now();" +
            " return fetch('/login',{method:'POST',credentials:'same-origin'," +
            "  headers:{'Accept':'application/json','Content-Type':'application/x-www-form-urlencoded','X-XSRF-TOKEN':token}," +
            "  body:'email='+encodeURIComponent(b.getEmail())+'&password='+encodeURIComponent(b.getPassword())+'&remember='})" +
            " .then(function(r2){" +
            "  var loginMs=Math.round(performance.now()-t1);" +
            "  var p=r2.status===422?r2.text():Promise.resolve('');" +
            "  return p.then(function(body){" +
            "   b.onDone(JSON.stringify({stage:'done',csrfStatus:csrfStatus,csrfMs:csrfMs,status:r2.status,loginMs:loginMs,finalUrl:r2.url||'',body422:body}));});" +
            " });" +
            "})" +
            ".catch(function(e){b.onDone(JSON.stringify({stage:'login-io',err:e&&e.name?e.name:'FetchError'}));});" +
            "})()";
}
