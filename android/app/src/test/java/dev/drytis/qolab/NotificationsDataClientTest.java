package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v1.5.46 — Notifications data-layer regression tests.
 * Payload shapes captured live from GET /notifications on 2026-10-15
 * (message.sent, livehub, onboarding.document_signed rows all included).
 */
public class NotificationsDataClientTest {

    /** Live-captured shape: unread message.sent with channel payload. */
    private static final String PAGE_JSON = "{"
            + "\"data\":["
            + "{\"id\":253,\"type\":\"message.sent\","
            + "\"title\":\"New message in Coffee Lounge\","
            + "\"body\":\"Abhishek udy: Hello\","
            + "\"data\":{\"channel_id\":47,\"channel_name\":\"Coffee Lounge\","
            + "\"channel_type\":\"group\",\"is_dm\":false,\"message_id\":16,"
            + "\"sender_id\":26,\"sender_name\":\"Abhishek udy\",\"sender_avatar\":null},"
            + "\"read_at\":null,"
            + "\"created_at\":\"2026-10-14T18:54:49.000000Z\"},"
            + "{\"id\":244,\"type\":\"livehub\","
            + "\"title\":\"External visitor joined\","
            + "\"body\":\"Guest joined \\\"E2E Live Hub test session\\\".\","
            + "\"data\":{\"live_room_id\":5,\"guest_identity\":\"guest-1\"},"
            + "\"read_at\":\"2026-10-13T18:00:00.000000Z\","
            + "\"created_at\":\"2026-10-13T17:07:14.000000Z\"},"
            + "{\"id\":231,\"type\":\"onboarding.document_signed\","
            + "\"title\":\"Document signed\","
            + "\"body\":\"A document was signed\","
            + "\"data\":{\"document_id\":9},"
            + "\"read_at\":null,"
            + "\"created_at\":\"2026-10-12T09:00:00.000000Z\"}"
            + "],"
            + "\"meta\":{\"current_page\":1,\"last_page\":2,\"per_page\":20,\"total\":33}"
            + "}";

    private static final String EMPTY_PAGE = "{"
            + "\"data\":[],\"meta\":{\"current_page\":1,\"last_page\":1,"
            + "\"per_page\":20,\"total\":0}}";

    @Test
    public void parsesValidPageWithMeta() {
        NotificationsDataClient.Page p =
                NotificationsDataClient.parsePage(PAGE_JSON);
        assertNotNull(p);
        assertEquals(3, p.items.size());
        assertEquals(1, p.currentPage);
        assertEquals(2, p.lastPage);
        assertEquals(33, p.total);
        assertTrue(p.hasMore());
    }

    @Test
    public void messageSentRowCarriesChannelIdentity() {
        NotificationsDataClient.Page p =
                NotificationsDataClient.parsePage(PAGE_JSON);
        NotificationsDataClient.Item msg = p.items.get(0);
        assertEquals(253L, msg.id);
        assertEquals("message.sent", msg.type);
        assertEquals(Long.valueOf(47L), msg.channelId);
        assertEquals("Coffee Lounge", msg.channelName);
        assertEquals("group", msg.channelType);
        assertFalse(msg.isDm);
        assertFalse(msg.read);
        assertEquals("2026-10-14T18:54:49.000000Z", msg.createdAtIso);
        assertTrue(NotificationsDataClient.isChatOpenable(msg));
    }

    @Test
    public void nonMessageTypesAreNotChatOpenable() {
        NotificationsDataClient.Page p =
                NotificationsDataClient.parsePage(PAGE_JSON);
        // livehub row (read)
        assertFalse(NotificationsDataClient.isChatOpenable(p.items.get(1)));
        // onboarding row (unread, no channel_id)
        assertFalse(NotificationsDataClient.isChatOpenable(p.items.get(2)));
    }

    @Test
    public void readFlagFromReadAt() {
        NotificationsDataClient.Page p =
                NotificationsDataClient.parsePage(PAGE_JSON);
        assertTrue(p.items.get(1).read);  // read_at set
        assertFalse(p.items.get(0).read); // read_at null
        assertFalse(p.items.get(2).read); // read_at null
    }

