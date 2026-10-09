package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * v1.5.39 — ChatDataClient contract tests against the REAL /chat Inertia
 * payload shape (verified live 2026-10-09): props.channels.{dm,group,
 * company,department}[] + props.employees[]; DM items carry dm_partner and
 * the partner's display name; rooms never appear (includeRooms=false), and
 * any stray is_room item is skipped defensively.
 */
public class ChatDataClientTest {

    private static final String INDEX_HTML_PAGE = "{"
            + "\"component\":\"Chat/Index\",\"url\":\"https://x/chat\","
            + "\"props\":{"
            + "\"channels\":{"
            + "\"company\":[],\"department\":[],"
            + "\"group\":[{\"id\":51,\"name\":\"QA Native Group Test 2\",\"type\":\"group\","
            + "\"description\":null,\"members_count\":2,\"last_message_at\":"
            + "\"2026-10-09T19:20:21.000000Z\",\"unread_count\":0,\"dm_partner\":null}],"
            + "\"dm\":["
            // fresh DM (partner name resolved)
            + "{\"id\":52,\"name\":\"QA Dropdown V3\",\"type\":\"dm\",\"description\":null,"
            + "\"members_count\":2,\"last_message_at\":\"2026-10-09T19:25:00.000000Z\","
            + "\"unread_count\":2,\"dm_partner\":{\"id\":13,\"name\":\"QA Dropdown V3\","
            + "\"job_title\":\"QA Temp\",\"department\":{\"id\":7,\"name\":\"Engineering\"}}},"
            // stale DM (no activity yet)
            + "{\"id\":49,\"name\":\"QA Dropdown V3\",\"type\":\"dm\",\"description\":null,"
            + "\"members_count\":2,\"last_message_at\":\"2026-10-01T10:35:55.000000Z\","
            + "\"unread_count\":0,\"dm_partner\":{\"id\":13,\"name\":\"QA Dropdown V3\"}},"
            // stray room item must be skipped
            + "{\"id\":77,\"name\":\"Coffee Lounge\",\"type\":\"casual\",\"is_room\":true,"
            + "\"members_count\":3,\"last_message_at\":\"2026-10-09T10:00:00.000000Z\","
            + "\"unread_count\":0,\"dm_partner\":null}"
            + "]},"
            + "\"employees\":["
            + "{\"id\":13,\"name\":\"QA Dropdown V3\",\"avatar_path\":null,\"job_title\":\"QA Temp\","
            + "\"department\":{\"id\":7,\"name\":\"Engineering\"},\"shift\":null,\"timezone\":\"Asia/Kolkata\"},"
            + "{\"id\":3,\"name\":\"Pugazh\",\"avatar_path\":null,\"job_title\":\"Manager\","
            + "\"department\":null,\"shift\":null,\"timezone\":\"UTC\"}"
            + "]}}";

    @Test
    public void parsesConversationsSortedRecentFirst() {
        ChatDataClient.ChatIndex idx = ChatDataClient.parseInertiaIndex(
                json(INDEX_HTML_PAGE));
        assertNotNull(idx);
        List<ChatDataClient.Conversation> cs = idx.conversations;
        assertEquals(3, cs.size()); // room skipped
        // newest activity first
        assertEquals(52L, cs.get(0).id);
        assertEquals(51L, cs.get(1).id);
        assertEquals(49L, cs.get(2).id);
        assertTrue(cs.get(0).dm);
        assertEquals("QA Dropdown V3", cs.get(0).partnerName);
        assertEquals(2, cs.get(0).unreadCount);
        assertEquals("group", cs.get(1).type);
    }

    @Test
    public void parsesEmployees() {
        ChatDataClient.ChatIndex idx = ChatDataClient.parseInertiaIndex(
                json(INDEX_HTML_PAGE));
        assertEquals(2, idx.employees.size());
        assertEquals(13L, idx.employees.get(0).id);
        assertEquals("QA Temp", idx.employees.get(0).jobTitle);
        assertEquals("Engineering", idx.employees.get(0).departmentName);
        assertNull(idx.employees.get(1).departmentName);
    }

