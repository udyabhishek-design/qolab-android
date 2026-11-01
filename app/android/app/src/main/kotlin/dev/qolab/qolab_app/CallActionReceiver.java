package dev.qolab.qolab_app;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.annotation.NonNull;

/**
 * Phase C3A — native helper behind FCM notification action buttons.
 *
 * Not registered statically in the manifest: MainActivity registers it in
 * configureFlutterEngine so it only exists while the Flutter engine is
 * alive. The accept/decline intents from FCM notifications are directed
 * here via explicit intent extras (see FcmCallNotifications).
 */
public class CallActionReceiver extends BroadcastReceiver {
    public static final String ACTION_INCOMING_CALL =
            "dev.qolab.qolab_app.CALL_INCOMING";
    public static final String ACTION_ACCEPT_CALL =
            "dev.qolab.qolab_app.CALL_ACCEPT";
    public static final String ACTION_DECLINE_CALL =
            "dev.qolab.qolab_app.CALL_DECLINE";
    public static final String ACTION_CALL_UPDATED =
            "dev.qolab.qolab_app.CALL_UPDATED";

    public static final String EXTRA_CALL_ID = "call_id";
    public static final String EXTRA_CALL_TYPE = "call_type";
    public static final String EXTRA_CALLER_ID = "caller_id";
    public static final String EXTRA_CALLER_NAME = "caller_name";
    public static final String EXTRA_STATUS = "status";

    public static final String CALL_CHANNEL_ID = "qolab_calls";
    public static final int INCOMING_CALL_NOTIFICATION_ID = 2001;

    private static MediaPlayer ringtonePlayer;
    private static Vibrator vibrator;

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        String action = intent.getAction() != null ? intent.getAction() : "";
        switch (action) {
            case ACTION_ACCEPT_CALL:
            case ACTION_DECLINE_CALL: {
                // Hand off to the Dart side via launch intent; Dart layer
                // performs the authenticated POST. Dismiss notification.
                android.app.NotificationManager nm = (android.app.NotificationManager)
                        context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(INCOMING_CALL_NOTIFICATION_ID);
                }
                stopRinging();

                Intent launch = new Intent(context, MainActivity.class);
                launch.setAction(action);
                launch.putExtra(EXTRA_CALL_ID, intent.getStringExtra(EXTRA_CALL_ID));
                launch.putExtra(EXTRA_CALL_TYPE, intent.getStringExtra(EXTRA_CALL_TYPE));
                launch.putExtra(EXTRA_CALLER_ID, intent.getStringExtra(EXTRA_CALLER_ID));
                launch.putExtra(EXTRA_CALLER_NAME, intent.getStringExtra(EXTRA_CALLER_NAME));
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(launch);
                break;
            }
            case ACTION_INCOMING_CALL: {
                startRinging(context);
                break;
            }
            case ACTION_CALL_UPDATED: {
                // Any transition (accept/decline/cancel/missed/end) stops
                // ringing and dismisses stale notifications.
                stopRinging();
                android.app.NotificationManager nm = (android.app.NotificationManager)
                        context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(INCOMING_CALL_NOTIFICATION_ID);
                }
                break;
            }
            default:
                break;
        }
    }

    // ── ringtone / vibration (shared with the Flutter layer) ──

    public static synchronized void startRinging(Context context) {
        stopRinging();
        try {
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (sound == null) {
                sound = RingtoneManager.getActualDefaultRingtoneUri(
                        context, RingtoneManager.TYPE_RINGTONE);
            }
            if (sound != null) {
                ringtonePlayer = new MediaPlayer();
                ringtonePlayer.setDataSource(context, sound);
                ringtonePlayer.setAudioStreamType(AudioManager.STREAM_RING);
                ringtonePlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                ringtonePlayer.setLooping(true);
                ringtonePlayer.prepare();
                ringtonePlayer.start();
            }
        } catch (Exception ignored) {
            // ringtone is best-effort; vibration still runs
        }

        try {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 600, 400};
                int repeatIndex = 0;
                VibrationEffect effect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? VibrationEffect.createWaveform(pattern, repeatIndex)
                        : VibrationEffect.createWaveform(pattern, repeatIndex);
                vibrator.vibrate(effect);
            }
        } catch (Exception ignored) {
        }
    }

    public static synchronized void stopRinging() {
        try {
            if (ringtonePlayer != null) {
                if (ringtonePlayer.isPlaying()) {
                    ringtonePlayer.stop();
                }
                ringtonePlayer.release();
            }
        } catch (Exception ignored) {
        }
        ringtonePlayer = null;
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {
            }
            vibrator = null;
        }
    }
}
