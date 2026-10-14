package dev.drytis.qolab;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * v1.5.43 regression tests for the composite Home data source.
 *
 * Root cause being pinned: GET /mobile/home was removed from the backend
 * (404 NotFoundHttpException with a fully valid session) — Home showed
 * permanent "Couldn't load" for Next Meeting / Social Rooms / Your
 * Department. Home now composes from /dashboard + /rooms Inertia pages
 * (+ /notifications/unread-count). These tests pin the parsing rules
 * against fixtures matching the LIVE payload shapes captured from the
 * dev server (2026-10-14).
 */
public class HomeCompositeFetchTest {

    // ---- fixture helpers ------------------------------------------------

    private static String inertia(String propsJson) {
        return "<!DOCTYPE html><html><head></head><body>"
                + "<div id=\"app\" data-page=\"app\"></div>"
                + "<script data-page type=\"application/json\">"
                + "{\"component\":\"Dashboard/Index\",\"url\":\"https://x/dashboard\","
                + "\"props\":" + propsJson + ",\"version\":\"1\"}"
                + "</script></body></html>";
    }

    private static String dashboardProps(String userJson, String todayMeetings,
                                         String upcoming, String metrics,
                                         String admin) {
        return "{\"auth\":{\"user\":" + userJson + "},"
                + "\"today_meetings\":" + todayMeetings + ","
                + "\"upcoming_meetings\":" + upcoming + ","
                + "\"dashboard_metrics\":" + metrics + ","
                + "\"admin_summary\":" + admin + "}";
    }

    private static final String USER_QA =
            "{\"id\":5,\"name\":\"QA Admin (Dev)\",\"system_role\":\"admin\","
            + "\"department_id\":null,\"department_name\":null,\"avatar_path\":null}";

    private static final String USER_ENG =
            "{\"id\":12,\"name\":\"Eng Member\",\"system_role\":\"member\","
            + "\"department_id\":7,\"department_name\":\"Engineering\","
            + "\"avatar_path\":\"/storage/a.png\"}";

    private static final String MEETING_21 =
            "{\"id\":21,\"title\":\"Me and you second test\",\"status\":\"scheduled\","
            + "\"scheduled_at_display\":\"8:58 AM\",\"scheduled_date_display\":\"Oct 14, 2026\","
            + "\"timezone\":\"UTC\",\"participants_count\":2}";

    // ---- /dashboard parsing ---------------------------------------------

    @Test
    public void dashboardParsesUserMeetingAndCounts() {
        String html = inertia(dashboardProps(USER_QA,
                "[" + MEETING_21 + "]", "[]",
                "{\"active_members\":23,\"online_now\":0,\"in_rooms\":0,\"meetings_today\":2}",
                "{\"pending_leave\":0,\"pending_corrections\":0,\"overdue_tasks\":10}"));
        HomeDataClient.PartialDashboard d = HomeDataClient.parseDashboardPage(html);
        assertNotNull(d);
        assertEquals(5, d.userId);
        assertEquals("QA Admin (Dev)", d.userName);
        assertEquals("admin", d.systemRole);
        assertNull("user without department", d.userDepartmentId);
        assertNull(d.departmentName);
        assertNotNull("today meeting must surface as next meeting", d.nextMeeting);
        assertEquals(21, d.nextMeeting.id);
        assertEquals("Me and you second test", d.nextMeeting.title);
        assertEquals("8:58 AM", d.nextMeeting.timeDisplay);
        assertEquals("Oct 14, 2026", d.nextMeeting.dateDisplay);
        assertEquals("UTC", d.nextMeeting.timezone);
        assertEquals(2, d.nextMeeting.participantsCount);
        assertEquals(2, d.meetingsToday);
        assertEquals(10, d.tasksOverdue);
    }

    @Test
    public void dashboardUserDepartmentIdExtracted() {
        String html = inertia(dashboardProps(USER_ENG, "[]", "[]",
                "{\"meetings_today\":0}", "{\"overdue_tasks\":0}"));
        HomeDataClient.PartialDashboard d = HomeDataClient.parseDashboardPage(html);
        assertNotNull(d);
        assertEquals(Long.valueOf(7L), d.userDepartmentId);
        assertEquals("Engineering", d.departmentName);
    }

