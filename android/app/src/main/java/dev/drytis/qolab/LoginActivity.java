package dev.drytis.qolab;

import android.animation.LayoutTransition;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Qolab Mobile — native Login screen (v1.4).
 *
 * Purpose-built mobile login establishing the Qolab Mobile visual language:
 * warm cream canvas, bold wordmark, editorial headline, popping color blocks.
 *
 * Authentication uses the EXISTING web endpoints (Laravel Fortify):
 *   GET  /sanctum/csrf-cookie  +  POST /login
 * On success the authenticated session cookie is written into the WebView
 * CookieManager so the main shell continues exactly like a web login,
 * then MainActivity is launched (existing post-login flow).
 *
 * Forgot password opens the EXISTING /forgot-password web flow in the main
 * WebView shell. No Google/social login anywhere.
 */
public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "QolabAuth";

    private EditText emailInput;
    private EditText passwordInput;
    private ImageButton passwordToggle;
    private Button signInButton;
    private ProgressBar signInProgress;
    private TextView arrowView;
    private TextView emailError;
    private TextView passwordError;
    private TextView formError;
    private ScrollView scroll;

    private boolean passwordVisible = false;
    private boolean submitting = false;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    private String appOrigin;
    private String appHost;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // App origin — same value the Capacitor bridge uses (capacitor.config.json server.url).
        appOrigin = readAppOrigin();
        try {
            appHost = java.net.URI.create(appOrigin).getHost();
        } catch (Exception e) {
            appHost = null;
        }

        // Clean stale session state so the MainActivity gate can't bounce
        // back mid-expiry: if we're showing native Login, any *-session
        // cookie present is expired/invalid. v1.5.2: ALSO expire XSRF-TOKEN
        // — a cross-run STALE XSRF value is exactly what paired with a fresh
        // session to produce the device 419 (see XsrfFreshness javadoc).
        // Cookie NAMES only logged.
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            String existing = cm.getCookie(appOrigin);
            if (existing != null) {
                boolean hadSession = false;
                for (String part : existing.split(";")) {
                    String name = part.split("=", 2)[0].trim();
                    if (name.toLowerCase().endsWith("session")
                            || name.equals("XSRF-TOKEN")) {
                        cm.setCookie(appOrigin + "/", AuthNavRules.expireCookieValue(name));
                        hadSession = true;
                    }
                }
                if (hadSession) {
                    cm.flush();
                    android.util.Log.i(TAG, "cleared stale session cookie on native login");
                }
            }
        } catch (Exception ignored) {
        }

        // v1.4.11: DEBUG-only access to the previous session's logout trace
        // (LogoutTrace is static, so it survives the activity transition).
        if (BuildConfig.DEBUG && LogoutTrace.hasData()) {
            try {
                android.widget.TextView traceLink = new android.widget.TextView(this);
                traceLink.setText("Last logout diagnostics");
                traceLink.setTextSize(12);
                traceLink.setAlpha(0.7f);
                traceLink.setPadding(16, 8, 16, 8);
                traceLink.setOnClickListener(v -> {
                    android.widget.ScrollView sv = new android.widget.ScrollView(this);
                    android.widget.TextView tv = new android.widget.TextView(this);
                    tv.setText(LogoutTrace.render());
                    tv.setPadding(48, 24, 48, 24);
                    tv.setTextIsSelectable(true);
                    sv.addView(tv);
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Logout trace")
                            .setView(sv)
                            .setPositiveButton("Close", null)
                            .show();
                });
                android.widget.FrameLayout root =
                        (android.widget.FrameLayout) findViewById(android.R.id.content);
                android.widget.FrameLayout.LayoutParams lp =
                        new android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                                android.view.Gravity.TOP | android.view.Gravity.END);
                root.addView(traceLink, lp);
            } catch (Exception ignored) {
            }
        }

        emailInput = findViewById(R.id.login_email);
        passwordInput = findViewById(R.id.login_password);
        passwordToggle = findViewById(R.id.login_password_toggle);
        signInButton = findViewById(R.id.login_submit);
        signInProgress = findViewById(R.id.login_progress);
        arrowView = findViewById(R.id.login_arrow);
        emailError = findViewById(R.id.login_email_error);
        passwordError = findViewById(R.id.login_password_error);
        formError = findViewById(R.id.login_form_error);
        scroll = findViewById(R.id.login_scroll);

        // Smooth error show/hide
        if (scroll.getChildAt(0) instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) scroll.getChildAt(0);
            if (group.getLayoutTransition() == null) {
                LayoutTransition lt = new LayoutTransition();
                lt.enableTransitionType(LayoutTransition.CHANGING);
                group.setLayoutTransition(lt);
            }
        }

        setupFields();
        setupPasswordToggle();
        setupForgotPassword();
        setupSignIn();
        bindDiagnosticsViews();
    }

    private String readAppOrigin() {
        // BuildConfig.QOLAB_ORIGIN is environment-aware: debug builds point at
        // the Qolab DEVELOPMENT backend, release builds at production. This
        // guarantees the native login can never authenticate against a
        // different origin than the WebView shell (MainActivity serves the
        // same capacitor server.url, which `npx cap sync` copies to match
        // the build being produced).
        return BuildConfig.QOLAB_ORIGIN.replaceAll("/+$", "");
    }

    // ── Fields ──────────────────────────────────────────────────────

    private void setupFields() {
        // Clear field errors as the user types
        emailInput.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                if (emailError.getVisibility() == View.VISIBLE) emailError.setVisibility(View.GONE);
                if (formError.getVisibility() == View.VISIBLE) formError.setVisibility(View.GONE);
            }
        });
        passwordInput.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                passwordToggle.setVisibility(s.length() > 0 ? View.VISIBLE : View.INVISIBLE);
                if (passwordError.getVisibility() == View.VISIBLE) passwordError.setVisibility(View.GONE);
                if (formError.getVisibility() == View.VISIBLE) formError.setVisibility(View.GONE);
            }
        });
        passwordToggle.setVisibility(View.INVISIBLE); // hidden until typing

        // Email: Next → focus password
        emailInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                passwordInput.requestFocus();
                return true;
            }
            return false;
        });

        // Password: Done → sign in
        passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptSignIn();
                return true;
            }
            return false;
        });
    }

    private void setupPasswordToggle() {
        passwordToggle.setOnClickListener(v -> {
            passwordVisible = !passwordVisible;
            if (passwordVisible) {
                passwordInput.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
                passwordToggle.setImageResource(R.drawable.qm_ic_eye_off);
                passwordToggle.setContentDescription(getString(R.string.login_hide_password));
            } else {
                passwordInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
                passwordToggle.setImageResource(R.drawable.qm_ic_eye);
                passwordToggle.setContentDescription(getString(R.string.login_show_password));
            }
            // Keep the caret at the end after the method switch
            passwordInput.setSelection(passwordInput.getText().length());
        });
    }

    private void setupForgotPassword() {
        TextView forgot = findViewById(R.id.login_forgot);
        forgot.setOnClickListener(v -> openForgotPassword());
    }

    /**
     * Forgot password: route through the EXISTING web flow. The user may not
     * be signed in, so we load the main WebView shell directly at
     * /forgot-password — Fortify's public reset-request page.
     */
    private void openForgotPassword() {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("qolab_start_path", "/forgot-password");
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void setupSignIn() {
        signInButton.setOnClickListener(v -> attemptSignIn());
    }

    // ── Validation ──────────────────────────────────────────────────

    private boolean validate() {
        boolean ok = true;
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        if (email.isEmpty()) {
            showFieldError(emailError, getString(R.string.login_error_missing_email));
            ok = false;
        } else if (!QolabAuthClient.isValidEmail(email)) {
            showFieldError(emailError, getString(R.string.login_error_invalid_email));
            ok = false;
        }
        if (password.isEmpty()) {
            showFieldError(passwordError, getString(R.string.login_error_missing_password));
            ok = false;
        }
        return ok;
    }

    private void showFieldError(TextView target, String message) {
        target.setText(message);
        target.setVisibility(View.VISIBLE);
    }

    // ── Submit ──────────────────────────────────────────────────────

    private void attemptSignIn() {
        if (submitting) return;
        hideKeyboard();
        formError.setVisibility(View.GONE);
        if (!validate()) return;

        setSubmitting(true);
        final String email = emailInput.getText().toString().trim();
        final String password = passwordInput.getText().toString();

        // v1.5.4: primary path = WebView login DRIVER through the REAL web
        // implementation. The hidden WebView loads the actual /login page,
        // fills its form and submits it — Inertia/Fortify handle CSRF,
        // cookies, session rotation and the redirect natively (the exact
        // flow that worked in the browser on this phone). No custom
        // CSRF → XSRF → POST /login handshaking anymore.
        // v1.5.8: snapshot the session-cookie VALUE BEFORE the login
        // transaction starts. The seam after a driver-verified redirect
        // requires this value to CHANGE (or a session to appear when none
        // existed) — a mere presence check passes on the ANONYMOUS
        // pre-login session cookie, handing Home an unauthenticated
        // session → 401 → the device auto-logout. Never logged.
        preAuthSessionValue = sessionCookieValue(appOrigin);

        QolabWebLoginDriver driver = new QolabWebLoginDriver(this, appOrigin, email, password,
                r -> runOnUiThread(() -> {
                    android.util.Log.i(TAG, "web-login-driver success=" + r.success
                            + " stage=" + r.stage + " ms=" + r.totalMs
                            + (r.note == null || r.note.isEmpty() ? "" : " note=" + r.note));

                    if (r.success) {
                        // Authenticated redirect completed (driver-verified).
                        // v1.5.7: the redirect was detected AFTER auth
                        // completed, so there is no pre-login value to
                        // compare against — wait for session cookie
                        // PRESENCE (with flush), not a value change.
                        waitForAuthenticatedSessionPresent();
                        return;
                    }
                    String message;
                    switch (r.stage) {
                        case "invalid-credentials":
                            // The real login form showed a field error —
                            // the credentials were rejected. Show the same
                            // generic message the form itself would.
                            message = getString(R.string.login_error_generic);
                            break;
                        case "timeout":
                            // v1.5.10: a driver timeout means only that
                            // location.href never left /login — an Inertia
                            // login may STILL have succeeded. Decide from
                            // the server: run ONE /mobile/home probe with
                            // the exact cookie path HomeDataClient uses.
                            // 200 → authenticated → Home. 401 → genuine
                            // failure → diagnostics. network/5xx → reported
                            // separately, never classified as auth loss.
                            runTimeoutProbe();
                            return;
                        case "load":
                        case "page-error":
                        case "bridge":
                        default:
                            message = getString(R.string.login_error_generic);
                            break;
                    }
                    lastFailure = snapshotOf(r);
                    // v1.5.7: auto-expand so the panel is ALWAYS visible
                    // after a failure — no tap required.
                    diagExpanded = true;
                    onLoginFailure(message);
                }));
        driver.start();
    }

    /** Build a diagnostics snapshot from the web-login-driver result (safe fields). */
    private static DiagSnapshot snapshotOf(QolabWebLoginDriver.Result r) {
        String failureType = r.stage;
        DiagSnapshot d = new DiagSnapshot("driver", 0, 0, failureType,
                r.totalMs, -1, false);
        d.errMsg = r.note + (r.domDiag == null || r.domDiag.isEmpty()
                ? "" : " [" + r.domDiag + "]");
        d.docUrl = r.finalUrl;
        d.failedStage = r.stage;
        return d;
    }

    // ── DEVELOPMENT-ONLY diagnostics (v1.4.4, temporary) ───────────
    // Safe fields ONLY: origin host, stage, statuses, durations, exception
    // class name, yes/no flags, sanitized JS exception message. Never
    // email/password/token/cookie VALUES.

    /** Snapshot of the last failed attempt (null until one happened). */
    private static final class DiagSnapshot {
        final String stage;
        final int csrfStatus;
        final int loginStatus;
        final String failureType;
        final long csrfMs;
        final long loginMs;
        final boolean xsrfCookie;
        // v1.5.3 sanitized WebView diagnostics (null when not fetch-io)
        String failedStage;
        String errMsg;
        String docUrl;
        String online;
        String fetchOk;
        String bridgeOk;

        DiagSnapshot(String stage, int csrfStatus, int loginStatus, String failureType,
                     long csrfMs, long loginMs, boolean xsrfCookie) {
            this.stage = stage == null ? "?" : stage;
            this.csrfStatus = csrfStatus;
            this.loginStatus = loginStatus;
            this.failureType = failureType == null ? "unknown" : failureType;
            this.csrfMs = csrfMs;
            this.loginMs = loginMs;
            this.xsrfCookie = xsrfCookie;
        }
    }

    private DiagSnapshot lastFailure = null;
    /** v1.5.8: session-cookie value captured BEFORE the login driver loads
     * /login — the baseline the post-auth seam compares against. Never logged. */
    private String preAuthSessionValue = null;
    private Boolean lastXsrfAppearedEncoded = null;
    private Boolean lastXsrfHeaderDecoded = null;
    private boolean diagExpanded = false;
    private LinearLayout diagPanel = null;
    private TextView diagBody = null;
    private TextView diagToggle = null;
    private TextView diagProbe = null;

    private void bindDiagnosticsViews() {
        diagPanel = findViewById(R.id.login_diag);
        diagBody = findViewById(R.id.login_diag_body);
        diagToggle = findViewById(R.id.login_diag_toggle);
        diagProbe = findViewById(R.id.login_diag_probe);
        if (diagPanel == null) return;
        if (!BuildConfig.DEBUG) {
            // Release safety: the panel can never render in a release build.
            diagPanel.setVisibility(View.GONE);
            return;
        }
        diagToggle.setOnClickListener(v -> {
            diagExpanded = !diagExpanded;
            diagBody.setVisibility(diagExpanded ? View.VISIBLE : View.GONE);
            diagProbe.setVisibility(diagExpanded ? View.VISIBLE : View.GONE);
            if (diagExpanded) renderDiagnostics();
        });
        diagProbe.setOnClickListener(v -> runConnectivityProbe());
    }

    /** Refresh the panel contents from the latest snapshot (safe fields). */
    private void renderDiagnostics() {
        if (diagBody == null || !BuildConfig.DEBUG) return;
        StringBuilder sb = new StringBuilder();
        sb.append("Origin: ").append(appHost == null ? appOrigin : appHost).append('\n');
        if (lastFailure == null) {
            sb.append("No failed attempt recorded yet.");
        } else {
            sb.append("Stage: ").append(lastFailure.stage).append('\n');
            sb.append("CSRF HTTP: ").append(fmtMs(lastFailure.csrfMs, lastFailure.csrfStatus)).append('\n');
            sb.append("Login HTTP: ").append(fmtMs(lastFailure.loginMs, lastFailure.loginStatus)).append('\n');
            sb.append("Failure type: ").append(lastFailure.failureType).append('\n');
            // v1.5.3: sanitized WebView failure details (fetch-io only)
            if (lastFailure.failedStage != null) {
                sb.append("Failed stage: ").append(lastFailure.failedStage).append('\n');
            }
            if (lastFailure.errMsg != null && !lastFailure.errMsg.isEmpty()) {
                sb.append("JS exception: ").append(lastFailure.errMsg).append('\n');
            }
            if (lastFailure.docUrl != null) {
                sb.append("Auth doc URL: ").append(lastFailure.docUrl).append('\n');
            }
            if (lastFailure.fetchOk != null) {
                sb.append("fetch available: ").append(lastFailure.fetchOk).append('\n');
            }
            if (lastFailure.bridgeOk != null) {
                sb.append("Bridge available: ").append(lastFailure.bridgeOk).append('\n');
            }
            if (lastFailure.online != null) {
                sb.append("navigator.onLine: ").append(lastFailure.online).append('\n');
            }
            sb.append("Network available: ").append(networkAvailable()).append('\n');
            sb.append("XSRF cookie present: ")
                    .append(lastFailure.xsrfCookie ? "Yes" : "No").append('\n');
            sb.append("XSRF appears URL-encoded: ")
                    .append(lastXsrfAppearedEncoded == null ? "Not reached"
                            : lastXsrfAppearedEncoded ? "Yes" : "No").append('\n');
            sb.append("XSRF decoded for header: ")
                    .append(lastXsrfHeaderDecoded == null ? "Not reached"
                            : lastXsrfHeaderDecoded ? "Yes" : "No").append('\n');
            sb.append("Session cookie received: ")
                    .append(lastFailure.loginStatus == 0
                            ? "Not reached" : "Yes").append('\n');
            sb.append("Cookie bridge: ").append("n/a (WebView session context)").append('\n');
            sb.append("Session cookie present (may be anonymous): ")
                    .append(sessionCookieValue(appOrigin) != null ? "Yes" : "No").append('\n');
            // v1.5.10: POST /login observability + probe result.
            QolabWebLoginDriver.Trace dt = QolabWebLoginDriver.LAST;
            sb.append("POST /login initiated: ")
                    .append(dt.postLoginInitiated ? "Yes" : "No").append('\n');
            if (dt.postLoginInitiated) {
                sb.append("POST /login status: ")
                        .append(dt.postLoginStatus == -2 ? "not observable"
                                : dt.postLoginStatus == -1 ? "network error"
                                : String.valueOf(dt.postLoginStatus))
                        .append(dt.postLoginFailed ? " (failed)" : "").append('\n');
            }
            sb.append("Home probe: ")
                    .append(HomeHandoffTrace.get().httpStatus == -1
                            ? "not run" : HomeHandoffTrace.get().render()).append('\n');
            sb.append("MainActivity launched: No");
        }
        diagBody.setText(sb.toString());
    }

    /** "204 (312 ms)" / "timeout (15003 ms)" / "not reached". */
    private static String fmtMs(long ms, int status) {
        if (ms < 0) return "not reached";
        if (ms == -2) return "timeout/failed (see Failure type)";
        if (status == 0) return "failed (" + ms + " ms)";
        return status + " (" + ms + " ms)";
    }

    private String networkAvailable() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkInfo n = cm.getActiveNetworkInfo();
            return n != null && n.isConnected() ? "Yes" : "No";
        } catch (Exception e) {
            return "?";
        }
    }

    /** Harmless GET to the Development origin — no auth, no writes. */
    private void runConnectivityProbe() {
        if (diagBody == null) return;
        diagProbe.setEnabled(false);
        networkExecutor.execute(() -> {
            QolabAuthClient probe = new QolabAuthClient(appOrigin);
            QolabAuthClient.ProbeResult r = probe.probeConnectivity();
            android.util.Log.i(TAG, "probe reachable=" + r.reachable
                    + " status=" + r.status + " ms=" + r.durationMs
                    + (r.ioError == null ? "" : " ioError=" + r.ioError));
            runOnUiThread(() -> {
                diagProbe.setEnabled(true);
                StringBuilder sb = new StringBuilder();
                sb.append("Development server: ").append(r.reachable ? "Reachable" : "Unreachable").append('\n');
                sb.append("HTTP: ").append(r.status > 0 ? String.valueOf(r.status) : "no response").append('\n');
                sb.append("Duration: ").append(r.durationMs).append(" ms");
                if (r.ioError != null) sb.append('\n').append("Probe error: ").append(r.ioError);
                // Show probe result appended under the main diagnostics body
                diagBody.append("\n\n[Connection test]\n" + sb);
            });
        });
    }

    /**
     * v1.5.9: SERVER-VERIFIED auth handoff. Cookie heuristics (presence /
     * value-change) are abandoned — the preAuth baseline was always null
     * (onCreate's stale cleanup expires *-session before attemptLogin), so
     * the ANONYMOUS session from the /login page load satisfied every
     * heuristic and Home launched unauthenticated → 401 → auto-logout.
     *
     * Now: after the driver verifies the Inertia redirect, we probe
     * GET /mobile/home with the SAME cookie path HomeDataClient uses
     * (request-time reads, Set-Cookie sync). Home launches ONLY on 200.
     * 401 → bounded retry (CookieManager may still be receiving the
     * authenticated session). Network/5xx/parse → NOT auth loss, retry too.
     * Never routes to login from the probe — the user just authenticated.
     * No cookies are cleared at any point here.
     */
    private void waitForAuthenticatedSessionPresent() {
        final String origin = appOrigin;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final int MAX_ATTEMPTS = 8;
        final int[] attempts = {0};
        final QolabDataClientHolder holder = new QolabDataClientHolder();
        final Runnable probe = new Runnable() {
            @Override
            public void run() {
                HomeHandoffTrace.get().retryCount = attempts[0];
                final QolabCookieSession.CookieStore store = new QolabCookieSession.WebViewCookieStore();
                final String url = origin + "/mobile/home";
                // Read the cookie header exactly as HomeDataClient will.
                final String cookieHeader = store.cookieHeaderFor(url);
                HomeHandoffTrace.get().sessionCookieSent =
                        QolabCookieSession.containsSessionCookie(cookieHeader);
                HomeHandoffTrace.get().sessionCookieName =
                        sessionCookieName(cookieHeader);
                recordSessionCookieComposition(cookieHeader);
                holder.client = new HomeDataClient(origin);
                final android.os.Handler ui = h;
                new Thread(() -> {
                    final HomeDataClient.Result r = holder.client.fetchHome(store, url);
                    ui.post(() -> {
                        HomeHandoffTrace.get().httpStatus = r.status;
                        HomeHandoffTrace.get().setCookieReceived = holder.client.lastSetCookieSeen;
                        HomeHandoffTrace.get().authLostReason =
                                r.authLost ? "401 with session cookie" : null;
                        boolean ok = r.data != null && r.status / 100 == 2;
                        if (ok) {
                            android.util.Log.i(TAG, "auth probe 200 → Home");
                            onLoginSuccess();
                            return;
                        }
                        boolean authLoss = r.authLost;             // 401 with session sent
                        boolean transientFailure = !ok && !authLoss; // network/5xx/parse/401-no-session
                        if (attempts[0]++ >= MAX_ATTEMPTS) {
                            android.util.Log.w(TAG, "auth probe exhausted (status="
                                    + r.status + " authLoss=" + authLoss + ")");
                            QolabWebLoginDriver.LAST.stage = "probe-exhausted";
                            QolabWebLoginDriver.LAST.note = "GET /mobile/home never returned 200"
                                    + " (last status=" + r.status + ", authLoss=" + authLoss + ")";
                            lastFailure = traceSnapshot("probe-exhausted",
                                    "authenticated probe failed after " + MAX_ATTEMPTS + " attempts");
                            onLoginFailure(getString(R.string.login_error_generic));
                            return;
                        }
                        // Either a 401 (session not committed yet) or a
                        // transient failure — wait and re-probe. NEVER
                        // routeToLogin from here.
                        ui.postDelayed(this, 1200);
                    });
                }, "auth-handoff-probe").start();
            }
        };
        h.postDelayed(probe, 150);
    }

    /**
     * v1.5.10: driver timed out (href still /login) — but the Inertia
     * POST /login may still have succeeded without a client-side
     * navigation. Decide from the SERVER: one authenticated
     * GET /mobile/home probe on the exact cookie path HomeDataClient
     * uses. 200 → authentication succeeded → Home. 401 → genuine
     * unauthenticated → diagnostics panel. network/5xx → reported as
     * 'server unreachable', never auth loss. Never clears cookies.
     */
    private void runTimeoutProbe() {
        final String origin = appOrigin;
        new Thread(() -> {
            final QolabCookieSession.CookieStore store =
                    new QolabCookieSession.WebViewCookieStore();
            // Flush CookieManager so the probe reads the committed state.
            try {
                android.webkit.CookieManager.getInstance().flush();
            } catch (Exception ignored) {
            }
            HomeDataClient client = new HomeDataClient(origin);
            HomeDataClient.Result r = client.fetchHome(store, origin + "/mobile/home");
            HomeHandoffTrace t = HomeHandoffTrace.get();
            t.httpStatus = r.status;
            String cookieHeader = store.cookieHeaderFor(origin + "/mobile/home");
            t.sessionCookieSent = QolabCookieSession.containsSessionCookie(cookieHeader);
            t.sessionCookieName = sessionCookieName(cookieHeader);
            recordSessionCookieComposition(cookieHeader);
            t.setCookieReceived = client.lastSetCookieSeen;
            runOnUiThread(() -> {
                if (r.data != null && r.status / 100 == 2) {
                    android.util.Log.i(TAG, "timeout probe 200 → authenticated → Home");
                    onLoginSuccess();
                    return;
                }
                String message;
                String stage;
                if (r.authLost) {                    // 401 with session cookie sent
                    message = getString(R.string.login_error_generic);
                    stage = "timeout-401";
                } else if (r.status == HomeDataClient.Result.STATUS_IO) {
                    // network/timeout — NOT an authentication failure.
                    message = getString(R.string.login_error_generic);
                    stage = "timeout-server-unreachable";
                } else {                             // 5xx etc.
                    message = getString(R.string.login_error_generic);
                    stage = "timeout-server-error-" + r.status;
                }
                lastFailure = traceSnapshot(stage,
                        "driver timed out on /login; server probe: "
                                + (r.authLost ? "401 unauthenticated"
                                : r.status == HomeDataClient.Result.STATUS_IO
                                ? "network failure (" + safeIo(r.ioError) + ")"
                                : "HTTP " + r.status));
                diagExpanded = true;
                onLoginFailure(message);
            });
        }, "timeout-probe").start();
    }

    private static String safeIo(String io) {
        return io == null ? "io" : io;
    }

    /** Cookie NAME (never value) for diagnostics, longest-name rule. */
    private static String sessionCookieName(String cookieHeader) {        if (cookieHeader == null) return null;
        String best = null;
        for (String part : cookieHeader.split(";")) {
            String name = part.split("=", 2)[0].trim();
            if (name.toLowerCase().endsWith("session")
                    && (best == null || name.length() > best.length())) {
                best = name;
            }
        }
        return best;
    }

    /** Small holder so the probe runnable can reach its client instance. */
    private static final class QolabDataClientHolder {
        HomeDataClient client;
    }

    /** v1.5.12: count *-session entries + duplicate names (names only). */
    private static void recordSessionCookieComposition(String cookieHeader) {
        HomeHandoffTrace t = HomeHandoffTrace.get();
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            t.sessionCookieEntries = 0;
            t.sessionCookieDuplicate = false;
            return;
        }
        java.util.List<String> sessionNames = new java.util.ArrayList<>();
        for (String part : cookieHeader.split(";")) {
            String name = part.split("=", 2)[0].trim();
            if (name.toLowerCase().endsWith("session")) sessionNames.add(name);
        }
        t.sessionCookieEntries = sessionNames.size();
        t.sessionCookieDuplicate = sessionNames.size() > 1;
    }

    /**
     * v1.5.12: decision evidence from the dual probe — shown in the diag
     * panel so one physical screenshot is sufficient.
     * A) web 200 + java 401 → browser session valid; Android cookie
     *    transport broken. B) both 401 → do NOT blame the transport.
     * C) both 200 → handoff works; investigate afterwards.
     * Domain/path scope is NOT reported: CookieManager.getCookie()
     * does not expose it.
     */
    private static String dualProbeDecision() {
        QolabWebLoginDriver.Trace d = QolabWebLoginDriver.LAST;
        HomeHandoffTrace t = HomeHandoffTrace.get();
        if (d.webProbeStatus == -2 || t.httpStatus == -2) return null;
        boolean webOk = d.webProbeStatus >= 200 && d.webProbeStatus < 300;
        boolean javaOk = t.httpStatus >= 200 && t.httpStatus < 300;
        if (webOk && !javaOk) {
            return "A: WebView probe " + d.webProbeStatus + " OK but Java probe "
                    + t.httpStatus + " — browser session valid; Android cookie "
                    + "transport broken (duplicate session-cookie names="
                    + (t.sessionCookieDuplicate ? "Y" : "N") + ")";
        }
        if (!webOk && !javaOk) {
            return "B: both probes 401 (web=" + d.webProbeStatus + ", java="
                    + t.httpStatus + ") — session not authenticated; do NOT "
                    + "blame Java cookie transport";
        }
        if (webOk && javaOk) {
            return "C: both probes 200 — handoff works; investigate what "
                    + "happens after handoff";
        }
        return "mixed: web=" + d.webProbeStatus + ", java=" + t.httpStatus
                + " — unexpected combination";
    }

    /**
     * v1.5.9: STATIC handoff diagnostics — survive routeToLogin() and
     * activity recreation so the failed-login diag panel can always show
     * WHY Home never launched (or why it did, then died). Booleans,
     * status codes and cookie NAMEs only — never values/tokens/creds.
     */
    static final class HomeHandoffTrace {
        static final HomeHandoffTrace I = new HomeHandoffTrace();
        volatile int httpStatus = -2; // -2 never run, -1 IO failure
        volatile boolean sessionCookieSent = false;
        volatile boolean setCookieReceived = false;
        volatile int retryCount = 0;
        volatile String authLostReason = null;
        volatile String sessionCookieName = null; // NAME only, never value
        // v1.5.12: cookie-header composition evidence (names/counts only).
        volatile int sessionCookieEntries = 0;   // *-session entries in header
        volatile boolean sessionCookieDuplicate = false;
        volatile String decision = null;         // A / B / C comparison verdict
        // CookieManager.getCookie() exposes names+values only — no
        // domain/path metadata, so scope is reported as unavailable.

        static HomeHandoffTrace get() { return I; }
        String render() {
            return "probe status=" + (httpStatus == -2 ? "not run"
                            : httpStatus == -1 ? "IO failure" : String.valueOf(httpStatus))
                    + ", session sent=" + (sessionCookieSent ? "Y" : "N")
                    + ", set-cookie=" + (setCookieReceived ? "Y" : "N")
                    + ", retries=" + retryCount
                    + ", sessionCookieEntries=" + sessionCookieEntries
                    + ", duplicateSessionNames="
                    + (sessionCookieEntries > 1 ? "Y(" + sessionCookieEntries + ")" : "N")
                    + (sessionCookieName != null ? ", cookie=" + sessionCookieName : "")
                    + (authLostReason != null ? ", reason=" + authLostReason : "");
        }
    }

    /** v1.5.7: snapshot from the persistent driver trace (safe fields). */
    private DiagSnapshot traceSnapshot(String stage, String note) {
        QolabWebLoginDriver.Trace t = QolabWebLoginDriver.LAST;
        DiagSnapshot d = new DiagSnapshot(stage, 0, 0, stage,
                t.totalMs, -1, false);
        d.failedStage = stage;
        d.errMsg = (note == null ? "" : note) + " [" + t.render() + "]";
        d.docUrl = t.href;
        return d;
    }

    /**
     * Cookie VALUE for comparison only — never logged, never exposed.
     * v1.5.8: deterministic selection when multiple *-session candidates
     * exist (e.g. a legacy name alongside Laravel's qolab_session): the
     * LONGEST cookie NAME wins, so a stale/short legacy cookie can never
     * shadow the real session. Value is used in-memory for comparison
     * only.
     */
    private String sessionCookieValue(String origin) {
        try {
            String cookies = android.webkit.CookieManager.getInstance().getCookie(origin);
            if (cookies == null) return null;
            String bestName = null;
            String bestValue = null;
            for (String part : cookies.split(";")) {
                String p = part.trim();
                String name = p.split("=", 2)[0].trim();
                if (name.toLowerCase().endsWith("session")) {
                    if (bestName == null || name.length() > bestName.length()) {
                        bestName = name;
                        bestValue = p.contains("=") ? p.split("=", 2)[1] : "";
                    }
                }
            }
            return bestValue;
        } catch (Exception ignored) {
        }
        return null;
    }

    private void onLoginSuccess() {
        android.util.Log.i(TAG, "login success → HomeActivity");
        setSubmitting(false);
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void onLoginFailure(@Nullable String serverMessage) {
        setSubmitting(false);
        if (serverMessage != null && !serverMessage.isEmpty()) {
            showFieldError(formError, serverMessage);
        } else {
            showFieldError(formError, getString(R.string.login_error_generic));
        }
        // DEVELOPMENT-ONLY: surface the safe diagnostics panel after failure.
        // v1.5.7: ALWAYS record + show — if no driver snapshot exists, fall
        // back to the persistent trace (survives WebView destruction).
        if (BuildConfig.DEBUG && diagPanel != null) {
            if (lastFailure == null) {
                lastFailure = traceSnapshot(QolabWebLoginDriver.LAST.stage,
                        QolabWebLoginDriver.LAST.note);
            }
            diagPanel.setVisibility(View.VISIBLE);
            if (diagBody != null) diagBody.setVisibility(
                    diagExpanded ? View.VISIBLE : View.GONE);
            if (diagProbe != null) diagProbe.setVisibility(
                    diagExpanded ? View.VISIBLE : View.GONE);
            renderDiagnostics();
        }
        // Bring the error into view on small screens
        scroll.post(() -> formError.requestRectangleOnScreen(
                new android.graphics.Rect(0, 0, formError.getWidth(), formError.getHeight()), true));
    }

    // ── Button state ────────────────────────────────────────────────

    private void setSubmitting(boolean active) {
        submitting = active;
        signInButton.setEnabled(!active);
        signInButton.setText(active
                ? getString(R.string.login_signing_in)
                : getString(R.string.login_sign_in));
        signInProgress.setVisibility(active ? View.VISIBLE : View.GONE);
        arrowView.setVisibility(active ? View.INVISIBLE : View.VISIBLE);
        // Announce loading state for accessibility
        if (active) {
            signInButton.announceForAccessibility(getString(R.string.login_signing_in));
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    /** TextWatcher with no-op callbacks. */
    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
    }
}
