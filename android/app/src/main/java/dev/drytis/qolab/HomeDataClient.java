package dev.drytis.qolab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.5.15 (Architecture B): HomeDataClient is now the PURE MODEL + PARSING
 * layer for the native Android Home. All JSON parsing, model classes and
 * error classification tests are preserved unchanged.
 *
 * The authenticated transport is {@link QolabWebViewTransport}: the
 * application-scoped WebView that owns the Laravel session performs
 * GET /mobile/home inside the browser context and hands only the JSON
 * text to this parser. Java HttpURLConnection no longer carries the
 * session (physically disproven on device: WebView 200 / Java 401).
 *
 * Data source (unchanged): the Development-only aggregate endpoint
 *   GET {origin}/mobile/home     (session-authenticated, read-only)
 *
 * GET requests only — no CSRF-requiring mutations from Home.
 */
public final class HomeDataClient {

    private HomeDataClient() {} // static parse layer only

    /** Outcome of one aggregate fetch. */
    public static final class Result {
        public static final int STATUS_IO = -1;
        public final int status;          // HTTP status, or -1 for IO failure
        public final HomeData data;       // non-null only on 2xx
        public final String ioError;      // exception class name only
        public final boolean authLost;    // 401 — session no longer valid

        Result(int status, HomeData data, String ioError, boolean authLost) {
            this.status = status;
            this.data = data;
            this.ioError = ioError;
            this.authLost = authLost;
        }
    }

    public static final class Participant {
        public final long id;
        public final String name;
        public final String avatarPath;

        Participant(long id, String name, String avatarPath) {
            this.id = id;
            this.name = name;
            this.avatarPath = avatarPath;
        }
    }

    public static final class Meeting {
        public final long id;
        public final String title;
        public final String timeDisplay;   // e.g. "10:30 AM"
        public final String dateDisplay;   // e.g. "Oct 1, 2026"
        public final String timezone;
        public final String status;
        public final int participantsCount;
        public final List<Participant> participants;

        Meeting(long id, String title, String timeDisplay, String dateDisplay,
                String timezone, String status, int participantsCount,
                List<Participant> participants) {
            this.id = id;
            this.title = title;
            this.timeDisplay = timeDisplay;
            this.dateDisplay = dateDisplay;
            this.timezone = timezone;
            this.status = status;
            this.participantsCount = participantsCount;
            this.participants = participants;
        }
    }

    public static final class Room {
        public final long id;
        public final String name;
        public final String description;
        public final String roomSlug;
        public final String roomType;     // social | casual | department
        public final String roomTheme;    // lobby | music | coffee | null
        public final int membersCount;
        public final List<Participant> participants;

