package dev.qolab.qolab_app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

/**
 * v1.5.64 — creates the incoming-call notification channels EAGERLY at
 * process start.
 *
 * When the app is KILLED and an FCM notification+data message arrives,
 * the system tray presents the notification WITHOUT running any app
 * code. If the target channel (manifest default_notification_channel_id
 * / the notification's channel id) does not already exist, the
 * notification is DROPPED silently. Creating the channel here — on every
 * app start — guarantees it exists before the first incoming call can
 * possibly arrive.
 *
 * Channel attributes MUST stay identical to
 * QolabFirebaseMessagingService.ensureChannels (Android ignores updates
 * to existing channels except name/description/importance).
 */
public class QolabApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }

        NotificationChannel calls = new NotificationChannel(
                CallActionReceiver.CALL_CHANNEL_ID,
                "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH);
        calls.setDescription("Qolab incoming call alerts");
        calls.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        calls.enableVibration(true);
        calls.setVibrationPattern(
                new long[]{0, 600, 400, 600, 400, 600, 400});
        nm.createNotificationChannel(calls);

        NotificationChannel updates = new NotificationChannel(
                "qolab_call_updates",
                "Call status updates",
                NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(updates);
    }
}
