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
 *    button[type=submit]) with id fallbacks — resilient to redesign.
 *  - Fill uses native value setters + input events (React controlled
 *    inputs), submit via form.requestSubmit() so the page's own
 *    Inertia/Fortify handler runs CSRF/cookies/rotation/redirects.
 *  - Failure classification from the page's own error elements.
 *
 * Credentials reach the page ONLY via @JavascriptInterface getters;
 * never logged, never persisted; bridge removed + WebView destroyed
 * when done.
 */
public final class QolabWebLoginDriver {

    public interface Listener {
        void onLoginResult(Result r);
    }

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
                public void onPageFinished(WebView view, String url) {
                    lastUrl = sanitizeUrl(url);
                    if (finished) return;
                    if (isLoginFormUrl(url)) {
                        // Start (or continue) probing until the SPA form mounts.
                        main.removeCallbacks(probe);
                        main.postDelayed(probe, PROBE_INTERVAL_MS);
                    } else {
                        // Navigated away from /login → authenticated redirect.
                        main.removeCallbacks(probe);
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

    /** Probe result beacon: fills the form the first time it is ready. */
    private void onProbeJson(String json) {
        if (finished || submitted) return;
        try {
            JSONObject o = new JSONObject(json);
            boolean formReady = o.optBoolean("formReady", false);
            lastDomDiag = buildDomDiag(o);
            if (formReady) {
                main.removeCallbacks(probe);
                submitted = true;
                main.removeCallbacks(loadWatchdog);
                main.postDelayed(submitWatchdog, SUBMIT_TIMEOUT_MS);
                // Fill + submit now that the form is confirmed present.
                webView.evaluateJavascript(FILL_SUBMIT_JS, null);
            }
        } catch (Exception ignored) {
        }
    }

    private static String buildDomDiag(JSONObject o) {
        // Booleans/counts only — never input values.
        return "readyState=" + o.optString("readyState", "?")
                + " title=" + sanitizeNote(o.optString("title", ""))
                + " forms=" + o.optInt("forms", 0)
                + " email=" + yn(o.optBoolean("email", false))
                + " password=" + yn(o.optBoolean("password", false))
                + " submit=" + yn(o.optBoolean("submit", false));
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
        if (finished) return;
        finished = true;
        main.removeCallbacks(loadWatchdog);
        main.removeCallbacks(submitWatchdog);
        main.removeCallbacks(probe);
        final WebView wv = webView;
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

        /** Outcome beacon from the page (invalid-credentials vs idle). */
        @JavascriptInterface
        public void onOutcome(String json) {
            main.post(() -> onOutcomeJson(json));
        }

        /** Progress beacon for logs (stage names only). */
        @JavascriptInterface
        public void onStage(String stage) {
            android.util.Log.i("QolabAuth", "web-login-driver stage: " + stage);
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
     * Fill the REAL login form using native value setters + input events
     * so React's controlled inputs pick them up, then trigger the form's
     * own submit handler (requestSubmit) — Inertia/Fortify handles CSRF,
     * cookies, rotation and the redirect natively. A MutationObserver
     * reports whether the page shows a visible error after submit.
     */
    private static final String FILL_SUBMIT_JS =
            "(function(){try{" +
            "var d=window.QolabLoginDriver;if(!d){return;}" +
            "function setVal(el,v){var proto=el instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
            " var setter=Object.getOwnPropertyDescriptor(proto,'value').set;" +
            " setter.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));" +
            " el.dispatchEvent(new Event('change',{bubbles:true}));}" +
            "var em=document.querySelector('input[type=email],#email');" +
            "var pw=document.querySelector('input[type=password],#password');" +
            "if(!em||!pw){d.onStage('form-missing-at-fill');return;}" +
            "setVal(em,d.getEmail());setVal(pw,d.getPassword());" +
            "d.onStage('form-filled');" +
            "setTimeout(function(){" +
            " var f=document.querySelector('form');" +
            " if(f){d.onStage('submitted');f.requestSubmit();}" +
            "},250);" +
            // After submit, if still on /login: field errors (invalid
            // credentials) or an error banner. Watch the DOM.
            "var ob=new MutationObserver(function(){" +
            " if(window.__qolabOutcomeSent){return;}" +
            " var bad=document.querySelector('.text-red-600,p[class*=red-600],div[class*=bg-red-100]');" +
            " if(bad&&bad.offsetParent!==null){window.__qolabOutcomeSent=1;" +
            "  try{d.onOutcome(JSON.stringify({hasVisibleError:true,note:'field error visible on login page'}));}catch(e){}" +
            "  ob.disconnect();}" +
            "});" +
            "ob.observe(document.body,{childList:true,subtree:true,attributes:true});" +
            "}catch(ex){try{window.QolabLoginDriver.onOutcome(JSON.stringify({hasVisibleError:false,note:'driver js error'}));}catch(e){}}})()";

    /** Read the page state directly when evaluateOutcome runs on /login. */
    private static final String OUTCOME_JS =
            "(function(){try{var d=window.QolabLoginDriver;if(!d){return;}" +
            "var bad=document.querySelector('.text-red-600,p[class*=red-600],div[class*=bg-red-100]');" +
            "d.onOutcome(JSON.stringify({hasVisibleError:(bad&&bad.offsetParent!==null)," +
            " note:'on login page after submit'}));}catch(e){}})()";
}
