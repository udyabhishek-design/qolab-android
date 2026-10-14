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
        /** v1.5.45: mutable — patched in place by the non-blocking unread
         *  callback (no full re-render, no new HomeData allocation). */
        public int unreadNotifications;

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
        public final long userId;
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

        HomeData(long userId, String userName, String userAvatarPath, String systemRole,
                 String departmentName, Meeting nextMeeting, List<Room> socialRooms,
                 Room departmentRoom, Conversation recentConversation,
                 Task priorityTask, TodayCounts today) {
            this.userId = userId;
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
            long userId = user != null ? user.optLong("id", 0) : 0L;
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

            return new HomeData(userId, userName, avatar, role, dept, meeting, rooms,
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

    // ------------------------------------------------------------------
    // v1.5.43: Inertia-page composition (replaces the retired dev-only
    // GET /mobile/home aggregate, which the backend no longer serves —
    // 404 NotFoundHttpException even with a fully valid session).
    //
    // Home is now composed from TWO existing Inertia pages plus the
    // existing unread-count JSON endpoint — no backend changes:
    //   GET /dashboard        → auth.user, today_meetings,
    //                           dashboard_metrics, admin_summary
    //   GET /rooms            → channels.group[]  (social rooms)
    //                           channels.department[] (department rooms)
    //   GET /notifications/unread-count → unread_notifications
    //
    // The parse functions are PURE and JVM-testable; the fetch step lives
    // in fetchComposite() and drives the existing WebView transport.
    // ------------------------------------------------------------------

    /**
     * Parse the /dashboard Inertia page (HTML) into the user / meeting /
     * today-counts part of HomeData. Returns null when the page has no
     * Inertia payload or no auth.user — a 401/redirect body never
     * masquerades as empty data.
     */
    public static PartialDashboard parseDashboardPage(String html) {
        try {
            JSONObject page = RoomDataClient.extractInertiaPage(html);
            if (page == null) return null;
            JSONObject props = page.optJSONObject("props");
            if (props == null) return null;
            JSONObject user = props.optJSONObject("auth") != null
                    ? props.optJSONObject("auth").optJSONObject("user") : null;
            if (user == null) return null;

            long userId = user.optLong("id", 0);
            String userName = optStringOrNull(user, "name");
            String avatar = optStringOrNull(user, "avatar_path");
            String role = optStringOrNull(user, "system_role");
            String deptName = optStringOrNull(user, "department_name");
            Long deptId = user.isNull("department_id") || !user.has("department_id")
                    ? null : user.optLong("department_id");

            // Next meeting: first of today_meetings that is not cancelled,
            // else first upcoming_meeting.
            Meeting meeting = null;
            JSONArray today = props.optJSONArray("today_meetings");
            if (today != null) {
                for (int i = 0; i < today.length(); i++) {
                    JSONObject m = today.optJSONObject(i);
                    if (m == null) continue;
                    if ("cancelled".equals(optStringOrNull(m, "status"))) continue;
                    meeting = parseMeeting(m);
                    break;
                }
            }
            if (meeting == null) {
                JSONArray up = props.optJSONArray("upcoming_meetings");
                if (up != null && up.length() > 0) {
                    meeting = parseMeeting(up.optJSONObject(0));
                }
            }

            int meetingsToday = 0;
            JSONObject metrics = props.optJSONObject("dashboard_metrics");
            if (metrics != null) meetingsToday = metrics.optInt("meetings_today", 0);
            int overdue = 0;
            JSONObject admin = props.optJSONObject("admin_summary");
            if (admin != null) overdue = admin.optInt("overdue_tasks", 0);

            return new PartialDashboard(userId, userName, avatar, role, deptName,
                    deptId, meeting, meetingsToday, overdue);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse the /rooms Inertia page (HTML) into social rooms + the user's
     * department room. Returns null when there is no Inertia payload (401
     * / error page). Missing sections degrade to empty list / null dept —
     * that is real data (valid session, no rooms), not failure.
     */
    public static PartialRooms parseRoomsPage(String html, Long userDepartmentId) {
        try {
            JSONObject page = RoomDataClient.extractInertiaPage(html);
            if (page == null) return null;
            JSONObject props = page.optJSONObject("props");
            if (props == null) return null;
            JSONObject channels = props.optJSONObject("channels");
            if (channels == null) return null;

            // Social rooms: entries in the "group" bucket that are rooms
            // (is_room) with a social/casual room_type. Non-room groups
            // (chat groups) are excluded — rooms never appear in Chat.
            List<Room> social = new ArrayList<>();
            JSONArray group = channels.optJSONArray("group");
            if (group != null) {
                for (int i = 0; i < group.length(); i++) {
                    JSONObject c = group.optJSONObject(i);
                    if (c == null) continue;
                    if (!c.optBoolean("is_room", false)) continue;
                    String type = optStringOrNull(c, "room_type");
                    if (!"social".equals(type) && !"casual".equals(type)) continue;
                    social.add(parseRoomFromChannel(c));
                }
            }

            // Department room: the channel whose department_id matches the
            // signed-in user's department (rooms list carries all
            // departments' rooms; matching picks the user's own).
            Room dept = null;
            if (userDepartmentId != null) {
                JSONArray depts = channels.optJSONArray("department");
                if (depts != null) {
                    for (int i = 0; i < depts.length(); i++) {
                        JSONObject c = depts.optJSONObject(i);
                        if (c == null) continue;
                        if (!c.optBoolean("is_room", false)) continue;
                        if (c.isNull("department_id")) continue;
                        if (c.optLong("department_id", -1) != userDepartmentId) continue;
                        dept = parseRoomFromChannel(c);
                        break;
                    }
                }
            }

            return new PartialRooms(social, dept);
        } catch (Exception e) {
            return null;
        }
    }

    /** Channel JSON (from /rooms) → Room model. participants absent in
     *  this payload — empty list (avatar row degrades honestly). */
    private static Room parseRoomFromChannel(JSONObject c) {
        return new Room(
                c.optLong("id", 0),
                optStringOrNull(c, "name"),
                optStringOrNull(c, "description"),
                optStringOrNull(c, "room_slug"),
                optStringOrNull(c, "room_type"),
                optStringOrNull(c, "room_theme"),
                c.optInt("members_count", 0),
                new ArrayList<>());
    }

    /** Fields parseDashboardPage extracts from /dashboard. */
    public static final class PartialDashboard {
        public final long userId;
        public final String userName;
        public final String userAvatarPath;
        public final String systemRole;
        public final String departmentName;
        public final Long userDepartmentId; // null when the user has none
        public final Meeting nextMeeting;   // null when none
        public final int meetingsToday;
        public final int tasksOverdue;

        PartialDashboard(long userId, String userName, String avatar, String role,
                         String deptName, Long deptId, Meeting nextMeeting,
                         int meetingsToday, int tasksOverdue) {
            this.userId = userId;
            this.userName = userName;
            this.userAvatarPath = avatar;
            this.systemRole = role;
            this.departmentName = deptName;
            this.userDepartmentId = deptId;
            this.nextMeeting = nextMeeting;
            this.meetingsToday = meetingsToday;
            this.tasksOverdue = tasksOverdue;
        }
    }

    /** Fields parseRoomsPage extracts from /rooms. */
    public static final class PartialRooms {
        public final List<Room> socialRooms;
        public final Room departmentRoom; // null when user has no department

        PartialRooms(List<Room> socialRooms, Room departmentRoom) {
            this.socialRooms = socialRooms;
            this.departmentRoom = departmentRoom;
        }
    }

    /** Compose the three responses into the HomeData model the native
     *  Home/Office screens already render. Null when the composition is
     *  not possible (dashboard or rooms page failed to yield props). */
    public static HomeData composeHomeData(PartialDashboard dash, PartialRooms rooms,
                                           int unreadNotifications) {
        if (dash == null || rooms == null) return null;
        TodayCounts today = new TodayCounts(0, dash.tasksOverdue, 0,
                dash.meetingsToday, unreadNotifications);
        return new HomeData(dash.userId, dash.userName, dash.userAvatarPath,
                dash.systemRole, dash.departmentName, dash.nextMeeting,
                rooms.socialRooms, rooms.departmentRoom,
                null, null, today);
    }

}
