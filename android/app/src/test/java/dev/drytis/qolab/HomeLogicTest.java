package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

import java.util.List;

/**
 * Home V1 unit tests — pure logic: greeting/time, name rendering,
 * JSON→model mapping, empty states, count formatting, hardcoded-content
 * regression.
 */
public class HomeLogicTest {

    // ------------------------------------------------------------------
    // Greeting / time logic
    // ------------------------------------------------------------------

    @Test
    public void greetingWordBoundaries() {
        assertEquals("Good Morning", GreetingLogic.greetingWord(0));
        assertEquals("Good Morning", GreetingLogic.greetingWord(11));
        assertEquals("Good Afternoon", GreetingLogic.greetingWord(12));
        assertEquals("Good Afternoon", GreetingLogic.greetingWord(16));
        assertEquals("Good Evening", GreetingLogic.greetingWord(17));
        assertEquals("Good Evening", GreetingLogic.greetingWord(23));
    }

    @Test
    public void greetingLineHasComma() {
        assertEquals("Good Morning,", GreetingLogic.greetingLine(9));
        assertEquals("Good Evening,", GreetingLogic.greetingLine(20));
    }

    @Test
    public void firstNameExtraction() {
        assertEquals("Abhishek", GreetingLogic.firstName("Abhishek Udy"));
        assertEquals("Priya", GreetingLogic.firstName("Priya"));
        assertNull(GreetingLogic.firstName(null));
        assertNull(GreetingLogic.firstName("   "));
        assertEquals("Jo", GreetingLogic.firstName("Jo van der Meer"));
    }

    @Test
    public void dateLineUsesProvidedValuesNotToday() {
        // Oct 1 2026 is a Thursday. The date line must come from the INPUT,
        // never from the wall clock, and must contain day + month + day-number.
        String line = GreetingLogic.dateLine(2026, java.util.Calendar.OCTOBER, 1);
        assertNotNull(line);
        assertTrue(line.contains("Oct"));
        assertTrue(line.contains("1"));
    }

    // ------------------------------------------------------------------
    // Home JSON → model mapping
    // ------------------------------------------------------------------

    @Test
    public void parseFullPayload() {
        String json = "{"
            + "\"user\":{\"name\":\"QA Dropdown V3\",\"system_role\":\"employee\",\"department_name\":\"Engineering\"},"
            + "\"next_meeting\":{\"id\":2,\"title\":\"Home V1 Test Sync\",\"scheduled_at_display\":\"4:45 PM\","
            +   "\"scheduled_date_display\":\"Oct 1, 2026\",\"timezone\":\"Asia/Kolkata\",\"status\":\"scheduled\","
            +   "\"participants_count\":5,"
            +   "\"participants\":[{\"id\":1,\"name\":\"Admin User\"},{\"id\":13,\"name\":\"QA Dropdown V3\"}]},"
            + "\"social_rooms\":["
            +   "{\"id\":48,\"name\":\"The Lobby\",\"room_slug\":\"the-lobby\",\"room_type\":\"social\",\"room_theme\":\"lobby\",\"members_count\":3,"
            +   "\"participants\":[{\"id\":1,\"name\":\"Admin User\"}]},"
            +   "{\"id\":7,\"name\":\"Music Lounge\",\"room_slug\":\"music-lounge\",\"room_type\":\"casual\",\"room_theme\":\"music\",\"members_count\":8,\"participants\":[]},"
            +   "{\"id\":47,\"name\":\"Coffee Lounge\",\"room_slug\":\"coffee-lounge\",\"room_type\":\"social\",\"room_theme\":\"coffee\",\"members_count\":2,\"participants\":[]}],"
            + "\"department_room\":{\"id\":9,\"name\":\"Engineering Room\",\"room_slug\":\"engineering-room\",\"members_count\":5,"
            +   "\"participants\":[{\"id\":6,\"name\":\"QA DJ (Dev)\"}]},"
            + "\"recent_conversation\":{\"id\":49,\"name\":\"QA Admin (Dev)\",\"type\":\"dm\","
            +   "\"dm_partner\":{\"id\":5,\"name\":\"QA Admin (Dev)\"},\"last_message_at\":\"2026-10-01T10:35:55.000000Z\",\"unread_count\":2},"
            + "\"priority_task\":{\"id\":20,\"title\":\"Home V1 task\",\"due_date\":\"2026-10-01\",\"status\":\"in-progress\"},"
            + "\"today\":{\"tasks_due_today\":1,\"tasks_overdue\":0,\"tasks_pending\":1,\"meetings_today\":1,\"unread_notifications\":4}"
            + "}";

        HomeDataClient.HomeData d = HomeDataClient.parse(json);
        assertEquals("QA Dropdown V3", d.userName);
        assertEquals("Engineering", d.departmentName);

        // next meeting
        assertNotNull(d.nextMeeting);
        assertEquals("Home V1 Test Sync", d.nextMeeting.title);
        assertEquals("4:45 PM", d.nextMeeting.timeDisplay);
        assertEquals(5, d.nextMeeting.participantsCount);
        assertEquals(2, d.nextMeeting.participants.size());

        // rooms
        assertEquals(3, d.socialRooms.size());
        assertEquals("The Lobby", d.socialRooms.get(0).name);
        assertEquals("music", d.socialRooms.get(1).roomTheme);
        assertEquals(8, d.socialRooms.get(1).membersCount);

        // department
        assertNotNull(d.departmentRoom);
        assertEquals("Engineering Room", d.departmentRoom.name);

        // conversation (dm partner preferred for DMs)
        assertNotNull(d.recentConversation);
        assertEquals("QA Admin (Dev)", d.recentConversation.dmPartnerName);
        assertEquals(2, d.recentConversation.unreadCount);

        // task + today
        assertNotNull(d.priorityTask);
        assertEquals("2026-10-01", d.priorityTask.dueDate);
        assertEquals(1, d.today.tasksDueToday);
        assertEquals(4, d.today.unreadNotifications);
    }

