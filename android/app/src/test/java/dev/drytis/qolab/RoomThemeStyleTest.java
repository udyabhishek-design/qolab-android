package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * v1.5.16 Home completion tests:
 *   - room theme → card palette mapping (the approved reference identity
 *     colors live in RoomThemeStyle, extracted from HomeActivity so they
 *     are JVM-testable)
 *   - default description selection per theme (no hardcoded sample member
 *     or meeting data ever enters the cards)
 *   - parse layer mapping for the fields the completed sections render
 */
public class RoomThemeStyleTest {

    @Test
    public void lobbyThemeMapsToCobalt() {
        RoomThemeStyle.Style s = RoomThemeStyle.styleFor("lobby", "the-lobby");
        assertEquals(0xFF2148C0, s.bg);
        assertEquals(0xFFFFFFFF, s.title);
        assertEquals(0xFFF5F0E4, s.enterBg);
        assertEquals("home_room_desc_lobby", s.descriptionKey);
    }

    @Test
    public void musicThemeMapsToPurpleWithLimeAccents() {
        RoomThemeStyle.Style s = RoomThemeStyle.styleFor("music", "music-lounge");
        assertEquals(0xFF6B21A8, s.bg);
        assertEquals(0xFFF4FF6B, s.title);   // lime title accent
        assertEquals(0xFFF4FF6B, s.enterBg); // lime button
        assertEquals(0xFF6B21A8, s.enterText);
        assertEquals("home_room_desc_music", s.descriptionKey);
    }

    @Test
    public void coffeeThemeMapsToForestWithLimeAccents() {
        RoomThemeStyle.Style s = RoomThemeStyle.styleFor("coffee", "coffee-lounge");
        assertEquals(0xFF14532D, s.bg);
        assertEquals(0xFFF4FF6B, s.title);
        assertEquals(0xFFF4FF6B, s.enterBg);
        assertEquals("home_room_desc_coffee", s.descriptionKey);
    }

    @Test
    public void slugFallbackWhenThemeIsNull() {
        RoomThemeStyle.Style s = RoomThemeStyle.styleFor(null, "music-lounge");
        assertEquals(0xFF6B21A8, s.bg);
        s = RoomThemeStyle.styleFor(null, "coffee-lounge");
        assertEquals(0xFF14532D, s.bg);
    }

    @Test
    public void unknownThemeFallsBackToCobaltSafely() {
        RoomThemeStyle.Style s = RoomThemeStyle.styleFor("garden", "garden-room");
        assertNotNull(s);
        assertEquals(0xFF2148C0, s.bg);
        assertEquals("home_room_default_description", s.descriptionKey);
    }

    @Test
    public void nullThemeAndSlugFallsBackSafely() {
        RoomThemeStyle.Style s = RoomThemeStyle.styleFor(null, null);
        assertNotNull(s);
        assertEquals(0xFF2148C0, s.bg);
    }

    // ------------------------------------------------------------------
    // Parse layer: fields the completed sections render
    // ------------------------------------------------------------------

    @Test
    public void meetingParsesTimeDateAndParticipants() {
        String json = "{\"next_meeting\":{\"id\":2,\"title\":\"Ops Sync\","
                + "\"scheduled_at_display\":\"4:45 PM\",\"scheduled_date_display\":\"Oct 5, 2026\","
                + "\"timezone\":\"Asia/Kolkata\",\"status\":\"upcoming\",\"participants_count\":5,"
                + "\"participants\":[{\"id\":1,\"name\":\"Admin User\",\"avatar_path\":null}]}}";
        HomeDataClient.HomeData d = HomeDataClient.parse(json);
        assertNotNull(d.nextMeeting);
        assertEquals("Ops Sync", d.nextMeeting.title);
        assertEquals("4:45 PM", d.nextMeeting.timeDisplay);
        assertEquals("Oct 5, 2026", d.nextMeeting.dateDisplay);
        assertEquals(5, d.nextMeeting.participantsCount);
        assertEquals(1, d.nextMeeting.participants.size());
    }

    @Test
    public void departmentDescriptionComesFromPayload() {
        String json = "{\"department_room\":{\"id\":9,\"name\":\"Engineering Room\","
                + "\"description\":\"Room for the Engineering department\","
                + "\"room_slug\":\"engineering-room\",\"room_theme\":null,"
                + "\"members_count\":5,\"participants\":[]}}";
        HomeDataClient.HomeData d = HomeDataClient.parse(json);
        assertNotNull(d.departmentRoom);
        assertEquals("Room for the Engineering department", d.departmentRoom.description);
        assertEquals("Engineering Room", d.departmentRoom.name);
    }

    @Test
    public void roomDescriptionIsNullWhenPayloadHasNone() {
        // render layer must substitute the theme default, never sample text
        String json = "{\"social_rooms\":[{\"id\":48,\"name\":\"The Lobby\","
                + "\"description\":null,\"room_slug\":\"the-lobby\",\"room_theme\":\"lobby\","
                + "\"members_count\":3,\"participants\":[]}]}";
        HomeDataClient.HomeData d = HomeDataClient.parse(json);
        assertEquals(1, d.socialRooms.size());
        assertNull(d.socialRooms.get(0).description);
        assertEquals("the-lobby", d.socialRooms.get(0).roomSlug);
    }

    @Test
    public void todayCountsIncludeOverdue() {
        String json = "{\"today\":{\"tasks_due_today\":1,\"tasks_overdue\":2,"
                + "\"tasks_pending\":0,\"meetings_today\":3,\"unread_notifications\":7}}";
        HomeDataClient.HomeData d = HomeDataClient.parse(json);
        assertEquals(1, d.today.tasksDueToday);
        assertEquals(2, d.today.tasksOverdue);
        assertEquals(3, d.today.meetingsToday);
        assertEquals(7, d.today.unreadNotifications);
    }

    // ═══ v1.5.19 visual refinement ═══

    @Test
    public void preferredOrderLobbyBeforeMusicBeforeCoffee() {
        int lobby = RoomThemeStyle.preferredOrder("lobby", "the-lobby");
        int music = RoomThemeStyle.preferredOrder("music", "music-lounge");
        int coffee = RoomThemeStyle.preferredOrder("coffee", "coffee-lounge");
        assertEquals(0, lobby);
        assertEquals(1, music);
        assertEquals(2, coffee);
        assertEquals("Lobby < Music < Coffee",
                true, lobby < music && music < coffee);
    }

    @Test
    public void preferredOrderUnknownRoomsSortLast() {
        assertEquals(3, RoomThemeStyle.preferredOrder(null, null));
        assertEquals(3, RoomThemeStyle.preferredOrder("casual", "garden"));
    }

    @Test
    public void preferredOrderMatchesThemeOrSlug() {
        assertEquals(0, RoomThemeStyle.preferredOrder("lobby", null));
        assertEquals(0, RoomThemeStyle.preferredOrder(null, "the-lobby"));
        assertEquals(2, RoomThemeStyle.preferredOrder(null, "coffee-lounge"));
    }
}
