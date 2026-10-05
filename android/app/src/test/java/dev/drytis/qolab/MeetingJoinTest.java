package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import dev.drytis.qolab.RoomDataClient.JoinToken;
import dev.drytis.qolab.RoomDataClient.MeetingDetail;

/**
 * v1.5.33 — meeting join/start/copy-link contracts.
 *
 *  Root bug fixed here too: org.json's optString(key, null) returns the
 *  literal STRING "null" for JSON nulls — a meeting created without a
 *  description rendered a literal "null" row in the facts card
 *  (physical G32 screenshot). The parser now null-cleans every field.
 */
public class MeetingJoinTest {

    private static final String DETAIL = "{"
            + "\"id\":11,\"title\":\"Weekly sync\",\"description\":null,"
            + "\"status\":\"scheduled\","
            + "\"scheduled_at_display\":\"Oct 7, 2026, 3:00 PM\","
            + "\"timezone_abbr\":\"IST\","
            + "\"session_key\":\"mtg-86jkULTvoYk9O\","
            + "\"meeting_url\":\"https://studio.drytis.ai/meeting/mtg-86jkULTvoYk9O\","
            + "\"studio_join_url\":\"https://studio.drytis.ai/meeting/mtg-86jkULTvoYk9O\","
            + "\"can_join\":true,\"is_creator\":true,"
            + "\"tasks\":[],\"transcript_segments\":null}";

    private static final String JOIN_TOKEN_OK = "{"
            + "\"success\":true,"
            + "\"token\":\"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEzIn0.x\","
            + "\"url\":\"wss://addy-uuxwzign.livekit.cloud\"}";

    private static final Path APP = findRoot().resolve("android/app/src/main");

    private static Path findRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path d = cwd; d != null; d = d.getParent()) {
            if (Files.exists(d.resolve(".git"))) return d;
        }
        return cwd;
    }

    // ── parser: null-clean ─────────────────────────────────────────────

    @Test
    public void jsonNullDescriptionNeverRendersAsLiteralNull() throws Exception {
        MeetingDetail d = RoomDataClient.parseMeetingDetail(DETAIL);
        assertNotNull(d);
        assertNull(d.description);            // JSON null → Java null, never "null"
        assertEquals("Weekly sync", d.title);
        assertEquals("scheduled", d.status);
    }

    @Test
    public void joinFieldsParsedFromDetail() throws Exception {
        MeetingDetail d = RoomDataClient.parseMeetingDetail(DETAIL);
        assertEquals("mtg-86jkULTvoYk9O", d.sessionKey);
        assertEquals("https://studio.drytis.ai/meeting/mtg-86jkULTvoYk9O", d.meetingUrl);
        assertTrue(d.canJoin);
        assertTrue(d.isCreator);
    }

    @Test
    public void optStrMapsLiteralNullAndBlankToJavaNull() throws Exception {
        JSONObject o = new JSONObject("{\"a\":null,\"b\":\"null\",\"c\":\"  \",\"d\":\"ok\"}");
        assertNull(RoomDataClient.optStr(o, "a"));
        assertNull(RoomDataClient.optStr(o, "b"));
        assertNull(RoomDataClient.optStr(o, "c"));
        assertEquals("ok", RoomDataClient.optStr(o, "d"));
    }

    // ── join-token contract ────────────────────────────────────────────

    @Test
    public void joinTokenParsedWithServerUrl() throws Exception {
        JoinToken jt = RoomDataClient.parseJoinToken(JOIN_TOKEN_OK);
        assertNotNull(jt);
        assertTrue(jt.token.startsWith("eyJ"));
        assertEquals("wss://addy-uuxwzign.livekit.cloud", jt.url);
    }

    @Test
    public void joinTokenRejectedWhenNotSuccess() {
        assertNull(RoomDataClient.parseJoinToken("{\"success\":false}"));
        assertNull(RoomDataClient.parseJoinToken(null));
        assertNull(RoomDataClient.parseJoinToken("not-json{"));
    }

    @Test
    public void startOrJoinReturnsEffectiveMeetingId() {
        assertEquals(12, RoomDataClient.parseStartOrJoin(
                "{\"success\":true,\"meeting_id\":12}"));
        assertEquals(0, RoomDataClient.parseStartOrJoin("{\"success\":false}"));
    }

    // ── wiring: status-driven actions ──────────────────────────────────

    @Test
    public void actionVisibilityFollowsRealStatusAndCanJoin() throws Exception {
        String src = new String(Files.readAllBytes(
                APP.resolve("java/dev/drytis/qolab/MeetingDetailActivity.java")),
                StandardCharsets.UTF_8);
        // actions gated on detail.canJoin AND status scheduled|live only
        assertTrue(src.contains("detail.canJoin"));
        assertTrue(src.contains("\"live\".equals(status) || \"scheduled\".equals(status)"));
        // join goes through the existing endpoint only
        assertTrue(src.contains("postJson(\"/meetings/\" + meetingId + \"/join-token\""));
    }

    @Test
    public void copyLinkUsesBackendMeetingUrlOnly() throws Exception {
        String src = new String(Files.readAllBytes(
                APP.resolve("java/dev/drytis/qolab/MeetingDetailActivity.java")),
                StandardCharsets.UTF_8);
        assertTrue(src.contains("detail.meetingUrl"));
        assertFalse(src.contains("studio.drytis.ai/meeting/\" +")); // never constructed
    }

    @Test
    public void listEnterActionOnlyForEnterableStatuses() throws Exception {
        String src = new String(Files.readAllBytes(
                APP.resolve("java/dev/drytis/qolab/RoomActivity.java")),
                StandardCharsets.UTF_8);
        assertTrue(src.contains("R.id.meeting_row_enter"));
        assertTrue(src.contains("\"live\".equals(m.status)"));
        assertTrue(src.contains("\"scheduled\".equals(m.status)"));
        assertTrue(src.contains("R.string.room_meeting_join"));
        assertTrue(src.contains("R.string.room_meeting_start"));
    }

    @Test
    public void liveRoomUsesOnlyIssuedCredentials() throws Exception {
        String kt = new String(Files.readAllBytes(
                APP.resolve("java/dev/drytis/qolab/LiveRoomActivity.kt")),
                StandardCharsets.UTF_8);
        // no token minting / room name invention / hardcoded servers
        assertFalse(kt.contains("livekit.cloud"));
        assertFalse(kt.contains("AccessToken"));
        assertTrue(kt.contains("r.connect(serverUrl!!, token!!)"));
        // leave posts the existing endpoint
        assertTrue(kt.contains("/leave"));
    }
}