    @Test
    public void cancelledTodayMeetingsSkipped_upcomingUsedInstead() {
        String cancelled = "{\"id\":30,\"title\":\"Cancelled\",\"status\":\"cancelled\"}";
        String upcoming = "{\"id\":31,\"title\":\"Upcoming one\",\"status\":\"scheduled\","
                + "\"scheduled_at_display\":\"3:00 PM\",\"participants_count\":1}";
        String html = inertia(dashboardProps(USER_QA,
                "[" + cancelled + "]", "[" + upcoming + "]",
                "{\"meetings_today\":1}", "{}"));
        HomeDataClient.PartialDashboard d = HomeDataClient.parseDashboardPage(html);
        assertNotNull(d);
        assertEquals(31, d.nextMeeting.id);
        assertEquals("Upcoming one", d.nextMeeting.title);
    }

    @Test
    public void noMeetingsYieldsNullNextMeeting_notError() {
        String html = inertia(dashboardProps(USER_QA, "[]", "[]",
                "{\"meetings_today\":0}", "{}"));
        HomeDataClient.PartialDashboard d = HomeDataClient.parseDashboardPage(html);
        assertNotNull("empty meetings is VALID data (empty state), not failure",
                d);
        assertNull(d.nextMeeting);
    }

    @Test
    public void dashboardWithoutAuthUserIsFailure() {
        // A 401/redirect body has no auth.user → null (error state, never
        // fabricated empty data).
        String html = inertia("{\"today_meetings\":[]}");
        assertNull(HomeDataClient.parseDashboardPage(html));
    }

    @Test
    public void nonInertiaBodyIsFailure() {
        assertNull(HomeDataClient.parseDashboardPage(
                "<html><body>Whoops, looks like something went wrong.</body></html>"));
        assertNull(HomeDataClient.parseDashboardPage(null));
    }

    // ---- /rooms parsing --------------------------------------------------

