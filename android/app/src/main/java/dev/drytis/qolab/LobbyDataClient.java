package dev.drytis.qolab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse-only layer for The Lobby (native social room, v1.5.30).
 * NO network code — LobbyActivity fetches via QolabWebViewTransport and
 * hands raw JSON bodies here.
 *
 * Existing contracts consumed (unchanged):
 *   GET /chat/channels/{id}/messages  → {data:[message…], meta:{total…}}
 *        message: id, body, parent_message_id, parent_message{id,body,user_name},
 *                 user{name,job_title}, created_at, replies[message…]
 *        (top-level only, newest-first; client renders chronological)
 *   GET /office/rooms/{slug} Inertia props → channel.members[] (name,
 *        channel_role, job_title), members_count — same source as RoomActivity.
 *
 * No fabricated values; missing fields stay null and are omitted at render.
 */
public final class LobbyDataClient {

    private LobbyDataClient() {}

    public static final class LobbyMessage {
        public final long id;
        public final String body;            // may be null (attachment-only)
        public final String senderName;
        public final String createdAtIso;    // raw; formatted via RoomFormat
        public final long parentMessageId;   // 0 when top-level
        public final String parentQuote;     // "Alice: text…" or null
        public final List<LobbyMessage> replies = new ArrayList<>();

        LobbyMessage(long id, String body, String senderName, String createdAtIso,
                     long parentMessageId, String parentQuote) {
            this.id = id;
            this.body = body;
            this.senderName = senderName;
            this.createdAtIso = createdAtIso;
            this.parentMessageId = parentMessageId;
            this.parentQuote = parentQuote;
        }
    }

    /**
     * Parse GET /chat/channels/{id}/messages body. Returns messages in
     * chronological order (oldest → newest) for natural mobile reading;
     * null when the payload is not the expected shape.
     */
    public static List<LobbyMessage> parseMessages(String body) {
        if (body == null) return null;
        try {
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("data");
            if (arr == null) return null;
            List<LobbyMessage> out = new ArrayList<>();
            for (int i = arr.length() - 1; i >= 0; i--) { // newest-first → chrono
                LobbyMessage m = parseMessage(arr.optJSONObject(i));
                if (m != null) out.add(m);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    static LobbyMessage parseMessage(JSONObject o) {
        if (o == null) return null;
        JSONObject user = o.optJSONObject("user");
        String sender = user != null ? optStringOrNull(user, "name") : null;

        long parentId = o.optLong("parent_message_id", 0);
        String quote = null;
        JSONObject parent = o.optJSONObject("parent_message");
        if (parent != null) {
            String pn = optStringOrNull(parent, "user_name");
            String pb = optStringOrNull(parent, "body");
            if (pb != null) {
                int max = Math.min(pb.length(), 60);
                String clipped = pb.substring(0, max) + (pb.length() > max ? "…" : "");
                quote = pn != null ? pn + ": " + clipped : clipped;
            } else if (pn != null) {
                quote = pn;
            }
        }

        LobbyMessage m = new LobbyMessage(
                o.optLong("id", 0),
                optStringOrNull(o, "body"),
                sender,
                optStringOrNull(o, "created_at"),
                parentId,
                quote);
        JSONArray replies = o.optJSONArray("replies");
        if (replies != null) {
            for (int i = 0; i < replies.length(); i++) {
                LobbyMessage r = parseMessage(replies.optJSONObject(i));
                if (r != null) m.replies.add(r);
            }
        }
        return m;
    }

    /** null/absent/JSON-null → null (callers omit; never render "null"). */
    static String optStringOrNull(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String v = o.optString(key, null);
        if (v == null || v.trim().isEmpty() || "null".equals(v.trim())) return null;
        return v;
    }
}
