package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * v1.5.15 (Architecture B) transport-boundary tests.
 *
 * The authenticated transport is QolabWebViewTransport: the
 * application-scoped WebView that owns the Laravel session performs
 * authenticated same-origin requests inside the browser context. Java
 * HttpURLConnection no longer carries the session (physically disproven
 * on device: WebView 200 / Java 401).
 *
 * These tests pin the static, JVM-testable parts of the boundary:
 *   - request paths are restricted to same-origin paths
 *   - JS beacon payloads carry ONLY status + JSON body — cookie names,
 *     values, tokens and credentials are never part of the Result
 *   - beacon validation rejects malformed/hostile input safely
 *   - the preserved parse layer still maps real payloads (success,
 *     malformed JSON, null sections)
 *   - the session-cookie detection helper contract is unchanged
 */
public class HomeSessionTransportTest {

    // ------------------------------------------------------------------
    // Origin/path enforcement (no arbitrary URLs from callers)
    // ------------------------------------------------------------------

    @Test
    public void offOriginPathIsRejected() {
        QolabWebViewTransport.Result r =
                QolabWebViewTransport.buildPathUrl("https://evil.example.com/x");
        assertNotNull("absolute/off-origin path must be rejected", r);
        assertEquals("off-origin", r.errorCategory);
    }

    @Test
    public void protocolRelativePathIsRejected() {
        QolabWebViewTransport.Result r =
                QolabWebViewTransport.buildPathUrl("//evil.example.com/x");
        assertNotNull(r);
        assertEquals("off-origin", r.errorCategory);
    }

    @Test
    public void sameOriginPathIsAccepted() {
        QolabWebViewTransport.Result r =
                QolabWebViewTransport.buildPathUrl("/mobile/home");
        assertNull("same-origin absolute path must be accepted", r);
    }

    // ------------------------------------------------------------------
    // Beacon validation: status + sanitized JSON only
    // ------------------------------------------------------------------

    @Test
    public void beaconParsesStatusAndBodyOnly() throws Exception {
        QolabWebViewTransport.Result r = QolabWebViewTransport.resultFromBeacon(
                "{\"s\":200,\"b\":\"{\\\"user\\\":{\\\"name\\\":\\\"QA\\\"}}\"}");
        assertEquals(200, r.status);
        assertNotNull(r.body);
        assertEquals("QA", new JSONObject(r.body).getJSONObject("user").getString("name"));
        assertNull("no error category on success", r.errorCategory);
    }

    @Test
    public void beaconRejectsNonJson() {
        QolabWebViewTransport.Result r = QolabWebViewTransport.resultFromBeacon("not json");
        assertEquals(QolabWebViewTransport.Result.STATUS_IO, r.status);
        assertEquals("bad-beacon", r.errorCategory);
    }

    @Test
    public void beaconRejectsMissingStatus() {
        QolabWebViewTransport.Result r = QolabWebViewTransport.resultFromBeacon("{\"b\":\"{}\"}");
        assertEquals(QolabWebViewTransport.Result.STATUS_IO, r.status);
        assertEquals("bad-beacon", r.errorCategory);
    }

    @Test
    public void beaconStatusIsClampedToHttpRange() {
        // hostile/buggy beacon values cannot smuggle sentinel statuses
        QolabWebViewTransport.Result r = QolabWebViewTransport.resultFromBeacon(
                "{\"s\":99999,\"b\":\"{}\"}");
        assertEquals("out-of-range status becomes bad-beacon, not a fake code",
                QolabWebViewTransport.Result.STATUS_IO, r.status);
        assertEquals("bad-beacon", r.errorCategory);
    }

