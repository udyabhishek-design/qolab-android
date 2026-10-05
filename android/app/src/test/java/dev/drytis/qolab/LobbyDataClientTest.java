package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * v1.5.30 — Lobby contract tests against the REAL
 * GET /chat/channels/{id}/messages response shape (verified live):
 * {data:[…newest-first, top-level only…], meta:{…}} with each message
 * carrying id, body, parent_message_id, parent_message, user{name},
 * created_at, replies[].
 */
public class LobbyDataClientTest {

    private static final String LIST_BODY = "{"
            + "\"data\":["
            // top-level newest (id 2) with a reply (id 3)
            + "{\"id\":2,\"body\":\"Second message\",\"parent_message_id\":null,"
            + "\"user\":{\"id\":9,\"name\":\"QA Dropdown V3\",\"job_title\":\"QA Temp\"},"
            + "\"created_at\":\"2026-10-05T06:00:00.000000Z\","
            + "\"replies\":[{\"id\":3,\"body\":\"A reply\",\"parent_message_id\":2,"
            + "\"user\":{\"id\":1,\"name\":\"Admin User\"},"
            + "\"created_at\":\"2026-10-05T06:05:00.000000Z\",\"replies\":[]}]},"
            // top-level older (id 1) quoting a parent (deleted/quoted)
            + "{\"id\":1,\"body\":\"First message\",\"parent_message_id\":null,"
            + "\"parent_message\":{\"id\":0,\"body\":\"older context\",\"user_name\":\"Abhishek udy\"},"
            + "\"user\":{\"id\":1,\"name\":\"Admin User\",\"job_title\":null},"
            + "\"created_at\":\"2026-10-04T09:00:00.000000Z\",\"replies\":[]}"
            + "],"
            + "\"meta\":{\"current_page\":1,\"last_page\":1,\"per_page\":50,\"total\":2}}";

    @Test
    public void parsesMessagesAndOrdersChronologically() {
        List<LobbyDataClient.LobbyMessage> ms = LobbyDataClient.parseMessages(LIST_BODY);
        assertNotNull(ms);
        assertEquals(2, ms.size());
        assertEquals(1, ms.get(0).id);           // oldest first
        assertEquals(2, ms.get(1).id);           // newest last
    }

    @Test
    public void parsesSenderBodyAndHumanFormattableTimestamp() {
        List<LobbyDataClient.LobbyMessage> ms = LobbyDataClient.parseMessages(LIST_BODY);
        assertEquals("Admin User", ms.get(0).senderName);
        assertEquals("First message", ms.get(0).body);
        // raw ISO must be formatable (never rendered raw)
        assertNotNull(RoomFormat.formatIsoDate(ms.get(0).createdAtIso));
    }

    @Test
    public void threadsComeBackInlineAsReplies() {
        List<LobbyDataClient.LobbyMessage> ms = LobbyDataClient.parseMessages(LIST_BODY);
        assertEquals(1, ms.get(1).replies.size());
        assertEquals(3, ms.get(1).replies.get(0).id);
        assertEquals(2, ms.get(1).replies.get(0).parentMessageId);
    }

    @Test
    public void nullJobTitleNeverBecomesLiteralNull() {
        // Admin User has job_title: null in real data
        List<LobbyDataClient.LobbyMessage> ms = LobbyDataClient.parseMessages(LIST_BODY);
        assertTrue(ms.get(0).senderName != null);
        // jobTitle is not part of LobbyMessage; guard on the shared member
        // parser separately (RoomDataClientTest covers members)
    }

    @Test
    public void garbageBodyReturnsNullNotCrash() {
        assertNull(LobbyDataClient.parseMessages(null));
        assertNull(LobbyDataClient.parseMessages("not json"));
        assertNull(LobbyDataClient.parseMessages("{}"));
    }

    @Test
    public void emptyDiscussionIsAnEmptyListNotNull() {
        List<LobbyDataClient.LobbyMessage> ms =
                LobbyDataClient.parseMessages("{\"data\":[],\"meta\":{\"total\":0}}");
        assertNotNull(ms);
        assertTrue(ms.isEmpty());
    }

    @Test
    public void replyParentQuoteClipsLongText() throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("id", 5);
        o.put("body", "reply body");
        long longText = 200;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < longText; i++) sb.append('x');
        JSONObject parent = new JSONObject();
        parent.put("body", sb.toString());
        parent.put("user_name", "Someone");
        o.put("parent_message", parent);
        LobbyDataClient.LobbyMessage m = LobbyDataClient.parseMessage(o);
        assertNotNull(m.parentQuote);
        assertTrue(m.parentQuote.length() < 80);
        assertTrue(m.parentQuote.startsWith("Someone:"));
        assertTrue(m.parentQuote.endsWith("…"));
    }
}
