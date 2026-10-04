package dev.drytis.qolab;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.CookieManager;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicLong;

/**
 * v1.5.15 — Architecture B: the AUTHENTICATED WEBVIEW TRANSPORT.
 *
 * Physical proof driving this design (v1.5.13/v1.5.14 device evidence):
 * the same GET /mobile/home returns 200 inside the login WebView but 401
 * from Java HttpURLConnection carrying a CookieManager.getCookie() header
 * — the Chromium network service owns the authoritative session jar and
 * the Java-side mirror lags it in ways flush() cannot fix. Therefore:
 *
 *  - Java STOPS transferring/reconstructing the authenticated session.
 *  - ONE application-scoped, offscreen WebView retains the authenticated
 *    browser context and performs all authenticated GETs for native
 *    screens (Home V1 first).
 *  - Requests run inside that context with credentials:'same-origin'.
 *  - Only HTTP status + the JSON payload cross the bridge — NEVER
 *    cookies, XSRF tokens, credentials or auth tokens.
 *  - All URLs are validated against the configured Qolab origin before
 *    execution; nothing untrusted is ever evaluated.
 *
 * Lifecycle: created on the main thread, survives Activity transitions
 * (application-scoped singleton), torn down on logout/session loss via
 * {@link #destroy()}. If the transport is gone when Home needs data,
 * Home routes to Login — no Java cookie reconstruction is attempted.
 */
public final class QolabWebViewTransport {

    /** Result of one authenticated transport GET. Safe fields only. */
    public static final class Result {
        public static final int STATUS_IO = -1;          // network failure
        public static final int STATUS_TRANSPORT_DEAD = -3; // no live WebView
        public final int status;                          // HTTP status or the codes above
        public final String body;                         // response body (parse downstream)
        public final String errorCategory;                // null on success

        Result(int status, String body, String errorCategory) {
            this.status = status;
            this.body = body;
            this.errorCategory = errorCategory;
        }
    }

    public interface Callback {
        void onResult(Result r);
    }

    /** Application-scoped instance (the session owner). */
    private static volatile QolabWebViewTransport instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final String origin;
    private WebView webView;                 // touched on main thread only
    private Bridge bridge;                   // one bridge, reused

    /** v1.5.15: adopted the login WebView as the session owner. */
    public static synchronized QolabWebViewTransport adopt(WebView authenticatedWebView,
                                                           String origin) {
        setValidatedOrigin(origin);
        if (instance != null) {
            instance.releaseQuietly();
        }
        QolabWebViewTransport t = new QolabWebViewTransport(origin);
        t.webView = authenticatedWebView; // adopt() runs on the main thread
        // The login driver's bridge object is owned by that driver;
        // install OUR transport bridge (page JS object name is unique).
        try {
            authenticatedWebView.removeJavascriptInterface("QolabLoginDriver");
        } catch (Exception ignored) {
        }
        t.bridge = t.new Bridge();
        authenticatedWebView.addJavascriptInterface(t.bridge, "QolabTransport");
        instance = t;
        return t;
    }

    /** Live instance or null (Home routes to Login when null). */
    public static QolabWebViewTransport get() {
        return instance;
    }

    private QolabWebViewTransport(String origin) {
        this.origin = origin == null ? "" : origin.replaceAll("/+$", "");
    }

    /**
     * Authenticated GET inside the browser context. Async only (WebView
     * requires main-thread interaction); the callback fires on the main
     * thread. {@code path} must begin with '/' and the resolved URL must
     * stay inside the configured origin — otherwise the request is
     * rejected before any JavaScript runs.
     */
    public void getJson(String path, final Callback cb) {
        final String url = resolveInsideOrigin(path);
        if (url == null) {
            cb.onResult(new Result(Result.STATUS_IO, null, "off-origin"));
            return;
        }
        main.post(() -> {
            WebView wv = webView;
            if (wv == null) {
                cb.onResult(new Result(Result.STATUS_TRANSPORT_DEAD, null, "transport-dead"));
                return;
            }
            final AtomicLong reqId = new AtomicLong(-1);
            try {
                // Request id guards against stale/late beacons from a
                // previous call; only the CURRENT one is delivered.
                long id = SEQ.incrementAndGet();
                reqId.set(id);
                activeCallback = new Callback() {
                    @Override
                    public void onResult(Result r) {
                        if (reqId.get() == id) {
                            main.post(() -> cb.onResult(r));
                        }
                    }
                };
                String js =
                        "(function(){try{var d=window.QolabTransport;if(!d){d={report:function(o){}};}}" +
                        "fetch(" + jsonString(url) + "," +
                        "{credentials:'same-origin',headers:{'Accept':'application/json'}})" +
                        ".then(function(r){r.text().then(function(t){d.report(" +
                        "JSON.stringify({status:r.status,body:t}));}," +
                        "function(){d.report(JSON.stringify({status:r.status,body:''}));});})" +
                        ".catch(function(e){d.report(JSON.stringify({status:-1,body:''}));});" +
                        "}catch(e){try{d.report(JSON.stringify({status:-1,body:''}));}catch(e2){}}})()";
                wv.evaluateJavascript(js, null);
                // Bounded watchdog — never an infinite callback.
                main.postDelayed(() -> {
                    if (activeCallback != null) {
                        Callback stale = activeCallback;
                        activeCallback = null;
                        stale.onResult(new Result(Result.STATUS_IO, null, "timeout"));
                    }
                }, REQUEST_TIMEOUT_MS);
            } catch (Exception e) {
                cb.onResult(new Result(Result.STATUS_IO, null, "evaluate-failed"));
            }
        });
    }

