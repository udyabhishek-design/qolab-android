package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import dev.drytis.qolab.RoomDataClient.Meeting;
import dev.drytis.qolab.RoomDataClient.MeetingDetail;
import dev.drytis.qolab.RoomDataClient.RoomPage;
import dev.drytis.qolab.RoomDataClient.TranscriptSession;

/**
 * Unit tests for RoomDataClient — the pure parsers behind the native
 * Engineering Room screen. Fixtures mirror the ACTUAL backend contracts:
 *  - /office/rooms/{slug} Inertia page (data-page script JSON)
 *  - /meetings/{id}/detail
 */
public class RoomDataClientTest {

    // ── Inertia page extraction ──────────────────────────────────────

    @Test
    public void extractInertiaPage_parsesDataPageScript() {
        String html = "<!doctype html><html><head></head><body>"
                + "<script data-page type=\"application/json\">"
                + "{\"component\":\"Rooms/Show\",\"props\":{\"channel\":{\"name\":\"Eng\"}}}"
                + "</script><div id=\"app\"></div></body></html>";
        JSONObject page = RoomDataClient.extractInertiaPage(html);
        assertNotNull(page);
        assertEquals("Rooms/Show", page.optString("component"));
        assertEquals("Eng", page.optJSONObject("props").optJSONObject("channel").optString("name"));
    }

    @Test
    public void extractInertiaPage_missingMarker_returnsNull() {
        assertNull(RoomDataClient.extractInertiaPage("<html><body>Login page</body></html>"));
        assertNull(RoomDataClient.extractInertiaPage(null));
        assertNull(RoomDataClient.extractInertiaPage(""));
    }

    @Test
    public void extractInertiaPage_garbageJson_returnsNull() {
        assertNull(RoomDataClient.extractInertiaPage(
                "<script data-page type=\"application/json\">{not json</script>"));
    }

    // ── Room page parsing ────────────────────────────────────────────

    private static JSONObject roomPageFixture() throws Exception {
        return new JSONObject("{"
                + "\"props\":{"
                + "\"channel\":{"
                +   "\"name\":\"Engineering Room\","
                +   "\"description\":\"Room for the Engineering department\","
                +   "\"members\":["
                +     "{\"id\":13,\"name\":\"QA Dropdown V3\",\"job_title\":\"QA\",\"department\":{\"id\":7,\"name\":\"Engineering\"},\"channel_role\":\"member\"},"
                +     "{\"id\":14,\"name\":\"Alex Rivera\",\"job_title\":\"Dev\",\"department\":null,\"channel_role\":\"owner\"}"
                +   "]"
                + "},"
                + "\"meetings\":{"
                +   "\"live\":[],"
                +   "\"upcoming\":[{\"id\":11,\"title\":\"Engineering Weekly Sync\",\"status\":\"scheduled\",\"scheduled_at_display\":\"Oct 7, 2026, 3:30 PM\",\"timezone_abbr\":\"IST\",\"duration_seconds\":0,\"has_transcript\":false,\"task_count\":0,\"schedule_type\":\"normal\"}],"
                +   "\"daily\":[],"
                +   "\"completed\":[{\"id\":7,\"title\":\"Eng Room Sync\",\"status\":\"ended\",\"scheduled_at_display\":\"Oct 3, 2026, 7:30 PM\",\"timezone_abbr\":\"IST\",\"duration_seconds\":1500,\"has_transcript\":true,\"task_count\":2,\"schedule_type\":\"normal\"}],"
                +   "\"cancelled\":[{\"id\":4,\"title\":\"IGNORE Cancelled\",\"status\":\"cancelled\",\"scheduled_at_display\":\"Oct 1, 2026, 6:05 PM\",\"timezone_abbr\":\"IST\",\"duration_seconds\":0,\"has_transcript\":false,\"task_count\":0}],"
                +   "\"counts\":{\"total\":3}"
                + "}"
                + "}}");
    }

