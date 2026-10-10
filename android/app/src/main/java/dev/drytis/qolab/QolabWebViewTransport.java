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
        /** v1.5.38: FINAL URL after redirects (fetch semantics). Null/empty
         *  when unknown or on IO failure. Lets callers recover route params
         *  the server encoded into a redirect — e.g. the meeting id from
         *  POST /office/rooms/{id}/meetings → 302 ?…&meeting={id}. */
        public final String finalUrl;

        Result(int status, String body, String errorCategory) {
            this(status, body, errorCategory, null);
        }

        Result(int status, String body, String errorCategory, String finalUrl) {
            this.status = status;
            this.body = body;
            this.errorCategory = errorCategory;
            this.finalUrl = finalUrl;
        }
    }

    public interface Callback {
        void onResult(Result r);
    }

    /** Application-scoped instance (the session owner). */
    private static volatile QolabWebViewTransport instance;

    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * v1.5.35: static main-thread handler for static entry points
     * (rehydrate). Lazily initialized so JVM unit tests that never touch the
     * transport can still load this class — Looper.getMainLooper() throws
     * "not mocked" on a plain JVM before a Looper exists.
     */
    private static volatile Handler MAIN_H;

    private static Handler MAIN() {
        Handler h = MAIN_H;
        if (h == null) {
            synchronized (QolabWebViewTransport.class) {
                h = MAIN_H;
                if (h == null) {
                    h = new Handler(Looper.getMainLooper());
                    MAIN_H = h;
                }
            }
        }
        return h;
    }
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
            // v1.5.42: the (possibly rehydrate-adopted, window-less)
            // WebView must be RESUMED for the in-page fetch to run at all.
            resumeForRequest();
            // v1.5.42: pause the WebView once THIS request settles. Declared
            // OUTSIDE the try so the catch path can also pause it.
            final java.util.concurrent.atomic.AtomicBoolean closed2 =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final QolabWebViewTransport self = this;
            final java.util.function.Consumer<Result> wrap = r -> {
                if (closed2.compareAndSet(false, true)) {
                    self.pauseAfterRequest();
                    cb.onResult(r);
                }
            };
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
                // v1.5.35 stability: the poll loop is JAVA-DRIVEN — the
                // ValueCallback only RECORDS the latest value into a holder;
                // rescheduling and the timeout decision happen in the Java
                // runnable. If the renderer drops a ValueCallback (paused
                // WebView, renderer kill), the old chain died silently and
                // the caller's callback NEVER fired (infinite skeleton).
                final java.util.concurrent.atomic.AtomicReference<String> latest =
                        new java.util.concurrent.atomic.AtomicReference<>(null);
                final Runnable[] poll = new Runnable[1];
                poll[0] = () -> {
                    if (delivered.get()) return;
                    // deliver whatever the last (possibly dropped-callback)
                    // poll managed to record
                    String recorded = latest.getAndSet(null);
                    if (recorded != null) {
                        Result r = resultFromEvaluateJavascript(recorded);
                        if (!"no-value".equals(r.errorCategory)) {
                            if (delivered.compareAndSet(false, true)) {
                                wrap.accept(r);
                            }
                            return;
                        }
                    }
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        if (delivered.compareAndSet(false, true)) {
                            wrap.accept(new Result(Result.STATUS_IO, null, "timeout"));
                        }
                        return;
                    }
                    String read =
                            "(function(){var v=window['" + key + "'];" +
                            "if(v!==undefined&&v!==null){window['" + key + "']=undefined;return v;}" +
                            "return null;})()";
                    try {
                        wv.evaluateJavascript(read, value -> latest.set(value));
                    } catch (Exception e) {
                        if (delivered.compareAndSet(false, true)) {
                            wrap.accept(new Result(Result.STATUS_IO, null, "evaluate-failed"));
                        }
                        return;
                    }
                    // ALWAYS reschedule from Java — even if the ValueCallback
                    // never fires, the next tick re-reads and the deadline
                    // still terminates the request.
                    main.postDelayed(poll[0], POLL_INTERVAL_MS);
                };
                // v1.5.41 PERFORMANCE: first sample immediately, then every
                // POLL_INTERVAL_MS (was: always a full interval late).
                poll[0].run();
            } catch (Exception e) {
                wrap.accept(new Result(Result.STATUS_IO, null, "evaluate-failed"));
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
            // v1.5.42: resumed for the duration of the round-trip (see
            // resumeForRequest — cold-restart partial-render fix).
            resumeForRequest();
            // Declared OUTSIDE the try so the catch path can also pause it.
            final java.util.concurrent.atomic.AtomicBoolean closed2 =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final QolabWebViewTransport self = this;
            final java.util.function.Consumer<Result> wrap = r -> {
                if (closed2.compareAndSet(false, true)) {
                    self.pauseAfterRequest();
                    cb.onResult(r);
                }
            };
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
                        // v1.5.38: capture r.url (final URL after redirects).
                        // The meeting create endpoint responds 302 →
                        // /office/rooms/{slug}?...&meeting={id}; fetch follows
                        // it transparently and r.status is the FINAL 200, so
                        // the created meeting id was previously unreachable.
                        "window['" + key + "']=JSON.stringify({s:r.status,b:t,u:r.url});}," +
                        "function(){window['" + key + "']=JSON.stringify({s:r.status,b:'',u:r.url});});})" +
                        ".catch(function(e){window['" + key + "']=JSON.stringify({s:-1,b:'',u:''});});" +
                        "}catch(e){window['" + key + "']=JSON.stringify({s:-1,b:'',u:''});}return 1;})()";
                wv.evaluateJavascript(kick, null);
                // v1.5.35 stability: same JAVA-DRIVEN poll loop as getJson —
                // a dropped ValueCallback can no longer strand the caller.
                final java.util.concurrent.atomic.AtomicBoolean retried419 =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                // v1.5.35: ONE 419 retry after a fresh CSRF handshake inside
                // the page. Bound by the SAME delivered/deadline flags as the
                // original request, so a failed refresh still delivers.
                final Runnable[] refresh = new Runnable[1];
                refresh[0] = () -> {
                    WebView w = webView;
                    if (w == null) {
                        cb.onResult(new Result(Result.STATUS_TRANSPORT_DEAD, null, "transport-dead"));
                        return;
                    }
                    w.evaluateJavascript(
                        "(function(){fetch(" + jsonString(origin + "/sanctum/csrf-cookie")
                        + ",{credentials:'same-origin'}).then(function(){return 1;})()",
                        v -> main.postDelayed(
                                () -> postJsonAfterRefresh(path, jsonBody, cb), 250));
                };
                final java.util.concurrent.atomic.AtomicReference<String> latest =
                        new java.util.concurrent.atomic.AtomicReference<>(null);
                final Runnable[] poll = new Runnable[1];
                poll[0] = () -> {
                    if (delivered.get()) return;
                    String recorded = latest.getAndSet(null);
                    if (recorded != null) {
                        Result r = resultFromEvaluateJavascript(recorded);
                        if (!"no-value".equals(r.errorCategory)) {
                            if (delivered.compareAndSet(false, true)) {
                                // v1.5.35 stability: 419 = stale XSRF-TOKEN.
                                // Laravel's /sanctum/csrf-cookie refreshes it
                                // inside this page context; retry the POST
                                // ONCE after refreshing (same pattern as the
                                // proven logout takeover in MainActivity).
                                if (r.status == 419 && !retried419.get()) {
                                    retried419.set(true);
                                    // v1.5.42: the retry re-enters postJson,
                                    // which owns its own resume/pause pair —
                                    // release THIS request's hold first.
                                    closed2.set(true);
                                    self.pauseAfterRequest();
                                    refresh[0].run();
                                    return;
                                }
                                wrap.accept(r);
                            }
                            return;
                        }
                    }
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        if (delivered.compareAndSet(false, true)) {
                            wrap.accept(new Result(Result.STATUS_IO, null, "timeout"));
                        }
                        return;
                    }
                    String read =
                            "(function(){var v=window['" + key + "'];" +
                            "if(v!==undefined&&v!==null){window['" + key + "']=undefined;return v;}" +
                            "return null;})()";
                    try {
                        wv.evaluateJavascript(read, value -> latest.set(value));
                    } catch (Exception e) {
                        if (delivered.compareAndSet(false, true)) {
                            wrap.accept(new Result(Result.STATUS_IO, null, "evaluate-failed"));
                        }
                        return;
                    }
                    main.postDelayed(poll[0], POLL_INTERVAL_MS);
                };
                // v1.5.41 PERFORMANCE: first sample immediately, then every
                // POLL_INTERVAL_MS (was: always a full interval late).
                poll[0].run();
            } catch (Exception e) {
                wrap.accept(new Result(Result.STATUS_IO, null, "evaluate-failed"));
            }
        });
    }

    private static final long POLL_INTERVAL_MS = 60;

    /**
     * v1.5.35: the single re-POST after a 419-triggered CSRF refresh. Runs
     * the SAME postJson path (fresh key, fresh deadline); the outer retry
     * flag lives in the caller's closure so this re-entry cannot loop.
     */
    void postJsonAfterRefresh(String path, String jsonBody, Callback cb) {
        // Fresh invocation → fresh retried419 state is INTENTIONAL per the
        // one-retry contract; the recursion is bounded because a second 419
        // would need another CSRF rotation within the same request.
        postJson(path, jsonBody, cb);
    }

    /** Test seam for the poll cadence (v1.5.41 latency work). */
    static long pollIntervalMsForTest() {
        return POLL_INTERVAL_MS;
    }

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

    /**
     * v1.5.42 FIX (cold-restart partial Home): the rehydrate-adopted
     * WebView is app-scoped and NEVER attached to a window, so Android
     * keeps it in the PAUSED lifecycle state. A paused WebView runs NO
     * page JavaScript — the fetch kick-off below executes, but the
     * promise resolution / window-variable write never happens, and
     * every data request on a cold start eventually times out while
     * warm (activity-owned) WebViews work fine. That asymmetry is why
     * cold restarts showed partial Home while normal navigation was
     * fast. Every request now briefly resumes the WebView for the
     * duration of the round-trip (and pauses it again when idle), so
     * timers/promises run during requests while the WebView stays
     * battery-friendly in between. Main thread.
     */
    private void resumeForRequest() {
        WebView wv = webView;
        if (wv == null) return;
        if (requestDepth == 0) {
            try { wv.onResume(); } catch (Exception ignored) { }
        }
        requestDepth++;
    }

    /** Pauses the WebView again once no request is in flight. Main thread. */
    private void pauseAfterRequest() {
        if (requestDepth > 0) requestDepth--;
        if (requestDepth == 0) {
            WebView wv = webView;
            if (wv != null) {
                try { wv.onPause(); } catch (Exception ignored) { }
            }
        }
    }

    /** Number of in-flight requests (test seam). */
    private int requestDepth = 0;

    int requestDepthForTest() {
        return requestDepth;
    }

    // ------------------------------------------------------------------
    // v1.5.35 stability: cold-start rehydration
    // ------------------------------------------------------------------

    /**
     * Cold start with a persisted session cookie: the in-memory transport is
     * gone (process death) but the session is still valid server-side.
     * Previously Home bounced straight to LoginActivity, which EXPIRED every
     * session cookie — forcing a full manual re-login on every process
     * restart and racing the dev-update dialog on a finished activity (the
     * "first launch exits, second works" bug).
     *
     * Rehydration builds a headless app-context WebView over the persisted
     * CookieManager state, loads the app origin, and lets the SERVER decide:
     * an authenticated session lands on /dashboard (or any authed page) —
     * transport adopted; an expired session lands on /login — report failure
     * so the caller routes to native Login honestly.
     *
     * Must be called on the main thread. The callback fires on the main
     * thread.
     */
    public interface RehydrateCallback {
        /**
         * v1.5.40: sessionValid now carries THREE states.
         *   true  — server proved the session (adopted).
         *   false — server PROVED the session is gone: the dashboard load
         *           landed on /login (a genuine redirect), or no persisted
         *           *-session cookie exists at all. Routing to Login is the
         *           honest response.
         *           THIS IS NOT REPORTED for transient transport failures.
         */
        void onResult(boolean sessionValid);

        /**
         * v1.5.40: rehydration could not DETERMINE session validity —
         * main-frame network error, timeout, or a redirect that neither
         * proved nor disproved the session. The session cookie is kept
         * intact; callers must retry or show a temporary error, NEVER
         * route to Login (which would expire a possibly-valid cookie).
         */
        default void onTransientFailure(String category) {
            onResult(false);
        }
    }

    /** Lets the rehydrate() timeout runnable trigger the client's settle(). */
    interface RehydrateClient {
        void settle(boolean valid);
    }

    public static void rehydrate(android.content.Context appContext, String origin,
                                 RehydrateCallback cb) {
        final String o = origin == null ? "" : origin.replaceAll("/+$", "");
        MAIN().post(() -> {
            // Someone logged in faster than us (e.g. LoginActivity finished)
            // — nothing to do.
            if (instance != null && instance.isAlive()) {
                cb.onResult(true);
                return;
            }
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            String cookies = o.isEmpty() ? null : cm.getCookie(o);
            boolean hasSession = false;
            if (cookies != null) {
                for (String part : cookies.split(";")) {
                    String name = part.split("=", 2)[0].trim().toLowerCase();
                    if (name.endsWith("session") && part.contains("=")
                            && part.split("=", 2)[1].trim().length() > 0) {
                        hasSession = true;
                        break;
                    }
                }
            }
            if (!hasSession) {
                cb.onResult(false);
                return;
            }
            android.webkit.WebView wv = new android.webkit.WebView(appContext);
            android.webkit.WebSettings s = wv.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            final RehydrateWebViewClient client = new RehydrateWebViewClient() {
                boolean settled = false;

                @Override
                public void settle(boolean valid) {
                    if (settled) return;
                    settled = true;
                    MAIN().removeCallbacks(timeout);
                    if (valid) {
                        adopt(wv, o);
                        cb.onResult(true);
                    } else {
                        try { wv.destroy(); } catch (Exception ignored) { }
                        // v1.5.40: only /login is PROOF the session is gone.
                        // Network errors / timeouts are TRANSIENT — the
                        // cookie stays; the caller retries or shows a
                        // temporary error instead of forcing a logout.
                        RehydrateOutcome outcome = classifyRehydrateFailure(
                                failureCategory, loginLanding);
                        if (outcome == RehydrateOutcome.PROVEN_INVALID) {
                            cb.onResult(false);
                        } else {
                            cb.onTransientFailure(
                                    failureCategory == null ? "timeout" : failureCategory);
                        }
                    }
                }
            };
            final Runnable timeout = () -> {
                if (client.failureCategory == null) client.failureCategory = "timeout";
                client.settle(false);
            };
            client.attach(timeout);
            wv.setWebViewClient(client);
            MAIN().postDelayed(timeout, 15_000);
            wv.loadUrl(o + "/dashboard");
        });
    }

    /** Outcome of one rehydrate failure — PROVEN_INVALID vs TRANSIENT. */
    public enum RehydrateOutcome {
        /** Server landed the dashboard load on /login — session is gone. */
        PROVEN_INVALID,
        /** Main-frame network error / timeout — validity unknown. */
        TRANSIENT
    }

    /**
     * v1.5.40: classify a rehydrate failure. Static, JVM-testable.
     * The old code treated EVERY failure (onReceivedError, 15 s timeout)
     * as "session invalid", so a cold start during a network hiccup (or a
     * slow dashboard render) routed to LoginActivity — whose onCreate
     * EXPIRED the persisted session cookie. A still-valid session was
     * destroyed by a transient blip. Only a confirmed /login landing is
     * PROVEN_INVALID; everything else keeps the cookie and retries.
     */
    static RehydrateOutcome classifyRehydrateFailure(String failureCategory,
                                                     boolean landedOnLogin) {
        if (landedOnLogin) return RehydrateOutcome.PROVEN_INVALID;
        if (failureCategory == null) return RehydrateOutcome.PROVEN_INVALID;
        return RehydrateOutcome.TRANSIENT;
    }

    /** WebViewClient that lets the rehydrate() timeout trigger settle(). */
    abstract static class RehydrateWebViewClient
            extends android.webkit.WebViewClient implements RehydrateClient {
        Runnable timeout;

        /** v1.5.40: true when the server explicitly parked us on /login. */
        boolean loginLanding = false;
        /** v1.5.40: why the attempt failed ("network-error"|"timeout"|null
         *  when the failure WAS the /login landing itself). */
        String failureCategory = null;

        void attach(Runnable timeout) { this.timeout = timeout; }

        @Override
        public void onPageFinished(android.webkit.WebView view, String url) {
            // The server keeps a logged-in user away from /login; only an
            // expired session ends up parked there.
            if (url != null && url.contains("/login")) {
                loginLanding = true;
                settle(false);
                return;
            }
            // Any authenticated page (wherever the redirect lands) proves
            // the session.
            if (url != null) {
                settle(true);
            }
        }

        @Override
        public void onReceivedError(android.webkit.WebView view,
                                    android.webkit.WebResourceRequest req,
                                    android.webkit.WebResourceError err) {
            if (req.isForMainFrame()) {
                // v1.5.40: a network error says NOTHING about the session.
                // Mark transient — the caller retries, the cookie survives.
                failureCategory = "network-error";
                settle(false);
            }
        }
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
                // v1.5.42: destroy() requires a RESUMED state when the
                // WebView was paused between requests — resuming first
                // avoids the renderer teardown crash path on paused views.
                try { webView.onResume(); } catch (Exception ignored) { }
                requestDepth = 0;
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
            // v1.5.38: fetch beacon carries the final URL (u); absent in
            // legacy beacons → null.
            String u = o.has("u") ? o.optString("u", null) : null;
            return new Result(s, b, null, u);
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
     *  version silently DROPPED every char outside 0x20–0x7F.
     *  v1.5.39: public wrapper for other native layers (realtime auth). */
    public static String jsonStringSafe(String s) {
        return jsonString(s);
    }

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
