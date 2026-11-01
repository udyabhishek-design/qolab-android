package dev.qolab.qolab_app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Phase C3A — FCM entry point.
 *
 * Foreground: messages arrive in onMessageReceived; we show the same
 * high-priority notification (caller cannot see Flutter UI behind lock).
 * Background/terminated: Android displays the notification payload
 * directly; our data payload carries call_id for the action buttons.
 *
 * IMPORTANT: notification payloads NEVER carry LiveKit tokens or room
 * names — the Dart layer fetches everything authenticated via
 * GET /chat/calls/{id} after the user acts.
 */
public class QolabFirebaseMessagingService extends FirebaseMessagingService {

    private static final String CHANNEL_CALLS = CallActionReceiver.CALL_CHANNEL_ID;
    private static final String CHANNEL_UPDATES = "qolab_call_updates";

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        // Token refresh while the app may be backgrounded: store it via
        // broadcast; the Flutter layer persists + registers it on next
        // run. If the engine is dead, FirebaseMessaging's own token is
        // re-read on next start — registration is idempotent.
        Intent i = new Intent("dev.qolab.qolab_app.FCM_TOKEN");
        i.putExtra("token", token);
        sendBroadcast(i);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        String type = message.getData().get("type");

        if ("call.incoming".equals(type)) {
            showIncomingCallNotification(message);
            // Also start ringtone/vibration for foregrounded-locked devices.
            CallActionReceiver.startRinging(this);
        } else if ("call.update".equals(type)) {
            // Dismiss stale ringing notification on any transition.
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(CallActionReceiver.INCOMING_CALL_NOTIFICATION_ID);
            }
            CallActionReceiver.stopRinging();
        }
        // Other message types (chat etc.) — out of C3A scope.
    }

    private void showIncomingCallNotification(RemoteMessage message) {
        String callId = message.getData().get("call_id");
        String callType = message.getData().get("call_type");
        String callerName = message.getData().get("caller_name");
        String callerId = message.getData().get("caller_id");
        if (callId == null) {
            return;
        }

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        ensureChannels(nm);

        boolean video = "video".equals(callType);
        String title = callerName != null ? callerName : "Qolab call";
        String body = video ? "Incoming video call" : "Incoming audio call";

        // Content intent: open app on the call screen.
        PendingIntent content = actionIntent(this, 0, callId, callType, callerId, callerName,
                "android.intent.action.MAIN");

        NotificationCompat.Action decline = new NotificationCompat.Action(
                android.R.drawable.ic_delete, "Decline",
                actionIntent(this, 1, callId, callType, callerId, callerName,
                        CallActionReceiver.ACTION_DECLINE_CALL));

        NotificationCompat.Action accept = new NotificationCompat.Action(
                android.R.drawable.ic_menu_call, "Accept",
                actionIntent(this, 2, callId, callType, callerId, callerName,
                        CallActionReceiver.ACTION_ACCEPT_CALL));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_CALLS)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(true)
                .setContentIntent(content)
                .addAction(decline)
                .addAction(accept)
                .setDeleteIntent(actionIntent(this, 3, callId, null, null, null,
                        CallActionReceiver.ACTION_CALL_UPDATED))
                // Full-screen intent: rings over the lock screen.
                .setFullScreenIntent(actionIntent(this, 4, callId, callType, callerId,
                        callerName, CallActionReceiver.ACTION_ACCEPT_CALL), true);

        nm.notify(CallActionReceiver.INCOMING_CALL_NOTIFICATION_ID, builder.build());
    }

    private PendingIntent actionIntent(Context ctx, int requestCode, String callId,
            String callType, String callerId, String callerName, String action) {
        Intent i = new Intent(ctx, CallActionReceiver.class);
        i.setAction(action);
        i.putExtra(CallActionReceiver.EXTRA_CALL_ID, callId);
        i.putExtra(CallActionReceiver.EXTRA_CALL_TYPE, callType);
        i.putExtra(CallActionReceiver.EXTRA_CALLER_ID, callerId);
        i.putExtra(CallActionReceiver.EXTRA_CALLER_NAME, callerName);
        return PendingIntent.getBroadcast(ctx, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void ensureChannels(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel calls = new NotificationChannel(
                CHANNEL_CALLS, "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH);
        calls.setDescription("Qolab incoming call alerts");
        calls.setSound(null, null); // custom MediaPlayer ringtone instead
        calls.enableVibration(false); // custom Vibrator pattern instead
        calls.setBypassDnd(false);

        NotificationChannel updates = new NotificationChannel(
                CHANNEL_UPDATES, "Call status updates",
                NotificationManager.IMPORTANCE_DEFAULT);

        nm.createNotificationChannel(calls);
        nm.createNotificationChannel(updates);
    }
}