    @Test
    public void brokenDmWithoutPartnerIsSkipped() {
        String page = "{\"props\":{\"channels\":{\"dm\":[{\"id\":9,\"name\":null,"
                + "\"type\":\"dm\",\"members_count\":2,\"last_message_at\":\"x\","
                + "\"unread_count\":0,\"dm_partner\":null}]},\"employees\":[]}}";
        ChatDataClient.ChatIndex idx = ChatDataClient.parseInertiaIndex(json(page));
        assertNotNull(idx);
        assertEquals(0, idx.conversations.size());
    }

    @Test
    public void nullPageReturnsNull() {
        assertNull(ChatDataClient.parseInertiaIndex(null));
        assertNull(ChatDataClient.parseInertiaIndex(json("{\"props\":{}}")));
    }

    // ── messages ──────────────────────────────────────────────────────

    @Test
    public void parsesMessagesNewestFirstToChronological() {
        String body = "{\"data\":["
                + "{\"id\":10,\"channel_id\":51,\"user_id\":5,\"body\":\"second\","
                + "\"type\":\"text\",\"created_at\":\"2026-10-09T19:20:21.000000Z\","
                + "\"user\":{\"id\":5,\"name\":\"QA Admin (Dev)\"},\"attachments\":[],\"replies\":[]},"
                + "{\"id\":9,\"channel_id\":51,\"user_id\":13,\"body\":\"first\","
                + "\"type\":\"text\",\"created_at\":\"2026-10-09T19:19:00.000000Z\","
                + "\"user\":{\"id\":13,\"name\":\"QA Dropdown V3\"},\"attachments\":[],\"replies\":[]}"
                + "],\"meta\":{\"current_page\":1,\"last_page\":1,\"per_page\":50,\"total\":2}}";
        ChatDataClient.MessagesPage p = ChatDataClient.parseMessages(body);
        assertNotNull(p);
        assertEquals(2, p.messages.size());
        assertEquals(9L, p.messages.get(0).id);   // oldest first
        assertEquals(10L, p.messages.get(1).id);
        assertEquals("QA Dropdown V3", p.messages.get(0).senderName);
        assertEquals(1, p.lastPage);
    }

    @Test
    public void malformedMessagesReturnNull() {
        assertNull(ChatDataClient.parseMessages(null));
        assertNull(ChatDataClient.parseMessages("<html>404</html>"));
        assertNull(ChatDataClient.parseMessages("{\"nope\":1}"));
    }

    @Test
    public void parsesUnreadCounts() {
        java.util.Map<Long, Integer> m = ChatDataClient.parseUnreadCounts(
                "{\"data\":{\"7\":2,\"50\":1}}");
        assertEquals(Integer.valueOf(2), m.get(7L));
        assertEquals(Integer.valueOf(1), m.get(50L));
        assertTrue(ChatDataClient.parseUnreadCounts(null).isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────

    @Test
    public void channelIdFromUrl() {
        assertEquals(51L, ChatDataClient.channelIdFromUrl(
                "https://workpulse-gbpedt.drytis.dev/chat/channels/51"));
        assertEquals(0L, ChatDataClient.channelIdFromUrl(
                "https://x.dev/chat"));
        assertEquals(0L, ChatDataClient.channelIdFromUrl(null));
    }

    @Test
    public void isoMillisParsesBackendFormats() {
        long t = ChatDataClient.parseIsoMillis("2026-10-09T19:20:21.000000Z");
        assertTrue(t > 0);
        assertEquals(0L, ChatDataClient.parseIsoMillis(null));
        assertEquals(0L, ChatDataClient.parseIsoMillis("not-a-date"));
    }

    @Test
    public void parseBroadcastMessage() {
        String payload = "{\"id\":11,\"channel_id\":51,\"user_id\":13,\"body\":\"hello\","
                + "\"type\":\"text\",\"created_at\":\"2026-10-09T20:00:00.000000Z\","
                + "\"user\":{\"id\":13,\"name\":\"QA Dropdown V3\"}}";
        ChatDataClient.ChatMessage m = ChatDataClient.parseBroadcastMessage(payload);
        assertNotNull(m);
        assertEquals(11L, m.id);
        assertEquals(13L, m.userId);
        assertNull(ChatDataClient.parseBroadcastMessage("garbage"));
    }

    private static JSONObject json(String s) {
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
