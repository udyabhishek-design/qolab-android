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
 * v1.5.4: WebView login driver that authenticates through the REAL web
 * implementation — the exact flow that worked on this phone in the browser.
 *
 * The hidden WebView loads the Development `/login` page (the actual React/
 * Inertia/Fortify implementation), waits for the form to be present, fills
 * #email / #password through native value setters + input events (so React
 * state updates), triggers the form's own submit handler via
 * requestSubmit(), and lets the browser/Inertia/Fortify pipeline handle
 * CSRF, cookies, session rotation and redirects.
 *
 * Success is detected by navigation AWAY from /login (Fortify redirects to
 * the authenticated area) combined with an authenticated session cookie
 * (checked by name + value transition by the caller's seam poll, which
 * runs AFTER the transaction completes).
 *
 * Failure classification comes from the page itself:
 *  - visible field error / error banner  → invalid credentials (422)
 *  - still on /login after redirect settles → credentials rejected
 *  - timeout / load failure → network/timeout native error
 *
 * Credentials reach the page ONLY through @JavascriptInterface getters
 * (same boundary as the previous bridge); they are never logged, never
 * persisted, and the bridge is removed + WebView destroyed when done.
 */
public final class QolabWebLoginDriver {

    public interface Listener {
        void onLoginResult(Result r);
    }

    /** Safe result — statuses, timings, sanitized messages. Never values. */
    public static final class Result {
        /** "done" | "invalid-credentials" | "page-error" | "timeout" | "load" | "bridge" */
        public final String stage;
        /** True when navigation left /login (authenticated redirect). */
        public final boolean success;
        /** Sanitized message for diagnostics (no secrets). */
        public final String note;
        public final long totalMs;
        /** Final URL (host+path only — no query, no fragments). */
        public final String finalUrl;

        Result(boolean success, String stage, String note, long totalMs, String finalUrl) {
            this.success = success;
            this.stage = stage;
            this.note = note;
            this.totalMs = totalMs;
            this.finalUrl = finalUrl;
        }
    }

    private static final long LOAD_TIMEOUT_MS = 25_000;
    private static final long SUBMIT_TIMEOUT_MS = 30_000;

    private final Context context;
    private final String origin;
    private final String email;
    private final String password;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private volatile boolean finished = false;
    private volatile boolean formFilled = false;
    private volatile boolean submitted = false;
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
        if (!finished && !formFilled) {
            finish(new Result(false, "timeout", "login page did not finish loading", elapsed(), lastUrl));
        }
    };

    private final Runnable submitWatchdog = () -> {
        if (!finished) {
            finish(new Result(false, submitted ? "timeout" : "page-error",
                    submitted ? "no navigation after submit" : "form never appeared", elapsed(), lastUrl));
        }
    };

    /** Safe page-level outcome beacon from the MutationObserver. */
    private final Runnable outcomeCheck = this::evaluateOutcome;

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
                        // The real login page is up — fill + submit (idempotent guard).
                        main.postDelayed(() -> {
                            if (!finished && !formFilled) {
                                formFilled = true;
                                main.removeCallbacks(loadWatchdog);
                                main.postDelayed(submitWatchdog, SUBMIT_TIMEOUT_MS);
                                webView.evaluateJavascript(FILL_SUBMIT_JS, null);
                            }
                        }, 400); // let React finish hydrating
                    } else {
                        // Navigated away from /login → authenticated redirect.
                        main.postDelayed(outcomeCheck, 600);
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

    /** Outcome decision — navigation URL plus the page's own error state. */
    private void evaluateOutcome() {
        if (finished) return;
        main.removeCallbacks(submitWatchdog);
        boolean leftLogin = !isLoginFormUrl(lastUrl);
        if (leftLogin) {
            finish(new Result(true, "done", "authenticated redirect", elapsed(), lastUrl));
            return;
        }
        // Still on /login — check whether the page rendered a field error
        // (invalid credentials) or just idles (page-error).
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
                        elapsed(), lastUrl));
            } else {
                finish(new Result(false, "page-error",
                        note.isEmpty() ? "no navigation away from login" : note,
                        elapsed(), lastUrl));
            }
        } catch (Exception e) {
            finish(new Result(false, "bridge", "outcome beacon unreadable", elapsed(), lastUrl));
        }
    }

    private void finish(Result r) {
        if (finished) return;
        finished = true;
        main.removeCallbacks(loadWatchdog);
        main.removeCallbacks(submitWatchdog);
        main.removeCallbacks(outcomeCheck);
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

    /** JS→Java beacon. Credentials leave ONLY through getEmail/getPassword. */
    private final class Bridge {
        @JavascriptInterface
        public String getEmail() {
            return email;
        }

        @JavascriptInterface
        public String getPassword() {
            return password;
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
     * Fill the REAL login form (#email, #password) using native value
     * setters + input events so React's controlled inputs pick them up,
     * then trigger the form's own submit handler (requestSubmit) —
     * Inertia/Fortify then handles CSRF, cookies, rotation and the
     * redirect natively. A MutationObserver reports back whether the
     * page shows a visible error (credentials rejected) after submit.
     */
    private static final String FILL_SUBMIT_JS =
            "(function(){try{" +
            "var d=window.QolabLoginDriver;if(!d){return;}" +
            "function setVal(sel,v){var el=document.querySelector(sel);" +
            " if(!el){return false;}" +
            " var proto=el instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
            " var setter=Object.getOwnPropertyDescriptor(proto,'value').set;" +
            " setter.call(el,v);" +
            " el.dispatchEvent(new Event('input',{bubbles:true}));" +
            " return true;}" +
            // Wait for React hydration to finish — the form must exist.
            "var tries=0;" +
            "function fill(){" +
            " if(document.querySelector('#email')&&document.querySelector('#password')){" +
            "  setVal('#email',d.getEmail());setVal('#password',d.getPassword());" +
            "  d.onStage('form-filled');" +
            "  setTimeout(function(){" +
            "   var f=document.querySelector('form');" +
            "   if(f){d.onStage('submitted');f.requestSubmit();}" +
            "  },250);" +
            " } else if(tries++<50){setTimeout(fill,200);} else {d.onStage('form-missing');}" +
            "}" +
            "fill();" +
            // After submit, if we are still on /login, the page either shows
            // field errors (invalid credentials) or an error banner. Watch DOM.
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
