package dev.drytis.qolab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.5.49 — pure, JVM-testable parser for the native Meetings list
 * (GET /meetings, Inertia component Meetings/Index).
 *
 * Live-verified Development payload shape (read-only probe):
 *   props: { auth, company, flash, upcoming:{data[],meta}, past:{...}, all:{...} }
 *   row:   id, title, description, meeting_type, schedule_type, status,
 *          scheduled_at_display, timezone_abbr, duration_seconds,
 *          has_transcript, participants[], creator{}, department{}|null,
 *          is_creator, is_participant, meeting_source, ...
 *
 * No backend changes — the response is parsed exactly as the web page
 * receives it.
 */
public final class MeetingsDataClient {

    private MeetingsDataClient() { }

    public static final class MeetingRow {
        public final long id;
        public final String title;
        public final String description;
        public final String status;            // scheduled | live | ended | cancelled
        public final String scheduledAtDisplay; // pre-formatted server string
        public final String timezoneAbbr;
        public final long durationSeconds;
        public final boolean hasTranscript;
        public final String meetingType;       // group | department
        public final String meetingSource;     // social_room | department_room | …
        public final int participantsCount;
        public final boolean isParticipant;

        MeetingRow(long id, String title, String description, String status,
                   String scheduledAtDisplay, String timezoneAbbr, long durationSeconds,
                   boolean hasTranscript, String meetingType, String meetingSource,
                   int participantsCount, boolean isParticipant) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.status = status;
            this.scheduledAtDisplay = scheduledAtDisplay;
            this.timezoneAbbr = timezoneAbbr;
            this.durationSeconds = durationSeconds;
            this.hasTranscript = hasTranscript;
            this.meetingType = meetingType;
            this.meetingSource = meetingSource;
            this.participantsCount = participantsCount;
            this.isParticipant = isParticipant;
        }
    }

    public static final class MeetingsPage {
        public final List<MeetingRow> upcoming;
        public final List<MeetingRow> past;
        public final int totalUpcoming;
        public final int totalPast;

        MeetingsPage(List<MeetingRow> upcoming, List<MeetingRow> past,
                     int totalUpcoming, int totalPast) {
            this.upcoming = upcoming;
            this.past = past;
            this.totalUpcoming = totalUpcoming;
            this.totalPast = totalPast;
        }
    }

    /**
     * Parses the Inertia page JSON (already extracted via
     * {@link RoomDataClient#extractInertiaPage}). Null when the payload
     * does not contain the expected meetings collections.
     */
    public static MeetingsPage parse(JSONObject page) {
        if (page == null) return null;
        JSONObject props = page.optJSONObject("props");
        if (props == null) return null;
        JSONObject up = props.optJSONObject("upcoming");
        JSONObject past = props.optJSONObject("past");
        if (up == null && past == null) return null;
        List<MeetingRow> upRows = collection(up);
        List<MeetingRow> pastRows = collection(past);
        int totalUp = up != null ? up.optJSONObject("meta") != null
                ? up.optJSONObject("meta").optInt("total", upRows.size()) : upRows.size() : 0;
        int totalPast = past != null ? past.optJSONObject("meta") != null
                ? past.optJSONObject("meta").optInt("total", pastRows.size()) : pastRows.size() : 0;
        return new MeetingsPage(upRows, pastRows, totalUp, totalPast);
    }

    private static List<MeetingRow> collection(JSONObject coll) {
        List<MeetingRow> rows = new ArrayList<>();
        if (coll == null) return rows;
        JSONArray data = coll.optJSONArray("data");
        if (data == null) return rows;
        for (int i = 0; i < data.length(); i++) {
            JSONObject m = data.optJSONObject(i);
            if (m == null) continue;
            MeetingRow row = row(m);
            if (row != null) rows.add(row);
        }
        return rows;
    }

    /** One meeting row; null when the row lacks an id (unusable). */
    public static MeetingRow row(JSONObject m) {
        if (m == null || !m.has("id")) return null;
        JSONArray participants = m.optJSONArray("participants");
        return new MeetingRow(
                m.optLong("id", 0),
                m.optString("title", ""),
                m.optString("description", ""),
                m.optString("status", ""),
                m.optString("scheduled_at_display", ""),
                m.optString("timezone_abbr", ""),
                m.optLong("duration_seconds", 0),
                m.optBoolean("has_transcript", false),
                m.optString("meeting_type", ""),
                m.optString("meeting_source", ""),
                participants != null ? participants.length() : 0,
                m.optBoolean("is_participant", false));
    }

    /** True when the row can be opened in MeetingDetailActivity. */
    public static boolean openable(MeetingRow r) {
        return r != null && r.id > 0;
    }
}
