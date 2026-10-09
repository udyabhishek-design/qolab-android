package dev.drytis.qolab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v1.5.39 — Native Chat data layer (pure parsing, no Android imports;
 * unit-tested on the JVM).
 *
 * Contracts consumed (existing, unchanged — all verified live against the
 * Development backend on 2026-10-09):
 *
 *   GET /chat                    → Inertia HTML; props.channels.{company,
 *                                 department, group, dm}[] + props.employees[]
 *   GET /chat/channels/{id}      → Inertia HTML; props.channel{...members}
 *   GET /chat/channels/{id}/messages?per_page&page → {data[newest-first], meta}
 *   GET /chat/unread-counts      → {data:{channelId:count}}
 *
 * Shape rules mirrored from ChannelService::getUserChannels: DM name is the
 * partner's name, dm_partner carries the partner object, unread_count /
 * members_count / last_message_at are always present on channel items.
 * Rooms (is_room) are EXCLUDED by the backend for Chat by design — the
 * parser additionally skips any item that claims to be a room (defense in
 * depth: Social Rooms and Department Rooms never appear in Chat).
 *
 * NO last-message-body exists anywhere in the list payload — the list
 * preview line shows member count + relative activity time (the same data
 * the approved web ChatLayout shows), never a fabricated message.
 */
public final class ChatDataClient {

    private ChatDataClient() {}

    /** One conversation row. */
    public static final class Conversation {
        public final long id;
        public final String name;          // DM → partner name
        public final String type;          // dm | group | company | department
        public final String description;
        public final long membersCount;
        public final String lastMessageAt; // ISO or null
        public final int unreadCount;
        public final boolean dm;
        public final String partnerName;   // null for non-DM

