package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * v1.5.40 — Chat mockup UI data tests:
 *   - filter classification (All / DMs / Groups / Announcements)
 *   - pinned-section rule (official company channels; the backend has
 *     NO per-user pin flag — verified)
 *   - pastel color stability per channel id
 *   - announcements parsing (GET /announcements Inertia props)
 */
public class ChatMockupTest {

    private static ChatDataClient.Conversation conv(
            long id, String name, String type, boolean dm) {
        return new ChatDataClient.Conversation(id, name, type, null, 3,
                "2026-10-09T10:00:00Z", 0, dm, null);
    }

    // ── filters ─────────────────────────────────────────────────────

    @Test
    public void filterDmsKeepsOnlyDms() {
        ChatDataClient.Conversation dm = conv(1, "Alice", "dm", true);
        ChatDataClient.Conversation group = conv(2, "Team", "group", false);
        ChatDataClient.Conversation company = conv(3, "general", "company", false);
        assertTrue(ChatDataClient.matchesFilter(dm, ChatDataClient.ChatFilter.DMS));
        assertFalse(ChatDataClient.matchesFilter(group, ChatDataClient.ChatFilter.DMS));
        assertFalse(ChatDataClient.matchesFilter(company, ChatDataClient.ChatFilter.DMS));
    }

    @Test
    public void filterGroupsKeepsOnlyGroups() {
        assertTrue(ChatDataClient.matchesFilter(conv(2, "Team", "group", false),
                ChatDataClient.ChatFilter.GROUPS));
        assertFalse(ChatDataClient.matchesFilter(conv(1, "Alice", "dm", true),
                ChatDataClient.ChatFilter.GROUPS));
        assertFalse(ChatDataClient.matchesFilter(conv(3, "general", "company", false),
                ChatDataClient.ChatFilter.GROUPS));
    }

    @Test
    public void filterAnnouncementsKeepsCompanyChannels() {
        assertTrue(ChatDataClient.matchesFilter(conv(3, "announcements", "company", false),
                ChatDataClient.ChatFilter.ANNOUNCEMENTS));
        assertFalse(ChatDataClient.matchesFilter(conv(2, "Team", "group", false),
                ChatDataClient.ChatFilter.ANNOUNCEMENTS));
    }

    @Test
    public void filterAllKeepsEverything() {
        assertTrue(ChatDataClient.matchesFilter(conv(1, "A", "dm", true),
                ChatDataClient.ChatFilter.ALL));
        assertTrue(ChatDataClient.matchesFilter(conv(2, "B", "group", false),
                ChatDataClient.ChatFilter.ALL));
        assertTrue(ChatDataClient.matchesFilter(conv(3, "C", "company", false),
                ChatDataClient.ChatFilter.ALL));
    }

    @Test
    public void nullConversationMatchesNothing() {
        assertFalse(ChatDataClient.matchesFilter(null, ChatDataClient.ChatFilter.ALL));
    }

    // ── pinned rule ─────────────────────────────────────────────────

    @Test
    public void officialCompanyChannelsArePinned() {
        assertTrue(ChatDataClient.isPinned(conv(54, "general", "company", false)));
        assertTrue(ChatDataClient.isPinned(conv(55, "announcements", "company", false)));
    }

    @Test
    public void dmsAndGroupsAreNotPinned() {
        assertFalse(ChatDataClient.isPinned(conv(1, "Alice", "dm", true)));
        assertFalse(ChatDataClient.isPinned(conv(2, "Team", "group", false)));
        assertFalse(ChatDataClient.isPinned(null));
    }

    // ── pastel palette ──────────────────────────────────────────────

    @Test
    public void pastelIndexIsStablePerId() {
        assertEquals(ChatDataClient.pastelIndexFor(54, 5),
                ChatDataClient.pastelIndexFor(54, 5));
    }

    @Test
    public void pastelIndexStaysInBounds() {
        for (long id : new long[]{0, 1, 5, 6, 100, -3, Long.MAX_VALUE}) {
            int i = ChatDataClient.pastelIndexFor(id, 5);
            assertTrue("index in [0,5): " + i, i >= 0 && i < 5);
        }
        assertEquals(0, ChatDataClient.pastelIndexFor(7, 0)); // degenerate
    }