    @Test
    public void beaconBodyIsCappedToPreventMemoryAbuse() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 600_000; i++) huge.append('x');
        QolabWebViewTransport.Result r = QolabWebViewTransport.resultFromBeacon(
                "{\"s\":200,\"b\":\"" + huge + "\"}");
        assertTrue("oversized body must be rejected",
                r.body == null || r.errorCategory != null);
    }

    // ------------------------------------------------------------------
    // v1.5.17: promise-return channel decode (evaluateJavascript callback)
    // ------------------------------------------------------------------

    @Test
    public void evaluateJavascriptReturnDecodesStatusAndBody() {
        // The poll reads a window variable whose value is the {s,b} JSON text
        String raw = "\"{\\\"s\\\":200,\\\"b\\\":\\\"{\\\\\\\"user\\\\\\\":{}}\\\"}\"";
        QolabWebViewTransport.Result r = QolabWebViewTransport.resultFromEvaluateJavascript(raw);
        assertEquals(200, r.status);
        assertNotNull(r.body);
    }

    @Test
    public void evaluateJavascriptNullReturnIsSafeIO() {
        QolabWebViewTransport.Result r = QolabWebViewTransport.resultFromEvaluateJavascript("null");
        assertEquals(QolabWebViewTransport.Result.STATUS_IO, r.status);
        assertEquals("no-value", r.errorCategory);
    }

    @Test
    public void evaluateJavascriptGarbageIsSafeIO() {
        QolabWebViewTransport.Result r = QolabWebViewTransport.resultFromEvaluateJavascript("<not json");
        assertEquals(QolabWebViewTransport.Result.STATUS_IO, r.status);
    }
    @Test
    public void evaluateJavascriptNetworkFailureMapsToIO() {
        // fetch catch → JSON.stringify({s:-1,b:''}) → delivered as literal
        String raw = "\"{\\\"s\\\":-1,\\\"b\\\":\\\"\\\"}\"";
        QolabWebViewTransport.Result r = QolabWebViewTransport.resultFromEvaluateJavascript(raw);
        assertEquals(QolabWebViewTransport.Result.STATUS_IO, r.status);
        assertNull(r.errorCategory);
    }

    @Test
    public void evaluateJavascriptRejectsFakeStatusCodes() {
        String raw = "\"{\\\"s\\\":99999,\\\"b\\\":\\\"\\\"}\"";
        QolabWebViewTransport.Result r = QolabWebViewTransport.resultFromEvaluateJavascript(raw);
        assertEquals(QolabWebViewTransport.Result.STATUS_IO, r.status);
        assertEquals("bad-beacon", r.errorCategory);
    }

    @Test
    public void evaluateJavascriptPromiseObjectReturnIsRejectedNotMistakenForData() {
        // v1.5.18 regression: a Promise return serializes to '{}' — it must
        // NOT be accepted as a result (it decodes to a beacon with no 's').
        QolabWebViewTransport.Result r = QolabWebViewTransport.resultFromEvaluateJavascript("\"{}\"");
        assertEquals("promise-serialization must map to bad-beacon, not data",
                "bad-beacon", r.errorCategory);
    }

    @Test
    public void evaluateJavascriptPendingNullIsNoValueCategory() {
        QolabWebViewTransport.Result r = QolabWebViewTransport.resultFromEvaluateJavascript("null");
        assertEquals("no-value", r.errorCategory);
        assertEquals(QolabWebViewTransport.Result.STATUS_IO, r.status);
    }

    // ------------------------------------------------------------------
    // Preserved parse layer (Architecture B keeps all Home JSON mapping)
    // ------------------------------------------------------------------

    @Test
    public void parseRealPayloadPopulatesHome() {
        String json = "{\"user\":{\"name\":\"QA Dropdown V3\"},"
                + "\"next_meeting\":{\"id\":2,\"title\":\"Home V1 Test Sync\","
                + "\"scheduled_at_display\":\"4:45 PM\",\"participants_count\":5,\"participants\":[]},"
                + "\"social_rooms\":[{\"id\":48,\"name\":\"The Lobby\",\"members_count\":3,\"participants\":[]}],"
                + "\"department_room\":{\"id\":9,\"name\":\"Engineering Room\",\"members_count\":5,\"participants\":[]},"
                + "\"recent_conversation\":{\"id\":49,\"name\":\"QA Admin (Dev)\",\"type\":\"dm\","
                + "\"dm_partner\":{\"id\":5,\"name\":\"QA Admin (Dev)\"},\"unread_count\":2},"
                + "\"priority_task\":{\"id\":20,\"title\":\"Review APK\",\"due_date\":\"2026-10-01\"},"
                + "\"today\":{\"tasks_due_today\":1,\"meetings_today\":1,\"unread_notifications\":4}}";
        HomeDataClient.HomeData d = HomeDataClient.parse(json);
        assertNotNull(d);
        assertEquals("QA Dropdown V3", d.userName);
        assertEquals("Home V1 Test Sync", d.nextMeeting.title);
        assertEquals(1, d.socialRooms.size());
        assertEquals("Engineering Room", d.departmentRoom.name);
        assertEquals(2, d.recentConversation.unreadCount);
        assertEquals(4, d.today.unreadNotifications);
    }

    @Test
    public void parseMalformedJsonDegradesSafely() {
        // v1.5.36: malformed body = failure (null). The parser still never
        // throws; callers treat null as error+retry, never as empty data.
        HomeDataClient.HomeData d = HomeDataClient.parse("not json {{{");
        assertNull("unparseable body must be null (failure), not empty model", d);
    }

    @Test
    public void parseEmptyObjectYieldsEmptySections() {
        HomeDataClient.HomeData d = HomeDataClient.parse("{}");
        assertNotNull(d);
        assertNull(d.nextMeeting);
        assertNull(d.departmentRoom);
        assertNull(d.recentConversation);
        assertNull(d.priorityTask);
        assertTrue(d.socialRooms.isEmpty());
    }

    // ------------------------------------------------------------------
    // Session-cookie detection helper (contract unchanged)
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
