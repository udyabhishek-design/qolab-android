package dev.drytis.qolab;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * v1.5.34 regression guard for the G32 black screen.
 *
 *  Root cause: v1.5.33 added meeting_action_primary / meeting_action_copy /
 *  meeting_action_area to activity_meeting_detail.xml and fields
 *  actionArea/actionPrimary/actionCopy to MeetingDetailActivity, but
 *  bindViews() never assigned them — renderActions() NPE'd on the first
 *  scheduled/live meeting, killing the process mid-transition (black
 *  screen). These tests pin the wiring statically: the layout declares the
 *  ids, MeetingDetailActivity binds ALL of them in bindViews(), and wires
 *  click listeners in bindActions() so LiveRoomActivity can only launch
 *  from an explicit Start/Join tap.
 */
public class MeetingDetailBindingTest {

    private static final String SRC =
            "app/src/main/java/dev/drytis/qolab/MeetingDetailActivity.java";
    private static final String LAYOUT =
            "app/src/main/res/layout/activity_meeting_detail.xml";
    private static final String ROW =
            "app/src/main/java/dev/drytis/qolab/RoomActivity.java";
    private static final String HOME =
            "app/src/main/java/dev/drytis/qolab/HomeActivity.java";

    private static String read(String rel) throws Exception {
        Path cwd = Paths.get("").toAbsolutePath();
        Path root = cwd;
        for (Path d = cwd; d != null; d = d.getParent()) {
            if (Files.exists(d.resolve(".git"))) { root = d; break; }
        }
        return new String(Files.readAllBytes(
                root.resolve("android").resolve(rel)), StandardCharsets.UTF_8);
    }

    @Test
    public void layoutDeclaresActionViews() throws Exception {
        String xml = read(LAYOUT);
        for (String id : new String[]{
                "meeting_action_area", "meeting_action_primary", "meeting_action_copy"}) {
            assertTrue("layout missing @" + "id/" + id,
                    xml.contains("@+id/" + id));
        }
    }

    @Test
    public void everyActionViewIsBoundInBindViews() throws Exception {
        String src = read(SRC);
        for (String field : new String[]{"actionArea", "actionPrimary", "actionCopy"}) {
            int uses = 0;
            int idx = -1;
            while ((idx = src.indexOf(field + " = findViewById(", idx + 1)) != -1) uses++;
            assertTrue("bindViews() must assign " + field
                            + " (v1.5.33 regression: unbound field NPE'd in renderActions)",
                    uses == 1);
        }
    }

    @Test
    public void actionViewsHaveClickListeners() throws Exception {
        String src = read(SRC);
        assertTrue("primary action needs its Start/Join listener",
                src.contains("actionPrimary.setOnClickListener"));
        assertTrue("copy action needs its Copy Link listener",
                src.contains("actionCopy.setOnClickListener"));
    }

    @Test
    public void meetingCardStillTargetsMeetingDetailActivity() throws Exception {
        String row = read(ROW);
        assertTrue("meeting card must open MeetingDetailActivity",
                row.contains("new Intent(this, MeetingDetailActivity.class)"));
        // v1.5.34 black-screen rule (refined v1.5.38): the CARD TAP must not
        // enter LiveRoomActivity directly — only the explicit user actions
        // (Start-now chain / row Join-Start button) may, and via the shared
        // enterMeeting chain. The card's click listener body (from
        // setOnClickListener to the closing of that lambda) must contain no
        // LiveRoom intent.
        int cardListener = row.indexOf("card.setOnClickListener");
        int nextListener = row.indexOf("setOnClickListener", cardListener + 1);
        int scopeEnd = nextListener > 0 ? nextListener : row.length();
        String cardScope = row.substring(cardListener, scopeEnd);
        assertTrue("card click must never launch LiveRoomActivity directly",
                !cardScope.contains("new Intent(this, LiveRoomActivity.class)"));
        // …and the single LiveRoom launch site must live inside the explicit
        // requestJoinToken chain, not in any card/list rendering path.
        int launch = row.indexOf("new Intent(this, LiveRoomActivity.class)");
        int chainStart = row.indexOf("private void requestJoinToken(long resolvedMeetingId)");
        int chainEnd = row.indexOf("private void showComingSoon()");
        assertTrue("LiveRoomActivity launch must be inside requestJoinToken()",
                chainStart > 0 && launch > chainStart
                        && (chainEnd < 0 || launch < chainEnd));
    }

    @Test
    public void liveRoomLaunchOnlyFromExplicitUserAction() throws Exception {
        String src = read(SRC);
        int liveRoomLaunches = count(src, "new Intent(this, LiveRoomActivity.class)");
        assertTrue("LiveRoomActivity must be launched exactly once (from startOrJoin)",
                liveRoomLaunches == 1);
        int startOrJoinCallSites = count(src, "v -> startOrJoin()");
        // onCreate/bindViews must not call it; only the explicit click listener
        assertTrue("startOrJoin() must be invoked only from the click listener",
                startOrJoinCallSites == 1);
    }

    @Test
    public void summaryAndTranscriptRenderUntouched() throws Exception {
        String src = read(SRC);
        assertTrue(src.contains("renderSummary();"));
        assertTrue(src.contains("renderTranscript();"));
        // render order: summary first, actions last — status/action code
        // cannot prevent detail rendering from having already run.
        int summary = src.indexOf("renderSummary();");
        int actions = src.indexOf("renderActions();");
        assertTrue(summary >= 0 && actions > summary);
    }

    @Test
    public void homeDepartmentCardOpensSharedRoomActivity() throws Exception {
        String home = read(HOME);
        assertTrue("Home must open the shared native RoomActivity for the department room",
                home.contains("new Intent(this, RoomActivity.class)"));
        assertTrue("Home must pass the real room_slug identity",
                home.contains("RoomActivity.EXTRA_ROOM_SLUG"));
    }

    private static int count(String haystack, String needle) {
        int n = 0, idx = -1;
        while ((idx = haystack.indexOf(needle, idx + 1)) != -1) n++;
        return n;
    }
}