    // ── announcements parsing ───────────────────────────────────────

    @Test
    public void parsesAnnouncementsFromInertiaPage() throws Exception {
        String pageJson = "{\"component\":\"Announcements/Index\",\"props\":{"
                + "\"announcements\":{\"data\":["
                + "{\"id\":1,\"title\":\"Welcome\",\"priority\":\"normal\","
                + "\"published_at\":\"2026-10-08T10:00:00.000000Z\","
                + "\"creator\":{\"id\":5,\"name\":\"Q A Admin\"}},"
                + "{\"id\":2,\"title\":\"Second\",\"priority\":\"high\","
                + "\"published_at\":\"2026-10-07T10:00:00.000000Z\","
                + "\"creator\":null}"
                + "],\"meta\":{\"total\":2}}}}";
        List<ChatDataClient.Announcement> list =
                ChatDataClient.parseAnnouncements(new JSONObject(pageJson));
        assertEquals(2, list.size());
        assertEquals("Welcome", list.get(0).title);
        assertEquals("normal", list.get(0).priority);
        assertEquals("Q A Admin", list.get(0).creatorName);
        assertEquals(2, list.get(1).id);
    }

    @Test
    public void emptyOrMissingAnnouncementsParseToEmpty() throws Exception {
        assertTrue(ChatDataClient.parseAnnouncements(null).isEmpty());
        assertTrue(ChatDataClient.parseAnnouncements(new JSONObject("{}")).isEmpty());
        assertTrue(ChatDataClient.parseAnnouncements(
                new JSONObject("{\"props\":{}}")).isEmpty());
    }

    // ── layout regression (mockup elements present, honest) ────────

    private static String read(String rel) throws Exception {
        java.io.File f = new java.io.File(
                System.getProperty("user.dir") + "/../" + rel);
        if (!f.exists()) f = new java.io.File(rel);
        if (!f.exists()) f = new java.io.File("/home/coder/qolab-android/" + rel);
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }

    @Test
    public void chatListLayoutHasMockupElements() throws Exception {
        String xml = read("android/app/src/main/res/layout/activity_chat_list.xml");
        assertTrue("Qolab wordmark", xml.contains("@string/chat_wordmark"));
        assertTrue("heading", xml.contains("@string/chat_title"));
        assertTrue("subtitle", xml.contains("@string/chat_subtitle"));
        assertTrue("filter chips", xml.contains("chat_filter_all")
                && xml.contains("chat_filter_dms")
                && xml.contains("chat_filter_groups")
                && xml.contains("chat_filter_announcements"));
        assertTrue("search action", xml.contains("chat_btn_search"));
        assertTrue("announcements banner", xml.contains("chat_announcement_banner"));
        assertTrue("banner hidden by default (real data only)",
                xml.contains("android:id=\"@+id/chat_announcement_banner\""));
        assertTrue("floating compose", xml.contains("chat_fab"));
        // Social Rooms / Department Rooms stay out of Chat
        assertFalse("no room tabs in Chat", xml.contains("lobby_tab_"));
        assertFalse("no room cards in Chat", xml.contains("@layout/view_room_card"));
    }

    @Test
    public void chatRowHasTimestampAndPastelCard() throws Exception {
        String xml = read("android/app/src/main/res/layout/view_chat_row.xml");
        assertTrue("timestamp column", xml.contains("chat_row_time"));
        assertTrue("unread badge", xml.contains("chat_row_unread"));
        assertTrue("card container for pastel background",
                xml.contains("chat_row_card"));
    }

    @Test
    public void bannerStartsHiddenUntilRealDataArrives() throws Exception {
        String xml = read("android/app/src/main/res/layout/activity_chat_list.xml");
        // The banner view must carry visibility=gone in the layout — it
        // only appears when a REAL announcement was fetched.
        int idx = xml.indexOf("chat_announcement_banner");
        int gone = xml.indexOf("android:visibility=\"gone\"", idx);
        int nextId = xml.indexOf("android:id=\"@+id/", idx + 10);
        assertTrue("banner must start gone",
                gone > -1 && (nextId == -1 || gone < nextId));
    }
}