    @Test
    public void parseRoomPage_fullFixture() throws Exception {
        RoomPage p = RoomDataClient.parseRoomPage(roomPageFixture());
        assertNotNull(p);
        assertEquals("Engineering Room", p.roomName);
        assertEquals("Room for the Engineering department", p.roomDescription);
        assertEquals(2, p.members.size());
        assertEquals("QA Dropdown V3", p.members.get(0).name);
        assertEquals("QA", p.members.get(0).jobTitle);
        assertEquals("Engineering", p.members.get(0).departmentName);
        assertEquals("member", p.members.get(0).role);
        assertNull(p.members.get(1).departmentName);

        assertEquals(1, p.upcoming.size());
        Meeting up = p.upcoming.get(0);
        assertEquals(11, up.id);
        assertEquals("scheduled", up.status);
        assertEquals("Oct 7, 2026, 3:30 PM", up.scheduledAtDisplay);
        assertFalse(up.hasTranscript);

        assertEquals(1, p.completed.size());
        Meeting done = p.completed.get(0);
        assertEquals("ended", done.status);
        assertEquals(1500, done.durationSeconds);
        assertTrue(done.hasTranscript);
        assertEquals(2, done.taskCount);

        assertEquals(1, p.cancelled.size());
        assertTrue(p.live.isEmpty());
    }

    @Test
    public void parseRoomPage_emptyMeetings_honestEmptyLists() throws Exception {
        JSONObject page = roomPageFixture();
        JSONObject meetings = page.getJSONObject("props").getJSONObject("meetings");
        meetings.put("upcoming", new org.json.JSONArray());
        meetings.put("completed", new org.json.JSONArray());
        RoomPage p = RoomDataClient.parseRoomPage(page);
        assertNotNull(p);
        assertTrue(p.upcoming.isEmpty());
        assertTrue(p.completed.isEmpty());
        assertEquals(2, p.members.size()); // members still real
    }

    @Test
    public void parseRoomPage_missingChannelOrMeetings_null() throws Exception {
        JSONObject page = roomPageFixture();
        page.getJSONObject("props").remove("channel");
        assertNull(RoomDataClient.parseRoomPage(page));
        JSONObject page2 = roomPageFixture();
        page2.getJSONObject("props").remove("meetings");
        assertNull(RoomDataClient.parseRoomPage(page2));
        assertNull(RoomDataClient.parseRoomPage(null));
    }

    // ── Meeting detail parsing ───────────────────────────────────────

    private static final String DETAIL_FIXTURE = "{"
            + "\"id\":7,\"title\":\"Eng Room Sync — action items\","
            + "\"description\":\"Follow-up session\","
            + "\"status\":\"ended\","
            + "\"scheduled_at_display\":\"Oct 3, 2026, 7:30 PM\",\"timezone_abbr\":\"IST\","
            + "\"duration_seconds\":1500,"
            + "\"transcript_text\":\"Alex: line one\","
            + "\"transcript_segments\":["
            +   "{\"speakerName\":\"Alex Rivera\",\"text\":\"Kicking off.\",\"timestamp\":12},"
            +   "{\"speakerName\":\"Sam Chen\",\"text\":\"Second line.\",\"timestamp\":48}"
            + "],"
            + "\"historical_sessions\":[{"
            +   "\"id\":6,\"title\":\"Eng Room Sync — transcript review\","
            +   "\"scheduled_at\":\"2026-10-02T14:00:00.000000Z\","
            +   "\"transcript_text\":\"Sam: earlier session\","
            +   "\"transcript_segments\":[{\"speakerName\":\"Sam Chen\",\"text\":\"Earlier.\",\"timestamp\":5}]"
            + "}],"
            + "\"tasks\":["
            +   "{\"id\":31,\"title\":\"Fix transcript rendering\",\"status\":\"in-progress\",\"priority\":\"urgent\",\"due_date\":\"2026-10-10\",\"assignee\":{\"name\":\"Alex Rivera\"}},"
            +   "{\"id\":32,\"title\":\"Second task\",\"status\":\"completed\",\"priority\":\"low\",\"due_date\":null,\"assignee\":null}"
            + "],"
            + "\"task_summary\":{\"total\":2,\"completed\":1,\"incomplete\":1,\"overdue\":0},"
            + "\"ai_metadata\":{\"summaries\":[{"
            +   "\"executive\":\"Two-line executive summary.\","
            +   "\"key_decisions\":[\"Decision A\",\"Decision B\"],"
            +   "\"follow_ups\":[\"Follow-up A\"]"
            + "}]}"
            + "}";

