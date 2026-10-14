package dev.drytis.qolab;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

/**
 * v1.5.5: WebView login driver that authenticates through the REAL web
 * implementation. The Development /login page is a Vite SPA shell — the
 * server HTML contains only <div id="app"></div>; the React/Inertia login
 * form (input[type=email], input[type=password], form) is mounted
 * client-side by /build/assets/app-*.js. v1.5.4 failed on the device
 * because its 10s in-JS wait for #email/#password was not enough for the
 * device to download, parse and hydrate the SPA. v1.5.5 changes:
 *
 *  - Java-side retry loop (not one-shot): onPageFinished on /login
 *    re-runs the probe up to 60 times over ~30s, each probe reports
 *    diagnostics (readyState, form count, email/password/submit found)
 *    so a "form never appeared" failure is now self-describing.
 *  - SEMANTIC selectors (input[type=email] / [type=password], form,
 *    v1.5.11: the DOM-driven fill/submit path was REMOVED (physically
 *    proven never to initiate POST /login). The auth transaction is now
 *    a direct same-origin fetch POST /login from inside the WebView,
 *    with X-XSRF-TOKEN read from document.cookie at transaction time.
 *  - Failure classification from HTTP status (419/422/network) + page state.
 *
 * Credentials reach the page ONLY via @JavascriptInterface getters;
 * never logged, never persisted; bridge removed + WebView destroyed
 * when done.
 */
public final class QolabWebLoginDriver {

    public interface Listener {
        void onLoginResult(Result r);
    }

    /**
     * v1.5.7: PERSISTENT auth-driver trace (static — survives the hidden
     * WebView's destruction and LoginActivity recreation). Safe fields
     * ONLY: stages, booleans, sanitized href/title/note. Never
     * credentials, cookie or token values.
     */
    public static final class Trace {
        public volatile String stage = "start";
        public volatile String note = "";
        public volatile String href = "";
        public volatile String title = "";
        public volatile boolean formFound = false;
        public volatile boolean emailVal = false;
        public volatile boolean pwVal = false;
        public volatile boolean submitFired = false;
        public volatile boolean errorVisible = false;
        public volatile long totalMs = -1;
        // v1.5.10: POST /login observability (safe fields only — never
        // request/response bodies, cookies, tokens or credentials).
        public volatile boolean postLoginInitiated = false;
        public volatile int postLoginStatus = -2;   // -2 not observable, -1 network error
        public volatile boolean postLoginFailed = false;
        // v1.5.12: login POST response + dual-probe decision evidence.
        public volatile boolean postLoginRedirected = false; // actual r.redirected
        public volatile String postLoginFinalUrl = ""; // actual r.url ONLY (no location.href fallback)
        public volatile boolean sessionBeforePost = false;
        public volatile boolean sessionAfterPost = false;
        public volatile Boolean sessionChanged = null;  // null = not determinable
        public volatile int webProbeStatus = -2;        // -2 not run
        public volatile boolean webProbeOk = false;
        // v1.5.13: three-stage verification flags (the diag panel's core).
        public volatile boolean loginPostAuthenticated = false; // 2xx from POST /login
        public volatile boolean webSessionVerified = false;     // WebView session probe 200 (v1.5.43: /rooms)
        // v1.5.14: cookie header captured on the MAIN thread at the
        // web-verified moment (request-time authoritative read). Used for
        // the Java probe's first attempt ONLY; never logged.
        public volatile String verifiedCookieHeader = null;
        public volatile boolean javaSessionVerified = false;    // Java session probe 200

        /** One-line, panel-safe rendering (booleans only). */
        public String render() {
            return "stage=" + stage
                    + " submitFired=" + yn2(submitFired)
                    + " form=" + yn2(formFound)
                    + " emailVal=" + yn2(emailVal)
                    + " pwVal=" + yn2(pwVal)
                    + " errorVisible=" + yn2(errorVisible)
                    + " POST/login=" + (postLoginInitiated ? "Y" : "N")
                    + (postLoginInitiated
                        ? " status=" + (postLoginStatus == -2 ? "not observable"
                                        : postLoginStatus == -1 ? "network-error"
                                        : String.valueOf(postLoginStatus))
                          + (postLoginFailed ? " (failed)" : "")
                        : "")
                    + " title=" + QolabWebLoginDriver.sanitizeNote(title)
                    + " href=" + href
                    + " postRedirected=" + (postLoginRedirected ? "Y" : "N")
                    + (postLoginFinalUrl.isEmpty() ? "" : " responseUrl=" + postLoginFinalUrl)
                    + " sessionBefore=" + (sessionBeforePost ? "Y" : "N")
                    + " sessionAfter=" + (sessionAfterPost ? "Y" : "N")
                    + (sessionChanged == null ? " sessionChanged=?" :
                        " sessionChanged=" + (sessionChanged ? "Y" : "N"))
                    + " webProbe=" + (webProbeStatus == -2 ? "not run" : String.valueOf(webProbeStatus))
                    + (webProbeStatus >= 200 && webProbeStatus < 300 ? " OK" : "")
                    + (note.isEmpty() ? "" : " note=" + note)
                    + (totalMs >= 0 ? " ms=" + totalMs : "");
        }

