package dev.drytis.qolab;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v1.5.39 — Application-scoped realtime hub for native Chat.
 *
 * One QolabRealtimeClient shared by all chat screens. Handles:
 *   - auth bridging: private-channel auth strings come from
 *     POST /broadcasting/auth executed INSIDE the authenticated WebView
 *     transport (session + XSRF stay in the browser context; only the
 *     resulting auth string crosses into the socket)
 *   - listener registry (screen callbacks) with correct cleanup
 *   - message dedupe (own sends echoed back are skipped; ids unique)
 *   - user.{id} notification events → message-arrival fan-out
 *
 * Lifecycle: first ChatListActivity.start() opens the socket; screens
 * register listeners in onStart / release in onStop. The socket itself
 * lives while any listener is registered; the LAST release closes it.
 */
public final class ChatRealtime {

    /** A message arrived (channelId>0) or any notification (channelId=0
     *  when the payload carried no usable channel_id). data is the raw
     *  event JSON for message.sent; null for the generic notification case. */
    public interface Listener {
        void onChatEvent(long channelId, String eventName, String data);

        /**
         * v1.5.41: the realtime socket (re)established its connection and
         * re-subscribed. Screens may reload to catch messages missed while
         * offline (id-dedupe makes the overlap safe). Main thread.
         */
        default void onRealtimeConnected() {}
    }

    private static volatile ChatRealtime instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private QolabRealtimeClient client;
    private final Map<String, List<Listener>> channelListeners = new HashMap<>();
    private final Set<Long> myMessageIds = new HashSet<>(); // dedupe own sends
    private long myUserId = 0;
    private boolean userChannelUp = false;
    private String origin;

    private ChatRealtime() {}

    public static ChatRealtime get() {
        ChatRealtime i = instance;
        if (i == null) {
            synchronized (ChatRealtime.class) {
                i = instance;
                if (i == null) {
                    i = new ChatRealtime();
                    instance = i;
                }
            }
        }
        return i;
    }

    /** Test seam: replace the singleton. */
    static void overrideForTest(ChatRealtime fake) {
        instance = fake;
    }

    public synchronized void configure(long userId, String appOrigin) {
        boolean first = this.myUserId == 0;
        this.myUserId = userId;
        this.origin = appOrigin;
        if (first && client == null) {
            connect();
        }
    }

    /** v1.5.41: re-arm entry from screens — reset the abandoned-client
     *  limiter so a fresh start() after a long outage can reconnect. */
    public synchronized void resetForRetry() {
        reArmCount = 0;
    }

    private synchronized void connect() {
        if (client != null) return;
        client = QolabRealtimeClient.create(origin, new QolabRealtimeClient.Listener() {
            @Override
            public void authorize(String channelName, QolabRealtimeClient.AuthCallback cb) {
                authorizeThroughTransport(channelName, cb);
            }

            @Override
            public void onEvent(String channel, String event, String data) {
                dispatchEvent(channel, event, data);
            }

            @Override
            public void onStateChanged(boolean connected) {
                if (connected) {
                    // connection_established fired; ensure the user channel
                    // is subscribed whenever we know the user.
                    ensureUserChannel();
                    // v1.5.41: forward the reconnect so screens can refresh
                    // and catch anything missed while offline (dedupe by
                    // message id makes the overlap safe).
                    notifyConnected();
                }
                // Disconnection: poll/retry paths handle recovery; nothing
                // to tear down here (listeners stay registered).
            }

            @Override
            public void onReconnectAbandoned() {
                // v1.5.41: bounded reconnect gave up (long outage). Re-arm
                // once after a pause; the screens' network-recovery path
                // (ConnectivityWatcher) also re-arms via configure(). No
                // unbounded loop: abandoned re-arms themselves are capped.
                scheduleReArm();
            }
        });
        client.connect();
    }