    @Test
    public void parseMeetingDetail_fullFixture() {
        MeetingDetail d = RoomDataClient.parseMeetingDetail(DETAIL_FIXTURE);
        assertNotNull(d);
        assertEquals(7, d.id);
        assertEquals("ended", d.status);
        assertEquals(1500, d.durationSeconds);
        assertEquals(2, d.segments.size());
        assertEquals("Alex Rivera", d.segments.get(0).speaker);
        assertEquals(12, d.segments.get(0).timestamp);

        assertEquals(1, d.historicalSessions.size());
        TranscriptSession s = d.historicalSessions.get(0);
        assertEquals(6, s.meetingId);
        assertEquals(1, s.segments.size());

        assertEquals(2, d.tasks.size());
        assertEquals("Alex Rivera", d.tasks.get(0).assigneeName);
        assertNull(d.tasks.get(1).assigneeName);
        assertNull(d.tasks.get(1).dueDate);

        assertEquals(2, d.taskTotal);
        assertEquals(1, d.taskCompleted);
        assertEquals(0, d.taskOverdue);

        assertEquals("Two-line executive summary.", d.aiSummary);
        assertEquals(2, d.keyDecisions.size());
        assertEquals(1, d.followUps.size());
    }

    @Test
    public void parseMeetingDetail_noTranscript_honestEmpty() throws Exception {
        JSONObject d = new JSONObject(DETAIL_FIXTURE);
        d.put("transcript_text", "");
        d.put("transcript_segments", new org.json.JSONArray());
        d.put("historical_sessions", new org.json.JSONArray());
        MeetingDetail parsed = RoomDataClient.parseMeetingDetail(d.toString());
        assertNotNull(parsed);
        assertTrue(parsed.segments.isEmpty());
        assertTrue(parsed.historicalSessions.isEmpty());
        assertEquals("", parsed.transcriptText);
    }

    @Test
    public void parseMeetingDetail_noAiSummary_nullSummaryEmptyLists() throws Exception {
        JSONObject d = new JSONObject(DETAIL_FIXTURE);
        d.put("ai_metadata", JSONObject.NULL);
        MeetingDetail parsed = RoomDataClient.parseMeetingDetail(d.toString());
        assertNotNull(parsed);
        assertNull(parsed.aiSummary);
        assertTrue(parsed.keyDecisions.isEmpty());
        assertTrue(parsed.followUps.isEmpty());
    }

    @Test
    public void parseMeetingDetail_garbage_returnsNull() {
        assertNull(RoomDataClient.parseMeetingDetail("not json"));
        assertNull(RoomDataClient.parseMeetingDetail(null));
    }

    @Test
    public void parseMeetingDetail_plainTextFallbackWhenNoSegments() throws Exception {
        JSONObject d = new JSONObject(DETAIL_FIXTURE);
        d.put("transcript_segments", new org.json.JSONArray());
        MeetingDetail parsed = RoomDataClient.parseMeetingDetail(d.toString());
        assertNotNull(parsed);
        assertEquals("Alex: line one", parsed.transcriptText);
        assertTrue(parsed.segments.isEmpty());
    }
}