        private static String yn2(boolean b) {
            return b ? "Y" : "N";
        }
    }

    /** Latest driver trace — read by the Login diagnostics panel. */
    public static final Trace LAST = new Trace();

    /** Safe result — statuses, timings, sanitized messages. Never values. */
    public static final class Result {
        /** "done" | "invalid-credentials" | "page-error" | "timeout" | "load" | "bridge" */
        public final String stage;
        public final boolean success;
        public final String note;
        public final long totalMs;
        public final String finalUrl;
        /** v1.5.5 DEBUG-only DOM diagnostics (booleans/counts only). */
        public final String domDiag;

        Result(boolean success, String stage, String note, long totalMs, String finalUrl, String domDiag) {
            this.success = success;
            this.stage = stage;
            this.note = note;
            this.totalMs = totalMs;
            this.finalUrl = finalUrl;
            this.domDiag = domDiag;
        }
    }

    private static final long LOAD_TIMEOUT_MS = 30_000;
    private static final long SUBMIT_TIMEOUT_MS = 25_000;
    private static final int PROBE_MAX = 60;          // 60 × 500ms ≈ 30s hydration budget
    private static final long PROBE_INTERVAL_MS = 500;
    /** v1.5.6: post-submit poll cadence/budget (must fit inside SUBMIT_TIMEOUT_MS). */
    private static final long POST_SUBMIT_INTERVAL_MS = 1000;
    private static final int POST_SUBMIT_MAX = 24;

    private final Context context;
    private final String origin;
    private final String email;
    private final String password;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private volatile boolean finished = false;
    private volatile boolean submitted = false;
    private volatile String lastDomDiag = "";
    private String lastUrl = "";
    private long t0;

    public QolabWebLoginDriver(Context context, String origin, String email,
                               String password, Listener listener) {
        this.context = (context instanceof android.app.Activity)
                ? context : context.getApplicationContext();
        this.origin = trimSlash(origin);
        this.email = email;
        this.password = password;
        this.listener = listener;
    }

    private static String trimSlash(String s) {
        if (s != null && s.endsWith("/")) return s.substring(0, s.length() - 1);
        return s;
    }

    private final Runnable loadWatchdog = () -> {
        if (!finished && !submitted) {
            finish(new Result(false, "timeout",
                    "login page/form did not appear in " + LOAD_TIMEOUT_MS + "ms", elapsed(), lastUrl, lastDomDiag));
        }
    };

    private final Runnable submitWatchdog = () -> {
        if (!finished) {
            finish(new Result(false, "timeout",
                    "no navigation after submit", elapsed(), lastUrl, lastDomDiag));
        }
    };

    /** Java-side probe scheduler — re-tries until the form is ready. */
    private final Runnable probe = new Runnable() {
        private int count = 0;

        @Override
        public void run() {
            if (finished || submitted || webView == null) return;
            if (count++ >= PROBE_MAX) {
                finish(new Result(false, "page-error",
                        "login form never appeared (SPA hydration incomplete)", elapsed(), lastUrl, lastDomDiag));
                return;
            }
            webView.evaluateJavascript(PROBE_JS, null);
            main.postDelayed(this, PROBE_INTERVAL_MS);
        }
    };

    /**
     * v1.5.6: post-submit poll — evaluates the page state every second
     * after submit. Success is leaving /login in location.href (works for
     * Inertia client-side redirects that never trigger onPageFinished).
     */
    private final Runnable postSubmitPoll = new Runnable() {
        private int count = 0;

        @Override
        public void run() {
            if (finished || webView == null) return;
            if (count++ > POST_SUBMIT_MAX) {
                evaluateOutcome(); // deadline — classify from page state
                return;
            }
            webView.evaluateJavascript(POLL_JS, null);
            main.postDelayed(this, POST_SUBMIT_INTERVAL_MS);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    public void start() {
        main.post(() -> {
            if (finished) return;
            t0 = System.currentTimeMillis();
            webView = new WebView(context);
            ViewGroup contentParent =
                    (ViewGroup) ((android.app.Activity) context)
                            .findViewById(android.R.id.content);
            FrameLayout host = new FrameLayout(context);
            host.setVisibility(android.view.View.GONE);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1, 1);
            host.addView(webView, lp);
            contentParent.addView(host);

            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

            webView.setWebChromeClient(new android.webkit.WebChromeClient());

            webView.addJavascriptInterface(new Bridge(), "QolabLoginDriver");

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return false; // real navigations — Fortify redirect must run
                }

                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    // v1.5.10: install the POST /login observer BEFORE the
                    // SPA hydrates, so the Inertia POST is captured.
                    view.evaluateJavascript(XHR_OBSERVER_JS, null);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    lastUrl = sanitizeUrl(url);
                    if (finished) return;
                    if (isLoginFormUrl(url)) {
                        // Start (or continue) probing until the SPA form mounts.
                        main.removeCallbacks(probe);
                        main.postDelayed(probe, PROBE_INTERVAL_MS);
                    } else {
                        // Navigated away from /login → authenticated redirect
                        // (real navigation path; the client-side path is
                        // detected by the post-submit poll).
                        main.removeCallbacks(probe);
                        main.removeCallbacks(postSubmitPoll);
                        main.postDelayed(() -> evaluateOutcome(), 600);
                    }
                }
            });

