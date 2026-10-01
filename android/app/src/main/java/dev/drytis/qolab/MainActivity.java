package dev.drytis.qolab;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Handler;
import android.widget.Toast;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.drytis.qolab.BuildConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Qolab Android shell — Phase 1.
 *
 * Loads the existing Qolab Laravel + React/Inertia web app inside a hardened
 * Capacitor WebView. No web application code is duplicated or rewritten.
 *
 * Behavior implemented here:
 *   - Remote-app load from capacitor.config.json `server.url` (non-production).
 *   - App-origin navigation allowlist: any foreign host opens in the system
 *     browser and is never loaded inside the shell.
 *   - External links -> system browser (also covers target=_blank/window.open).
 *   - Android back button -> WebView history (Inertia history) when possible,
 *     otherwise default (exit) behavior.
 *   - File picker / uploads for <input type="file"> (WebChromeClient
 *     onShowFileChooser).
 *   - Authenticated downloads: session-cookie requests intercepted on
 *     DownloadListener and routed through the system via ACTION_VIEW intents
 *     or the Download Manager.
 *   - Microphone permission preparation for LiveKit: bridges WebView
 *     onPermissionRequest (getUserMedia) to the Android RECORD_AUDIO runtime
 *     permission; camera bridged the same way.
 *   - Android-safe WebView configuration: JavaScript + DOM storage on, file
 *     access off, mixed content blocked, safe geolocation default-deny.
 */
public class MainActivity extends BridgeActivity {

    private static final String TAG = "QolabShell";

    /** Allowed hosts for in-app navigation. App origin comes from the bridge server URL. */
    private static final String[] EXTRA_ALLOWED_HOSTS = new String[0];

    private static final int REQ_PERM_MIC = 7001;
    private static final int REQ_PERM_CAM = 7002;

    @Nullable
    private PermissionRequest pendingWebPermissionRequest = null;

    /** Resources the pending web PermissionRequest actually asked for. */
    @Nullable
    private String[] pendingWebPermissionResources = null;

    /** Android permissions requested for the current pending web request. */
    @Nullable
    private String[] pendingAndroidPermissions = null;

    @Nullable
    private ValueCallback<Uri[]> filePathCallback = null;

    // ---- v1.3: cold-start UX + state restoration ----
    /** Overlay shown until the first remote page finishes rendering. */
    @Nullable
    private LinearLayout splashOverlay = null;
    private boolean splashDismissed = false;
    private boolean restoredFromState = false;
    private String lastLoadedUrl = null;
    /** One-shot guard so an expired-session /login redirect can't loop. */
    private boolean authRedirectHandled = false;

    /**
     * v1.4.9: the authenticated session has ended (logout POST completed on
     * the server, or the session expired). Called ONLY from the /login
     * interception paths — i.e. AFTER Laravel confirmed logged-out state
     * (Fortify already invalidated the session; the /login redirect IS the
     * confirmation). Sequence:
     *   1. freeze the WebView immediately (no web-login flash),
     *   2. expire ONLY the app-origin *-session cookies (names only;
     *      unrelated cookies/domains untouched),
     *   3. launch the native LoginActivity as the NEW TASK ROOT
     *      (Back cannot return to the authenticated screen),
     *   4. finish this activity.
     * Idempotent via authRedirectHandled — no duplicate launches, no loop
     * (LoginActivity only returns here after a fresh successful login).
     */
    private void handleLoggedOut() {
        if (authRedirectHandled) return;
        authRedirectHandled = true;

        // 1. Freeze instantly — the web login must never visibly render.
        try {
            WebView frozen = getBridge() != null ? getBridge().getWebView() : null;
            if (frozen != null) {
                frozen.stopLoading();
                frozen.onPause();
                frozen.setVisibility(View.GONE);
            }
        } catch (Exception ignored) {
        }

        // 2. Clean local authenticated state: expire only the app-origin
        //    *-session cookies. The SERVER session is already invalidated
        //    by Laravel (this method runs after the logout redirect).
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            String origin = appOriginUrl();
            String header = cm.getCookie(origin);
            for (String name : AuthNavRules.sessionCookieNames(header)) {
                cm.setCookie(origin + "/", AuthNavRules.expireCookieValue(name));
            }
            cm.flush();
            android.util.Log.i("QolabAuth", "logged-out: session cookies expired ("
                    + AuthNavRules.sessionCookieNames(header).size() + ")");
        } catch (Exception e) {
            android.util.Log.w("QolabAuth", "cookie cleanup failed: "
                    + e.getClass().getSimpleName());
        }

