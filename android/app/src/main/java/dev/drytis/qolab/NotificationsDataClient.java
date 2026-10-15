package dev.drytis.qolab;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v1.5.46 — Notifications data layer (pure JSON; no Android imports).
 *
 * Contracts consumed (existing, unchanged — verified live 2026-10-15):
 *   GET   /notifications?page=N  → {"data":[{id,type,title,body,data:{...},
 *        read_at, created_at}...], "meta":{current_page,last_page,
 *        per_page,total}}
 *   GET   /notifications/unread-count → {"unread_count":N}
 *   PATCH /notifications/{id}/read     → {"success":true}
 *   PATCH /notifications/read-all      → {"success":true}
 *
 * A PATCH to a notification that does not belong to this user answers
 * {"success":false} with HTTP 200 — the UI must treat that as FAILURE,
 * not as read.
 */
public final class NotificationsDataClient {

    private NotificationsDataClient() {}

    /** One notification row; identity from the payload, never fabricated. */
    public static final class Item {
        public final long id;
        public final String type;          // e.g. "message.sent"
        public final String title;
        public final String body;
        public final String createdAtIso;  // "2026-10-14T18:54:49.000000Z"
        public final Long channelId;       // message.sent only (may be null)
        public final String channelName;   // message.sent only (may be null)
        public final String channelType;   // group/dm/company/department
        public final boolean isDm;
        public final boolean read;

        Item(long id, String type, String title, String body, String createdAtIso,
             Long channelId, String channelName, String channelType,
             boolean isDm, boolean read) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.body = body;
            this.createdAtIso = createdAtIso;
            this.channelId = channelId;
            this.channelName = channelName;
            this.channelType = channelType;
            this.isDm = isDm;
            this.read = read;
        }

        /** Copy constructor used by read-state patching. */
        static Item copyRead(Item src, boolean read) {
            return new Item(src.id, src.type, src.title, src.body,
                    src.createdAtIso, src.channelId, src.channelName,
                    src.channelType, src.isDm, read);
        }
    }

    /** Parsed page: rows + pagination truth from meta. */
    public static final class Page {
        public final List<Item> items;
        public final int currentPage;
        public final int lastPage;
        public final int total;

        Page(List<Item> items, int currentPage, int lastPage, int total) {
            this.items = items;
            this.currentPage = currentPage;
            this.lastPage = lastPage;
            this.total = total;
        }

        public boolean hasMore() {
            return currentPage < lastPage;
        }
    }

    /** null → parse failure (caller shows retryable error). */
    public static Page parsePage(String body) {
        if (body == null) return null;
        try {
            JSONObject root = new JSONObject(body);
            org.json.JSONArray arr = root.optJSONArray("data");
            JSONObject meta = root.optJSONObject("meta");
            if (arr == null || meta == null) return null;
            int current = meta.optInt("current_page", 1);
            int last = meta.optInt("last_page", 1);
            int total = meta.optInt("total", arr.length());
            List<Item> items = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                Item it = parseItem(arr.getJSONObject(i));
                if (it != null && it.id > 0) items.add(it);
            }
            return new Page(items, current, last, total);
        } catch (Exception e) {
            return null;
        }
    }

    /** null → invalid row (skipped by parsePage). */
    public static Item parseItem(JSONObject o) {
        if (o == null) return null;
        try {
            long id = o.optLong("id", 0);
            String type = o.optString("type", null);
            String title = o.optString("title", null);
            String body = o.optString("body", null);
            String created = o.optString("created_at", null);
            JSONObject data = o.optJSONObject("data");
            Long channelId = null;
            String channelName = null;
            String channelType = null;
            boolean isDm = false;
            if (data != null) {
                long cid = data.optLong("channel_id", 0);
                if (cid > 0) channelId = cid;
                channelName = data.optString("channel_name", null);
                channelType = data.optString("channel_type", null);
                isDm = data.optBoolean("is_dm", false);
            }
            boolean read = !o.isNull("read_at");
            if (id <= 0) return null;
            if (title == null && body == null) return null;
            return new Item(id, type, title, body, created, channelId,
                    channelName, channelType, isDm, read);
        } catch (Exception e) {
            return null;
        }
    }

    /** {"unread_count":N} → N; -1 on parse failure (caller preserves). */
    public static int parseUnreadCount(String body) {
        if (body == null) return -1;
        try {
            JSONObject o = new JSONObject(body);
            return o.optInt("unread_count", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * PATCH /notifications/{id}/read response → success. Live-verified:
     * HTTP 200 {"success":true} on success; {"success":false} (also 200)
     * when the notification is not this user's — treated as failure.
     */
    public static boolean parseSuccess(String body) {
        if (body == null) return false;
        try {
            return new JSONObject(body).optBoolean("success", false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Read-state patching with dedupe — pagination can overlap pages. */
    public static List<Item> mergeItems(List<Item> existing, List<Item> next) {
        List<Item> out = new ArrayList<>(existing.size() + next.size());
        Set<Long> seen = new HashSet<>();
        for (Item i : existing) {
            if (i != null && i.id > 0 && seen.add(i.id)) out.add(i);
        }
        for (Item i : next) {
            if (i != null && i.id > 0 && seen.add(i.id)) out.add(i);
        }
        return out;
    }

    /** Local mark-read of one row (after SERVER confirmation only). */
    public static List<Item> withRead(List<Item> items, long id) {
        List<Item> out = new ArrayList<>(items.size());
        for (Item i : items) {
            out.add(i.id == id ? Item.copyRead(i, true) : i);
        }
        return out;
    }

    /** Mark all rows read locally (after SERVER read-all confirmation). */
    public static List<Item> allRead(List<Item> items) {
        List<Item> out = new ArrayList<>(items.size());
        for (Item i : items) {
            out.add(Item.copyRead(i, true));
        }
        return out;
    }

    /** Unread count of the currently loaded rows (badge sync source). */
    public static int countUnread(List<Item> items) {
        int n = 0;
        for (Item i : items) if (!i.read) n++;
        return n;
    }

    /**
     * Should tapping this notification open the native Chat screen?
     * Only message.sent rows that carry a real channel_id. Every other
     * type renders its information without an invented destination.
     */
    public static boolean isChatOpenable(Item i) {
        return i != null
                && "message.sent".equals(i.type)
                && i.channelId != null && i.channelId > 0;
    }
}
