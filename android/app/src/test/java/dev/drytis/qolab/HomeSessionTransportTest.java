package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Home V1.5.1 session-transport regression tests.
 *
 * Physical-device bug: Home loaded with an anonymous/stale session cookie →
 * 401 → automatic logout. These tests pin the fixed contract:
 *   - cookies come from the authoritative store AT REQUEST TIME
 *   - Set-Cookie responses are synchronized back into the store
 *   - 401 without a sent session cookie is NOT auth-lost
 *   - 401 with a session cookie IS auth-lost (genuine server rejection)
 *   - network error / timeout / 5xx / malformed JSON never report auth-lost
 *   - a successful 200 populates HomeData
 *
 * Uses a minimal raw-socket HTTP server (JDK core only — the Android unit
 * test classpath has no com.sun.net.httpserver).
 */
public class HomeSessionTransportTest {

    /** Minimal request-recording HTTP server on a raw socket. */
    private static final class MiniServer {
        interface Handler {
            void handle(String cookieHeader, Response out);
        }
        static final class Response {
            int status;
            String body;
            String setCookie;
        }

        final ServerSocket serverSocket;
        final List<String> cookieHeaders = new ArrayList<>();
        final AtomicInteger requests = new AtomicInteger();
        private volatile Handler handler;
        private volatile boolean stopped;
        private Thread acceptThread;

        MiniServer() throws Exception {
            serverSocket = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        }

        void setHandler(Handler h) {
            this.handler = h;
        }

        void start() {
            acceptThread = new Thread(() -> {
                while (!stopped) {
                    try {
                        Socket s = serverSocket.accept();
                        Thread t = new Thread(() -> serve(s));
                        t.setDaemon(true);
                        t.start();
                    } catch (Exception e) {
                        if (!stopped) e.printStackTrace();
                    }
                }
            });
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        private void serve(Socket s) {
            try {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String requestLine = in.readLine();
                String cookieHeader = null;
                String line;
                int contentLen = 0;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    String lower = line.toLowerCase();
                    if (lower.startsWith("cookie:")) {
                        cookieHeader = line.substring(7).trim();
                    }
                    if (lower.startsWith("content-length:")) {
                        contentLen = Integer.parseInt(line.substring(15).trim());
                    }
                }
                if (contentLen > 0) {
                    char[] buf = new char[contentLen];
                    int read = 0;
                    while (read < contentLen) {
                        int n = in.read(buf, read, contentLen - read);
                        if (n < 0) break;
                        read += n;
                    }
                }
                final String cookie = cookieHeader;
                requests.incrementAndGet();
                synchronized (cookieHeaders) { cookieHeaders.add(cookie); }
                Response r = new Response();
                Handler h = handler;
                if (h != null) h.handle(cookie, r);
                int status = r.status == 0 ? 200 : r.status;
                String body = r.body == null ? "" : r.body;
                StringBuilder head = new StringBuilder();
                head.append("HTTP/1.1 ").append(status).append(" ")
                        .append(status == 200 ? "OK" : status == 401 ? "Unauthorized"
                        : status == 500 ? "Internal Server Error" : "Status").append("\r\n");
                if (r.setCookie != null) {
                    head.append("Set-Cookie: ").append(r.setCookie).append("\r\n");
                }
                head.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length)
                        .append("\r\nConnection: close\r\n\r\n");
                OutputStream os = s.getOutputStream();
                os.write(head.toString().getBytes(StandardCharsets.UTF_8));
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
                s.close();
            } catch (Exception ignored) {
            }
        }

        void stop() throws Exception {
            stopped = true;
            serverSocket.close();
        }

