package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * v1.5.39 — QolabRealtimeClient pure protocol tests: frame building,
 * connection-event routing, keepalive, reconnect backoff, subscribe/auth
 * queue behavior and listener cleanup. All run against the client's JVM
 * seams without sockets.
 */
public class QolabRealtimeClientTest {

    private static QolabRealtimeClient newClient(TestListener l) {
        return new QolabRealtimeClient("wss://x/app/k", null, l);
    }

    static class TestListener implements QolabRealtimeClient.Listener {
        final List<String> events = new ArrayList<>();
        final AtomicReference<String> lastAuthChannel = new AtomicReference<>();
        volatile boolean connected = false;

        @Override
        public void authorize(String channelName, QolabRealtimeClient.AuthCallback cb) {
            lastAuthChannel.set(channelName);
            cb.onAuth("key:fake-hmac"); // synchronous happy path
        }

        @Override
        public void onEvent(String channel, String event, String data) {
            events.add(channel + "|" + event + "|" + data);
        }

        @Override
        public void onStateChanged(boolean c) {
            connected = c;
        }
    }

    @Test
    public void connectionEventsAreRecognized() {
        assertTrue(QolabRealtimeClient.isConnectionEvent("pusher:ping"));
        assertTrue(QolabRealtimeClient.isConnectionEvent("pusher:connection_established"));
        assertFalse(QolabRealtimeClient.isConnectionEvent("message.sent"));
        assertFalse(QolabRealtimeClient.isConnectionEvent(null));
    }

    @Test
    public void buildsSubscribeFrameWithAuth() throws Exception {
        String f = QolabRealtimeClient.subscribeFrame(
                "private-user.5", "key:hmac");
        JSONObject o = new JSONObject(f);
        assertEquals("pusher:subscribe", o.optString("event"));
        assertEquals("private-user.5",
                o.optJSONObject("data").optString("channel"));
        assertEquals("key:hmac", o.optJSONObject("data").optString("auth"));
    }

    @Test
    public void publicSubscribeOmitsAuth() throws Exception {
        JSONObject o = new JSONObject(QolabRealtimeClient.subscribeFrame("chan", null));
        assertFalse(o.optJSONObject("data").has("auth"));
    }

    @Test
    public void socketIdFromEstablishedPayload() {
        assertEquals("767.199",
                QolabRealtimeClient.socketIdFromEstablished(
                        "{\"socket_id\":\"767.199\",\"activity_timeout\":30}"));
        assertEquals(null,
                QolabRealtimeClient.socketIdFromEstablished("nope"));
    }

    @Test
    public void reconnectBackoffIsBounded() {
        assertEquals(1000L, QolabRealtimeClient.reconnectDelayMs(1));
        assertEquals(2000L, QolabRealtimeClient.reconnectDelayMs(2));
        assertEquals(4000L, QolabRealtimeClient.reconnectDelayMs(3));
        assertEquals(8000L, QolabRealtimeClient.reconnectDelayMs(4));
        assertEquals(8000L, QolabRealtimeClient.reconnectDelayMs(5));
        assertEquals(8000L, QolabRealtimeClient.reconnectDelayMs(50));
    }

    @Test
    public void hostOfStripsSchemePathAndPort() {
        assertEquals("workpulse-gbpedt.drytis.dev",
                QolabRealtimeClient.hostOf("https://workpulse-gbpedt.drytis.dev/"));
        assertEquals("qolab.drytis.com",
                QolabRealtimeClient.hostOf("https://qolab.drytis.com:443/x"));
        assertEquals("", QolabRealtimeClient.hostOf(null));
    }

    @Test
    public void subscribeBeforeConnectIsQueuedThenSentOnEstablish() throws Exception {
        TestListener l = new TestListener();
        QolabRealtimeClient c = newClient(l);
        List<String> sent = new ArrayList<>();
        // queue while offline
        c.subscribePrivate("user.5");
        assertTrue(c.testPending().containsKey("private-user.5"));
        assertTrue(l.events.isEmpty());

        // connect established → queued subscribe must now be sent
        c.testInjectSink(new QolabRealtimeClient.WebSocketSink() {
            @Override public boolean send(String text) { return sent.add(text); }
            @Override public void close(int c, String r) { }
        });
        c.testDeliver("{\"event\":\"pusher:connection_established\","
                + "\"data\":\"{\\\"socket_id\\\":\\\"1.2\\\",\\\"activity_timeout\\\":30}\"}");
        assertTrue(l.connected);
        assertEquals(1, sent.size());
        JSONObject sub = new JSONObject(sent.get(0));
        assertEquals("pusher:subscribe", sub.optString("event"));
        assertEquals("private-user.5",
                sub.optJSONObject("data").optString("channel"));
        assertEquals("key:fake-hmac",
                sub.optJSONObject("data").optString("auth"));
    }

