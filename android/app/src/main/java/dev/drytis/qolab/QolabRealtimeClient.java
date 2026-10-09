package dev.drytis.qolab;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * v1.5.39 — Minimal Pusher-protocol WebSocket client for Laravel Reverb
 * (protocol 7). Lives INSIDE the app process; the session never leaves the
 * device: private-channel auth happens through the existing authenticated
 * WebView transport (POST /broadcasting/auth with the page's XSRF cookie
 * value), and only the returned auth STRING is handed to the socket.
 *
 * Responsibilities (and nothing more):
 *   - connect wss://{origin-host}:443/app/{public app key}
 *   - pusher:ping ⇄ pusher:pong keepalive
 *   - subscribe private-{name} (auth via listener), public {name}
 *   - deliver every event on the main thread exactly once
 *   - bounded automatic reconnect with backoff (network interruption/recovery)
 *   - clean shutdown: no leaked threads, listeners or sockets
 *
 * JVM-testable: socket interactions are funneled through the WebSocketSink
 * interface; the pure protocol state (event routing, resubscribe queue,
 * reconnect schedule, keepalive bookkeeping) is static logic under test.
 */
public final class QolabRealtimeClient {

    /** Public client key of the Qolab Reverb app (safe — shipped in every
     *  web page bundle). Dev and prod run the same key on different hosts. */
    public static final String REVERB_APP_KEY = "E6bKH3QNfxtlNpk7ByiX";

    /** Host to connect to: derived at runtime from BuildConfig.QOLAB_ORIGIN. */

    public interface Listener {
        /** Authorize a private channel. Must call back on the MAIN thread. */
        void authorize(String channelName, AuthCallback cb);

        void onEvent(String channel, String event, String data);

        /** Connection state changed (connected/disconnected). Main thread. */
        void onStateChanged(boolean connected);

        /**
         * v1.5.41: reconnect attempts exhausted (bounded backoff gave up).
         * The host should surface a retryable state; the next
         * configure()/resume re-arms the client. Default no-op.
         */
        default void onReconnectAbandoned() {}
    }

    public interface AuthCallback {
        void onAuth(String auth);
    }

    /** Socket factory seam — OkHttp in production, fake in unit tests. */
    public interface WebSocketSink {
        boolean send(String text);
        void close(int code, String reason);
    }

    // ── Pure protocol helpers (JVM-tested) ────────────────────────────

    /** Event names that belong to the connection, not to channels. */
    static boolean isConnectionEvent(String event) {
        return event != null && event.startsWith("pusher:");
    }