        String url(String path) {
            return "http://127.0.0.1:" + serverSocket.getLocalPort() + path;
        }
    }

    /** In-memory authoritative store, mirroring WebViewCookieStore semantics. */
    private static final class FakeStore implements QolabCookieSession.CookieStore {
        final Map<String, String> jar = new HashMap<>();
        final List<String> setCookieCalls = new ArrayList<>();

        @Override
        public synchronized String cookieHeaderFor(String url) {
            if (jar.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : jar.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append("=").append(e.getValue());
            }
            return sb.toString();
        }

        @Override
        public synchronized void setCookie(String url, String setCookieValue) {
            setCookieCalls.add(setCookieValue);
            String name = setCookieValue.split("=", 2)[0].trim();
            String rest = setCookieValue.contains("=")
                    ? setCookieValue.split("=", 2)[1] : "";
            String value = rest.split(";", 2)[0];
            jar.put(name, value);
        }

        @Override
        public void flush() { }
    }

    private MiniServer server;
    private FakeStore store;
    private HomeDataClient client;

    @Before
    public void setUp() throws Exception {
        store = new FakeStore();
        server = new MiniServer();
        server.start();
        client = new HomeDataClient(server.url(""));
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) server.stop();
    }

    private void respondWith(int status, String body, String setCookie) {
        server.setHandler((cookie, out) -> {
            out.status = status;
            out.body = body;
            out.setCookie = setCookie;
        });
    }

    // ------------------------------------------------------------------
    // Set-Cookie synchronization + request-time cookie reads
    // ------------------------------------------------------------------

    @Test
    public void setCookieIsSynchronizedIntoStore() {
        respondWith(200, "{}", "qolab-session=rotated-id; path=/; httponly");
        client.fetchHome(store, server.url("/mobile/home"));
        assertTrue("Set-Cookie must land in the authoritative store",
                store.jar.containsKey("qolab-session"));
        assertEquals("rotated-id", store.jar.get("qolab-session"));
        assertFalse(store.setCookieCalls.isEmpty());
    }

    @Test
    public void cookiesAreReadFromStoreAtRequestTime() {
        respondWith(200, "{}", null);
        store.jar.put("qolab-session", "auth-value-A");
        client.fetchHome(store, server.url("/mobile/home"));
        synchronized (server.cookieHeaders) {
            assertTrue(server.cookieHeaders.get(0).contains("auth-value-A"));
        }
        // store mutated between requests → NEXT request must send the new value
        store.jar.put("qolab-session", "auth-value-B");
        client.fetchHome(store, server.url("/mobile/home"));
        synchronized (server.cookieHeaders) {
            assertTrue(server.cookieHeaders.get(1).contains("auth-value-B"));
        }
    }

    // ------------------------------------------------------------------
    // v1.5.14: 401 Set-Cookie must NEVER clobber the store
    // ------------------------------------------------------------------

    @Test
    public void unauthorized401SetCookieIsNotSynchronizedIntoStore() {
        // Laravel's 401 regenerates the session (fresh ANONYMOUS cookie in
        // Set-Cookie). Syncing it would destroy the authenticated session.
        respondWith(401, "", "qolab-session=anonymous-rotated; path=/; httponly");
        store.jar.put("qolab-session", "authenticated-value");
        HomeDataClient.Result r = client.fetchHome(store, server.url("/mobile/home"));
        assertEquals(401, r.status);
        assertEquals("401 Set-Cookie must NOT overwrite the store",
                "authenticated-value", store.jar.get("qolab-session"));
        assertTrue("no setCookie calls expected on 401", store.setCookieCalls.isEmpty());
    }

    @Test
    public void non2xx500SetCookieIsNotSynchronizedIntoStore() {
        respondWith(500, "boom", "qolab-session=poison; path=/");
        store.jar.put("qolab-session", "authenticated-value");
        HomeDataClient.Result r = client.fetchHome(store, server.url("/mobile/home"));
        assertEquals(500, r.status);
        assertEquals("5xx Set-Cookie must NOT overwrite the store",
                "authenticated-value", store.jar.get("qolab-session"));
    }

    @Test
    public void ok200SetCookieStillSynchronizesIntoStore() {
        respondWith(200, "{}", "qolab-session=rotated-authenticated; path=/; httponly");
        client.fetchHome(store, server.url("/mobile/home"));
        assertEquals("2xx Set-Cookie must still sync (normal rotation)",
                "rotated-authenticated", store.jar.get("qolab-session"));
    }

    @Test
    public void pinnedFirstAttemptHeaderIsSentVerbatim() {
        // The first Java probe attempt carries the exact cookie header the
        // WebView proved authenticated — the store must NOT be consulted.
        respondWith(200, "{}", null);
        store.jar.put("qolab-session", "stale-anonymous");
        HomeDataClient.Result r = client.fetchHome(store, server.url("/mobile/home"),
                "qolab-session=web-verified-value");
        assertEquals(200, r.status);
        synchronized (server.cookieHeaders) {
            assertEquals("pinned header must be sent verbatim",
                    "qolab-session=web-verified-value", server.cookieHeaders.get(0));
        }
    }

    @Test
    public void pinnedNullFallsBackToStoreAtRequestTime() {
        respondWith(200, "{}", null);
        store.jar.put("qolab-session", "from-store");
        HomeDataClient.Result r = client.fetchHome(store, server.url("/mobile/home"), null);
        assertEquals(200, r.status);
        synchronized (server.cookieHeaders) {
            assertTrue(server.cookieHeaders.get(0).contains("from-store"));
        }
    }

    // ------------------------------------------------------------------
    // 401 policy
    // ------------------------------------------------------------------

    @Test
    public void unauthorizedWithoutSessionCookieIsNotAuthLost() {
        respondWith(401, "", null);
        HomeDataClient.Result r = client.fetchHome(store, server.url("/mobile/home"));
        assertEquals(401, r.status);
        assertFalse("401 without sent session cookie must NOT report authLost",
                r.authLost);
    }

    @Test
    public void unauthorizedWithSessionCookieIsAuthLost() {
        respondWith(401, "", null);
        store.jar.put("qolab-session", "valid-looking-id");
        HomeDataClient.Result r = client.fetchHome(store, server.url("/mobile/home"));
        assertEquals(401, r.status);
        assertTrue("401 WITH sent session cookie is a genuine rejection",
                r.authLost);
    }

    // ------------------------------------------------------------------
    // Non-401 failures never log out
    // ------------------------------------------------------------------

    @Test
    public void serverErrorDoesNotReportAuthLost() {
        respondWith(500, "boom", null);
        store.jar.put("qolab-session", "auth-value");
        HomeDataClient.Result r = client.fetchHome(store, server.url("/mobile/home"));
        assertEquals(500, r.status);
        assertFalse(r.authLost);
        assertNull(r.data);
    }

    @Test
    public void malformedJsonOn200DoesNotReportAuthLostAndNeverThrows() {
        respondWith(200, "not json {{{", null);
        store.jar.put("qolab-session", "auth-value");
        HomeDataClient.Result r = client.fetchHome(store, server.url("/mobile/home"));
        assertEquals(200, r.status);
        assertFalse(r.authLost);
        assertNotNull(r.data);
        assertTrue(r.data.socialRooms.isEmpty());
    }

    @Test
    public void networkFailureDoesNotReportAuthLost() {
        store.jar.put("qolab-session", "auth-value");
        // port 1 on loopback: connection refused immediately
        HomeDataClient.Result r = client.fetchHome(store, "http://127.0.0.1:1/mobile/home");
        assertEquals(HomeDataClient.Result.STATUS_IO, r.status);
        assertFalse(r.authLost);
        assertNull(r.data);
    }

    @Test
    public void timeoutDoesNotReportAuthLost() {
        // handler that never writes a response → read timeout
        server.setHandler((cookie, out) -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
        });
        store.jar.put("qolab-session", "auth-value");
        client.setTimeouts(1000, 300);
        HomeDataClient.Result r = client.fetchHome(store, server.url("/mobile/home"));
        assertEquals(HomeDataClient.Result.STATUS_IO, r.status);
        assertFalse(r.authLost);
    }

    // ------------------------------------------------------------------
    // Success path populates Home
    // ------------------------------------------------------------------

    @Test
    public void ok200WithRealPayloadPopulatesHome() {
        respondWith(200, "{\"user\":{\"name\":\"QA Dropdown V3\"},"
                + "\"next_meeting\":{\"id\":2,\"title\":\"Home V1 Test Sync\","
                + "\"scheduled_at_display\":\"4:45 PM\",\"participants_count\":5,\"participants\":[]},"
                + "\"social_rooms\":[{\"id\":48,\"name\":\"The Lobby\",\"members_count\":3,\"participants\":[]}],"
                + "\"department_room\":{\"id\":9,\"name\":\"Engineering Room\",\"members_count\":5,\"participants\":[]},"
                + "\"recent_conversation\":{\"id\":49,\"name\":\"QA Admin (Dev)\",\"type\":\"dm\","
                + "\"dm_partner\":{\"id\":5,\"name\":\"QA Admin (Dev)\"},\"unread_count\":2},"
                + "\"priority_task\":{\"id\":20,\"title\":\"Review APK\",\"due_date\":\"2026-10-01\"},"
                + "\"today\":{\"tasks_due_today\":1,\"meetings_today\":1,\"unread_notifications\":4}}",
                null);
        store.jar.put("qolab-session", "auth-value");
        HomeDataClient.Result r = client.fetchHome(store, server.url("/mobile/home"));
        assertEquals(200, r.status);
        assertFalse(r.authLost);
        assertNotNull(r.data);
        assertEquals("QA Dropdown V3", r.data.userName);
        assertEquals("Home V1 Test Sync", r.data.nextMeeting.title);
        assertEquals(1, r.data.socialRooms.size());
        assertEquals("Engineering Room", r.data.departmentRoom.name);
        assertEquals(2, r.data.recentConversation.unreadCount);
        assertEquals(4, r.data.today.unreadNotifications);
    }

    // ------------------------------------------------------------------
    // Session-cookie detection helper
    // ------------------------------------------------------------------

    @Test
    public void containsSessionCookieNameOnly() {
        assertTrue(QolabCookieSession.containsSessionCookie(
                "XSRF-TOKEN=abc; qolab-session=xyz"));
        assertTrue(QolabCookieSession.containsSessionCookie("OTHERSESSION=1"));
        assertFalse(QolabCookieSession.containsSessionCookie("XSRF-TOKEN=abc"));
        assertFalse(QolabCookieSession.containsSessionCookie(null));
        assertFalse(QolabCookieSession.containsSessionCookie(""));
    }
}