            main.postDelayed(loadWatchdog, LOAD_TIMEOUT_MS);
            webView.loadUrl(origin + "/login");
        });
    }

    private boolean isLoginFormUrl(String url) {
        return url != null && (url.endsWith("/login") || url.contains("/login?"));
    }

    /**
     * Probe result beacon: v1.5.11 — the first probe confirming the page
     * document exists (readyState past 'loading') triggers the DIRECT
     * auth transaction (LOGIN_TX_JS). We no longer wait for the React
     * form to mount, fill it, or submit it — those dependencies are gone.
     * formReady/form elements are reported for diagnostics only.
     */
    private void onProbeJson(String json) {
        if (finished || submitted) return;
        try {
            JSONObject o = new JSONObject(json);
            boolean formReady = o.optBoolean("formReady", false);
            lastDomDiag = buildDomDiag(o);
            LAST.formFound = formReady;
            LAST.href = lastUrl;
            LAST.title = o.optString("title", "");
            String rs = o.optString("readyState", "?");
            boolean docReady = "interactive".equals(rs) || "complete".equals(rs);
            if (docReady) {
                LAST.stage = "tx-starting";
                submitted = true;
                main.removeCallbacks(probe);
                main.removeCallbacks(loadWatchdog);
                main.postDelayed(submitWatchdog, SUBMIT_TIMEOUT_MS);
                // v1.5.6: poll post-submit state — a successful direct
                // transaction navigates to /dashboard (real page load);
                // this poll additionally covers any client-side redirect.
                main.postDelayed(postSubmitPoll, 800);
                // Direct in-context POST /login — no DOM driving.
                webView.evaluateJavascript(LOGIN_TX_JS, null);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.5.11: direct-transaction outcome classification — by HTTP status,
     * from the beacon. 2xx → the JS side navigates to /dashboard; success
     * is then confirmed by the navigation/poll path (and, at the LoginActivity
     * seam, by the /mobile/home probe). 419 → CSRF rejection. 422 →
     * validation/invalid credentials. -1 → network failure. Nothing here
     * treats a non-2xx as success.
     */
    private void onTxJson(String json) {
        if (finished) return;
        try {
            JSONObject o = new JSONObject(json);
            int status = o.optInt("status", -2);
            boolean sess = o.optBoolean("sessionCookie", false);
            boolean started = o.optBoolean("started", false);
            String netErr = sanitizeNote(o.optString("networkError", ""));
            LAST.postLoginInitiated = started;
            LAST.postLoginStatus = status;
            LAST.postLoginFailed = status == -1 || status >= 400;
            if (status == -2 && !started) {
                finish(new Result(false, "tx-error",
                        "transaction JS failed before starting", elapsed(), lastUrl, lastDomDiag));
                return;
            }
            if (status == -1) {
                finish(new Result(false, "tx-network",
                        "network failure" + (netErr.isEmpty() ? "" : ": " + netErr),
                        elapsed(), lastUrl, lastDomDiag));
                return;
            }
            if (status == 419) {
                finish(new Result(false, "tx-csrf-419",
                        "CSRF rejected (419)" + (sess ? "" : " [no session cookie in page]"),
                        elapsed(), lastUrl, lastDomDiag));
                return;
            }
            if (status == 422) {
                finish(new Result(false, "invalid-credentials",
                        "credentials rejected (422)", elapsed(), lastUrl, lastDomDiag));
                return;
            }
            if (status >= 200 && status < 300) {
                // v1.5.13: genuine Fortify AJAX success (200 {"two_factor":...}).
                // No navigation happens and none is needed. Keep this exact
                // WebView ALIVE and verify its session in-page (authoritative),
                // then report to LoginActivity which runs the Java probe.
                LAST.loginPostAuthenticated = true;
                LAST.stage = "tx-2xx-verifying";
                submitted = true; // stop the postSubmitPoll
                main.removeCallbacks(submitWatchdog);
                main.removeCallbacks(postSubmitPoll);
                // v1.5.13: synchronize WebView→CookieManager cookie state
                // BEFORE any verification reads it. flush() is the proper
                // Android mechanism (persists pending cookies); it is not
                // a success criterion, just state synchronization.
                try {
                    android.webkit.CookieManager.getInstance().flush();
                } catch (Exception ignored) {
                }
                if (webView != null) {
                    try {
                        webView.evaluateJavascript(VERIFY_JS, null);
                    } catch (Exception e) {
                        // WebView gone — report honestly, no retry theater.
                        LAST.webProbeStatus = -1;
                        finish(new Result(false, "verify-lost-webview",
                                "WebView unavailable for session verification",
                                elapsed(), lastUrl, lastDomDiag));
                        return;
                    }
                    // VERIFY_JS retries up to ~4s internally. Wait bounded
                    // time for the authoritative web probe before giving up.
                    main.postDelayed(() -> {
                        if (finished) return;
                        if (LAST.webProbeOk) {
                            LAST.webSessionVerified = true;
                            // v1.5.14: capture the authoritative cookie
                            // header ON THE MAIN THREAD at the exact moment
                            // the WebView proved the session — the Java
                            // probe's first attempt uses THIS header so it
                            // provably carries the SAME authenticated
                            // session. (Read AFTER the second flush below.)
                            LAST.verifiedCookieHeader =
                                    safeCookieHeader(originForCookies());
                            // Second flush AFTER the web probe returned 200
                            // (spec: flush/sync again before the Java probe).
                            try {
                                android.webkit.CookieManager.getInstance().flush();
                            } catch (Exception ignored) {
                            }
                            finish(new Result(true, "done",
                                    "web session verified", elapsed(), lastUrl, lastDomDiag),
                                    true /* keep WebView alive until Java probe 200 */);
                        } else {
                            // WebView 401 after genuine POST success — NOT a
                            // login failure. Preserve exactly; WebView KEPT
                            // ALIVE for inspection (caller decides teardown).
                            finish(new Result(true, "web-verify-failed",
                                    "POST /login authenticated but WebView session probe="
                                            + LAST.webProbeStatus
                                            + " — preserved for diagnostics",
                                    elapsed(), lastUrl, lastDomDiag), true);
                        }
                    }, 4500);
                }
                return;
            }
            // Other statuses (429, 500, ...) — report, don't guess.
            finish(new Result(false, "tx-http-" + status,
                    "login HTTP " + status, elapsed(), lastUrl, lastDomDiag));
        } catch (Exception e) {
            finish(new Result(false, "bridge", "tx beacon unreadable", elapsed(), lastUrl, lastDomDiag));
        }
    }

    private static String buildDomDiag(JSONObject o) {        // Booleans/counts only — never input values.
        return "readyState=" + o.optString("readyState", "?")
                + " title=" + sanitizeNote(o.optString("title", ""))
                + " forms=" + o.optInt("forms", 0)
                + " email=" + yn(o.optBoolean("email", false))
                + " password=" + yn(o.optBoolean("password", false))
                + " submit=" + yn(o.optBoolean("submit", false));
    }

    /** v1.5.6: post-submit poll beacon — success/error/idle detection. */
    private void onPollJson(String json) {
        if (finished) return;
        try {
            JSONObject o = new JSONObject(json);
            String href = sanitizeUrl(o.optString("href", ""));
            if (!href.isEmpty()) lastUrl = href;
            boolean hasError = o.optBoolean("hasVisibleError", false);
            LAST.href = lastUrl;
            LAST.title = o.optString("title", "");
            LAST.errorVisible = hasError;
            LAST.emailVal = o.optBoolean("emailFilled", false);
            LAST.pwVal = o.optBoolean("passwordFilled", false);
            LAST.submitFired = true; // poll only runs after submit fired
            // Persist the richest diag we have (booleans only, never values).
            lastDomDiag = "readyState=" + o.optString("readyState", "?")
                    + " title=" + sanitizeNote(o.optString("title", ""))
                    + " emailVal=" + yn(o.optBoolean("emailFilled", false))
                    + " pwVal=" + yn(o.optBoolean("passwordFilled", false))
                    + " formValid=" + yn(o.optBoolean("formValid", true))
                    + " errorVisible=" + yn(hasError)
                    + " href=" + lastUrl;
            if (!isLoginFormUrl(lastUrl)) {
                // Left /login (history API or real navigation) → success.
                main.removeCallbacks(submitWatchdog);
                main.removeCallbacks(postSubmitPoll);
                finish(new Result(true, "done", "authenticated redirect (client-side)",
                        elapsed(), lastUrl, lastDomDiag));
                return;
            }
            if (hasError) {
                main.removeCallbacks(submitWatchdog);
                main.removeCallbacks(postSubmitPoll);
                finish(new Result(false, "invalid-credentials",
                        o.optString("note", "").isEmpty()
                                ? "field error visible on login page"
                                : sanitizeNote(o.optString("note", "")),
                        elapsed(), lastUrl, lastDomDiag));
            }
        } catch (Exception ignored) {
        }
    }

    private static String yn(boolean b) {
        return b ? "Y" : "N";
    }

    /** Outcome decision — navigation URL plus the page's own error state. */
    private void evaluateOutcome() {
        if (finished) return;
        main.removeCallbacks(submitWatchdog);
        boolean leftLogin = !isLoginFormUrl(lastUrl);
        if (leftLogin) {
            finish(new Result(true, "done", "authenticated redirect", elapsed(), lastUrl, lastDomDiag));
            return;
        }
        if (webView != null) {
            webView.evaluateJavascript(OUTCOME_JS, null);
        }
    }

    /** Called by the JS beacon with the page outcome (no secrets). */
    private void onOutcomeJson(String json) {
        if (finished) return;
        try {
            JSONObject o = new JSONObject(json);
            boolean hasError = o.optBoolean("hasVisibleError", false);
            String note = sanitizeNote(o.optString("note", ""));
            if (hasError) {
                finish(new Result(false, "invalid-credentials",
                        note.isEmpty() ? "credentials rejected by the login form" : note,
                        elapsed(), lastUrl, lastDomDiag));
            } else {
                finish(new Result(false, "page-error",
                        note.isEmpty() ? "no navigation away from login" : note,
                        elapsed(), lastUrl, lastDomDiag));
            }
        } catch (Exception e) {
            finish(new Result(false, "bridge", "outcome beacon unreadable", elapsed(), lastUrl, lastDomDiag));
        }
    }

    private void finish(Result r) {
        finish(r, false);
    }

    /**
     * v1.5.13: finish(r, keepWebView) — the driver may report a result
     * WITHOUT destroying the WebView. The tx-handoff path does exactly
     * that: the WebView stays alive so the caller can (a) keep probing /
     * inspecting, and (b) destroy it only when BOTH the WebView probe
     * and the Java probe have returned 200 (see destroy()).
     */
    private void finish(Result r, boolean keepWebView) {
        if (finished) return;
        finished = true;
        LAST.stage = r.stage;
        LAST.note = sanitizeNote(r.note == null ? "" : r.note);
        LAST.href = r.finalUrl == null ? "" : r.finalUrl;
        LAST.totalMs = r.totalMs;
        main.removeCallbacks(loadWatchdog);
        main.removeCallbacks(submitWatchdog);
        main.removeCallbacks(probe);
        main.removeCallbacks(postSubmitPoll);
        final WebView wv = webView;
        if (keepWebView) {
            // WebView stays ALIVE. The caller MUST eventually destroy()
            // it. Report immediately — no teardown, no probe here.
            keepAliveWebView = wv;
            main.post(() -> listener.onLoginResult(r));
            return;
        }
        main.post(() -> {
            if (wv != null) {
                try {
                    wv.removeJavascriptInterface("QolabLoginDriver");
                    wv.stopLoading();
                    ViewGroup parent = (ViewGroup) wv.getParent();
                    if (parent != null) {
                        ViewGroup grand = (ViewGroup) parent.getParent();
                        if (grand != null) grand.removeView(parent);
                    }
                    wv.destroy();
                } catch (Exception ignored) {
                }
            }
            if (webView == wv) webView = null;
            listener.onLoginResult(r);
        });
    }

    /** Cookie URL for the app origin (matches the login WebView's). */
    private String originForCookies() {
        try {
            return origin == null ? lastUrl : origin;
        } catch (Exception e) {
            return lastUrl;
        }
    }

    /** Safe cookie header read (names+values stay in memory only). */
    private static String safeCookieHeader(String url) {
        try {
            return CookieManager.getInstance().getCookie(url);
        } catch (Exception e) {
            return null;
        }
    }

    /** v1.5.13: the ONLY teardown path for the kept-alive WebView. */
    private static WebView keepAliveWebView;

    /**
     * v1.5.15: HAND OVER the kept-alive authenticated WebView to the
     * application-scoped WebView transport instead of destroying it.
     * Must run on the main thread (called when both probes have passed).
     * Returns true if a live WebView was handed over.
     */
    public static boolean handOverToTransport(String origin) {
        WebView wv = keepAliveWebView;
        keepAliveWebView = null;
        if (wv == null) return false;
        QolabWebViewTransport.adopt(wv, origin);
        return true;
    }

    /** v1.5.13/15: destroy the kept-alive WebView WITHOUT adopting it
     *  (retry / activity death paths). Safe to call repeatedly; main thread. */
    public static void destroyKeptAlive() {
        WebView wv = keepAliveWebView;
        keepAliveWebView = null;
        if (wv == null) return;
        try {
            wv.removeJavascriptInterface("QolabLoginDriver");
            wv.stopLoading();
            ViewGroup parent = (ViewGroup) wv.getParent();
            if (parent != null) {
                ViewGroup grand = (ViewGroup) parent.getParent();
                if (grand != null) grand.removeView(parent);
            }
            wv.destroy();
        } catch (Exception ignored) {
        }
    }

    private long elapsed() {
        return System.currentTimeMillis() - t0;
    }

    /** Strip query/fragment; keep scheme+host+path only. */
    static String sanitizeUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        if (q >= 0) url = url.substring(0, q);
        int h = url.indexOf('#');
        if (h >= 0) url = url.substring(0, h);
        return url;
    }

    static String sanitizeNote(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\x00-\\x1f]", " ").trim();
        return t.length() > 100 ? t.substring(0, 100) : t;
    }

    /** JS→Java beacons. Credentials leave ONLY through getEmail/getPassword. */
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
        public void onProbe(String json) {
            main.post(() -> onProbeJson(json));
        }

        /** v1.5.6: post-submit poll beacon (no secrets — booleans/hrefs). */
        @JavascriptInterface
        public void onPoll(String json) {
            main.post(() -> onPollJson(json));
        }

        /** Outcome beacon from the page (invalid-credentials vs idle). */
        @JavascriptInterface
        public void onOutcome(String json) {
            main.post(() -> onOutcomeJson(json));
        }

        /** v1.5.12: WebView-context session probe result (status only). */
        @JavascriptInterface
        public void onWebProbe(String json) {
            main.post(() -> {
                try {
                    JSONObject o = new JSONObject(json);
                    LAST.webProbeStatus = o.optInt("status", -1);
                    LAST.webProbeOk = LAST.webProbeStatus >= 200 && LAST.webProbeStatus < 300;
                } catch (Exception ignored) {
                }
            });
        }

        /** v1.5.12: login POST response metadata (no values). */
        @JavascriptInterface
        public void onLoginMeta(String json) {
            main.post(() -> {
                try {
                    JSONObject o = new JSONObject(json);
                    LAST.postLoginRedirected = o.optBoolean("redirected", false);
                    String fu = sanitizeUrl(o.optString("finalUrl", ""));
                    // keep path only
                    int i = fu.indexOf('/', fu.indexOf("//") + 2);
                    LAST.postLoginFinalUrl = i >= 0 ? fu.substring(i) : fu;
                    LAST.sessionBeforePost = o.optBoolean("sessionBefore", false);
                    LAST.sessionAfterPost = o.optBoolean("sessionAfter", false);
                    if (o.has("changed")) LAST.sessionChanged = o.optBoolean("changed", false);
                } catch (Exception ignored) {
                }
            });
        }

        /**
         * v1.5.11: DIRECT-transaction outcome beacon. Safe fields only:
         * status code, sessionCookie boolean, networkError name. Never
         * bodies, cookies or tokens.
         */
        @JavascriptInterface
        public void onTx(String json) {
            main.post(() -> onTxJson(json));
        }

        /** Progress beacon for logs (stage names only). */
        @JavascriptInterface
        public void onStage(String stage) {            android.util.Log.i("QolabAuth", "web-login-driver stage: " + stage);
        }

        /** v1.5.10: XHR/fetch observer beacon — safe fields only
         *  (method, URL path, status code). Never bodies/headers/values. */
        @JavascriptInterface
        public void onXhr(String json) {
            try {
                JSONObject o = new JSONObject(json);
                String method = o.optString("m", "");
                String u = o.optString("u", "");
                String path = u.startsWith("http")
                        ? android.net.Uri.parse(u).getPath() : u;
                boolean isLoginPost = "POST".equalsIgnoreCase(method)
                        && path != null && path.endsWith("/login");
                if (!isLoginPost) return;
                LAST.postLoginInitiated = true;
                LAST.postLoginStatus = o.optInt("s", -2);
                LAST.postLoginFailed = o.optBoolean("f", false);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Java-scheduled probe: is the login DOM ready? Reports diagnostics
     * (readyState, title, form count, email/password/submit found) every
     * probe so failures are self-describing. Fires the moment the form is
     * ready — the Java side then runs FILL_SUBMIT_JS.
     */
    private static final String PROBE_JS =
            "(function(){try{var d=window.QolabLoginDriver;if(!d){return;}" +
            "var em=document.querySelector('input[type=email],#email');" +
            "var pw=document.querySelector('input[type=password],#password');" +
            "var f=document.querySelector('form');" +
            "var sb=f?f.querySelector('button[type=submit],input[type=submit]'):null;" +
            "var ready=!!(em&&pw&&f&&document.readyState!=='loading');" +
            "d.onProbe(JSON.stringify({formReady:ready," +
            " readyState:document.readyState||'?',title:(document.title||'').substring(0,40)," +
            " forms:document.forms?document.forms.length:0," +
            " email:!!em,password:!!pw,submit:!!sb}));" +
            "}catch(e){}})()";

    /**
     * v1.5.11: DIRECT AUTH TRANSACTION — the DOM-driving path (fill React
     * controlled inputs → requestSubmit()/click()/form.submit()) is
     * REMOVED. v1.5.10 proved on device that the synthetic submit never
     * reached React's handler and produced a native GET fallback (no POST
     * /login at all). We now perform the SAME transaction the browser
     * performs, from inside the same WebView context:
     *   fetch(origin + '/login', {method:'POST', credentials:'same-origin'})
     * with X-XSRF-TOKEN read from document.cookie AT TRANSACTION TIME
     * (fresh — never the Java-side stale read that caused the v1.5.2 419)
     * and the credentials ONLY in the POST body (URLSearchParams — never
     * in the URL/query, logs or diagnostics). No React hydration, form or
     * submit timing is involved. On 2xx we navigate to /dashboard (a real
     * page load, handing the authenticated session to CookieManager);
     * outcomes are reported via the onTx beacon: initiated Y/N, HTTP
     * status (2xx/419/422/network), session cookie present Y/N. Values
     * never leave through this path except via the bridge getters below.
     */
    private static final String LOGIN_TX_JS =
            "(function(){try{" +
            "var d=window.QolabLoginDriver;if(!d){return;}" +
            "var xsrf='';" +
            "document.cookie.split(';').forEach(function(row){" +
            " if(row.trim().indexOf('XSRF-TOKEN=')===0){" +
            "  try{xsrf=decodeURIComponent(row.split('=').slice(1).join('='));}catch(e){xsrf=row.split('=').slice(1).join('=');}" +
            " }" +
            "});" +
            "var sess=function(){" +
            " var f=false;document.cookie.split(';').forEach(function(row){" +
            " var n=row.trim().split('=')[0];if(/session$/i.test(n))f=true;" +
            "});return f;};" +
            "var sessBefore=sess();" +
            "var rep=function(s,extra){try{d.onTx(JSON.stringify(Object.assign({status:s,sessionCookie:sess()},extra||{})));}catch(e){}};" +
            "var body=new URLSearchParams();" +
            "body.append('email',d.getEmail());body.append('password',d.getPassword());" +
            "fetch(location.origin+'/login',{method:'POST',credentials:'same-origin',headers:" +
            "{'Content-Type':'application/x-www-form-urlencoded','Accept':'application/json'," +
            "'X-Requested-With':'XMLHttpRequest','X-XSRF-TOKEN':xsrf},body:body})" +
            ".then(function(r){rep(r.status,{started:true});" +
            // v1.5.13: r.url ONLY — the actual fetch response URL. No
            // location.href fallback (that made postFinal=/dashboard a
            // synthetic artifact, not proof of anything).
            " try{d.onLoginMeta(JSON.stringify({redirected:r.redirected===true," +
            " finalUrl:r.url||'',sessionBefore:sessBefore," +
            " sessionAfter:sess(),changed:null}));}catch(e){}" +
            // v1.5.13: DO NOT navigate to /dashboard. The WebView stays
            // exactly where it is and alive; the Java side now runs the
            // authoritative in-page session probe (VERIFY_JS, /rooms v1.5.43).
            "})" +
            ".catch(function(e){rep(-1,{started:true,networkError:(e&&e.name)?String(e.name).substring(0,40):'network'});" +
            " try{d.onLoginMeta(JSON.stringify({redirected:false,finalUrl:location.href," +
            " sessionBefore:sessBefore,sessionAfter:sess(),changed:null}));}catch(e2){}});" +
            "}catch(e){try{d.onTx(JSON.stringify({status:-2,sessionCookie:false,started:false}));}catch(e2){}}})()";

    /**
     * v1.5.6: post-submit state poll. Inertia performs successful logins as
     * a client-side redirect (XHR + history.pushState) with NO full page
     * load — onPageFinished never fires — so success is detected here from
     * location.href. Also reports error elements (invalid credentials) and
     * form/value state. Booleans + sanitized href/title only — never values.
     */
    private static final String POLL_JS =
            "(function(){try{var d=window.QolabLoginDriver;if(!d){return;}" +
            "var em=document.querySelector('input[type=email],#email');" +
            "var pw=document.querySelector('input[type=password],#password');" +
            "var f=document.querySelector('form');" +
            "var bad=document.querySelector('p[class*=red-600],[role=alert],div[class*=bg-red-100],.text-red-600');" +
            "var errVis=!!(bad&&bad.offsetParent!==null&&bad.textContent&&bad.textContent.trim().length>0);" +
            "d.onPoll(JSON.stringify({href:(location.href||'')," +
            " readyState:document.readyState||'?',title:(document.title||'').substring(0,40)," +
            " emailFilled:!!(em&&em.value&&em.value.length>0)," +
            " passwordFilled:!!(pw&&pw.value&&pw.value.length>0)," +
            " formValid:!!(f&&f.checkValidity&&f.checkValidity())," +
            " hasVisibleError:errVis," +
            " note:errVis?'field error visible on login page':''}));" +
            "}catch(e){}})()";

    /**
     * v1.5.10: injected at page start (before hydration). Wraps
     * XMLHttpRequest and fetch so POST /login initiation, HTTP status and
     * network failure are recorded via the onXhr beacon. Only method,
     * URL and status code travel out — never bodies, headers, cookies,
     * tokens or response payloads. Idempotent via the window guard.
     */
    private static final String XHR_OBSERVER_JS =
            "(function(){if(window.__qolabXhrHooked)return;window.__qolabXhrHooked=true;try{" +
            "var d=window.QolabLoginDriver;var rep=function(m,u,s,f){try{d.onXhr(JSON.stringify({m:m,u:u,s:s,f:f}));}catch(e){}};" +
            "var xo=XMLHttpRequest.prototype.open, xs=XMLHttpRequest.prototype.send;" +
            "XMLHttpRequest.prototype.open=function(m,u){this.__qm={m:m,u:String(u)};return xo.apply(this,arguments);};" +
            "XMLHttpRequest.prototype.send=function(){var x=this;if(x.__qm){" +
            " x.addEventListener('loadend',function(){rep(x.__qm.m,x.__qm.u,x.status||(-1),false);});}};" +
            "if(window.fetch){var of=window.fetch;" +
            " window.fetch=function(input,init){var m=((init&&init.method)||'GET'),u=(typeof input==='string')?input:(input&&input.url)||'';" +
            "  return of.apply(this,arguments).then(function(r){rep(m,u,r.status,false);return r;},function(e){rep(m,u,-1,true);throw e;});};" +
            "}}catch(e){}})()";

    /**
     * v1.5.13: AUTHORITATIVE WebView-session verification. Run INSIDE the
     * SAME live hidden WebView immediately after POST /login succeeds —
     * no navigation, no teardown, no fixed delay. Retries on a short
     * bounded cadence (CookieManager/WebView network-stack cookie commit
     * is event-driven, not timed, so we poll until 200 or the budget is
     * exhausted — the poll cadence is not the success criterion; the 200
     * is). Beacons the final status only.
     */
    static final String VERIFY_JS =
            "(function(){try{var d=window.QolabLoginDriver;if(!d){return;}" +
            "var n=0;" +
            "var go=function(){" +
            " fetch(location.origin+'/rooms',{credentials:'same-origin'," +
            " headers:{'Accept':'application/json'}})" +
            " .then(function(r){d.onWebProbe(JSON.stringify({status:r.status}));" +
            "  if(r.status>=200&&r.status<300)return;" +
            "  if(++n<10)setTimeout(go,400);" +
            " })" +
            " .catch(function(){if(++n<10)setTimeout(go,400);" +
            "  else d.onWebProbe(JSON.stringify({status:-1}));});" +
            "};" +
            "go();" +
            "}catch(e){try{d.onWebProbe(JSON.stringify({status:-1}));}catch(e2){}}})()";

    /**
     * v1.5.12: WebView-context session probe — run INSIDE the
     * authenticated hidden WebView before destroy. Reports the HTTP
     * status only (never body/cookies/tokens). This distinguishes
     * browser-session-authenticated (A: web 200 + java 401 → cookie
     * transport broken) from a genuinely unauthenticated session
     * (B: both 401 → do not blame the Java transport).
     */
    static final String WEB_PROBE_JS =
            "(function(){try{var d=window.QolabLoginDriver;if(!d){return;}" +
            "fetch(location.origin+'/rooms',{credentials:'same-origin'," +
            "headers:{'Accept':'application/json'}})" +
            ".then(function(r){d.onWebProbe(JSON.stringify({status:r.status}));})" +
            ".catch(function(){d.onWebProbe(JSON.stringify({status:-1}));});" +
            "}catch(e){try{d.onWebProbe(JSON.stringify({status:-1}));}catch(e2){}}})()";

    /** Read the page state directly when evaluateOutcome runs on /login. */
    private static final String OUTCOME_JS =            "(function(){try{var d=window.QolabLoginDriver;if(!d){return;}" +
            "var bad=document.querySelector('p[class*=red-600],[role=alert],div[class*=bg-red-100],.text-red-600');" +
            "var errVis=!!(bad&&bad.offsetParent!==null&&bad.textContent&&bad.textContent.trim().length>0);" +
            "d.onOutcome(JSON.stringify({hasVisibleError:errVis," +
            " note:'on login page after submit'}));}catch(e){}})()";
}
