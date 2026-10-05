package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * v1.5.31 — Create Meeting contract tests. Payload must match EXACTLY
 * what the web client sends to POST /office/rooms/{room}/meetings
 * (MeetingController@roomStore, verified in web source):
 * title / description? / scheduled_at "Y-m-d\TH:i" local (empty=instant) /
 * timezone IANA / participants int[] of real user ids. No duration field.
 */
public class CreateMeetingFormTest {

    @Test
    public void scheduledAtMatchesDatetimeLocalFormat() {
        assertEquals("2026-10-06T14:30",
                CreateMeetingForm.scheduledAtLocal(2026, 10, 6, 14, 30));
        assertEquals("2026-01-05T09:05",
                CreateMeetingForm.scheduledAtLocal(2026, 1, 5, 9, 5));
    }

    @Test
    public void payloadMatchesWebClientShape() throws Exception {
        List<Long> ids = Arrays.asList(13L, 1L);
        String json = CreateMeetingForm.buildPayload(
                "Weekly sync", "Agenda notes",
                "2026-10-06T14:30", "Asia/Kolkata", ids);
        JSONObject o = new JSONObject(json);
        assertEquals("Weekly sync", o.getString("title"));
        assertEquals("Agenda notes", o.getString("description"));
        assertEquals("2026-10-06T14:30", o.getString("scheduled_at"));
        assertEquals("Asia/Kolkata", o.getString("timezone"));
        JSONArray p = o.getJSONArray("participants");
        assertEquals(2, p.length());
        assertEquals(13, p.getLong(0));
        assertEquals(1, p.getLong(1));
    }

    @Test
    public void instantPayloadOmitsScheduledAt() throws Exception {
        String json = CreateMeetingForm.buildPayload(
                "Engineering Instant Meeting", null, null, "Asia/Kolkata",
                Collections.emptyList());
        JSONObject o = new JSONObject(json);
        assertTrue(!o.has("scheduled_at"));  // instant = contract's empty scheduled_at
        assertTrue(!o.has("participants"));  // empty list omitted
        assertTrue(!o.has("description"));   // blank omitted
        assertEquals("Engineering Instant Meeting", o.getString("title"));
    }

    @Test
    public void payloadNeverCarriesDurationOrMeetingType() throws Exception {
        String json = CreateMeetingForm.buildPayload("t", null,
                "2026-10-06T14:30", "UTC", null);
        JSONObject o = new JSONObject(json);
        assertTrue(!o.has("duration"));
        assertTrue(!o.has("duration_minutes"));
        assertTrue(!o.has("meeting_type")); // server overrides to 'department'
        assertTrue(!o.has("schedule_type"));
    }

    @Test
    public void validationRequiresTitleAndCompleteDateTime() {
        assertNull(CreateMeetingForm.validate("Sync", true, true));   // ok
        assertEquals("title", CreateMeetingForm.validate("", true, true));
        assertEquals("title", CreateMeetingForm.validate(null, true, true));
        assertEquals("when", CreateMeetingForm.validate("Sync", true, false));
        assertEquals("when", CreateMeetingForm.validate("Sync", false, true));
    }

    @Test
    public void fourTwentyTwoMapsToHumanFieldError() {
        String body = "{\"message\":\"The given data was invalid.\"," +
                "\"errors\":{\"title\":[\"The title field is required.\"]}}";
        assertEquals("The title field is required.", CreateMeetingForm.humanError(body));
    }

    @Test
    public void garbageOrEmptyBodyYieldsNullForGenericMessage() {
        assertNull(CreateMeetingForm.humanError(null));
        assertNull(CreateMeetingForm.humanError("html error page"));
        assertNull(CreateMeetingForm.humanError("{\"message\":\"oops\"}"));
    }

    @Test
    public void deviceTimezoneIsIanaId() {
        String tz = CreateMeetingForm.deviceTimezone();
        assertNotNull(tz);
        assertTrue(tz.contains("/")); // e.g. Asia/Kolkata, Europe/Berlin
    }

    @Test
    public void emptyTitleNeverSubmittedWithWhitespace() {
        assertEquals("title", CreateMeetingForm.validate("   ", true, true));
    }
}
