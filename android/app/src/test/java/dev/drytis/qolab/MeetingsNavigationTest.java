package dev.drytis.qolab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.junit.Assert.*;

/** v1.5.49 regression tests — Meetings navigation + list parsing. */
public class MeetingsNavigationTest {

    private static String read(String rel) throws IOException {
        return new String(Files.readAllBytes(
                Paths.get(System.getProperty("user.dir"), "..", rel).normalize()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── parser (pure) ─────────────────────────────────────────────────

    private static JSONObject page(String upJson, String pastJson) throws Exception {
        JSONObject page = new JSONObject();
        JSONObject props = new JSONObject();
        if (upJson != null) props.put("upcoming", new JSONObject(upJson));
        if (pastJson != null) props.put("past", new JSONObject(pastJson));
        page.put("props", props);
        return page;
    }

    @Test
    public void parsesUpcomingAndPastCollections() throws Exception {
        String up = "{\"data\":[{\"id\":41,\"title\":\"Team sync\",\"status\":\"scheduled\","
                + "\"scheduled_at_display\":\"Oct 16, 2026, 9:00 AM\",\"timezone_abbr\":\"PKT\","
                + "\"duration_seconds\":1800,\"has_transcript\":false,\"meeting_type\":\"group\","
                + "\"meeting_source\":\"social_room\",\"participants\":[{\"id\":1},{\"id\":2}],"
                + "\"is_participant\":true}],"
                + "\"meta\":{\"current_page\":1,\"last_page\":1,\"per_page\":15,\"total\":1}}";
        String past = "{\"data\":[{\"id\":12,\"title\":\"Retro\",\"status\":\"ended\","
                + "\"scheduled_at_display\":\"Oct 6, 2026, 7:36 AM\",\"timezone_abbr\":\"PKT\","
                + "\"duration_seconds\":3540,\"has_transcript\":true,\"meeting_type\":\"department\","
                + "\"participants\":[]}],"
                + "\"meta\":{\"current_page\":1,\"last_page\":3,\"per_page\":15,\"total\":32}}";
        MeetingsDataClient.MeetingsPage p = MeetingsDataClient.parse(page(up, past));
        assertNotNull(p);
        assertEquals(1, p.upcoming.size());
        assertEquals(1, p.past.size());
        assertEquals(1, p.totalUpcoming);
        assertEquals(32, p.totalPast);
        MeetingsDataClient.MeetingRow r = p.upcoming.get(0);
        assertEquals(41, r.id);
        assertEquals("Team sync", r.title);
        assertEquals("scheduled", r.status);
        assertEquals("Oct 16, 2026, 9:00 AM", r.scheduledAtDisplay);
        assertEquals("PKT", r.timezoneAbbr);
        assertEquals(1800, r.durationSeconds);
        assertEquals(2, r.participantsCount);
        assertTrue(r.isParticipant);
        assertTrue(p.past.get(0).hasTranscript);
        assertEquals("ended", p.past.get(0).status);
    }

    @Test
    public void emptyCollectionsParseToEmptyLists() throws Exception {
        String empty = "{\"data\":[],\"meta\":{\"current_page\":1,\"last_page\":1,\"total\":0}}";
        MeetingsDataClient.MeetingsPage p = MeetingsDataClient.parse(page(empty, empty));
        assertNotNull(p);
        assertTrue(p.upcoming.isEmpty());
        assertTrue(p.past.isEmpty());
        assertEquals(0, p.totalUpcoming);
        assertEquals(0, p.totalPast);
    }

    @Test
    public void invalidRowsAreSkipped() throws Exception {
        String up = "{\"data\":[{\"title\":\"no id\"},{\"id\":7,\"title\":\"valid\"}],\"meta\":{}}";
        MeetingsDataClient.MeetingsPage p = MeetingsDataClient.parse(page(up, null));
        assertEquals(1, p.upcoming.size());
        assertEquals(7, p.upcoming.get(0).id);
    }

    @Test
    public void malformedPageReturnsNull() throws Exception {
        assertNull(MeetingsDataClient.parse(null));
        assertNull(MeetingsDataClient.parse(new JSONObject("{\"props\":{}}")));
        assertNull(MeetingsDataClient.parse(new JSONObject("{\"nothing\":true}")));
    }

    @Test
    public void openableRequiresPositiveId() throws Exception {
        JSONObject j = new JSONObject("{\"id\":5,\"title\":\"x\"}");
        assertTrue(MeetingsDataClient.openable(MeetingsDataClient.row(j)));
        JSONObject j0 = new JSONObject("{\"id\":0,\"title\":\"x\"}");
        assertFalse(MeetingsDataClient.openable(MeetingsDataClient.row(j0)));
        JSONObject jn = new JSONObject("{\"title\":\"x\"}");
        assertNull(MeetingsDataClient.row(jn));
    }

    // ── wiring ────────────────────────────────────────────────────────

    @Test
    public void hamburgerMeetingsOpensNativeList() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/HomeActivity.java");
        int menu = src.indexOf("private void showMenuSheet()");
        assertTrue(menu > 0);
        int wiring = src.indexOf("home_menu_meetings", menu);
        int nav = src.indexOf("MeetingsListActivity.class", menu);
        assertTrue("Meetings menu item must open MeetingsListActivity", nav > 0);
        assertTrue("wiring inside the menu sheet", wiring > 0 && nav > wiring);
        // Other destinations still Coming Soon (untouched).
        assertTrue(src.contains("showComingSoon(dest)"));
    }

    @Test
    public void meetingCardOpensDetailWithRealExtras() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/HomeActivity.java");
        int render = src.indexOf("private void renderMeeting()");
        String body = src.substring(render, src.indexOf("private void renderRooms"));
        assertTrue("card must be clickable when a meeting exists",
                body.contains("meetingCard.setOnClickListener"));
        assertTrue("must pass the real meeting id",
                body.contains("EXTRA_MEETING_ID, m.id"));
        assertTrue("must pass the real title",
                body.contains("EXTRA_MEETING_TITLE"));
        assertTrue("invalid ids never navigate",
                body.contains("if (m.id > 0)"));
        assertTrue("non-clickable when id invalid",
                body.contains("meetingCard.setClickable(false)"));
    }

    @Test
    public void listActivityExistsAndIsRegistered() throws IOException {
        assertTrue("MeetingsListActivity source exists",
                new java.io.File(System.getProperty("user.dir"),
                        "src/main/java/dev/drytis/qolab/MeetingsListActivity.java").exists());
        String manifest = read("app/src/main/AndroidManifest.xml");
        assertTrue("manifest registers MeetingsListActivity",
                manifest.contains(".MeetingsListActivity"));
        String layout = read("app/src/main/res/layout/activity_meetings_list.xml");
        assertTrue(layout.contains("@+id/meetings_skeleton"));
        assertTrue(layout.contains("@+id/meetings_error_box"));
        assertTrue(layout.contains("@+id/meetings_retry"));
        assertTrue(layout.contains("@+id/meetings_empty"));
    }

    @Test
    public void listActivityHasAllFourStates() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/MeetingsListActivity.java");
        assertTrue("skeleton while first load", src.contains("private void showLoading()"));
        assertTrue("error + Retry", src.contains("private void showError()"));
        assertTrue("refresh failure preserves content (v1.5.44 rule)",
                src.indexOf("if (page != null)", src.indexOf("private void showError")) > 0);
        assertTrue("empty state per section",
                src.contains("meetings_empty"));
        // Fetch + parse contract.
        assertTrue("fetches GET /meetings via the authenticated transport",
                src.contains("transport.getJson(\"/meetings\""));
        assertTrue("parses via the pure client",
                src.contains("MeetingsDataClient.parse(inertia)"));
        // Session/recovery patterns preserved.
        assertTrue("SessionGate verifies dead transport",
                src.contains("SessionGate.requireValidSession"));
        assertTrue("ConnectivityWatcher auto-retry registered",
                src.contains("ConnectivityWatcher.register"));
        assertTrue("watcher unregistered on destroy",
                src.contains("ConnectivityWatcher.unregister(this, netWatcher)"));
    }

    @Test
    public void rowsOpenExistingDetailActivity() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/MeetingsListActivity.java");
        int card = src.indexOf("private View meetingCard(");
        String body = src.substring(card);
        assertTrue("rows reuse MeetingDetailActivity", body.contains("MeetingDetailActivity.class"));
        assertTrue("rows pass the row id", body.contains("EXTRA_MEETING_ID, m.id"));
        assertTrue("guard against invalid ids",
                body.contains("MeetingsDataClient.openable(m)"));
    }
}
