package dev.drytis.qolab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure parser for the Engineering Room (department room) native screen.
 *
 * Input sources — ALL existing backend contracts, nothing invented:
 *   1. GET /mobile/home            → HomeDataClient (already parsed) for
 *      department_room { id, name, description, room_slug, members_count,
 *      participants[] } — provides the room identity.
 *   2. GET /office/rooms/{slug}    → Inertia HTML whose data-page script JSON
 *      carries props: channel{name,description,room_slug,members[],members_count},
 *      meetings{live[],upcoming[],daily[],completed[],cancelled[],counts},
 *      meetingNotesArchive[{series_key,title,occurrences[]}].
 *   3. GET /meetings/{id}/detail   → full meeting JSON incl. transcript_text,
 *      transcript_segments[], historical_sessions[] (multi-session transcripts),
 *      tasks[] (TaskService::formatTask), task_summary, ai_metadata.summaries.
 *
 * This class contains NO network code — RoomActivity fetches via
 * QolabWebViewTransport and hands raw strings to these parsers.
 */
public final class RoomDataClient {

    private RoomDataClient() {}

    // ── Models ────────────────────────────────────────────────────────

    /** A meeting row as it appears in room lists / archive occurrences. */
    public static final class Meeting {
        public final long id;
        public final String title;
        public final String description;
        public final String status;          // scheduled | live | ended | cancelled
        public final String scheduledAtDisplay; // pre-formatted server string
        public final String timezoneAbbr;
        public final long durationSeconds;   // 0 when unknown
        public final boolean hasTranscript;
        public final int taskCount;
        public final String scheduleType;    // normal | daily | …

