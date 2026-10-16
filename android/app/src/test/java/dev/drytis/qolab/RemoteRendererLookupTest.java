package dev.drytis.qolab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

/**
 * v1.5.52 regression tests — the ClassCastException crash
 * (FrameLayout → SurfaceViewRenderer, logcat 2026-10-16 12:42:51,
 * onRemoteCameraOff line 750 via onEvent TrackMuted line 417).
 *
 * ROOT CAUSE: View.findViewWithTag() matches the view ITSELF first, and
 * the remote slot (FrameLayout) carries the SAME tag as the renderer
 * inside it, so the generic lookup returned the slot and the unchecked
 * cast exploded — and onRemoteCameraOn's null-check silently skipped
 * reattachment (non-null slot read as "renderer exists"). The fix is a
 * typed direct-child scan (findRemoteRenderer) with no unsafe casts.
 */
public class RemoteRendererLookupTest {

    private static final String KOTLIN = repoFile(
            "app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");

    private static String repoFile(String rel) {
        String[] roots = {"/home/coder/qolab-android/android/", "android/", "../"};
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

    private static String function(String marker) {
        int idx = KOTLIN.indexOf(marker);
        assertTrue("function not found: " + marker, idx > 0);
        int end = KOTLIN.indexOf("\n    }\n", idx);
        return KOTLIN.substring(idx, end);
    }

    // ── the crash fix ──────────────────────────────────────────────

    @Test
    public void typedChildScanExists_noUnsafeFindViewWithTagOnRenderer() {
        String fn = function("private fun findRemoteRenderer");
        // Explicit type check on children — not a generic unchecked cast.
        assertTrue("must type-check each child",
                fn.contains("is io.livekit.android.renderer.SurfaceViewRenderer"));
        assertTrue("must also match the tag",
                fn.contains("child.tag == tag"));
        assertTrue("returns null when absent",
                fn.contains("return null"));
    }

    @Test
    public void remoteCameraOffUsesTypedScan() {
        String fn = function("private fun onRemoteCameraOff");
        assertTrue("OFF must resolve the renderer via findRemoteRenderer",
                fn.contains("findRemoteRenderer(slot, tag)"));
        assertFalse("OFF must not use findViewWithTag for the renderer",
                fn.contains("findViewWithTag<io.livekit.android.renderer.SurfaceViewRenderer>"));
        // Crash path preserved semantics: destroy BEFORE remove, placeholder kept.
        int destroy = fn.indexOf("destroyRenderer(renderer)");
        int remove = fn.indexOf("slot.removeView(renderer)");
        assertTrue(destroy > 0 && remove > destroy);
        assertTrue("placeholder added when absent", fn.contains("makeTilePlaceholder"));
    }

    @Test
    public void remoteCameraOnUsesTypedScan() {
        String fn = function("private fun onRemoteCameraOn");
        assertTrue("ON must check for an existing renderer via findRemoteRenderer",
                fn.contains("findRemoteRenderer(slot, tag) == null"));
        assertFalse("ON must not use findViewWithTag for the renderer",
                fn.contains("findViewWithTag<io.livekit.android.renderer.SurfaceViewRenderer>"));
        // No duplicate registration: a fresh renderer only when none exists.
        assertTrue("fresh renderer only when absent",
                fn.contains("val renderer = makeRenderer(mirror = false)"));
        assertTrue("renderer attached with track registration",
                fn.contains("attachRenderer(track, renderer)"));
    }

    @Test
    public void unsubscribeAndUnpublishedUseTypedScan() {
        String fn = function("private fun onRemoteTrackDropped");
        assertTrue("TrackDropped must resolve the renderer via findRemoteRenderer",
                fn.contains("findRemoteRenderer(slot, tag)"));
        assertFalse("TrackDropped must not use findViewWithTag for the renderer",
                fn.contains("findViewWithTag<io.livekit.android.renderer.SurfaceViewRenderer>"));
        // TrackUnpublished funnels into the same idempotent path.
        int unpub = KOTLIN.indexOf("is RoomEvent.TrackUnpublished ->");
        String branch = KOTLIN.substring(unpub, unpub + 1200);
        assertTrue("unpublished still routes to onRemoteTrackDropped",
                branch.contains("onRemoteTrackDropped(it)"));
    }

    @Test
    public void noUntypedGenericRendererLookupRemains() {
        // No remote code path may call findViewWithTag<SurfaceViewRenderer>
        // on a slot that shares the renderer's tag. The two LOCAL lookups
        // are safe (localFrame carries no tag of its own) and are the only
        // permitted occurrences.
        int occurrences = count(KOTLIN,
                "findViewWithTag<io.livekit.android.renderer.SurfaceViewRenderer");
        int localOccurrences = count(KOTLIN,
                "localFrame.findViewWithTag<io.livekit.android.renderer.SurfaceViewRenderer");
        assertTrue("only the safe LOCAL lookups may remain (found "
                        + occurrences + " total, " + localOccurrences + " local)",
                occurrences == localOccurrences);
    }

    // ── ON → OFF → ON cycle ────────────────────────────────────────

    @Test
    public void onOffOnCycleIsSafe() {
        // OFF (TrackMuted): destroyRenderer + remove + placeholder.
        // ON (TrackUnmuted): placeholder removed, fresh renderer only
        // when findRemoteRenderer returns null (it does after OFF removed
        // the renderer — the v1.5.51 bug had the slot itself match, so ON
        // silently did nothing).
        String on = function("private fun onRemoteCameraOn");
        assertTrue("ON removes the placeholder first",
                on.contains("cam-placeholder\")?.let { slot.removeView(it) }"));
        String off = function("private fun onRemoteCameraOff");
        assertTrue("OFF removes the renderer from the slot",
                off.contains("slot.removeView(renderer)"));
        // repeated ON must not attach twice: guarded by the == null check
        assertTrue("repeated ON cannot duplicate the renderer",
                on.contains("== null"));
    }

    // ── participant leave / rejoin ─────────────────────────────────

    @Test
    public void leaveRejoinCleansAndRecreatesTile() {
        String gone = function("private fun onRemoteGone");
        assertTrue("leave removes the tile from the grid",
                gone.contains("grid.removeView(slot)"));
        assertTrue("leave releases renderers first",
                gone.contains("releaseRenderers(slot)"));
        // Rejoin: ParticipantConnected → ensureRemoteTile creates a FRESH
        // tile (idempotent lookup must not find the removed one).
        String ensure = function("private fun ensureRemoteTile");
        assertTrue("tile creation is idempotent by grid lookup",
                ensure.contains("grid.findViewWithTag<FrameLayout>(tag)?.let { return it }"));
        int conn = KOTLIN.indexOf("is RoomEvent.ParticipantConnected ->");
        String branch = KOTLIN.substring(conn, conn + 500);
        assertTrue("ParticipantConnected still creates the tile",
                branch.contains("ensureRemoteTile(it)"));
        // TrackSubscribed after rejoin goes through onRemoteVideo, which
        // rebuilds slot contents from scratch (no stale renderer).
        String rv = function("private fun onRemoteVideo");
        assertTrue("resubscribe rebuilds the slot contents",
                rv.contains("slot.removeAllViews()"));
    }

    // ── preserved contracts ────────────────────────────────────────

    @Test
    public void diagnosticLoggingPreserved() {
        assertTrue(KOTLIN.contains("classify pub sid="));
        assertTrue(KOTLIN.contains("const val TAG = \"QolabLive\""));
    }

    @Test
    public void meetingsNavigationPreserved() throws Exception {
        String home = new String(Files.readAllBytes(new File(
                "/home/coder/qolab-android/android/app/src/main/java/dev/drytis/qolab/HomeActivity.java").toPath()));
        assertTrue(home.contains("MeetingsListActivity.class"));
        assertTrue(home.contains("MeetingDetailActivity.class"));
    }

    @Test
    public void versionBumped() throws Exception {
        String g = repoFile("app/build.gradle");
        assertTrue(g.contains("versionCode 68"));
        assertTrue(g.contains("versionName \"1.5.52\""));
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
