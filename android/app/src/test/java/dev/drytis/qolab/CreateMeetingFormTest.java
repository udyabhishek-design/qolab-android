package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
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
    public void canonicalTimezoneResolvesLegacyAliases() {
        assertEquals("Asia/Kolkata", CreateMeetingForm.canonicalTimezoneId("Asia/Calcutta"));
        assertEquals("Asia/Kolkata", CreateMeetingForm.canonicalTimezoneId("Asia/Kolkata"));
        assertEquals("Asia/Ho_Chi_Minh", CreateMeetingForm.canonicalTimezoneId("Asia/Saigon"));
        assertEquals("UTC", CreateMeetingForm.canonicalTimezoneId("UTC"));
    }

    @Test
    public void displayTimezoneNeverShowsLegacyAlias() {
        String label = CreateMeetingForm.displayTimezone("Asia/Calcutta");
        assertNotNull(label);
        assertTrue("must not expose legacy alias: " + label,
                !label.contains("Calcutta"));
        String kolkata = CreateMeetingForm.displayTimezone("Asia/Kolkata");
        assertNotNull(kolkata);
        assertTrue("Kolkata/Calcutta must display identically: " + label + " vs " + kolkata,
                label.equals(kolkata));
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

    // ── v1.5.37: scheduled-time-must-be-future regression ────────────
    // Backend list contract: upcoming only includes scheduled meetings with
    // scheduled_at >= now(); past-dated creates are hidden from every bucket.
    // The picker must therefore default to a future slot and submit must
    // reject past datetimes BEFORE any request is sent.

    @Test
    public void pastLocalDatetimeIsRejected() {
        // Far past — always rejected.
        assertFalse(CreateMeetingForm.isFutureLocal(2020, 1, 1, 9, 0));
    }

    @Test
    public void farFutureLocalDatetimeIsAccepted() {
        // 2100 is comfortably future on any device clock.
        assertTrue(CreateMeetingForm.isFutureLocal(2100, 6, 15, 14, 30));
    }

    @Test
    public void oneMinuteAheadIsAccepted() {
        // A slot one minute in the future must pass — the guard must not be
        // so aggressive that legitimate near-term scheduling breaks.
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.MINUTE, 1);
        assertTrue(CreateMeetingForm.isFutureLocal(
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE)));
    }

    @Test
    public void oneMinuteAgoIsRejected() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.MINUTE, -1);
        assertFalse(CreateMeetingForm.isFutureLocal(
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE)));
    }

    @Test
    public void pickerDefaultIsAlwaysAFutureSlotTodayOrRollsHour() {
        // The computed default must land on a half-hour boundary and be a
        // valid future time when combined with today's date (or the next
        // hour when the current time is past :30).
        int h = CreateMeetingForm.defaultPickerHour();
        int min = CreateMeetingForm.defaultPickerMinute();
        assertTrue("minute must be 0 or 30", min == 0 || min == 30);
        assertTrue("hour must be 0..23", h >= 0 && h <= 23);
        java.util.Calendar now = java.util.Calendar.getInstance();
        int nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60
                + now.get(java.util.Calendar.MINUTE);
        int defMin = h * 60 + min;
        // Either later today, or it rolled to 00:00 (next day) — both are
        // future slots relative to now.
        assertTrue("default slot must be after now or rolled to next day",
                defMin > nowMin || (h == 0 && min == 0));
    }
}
