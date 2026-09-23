package dev.drytis.qolab;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
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
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // Track the last app-origin URL for state restoration.
                String host = url == null ? null : Uri.parse(url).getHost();
                if (host != null && isAllowedHost(host, navBridge)) {
                    lastLoadedUrl = url;
                }
                if (BuildConfig.DEBUG) {
                    android.util.Log.i("QolabAuth", "webview navigation: " + url
                            + (url != null && url.endsWith("/login") ? "  ← redirected to /login" : ""));
                }
                // Session expired while using the app: Laravel redirects to
                // /login. NEVER show the old web login — route cleanly back
                // to the native LoginActivity instead. Guarded so a redirect
                // storm can't launch it twice (no loop: LoginActivity waits
                // for user input before returning here).
                if (url != null && url.equals(appOriginUrl() + "/login")
                        && !authRedirectHandled) {
                    authRedirectHandled = true;
                    Log.i("QolabAuth", "session expired → native LoginActivity");
                    Intent login = new Intent(MainActivity.this, LoginActivity.class);
                    login.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(login);
                    finish();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                dismissSplash();
            }
        });

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
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            String cookies = cm.getCookie(appOriginUrl());
            if (cookies == null) return false;
            for (String part : cookies.split(";")) {
                String name = part.split("=", 2)[0].trim();
                if (name.toLowerCase().endsWith("session")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
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