        Conversation(long id, String name, String type, String description,
                     long membersCount, String lastMessageAt, int unreadCount,
                     boolean dm, String partnerName) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.description = description;
            this.membersCount = membersCount;
            this.lastMessageAt = lastMessageAt;
            this.unreadCount = unreadCount;
            this.dm = dm;
            this.partnerName = partnerName;
        }
    }

    /** One selectable employee (group member picker / new DM). */
    public static final class Employee {
        public final long id;
        public final String name;
        public final String jobTitle;
        public final String departmentName;

        Employee(long id, String name, String jobTitle, String departmentName) {
            this.id = id;
            this.name = name;
            this.jobTitle = jobTitle;
            this.departmentName = departmentName;
        }
    }

    /**
     * v1.5.40 — one published announcement (GET /announcements Inertia props,
     * existing endpoint; unauthenticated JSON requests → 401, authenticated →
     * the Inertia page JSON for XHR). Announcements are a SEPARATE module
     * from chat channels — the banner surfaces the latest one honestly and
     * never invents content.
     */
    public static final class Announcement {
        public final long id;
        public final String title;
        public final String priority;      // urgent|high|normal|low
        public final String publishedAtIso;
        public final String creatorName;

        Announcement(long id, String title, String priority,
                     String publishedAtIso, String creatorName) {
            this.id = id;
            this.title = title;
            this.priority = priority;
            this.publishedAtIso = publishedAtIso;
            this.creatorName = creatorName;
        }
    }

    /** Parse props.announcements.data[] (AnnouncementService::formatAnnouncement). */
    static List<Announcement> parseAnnouncements(JSONObject page) {
        List<Announcement> out = new ArrayList<>();
        if (page == null) return out;
        JSONObject props = page.optJSONObject("props");
        if (props == null) return out;
        JSONObject announcements = props.optJSONObject("announcements");
        if (announcements == null) return out;
        JSONArray data = announcements.optJSONArray("data");
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject a = data.optJSONObject(i);
            if (a == null) continue;
            JSONObject creator = a.optJSONObject("creator");
            out.add(new Announcement(
                    a.optLong("id", 0),
                    a.optString("title", null),
                    a.optString("priority", null),
                    a.optString("published_at", null),
                    creator != null ? creator.optString("name", null) : null));
        }
        return out;
    }

    /** Inertia props from GET /chat (or /chat/channels/{id}). */
    public static final class ChatIndex {
        public final List<Conversation> conversations; // sorted: most recent first
        public final List<Employee> employees;
        /** From the show page; null when parsing the index page. */
        public final Conversation detail;

        ChatIndex(List<Conversation> conversations, List<Employee> employees,
                  Conversation detail) {
            this.conversations = conversations;
            this.employees = employees;
            this.detail = detail;
        }
    }

    /** One chat message. */
    public static final class ChatMessage {
        public final long id;
        public final long channelId;
        public final long userId;
        public final String body;             // may be null (attachment-only)
        public final String senderName;       // user.name or null
        public final String createdAtIso;
        public final boolean edited;

        ChatMessage(long id, long channelId, long userId, String body,
                    String senderName, String createdAtIso, boolean edited) {
            this.id = id;
            this.channelId = channelId;
            this.userId = userId;
            this.body = body;
            this.senderName = senderName;
            this.createdAtIso = createdAtIso;
            this.edited = edited;
        }

        public static ChatMessage fromJson(JSONObject o) {
            if (o == null) return null;
            JSONObject user = o.optJSONObject("user");
            return new ChatMessage(
                    o.optLong("id", 0),
                    o.optLong("channel_id", 0),
                    o.optLong("user_id", 0),
                    optStringOrNull(o, "body"),
                    user != null ? optStringOrNull(user, "name") : null,
                    optStringOrNull(o, "created_at"),
                    !o.isNull("edited_at") && o.optString("edited_at", null) != null);
        }
    }

    /** GET /chat/channels/{id}/messages outcome. */
    public static final class MessagesPage {
        public final List<ChatMessage> messages; // chronological (oldest→newest)
        public final int currentPage;
        public final int lastPage;

        MessagesPage(List<ChatMessage> messages, int currentPage, int lastPage) {
            this.messages = messages;
            this.currentPage = currentPage;
            this.lastPage = lastPage;
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────

    /** Parse an Inertia page JSON (from extractInertiaPage) into a ChatIndex.
     *  detail is populated from props.channel when present. Null on failure. */
    public static ChatIndex parseInertiaIndex(JSONObject page) {
        if (page == null) return null;
        try {
            JSONObject props = page.optJSONObject("props");
            if (props == null) return null;
            JSONObject channels = props.optJSONObject("channels");
            if (channels == null) return null;

            List<Conversation> all = new ArrayList<>();
            String[] types = {"dm", "group", "company", "department"};
            for (String type : types) {
                JSONArray arr = channels.optJSONArray(type);
                if (arr == null) continue;
                for (int i = 0; i < arr.length(); i++) {
                    Conversation c = parseConversation(arr.optJSONObject(i), type);
                    if (c != null) all.add(c);
                }
            }
            sortRecentFirst(all);

            List<Employee> employees = new ArrayList<>();
            JSONArray emp = props.optJSONArray("employees");
            if (emp == null) emp = props.optJSONArray("available_users");
            if (emp != null) {
                for (int i = 0; i < emp.length(); i++) {
                    Employee e = parseEmployee(emp.optJSONObject(i));
                    if (e != null) employees.add(e);
                }
            }

            Conversation detail = null;
            JSONObject ch = props.optJSONObject("channel");
            if (ch != null) detail = parseDetail(ch);

            return new ChatIndex(all, employees, detail);
        } catch (Exception e) {
            return null;
        }
    }

    /** Channel item from getUserChannels (grouped lists). */
    static Conversation parseConversation(JSONObject c, String fallbackType) {
        if (c == null) return null;
        if (c.optBoolean("is_room", false)) return null; // rooms never in Chat
        String type = optStringOrNull(c, "type");
        if (type == null) type = fallbackType;
        boolean dm = "dm".equals(type);
        String name = optStringOrNull(c, "name");
        String partner = null;
        if (dm) {
            JSONObject p = c.optJSONObject("dm_partner");
            if (p != null) partner = optStringOrNull(p, "name");
            if (name == null && partner == null) return null; // broken DM
            if (name == null) name = partner;
        }
        if (c.optLong("id", 0) <= 0) return null;
        return new Conversation(
                c.optLong("id"),
                name,
                type,
                optStringOrNull(c, "description"),
                c.optLong("members_count", 0),
                optStringOrNull(c, "last_message_at"),
                c.optInt("unread_count", 0),
                dm,
                partner);
    }

    /** Channel detail object from the show page props. */
    static Conversation parseDetail(JSONObject c) {
        if (c == null) return null;
        boolean dm = "dm".equals(optStringOrNull(c, "type"));
        String partner = null;
        if (dm) {
            JSONObject p = c.optJSONObject("dm_partner");
            if (p != null) partner = optStringOrNull(p, "name");
        }
        if (c.optLong("id", 0) <= 0) return null;
        return new Conversation(
                c.optLong("id"),
                optStringOrNull(c, "name"),
                optStringOrNull(c, "type"),
                optStringOrNull(c, "description"),
                c.optLong("members_count", 0),
                null,
                c.optInt("unread_count", 0),
                dm,
                partner);
    }

    static Employee parseEmployee(JSONObject e) {
        if (e == null || e.optLong("id", 0) <= 0) return null;
        String dept = null;
        JSONObject d = e.optJSONObject("department");
        if (d != null) dept = optStringOrNull(d, "name");
        return new Employee(e.optLong("id"),
                optStringOrNull(e, "name"),
                optStringOrNull(e, "job_title"),
                dept);
    }

    /** Parse GET /chat/channels/{id}/messages body. Null when malformed. */
    public static MessagesPage parseMessages(String body) {
        if (body == null) return null;
        try {
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("data");
            if (arr == null) return null;
            List<ChatMessage> out = new ArrayList<>();
            for (int i = arr.length() - 1; i >= 0; i--) { // newest-first → chrono
                ChatMessage m = ChatMessage.fromJson(arr.optJSONObject(i));
                if (m != null && m.id > 0) out.add(m);
            }
            int current = 1, last = 1;
            JSONObject meta = root.optJSONObject("meta");
            if (meta != null) {
                current = meta.optInt("current_page", 1);
                last = meta.optInt("last_page", 1);
            }
            return new MessagesPage(out, current, last);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Parse a message.sent broadcast payload (same shape as a message row). */
    public static ChatMessage parseBroadcastMessage(String json) {
        try {
            return ChatMessage.fromJson(new JSONObject(json));
        } catch (Exception e) {
            return null;
        }
    }

    /** Parse GET /chat/unread-counts body → {channelId: count}. Null-safe. */
    public static Map<Long, Integer> parseUnreadCounts(String body) {
        Map<Long, Integer> out = new HashMap<>();
        if (body == null) return out;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return out;
            for (java.util.Iterator<String> it = data.keys(); it.hasNext(); ) {
                String k = it.next();
                try {
                    out.put(Long.parseLong(k), data.optInt(k, 0));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Extract a channel id from a final URL like
     *  https://host/chat/channels/51 — 0 when absent. */
    public static long channelIdFromUrl(String url) {
        if (url == null) return 0;
        int i = url.indexOf("/chat/channels/");
        if (i < 0) return 0;
        String tail = url.substring(i + "/chat/channels/".length());
        StringBuilder sb = new StringBuilder();
        for (char ch : tail.toCharArray()) {
            if (Character.isDigit(ch)) sb.append(ch);
            else break;
        }
        try {
            return sb.length() == 0 ? 0 : Long.parseLong(sb.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Sort: conversations with recent activity first, inactive after. */
    static void sortRecentFirst(List<Conversation> list) {
        java.util.Collections.sort(list, (a, b) -> {
            long ta = parseIsoMillis(a.lastMessageAt);
            long tb = parseIsoMillis(b.lastMessageAt);
            return Long.compare(tb, ta);
        });
    }

    // ── v1.5.40 mockup UI classification (pure, JVM-tested) ──────────

    /** UI filter chips. */
    public enum ChatFilter { ALL, DMS, GROUPS, ANNOUNCEMENTS }

    /**
     * Filter selection for a conversation. DMS: dm=true. GROUPS: type
     * group. ANNOUNCEMENTS: the company-wide official channel (type
     * company — the backend's seeded 'announcements' channel; no per-user
     * pin flag exists server-side). ALL: everything.
     */
    public static boolean matchesFilter(Conversation c, ChatFilter f) {
        if (c == null) return false;
        switch (f) {
            case DMS: return c.dm;
            case GROUPS: return "group".equals(c.type);
            case ANNOUNCEMENTS: return "company".equals(c.type);
            case ALL:
            default: return true;
        }
    }

    /**
     * "Pinned" section membership. The backend has NO per-user pin flag
     * (no pinned column on channels/channel_members — verified), so the
     * honest deterministic rule is: official company-wide channels
     * (type=company, is_official — 'general' / 'announcements') sit on
     * top as pinned. No invented data: non-official channels are Recent.
     */
    public static boolean isPinned(Conversation c) {
        return c != null && "company".equals(c.type);
    }

    /** Pastel card color for a conversation (stable by id, mockup palette). */
    public static int pastelIndexFor(long id, int paletteSize) {
        if (paletteSize <= 0) return 0;
        long v = ((id % paletteSize) + paletteSize) % paletteSize;
        return (int) v;
    }

    /** ISO-8601 (backend toISOString) → epoch millis; 0 when absent/bad. */
    public static long parseIsoMillis(String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        try {
            String s = iso.replace(' ', 'T');
            if (s.contains(".")) {
                int dot = s.indexOf('.');
                int end = dot + 1;
                while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
                s = s.substring(0, dot) + s.substring(end);
            }
            java.text.SimpleDateFormat in =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            if (s.endsWith("Z")) {
                in.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                s = s.substring(0, s.length() - 1);
            }
            java.util.Date d = in.parse(s);
            return d == null ? 0L : d.getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** null/absent/JSON-null/blank → null (never render "null"). */
    static String optStringOrNull(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String v = o.optString(key, null);
        return (v == null || v.isEmpty() || "null".equals(v)) ? null : v;
    }
}