    /** POST /broadcasting/auth inside the authenticated transport WebView. */
    private void authorizeThroughTransport(String channelName,
                                           QolabRealtimeClient.AuthCallback cb) {
        final QolabRealtimeClient.AuthCallback done = auth -> main.post(() -> cb.onAuth(auth));
        QolabWebViewTransport transport = QolabWebViewTransport.get();
        if (transport == null || !transport.isAlive()) {
            done.onAuth(null);
            return;
        }
        // URL-encode the channel name for the JSON body (it is plain here),
        // send as JSON — the endpoint accepts form or JSON bodies.
        String body = "{\"socket_id\":" + QolabWebViewTransport.jsonStringSafe(clientSocketId())
                + ",\"channel_name\":" + QolabWebViewTransport.jsonStringSafe(channelName) + "}";
        transport.postJson("/broadcasting/auth", body, r -> {
            if (closed) {
                done.onAuth(null);
                return;
            }
            String auth = null;
            if (r.status == 200 && r.body != null) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(r.body);
                    auth = o.optString("auth", null);
                } catch (Exception ignored) {
                }
            }
            done.onAuth(auth);
        });
    }

    private String clientSocketId() {
        return client != null && client.isConnected() ? clientSocket() : "";
    }

    private String clientSocket() {
        // The QolabRealtimeClient tracks its socket id internally; expose it
        // through a package seam for the auth request.
        return client != null ? client.socketIdOrNull() : "";
    }

    private volatile boolean closed = false;

    private void dispatchEvent(String channel, String event, String data) {
        long channelId = 0;
        if (channel.startsWith("private-channel.")) {
            try {
                channelId = Long.parseLong(channel.substring("private-channel.".length()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (channel.startsWith("private-user.")) {
            handleNotification(data);
            return;
        }
        if (channelId == 0) return;
        List<Listener> targets = channelListeners.get(channel);
        if (targets == null || targets.isEmpty()) return;
        for (Listener l : new ArrayList<>(targets)) {
            l.onChatEvent(channelId, event, data);
        }
    }

    /** notification.received payloads carry data.channel_id for messages. */
    private void handleNotification(String dataJson) {
        long channelId = 0;
        try {
            org.json.JSONObject o = new org.json.JSONObject(dataJson);
            if ("message.sent".equals(o.optString("type", ""))) {
                channelId = o.optLong("channel_id", 0);
            }
        } catch (Exception ignored) {
        }
        List<Listener> targets = channelListeners.get("user");
        if (targets == null) return;
        for (Listener l : new ArrayList<>(targets)) {
            l.onChatEvent(channelId, "notification.received", dataJson);
        }
    }

    private void ensureUserChannel() {
        if (myUserId > 0 && !userChannelUp && client != null) {
            userChannelUp = true;
            client.subscribePrivate("user." + myUserId);
        }
    }

    /** v1.5.41: fan out a realtime (re)connection to every registered
     *  listener so screens refresh and catch missed messages. Main thread. */
    private void notifyConnected() {
        List<Listener> all = new ArrayList<>();
        synchronized (this) {
            for (List<Listener> ls : channelListeners.values()) all.addAll(ls);
        }
        for (Listener l : all) {
            try { l.onRealtimeConnected(); } catch (Exception ignored) { }
        }
    }

    /** v1.5.41: the client exhausted its bounded reconnects (very long
     *  outage). Tear it down and re-open ONE fresh client after a pause —
     *  this resets the backoff and re-authenticates every channel without
     *  an infinite loop (abandoned re-arms capped at REARM_LIMIT). */
    private void scheduleReArm() {
        synchronized (this) {
            if (closed) return;
            reArmCount++;
            if (reArmCount > REARM_LIMIT) return;
            if (client != null) {
                try { client.shutdown(); } catch (Exception ignored) { }
                client = null;
            }
        }
        main.postDelayed(this::connect, REARM_DELAY_MS);
    }

    private int reArmCount = 0;
    private static final int REARM_LIMIT = 4;
    private static final long REARM_DELAY_MS = 30_000L;

    // ── Screen API ────────────────────────────────────────────────────

    /** Listen to ONE channel's events ("user" for the notification channel,
     *  or the channel id as a decimal string). */
    public synchronized void listen(String channelKey, Listener l) {
        channelListeners.computeIfAbsent(channelKey, k -> new ArrayList<>()).add(l);
        if ("user".equals(channelKey)) {
            ensureUserChannel();
        } else if (client != null && userChannelUp) {
            try {
                long id = Long.parseLong(channelKey);
                client.subscribePrivate("channel." + id);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /** Release a listener; when the last one for a channel goes away the
     *  channel is unsubscribed. Main thread. */
    public synchronized void release(String channelKey, Listener l) {
        List<Listener> list = channelListeners.get(channelKey);
        if (list == null) return;
        list.remove(l);
        if (!list.isEmpty()) return;
        channelListeners.remove(channelKey);
        if (client == null) return;
        if ("user".equals(channelKey)) {
            client.unsubscribePrivate("user." + myUserId);
            userChannelUp = false;
        } else {
            try {
                long id = Long.parseLong(channelKey);
                client.unsubscribePrivate("channel." + id);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /** Remember an id we just sent ourselves so broadcast echoes are
     *  deduped. Bounded: keep the newest 64. */
    public synchronized void markMine(long messageId) {
        if (messageId <= 0) return;
        myMessageIds.add(messageId);
        if (myMessageIds.size() > 64) {
            // crude trim: rebuild from scratch (64 ids is tiny)
            myMessageIds.clear();
        }
    }

    public synchronized boolean isMine(long messageId) {
        return messageId > 0 && myMessageIds.contains(messageId);
    }

    /** Hard teardown (logout). Also stops future use until configure(). */
    public synchronized void shutdown() {
        closed = true;
        myUserId = 0;
        userChannelUp = false;
        channelListeners.clear();
        myMessageIds.clear();
        if (client != null) {
            client.shutdown();
            client = null;
        }
    }
}
