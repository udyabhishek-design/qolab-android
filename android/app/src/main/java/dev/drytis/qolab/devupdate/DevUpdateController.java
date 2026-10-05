package dev.drytis.qolab.devupdate;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.drytis.qolab.BuildConfig;
import dev.drytis.qolab.R;

/**
 * DEVELOPMENT-ONLY in-app APK updater.
 *
 * Flow: startup (delayed, async) or manual menu check → fetch latest.json →
 * DevUpdateChecker decision → [Later]/[Update] prompt → DownloadManager to the
 * app's external files dir (no storage permission needed) → validate the file
 * (DevApkValidator + signature check vs installed app) → Android package
 * installer via FileProvider URI with FLAG_GRANT_READ_URI_PERMISSION. Never
 * silent; never uninstalls the app; never touches WebView session/cookie
 * data — the manifest fetch is a plain unauthenticated GET of public static
 * files.
 *
 * Everything is inert in release builds: {@link DevUpdateGuard#isEnabled()}
 * is compile-time false there, so every entry point returns immediately.
 */
public final class DevUpdateController {

    public static final String MANIFEST_URL = BuildConfig.QOLAB_ORIGIN + "/apk/latest.json";

    private static final long STARTUP_DELAY_MS = 1500;

    private final Activity activity;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BroadcastReceiver downloadReceiver;
    private long downloadId = -1;
    private AlertDialog progressDialog;
    private boolean busy;

    /** Set when the user went to system settings for install permission with a pending update. */
    private DevUpdateManifest pendingManifest;

    public DevUpdateController(Activity activity) {
        this.activity = activity;
    }

    // ------------------------------------------------------------------
    // Entry points (guard-checked — unreachable in release)
    // ------------------------------------------------------------------

    /** Async, non-blocking startup check; silently does nothing when current. */
    public void maybeCheckOnStartup() {
        if (!DevUpdateGuard.isEnabled()) return;
        main.postDelayed(this::checkQuietly, STARTUP_DELAY_MS);
    }

    /** Manual check with user feedback (dev menu entry). */
    public void checkNow() {
        if (!DevUpdateGuard.isEnabled()) return;
        checkWithFeedback();
    }

    /** Call from the host Activity's onResume — continues after settings return. */
    public void onResume() {
        if (!DevUpdateGuard.isEnabled()) return;
        if (pendingManifest != null && canRequestInstalls()) {
            DevUpdateManifest m = pendingManifest;
            pendingManifest = null;
            startDownload(m);
        }
    }

