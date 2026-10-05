package dev.drytis.qolab;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * v1.5.31 — pure, JVM-testable helpers for the Create Meeting flow
 * (Engineering Room). Mirrors EXACTLY what the web client sends to
 * POST /office/rooms/{room}/meetings (MeetingController@roomStore):
 *
 *   title            required string ≤255
 *   description      optional string ≤5000
 *   scheduled_at     optional 'Y-m-d\TH:i' LOCAL (datetime-local), no
 *                    seconds, no timezone — empty/omitted = instant meeting
 *   timezone         IANA id (device zone; server converts to UTC)
 *   participants     array of real user ids (invited; creator auto-added
 *                    server-side)
 *
 * meeting_type/schedule_type: not sent — meeting_type is overridden to
 * 'department' server-side; 'daily' recurrence is a separate web-only
 * mode and is deliberately not exposed.
 * There is NO duration field in the contract.
 */
public final class CreateMeetingForm {

    private CreateMeetingForm() {}

    /** "2026-10-06T14:30" — datetime-local, exactly like the web client. */
    public static String scheduledAtLocal(int year, int month1based, int day,
                                          int hour, int minute) {
        return String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d",
                year, month1based, day, hour, minute);
    }

    /**
     * Device IANA timezone id, e.g. "Asia/Kolkata". Uses java.time
     * normalization so legacy aliases some devices report (Asia/Calcutta,
     * Asia/Saigon, …) are canonicalized to their current IANA names.
     */
    public static String deviceTimezone() {
        return canonicalTimezoneId(TimeZone.getDefault().getID());
    }

    /** Deprecated IANA ids → their current canonical names (IANA backward
     *  list subset actually seen on Android devices; ZoneId.of().getId()
     *  does NOT canonicalize these, so map them explicitly). */
    private static final java.util.Map<String, String> TZ_ALIAS;
    static {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("Asia/Calcutta", "Asia/Kolkata");
        m.put("Asia/Saigon", "Asia/Ho_Chi_Minh");
        m.put("Asia/Katmandu", "Asia/Kathmandu");
        m.put("Asia/Chungking", "Asia/Chongqing");
        m.put("Asia/Ujung_Pandang", "Asia/Makassar");
        m.put("Europe/Kiev", "Europe/Kyiv");
        m.put("Atlantic/Faeroe", "Atlantic/Faroe");
        m.put("Pacific/Enderbury", "Pacific/Kanton");
        m.put("US/Pacific-New", "America/Los_Angeles");
        m.put("Canada/East-Saskatchewan", "America/Regina");
        TZ_ALIAS = java.util.Collections.unmodifiableMap(m);
    }

    /** "Asia/Calcutta" → "Asia/Kolkata" (IANA deprecated alias resolution). */
    public static String canonicalTimezoneId(String id) {
        if (id == null || id.isEmpty()) return id;
        String mapped = TZ_ALIAS.get(id);
        if (mapped != null) return mapped;
        try {
            return java.time.ZoneId.of(id).getId();
        } catch (Exception e) {
            return id; // custom ids like "GMT+05:30" pass through unchanged
        }
    }

    /**
     * User-facing timezone label, e.g. "India Standard Time (IST)". Never
     * shows the wire value or a legacy alias; falls back to the id itself
     * when the platform has no display name.
     */
    public static String displayTimezone(String ianaId) {
        String canonical = canonicalTimezoneId(ianaId);
        try {
            TimeZone tz = TimeZone.getTimeZone(canonical);
            String longName = tz.getDisplayName(false, TimeZone.LONG, Locale.getDefault());
            if (longName == null || longName.isEmpty()
                    || longName.startsWith("GMT")) {
                return canonical;
            }
            String abbrev = tz.getDisplayName(false, TimeZone.SHORT, Locale.US);
            if (abbrev == null || abbrev.isEmpty()
                    || abbrev.startsWith("GMT") || abbrev.equals(canonical)) {
                return longName;
            }
            return longName + " (" + abbrev + ")";
        } catch (Exception e) {
            return canonical;
        }
    }

    /** Local validation BEFORE submit; null when the form may be sent. */
    public static String validate(String title, boolean hasDate, boolean hasTime) {
        if (title == null || title.trim().isEmpty()) return "title";
        if (hasDate != hasTime) return "when";      // half-picked date/time
        return null;
    }

    /**
     * Builds the exact request payload. selectedUserIds may be empty
     * (participants optional); ids must come from real room members.
     */
    public static String buildPayload(String title, String description,
                                      String scheduledAtLocal, String timezone,
                                      List<Long> selectedUserIds) throws Exception {
        JSONObject o = new JSONObject();
        o.put("title", title.trim());
        if (description != null && !description.trim().isEmpty()) {
            o.put("description", description.trim());
        }
        if (scheduledAtLocal != null && !scheduledAtLocal.isEmpty()) {
            o.put("scheduled_at", scheduledAtLocal);
        }
        if (timezone != null && !timezone.isEmpty()) {
            o.put("timezone", timezone);
        }
        if (selectedUserIds != null && !selectedUserIds.isEmpty()) {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (Long id : selectedUserIds) arr.put(id.longValue());
            o.put("participants", arr);
        }
        return o.toString();
    }

    /**
     * Maps a Laravel 422 body ({"message":…,"errors":{"field":[msg,…]}}) to
     * one human-readable line. Never returns raw backend text as-is when a
     * field message exists; unknown/garbage → generic message (caller's).
     */
    public static String humanError(String responseBody) {
        try {
            JSONObject root = new JSONObject(responseBody);
            JSONObject errors = root.optJSONObject("errors");
            if (errors != null) {
                java.util.Iterator<String> it = errors.keys();
                if (it.hasNext()) {
                    String field = it.next();
                    org.json.JSONArray arr = errors.optJSONArray(field);
                    if (arr != null && arr.length() > 0) {
                        return arr.optString(0);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null; // caller shows its own generic message
    }

    /** Convenience: current local date parts for picker defaults (none). */
    public static int[] defaultPickerDate() {
        Calendar c = Calendar.getInstance();
        return new int[]{c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH)};
    }
}
