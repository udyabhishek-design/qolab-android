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

    /** v1.5.15: adopted the login WebView as the session owner. */
    public static synchronized QolabWebViewTransport adopt(WebView authenticatedWebView,
                                                           String origin) {
        setValidatedOrigin(origin);
        if (instance != null) {
            instance.releaseQuietly();
        }
        QolabWebViewTransport t = new QolabWebViewTransport(origin);
        t.webView = authenticatedWebView; // adopt() runs on the main thread
        // v1.5.17: no addJavascriptInterface on the already-loaded page —
        // Android only exposes JS interfaces at PAGE-LOAD time, so an
        // interface added now is invisible to the current page (that was
        // the v1.5.16 Home data blackout). Requests now return through
        // evaluateJavascript's promise ValueCallback instead; the old
        // driver's interface is simply removed.
        try {
            authenticatedWebView.removeJavascriptInterface("QolabLoginDriver");
        } catch (Exception ignored) {
        }
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
     *
     * v1.5.18 FIX: evaluateJavascript's ValueCallback delivers the
     * SYNCHRONOUS return value only — a Promise return serializes to '{}'
     * and is never awaited (this was the v1.5.17 physical failure: every
     * request decoded as bad-beacon → IO → "Couldn't load"). And
     * addJavascriptInterface added after page load never reaches the
     * already-loaded page (the v1.5.16 failure). Neither channel works
     * here. Instead: the fetch KICK-OFF stores its result in a unique
     * window variable; a bounded POLL of synchronous-returning
     * evaluateJavascript expressions reads that variable — each poll
     * returns a real value ('null' while pending), so the result reaches
     * Java without any injection at all.
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
            try {
                final long id = SEQ.incrementAndGet();
                final String key = "__qolabT" + id; // numeric id → safe JS ident
                final java.util.concurrent.atomic.AtomicBoolean delivered =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                final long deadline = android.os.SystemClock.elapsedRealtime()
                        + REQUEST_TIMEOUT_MS;
                // Kick off: fetch stores {s,b} into window[key] when done.
                String kick =
                        "(function(){try{window['" + key + "']=null;" +
                        "fetch(" + jsonString(url) + "," +
                        "{credentials:'same-origin',headers:{'Accept':'application/json'}})" +
                        ".then(function(r){r.text().then(function(t){" +
                        "window['" + key + "']=JSON.stringify({s:r.status,b:t});}," +
                        "function(){window['" + key + "']=JSON.stringify({s:r.status,b:''});});})" +
                        ".catch(function(e){window['" + key + "']=JSON.stringify({s:-1,b:''});});" +
                        "}catch(e){window['" + key + "']=JSON.stringify({s:-1,b:''});}return 1;})()";
                wv.evaluateJavascript(kick, null);
                // Poll the window variable with a SYNCHRONOUS expression.
                final Runnable[] poll = new Runnable[1];
                poll[0] = () -> {
                    if (delivered.get()) return;
                    String read =
                            "(function(){var v=window['" + key + "'];" +
                            "if(v!==undefined&&v!==null){window['" + key + "']=undefined;return v;}" +
                            "return null;})()";
                    try {
                        wv.evaluateJavascript(read, value -> {
                            if (delivered.get()) return;
                            Result r = resultFromEvaluateJavascript(value);
                            if (!"no-value".equals(r.errorCategory)) {
                                if (delivered.compareAndSet(false, true)) {
                                    main.post(() -> cb.onResult(r));
                                }
                                return;
                            }
                            // still pending — bounded re-poll
                            if (android.os.SystemClock.elapsedRealtime() < deadline) {
                                main.postDelayed(poll[0], POLL_INTERVAL_MS);
                            } else if (delivered.compareAndSet(false, true)) {
                                cb.onResult(new Result(Result.STATUS_IO, null, "timeout"));
                            }
                        });
                    } catch (Exception e) {
                        if (delivered.compareAndSet(false, true)) {
                            cb.onResult(new Result(Result.STATUS_IO, null, "evaluate-failed"));
                        }
                    }
                };
                main.postDelayed(poll[0], POLL_INTERVAL_MS);
            } catch (Exception e) {
                cb.onResult(new Result(Result.STATUS_IO, null, "evaluate-failed"));
            }
        });
    }

    /**
     * v1.5.30: authenticated JSON POST through the session WebView
     * (Lobby composer). Same same-origin/eval safety rules as getJson;
     * body is JSON-escaped and never interpolated raw. Laravel CSRF is
     * satisfied by reading the XSRF-TOKEN cookie INSIDE the page and
     * echoing it as the X-XSRF-TOKEN header — the token never crosses
     * the bridge into Java.
     */
    public void postJson(String path, String jsonBody, final Callback cb) {
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
            try {
                final long id = SEQ.incrementAndGet();
                final String key = "__qolabT" + id;
                final java.util.concurrent.atomic.AtomicBoolean delivered =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                final long deadline = android.os.SystemClock.elapsedRealtime()
                        + REQUEST_TIMEOUT_MS;
                String kick =
                        "(function(){try{window['" + key + "']=null;" +
                        "var xsrf='';var m=document.cookie.match(/(?:^|;\\s*)XSRF-TOKEN=([^;]+)/);" +
                        "if(m){xsrf=decodeURIComponent(m[1].replace(/\\+/g,'%20'));}" +
                        "var h={'Accept':'application/json','Content-Type':'application/json'};" +
                        "if(xsrf)h['X-XSRF-TOKEN']=xsrf;" +
                        "fetch(" + jsonString(url) + "," +
                        "{method:'POST',credentials:'same-origin',headers:h," +
                        // jsonString() already emits a JS string literal of the
                        // serialized payload; JSON.stringify() around it would
                        // DOUBLE-encode (string scalar, not object) and the
                        // server would see no fields. Send the string as-is.
                        "body:" + jsonString(jsonBody == null ? "{}" : jsonBody) + "})" +
                        ".then(function(r){r.text().then(function(t){" +
                        "window['" + key + "']=JSON.stringify({s:r.status,b:t});}," +
                        "function(){window['" + key + "']=JSON.stringify({s:r.status,b:''});});})" +
                        ".catch(function(e){window['" + key + "']=JSON.stringify({s:-1,b:''});});" +
                        "}catch(e){window['" + key + "']=JSON.stringify({s:-1,b:''});}return 1;})()";
                wv.evaluateJavascript(kick, null);
                final Runnable[] poll = new Runnable[1];
                poll[0] = () -> {
                    if (delivered.get()) return;
                    String read =
                            "(function(){var v=window['" + key + "'];" +
                            "if(v!==undefined&&v!==null){window['" + key + "']=undefined;return v;}" +
                            "return null;})()";
                    try {
                        wv.evaluateJavascript(read, value -> {
                            if (delivered.get()) return;
                            Result r = resultFromEvaluateJavascript(value);
                            if (!"no-value".equals(r.errorCategory)) {
                                if (delivered.compareAndSet(false, true)) {
                                    main.post(() -> cb.onResult(r));
                                }
                                return;
                            }
                            if (android.os.SystemClock.elapsedRealtime() < deadline) {
                                main.postDelayed(poll[0], POLL_INTERVAL_MS);
                            } else if (delivered.compareAndSet(false, true)) {
                                cb.onResult(new Result(Result.STATUS_IO, null, "timeout"));
                            }
                        });
                    } catch (Exception e) {
                        if (delivered.compareAndSet(false, true)) {
                            cb.onResult(new Result(Result.STATUS_IO, null, "evaluate-failed"));
                        }
                    }
                };
                main.postDelayed(poll[0], POLL_INTERVAL_MS);
            } catch (Exception e) {
                cb.onResult(new Result(Result.STATUS_IO, null, "evaluate-failed"));
            }
        });
    }

    private static final long POLL_INTERVAL_MS = 250;

    private static final AtomicLong SEQ = new AtomicLong();
    private static final long REQUEST_TIMEOUT_MS = 25_000;

    /**
     * v1.5.17/18: decode an evaluateJavascript return value. The callback
     * receives the JS expression's result as a JSON-encoded string — either
     * a quoted string or the literal 'null'. 'null' (still pending / absent)
     * maps to the "no-value" IO category, which the poll loop treats as
     * "keep waiting", not an error. Static and JVM-testable.
     */
    public static Result resultFromEvaluateJavascript(String value) {
        if (value == null || "null".equals(value.trim())) {
            return new Result(Result.STATUS_IO, null, "no-value");
        }
        String decoded;
        try {
            // Unwrap the outer JSON string literal WebView hands us.
            decoded = new org.json.JSONTokener(value).nextValue().toString();
        } catch (Exception e) {
            return new Result(Result.STATUS_IO, null, "bad-return");
        }
        // decoded is now our {s:..,b:..} JSON text — reuse the beacon validator.
        return resultFromBeacon(decoded);
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
                try { webView.removeJavascriptInterface("QolabLoginDriver"); } catch (Exception ignored) { }
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

    /** Legacy-shaped beacon ({"status":N,"body":".."}) for the bridge. */
    static Result resultFromBeaconJson(String json) {
        try {
            JSONObject o = new JSONObject(json == null ? "" : json);
            int s = o.optInt("status", -1);
            String b = o.optString("body", null);
            if (s < 100 || s > 599) return resultFromBeacon(json);
            if (b != null && b.length() > 500_000) {
                return new Result(Result.STATUS_IO, null, "bad-beacon");
            }
            return new Result(s, b, null);
        } catch (Exception e) {
            return resultFromBeacon(json);
        }
    }

    /** JSON string literal for a URL we already validated (no untrusted input).
     *  Emits non-ASCII and control chars as \\uXXXX escapes — a previous
     *  version silently DROPPED every char outside 0x20–0x7F. */
    static String jsonString(String s) {  // package-private: JVM-testable
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c >= 0x20 && c < 0x7f) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04x", (int) c));
            }
        }
        return sb.append('"').toString();
    }
}