    @Test
    public void emptyPageShowsEmptyNotError() {
        NotificationsDataClient.Page p =
                NotificationsDataClient.parsePage(EMPTY_PAGE);
        assertNotNull(p);
        assertEquals(0, p.items.size());
        assertFalse(p.hasMore());
        assertEquals(0, NotificationsDataClient.countUnread(p.items));
    }

    @Test
    public void unreadCountCountsOnlyUnreadRows() {
        NotificationsDataClient.Page p =
                NotificationsDataClient.parsePage(PAGE_JSON);
        assertEquals(2, NotificationsDataClient.countUnread(p.items));
    }

    @Test
    public void malformedBodiesParseToNullNotCrash() {
        assertNull(NotificationsDataClient.parsePage(null));
        assertNull(NotificationsDataClient.parsePage(""));
        assertNull(NotificationsDataClient.parsePage("not json"));
        assertNull(NotificationsDataClient.parsePage("{\"data\":[]}")); // no meta
        assertNull(NotificationsDataClient.parseItem(null));
        // A row with an invalid id is SKIPPED (page still parses) — one
        // bad server row must never blank the whole list.
        NotificationsDataClient.Page p = NotificationsDataClient.parsePage(
                "{\"data\":[{\"id\":0,\"title\":\"x\"}],"
                        + "\"meta\":{\"current_page\":1,\"last_page\":1}}");
        assertNotNull(p);
        assertEquals(0, p.items.size());
    }

    @Test
    public void unreadCountPayload() {
        assertEquals(33,
                NotificationsDataClient.parseUnreadCount("{\"unread_count\":33}"));
        assertEquals(0,
                NotificationsDataClient.parseUnreadCount("{\"unread_count\":0}"));
        assertEquals(-1,
                NotificationsDataClient.parseUnreadCount("{\"foo\":1}"));
        assertEquals(-1, NotificationsDataClient.parseUnreadCount(null));
    }

    @Test
    public void markReadSuccessOnlyOnExplicitTrue() {
        // Live-verified: 200 {"success":false} when the notification is NOT
        // this user's — must be treated as failure, never as read.
        assertTrue(NotificationsDataClient
                .parseSuccess("{\"success\":true}"));
        assertFalse(NotificationsDataClient
                .parseSuccess("{\"success\":false}"));
        assertFalse(NotificationsDataClient.parseSuccess(null));
        assertFalse(NotificationsDataClient.parseSuccess("garbage"));
    }

    @Test
    public void withReadFlipsOnlyTheTargetRow() {
        NotificationsDataClient.Page p =
                NotificationsDataClient.parsePage(PAGE_JSON);
        List<NotificationsDataClient.Item> after =
                NotificationsDataClient.withRead(p.items, 253);
        assertTrue(after.get(0).read);
        assertTrue(after.get(1).read);  // was already read
        assertFalse(after.get(2).read); // untouched
        // original list untouched (immutability)
        assertFalse(p.items.get(0).read);
    }

    @Test
    public void allReadMarksEveryRow() {
        NotificationsDataClient.Page p =
                NotificationsDataClient.parsePage(PAGE_JSON);
        List<NotificationsDataClient.Item> after =
                NotificationsDataClient.allRead(p.items);
        assertEquals(0, NotificationsDataClient.countUnread(after));
    }

    @Test
    public void paginationMergeDedupesById() {
        NotificationsDataClient.Page p1 =
                NotificationsDataClient.parsePage(PAGE_JSON);
        // Page 2 overlaps row 231 (server pages can overlap after reads).
        List<NotificationsDataClient.Item> merged =
                NotificationsDataClient.mergeItems(
                        p1.items, Arrays.asList(p1.items.get(2)));
        assertEquals(3, merged.size());
    }

    @Test
    public void mergeSkipsNullAndInvalid() {
        NotificationsDataClient.Page p1 =
                NotificationsDataClient.parsePage(PAGE_JSON);
        List<NotificationsDataClient.Item> empty = new ArrayList<>();
        assertEquals(p1.items.size(),
                NotificationsDataClient.mergeItems(p1.items, empty).size());
        assertEquals(0,
                NotificationsDataClient.mergeItems(empty, empty).size());
    }
}
