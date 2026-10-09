package dev.drytis.qolab;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

/**
 * v1.5.41 — AUTOMATIC NETWORK RECOVERY for the native screens.
 *
 * CONFIRMED DEVICE ISSUE: after disabling and restoring internet
 * connectivity, Home/Chat sat in their error state until the user
 * manually tapped Retry. Root cause: request failures were terminal —
 * nothing listened for connectivity restoration, so no automatic retry
 * ever happened (the Reverb client reconnected on its own backoff, but
 * the list/history HTTP data stayed stale or erroring).
 *
 * This watcher wraps a {@link ConnectivityManager.NetworkCallback} (API
 * 24+; a connectivity-action BroadcastReceiver fallback for API 23):
 *   - onAvailable() / CONNECTIVITY_ACTION with validated transport
 *     → fires {@link Recovery#onNetworkRestored()} on the main thread,
 *       DEBOUNCED (a fresh OS callback often arrives before the network
 *       can actually carry traffic) and at most once per activity
 *       lifecycle registration.
 *   - The hosting activity unregisters in onDestroy — no leaks, and
 *     background activities never fire recovery work.
 *
 * Retry discipline (the activities implement):
 *   - single-flight guards prevent duplicate requests on repeated
 *     callbacks (the debounce plus each screen's fetching/loading flag)
 *   - recovery re-uses the EXISTING load methods, so login security,
 *     the SessionGate and dedupe rules are untouched
 *   - attempts are bounded: recovery triggers only on the system
 *     "network available" edge (not a loop); manual Retry stays
 *     available if the load fails again.
 */
public final class ConnectivityWatcher {

    /** Host implements this; called on the main thread. */
    public interface Recovery {
        void onNetworkRestored();
    }

    private ConnectivityWatcher() {}

    /**
     * Register automatic recovery for an activity. Safe to call in
     * onCreate. Returns an opaque handle for {@link #unregister}.
     */
    public static Object register(Activity activity, Recovery recovery) {
        final Handler main = new Handler(Looper.getMainLooper());
        final Runnable[] fire = new Runnable[1];
        final boolean[] delivered = {false};

        // Debounce wrapper: single delivery per registration.
        Runnable deliver = () -> {
            if (delivered[0]) return;
            delivered[0] = true;
            try {
                recovery.onNetworkRestored();
            } catch (Exception ignored) {
            }
            // Allow a FUTURE restoration (after another loss) to fire
            // again — reset after the callback settles.
            main.postDelayed(() -> delivered[0] = false, 2_000L);
        };
        fire[0] = deliver;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager cm = (ConnectivityManager)
                    activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            ConnectivityManager.NetworkCallback cb =
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(@NonNull Network network) {
                            // Small settle delay: right at the edge the OS
                            // may report available before routes/DNS work.
                            main.postDelayed(fire[0], 400);
                        }
                    };
            try {
                NetworkRequest req = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                cm.registerNetworkCallback(req, cb);
            } catch (Exception e) {
                return null;
            }
            return cb;
        }
        // API 23 fallback: sticky broadcast receiver.
        BroadcastReceiver br = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (isConnected(context)) {
                    main.postDelayed(fire[0], 400);
                }
            }
        };
        try {
            activity.registerReceiver(br,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        } catch (Exception e) {
            return null;
        }
        return br;
    }

    /** Unregister in onDestroy. Null-safe for the API 23 path. */
    public static void unregister(Activity activity, Object handle) {
        if (handle == null) return;
        try {
            if (handle instanceof ConnectivityManager.NetworkCallback) {
                ConnectivityManager cm = (ConnectivityManager)
                        activity.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(
                            (ConnectivityManager.NetworkCallback) handle);
                }
            } else if (handle instanceof BroadcastReceiver) {
                activity.unregisterReceiver((BroadcastReceiver) handle);
            }
        } catch (Exception ignored) {
        }
    }

    /** Connected to something with internet capability (API 23-safe). */
    public static boolean isConnected(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network n = cm.getActiveNetwork();
                if (n == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                return caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return false;
        }
    }
}
