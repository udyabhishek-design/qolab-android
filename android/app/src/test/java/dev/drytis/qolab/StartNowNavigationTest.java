package dev.drytis.qolab;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * v1.5.38 regression guards: "Start now" must chain directly into the
 * existing join flow using the meeting id the server encodes into the
 * create redirect (?meeting={id}), with no duplicated LiveKit logic and
 * no navigation when the id is missing or creation failed.
 */
public class StartNowNavigationTest {

    private static Path repoRoot() {
        Path d = new File(".").getAbsoluteFile().toPath().getParent();
        for (int i = 0; i < 8 && d != null; i++, d = d.getParent()) {
            if (Files.exists(d.resolve(".git"))) return d;
        }
        return new File(".").getAbsoluteFile().toPath().getParent();
    }

    private static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(
                repoRoot().resolve("android/app/src/main/java/dev/drytis/qolab/" + rel)));
    }

    // ── Behavior: meetingIdFromFinalUrl ───────────────────────────────

    @Test
    public void meetingIdRecoveredFromLiveContractRedirect() {
        // Exact shape live-verified 2026-10-09: POST /office/rooms/9/meetings
        // → 302 Location .../office/rooms/engineering-room?tab=meetings&filter=live&meeting=32
        assertEquals(32, RoomDataClient.meetingIdFromFinalUrl(
                "https://workpulse-gbpedt.drytis.dev/office/rooms/engineering-room?tab=meetings&filter=live&meeting=32"));
    }

    @Test
    public void meetingIdLastParamAlsoRecovered() {
        assertEquals(7, RoomDataClient.meetingIdFromFinalUrl(
                "https://host/office/rooms/x?meeting=7&tab=meetings"));
    }

    @Test
    public void missingOrNullMeetingParamYieldsZero() {
        assertEquals(0, RoomDataClient.meetingIdFromFinalUrl(
                "https://host/office/rooms/x?tab=meetings"));
        assertEquals(0, RoomDataClient.meetingIdFromFinalUrl(null));
        assertEquals(0, RoomDataClient.meetingIdFromFinalUrl("https://host/x"));
    }

    @Test
    public void malformedValuesNeverCrashAndYieldZero() {
        assertEquals(0, RoomDataClient.meetingIdFromFinalUrl(
                "https://host/x?meeting=abc"));
        assertEquals(0, RoomDataClient.meetingIdFromFinalUrl(
                "https://host/x?meeting=-5"));
        assertEquals(0, RoomDataClient.meetingIdFromFinalUrl(
                "https://host/x?meeting="));
    }

    // ── Wiring: transport carries the final URL ───────────────────────

    @Test
    public void fetchBeaconCarriesFinalUrl() throws Exception {
        String src = read("QolabWebViewTransport.java");
        assertTrue("beacon must include r.url",
                src.contains("u:r.url"));
        assertTrue("Result must expose finalUrl",
                src.contains("public final String finalUrl"));
    }

    @Test
    public void beaconParserReadsUrlField() throws Exception {
        String src = read("QolabWebViewTransport.java");
        assertTrue(src.contains("o.optString(\"u\", null)"));
    }

    // ── Wiring: Start-now chains into the join flow ───────────────────

    @Test
    public void instantCreateHandsIdToRoomListener() throws Exception {
        String src = read("CreateMeetingSheet.java");
        assertTrue("instant path must pass the created id",
                src.contains("listener.onMeetingCreated(createdId, true)"));
        // no "Started" toast on the instant path anymore — the meeting entry
        // IS the confirmation; a toast implies the old two-step flow.
        int toastIdx = src.indexOf("cm_started_toast");
        int instantReturn = src.indexOf("listener.onMeetingCreated(createdId, true)");
        assertTrue("cm_started_toast may only exist on non-instant paths",
                toastIdx < 0 || instantReturn < toastIdx);
    }

    @Test
    public void roomChainsStartNowIntoExistingJoinFlow() throws Exception {
        String src = read("RoomActivity.java");
        assertTrue("onMeetingCreated must accept the id + instant flag",
                src.contains("onMeetingCreated(long createdMeetingId, boolean instant)"));
        assertTrue("instant create must call enterMeeting",
                src.contains("enterMeeting(createdMeetingId)"));
        // the chain reuses the VERIFIED endpoint sequence…
        assertTrue(src.contains("\"/meetings/\" + meetingId + \"/start-or-join-session\""));
        assertTrue(src.contains("\"/meetings/\" + resolvedMeetingId + \"/join-token\""));
        // …and hands off to the single LiveKit owner
        assertTrue(src.contains("new Intent(this, LiveRoomActivity.class)"));
    }

    @Test
    public void doubleTapGuardOnJoinChain() throws Exception {
        String src = read("RoomActivity.java");
        assertTrue("enterMeeting must be guarded",
                src.contains("if (entering) return;"));
        assertTrue(src.contains("private volatile boolean entering"));
        // and reset on resume so a leave→rejoin works
        assertTrue(src.contains("if (entering) entering = false;"));
    }

    @Test
    public void schedulePathUnchanged() throws Exception {
        String src = read("CreateMeetingSheet.java");
        // scheduled create still toasts + refreshes via the same listener
        assertTrue(src.contains("listener.onMeetingCreated(createdId, instant)"));
        assertTrue(src.contains("cm_created_toast"));
    }

    @Test
    public void noDuplicatedLiveKitConnectionLogic() throws Exception {
        // LiveRoomActivity must remain the ONLY class that constructs a
        // LiveKit Room/connect. RoomActivity must not import livekit.
        String room = read("RoomActivity.java");
        assertFalse("RoomActivity must not own LiveKit connect logic",
                room.contains("io.livekit"));
        String sheet = read("CreateMeetingSheet.java");
        assertFalse("CreateMeetingSheet must not own LiveKit connect logic",
                sheet.contains("io.livekit"));
    }

    @Test
    public void rowJoinStartButtonsAreWired() throws Exception {
        String src = read("RoomActivity.java");
        assertTrue("row Join/Start action must navigate, not just show",
                src.contains("enterAction.setOnClickListener(v -> enterMeetingFromRow(m.id))"));
    }
}