        Meeting(long id, String title, String description, String status,
                String scheduledAtDisplay, String timezoneAbbr,
                long durationSeconds, boolean hasTranscript, int taskCount,
                String scheduleType) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.status = status;
            this.scheduledAtDisplay = scheduledAtDisplay;
            this.timezoneAbbr = timezoneAbbr;
            this.durationSeconds = durationSeconds;
            this.hasTranscript = hasTranscript;
            this.taskCount = taskCount;
            this.scheduleType = scheduleType;
        }
    }

    /** A room member (from channel.members). */
    public static final class Member {
        public final long id;
        public final String name;
        public final String jobTitle;
        public final String departmentName;
        public final String role;            // channel_role (owner/member…)

        Member(long id, String name, String jobTitle, String departmentName, String role) {
            this.id = id;
            this.name = name;
            this.jobTitle = jobTitle;
            this.departmentName = departmentName;
            this.role = role;
        }
    }

    /** Aggregate parsed from the /office/rooms/{slug} page JSON. */
    public static final class RoomPage {
        public final long channelId;             // channel.id (v1.5.31: meeting create)
        public final String roomName;
        public final String roomDescription;
        public final List<Member> members;      // channel.members (ordered by name)
        public final List<Meeting> upcoming;    // includes daily
        public final List<Meeting> completed;
        public final List<Meeting> live;
        public final List<Meeting> cancelled;

        RoomPage(long channelId, String roomName, String roomDescription, List<Member> members,
                 List<Meeting> upcoming, List<Meeting> completed,
                 List<Meeting> live, List<Meeting> cancelled) {
            this.channelId = channelId;
            this.roomName = roomName;
            this.roomDescription = roomDescription;
            this.members = members;
            this.upcoming = upcoming;
            this.completed = completed;
            this.live = live;
            this.cancelled = cancelled;
        }
    }

    /** One transcript session of a meeting series (from historical_sessions). */
    public static final class TranscriptSession {
        public final long meetingId;
        public final String title;
        public final String scheduledAt;     // raw ISO for grouping labels
        public final String transcriptText;  // plain text, may be null/empty
        public final List<Segment> segments; // structured, may be empty

        TranscriptSession(long meetingId, String title, String scheduledAt,
                          String transcriptText, List<Segment> segments) {
            this.meetingId = meetingId;
            this.title = title;
            this.scheduledAt = scheduledAt;
            this.transcriptText = transcriptText;
            this.segments = segments;
        }
    }

    public static final class Segment {
        public final String speaker;
        public final String text;
        public final long timestamp; // seconds from meeting start

        Segment(String speaker, String text, long timestamp) {
            this.speaker = speaker;
            this.text = text;
            this.timestamp = timestamp;
        }
    }

    /** Action item / task shown on Meeting Details (formatTask shape). */
    public static final class MeetingTask {
        public final long id;
        public final String title;
        public final String status;
        public final String priority;
        public final String dueDate;             // yyyy-MM-dd or null
        public final String assigneeName;        // null when unassigned

        MeetingTask(long id, String title, String status, String priority,
                    String dueDate, String assigneeName) {
            this.id = id;
            this.title = title;
            this.status = status;
            this.priority = priority;
            this.dueDate = dueDate;
            this.assigneeName = assigneeName;
        }
    }

    /** Parsed /meetings/{id}/detail payload. */
    public static final class MeetingDetail {
        public final long id;
        public final String title;
        public final String description;
        public final String status;
        public final String scheduledAtDisplay;
        public final String timezoneAbbr;
        public final long durationSeconds;
        public final String transcriptText;
        public final List<Segment> segments;
        public final List<TranscriptSession> historicalSessions;
        public final List<MeetingTask> tasks;
        public final int taskTotal, taskCompleted, taskOverdue;
        public final String aiSummary;       // first summary's executive text, may be null
        public final List<String> keyDecisions;
        public final List<String> followUps;

        MeetingDetail(long id, String title, String description, String status,
                      String scheduledAtDisplay, String timezoneAbbr,
                      long durationSeconds, String transcriptText,
                      List<Segment> segments, List<TranscriptSession> historicalSessions,
                      List<MeetingTask> tasks, int taskTotal, int taskCompleted,
                      int taskOverdue, String aiSummary, List<String> keyDecisions,
                      List<String> followUps) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.status = status;
            this.scheduledAtDisplay = scheduledAtDisplay;
            this.timezoneAbbr = timezoneAbbr;
            this.durationSeconds = durationSeconds;
            this.transcriptText = transcriptText;
            this.segments = segments;
            this.historicalSessions = historicalSessions;
            this.tasks = tasks;
            this.taskTotal = taskTotal;
            this.taskCompleted = taskCompleted;
            this.taskOverdue = taskOverdue;
            this.aiSummary = aiSummary;
            this.keyDecisions = keyDecisions;
            this.followUps = followUps;
        }
    }

    // ── HTML data-page extraction ─────────────────────────────────────

    /**
     * Extracts the Inertia data-page JSON from the room page HTML.
     * The payload is a script element: {@code <script data-page type=
     * "application/json">{json}</script>} — raw json_encode, no entities.
     * Returns null when the marker is absent (login bounce, error page).
     */
    public static JSONObject extractInertiaPage(String html) {
        if (html == null) return null;
        String marker = "<script data-page";
        int start = html.indexOf(marker);
        if (start < 0) return null;
        int gt = html.indexOf('>', start);
        if (gt < 0) return null;
        int end = html.indexOf("</script>", gt);
        if (end < 0) return null;
        try {
            return new JSONObject(html.substring(gt + 1, end));
        } catch (Exception e) {
            return null;
        }
    }

    // ── Room page parsing ─────────────────────────────────────────────

    /** Parses props from the extracted Inertia page JSON. Null on failure. */
    public static RoomPage parseRoomPage(JSONObject page) {
        if (page == null) return null;
        try {
            JSONObject props = page.optJSONObject("props");
            if (props == null) return null;
            JSONObject channel = props.optJSONObject("channel");
            JSONObject meetings = props.optJSONObject("meetings");
            if (channel == null || meetings == null) return null;

            List<Member> members = new ArrayList<>();
            JSONArray memberArr = channel.optJSONArray("members");
            if (memberArr != null) {
                for (int i = 0; i < memberArr.length(); i++) {
                    JSONObject m = memberArr.optJSONObject(i);
                    if (m == null) continue;
                    JSONObject dept = m.optJSONObject("department");
                    members.add(new Member(
                            m.optLong("id", 0),
                            clean(m.optString("name", null)),
                            // v1.5.30: JSON null / literal "null" / blank must
                            // not surface in UI (was the "Member · null" bug)
                            clean(m.optString("job_title", null)),
                            dept != null ? clean(dept.optString("name", null)) : null,
                            clean(m.optString("channel_role", null))));
                }
            }

            return new RoomPage(
                    channel.optLong("id", 0),
                    channel.optString("name", null),
                    channel.optString("description", null),
                    members,
                    parseMeetingList(meetings.optJSONArray("upcoming")),
                    parseMeetingList(meetings.optJSONArray("completed")),
                    parseMeetingList(meetings.optJSONArray("live")),
                    parseMeetingList(meetings.optJSONArray("cancelled")));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Meeting> parseMeetingList(JSONArray arr) {
        List<Meeting> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            out.add(new Meeting(
                    m.optLong("id", 0),
                    m.optString("title", null),
                    m.optString("description", null),
                    m.optString("status", null),
                    m.optString("scheduled_at_display", null),
                    m.optString("timezone_abbr", null),
                    m.optLong("duration_seconds", 0),
                    m.optBoolean("has_transcript", false),
                    m.optInt("task_count", m.optInt("tasks_count", 0)),
                    m.optString("schedule_type", "normal")));
        }
        return out;
    }

    // ── Meeting detail parsing ────────────────────────────────────────

    /** Parses GET /meetings/{id}/detail. Null on failure. */
    public static MeetingDetail parseMeetingDetail(String body) {
        if (body == null) return null;
        try {
            return parseMeetingDetailObject(new JSONObject(body));
        } catch (Exception e) {
            return null;
        }
    }

    static MeetingDetail parseMeetingDetailObject(JSONObject d) {
        try {
            List<Segment> segments = parseSegments(d.optJSONArray("transcript_segments"));

            List<TranscriptSession> sessions = new ArrayList<>();
            JSONArray hs = d.optJSONArray("historical_sessions");
            if (hs != null) {
                for (int i = 0; i < hs.length(); i++) {
                    JSONObject s = hs.optJSONObject(i);
                    if (s == null) continue;
                    sessions.add(new TranscriptSession(
                            s.optLong("id", 0),
                            s.optString("title", null),
                            s.optString("scheduled_at", null),
                            s.optString("transcript_text", null),
                            parseSegments(s.optJSONArray("transcript_segments"))));
                }
            }

            List<MeetingTask> tasks = new ArrayList<>();
            JSONArray ta = d.optJSONArray("tasks");
            if (ta != null) {
                for (int i = 0; i < ta.length(); i++) {
                    JSONObject t = ta.optJSONObject(i);
                    if (t == null) continue;
                    JSONObject assignee = t.optJSONObject("assignee");
                    tasks.add(new MeetingTask(
                            t.optLong("id", 0),
                            t.optString("title", null),
                            t.optString("status", null),
                            t.optString("priority", null),
                            t.optString("due_date", null),
                            assignee != null ? assignee.optString("name", null) : null));
                }
            }

            // task_summary {total, completed, incomplete, overdue}
            JSONObject ts = d.optJSONObject("task_summary");
            int taskTotal = ts != null ? ts.optInt("total", 0) : tasks.size();
            int taskDone = ts != null ? ts.optInt("completed", 0) : 0;
            int taskOverdue = ts != null ? ts.optInt("overdue", 0) : 0;

            // ai_metadata.summaries[] → first executive summary + lists
            String aiSummary = null;
            List<String> decisions = new ArrayList<>();
            List<String> followUps = new ArrayList<>();
            JSONObject meta = d.optJSONObject("ai_metadata");
            JSONArray summaries = meta != null ? meta.optJSONArray("summaries") : null;
            if (summaries != null && summaries.length() > 0) {
                JSONObject s0 = summaries.optJSONObject(0);
                if (s0 != null) {
                    aiSummary = s0.optString("executive", null);
                    JSONArray kd = s0.optJSONArray("key_decisions");
                    if (kd != null) for (int i = 0; i < kd.length(); i++)
                        decisions.add(kd.optString(i, null));
                    JSONArray fu = s0.optJSONArray("follow_ups");
                    if (fu != null) for (int i = 0; i < fu.length(); i++)
                        followUps.add(fu.optString(i, null));
                }
            }

            return new MeetingDetail(
                    d.optLong("id", 0),
                    d.optString("title", null),
                    d.optString("description", null),
                    d.optString("status", null),
                    d.optString("scheduled_at_display", null),
                    d.optString("timezone_abbr", null),
                    d.optLong("duration_seconds", 0),
                    d.optString("transcript_text", null),
                    segments,
                    sessions,
                    tasks,
                    taskTotal, taskDone, taskOverdue,
                    aiSummary, decisions, followUps);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Segment> parseSegments(JSONArray arr) {
        List<Segment> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            out.add(new Segment(
                    s.optString("speakerName", null),
                    s.optString("text", null),
                    s.optLong("timestamp", 0)));
        }
        return out;
    }

    /** JSON-null-safe optString: null / literal "null" / blank → null. */
    private static String clean(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty() || "null".equals(t) || "undefined".equals(t)) return null;
        return v;
    }
}