    @Test
    public void serverPingIsAnsweredWithPong() throws Exception {
        TestListener l = new TestListener();
        QolabRealtimeClient c = newClient(l);
        List<String> sent = new ArrayList<>();
        c.testInjectSink(new QolabRealtimeClient.WebSocketSink() {
            @Override public boolean send(String text) { return sent.add(text); }
            @Override public void close(int c, String r) { }
        });
        c.testDeliver("{\"event\":\"pusher:ping\",\"data\":\"{}\"}");
        assertEquals(1, sent.size());
        assertEquals("pusher:pong", new JSONObject(sent.get(0)).optString("event"));
        assertTrue(l.events.isEmpty()); // connection events never reach app
    }

    @Test
    public void channelEventsAreDeliveredAndSubscriptionMarksSuccess() throws Exception {
        TestListener l = new TestListener();
        QolabRealtimeClient c = newClient(l);
        List<String> sent = new ArrayList<>();
        c.testInjectSink(new QolabRealtimeClient.WebSocketSink() {
            @Override public boolean send(String text) { return sent.add(text); }
            @Override public void close(int c, String r) { }
        });
        c.testDeliver("{\"event\":\"pusher:connection_established\","
                + "\"data\":\"{\\\"socket_id\\\":\\\"1.2\\\"}\"}");
        c.testDeliver("{\"event\":\"pusher_internal:subscription_succeeded\","
                + "\"channel\":\"private-user.5\",\"data\":\"{}\"}");
        assertTrue(c.testSubscribed().containsKey("private-user.5"));
        c.testDeliver("{\"event\":\".message.sent\",\"channel\":"
                + "\"private-channel.51\",\"data\":\"{\\\"id\\\":10}\"}");
        assertEquals(1, l.events.size());
        assertEquals("private-channel.51|.message.sent|{\"id\":10}", l.events.get(0));
    }

    @Test
    public void unsubscribeRemovesState() {
        TestListener l = new TestListener();
        QolabRealtimeClient c = newClient(l);
        c.subscribePrivate("user.5");
        c.unsubscribePrivate("user.5");
        assertTrue(c.testSubscribed().isEmpty());
        assertTrue(c.testPending().isEmpty());
    }

    @Test
    public void duplicateSubscribeIsIdempotent() throws Exception {
        TestListener l = new TestListener();
        QolabRealtimeClient c = newClient(l);
        List<String> sent = new ArrayList<>();
        c.testInjectSink(new QolabRealtimeClient.WebSocketSink() {
            @Override public boolean send(String text) { return sent.add(text); }
            @Override public void close(int c, String r) { }
        });
        c.testDeliver("{\"event\":\"pusher:connection_established\","
                + "\"data\":\"{\\\"socket_id\\\":\\\"1.2\\\"}\"}");
        c.subscribePrivate("user.5");
        int afterFirst = sent.size();
        assertEquals(1, afterFirst);
        c.subscribePrivate("user.5"); // already subscribed
        assertEquals(afterFirst, sent.size());
    }

    @Test
    public void malformedFramesAreIgnored() {
        TestListener l = new TestListener();
        QolabRealtimeClient c = newClient(l);
        c.testInjectSink(new QolabRealtimeClient.WebSocketSink() {
            @Override public boolean send(String text) { return true; }
            @Override public void close(int c, String r) { }
        });
        c.testDeliver("not json at all");
        c.testDeliver("{\"data\":\"{}\"}"); // no event
        assertTrue(l.events.isEmpty());
    }

    @Test
    public void parseFrameRejectsGarbage() {
        assertEquals(null, QolabRealtimeClient.parseFrame("xx"));
        JSONObject o = QolabRealtimeClient.parseFrame(
                "{\"event\":\"e\",\"channel\":\"c\",\"data\":\"d\"}");
        assertEquals("e", o.optString("event"));
    }
}
