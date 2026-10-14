package dev.drytis.qolab;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * v1.5.44 regression tests — intermittent Home content disappearance.
 *
 * Confirmed bug (v1.5.43, BlueStacks): a transient background-refresh
 * failure ran showError() while valid content was on screen. It tore down
 * the rendered Social Rooms row, hid Continue Working and overlaid
 * "Couldn't load / Retry" on Meeting/Department cards; render methods
 * never cleared sibling error/empty views, so valid cards and error
 * messages coexisted — and persisted after a successful Retry.
 *
 * These tests pin the fix on two levels:
 *   1. behavior of the extracted pure rules (HomeSectionState);
 *   2. source-level assertions that HomeActivity actually applies them
 *      (error paths preserve content, renders clear stale states).
 */
public class HomeRefreshPreservationTest {

    private static Path repoRoot() {
        Path d = new File(".").getAbsoluteFile().toPath().getParent();
        for (int i = 0; i < 8 && d != null; i++, d = d.getParent()) {
            if (Files.exists(d.resolve(".git"))) return d;
        }
        return new File(".").getAbsoluteFile().toPath().getParent();
    }

    private static String home() throws Exception {
        return new String(Files.readAllBytes(repoRoot()
                .resolve("android/app/src/main/java/dev/drytis/qolab/HomeActivity.java")));
    }

    // ---- pure rules ------------------------------------------------------

    @Test
    public void successfulLoadResolvesContentOrEmptyNeverError() {
        assertEquals(HomeSectionState.Visual.CONTENT,
                HomeSectionState.resolved(true, true));
        assertEquals(HomeSectionState.Visual.EMPTY,
                HomeSectionState.resolved(true, false));
    }

    @Test
    public void failedInitialLoadResolvesErrorNeverEmpty() {
        // A failed fetch must never fabricate an "empty account".
        assertEquals(HomeSectionState.Visual.ERROR,
                HomeSectionState.resolved(false, false));
        // Failure with section data that came from NOWHERE (stale call)
        // still resolves as an error — data retention is judged by the
        // activity's own state, not by this flag.
        assertEquals(HomeSectionState.Visual.ERROR,
                HomeSectionState.resolved(false, true));
    }

    @Test
    public void refreshFailureWithRetainedDataKeepsContent() {
        assertTrue("refresh failure with retained data keeps content",
                HomeSectionState.refreshFailureKeepsContent(true));
        assertFalse("failure with NO retained data → full error state",
                HomeSectionState.refreshFailureKeepsContent(false));
    }

    @Test
    public void renderedRoomsRowSurvivesRefreshFailure() {
        assertTrue("3 rendered rooms + retained data → row kept",
                HomeSectionState.keepRoomsRowOnRefreshFailure(3, true));
        assertFalse("no rendered rooms → nothing to keep",
                HomeSectionState.keepRoomsRowOnRefreshFailure(0, true));
        assertFalse("no retained data → row not kept",
                HomeSectionState.keepRoomsRowOnRefreshFailure(3, false));
    }

    // ---- activity wiring --------------------------------------------------

    @Test
    public void showErrorPreservesContentWhenDataRetained() throws Exception {
        String src = home();
        int err = src.indexOf("private void showError()");
        assertTrue("showError missing", err > 0);
        String body = src.substring(err, err + 2500);
        assertTrue("showError must consult the retention rule",
                body.contains("HomeSectionState.refreshFailureKeepsContent(data != null)"));
        // In the keep-branch, the destructive teardown calls must NOT run
        // before the early return. Anchor on the executable branch (the
        // method comment legitimately DESCRIBES the old bug).
        int keepStart = body.indexOf("if (keep) {");
        assertTrue("keep-branch missing", keepStart > 0);
        int earlyReturn = body.indexOf("return;", keepStart);
        assertTrue("keep-branch must return early", earlyReturn > keepStart);
        String keepBranch = body.substring(keepStart, earlyReturn);
        assertFalse("keep-branch must not clear the rooms row",
                keepBranch.contains("removeAllViews"));
        assertFalse("keep-branch must not hide Continue cards",
                keepBranch.contains("hide(convCard, convEmpty, taskCard, taskEmpty)"));
        assertFalse("keep-branch must not show section errors",
                keepBranch.contains("show(meetingError)") || keepBranch.contains("show(deptError)"));
        assertTrue("keep-branch must surface the non-blocking warning",
                keepBranch.contains("show(refreshWarning)"));
    }

