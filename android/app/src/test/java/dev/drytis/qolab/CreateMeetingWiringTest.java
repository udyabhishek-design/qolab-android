package dev.drytis.qolab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * v1.5.31 — Create Meeting wiring tests (source-level contract checks).
 */
public class CreateMeetingWiringTest {

    private static final String APP = "android/app/src/main";
    private static final String JAVA = APP + "/java/dev/drytis/qolab";

    private static String read(String rel) throws Exception {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path p = dir.resolve(rel).normalize();
            if (Files.exists(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        }
        throw new java.io.FileNotFoundException(rel);
    }

    @Test
    public void roomPageParsesChannelId() throws Exception {
        String src = read(JAVA + "/RoomDataClient.java");
        assertTrue(src.contains("channel.optLong(\"id\", 0)"));
        assertTrue(src.contains("public final long channelId"));
    }

    @Test
    public void newMeetingEntryWiredWithoutDisruptingTabs() throws Exception {
        // v1.5.35: the entry lives in the MEETINGS tab header now, not the
        // tab pill row in activity_room.xml.
        String layout = read(APP + "/res/layout/room_tab_meetings.xml");
        assertTrue(layout.contains("room_new_meeting"));
        String room = read(APP + "/res/layout/activity_room.xml");
        assertTrue(room.contains("room_tab_overview"));
        assertTrue(room.contains("room_tab_meetings"));
        assertTrue(room.contains("room_tab_tasks"));
        String src = read(JAVA + "/RoomActivity.java");
        assertTrue(src.contains("openCreateMeeting()"));
        assertTrue(src.contains("new CreateMeetingSheet(this, page.channelId,"));
    }

    @Test
    public void createUsesExistingEndpointWithRealChannelId() throws Exception {
        String src = read(JAVA + "/CreateMeetingSheet.java");
        assertTrue(src.contains("transport.postJson(\"/office/rooms/\" + channelId + \"/meetings\""));
        // never shows ids/slugs in UI; room association from page payload
        assertTrue(src.contains("channelId"));
        assertTrue(!src.contains("spinner"));
    }

    @Test
    public void successTriggersRoomRefresh() throws Exception {
        String src = read(JAVA + "/RoomActivity.java");
        assertTrue(src.contains("onMeetingCreated"));
        assertTrue(src.contains("loadRoom();"));
    }

    @Test
    public void errorClearsWhenUserEditsFields() throws Exception {
        String src = read(JAVA + "/CreateMeetingSheet.java");
        // v1.5.32: a visible validation error (local or server 422) must be
        // cleared when the user edits/corrects any field — never stuck on
        // screen after the field becomes valid.
        assertTrue(src.contains("addTextChangedListener"));
        assertTrue(src.contains("clearError()"));
        // Title must be read from the CURRENT visible input at submit time.
        assertTrue(src.contains("titleInput.getText().toString().trim()"));
    }

    @Test
    public void timezoneShownFriendlyNotLegacyAlias() throws Exception {
        String src = read(JAVA + "/CreateMeetingSheet.java");
        // Display label goes through displayTimezone(); raw device id
        // (which can be the legacy Asia/Calcutta alias) never reaches the UI.
        assertTrue(src.contains("CreateMeetingForm.displayTimezone("));
        assertTrue(!src.contains("timezoneView.setText(CreateMeetingForm.deviceTimezone())"));
    }

    @Test
    public void startNowUsesContractInstantMode() throws Exception {
        String src = read(JAVA + "/CreateMeetingSheet.java");
        assertTrue(src.contains("submit(true)"));
        String form = read(JAVA + "/CreateMeetingForm.java");
        assertTrue(form.contains("empty/omitted = instant meeting"));
    }

    @Test
    public void participantsOnlyFromRealMembers() throws Exception {
        String src = read(JAVA + "/CreateMeetingSheet.java");
        assertTrue(src.contains("for (RoomDataClient.Member m : members)"));
        assertTrue(src.contains("if (m.id <= 0) continue;"));
    }

    @Test
    public void humanReadableErrorsNoRawBackendText() throws Exception {
        String src = read(JAVA + "/CreateMeetingSheet.java");
        assertTrue(src.contains("CreateMeetingForm.humanError(r.body)"));
        assertTrue(src.contains("cm_error_generic"));
    }

    @Test
    public void approvedScreensUnchangedNoMeetingDetailOrTranscriptDiff() throws Exception {
        // Meeting Details / Transcript sources must not be touched by this feature
        String detail = read(JAVA + "/MeetingDetailActivity.java");
        assertTrue(detail.contains("transport.getJson(\"/meetings/\" + meetingId + \"/detail\""));
    }

    @Test
    public void submitRejectsPastScheduledTimeBeforeRequest() throws Exception {
        // v1.5.37: the backend list contract hides scheduled meetings whose
        // scheduled_at has passed, so a past-dated create is a false success.
        // submit() must guard BEFORE building/sending the payload, keeping the
        // sheet open with a visible human error (form data preserved).
        String src = read(JAVA + "/CreateMeetingSheet.java");
        int guard = src.indexOf("CreateMeetingForm.isFutureLocal");
        int payload = src.indexOf("CreateMeetingForm.buildPayload");
        assertTrue("future-time guard missing from submit()", guard > 0);
        assertTrue("buildPayload call missing", payload > 0);
        assertTrue("future-time guard must precede buildPayload",
                guard < payload);
        assertTrue("guard must show the human past-time error",
                src.contains("cm_error_past_time"));
        // Within submit() itself, nothing dismisses before the guard —
        // the sheet must stay open so entered data is preserved.
        int submitStart = src.indexOf("private void submit(boolean instant)");
        int guard2 = src.indexOf("CreateMeetingForm.isFutureLocal", submitStart);
        assertTrue("submit() method not found or guard absent inside it",
                submitStart >= 0 && guard2 > submitStart);
        String beforeGuard = src.substring(submitStart, guard2);
        assertTrue("submit() must not dismiss before the guard fires",
                !beforeGuard.contains("dialog.dismiss()"));
    }

    @Test
    public void timePickerDefaultsToNextFutureSlot() throws Exception {
        String src = read(JAVA + "/CreateMeetingSheet.java");
        // hardcoded 9,0 default was the v1.5.36 bug: scheduling "today"
        // defaulted to a time already past → meeting hidden from Upcoming.
        assertFalse("hardcoded 9:00 picker default must be gone",
                src.contains("}, 9, 0, false).show()"));
        assertTrue("picker must use the computed future default",
                src.contains("CreateMeetingForm.defaultPickerHour()")
                        && src.contains("CreateMeetingForm.defaultPickerMinute()"));
    }
}