        Room(long id, String name, String description, String roomSlug,
             String roomType, String roomTheme, int membersCount,
             List<Participant> participants) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.roomSlug = roomSlug;
            this.roomType = roomType;
            this.roomTheme = roomTheme;
            this.membersCount = membersCount;
            this.participants = participants;
        }
    }

    public static final class Conversation {
        public final long id;
        public final String name;
        public final String type;
        public final String dmPartnerName;
        public final String lastMessageAt;
        public final int unreadCount;

        Conversation(long id, String name, String type, String dmPartnerName,
                     String lastMessageAt, int unreadCount) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.dmPartnerName = dmPartnerName;
            this.lastMessageAt = lastMessageAt;
            this.unreadCount = unreadCount;
        }
    }

    public static final class Task {
        public final long id;
        public final String title;
        public final String dueDate;   // yyyy-MM-dd or null
        public final String status;

        Task(long id, String title, String dueDate, String status) {
            this.id = id;
            this.title = title;
            this.dueDate = dueDate;
            this.status = status;
        }
    }

    public static final class TodayCounts {
        public final int tasksDueToday;
        public final int tasksOverdue;
        public final int tasksPending;
        public final int meetingsToday;
        public final int unreadNotifications;

        TodayCounts(int tasksDueToday, int tasksOverdue, int tasksPending,
                    int meetingsToday, int unreadNotifications) {
            this.tasksDueToday = tasksDueToday;
            this.tasksOverdue = tasksOverdue;
            this.tasksPending = tasksPending;
            this.meetingsToday = meetingsToday;
            this.unreadNotifications = unreadNotifications;
        }
    }

    public static final class HomeData {
        public final String userName;
        public final String userAvatarPath;
        public final String systemRole;
        public final String departmentName;
        public final Meeting nextMeeting;            // null when none
        public final List<Room> socialRooms;
        public final Room departmentRoom;            // null when user has no department
        public final Conversation recentConversation; // null when no activity
        public final Task priorityTask;              // null when none
        public final TodayCounts today;

        HomeData(String userName, String userAvatarPath, String systemRole,
                 String departmentName, Meeting nextMeeting, List<Room> socialRooms,
                 Room departmentRoom, Conversation recentConversation,
                 Task priorityTask, TodayCounts today) {
            this.userName = userName;
            this.userAvatarPath = userAvatarPath;
            this.systemRole = systemRole;
            this.departmentName = departmentName;
            this.nextMeeting = nextMeeting;
            this.socialRooms = socialRooms;
            this.departmentRoom = departmentRoom;
            this.recentConversation = recentConversation;
            this.priorityTask = priorityTask;
            this.today = today;
        }
    }

    // ------------------------------------------------------------------
    // Parsing — pure, unit-tested
    // ------------------------------------------------------------------

    /** Parse the /mobile/home JSON payload. Never throws; missing/invalid
     *  sections degrade to nulls/empty lists. */
    public static HomeData parse(String json) {
        try {
            JSONObject root = new JSONObject(json == null ? "{}" : json);

            JSONObject user = root.optJSONObject("user");
            String userName = user != null ? user.optString("name", null) : null;
            String avatar = user != null ? user.optString("avatar_path", null) : null;
            String role = user != null ? user.optString("system_role", null) : null;
            String dept = user != null ? optStringOrNull(user, "department_name") : null;

            Meeting meeting = parseMeeting(root.optJSONObject("next_meeting"));

            List<Room> rooms = new ArrayList<>();
            JSONArray roomsArr = root.optJSONArray("social_rooms");
            if (roomsArr != null) {
                for (int i = 0; i < roomsArr.length(); i++) {
                    Room r = parseRoom(roomsArr.optJSONObject(i));
                    if (r != null) rooms.add(r);
                }
            }

            Room deptRoom = parseRoom(root.optJSONObject("department_room"));

            Conversation conv = parseConversation(root.optJSONObject("recent_conversation"));

            Task task = null;
            JSONObject taskObj = root.optJSONObject("priority_task");
            if (taskObj != null) {
                task = new Task(taskObj.optLong("id", 0),
                        taskObj.optString("title", null),
                        optStringOrNull(taskObj, "due_date"),
                        taskObj.optString("status", null));
            }

            TodayCounts today = new TodayCounts(0, 0, 0, 0, 0);
            JSONObject todayObj = root.optJSONObject("today");
            if (todayObj != null) {
                today = new TodayCounts(
                        todayObj.optInt("tasks_due_today", 0),
                        todayObj.optInt("tasks_overdue", 0),
                        todayObj.optInt("tasks_pending", 0),
                        todayObj.optInt("meetings_today", 0),
                        todayObj.optInt("unread_notifications", 0));
            }

            return new HomeData(userName, avatar, role, dept, meeting, rooms,
                    deptRoom, conv, task, today);
        } catch (Exception e) {
            // v1.5.36: a body we cannot parse (HTML error page, truncated
            // payload, non-JSON) is a FAILURE — returning fabricated empty
            // data here made Office render "No social rooms yet" for a 404,
            // hiding a real outage as a valid empty account. Parse failure
            // is reported as null; the caller shows its error+retry state.
            // Valid JSON with missing sections still degrades gracefully
            // inside the try block above (that is real data, not failure).
            return null;
        }
    }

    private static Meeting parseMeeting(JSONObject m) {
        if (m == null) return null;
        return new Meeting(
                m.optLong("id", 0),
                m.optString("title", null),
                optStringOrNull(m, "scheduled_at_display"),
                optStringOrNull(m, "scheduled_date_display"),
                optStringOrNull(m, "timezone"),
                m.optString("status", null),
                m.optInt("participants_count", 0),
                parseParticipants(m.optJSONArray("participants")));
    }

    private static Room parseRoom(JSONObject r) {
        if (r == null) return null;
        return new Room(
                r.optLong("id", 0),
                r.optString("name", null),
                optStringOrNull(r, "description"),
                optStringOrNull(r, "room_slug"),
                optStringOrNull(r, "room_type"),
                optStringOrNull(r, "room_theme"),
                r.optInt("members_count", 0),
                parseParticipants(r.optJSONArray("participants")));
    }

    private static Conversation parseConversation(JSONObject c) {
        if (c == null) return null;
        String dmPartner = null;
        JSONObject partner = c.optJSONObject("dm_partner");
        if (partner != null) dmPartner = partner.optString("name", null);
        if (dmPartner == null) dmPartner = optStringOrNull(c, "dm_partner");
        return new Conversation(
                c.optLong("id", 0),
                c.optString("name", null),
                optStringOrNull(c, "type"),
                dmPartner,
                optStringOrNull(c, "last_message_at"),
                c.optInt("unread_count", 0));
    }

    private static List<Participant> parseParticipants(JSONArray arr) {
        List<Participant> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            out.add(new Participant(p.optLong("id", 0),
                    p.optString("name", null),
                    optStringOrNull(p, "avatar_path")));
        }
        return out;
    }

    private static String optStringOrNull(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String v = o.optString(key, null);
        return (v == null || v.isEmpty() || "null".equals(v)) ? null : v;
    }

}