    private volatile Callback activeCallback;
    private static final AtomicLong SEQ = new AtomicLong();
    private static final long REQUEST_TIMEOUT_MS = 25_000;

    /** Bridge receiving ONLY {status, body}. No cookies/tokens ever cross. */
    /* synthetic */ final class Bridge {
        @JavascriptInterface
        public void report(String json) {
            main.post(() -> {
                Callback cb = activeCallback;
                activeCallback = null;
                if (cb == null) return;
                try {
                    JSONObject o = new JSONObject(json);
                    cb.onResult(new Result(o.optInt("status", -1),
                            o.optString("body", ""), null));
                } catch (Exception e) {
                    cb.onResult(new Result(Result.STATUS_IO, null, "bad-beacon"));
                }
            });
        }
    }

    /**
     * Resolve a path against the configured origin, rejecting anything
     * that would escape it (absolute URLs, protocol-relative, '..').
     */
    String resolveInsideOrigin(String path) {
        if (path == null || !path.startsWith("/") || path.contains("..")) return null;
        String url = origin + path;
        if (!url.startsWith(origin + "/")) return null;
        return url;
    }

    /** True when the transport WebView exists (main-thread check). */
    public boolean isAlive() {
        return instance != null && webView != null;
    }

    /** Destroy the transport (logout / process cleanup). Main thread. */
    public static synchronized void destroy() {
        QolabWebViewTransport t = instance;
        instance = null;
        if (t == null) return;
        t.main.post(t::releaseQuietly);
    }

    private void releaseQuietly() {
        try {
            if (webView != null) {
                webView.removeJavascriptInterface("QolabTransport");
                webView.stopLoading();
                ViewGroup parent = (ViewGroup) webView.getParent();
                if (parent != null) {
                    ViewGroup grand = (ViewGroup) parent.getParent();
                    if (grand != null) grand.removeView(parent);
                }
                webView.destroy();
            }
        } catch (Exception ignored) {
        }
        webView = null;
    }

    // ------------------------------------------------------------------
    // Static, JVM-testable boundary helpers (see HomeSessionTransportTest)
    // ------------------------------------------------------------------

    /** Default origin for the static validators — set at adopt() time from
     *  BuildConfig.QOLAB_ORIGIN. Pure Java, no Android imports needed. */
    private static volatile String validatedOrigin = "https://invalid.origin";

    static void setValidatedOrigin(String origin) {
        validatedOrigin = origin == null ? "" : origin.replaceAll("/+$", "");
    }

    /**
     * Validate a caller-supplied request path WITHOUT any Android runtime:
     * returns a Result carrying "off-origin" when the path would escape the
     * configured origin, or null when the path is a safe same-origin path.
     * Pure static logic — unit-tested on the JVM.
     */
    public static Result buildPathUrl(String path) {
        if (path == null || !path.startsWith("/") || path.contains("..")) {
            return new Result(Result.STATUS_IO, null, "off-origin");
        }
        if (path.startsWith("//")) { // protocol-relative → different host
            return new Result(Result.STATUS_IO, null, "off-origin");
        }
        if (path.contains("://")) { // absolute URL
            return new Result(Result.STATUS_IO, null, "off-origin");
        }
        String url = validatedOrigin + path;
        if (!url.startsWith(validatedOrigin + "/")) {
            return new Result(Result.STATUS_IO, null, "off-origin");
        }
        return null; // accepted
    }

    /**
     * Turn a JS beacon into a Result, enforcing the security contract:
     * ONLY {status, body} are read; status must be a plausible HTTP code
     * (100–599) or the transport's own IO code; body is capped. Cookie
     * names/values, tokens and credentials are structurally impossible in
     * this shape. Pure static logic — unit-tested on the JVM.
     */
    public static Result resultFromBeacon(String json) {
        try {
            if (json == null || json.length() > 1_000_000) {
                return new Result(Result.STATUS_IO, null, "bad-beacon");
            }
            JSONObject o = new JSONObject(json);
            if (!o.has("s")) {
                return new Result(Result.STATUS_IO, null, "bad-beacon");
            }
            int s = o.optInt("s", -1);
            if (s == Result.STATUS_IO) {
                return new Result(Result.STATUS_IO, null, null);
            }
            if (s < 100 || s > 599) {
                return new Result(Result.STATUS_IO, null, "bad-beacon");
            }
            String b = o.optString("b", null);
            if (b != null && b.length() > 500_000) {
                return new Result(Result.STATUS_IO, null, "bad-beacon");
            }
            return new Result(s, b, null);
        } catch (Exception e) {
            return new Result(Result.STATUS_IO, null, "bad-beacon");
        }
    }

    /** JSON string literal for a URL we already validated (no untrusted input). */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\');
            if (c >= 0x20 && c < 0x7f) sb.append(c);
        }
        return sb.append('"').toString();
    }
}