    @Test
    public void showErrorFullErrorStateStillExistsWhenNoData() throws Exception {
        String src = home();
        int err = src.indexOf("private void showError()");
        String body = src.substring(err, err + 3000);
        int keepStart = body.indexOf("if (keep) {");
        int earlyReturn = body.indexOf("return;", keepStart);
        String full = body.substring(earlyReturn);
        assertTrue("no-data path still shows meeting error",
                full.contains("show(meetingError)"));
        assertTrue("no-data path still shows dept error",
                full.contains("show(deptError)"));
        assertTrue("no-data path still sets rooms error text",
                full.contains("home_error_section"));
    }

    @Test
    public void successfulRenderClearsStaleErrorAndEmpty() throws Exception {
        String src = home();
        // Meeting
        int m = src.indexOf("private void renderMeeting()");
        String mb = src.substring(m, src.indexOf("private void renderRooms"));
        assertTrue("renderMeeting must hide error+empty when showing card",
                mb.contains("hide(meetingEmpty, meetingError)"));
        assertTrue("empty meeting must hide the error too",
                mb.contains("hide(meetingCard, meetingError)"));
        // Department
        int d = src.indexOf("private void renderDepartment()");
        String db = src.substring(d, d + 900);
        assertTrue("renderDepartment must hide error+empty when showing card",
                db.contains("hide(deptEmpty, deptError)"));
        assertTrue("empty dept must hide the error too",
                db.contains("hide(deptCard, deptError)"));
    }

    @Test
    public void renderAllRetiresRefreshWarning() throws Exception {
        String src = home();
        int r = src.indexOf("private void renderAll(int unread)");
        String rb = src.substring(r, r + 700);
        assertTrue("a successful render must hide the refresh warning",
                rb.contains("hide(refreshWarning)"));
    }

    @Test
    public void retryFromWarningReloadsHome() throws Exception {
        String src = home();
        int w = src.indexOf("refreshWarning.setOnClickListener");
        assertTrue("warning must be tappable", w > 0);
        String wb = src.substring(w, w + 120);
        assertTrue("tapping the warning retries the load",
                wb.contains("loadHome()"));
    }

    @Test
    public void loadingAndSuccessBothClearWarning() throws Exception {
        String src = home();
        int l = src.indexOf("private void showLoading()");
        String lb = src.substring(l, src.indexOf("private void showError"));
        assertTrue("showLoading must hide the refresh warning",
                lb.contains("hide(refreshWarning)"));
    }

    // ---- composite: optional unread failure must not block content -------

    @Test
    public void unreadFailureStillDeliversCompositeContent() throws Exception {
        // HomeCompositeFetch: the unread arm parses defensively — a failed
        // or malformed unread response yields unread=0 and STILL delivers
        // 200 with the composed HomeData. Content rendering must not
        // depend on the optional endpoint.
        String src = new String(Files.readAllBytes(repoRoot().resolve(
                "android/app/src/main/java/dev/drytis/qolab/HomeCompositeFetch.java")));
        assertTrue("unread parse must be defensive (try/catch)",
                src.contains("} catch (Exception ignored) {\n                }"));
        int unreadArm = src.indexOf("getJson(\"/notifications/unread-count\"");
        assertTrue("composite must still deliver after unread settles",
                unreadArm > 0);
        String tail = src.substring(unreadArm);
        assertTrue("delivery must be unconditional in the unread callback",
                tail.contains("cb.onReady(200, full, null)"));
    }
}
