package dev.drytis.qolab;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * v1.5.35 stability pass regression guards.
 *
 *  Root causes fixed (see .drytis/specs/android-stability-v1.5.35.md):
 *   1. Cold-start exit: AlertDialog on a FINISHED activity (BadTokenException
 *      kills the process) + cold start bouncing to Login which expired every
 *      session cookie.
 *   2. Infinite skeleton: transport poll rescheduling lived INSIDE the
 *      evaluateJavascript ValueCallback — a dropped callback stranded the
 *      caller forever.
 *   3. Create/Start intermittent: transport captured at sheet construction,
 *      silent dismiss on transport-dead, no 419 retry, stuck joining flag,
 *      LiveRoom back-press never disconnecting/leaving.
 */
public class StabilityRegressionTest {

    private static Path findRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path d = cwd; d != null; d = d.getParent()) {
            if (Files.exists(d.resolve(".git"))) return d;
        }
        return cwd;
    }

    private static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(
                findRoot().resolve("android").resolve(rel)), StandardCharsets.UTF_8);
    }

    // ── 1. cold start ───────────────────────────────────────────────

    @Test
    public void devUpdateDialogsGuardedAgainstFinishedActivity() throws Exception {
        String src = read("app/src/main/java/dev/drytis/qolab/devupdate/DevUpdateController.java");
        int guards = count(src, "activity.isFinishing() || activity.isDestroyed()");
        // showPrompt + showProgressDialog must both bail on a dead activity.
        assertTrue(guards >= 2);
    }

    @Test
    public void homeRehydratesInsteadOfInstantLogoutBounce() throws Exception {
        String src = read("app/src/main/java/dev/drytis/qolab/HomeActivity.java");
        // v1.5.40: rehydration now flows through the SessionGate
        // (proof-before-logout). The gate itself must drive rehydrate and
        // Home must use the gate rather than bouncing straight to Login.
        assertTrue("cold start must attempt session rehydration first",
                src.contains("SessionGate.requireValidSession"));
        assertTrue("the gate must drive the transport rehydrate",
                read("app/src/main/java/dev/drytis/qolab/SessionGate.java")
                        .contains("QolabWebViewTransport.rehydrate("));
        assertTrue("failed rehydration must route to Login honestly",
                src.contains("routeToLogin()"));
    }

    // ── 2. callback always fires ────────────────────────────────────

    @Test
    public void pollLoopIsJavaDrivenInBothVerbs() throws Exception {
        String src = read("app/src/main/java/dev/drytis/qolab/QolabWebViewTransport.java");
        // The reschedule must happen from the Java runnable, not from the
        // ValueCallback chain.
        // Each verb has the reschedule inside poll[0] AND the initial kick
        // post — 2 sites x 2 verbs.
        int javaReschedules = count(src, "main.postDelayed(poll[0], POLL_INTERVAL_MS);");
        assertTrue("both getJson and postJson must reschedule from Java (found "
                + javaReschedules + ")", javaReschedules == 4);
        // A result must be delivered even if the WebView callback never runs:
        // the deadline check sits in the Java runnable before evaluate.
        int deadlineChecks = count(src, "elapsedRealtime() >= deadline");
        assertTrue(deadlineChecks >= 2);
    }

    // ── 3. meeting reliability ──────────────────────────────────────

    @Test
    public void createSheetCapturesTransportAtSubmitTime() throws Exception {
        String sheet = read("app/src/main/java/dev/drytis/qolab/CreateMeetingSheet.java");
        assertTrue("transport must be fetched inside submit, not at field init",
                sheet.contains("QolabWebViewTransport transport = QolabWebViewTransport.get();"));
        assertTrue("field-init capture must be gone",
                !sheet.contains("private final QolabWebViewTransport transport"));
    }

    @Test
    public void postJsonRetries419OnceAfterCsrfRefresh() throws Exception {
        String src = read("app/src/main/java/dev/drytis/qolab/QolabWebViewTransport.java");
        assertTrue("419 must trigger a retry path",
                src.contains("r.status == 419"));
        assertTrue("retry must refresh the CSRF cookie first",
                src.contains("sanctum/csrf-cookie"));
        assertTrue("retry must be bounded to once",
                src.contains("retried419"));
    }

    @Test
    public void detailRefetchOnlyAfterLiveRoomReturn() throws Exception {
        String src = read("app/src/main/java/dev/drytis/qolab/MeetingDetailActivity.java");
        assertTrue(src.contains("returningFromLiveRoom"));
        assertTrue("joining must be reset on resume/destroy (dropped callback "
                + "used to leave it stuck)",
                src.contains("joining = false;"));
    }

    @Test
    public void liveRoomBackPressBehavesLikeLeave() throws Exception {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        assertTrue("back press must route through leave()",
                src.contains("override fun onBackPressed() {\n        leave()\n    }"));
        assertTrue("onDestroy must disconnect unconditionally",
                src.contains("if (!leaving)"));
        assertTrue("coroutine scope must be cancelled",
                src.contains("scope.cancel()"));
    }

    // ── 4. UI: new meeting pill lives in the Meetings header ────────

    @Test
    public void newMeetingActionMovedIntoMeetingsHeader() throws Exception {
        String room = read("app/src/main/res/layout/activity_room.xml");
        String meetings = read("app/src/main/res/layout/room_tab_meetings.xml");
        assertTrue("tab pill row must no longer carry the new-meeting pill",
                !room.contains("room_new_meeting"));
        assertTrue("compact action must live in the Meetings tab",
                meetings.contains("room_new_meeting"));
        assertTrue("must use the compact label",
                meetings.contains("room_new_meeting_compact"));
    }

    private static int count(String haystack, String needle) {
        int n = 0, idx = -1;
        while ((idx = haystack.indexOf(needle, idx + 1)) != -1) n++;
        return n;
    }
}