    public void onDestroy() {
        if (downloadReceiver != null) {
            try {
                activity.unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {
            }
            downloadReceiver = null;
        }
        io.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Check
    // ------------------------------------------------------------------

    private void checkQuietly() {
        fetchManifest(manifest -> {
            if (manifest == null) return; // network unavailable / malformed → do nothing
            if (DevUpdateChecker.check(manifest, BuildConfig.VERSION_CODE)
                    == DevUpdateChecker.Decision.UPDATE_AVAILABLE) {
                showPrompt(manifest);
            }
        });
    }

    private void checkWithFeedback() {
        if (busy) return;
        busy = true;
        Toast.makeText(activity, R.string.dev_update_checking, Toast.LENGTH_SHORT).show();
        fetchManifest(manifest -> {
            busy = false;
            if (manifest == null) {
                Toast.makeText(activity, R.string.dev_update_check_failed,
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (DevUpdateChecker.check(manifest, BuildConfig.VERSION_CODE)
                    == DevUpdateChecker.Decision.UPDATE_AVAILABLE) {
                showPrompt(manifest);
            } else {
                Toast.makeText(activity, R.string.dev_update_up_to_date,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Plain unauthenticated GET — no cookies, no session, public static file. */
    private void fetchManifest(Callback cb) {
        io.execute(() -> {
            String body = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Cache-Control", "no-cache");
                if (c.getResponseCode() == 200) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    try (InputStream in = c.getInputStream()) {
                        byte[] chunk = new byte[4096];
                        int n;
                        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                    }
                    if (buf.size() > 0 && buf.size() < 64 * 1024) {
                        body = new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            } catch (IOException ignored) {
                body = null;
            } finally {
                if (c != null) c.disconnect();
            }
            final String b = body;
            main.post(() -> cb.onFetched(
                    b == null ? null : DevUpdateManifest.parse(b, originHost())));
        });
    }

    private interface Callback {
        void onFetched(DevUpdateManifest manifest);
    }

    private String originHost() {
        try {
            return java.net.URI.create(BuildConfig.QOLAB_ORIGIN).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Prompt
    // ------------------------------------------------------------------

    private void showPrompt(DevUpdateManifest m) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.dev_update_available_title)
                .setMessage(activity.getString(R.string.dev_update_available_msg, m.versionName))
                .setPositiveButton(R.string.dev_update_action_update,
                        (d, w) -> startDownload(m))
                .setNegativeButton(R.string.dev_update_action_later, null)
                .show();
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    private void startDownload(DevUpdateManifest m) {
        if (!canRequestInstalls()) {
            explainInstallPermission(m);
            return;
        }
        File dir = downloadDir();
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(activity, R.string.dev_update_download_failed, Toast.LENGTH_LONG).show();
            return;
        }
        File target = new File(dir, "qolab-dev-update.apk");
        if (target.exists()) target.delete();

        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(m.apkUrl))
                .setTitle(activity.getString(R.string.dev_update_download_title))
                .setDestinationUri(Uri.fromFile(target))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true);
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            Toast.makeText(activity, R.string.dev_update_download_failed, Toast.LENGTH_LONG).show();
            return;
        }
        downloadId = dm.enqueue(req);
        registerCompletion(dm);
        showProgressDialog(dm);
    }

    private File downloadDir() {
        return new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "dev-updates");
    }

    private void registerCompletion(DownloadManager dm) {
        if (downloadReceiver != null) return;
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;
                onDownloadComplete(dm, id);
            }
        };
        activity.registerReceiver(downloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    private void onDownloadComplete(DownloadManager dm, long id) {
        File target = new File(downloadDir(), "qolab-dev-update.apk");
        boolean failed = false;
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
        try (Cursor c = dm.query(q)) {
            if (c != null && c.moveToFirst()) {
                if (c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS))
                        != DownloadManager.STATUS_SUCCESSFUL) {
                    failed = true;
                }
            } else {
                failed = true;
            }
        } catch (Exception e) {
            failed = true;
        }
        dismissProgressDialog();
        if (failed || !target.exists()) {
            cleanup(dm, target, id);
            Toast.makeText(activity, R.string.dev_update_download_failed, Toast.LENGTH_LONG).show();
            return;
        }

        // Security validation BEFORE the installer is ever launched
        DevApkValidator.Result r = DevApkValidator.validate(
                target.getAbsolutePath(), BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE);
        if (!r.ok || !signaturesMatch(target)) {
            cleanup(dm, target, id);
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.dev_update_invalid_title)
                    .setMessage(activity.getString(R.string.dev_update_invalid_msg,
                            r.ok ? "signature mismatch" : r.reason))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        launchInstaller(target);
    }

    private void cleanup(DownloadManager dm, File target, long id) {
        if (target != null && target.exists()) target.delete();
        try {
            dm.remove(id);
        } catch (Exception ignored) {
        }
    }

    private void showProgressDialog(DownloadManager dm) {
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView label = new TextView(activity);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        label.setPadding(pad, pad, pad, 0);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label);
        box.addView(bar);
        progressDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.dev_update_download_title)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    if (downloadId >= 0) dm.remove(downloadId);
                    downloadId = -1;
                })
                .show();
        pollProgress(dm, bar, label);
    }

    private void pollProgress(DownloadManager dm, ProgressBar bar, TextView label) {
        if (progressDialog == null || !progressDialog.isShowing()) return;
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor c = dm.query(q)) {
            if (c != null && c.moveToFirst()) {
                long total = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                long done = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                if (total > 0) bar.setProgress((int) (done * 100 / total));
                label.setText(activity.getString(
                        R.string.dev_update_download_progress, done / 1024, total / 1024));
            }
        } catch (Exception ignored) {
        }
        main.postDelayed(() -> pollProgress(dm, bar, label), 500);
    }

    private void dismissProgressDialog() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignored) {
            }
            progressDialog = null;
        }
    }

    // ------------------------------------------------------------------
    // Installer + permissions
    // ------------------------------------------------------------------

    private boolean canRequestInstalls() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        return activity.getPackageManager().canRequestPackageInstalls();
    }

    private void explainInstallPermission(DevUpdateManifest m) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.dev_update_permission_title)
                .setMessage(R.string.dev_update_permission_msg)
                .setPositiveButton(R.string.dev_update_permission_open_settings, (d, w) -> {
                    pendingManifest = m; // onResume() re-checks and continues the flow
                    try {
                        activity.startActivity(new Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + BuildConfig.APPLICATION_ID)));
                    } catch (Exception e) {
                        activity.startActivity(new Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Best-effort signing-certificate comparison between the downloaded APK
     * and the currently installed app. Fails CLOSED (rejects) when either
     * side cannot be read.
     */
    private boolean signaturesMatch(File apk) {
        try {
            PackageManager pm = activity.getPackageManager();
            PackageInfo downloaded;
            PackageInfo installed;
            if (Build.VERSION.SDK_INT >= 28) {
                downloaded = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                installed = pm.getPackageInfo(BuildConfig.APPLICATION_ID,
                        PackageManager.GET_SIGNING_CERTIFICATES);
                if (downloaded == null || installed == null
                        || downloaded.signingInfo == null || installed.signingInfo == null
                        || downloaded.signingInfo.hasMultipleSigners()
                        || installed.signingInfo.hasMultipleSigners()) return false;
                return downloaded.signingInfo.getApkContentsSigners()[0]
                        .equals(installed.signingInfo.getApkContentsSigners()[0]);
            } else {
                downloaded = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                        PackageManager.GET_SIGNATURES);
                installed = pm.getPackageInfo(BuildConfig.APPLICATION_ID,
                        PackageManager.GET_SIGNATURES);
                if (downloaded == null || installed == null
                        || downloaded.signatures == null || installed.signatures == null
                        || downloaded.signatures.length == 0 || installed.signatures.length == 0) {
                    return false;
                }
                return downloaded.signatures[0].equals(installed.signatures[0]);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void launchInstaller(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    BuildConfig.APPLICATION_ID + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(uri);
            intent.setType("application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) {
            apk.delete();
            Toast.makeText(activity, R.string.dev_update_download_failed, Toast.LENGTH_LONG).show();
        }
    }
}