    // ------------------------------------------------------------------
    // Empty states
    // ------------------------------------------------------------------

    @Test
    public void meetingEmptyWhenNull() {
        HomeDataClient.HomeData d = HomeDataClient.parse("{\"next_meeting\":null}");
        assertNull(d.nextMeeting);
    }

    @Test
    public void departmentEmptyWhenNull() {
        HomeDataClient.HomeData d = HomeDataClient.parse("{\"department_room\":null}");
        assertNull(d.departmentRoom);
    }

    @Test
    public void emptyPayloadDegradesGracefully() {
        HomeDataClient.HomeData d = HomeDataClient.parse("{}");
        assertNull(d.userName);
        assertNull(d.nextMeeting);
        assertNull(d.departmentRoom);
        assertNull(d.recentConversation);
        assertNull(d.priorityTask);
        assertNotNull(d.socialRooms);
        assertTrue(d.socialRooms.isEmpty());
        assertEquals(0, d.today.meetingsToday);
    }

    @Test
    public void malformedJsonNeverThrows() {
        // v1.5.36: a body we cannot parse is a FAILURE (null), never
        // fabricated empty data. Previously this returned an empty HomeData,
        // which made Office render a 404's HTML error page as a valid
        // "No social rooms yet" empty account.
        HomeDataClient.HomeData d = HomeDataClient.parse("not json {");
        assertNull(d);
    }

    @Test
    public void htmlErrorPageIsParseFailureNotEmptyData() {
        // Exact v1.5.35 field regression: a Laravel 404/500 HTML page body
        // must parse to null so the screen shows error+retry, not empty state.
        HomeDataClient.HomeData d = HomeDataClient.parse(
            "<!DOCTYPE html><html><head><title>404 Not Found</title></head>"
            + "<body><h1>Not Found</h1></body></html>");
        assertNull(d);
    }

    // ------------------------------------------------------------------
    // Count formatting / today strip
    // ------------------------------------------------------------------

    @Test
    public void todayCountsMapFromPayload() {
        HomeDataClient.HomeData d = HomeDataClient.parse(
            "{\"today\":{\"tasks_due_today\":3,\"tasks_overdue\":1,\"meetings_today\":2,\"unread_notifications\":7}}");
        assertEquals(3, d.today.tasksDueToday);
        assertEquals(1, d.today.tasksOverdue);
        assertEquals(2, d.today.meetingsToday);
        assertEquals(7, d.today.unreadNotifications);
    }

    // ------------------------------------------------------------------
    // Hardcoded reference-content regression
    // ------------------------------------------------------------------

    @Test
    public void noHardcodedReferenceUserOrMeetingContent() {
        // The REFERENCE design's sample data must never leak into models.
        // 'Abhishek' as a user name, 'Operations Sync' as a meeting, etc. are
        // reference fixtures — the parsed model must only ever carry what the
        // payload supplied.
        HomeDataClient.HomeData d = HomeDataClient.parse(
            "{\"user\":{\"name\":\"Zoe Quill\"},\"next_meeting\":null}");
        assertEquals("Zoe Quill", d.userName);
        assertNull(d.nextMeeting); // null stays null — nothing fabricated
    }
}
