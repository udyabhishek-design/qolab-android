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

        networkExecutor.execute(() -> {
            QolabAuthClient client = new QolabAuthClient(appOrigin);
            android.util.Log.i(TAG, "sign-in start host=" + appHost);

            QolabAuthClient.LoginResult result = client.login(email, password, false);

            android.util.Log.i(TAG, "result success=" + result.success
                    + " stage=" + result.stage
                    + " csrfStatus=" + result.csrfStatus
                    + " loginStatus=" + result.status
                    + " xsrfCookie=" + result.xsrfCookiePresent
                    + " sessionCookie=" + result.sessionCookiePresent
                    + " redirect=" + result.redirectLocation
                    + (result.ioError == null ? "" : " ioError=" + result.ioError)
                    + " csrfMs=" + result.csrfDurationMs
                    + " loginMs=" + result.loginDurationMs
                    + " cookieNames=" + result.cookieNames);

            if (result.success) {
                // Persist the authenticated session into the WebView so the
                // shell behaves exactly like a browser login — and VERIFY the
                // store actually retained it before navigating (setCookie on
                // some WebView versions is asynchronous).
                boolean bridged = writeCookiesToWebView(result.cookies);
                android.util.Log.i(TAG, "cookie bridge ok=" + bridged
                        + " cookieManagerHasSession=" + cookieManagerHasSession());
                if (bridged) {
                    runOnUiThread(this::onLoginSuccess);
                } else {
                    lastFailure = new DiagSnapshot(result, "cookie-bridge", "verification failed", 0);
                    runOnUiThread(() -> onLoginFailure(null));
                }
                return;
            }

            String message;
            if (result.status == 422) {
                message = QolabAuthClient.parseErrorMessage(client.lastBody());
            } else {
                message = null;
            }
            final String msg = message;
            lastFailure = new DiagSnapshot(result, result.stage, result.ioError, 0);
            runOnUiThread(() -> onLoginFailure(msg));
        });
    }

    // ── DEVELOPMENT-ONLY diagnostics (v1.4.4, temporary) ───────────
    // Safe fields ONLY: origin host, stage, statuses, durations, exception
    // class name, yes/no flags. Never email/password/token/cookie VALUES.

    /** Snapshot of the last failed attempt (null until one happened). */
    private static final class DiagSnapshot {
        final String originHost;
        final String stage;
        final int csrfStatus;
        final int loginStatus;
        final String failureType;
        final long csrfMs;
        final long loginMs;
        final boolean xsrfCookie;
        final boolean sessionCookie;
        final boolean cookieBridgeOk;
        final boolean mainActivityLaunched;

        DiagSnapshot(QolabAuthClient.LoginResult r, String stage, String failureType,
                     long probeUnused) {
            this.originHost = safeHostOf(r);
            this.stage = stage == null ? "?" : stage;
            this.csrfStatus = r.csrfStatus;
            this.loginStatus = r.status;
            this.failureType = failureType == null
                    ? (r.status == 422 ? "credentials rejected (422)"
                       : r.status > 0 ? "HTTP error " + r.status : "unknown")
                    : failureType;
            this.csrfMs = r.csrfDurationMs;
            this.loginMs = r.loginDurationMs;
            this.xsrfCookie = r.xsrfCookiePresent;
            this.sessionCookie = r.sessionCookiePresent;
            this.cookieBridgeOk = false;
            this.mainActivityLaunched = false;
        }

        private static String safeHostOf(QolabAuthClient.LoginResult r) {
            // Cookie NAMES are safe; derive host from the first cookie name
            // context is unnecessary — the origin host is fixed per build.
            return null; // filled by the activity (has BuildConfig origin)
        }
    }

    private DiagSnapshot lastFailure = null;
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
            sb.append("Network available: ").append(networkAvailable()).append('\n');
            sb.append("Session cookie received: ")
                    .append(lastFailure.csrfStatus == 0 && lastFailure.loginStatus == 0
                            ? "Not reached" : lastFailure.sessionCookie ? "Yes" : "No").append('\n');
            sb.append("Cookie bridge: ").append(lastFailure.cookieBridgeOk ? "Yes" : "No").append('\n');
            sb.append("MainActivity launched: ").append(lastFailure.mainActivityLaunched ? "Yes" : "No");
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
     * Write the authenticated cookies into the WebView CookieManager and
     * VERIFY the store retained the session cookie. Returns true only when
     * the read-back confirms a *-session cookie for the app origin.
     */
    private boolean writeCookiesToWebView(List<QolabAuthClient.Cookie> cookies) {
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            for (QolabAuthClient.Cookie c : cookies) {
                // Skip the CSRF cookie — the WebView re-does its own handshake.
                if ("XSRF-TOKEN".equalsIgnoreCase(c.name)) continue;
                String cookieValue = String.format(java.util.Locale.US,
                        "%s=%s; Path=/; Secure", c.name, c.value);
                cm.setCookie(appOrigin + "/", cookieValue);
            }
            cm.flush();
            // Read-back verification: CookieManager.setCookie is documented
            // as synchronous in practice, but flush() persistence and some
            // OEM implementations are not; verify the session cookie is
            // actually retrievable before we navigate anywhere.
            return cookieManagerHasSession();
        } catch (Exception e) {
            android.util.Log.w(TAG, "cookie bridge failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** Safe: checks cookie NAMES only. */
    private boolean cookieManagerHasSession() {
        try {
            String cookies = android.webkit.CookieManager.getInstance().getCookie(appOrigin);
            if (cookies == null) return false;
            for (String part : cookies.split(";")) {
                String name = part.split("=", 2)[0].trim();
                if (name.toLowerCase().endsWith("session")) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void onLoginSuccess() {
        android.util.Log.i(TAG, "login success → MainActivity");
        setSubmitting(false);
        Intent i = new Intent(this, MainActivity.class);
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
        // (In release builds bindDiagnosticsViews() has already removed it.)
        if (BuildConfig.DEBUG && diagPanel != null && lastFailure != null) {
            diagPanel.setVisibility(View.VISIBLE);
            if (diagExpanded) renderDiagnostics();
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
