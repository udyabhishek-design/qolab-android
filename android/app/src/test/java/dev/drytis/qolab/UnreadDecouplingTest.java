package dev.drytis.qolab;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * v1.5.45 regression tests — Home startup performance.
 *
 * Pins the unread-count decoupling: primary Home content is delivered
 * the moment /dashboard + /rooms are valid; the optional unread request
 * is independent and can only patch the badge / Today indicator.
 */
public class UnreadDecouplingTest {

    private static Path repoRoot() {
        Path d = new File(".").getAbsoluteFile().toPath().getParent();
        for (int i = 0; i < 8 && d != null; i++, d = d.getParent()) {
            if (Files.exists(d.resolve(".git"))) return d;
        }
        return new File(".").getAbsoluteFile().toPath().getParent();
    }

    private static String fetch() throws Exception {
        return new String(Files.readAllBytes(repoRoot().resolve(
                "android/app/src/main/java/dev/drytis/qolab/HomeCompositeFetch.java")));
    }

    private static String home() throws Exception {
        return new String(Files.readAllBytes(repoRoot().resolve(
                "android/app/src/main/java/dev/drytis/qolab/HomeActivity.java")));
    }

    @Test
    public void primaryDeliveryIsNotOrderedAfterUnreadRequest() throws Exception {
        String src = fetch();
        int settle = src.indexOf("final Runnable settle");
        int deliver = src.indexOf("cb.onReady(200, data, null);", settle);
        int unreadReq = src.indexOf("getJson(\"/notifications/unread-count\"", settle);
        assertTrue("primary delivery missing", deliver > 0);
        assertTrue("unread request missing", unreadReq > 0);
        assertTrue("onReady(200) must execute BEFORE the unread request starts",
                deliver < unreadReq);
    }

    @Test
    public void unreadFailureNeverFailsTheComposite() throws Exception {
        String src = fetch();
        int unreadArm = src.indexOf("getJson(\"/notifications/unread-count\"");
        String tail = src.substring(unreadArm);
        // Defensive parse: exceptions and non-2xx yield parseOk=false and
        // the callback STILL fires (caller keeps current value). There is
        // no path from the unread arm back into cb.onReady.
        assertTrue(tail.contains("catch (Exception ignored)"));
        assertFalse("unread arm must not call cb.onReady",
                tail.contains("cb.onReady("));
    }

    @Test
    public void unreadPatchUpdatesOnlyBadgeAndTodayIndicator() throws Exception {
        String src = home();
        int patch = src.indexOf("unreadUserId, unread, parseOk");
        assertTrue("unread callback wiring missing", patch > 0);
        // The patch lambda body runs to its closing runOnUiThread block —
        // bound it by the last statement in the body instead of a fragile
        // closing-token search.
        String body = src.substring(patch,
                src.indexOf("}));", src.indexOf("todayUpdates.setText", patch)));
        assertTrue("must patch the badge",
                body.contains("notificationBadge.setVisibility"));
        assertTrue("must patch the Today strip",
                body.contains("todayUpdates.setText"));
        // The patch path must NOT renderAll / showLoading / showError.
        assertFalse("must not re-render everything",
                body.contains("renderAll("));
        assertFalse("must not re-show skeletons",
                body.contains("showLoading()"));
        assertFalse("must not show error states",
                body.contains("showError()"));
    }

    @Test
    public void staleUnreadCallbacksAreDropped() throws Exception {
        String src = home();
        // Generation bump on every new loadHome + logout.
        assertTrue(src.contains("fetchGeneration.incrementAndGet()"));
        assertTrue(src.contains("REQUEST_GENERATION.incrementAndGet()"));
        int patch = src.indexOf("LogoutFlowRules.applyUnreadResult");
        assertTrue("unread application must be gated by the pure rules",
                patch > 0);
        // destroyed-activity guard
        int cbStart = src.indexOf("unreadUserId, unread, parseOk");
        String cb = src.substring(cbStart, cbStart + 900);
        assertTrue("destroyed activity must ignore the callback",
                cb.contains("isFinishing() || isDestroyed()"));
    }

    @Test
    public void refreshFailurePreservationStillIntact() throws Exception {
        // v1.5.44 behavior must survive the perf change.
        String src = home();
        int err = src.indexOf("private void showError()");
        String body = src.substring(err, err + 2500);
        assertTrue("keep-content rule still wired",
                body.contains("HomeSectionState.refreshFailureKeepsContent(data != null)"));
        int renderAll = src.indexOf("private void renderAll(int unread)");
        assertTrue("successful render still retires the refresh warning",
                src.substring(renderAll, renderAll + 400).contains("hide(refreshWarning)"));
    }

    @Test
    public void unreadFailureDoesNotBlockHome() throws Exception {
        // Pure-rule cross-check: parseOk=false is never applied, and the
        // fetch composite always delivers primary data first (covered
        // above); here we pin the rule itself.
        assertFalse(LogoutFlowRules.applyUnreadResult(5, 5, false, false));
        assertTrue(LogoutFlowRules.applyUnreadResult(5, 5, false, true));
    }
}