    /** Route a raw server frame → parsed {channel,event,data}; null = skip. */
    public static JSONObject parseFrame(String text) {
        try {
            JSONObject o = new JSONObject(text);
            if (!o.has("event")) return null;
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Build a subscribe frame with an auth string (may be null → public). */
    public static String subscribeFrame(String channel, String auth) {
        JSONObject data = new JSONObject();
        try {
            data.put("channel", channel);
            if (auth != null) data.put("auth", auth);
        } catch (Exception ignored) {
        }
        return frame("pusher:subscribe", data);
    }

    public static String pongFrame() {
        return frame("pusher:pong", new JSONObject());
    }

    public static String pingFrame() {
        return frame("pusher:ping", new JSONObject());
    }

    private static String frame(String event, JSONObject data) {
        JSONObject o = new JSONObject();
        try {
            o.put("event", event);
            o.put("data", data);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    /** Socket id from the connection_established payload; null when absent. */
    public static String socketIdFromEstablished(String dataJson) {
        try {
            // data is a JSON-encoded STRING containing an object
            JSONObject inner = new JSONObject(dataJson);
            return inner.optString("socket_id", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Channel/event pair for pusher internal events (channel in "channel"
     *  field). Returns the user-visible event name; null when skip-worthy. */
    public static boolean isSubscriptionSucceeded(String event) {
        return "pusher_internal:subscription_succeeded".equals(event);
    }

    /** Reconnect delay: 1s, 2s, 4s, 8s, capped at 8s from attempt 4 on. */
    public static long reconnectDelayMs(int attempt) {
        long d = 1000L << Math.min(Math.max(attempt, 1) - 1, 3);
        return Math.min(d, 8_000L);
    }

    // ── Client ────────────────────────────────────────────────────────

    private static final int MAX_LISTENED_CHANNELS = 120;

    /** v1.5.41: reconnect backoff cap (1,2,4,8s) — ~10 minutes total. */
    private static final int MAX_RECONNECT_ATTEMPTS = 128;

    private final OkHttpClient http;
    private final String wsUrl;

    private final Listener listener;

    /** Lazy main handler (JVM tests construct this class without a Looper). */
    private volatile android.os.Handler main;
    private android.os.Handler main() {
        android.os.Handler h = main;
        if (h == null) {
            synchronized (this) {
                h = main;
                if (h == null) {
                    h = new android.os.Handler(android.os.Looper.getMainLooper());
                    main = h;
                }
            }
        }
        return h;
    }

    private WebSocketSink sink;            // main thread only
    private String socketId;
    private volatile boolean closed = false;
    private int reconnectAttempt = 0;
    private final AtomicInteger connects = new AtomicInteger();

    /** Channel name (private-...) → true while subscribed. Main thread. */
    private final Map<String, Boolean> subscribed = new HashMap<>();
    /** Channels waiting for authorization. Main thread. */
    private final Map<String, Boolean> pendingAuth = new HashMap<>();

    /** Injectable for tests: pass null in production to use OkHttp. */
    QolabRealtimeClient(String wsUrl, OkHttpClient client, Listener listener) {
        this.wsUrl = wsUrl;
        this.http = client;
        this.listener = listener;
    }

    public static QolabRealtimeClient create(String origin, Listener listener) {
        String host = hostOf(origin);
        String url = "wss://" + host + ":443/app/" + REVERB_APP_KEY
                + "?protocol=7&client=js";
        OkHttpClient c = new OkHttpClient.Builder()
                .pingInterval(java.time.Duration.ofSeconds(25))
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();
        return new QolabRealtimeClient(url, c, listener);
    }

    static String hostOf(String origin) {
        if (origin == null) return "";
        String s = origin.replaceAll("/+$", "");
        s = s.replaceFirst("^https://", "").replaceFirst("^http://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s;
    }

    /** Start connecting. Safe to call once per client. Main thread. */
    public synchronized void connect() {
        if (closed || sink != null) return;
        openSocket();
    }

    private void openSocket() {
        if (http == null) return; // test double without a socket
        Request req = new Request.Builder().url(wsUrl).build();
        final int gen = connects.incrementAndGet();
        http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                main().post(() -> handleOpen(ws, gen));
            }

            @Override public void onMessage(WebSocket ws, String text) {
                main().post(() -> handleMessage(text, gen));
            }

            @Override public void onFailure(WebSocket ws, Throwable t,
                                            Response response) {
                main().post(() -> handleClosed(gen, true));
            }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                main().post(() -> handleClosed(gen, false));
            }
        });
    }

    private void handleOpen(WebSocket ws, int gen) {
        if (closed || connects.get() != gen) {
            ws.close(1000, "superseded");
            return;
        }
        sink = new WebSocketSink() {
            @Override public boolean send(String text) {
                return ws.send(text);
            }

            @Override public void close(int code, String reason) {
                ws.close(code, reason);
            }
        };
        socketId = null;
        reconnectAttempt = 0;
    }

    private void handleMessage(String text, int gen) {
        if (closed || connects.get() != gen) return;
        JSONObject frame = parseFrame(text);
        if (frame == null) return;
        String event = frame.optString("event", "");
        if (isConnectionEvent(event)) {
            if ("pusher:connection_established".equals(event)) {
                socketId = socketIdFromEstablished(frame.optString("data", ""));
                // Re-subscribe everything we own (fresh socket after reconnect)
                resubscribeAll();
                listener.onStateChanged(true);
            } else if ("pusher:ping".equals(event)) {
                if (sink != null) sink.send(pongFrame());
            }
            // pusher:pong / error: keepalive handled at transport level
            return;
        }
        String channel = frame.optString("channel", "");
        if (channel.isEmpty()) return;
        String data = frame.optString("data", "{}");
        if (isSubscriptionSucceeded(event)) {
            // Server confirmed: pending → subscribed (or an unsolicited
            // confirm still counts as subscribed).
            pendingAuth.remove(channel);
            subscribed.put(channel, Boolean.TRUE);
            return;
        }
        listener.onEvent(channel, event, data);
    }

    private void handleClosed(int gen, boolean failed) {
        if (closed || connects.get() != gen) return;
        sink = null;
        socketId = null;
        subscribed.clear();
        pendingAuth.clear();
        listener.onStateChanged(false);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closed) return;
        reconnectAttempt++;
        if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS) {
            // v1.5.41: bounded — stop looping after ~10 min of failures
            // (attempts at 1,2,4,8s×cap). The owning screen's resume /
            // network-recovery path re-arms the socket by re-configuring.
            listener.onReconnectAbandoned();
            return;
        }
        long delay = reconnectDelayMs(reconnectAttempt);
        main().postDelayed(this::openSocket, delay);
    }

    /** Subscribe to a private channel (authorize first). Main thread. */
    public synchronized void subscribePrivate(String logicalName) {
        subscribe("private-" + logicalName);
    }

    private void subscribe(String channel) {
        if (closed) return;
        if (subscribed.size() + pendingAuth.size() >= MAX_LISTENED_CHANNELS
                && !subscribed.containsKey(channel)
                && !pendingAuth.containsKey(channel)) {
            return; // guard: never subscribe unbounded channel lists
        }
        if (socketId == null || sink == null) {
            // Not connected yet — queued; resubscribeAll() subscribes after
            // connection_established.
            pendingAuth.put(channel, Boolean.TRUE);
            return;
        }
        if (subscribed.containsKey(channel)
                || pendingAuth.containsKey(channel)) return; // already listening/in-flight
        pendingAuth.put(channel, Boolean.TRUE);
        final String fChannel = channel;
        listener.authorize(fChannel, auth -> {
            if (closed) return;
            if (auth == null) {
                // v1.5.41: authorization failed (transport dead / network
                // outage). Drop the pending entry but RETRY a bounded number
                // of times — a single dead auth moment used to silently
                // lose a channel until full reconnect.
                pendingAuth.remove(fChannel);
                Integer fails = authFails.get(fChannel);
                int n = fails == null ? 1 : fails + 1;
                if (n <= MAX_AUTH_ATTEMPTS) {
                    authFails.put(fChannel, n);
                    main().postDelayed(() -> {
                        if (closed) return;
                        // re-enter only if still wanted and still not up
                        if (!subscribed.containsKey(fChannel)) subscribe(fChannel);
                    }, AUTH_RETRY_DELAY_MS * n);
                }
                return;
            }
            authFails.remove(fChannel);
            Boolean stillWanted = pendingAuth.get(fChannel);
            if (stillWanted == null) return;
            if (sink != null) {
                sink.send(subscribeFrame(fChannel, auth));
            }
        });
    }

    private final Map<String, Integer> authFails = new HashMap<>();
    private static final int MAX_AUTH_ATTEMPTS = 3;
    private static final long AUTH_RETRY_DELAY_MS = 2_000L;

    /** Unsubscribe + forget. Main thread. Idempotent. */
    public synchronized void unsubscribePrivate(String logicalName) {
        String channel = "private-" + logicalName;
        boolean wasKnown = subscribed.remove(channel) != null
                || pendingAuth.remove(channel) != null;
        if (wasKnown && sink != null && socketId != null) {
            JSONObject data = new JSONObject();
            try {
                data.put("channel", channel);
            } catch (Exception ignored) {
            }
            sink.send(frame("pusher:unsubscribe", data));
        }
    }

    private void resubscribeAll() {
        java.util.List<String> wanted = new java.util.ArrayList<>(
                subscribed.keySet());
        wanted.addAll(pendingAuth.keySet());
        subscribed.clear();
        pendingAuth.clear();
        authFails.clear();
        for (String ch : wanted) {
            subscribe(ch);
        }
    }

    public boolean isConnected() {
        return socketId != null && sink != null;
    }

    /** Socket id for auth requests (empty when not connected). */
    public String socketIdOrNull() {
        return socketId == null ? "" : socketId;
    }

    /** Permanently close. After close() the client must not be reused. */
    public synchronized void shutdown() {
        if (closed) return;
        closed = true;
        main().removeCallbacksAndMessages(null);
        subscribed.clear();
        pendingAuth.clear();
        authFails.clear();
        if (sink != null) {
            sink.close(1000, "shutdown");
            sink = null;
        }
        if (http != null) {
            http.dispatcher().executorService().shutdown();
        }
    }

    // ── Test seams ────────────────────────────────────────────────────

    void testInjectSink(WebSocketSink s) {
        this.sink = s;
        this.socketId = "test-socket";
    }

    void testDeliver(String text) {
        handleMessage(text, connects.get());
    }

    void testClosed() {
        handleClosed(connects.get(), true);
    }

    Map<String, Boolean> testSubscribed() {
        return subscribed;
    }

    Map<String, Boolean> testPending() {
        return pendingAuth;
    }

    static int maxReconnectAttemptsForTest() {
        return MAX_RECONNECT_ATTEMPTS;
    }

    void testResetReconnects() {
        reconnectAttempt = 0;
        connects.set(0);
    }

    android.os.Handler testHandler() {
        return main();
    }

    long testReconnectAttempt() {
        return reconnectAttempt;
    }
}