    private static String channel(long id, String name, String roomType,
                                  String slug, String theme, int members,
                                  Long deptId, boolean isRoom) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"is_room\":" + isRoom
                + ",\"room_type\":" + (roomType == null ? "null" : "\"" + roomType + "\"")
                + ",\"room_slug\":" + (slug == null ? "null" : "\"" + slug + "\"")
                + ",\"room_theme\":" + (theme == null ? "null" : "\"" + theme + "\"")
                + ",\"members_count\":" + members
                + ",\"department_id\":" + (deptId == null ? "null" : deptId)
                + ",\"unread_count\":0,\"is_member\":true}";
    }

    private static String roomsProps(String groupArr, String deptArr) {
        return "{\"auth\":{\"user\":" + USER_ENG + "},"
                + "\"channels\":{\"company\":[],\"department\":" + deptArr
                + ",\"group\":" + groupArr + ",\"dm\":[]}}";
    }

    // Live shape (2026-10-14): group[] holds 3 social rooms + non-room groups.
    private static final String LIVE_GROUP =
            "[" + channel(48, "The Lobby", "social", "the-lobby", "lobby", 4, null, true)
            + "," + channel(51, "QA Native Group Test 2", null, null, null, 2, null, false)
            + "," + channel(7, "Music Lounge", "casual", "music-lounge", "music", 8, null, true)
            + "," + channel(47, "Coffee Lounge", "social", "coffee-lounge", "coffee", 3, null, true)
            + "," + channel(53, "My test grp", null, null, null, 5, null, false)
            + "]";

    private static final String LIVE_DEPT =
            "[" + channel(9, "Engineering Room", "department", "engineering-room", null, 5, 7L, true)
            + "," + channel(8, "Legal Room", "department", "legal-room", null, 1, 8L, true)
            + "," + channel(6, "Operations Room", "department", "operations-room", null, 3, 6L, true)
            + "]";

    @Test
    public void roomsParsesThreeSocialRooms_excludesNonRoomGroups() {
        String html = inertia(roomsProps(LIVE_GROUP, LIVE_DEPT));
        HomeDataClient.PartialRooms pr =
                HomeDataClient.parseRoomsPage(html, 7L);
        assertNotNull(pr);
        assertEquals("exactly the 3 approved social rooms", 3, pr.socialRooms.size());
        List<String> names = new ArrayList<>();
        for (HomeDataClient.Room r : pr.socialRooms) names.add(r.name);
        assertTrue(names.contains("The Lobby"));
        assertTrue(names.contains("Music Lounge"));
        assertTrue(names.contains("Coffee Lounge"));
        // non-room chat groups must never appear as rooms
        for (HomeDataClient.Room r : pr.socialRooms) {
            assertTrue(r.id != 51 && r.id != 53);
            assertNotNull(r.roomSlug);
        }
        HomeDataClient.Room lobby = pr.socialRooms.get(0);
        assertEquals(48, lobby.id);
        assertEquals("the-lobby", lobby.roomSlug);
        assertEquals("lobby", lobby.roomTheme);
        assertEquals(4, lobby.membersCount);
    }

    @Test
    public void departmentRoomMatchesUserDepartment() {
        String html = inertia(roomsProps(LIVE_GROUP, LIVE_DEPT));
        HomeDataClient.PartialRooms pr = HomeDataClient.parseRoomsPage(html, 7L);
        assertNotNull(pr.departmentRoom);
        assertEquals(9, pr.departmentRoom.id);
        assertEquals("Engineering Room", pr.departmentRoom.name);
        assertEquals("engineering-room", pr.departmentRoom.roomSlug);
    }

    @Test
    public void departmentNullWhenUserHasNoDepartment() {
        String html = inertia(roomsProps(LIVE_GROUP, LIVE_DEPT));
        HomeDataClient.PartialRooms pr = HomeDataClient.parseRoomsPage(html, null);
        assertNotNull("page itself is valid", pr);
        assertNull("no department → no department room (empty state)",
                pr.departmentRoom);
        assertEquals(3, pr.socialRooms.size());
    }

    @Test
    public void wrongDepartmentIdYieldsNullDeptRoom() {
        String html = inertia(roomsProps(LIVE_GROUP, LIVE_DEPT));
        HomeDataClient.PartialRooms pr = HomeDataClient.parseRoomsPage(html, 99L);
        assertNotNull(pr);
        assertNull(pr.departmentRoom);
    }

    @Test
    public void nonInertiaRoomsBodyIsFailure() {
        assertNull(HomeDataClient.parseRoomsPage("<html>Whoops</html>", 7L));
    }

    // ---- composition ------------------------------------------------------

    @Test
    public void composeHomeDataBuildsFullModel() {
        HomeDataClient.PartialDashboard dash = HomeDataClient.parseDashboardPage(
                inertia(dashboardProps(USER_ENG,
                        "[" + MEETING_21 + "]", "[]",
                        "{\"meetings_today\":2}",
                        "{\"overdue_tasks\":10}")));
        HomeDataClient.PartialRooms rooms = HomeDataClient.parseRoomsPage(
                inertia(roomsProps(LIVE_GROUP, LIVE_DEPT)), 7L);
        assertNotNull(dash);
        assertNotNull(rooms);

        HomeDataClient.HomeData h = HomeDataClient.composeHomeData(dash, rooms, 4);
        assertNotNull(h);
        assertEquals(12, h.userId);
        assertEquals("Eng Member", h.userName);
        assertEquals("Engineering", h.departmentName);
        assertEquals(21, h.nextMeeting.id);
        assertEquals(3, h.socialRooms.size());
        assertEquals(9, h.departmentRoom.id);
        assertEquals(2, h.today.meetingsToday);
        assertEquals(10, h.today.tasksOverdue);
        assertEquals(4, h.today.unreadNotifications);
        // Sections not sourced by the composite are honest nulls → the
        // approved empty states (never fabricated data).
        assertNull(h.recentConversation);
        assertNull(h.priorityTask);
    }

    @Test
    public void composeNullPartsYieldsNull() {
        HomeDataClient.PartialRooms rooms = HomeDataClient.parseRoomsPage(
                inertia(roomsProps(LIVE_GROUP, LIVE_DEPT)), null);
        assertNull(HomeDataClient.composeHomeData(null, rooms, 0));
        HomeDataClient.PartialDashboard dash = HomeDataClient.parseDashboardPage(
                inertia(dashboardProps(USER_QA, "[]", "[]", "{}", "{}")));
        assertNull(HomeDataClient.composeHomeData(dash, null, 0));
    }

    // ---- no-member-cross-contamination (session-scoped cache rule) --------

    @Test
    public void composedDataCarriesUserIdForCacheScoping() {
        HomeDataClient.PartialDashboard dashA = HomeDataClient.parseDashboardPage(
                inertia(dashboardProps(USER_QA, "[]", "[]", "{}", "{}")));
        HomeDataClient.PartialDashboard dashB = HomeDataClient.parseDashboardPage(
                inertia(dashboardProps(USER_ENG, "[]", "[]", "{}", "{}")));
        HomeDataClient.PartialRooms rooms =
                HomeDataClient.parseRoomsPage(inertia(roomsProps(LIVE_GROUP, LIVE_DEPT)), 7L);
        HomeDataClient.HomeData a = HomeDataClient.composeHomeData(dashA, rooms, 0);
        HomeDataClient.HomeData b = HomeDataClient.composeHomeData(dashB, rooms, 0);
        // Different users compose different identities — the v1.5.41
        // per-user cache (cachedForUserId) keeps them isolated.
        assertTrue(a.userId != b.userId);
        assertEquals(5, a.userId);
        assertEquals(12, b.userId);
    }
}
