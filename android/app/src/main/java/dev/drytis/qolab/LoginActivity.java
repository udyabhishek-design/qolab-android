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
    }

    private String readAppOrigin() {
        // Capacitor embeds the server url into the app assets config; the
        // bridge in MainActivity has it too, but LoginActivity runs before
        // the bridge exists. Read it from the copied capacitor config asset.
        try {
            java.io.InputStream is = getAssets().open("capacitor.config.json");
            java.util.Scanner s = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
            String json = s.hasNext() ? s.next() : "";
            s.close();
            org.json.JSONObject cfg = new org.json.JSONObject(json);
            String url = cfg.optJSONObject("server") != null
                    ? cfg.optJSONObject("server").optString("url", null) : null;
            if (url != null && !url.isEmpty()) return url.replaceAll("/+$", "");
        } catch (Exception ignored) {
        }
        return "https://qolab.drytis.com";
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
            QolabAuthClient.LoginResult result = client.login(email, password, false);

            if (result.success) {
                // Persist the authenticated session into the WebView so the
                // shell behaves exactly like a browser login.
                writeCookiesToWebView(result.cookies);
                runOnUiThread(this::onLoginSuccess);
                return;
            }

            String message;
            if (result.status == 422) {
                message = QolabAuthClient.parseErrorMessage(client.lastBody());
            } else {
                message = null;
            }
            final String msg = message;
            runOnUiThread(() -> onLoginFailure(msg));
        });
    }

    private void writeCookiesToWebView(List<QolabAuthClient.Cookie> cookies) {
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
        } catch (Exception ignored) {
        }
    }

    private void onLoginSuccess() {
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
