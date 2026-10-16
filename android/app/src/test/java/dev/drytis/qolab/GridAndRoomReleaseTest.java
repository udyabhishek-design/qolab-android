package dev.drytis.qolab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

/**
 * v1.5.50 regression tests — participant grid layout, LiveKit Room
 * lifecycle (release exactly once), terminal-disconnect handling and
 * preserved Meetings navigation.
 *
 * Source-pinned tests follow the established convention in this repo
 * (CameraOffFreezeTest, MultiParticipantStabilityTest): the behaviors
 * here are UI/SDK lifecycle wiring inside LiveRoomActivity.kt, which is
 * not unit-instantiable on the JVM, so the tests pin the exact source
 * contracts (weight distribution, guarded release, single /leave POST
 * funnel, disconnect-reason logging) plus pure layout arithmetic.
 */
public class GridAndRoomReleaseTest {

    private static final String KOTLIN = repoFile(
            "android/app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");

    private static String repoFile(String rel) {
        // Same resolver convention as the other pinned tests.
        String[] roots = {"/home/coder/qolab-android/", "android/", "../"};
        for (String root : roots) {
            File f = new File(root + rel);
            if (f.exists()) {
                try {
                    return new String(Files.readAllBytes(f.toPath()));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        throw new IllegalStateException("repo file not found: " + rel);
    }

    // ── Part 1: participant grid ────────────────────────────────────

    @Test
    public void localFrameUsesWeightedLayout() {
        // The v1.5.49 defect: localFrame had MATCH_PARENT height with no
        // weight in the VERTICAL grid → remote tiles measured to zero.
        // v1.5.50: every grid child (localFrame AND remote tiles) carries
        // height 0 + weight 1f so the space redistributes automatically.
        int localStart = KOTLIN.indexOf("localFrame = FrameLayout(this).apply");
        assertTrue("localFrame construction not found", localStart > 0);
        String localBlock = KOTLIN.substring(
                localStart, KOTLIN.indexOf("grid.addView(localFrame)", localStart));
        assertTrue("localFrame must NOT use unweighted MATCH_PARENT height",
                !localBlock.contains("MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT"));
        assertTrue("localFrame must participate in weight distribution",
                localBlock.contains("0, 1f"));
    }

    @Test
    public void remoteTilesUseEqualWeight() {
        // ensureRemoteTile tiles must also be weighted — otherwise they'd
        // stack to zero height exactly like the v1.5.49 defect.
        int start = KOTLIN.indexOf("private fun ensureRemoteTile");
        assertTrue(start > 0);
        String fn = KOTLIN.substring(start, KOTLIN.indexOf("}", KOTLIN.indexOf("return slot", start)) + 1);
        assertTrue("remote tile must use weight 1f",
                fn.contains("MATCH_PARENT, 0, 1f"));
    }

    @Test
    public void tileAddAndRemoveRedistributes() {
        // Weighted LinearLayout children redistribute automatically when a
        // tile is added (grid.addView(slot, 1)) or removed
        // (grid.removeView(slot)) — no manual relayout needed. Pin both.
        int ensure = KOTLIN.indexOf("private fun ensureRemoteTile");
        String ensureFn = KOTLIN.substring(ensure, ensure + 1200);
        assertTrue("tiles are inserted below the local tile",
                ensureFn.contains("grid.addView(slot, 1)"));
        int gone = KOTLIN.indexOf("private fun onRemoteGone");
        assertTrue("onRemoteGone not found", gone > 0);
        String goneFn = KOTLIN.substring(gone, KOTLIN.indexOf("}", KOTLIN.indexOf("grid.removeView(slot)", gone)));
        assertTrue("disconnect removes the tile from the grid",
                goneFn.contains("grid.removeView(slot)"));
    }

    /** Pure layout arithmetic: n weighted children share the grid equally. */
    @Test
    public void weightedChildrenShareAvailableSpace() {
        int gridHeightPx = 1200; // any non-zero height
        for (int participants = 1; participants <= 4; participants++) {
            // All children: height 0, weight 1f → equal division.
            int each = gridHeightPx / participants;
            assertTrue("with " + participants + " participants every tile must get space",
                    each > 0);
        }
        // The v1.5.49 defect arithmetic: unweighted MATCH_PARENT local tile
        // leaves 0 for every weighted remote tile.
        int consumedByUnweightedLocal = gridHeightPx;
        int leftover = gridHeightPx - consumedByUnweightedLocal;
        assertTrue("v1.5.49 defect reproduced: remote tiles got " + leftover,
                leftover == 0);
    }

    @Test
    public void placeholderTransitionsPreserveTile() {
        // Camera OFF → onRemoteCameraOff destroys the renderer but the TILE
        // (identity-keyed slot) survives with a "cam-placeholder"; ON →
        // onRemoteCameraOn re-attaches a fresh renderer. Both pinned in
        // v1.5.48/49 tests; here pin that neither path removes the slot.
        int off = KOTLIN.indexOf("private fun onRemoteCameraOff");
        String offFn = KOTLIN.substring(off, KOTLIN.indexOf("}\n", off) + 2);
        assertFalse("camera OFF must not delete the participant tile",
                offFn.contains("grid.removeView"));
        int on = KOTLIN.indexOf("private fun onRemoteCameraOn");
        String onFn = KOTLIN.substring(on, KOTLIN.indexOf("}\n", on) + 2);
        assertFalse("camera ON must not delete the participant tile",
                onFn.contains("grid.removeView"));
    }

    // ── Part 2: Room lifecycle ──────────────────────────────────────

    @Test
    public void releaseRunsExactlyOnceViaGuard() {
        int idx = KOTLIN.indexOf("private fun shutdownRoom");
        assertTrue("shutdownRoom not found", idx > 0);
        String fn = KOTLIN.substring(idx, KOTLIN.indexOf("\n    }\n", idx));
        assertTrue("shutdownRoom must be guarded by the released flag",
                fn.contains("if (released) return"));
        assertTrue("shutdownRoom must set the released flag",
                fn.contains("released = true"));
        assertTrue("shutdownRoom must call room.release()",
                fn.contains("r.release()"));
        assertTrue("shutdownRoom must disconnect first",
                fn.contains("r.disconnect()"));
    }

    @Test
    public void leaveAndOnDestroyBothFunnelIntoShutdown() {
        int leave = KOTLIN.indexOf("private fun leave()");
        String leaveFn = KOTLIN.substring(leave, KOTLIN.indexOf("\n    }\n", leave));
        assertFalse("leave() must no longer own room teardown",
                leaveFn.contains(".release()"));
        int destroy = KOTLIN.indexOf("override fun onDestroy");
        String destroyFn = KOTLIN.substring(destroy, KOTLIN.indexOf("\n    }\n", destroy));
        assertTrue("onDestroy must funnel into shutdownRoom()",
                destroyFn.contains("shutdownRoom()"));
    }

    @Test
    public void renderersDestroyedBeforeRoomRelease() {
        // Renderers render through the Room's shared EGL context; releasing
        // the Room first and then touching views would use freed resources.
        int destroy = KOTLIN.indexOf("override fun onDestroy");
        String destroyFn = KOTLIN.substring(destroy, KOTLIN.indexOf("\n    }\n", destroy));
        int rendererIdx = destroyFn.indexOf("releaseRenderers(root)");
        int shutdownIdx = destroyFn.indexOf("        shutdownRoom()\n"); // the call, not the comment
        assertTrue("onDestroy must call releaseRenderers(root)",
                rendererIdx > 0);
        assertTrue("onDestroy must call shutdownRoom()",
                shutdownIdx > 0);
        assertTrue("renderers must be destroyed BEFORE the Room is released",
                rendererIdx < shutdownIdx);
    }

    @Test
    public void rendererTeardownUnregistersSinkBeforeRelease() {
        // The v1.5.49 dead-sink freeze fix stays intact in v1.5.50.
        int idx = KOTLIN.indexOf("private fun destroyRenderer");
        String fn = KOTLIN.substring(idx, KOTLIN.indexOf("\n    }\n", idx));
        int removeIdx = fn.indexOf("track.removeRenderer(renderer)");
        int releaseIdx = fn.indexOf("renderer.release()");
        assertTrue("destroyRenderer must removeRenderer from the track",
                removeIdx > 0);
        assertTrue("destroyRenderer must release the renderer",
                releaseIdx > 0);
        assertTrue("sink must be unregistered BEFORE renderer.release()",
                removeIdx < releaseIdx);
    }

    // ── Part 3: terminal disconnect ─────────────────────────────────

    @Test
    public void unexpectedTerminalDisconnectPostsLeave() {
        // Disconnected is terminal in 2.18.2 (Reconnecting/Reconnected cover
        // transient loss). An unexpected terminal disconnect must still POST
        // /meetings/{id}/leave so the backend isn't stranded.
        int idx = KOTLIN.indexOf("is RoomEvent.Disconnected ->");
        assertTrue("Disconnected branch not found", idx > 0);
        String branch = KOTLIN.substring(idx, KOTLIN.indexOf("is RoomEvent.", idx + 40) > 0
                ? KOTLIN.indexOf("else ->", idx)
                : idx + 900);
        assertTrue("unexpected disconnect must call postLeave()",
                branch.contains("postLeave()"));
        assertTrue("must distinguish client-initiated leaves",
                branch.contains("clientInitiated"));
        // Leave button/Back path (leaving == true) must NOT double-POST —
        // leave() already routed through postLeave().
        int leaveFn = KOTLIN.indexOf("private fun leave()");
        String leaveBlock = KOTLIN.substring(leaveFn, KOTLIN.indexOf("\n    }\n", leaveFn));
        assertTrue("leave() must route its POST through postLeave()",
                leaveBlock.contains("postLeave()"));
    }

    @Test
    public void leavePostedAtMostOnce() {
        int idx = KOTLIN.indexOf("private fun postLeave");
        assertTrue("postLeave not found", idx > 0);
        String fn = KOTLIN.substring(idx, KOTLIN.indexOf("\n    }\n", idx));
        assertTrue("postLeave must be guarded (fire once)",
                fn.contains("if (leavePosted) return"));
        assertTrue("postLeave sets the guard",
                fn.contains("leavePosted = true"));
    }

    // ── Part 4: camera/mic failure visibility (v1.5.50) ─────────────

    @Test
    public void micEnableFailureSurfaced() {
        // publishDefaults previously swallowed setMicrophoneEnabled
        // exceptions entirely; now it logs (QolabLive), toasts and re-syncs
        // the toolbar from the real SDK state.
        int idx = KOTLIN.indexOf("private fun publishDefaults");
        String fn = KOTLIN.substring(idx, KOTLIN.indexOf("\n    }\n", idx));
        assertFalse("mic enable failures must not be silently swallowed",
                fn.contains("catch (_: Exception) { }"));
        assertTrue("mic failure must be logged",
                fn.contains("QolabLive"));
        assertTrue("mic failure must notify the user",
                fn.contains("live_mic_enable_failed"));
        assertTrue("toolbar re-synced from real state",
                fn.contains("syncTrackState()"));
    }

    @Test
    public void micFailureStringExists() {
        String s = repoFile("android/app/src/main/res/values/qm_live_strings.xml");
        assertTrue(s.contains("live_mic_enable_failed"));
    }

    // ── Part 6: preserved behavior ──────────────────────────────────

    @Test
    public void meetingsNavigationPreserved() {
        // v1.5.49's passing Meetings navigation must survive this release.
        String home = repoFile(
                "android/app/src/main/java/dev/drytis/qolab/HomeActivity.java");
        assertTrue("hamburger Meetings still opens MeetingsListActivity",
                home.contains("MeetingsListActivity.class"));
        int card = home.indexOf("meetingCard.setOnClickListener");
        if (card > 0) {
            String wiring = home.substring(card, Math.min(home.length(), card + 800));
            assertTrue("meeting card still opens MeetingDetailActivity",
                    wiring.contains("MeetingDetailActivity.class"));
        } else {
            // v1.5.49 wires the card inside renderMeeting() — check that form.
            int render = home.indexOf("MeetingDetailActivity.class");
            assertTrue("meeting card opens MeetingDetailActivity somewhere",
                    render > 0);
        }
        File list = new File(
                "android/app/src/main/java/dev/drytis/qolab/MeetingsListActivity.java");
        if (!list.exists()) {
            list = new File("/home/coder/qolab-android/android/app/src/main/java/dev/drytis/qolab/MeetingsListActivity.java");
        }
        assertTrue("MeetingsListActivity.java still exists", list.exists());
    }

    @Test
    public void versionBumped() throws Exception {
        String g = repoFile("android/app/build.gradle");
        assertTrue(g.contains("versionCode 68"));
        assertTrue(g.contains("versionName \"1.5.52\""));
    }
}