        // 3. Native login becomes the new task root — the authenticated
        //    activity is finished, so Back cannot resurrect it.
        Log.i("QolabAuth", "logged-out → native LoginActivity (task reset)");
        Intent login = new Intent(this, LoginActivity.class);
        login.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(login);

        // 4. Remove any authenticated page from WebView history/state.
        try {
            WebView wv2 = getBridge() != null ? getBridge().getWebView() : null;
            if (wv2 != null) wv2.clearHistory();
        } catch (Exception ignored) {
        }
        finish();
    }
    /** Failsafe timeout so the splash can never permanently block the UI. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long SPLASH_FAILSAFE_MS = 12_000;
    /** Delay before actually removing the splash after render, for smooth fade. */
    private static final long SPLASH_FADE_MS = 250;

    /** Tracks whether a permission dialog is showing so onPause doesn't kill it prematurely. */

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (filePathCallback == null) return;
                Uri[] results = null;
                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    results = new Uri[]{ result.getData().getData() };
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bridge bridge = getBridge();
        WebView webView = bridge.getWebView();

        // ---- v1.4: native login gate ----
        // On a fresh launch (not a state restore) check whether the WebView
        // CookieManager already holds a session cookie for the app origin.
        // Unauthenticated → show the native Qolab Mobile login screen first;
        // on success it writes the authenticated session cookie and returns.
        if (savedInstanceState == null) {
            String startPath = getIntent() != null
                    ? getIntent().getStringExtra("qolab_start_path") : null;
            // Public pages (forgot-password) skip the login gate.
            if (!"/forgot-password".equals(startPath) && !hasSessionCookie()) {
                Log.i("QolabAuth", "gate: no session cookie → LoginActivity");
                Intent login = new Intent(this, LoginActivity.class);
                startActivity(login);
                finish();
                return;
            }
            // Home V1: with a valid session and no explicit deep-link path,
            // the app lands on the NATIVE Home screen. The WebView shell is
            // only entered through explicit start paths from Home surfaces.
            if (startPath == null || startPath.isEmpty()) {
                Log.i("QolabAuth", "gate: session cookie present → native HomeActivity");
                Intent home = new Intent(this, HomeActivity.class);
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(home);
                finish();
                return;
            }
            Log.i("QolabAuth", "gate: session cookie present → WebView start");
            if (startPath != null && !startPath.isEmpty()) {
                String url = appOriginUrl() + startPath;
                Log.i("QolabAuth", "gate: explicit start path " + startPath);
                webView.loadUrl(url);
            }
        }

        restoredFromState = savedInstanceState != null;

        // ---- v1.3: Splash until first render ----
        attachSplashOverlay(webView);

        // ---- v1.3: WebView performance flags ----
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        try {
            WebSettings s0 = webView.getSettings();
            // Raster even when offscreen so returning to the app shows content
            // instantly instead of a blank frame.
            s0.setOffscreenPreRaster(true);
        } catch (Exception ignored) {
            // Not supported on very old WebView implementations — safe to skip.
        }
        // WebView debugging only on debug builds (release builds get it OFF).
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        // ---- Navigation allowlist ----
        // App origin (from server.url config) stays in the WebView; every other
        // host and every non-http(s) scheme (mailto:, tel:, intent:) is handed
        // to the system browser / matching app.
        Bridge navBridge = bridge;
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri uri = request.getUrl();
                String url = uri.toString();
                // v1.4.9: any navigation toward the app's /login page means
                // the authenticated session ended (intentional logout or
                // expiry). Intercept BEFORE the page loads — the old web
                // Login must never render.
                if (!authRedirectHandled && AuthNavRules.isLoginUrl(url, appOriginUrl())) {
                    android.util.Log.i("QolabAuth", "/login navigation intercepted (nav)");
                    handleLoggedOut();
                    return true;
                }
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
                if (!scheme.equals("https") && !scheme.equals("http")) {
                    openExternally(uri);
                    return true;
                }
                String host = uri.getHost();
                if (host != null && !isAllowedHost(host, navBridge)) {
                    openExternally(uri);
                    return true;
                }
                return false; // same-origin app navigation — load in WebView
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
                // v1.4.9: Inertia performs its post-logout redirect as a
                // client-side XHR page visit — that NEVER fires
                // shouldOverrideUrlLoading or onPageStarted. Catch the /login
                // page GET here, before the response can render.
                try {
                    String url = request.getUrl().toString();
                    String method = request.getMethod();
                    if (method != null && method.equalsIgnoreCase("GET")
                            && !authRedirectHandled
                            && AuthNavRules.isLoginUrl(url, appOriginUrl())) {
                        android.util.Log.i("QolabAuth", "/login request intercepted (inertia xhr)");
                        view.post(MainActivity.this::handleLoggedOut);
                    }
                } catch (Exception ignored) {
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // Track the last app-origin URL for state restoration.
                String host = url == null ? null : Uri.parse(url).getHost();
                if (host != null && isAllowedHost(host, navBridge)) {
                    lastLoadedUrl = url;
                }
                if (BuildConfig.DEBUG) {
                    android.util.Log.i("QolabAuth", "webview navigation: " + url
                            + (AuthNavRules.isLoginUrl(url, appOriginUrl())
                               ? "  ← redirected to /login" : ""));
                }
                // Session expired while using the app (classic full-page
                // redirect path). The /login page must never become visible.
                if (!authRedirectHandled && AuthNavRules.isLoginUrl(url, appOriginUrl())) {
                    android.util.Log.i("QolabAuth", "/login navigation intercepted (page-start)");
                    handleLoggedOut();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                dismissSplash();
                installLogoutTakeover(view);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                // v1.4.10: last-resort visibility gate — if any /login page
                // ever reaches the commit-visible stage (missed by all
                // earlier callbacks), transition before pixels settle.
                if (!authRedirectHandled && AuthNavRules.isLoginUrl(url, appOriginUrl())) {
                    android.util.Log.i("QolabAuth", "/login caught at commit-visible");
                    handleLoggedOut();
                }
            }
        });
        // v1.4.10: JS bridge the fetch wrapper calls. Must be registered
        // BEFORE the page's scripts run — do it once here, ahead of load.
        registerLogoutBridge(webView);
        // v1.4.11: persistent DEBUG-only diagnostics entry point.
        installAuthDebugOverlay();

        WebSettings s = webView.getSettings();
        // Required for a modern SPA (Inertia/React), consistent with defaults.
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // Android-safe hardening.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMediaPlaybackRequiresUserGesture(false); // Music Lounge / LiveKit audio
        // Meeting "Start/Join" uses window.open('about:blank') + popup.location
        // (popup-blocker-safe pattern). Multi-window must be ON for that to work;
        // onCreateWindow below routes the popup target through the allowlist.
        s.setSupportMultipleWindows(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Zoom controls off for app-like UX.
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    fileChooserLauncher.launch(params.createIntent());
                } catch (Exception e) {
                    Log.w(TAG, "File chooser launch failed", e);
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // Bridge WebView permission requests (getUserMedia for LiveKit
                // mic/camera) to Android runtime permissions.
                String[] webResources = request.getResources();
                List<String> needed = new ArrayList<>();
                boolean wantsMic = false, wantsCam = false;
                for (String r : webResources) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsCam = true;
                }
                if (wantsMic && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.RECORD_AUDIO);
                }
                if (wantsCam && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.CAMERA);
                }
                if (needed.isEmpty()) {
                    runOnUiThread(() -> {
                        try { request.grant(request.getResources()); } catch (Exception ignored) {}
                    });
                    return;
                }
                // Stash BOTH the web request and exactly the resources it asked
                // for, plus the Android permissions we're about to request. We
                // only ever grant a subset of what the site requested.
                pendingWebPermissionRequest = request;
                pendingWebPermissionResources = request.getResources();
                pendingAndroidPermissions = needed.toArray(new String[0]);
                final List<String> finalNeeded = needed;
                runOnUiThread(() ->
                        requestPermissions(finalNeeded.toArray(new String[0]),
                                REQ_PERM_MIC | REQ_PERM_CAM));
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                // Qolab does not use geolocation — deny by default (safe).
                callback.invoke(origin, false, false);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                // window.open(...) from the Qolab app (e.g. the popup-blocker-safe
                // meeting Start/Join pattern). With setSupportMultipleWindows(true)
                // the WebView requires us to create the target WebView here.
                //
                // Smallest safe approach: a temporary transparent WebView that we
                // attach, let it resolve the final URL, then route that URL through
                // the SAME navigation allowlist (app origin loads in the main
                // WebView; foreign hosts like the LiveKit meeting studio open in
                // the system browser, which handles mic/camera natively).
                if (!isUserGesture) {
                    return false; // block non user-initiated popups
                }
                final WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                final WebView popup = new WebView(MainActivity.this);
                popup.getSettings().setJavaScriptEnabled(false); // probe only
                popup.setWebViewClient(new android.webkit.WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView pv, android.webkit.WebResourceRequest request) {
                        Uri uri = request.getUrl();
                        try { popup.stopLoading(); } catch (Exception ignored) {}
                        try { popup.destroy(); } catch (Exception ignored) {}
                        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
                        if (!scheme.equals("https") && !scheme.equals("http")) {
                            openExternally(uri);
                        } else {
                            String host = uri.getHost();
                            if (host != null && isAllowedHost(host, navBridge)) {
                                // Same-origin target (e.g. PDF/document views):
                                // load in the MAIN WebView via Inertia-compatible
                                // navigation, preserving session cookies.
                                runOnUiThread(() -> {
                                    WebView main = getBridge().getWebView();
                                    if (main != null) main.loadUrl(uri.toString());
                                });
                            } else {
                                // Foreign host (e.g. LiveKit studio on meeting.drytis.dev):
                                // system browser handles WebRTC/mic natively.
                                openExternally(uri);
                            }
                        }
                        return true;
                    }
                });
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });

        // Authenticated downloads: session cookies flow through the WebView's
        // network stack, so intercept downloads here and hand them to the
        // system Download Manager (keeps cookies + auth headers).
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(Uri.parse(url));
                req.setMimeType(mimetype);
                String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
                if (cookies != null) req.addRequestHeader("cookie", cookies);
                req.addRequestHeader("User-Agent", userAgent);
                req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String name = URLUtil_nameFromUrl(url, contentDisposition, mimetype);
                req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name);
                req.setAllowedOverMetered(true);
                req.setAllowedOverRoaming(true);
                android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) dm.enqueue(req);
            } catch (Exception e) {
                Log.w(TAG, "Download failed", e);
                // Fallback: let the system try (e.g. view intent)
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {}
            }
        });

        // Persist session cookies so login survives app restarts.
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, false);
        }
        cm.flush();

        // ---- v1.3: Restore navigation state after process death/recreation ----
        if (restoredFromState && savedInstanceState != null) {
            android.os.Bundle webState = savedInstanceState.getBundle("qolab_webview_state");
            if (webState != null) {
                try {
                    webView.restoreState(webState);
                } catch (Exception ignored) {
                    // Fall back to normal (server.url) load.
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // v1.3: Splash overlay — dark Qolab-branded placeholder shown until the
    // remote app renders its first page. Never shows a blank white WebView.
    // ---------------------------------------------------------------------
    private void attachSplashOverlay(WebView webView) {
        if (splashOverlay != null) return;
        LinearLayout splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setGravity(Gravity.CENTER);
        splash.setBackgroundColor(Color.parseColor("#0b0e14")); // Qolab dark slate
        splash.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("Qolab");
        title.setTextColor(Color.parseColor("#e6e9ef"));
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        splash.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(this);
        sub.setText("Connecting…");
        sub.setTextColor(Color.parseColor("#8b94a7"));
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = 12;
        splash.addView(sub, subLp);

        android.view.ViewGroup content = (android.view.ViewGroup) webView.getParent();
        content.addView(splash, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        splashOverlay = splash;
        splash.bringToFront();

        // Failsafe: never trap the user behind the splash.
        mainHandler.postDelayed(this::dismissSplash, SPLASH_FAILSAFE_MS);
    }

    private void dismissSplash() {
        if (splashDismissed || splashOverlay == null) return;
        splashDismissed = true;
        final LinearLayout splash = splashOverlay;
        splashOverlay = null;
        mainHandler.post(() -> {
            AlphaAnimation fade = new AlphaAnimation(1f, 0f);
            fade.setDuration(SPLASH_FADE_MS);
            fade.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
                @Override public void onAnimationStart(android.view.animation.Animation animation) {}
                @Override public void onAnimationRepeat(android.view.animation.Animation animation) {}
                @Override public void onAnimationEnd(android.view.animation.Animation animation) {
                    try {
                        ((android.view.ViewGroup) splash.getParent()).removeView(splash);
                    } catch (Exception ignored) {}
                }
            });
            splash.startAnimation(fade);
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            Bundle webState = new Bundle();
            WebView webView = getBridge() != null ? getBridge().getWebView() : null;
            if (webView != null) {
                webView.saveState(webState);
                outState.putBundle("qolab_webview_state", webState);
                if (lastLoadedUrl != null) {
                    outState.putString("qolab_last_url", lastLoadedUrl);
                }
            }
        } catch (Exception ignored) {
            // State save is best-effort.
        }
    }

    @Override
    public void onStop() {
        // Flush cookies whenever the app goes to background so the session
        // survives even if the process is killed.
        try {
            android.webkit.CookieManager.getInstance().flush();
        } catch (Exception ignored) {}
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Only react to our WebView-permission bridge requests (combined code).
        if (requestCode != (REQ_PERM_MIC | REQ_PERM_CAM)) {
            return;
        }

        PermissionRequest webRequest = pendingWebPermissionRequest;
        String[] webResources = pendingWebPermissionResources;
        pendingWebPermissionRequest = null;
        pendingWebPermissionResources = null;
        pendingAndroidPermissions = null;
        if (webRequest == null || webResources == null) {
            return; // stale/cancelled — nothing to answer
        }

        // Map Android results back to the web resources the site actually
        // requested. Grant ONLY resources whose Android permission was granted
        // AND which the site asked for.
        boolean micGranted = false, camGranted = false;
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            if (Manifest.permission.RECORD_AUDIO.equals(permissions[i])
                    && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                micGranted = true;
            }
            if (Manifest.permission.CAMERA.equals(permissions[i])
                    && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                camGranted = true;
            }
        }

        List<String> toGrant = new ArrayList<>();
        for (String r : webResources) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && micGranted) toGrant.add(r);
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && camGranted) toGrant.add(r);
        }

        runOnUiThread(() -> {
            try {
                if (toGrant.isEmpty()) {
                    webRequest.deny();
                } else {
                    webRequest.grant(toGrant.toArray(new String[0]));
                }
            } catch (Exception ignored) {
                // Request already answered/gone (lifecycle) — safe to ignore.
            }
        });
    }

    /** Deny any pending web permission request on destroy (lifecycle safety). */
    @Override
    public void onDestroy() {
        PermissionRequest webRequest = pendingWebPermissionRequest;
        pendingWebPermissionRequest = null;
        pendingWebPermissionResources = null;
        pendingAndroidPermissions = null;
        if (webRequest != null) {
            try { webRequest.deny(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private static String URLUtil_nameFromUrl(String url, String contentDisposition, String mimetype) {
        String fallback = "qolab-download";
        try {
            String n = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype);
            return n == null ? fallback : n;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Navigation allowlist: app origin (and any EXTRA_ALLOWED_HOSTS) stays in
     * the WebView; everything else goes to the system browser.
     */
    private boolean isAllowedHost(String host, Bridge bridge) {
        String origin = bridge.getServerUrl();
        if (origin != null) {
            try {
                String originHost = Uri.parse(origin).getHost();
                if (originHost != null && originHost.equalsIgnoreCase(host)) return true;
            } catch (Exception ignored) {}
        }
        for (String allowed : EXTRA_ALLOWED_HOSTS) {
            if (allowed.equalsIgnoreCase(host)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // v1.4: login gate helpers
    // ---------------------------------------------------------------------

    /** App origin (capacitor server.url) without trailing slash. */
    private String appOriginUrl() {
        try {
            String origin = getBridge().getServerUrl();
            if (origin != null) return origin.replaceAll("/+$", "");
        } catch (Exception ignored) {}
        // Environment-aware fallback matching the build (debug=dev, release=prod).
        return BuildConfig.QOLAB_ORIGIN.replaceAll("/+$", "");
    }

    /**
     * True when the WebView CookieManager already holds a (possibly
     * authenticated) session cookie for the app origin. Cheap heuristic:
     * the backend sets its session cookie (qolab-session / laravel_session)
     * on the first CSRF handshake or login. Absent → assume signed out.
     */
    private boolean hasSessionCookie() {
        // v1.4.9: shared logic — names only, never values.
        return !AuthNavRules.sessionCookieNames(
                android.webkit.CookieManager.getInstance().getCookie(appOriginUrl())).isEmpty();
    }

    /**
     * v1.4.9: takeover of the SPA's logout. The web Logout button does
     * several async things (presence beacon, heartbeat teardown, Inertia
     * POST /logout); a stale XSRF token made that POST 419 — the UI stayed
     * on /dashboard and the user tapped again. We intercept
     * window.fetch('.../logout', {method:'POST'}) in the page and hand it
     * to the native client, which ALWAYS performs a fresh CSRF handshake
     * first and retries once on 419 — so one tap deterministically
     * invalidates the server session. The native client then triggers the
     * same handleLoggedOut() path used for expiry (freeze → expire app
     * session cookies → native LoginActivity as task root). CSRF is never
     * bypassed; only the transport of the exact same request moves to Java.
     */
    private void installLogoutTakeover(WebView view) {
        // v1.4.11: the web app's Inertia core uses XMLHttpRequest (verified
        // in the shipped bundle) — the XHR takeover is the real interceptor.
        // The fetch wrapper is kept as belt-and-braces for any fetch users.
        view.evaluateJavascript(AuthNavRules.buildXhrTakeoverJs(), null);
        view.evaluateJavascript(AuthNavRules.buildLogoutTakeoverJs(), null);
        // v1.4.12: verify BOTH takeover + bridge are actually live in the
        // page's JS context, and re-assert the flag on every page finish
        // (a page reload recreates the JS context — the old one-shot check
        // after injection raced Inertia's init and reported No wrongly).
        view.evaluateJavascript(
            "(function(){try{var xhrOk=window.__qolabXhrTakeover===true;"
            + "var bridgeOk=!!window.QolabNative;"
            + "window.QolabNative.takeoverInstalled(xhrOk&&bridgeOk, xhrOk, bridgeOk);"
            + "}catch(e){window.QolabNative.takeoverInstalled(false,false,false);}})()",
            null);
    }

    /**
     * v1.4.10: perform the ENTIRE logout transaction natively, then the
     * unified logged-out transition. Invoked by the injected JS
     * (window.QolabNative.logout()) returning a Promise-compatible object
     * so the Inertia visit completes. One tap → one server transaction
     * (fresh CSRF handshake → POST /logout → 419 retry) → transition.
     */
    public void registerLogoutBridge(WebView view) {
        view.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public org.json.JSONObject logout() {
                // v1.4.12: NEVER a native-HTTP logout (that split cookie
                // contexts → 419s, physically proven). This entry point now
                // only records the tap and runs the logout INSIDE this same
                // authenticated WebView's browser context; the result comes
                // back asynchronously via onLogoutResult().
                onLogoutTapped();
                return null;
            }

            @android.webkit.JavascriptInterface
            public void onLogoutResult(String json) {
                // Async result from the WebView-context transaction.
                try {
                    org.json.JSONObject r = new org.json.JSONObject(json);
                    onWebviewLogoutResult(r.optInt("status", 0), r.optInt("attempts", 0),
                            r.optLong("ms", 0), r.optBoolean("xsrfPresent", false),
                            r.optString("error", null));
                } catch (Exception e) {
                    onWebviewLogoutResult(0, 0, 0, false, "BadResultJson");
                }
            }

            @android.webkit.JavascriptInterface
            public void takeoverInstalled(boolean allOk, boolean xhrFlag, boolean bridgeOk) {
                LogoutTrace.takeoverInstalled = allOk;
                LogoutTrace.xhrTakeoverFlag = xhrFlag;
                LogoutTrace.bridgeAvailable = bridgeOk;
                android.util.Log.i("QolabAuth", "takeover state: xhr=" + xhrFlag
                        + " bridge=" + bridgeOk);
            }
        }, "QolabNative");
    }

    /** Single-flight guard: only ONE logout transaction may run at a time. */
    private volatile boolean logoutInFlight = false;

    /** Records a Logout tap and kicks off the WebView-context transaction. */
    private void onLogoutTapped() {
        final LogoutTrace.Attempt a = LogoutTrace.begin(lastLoadedUrl);
        a.uiLogoutTapped = true;
        a.bridgeInvoked = true;
        a.webviewSessionBefore = hasSessionCookie();
        a.webviewXsrfBefore = hasXsrfCookie();
        android.util.Log.i("QolabAuth", "logout tap #" + a.tapIndex
                + " (context: webview browser)");
        if (logoutInFlight) {
            a.note = "ignored: transaction already in flight";
            android.util.Log.w("QolabAuth", "logout tap while in flight — ignored");
            return;
        }
        logoutInFlight = true;
        a.nativeTransactionStarted = true; // transaction = webview-context run
        a.csrfStarted = true;
        mainHandler.post(() -> {
            WebView wv = getBridge() != null ? getBridge().getWebView() : null;
            if (wv == null) {
                finishLogoutTransaction(a, 0, 0, "NoWebView");
                return;
            }
            // Execute INSIDE the authenticated WebView — same cookie
            // context that serves /dashboard. Result → onLogoutResult.
            wv.evaluateJavascript(AuthNavRules.buildWebviewLogoutJs(), null);
        });
    }

    private void onWebviewLogoutResult(int status, int attempts, long ms,
                                       boolean xsrfPresent, String error) {
        LogoutTrace.Attempt a = LogoutTrace.current;
        if (a == null) {
            a = LogoutTrace.begin(lastLoadedUrl);
            a.uiLogoutTapped = true;
            a.bridgeInvoked = true;
        }
        a.csrfStatus = 204; // handshake precedes POST inside the JS
        a.csrfMs = -1;
        a.postStarted = true;
        a.postStatus = status;
        a.postMs = ms;
        a.httpAttempts = attempts;
        a.had419 = status == 419;
        a.ioError = error;
        a.serverConfirmed = status / 100 == 2 || status / 100 == 3;
        a.webviewSessionAfter = hasSessionCookie();
        a.webviewXsrfAfter = hasXsrfCookie();
        finishLogoutTransaction(a, status, attempts, error);
    }

    private void finishLogoutTransaction(LogoutTrace.Attempt a, int status,
                                         int attempts, String error) {
        logoutInFlight = false;
        boolean ok = a.serverConfirmed;
        a.webviewUrlAfter = lastLoadedUrl;
        android.util.Log.i("QolabAuth", "logout #" + a.tapIndex + " result: http=" + status
                + " attempts=" + attempts + " confirmed=" + ok);
        mainHandler.post(() -> {
            a.transitionRequested = ok;
            if (ok) {
                handleLoggedOut();
                a.nativeLoginLaunched = true;
            } else {
                android.util.Log.w("QolabAuth", "logout failed: http " + status
                        + (error != null ? " / " + error : ""));
                Toast.makeText(MainActivity.this,
                        "Couldn't sign out. Please try again.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private boolean hasXsrfCookie() {
        try {
            String header = android.webkit.CookieManager.getInstance().getCookie(appOriginUrl());
            if (header == null) return false;
            for (String part : header.split(";")) {
                if (part.trim().startsWith("XSRF-TOKEN=")) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * v1.4.12: REMOVED the native-HTTP logout (nativeLogout()). Physical
     * trace proved it split cookie contexts (CSRF GET via HttpURLConnection
     * + hand-copied jar ≠ the authenticated WebView session) → 419 every
     * time. The logout HTTP transaction now runs INSIDE the authenticated
     * WebView (see registerLogoutBridge / buildWebviewLogoutJs); native
     * Android still owns the post-success transition (handleLoggedOut).
     */

    /** DEBUG-only persistent overlay button showing the live logout trace. */
    private void installAuthDebugOverlay() {
        if (!BuildConfig.DEBUG) return;
        try {
            android.widget.Button b = new android.widget.Button(this);
            b.setText("Auth Debug");
            b.setTextSize(11);
            b.setAlpha(0.75f);
            b.setOnClickListener(v -> showAuthDebug());
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                            android.view.Gravity.BOTTOM | android.view.Gravity.START);
            lp.bottomMargin = 24;
            lp.leftMargin = 8;
            addContentView(b, lp);
        } catch (Exception ignored) {
        }
    }

    private void showAuthDebug() {
        String text = "Takeover JS installed: "
                + (LogoutTrace.takeoverInstalled ? "Yes" : "No") + "\n"
                + "QolabNative reachable from JS: "
                + (LogoutTrace.bridgeAvailable ? "Yes" : "No") + "\n"
                + "Native logout invocations: " + LogoutTrace.attempts.size() + "\n"
                + "Session cookie present now: " + (hasSessionCookie() ? "Yes" : "No")
                + "\n\n" + LogoutTrace.render();
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(text);
        tv.setPadding(48, 24, 48, 24);
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Logout trace")
                .setView(sv)
                .setPositiveButton("Close", null)
                .show();
    }

    /** DEBUG-only on-screen logout diagnostics (no values ever shown). */
    private void showLogoutDiagnostics(boolean ok, QolabAuthClient.LogoutResult r,
                                       boolean sessionCookiePresent) {
        try {
            String text = "Logout tap count: 1\n"
                    + "Logout HTTP: " + (r != null ? r.status : "—") + "\n"
                    + "Attempts: " + (r != null ? r.attempts : "—") + "\n"
                    + "Fresh CSRF retry: " + (r != null && r.retriedWithFreshCsrf ? "Yes" : "No") + "\n"
                    + "Server confirmed logout: " + (ok ? "Yes" : "No") + "\n"
                    + "Session cookie after logout: " + (sessionCookiePresent ? "Yes" : "No") + "\n"
                    + "Native Login launched: " + (ok ? "Yes" : "No");
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Development diagnostics")
                    .setMessage(text)
                    .setPositiveButton("Close", null)
                    .show();
        } catch (Exception ignored) {
        }
    }

    private void openExternally(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "No handler for external URL: " + uri, e);
        }
    }

    @Override
    public void onBackPressed() {
        WebView webView = getBridge().getWebView();
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
