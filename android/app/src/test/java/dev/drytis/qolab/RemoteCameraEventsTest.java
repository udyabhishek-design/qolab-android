package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

/**
 * v1.5.51 regression tests — consistent remote-camera-video event
 * classification, TrackUnpublished handling, visible camera-off
 * participant tiles, preserved renderer-cleanup ordering and Meetings
 * navigation.
 */
public class RemoteCameraEventsTest {

    private static final String KOTLIN = repoFile(
            "app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
    private static final String RULES = repoFile(
            "app/src/main/java/dev/drytis/qolab/RemoteCameraEventRules.kt");

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

    // ── §1: consistent remote-video identification ─────────────────

    @Test
    public void remoteVideoCameraSource() {
        // Normal case: remote video publication with CAMERA source.
        assertTrue(RemoteCameraEventRules.isRemoteCameraVideo(
                RemoteCameraEventRules.TrackKind.REMOTE_VIDEO,
                RemoteCameraEventRules.SourceKind.CAMERA));
    }

    @Test
    public void remoteVideoUnknownOrMissingSource() {
        // THE v1.5.50 DEFECT: web peers / ordering races deliver
        // UNKNOWN or missing source metadata. Subscribe keyed on track
        // TYPE, mute keyed on source == CAMERA → frozen frame. The fix
        // treats a remote VIDEO track as camera video regardless of
        // source metadata.
        assertTrue(RemoteCameraEventRules.isRemoteCameraVideo(
                RemoteCameraEventRules.TrackKind.REMOTE_VIDEO,
                RemoteCameraEventRules.SourceKind.UNKNOWN));
        assertTrue(RemoteCameraEventRules.isRemoteCameraVideo(
                RemoteCameraEventRules.TrackKind.REMOTE_VIDEO,
                RemoteCameraEventRules.SourceKind.MISSING));
    }

    @Test
    public void nonVideoTracksNeverClassifyAsCameraVideo() {
        // Audio / local / missing tracks must never enter the remote
        // camera-video branches (mic mute must not drop a video tile).
        for (RemoteCameraEventRules.TrackKind kind : new RemoteCameraEventRules.TrackKind[]{
                RemoteCameraEventRules.TrackKind.REMOTE_AUDIO,
                RemoteCameraEventRules.TrackKind.LOCAL_VIDEO,
                RemoteCameraEventRules.TrackKind.LOCAL_AUDIO,
                RemoteCameraEventRules.TrackKind.MISSING,
                RemoteCameraEventRules.TrackKind.OTHER}) {
            for (RemoteCameraEventRules.SourceKind src : RemoteCameraEventRules.SourceKind.values()) {
                assertFalse(kind + "+" + src + " must not classify as remote camera video",
                        RemoteCameraEventRules.isRemoteCameraVideo(kind, src));
            }
        }
    }

    @Test
    public void handlersUseTheConsistentPredicate() {
        // Both remote TrackMuted and TrackUnmuted branches route through
        // isRemoteCameraVideo — the source-only == CAMERA gate is gone.
        int muted = KOTLIN.indexOf("is RoomEvent.TrackMuted ->");
        int unmuted = KOTLIN.indexOf("is RoomEvent.TrackUnmuted ->");
        assertTrue(muted > 0 && unmuted > muted);
        String mutedBranch = KOTLIN.substring(muted, unmuted);
        assertTrue("remote TrackMuted must use isRemoteCameraVideo",
                mutedBranch.contains("isRemoteCameraVideo(ev.publication)"));
        assertFalse("source-only gate must be gone from TrackMuted",
                mutedBranch.contains("ev.publication?.source == io.livekit.android.room.track.Track.Source.CAMERA"));
        String unmutedBranch = KOTLIN.substring(unmuted, KOTLIN.indexOf("is RoomEvent.TrackUnsubscribed ->", unmuted));
        assertTrue("remote TrackUnmuted must use isRemoteCameraVideo",
                unmutedBranch.contains("isRemoteCameraVideo(ev.publication)"));
        assertFalse("source-only gate must be gone from TrackUnmuted",
                unmutedBranch.contains("ev.publication?.source == io.livekit.android.room.track.Track.Source.CAMERA"));
    }

    // ── §2: TrackUnpublished handling ──────────────────────────────

    @Test
    public void trackUnpublishedHandled() {
        int idx = KOTLIN.indexOf("is RoomEvent.TrackUnpublished ->");
        assertTrue("TrackUnpublished branch must exist", idx > 0);
        String branch = KOTLIN.substring(idx, idx + 1200);
        assertTrue("unpublish must drop the video renderer",
                branch.contains("onRemoteTrackDropped(it)"));
        assertTrue("unpublish only for remote participants",
                branch.contains("is io.livekit.android.room.participant.LocalParticipant"));
        assertTrue("unpublish only for video tracks",
                branch.contains("is RemoteVideoTrack"));
    }

    @Test
    public void duplicateCleanupIsSafe() {
        // onRemoteTrackDropped is idempotent: null-guarded renderer
        // lookup + placeholder existence check → a following
        // TrackUnsubscribed for the same track is a no-op, and it can
        // never touch another identity's renderer (identity-keyed tag).
        int idx = KOTLIN.indexOf("private fun onRemoteTrackDropped");
        String fn = KOTLIN.substring(idx, KOTLIN.indexOf("\n    }\n", idx));
        assertTrue("renderer lookup must be null-guarded",
                fn.contains("if (renderer != null)"));
        assertTrue("placeholder only added when absent",
                fn.contains("\"cam-placeholder\") == null"));
        assertTrue("slot is identity-keyed",
                fn.contains("remoteSlotTag(identity)"));
    }

    @Test
    public void participantDisconnectedStillRemovesTile() {
        int idx = KOTLIN.indexOf("is RoomEvent.ParticipantDisconnected ->");
        String branch = KOTLIN.substring(idx, idx + 400);
        assertTrue("ParticipantDisconnected still routes to onRemoteGone",
                branch.contains("onRemoteGone(it)"));
        int gone = KOTLIN.indexOf("private fun onRemoteGone");
        String goneFn = KOTLIN.substring(gone, KOTLIN.indexOf("\n    }\n", gone));
        assertTrue("onRemoteGone removes the tile from the grid",
                goneFn.contains("grid.removeView(slot)"));
    }

    // ── §3: visible camera-off placeholders ────────────────────────

    @Test
    public void placeholderDerivesInitialAndName() {
        RemoteCameraEventRules.PlaceholderContent c =
                RemoteCameraEventRules.placeholderFor("Ada Lovelace", "You", "Camera is off");
        assertEquals("A", c.getInitial());
        assertEquals("Ada Lovelace", c.getName());
        assertEquals("Camera is off", c.getCaption());
    }

    @Test
    public void placeholderFallsBack() {
        RemoteCameraEventRules.PlaceholderContent c =
                RemoteCameraEventRules.placeholderFor(null, "You", "Camera is off");
        assertEquals("Y", c.getInitial());
        assertEquals("You", c.getName());
        RemoteCameraEventRules.PlaceholderContent blank =
                RemoteCameraEventRules.placeholderFor("  ", "You", "Camera is off");
        assertEquals("Y", blank.getInitial());
        RemoteCameraEventRules.PlaceholderContent digits =
                RemoteCameraEventRules.placeholderFor("42nd User", "You", "Camera is off");
        assertEquals("4", digits.getInitial());
        RemoteCameraEventRules.PlaceholderContent emoji =
                RemoteCameraEventRules.placeholderFor("🎤 Host", "You", "Camera is off");
        // Emoji alone is not a letter — the first LETTER wins ("H").
        assertEquals("H", emoji.getInitial());
        RemoteCameraEventRules.PlaceholderContent onlyEmoji =
                RemoteCameraEventRules.placeholderFor("🎤", "You", "Camera is off");
        // No letter/digit anywhere → "?"
        assertEquals("?", onlyEmoji.getInitial());
    }

    @Test
    public void visibleTileLayoutExists() {
        String layout = repoFile("app/src/main/res/layout/live_tile_placeholder.xml");
        assertTrue("tile has an avatar initial view",
                layout.contains("tile_avatar"));
        assertTrue("tile shows the display name",
                layout.contains("tile_name"));
        assertTrue("tile has a camera-off caption",
                layout.contains("tile_caption"));
        assertTrue("tile uses the camera-off icon",
                layout.contains("qm_ic_videocam_off"));
        assertTrue("tile has a distinguishable background",
                layout.contains("qm_bg_video_tile"));
    }

    @Test
    public void allPlaceholderSitesUseVisibleTile() {
        // No site may still inflate the invisible v1.5.50 placeholder
        // inside LiveRoomActivity (layout file itself may remain unused).
        assertFalse("no live_cam_placeholder inflation left in the activity",
                KOTLIN.contains("R.layout.live_cam_placeholder"));
        int make = KOTLIN.indexOf("private fun makeTilePlaceholder");
        assertTrue("makeTilePlaceholder must exist", make > 0);
        assertTrue("ensureRemoteTile uses the visible placeholder",
                KOTLIN.substring(KOTLIN.indexOf("private fun ensureRemoteTile"),
                        KOTLIN.indexOf("private fun seedExistingParticipants"))
                        .contains("makeTilePlaceholder"));
        String offFn = KOTLIN.substring(KOTLIN.indexOf("private fun onRemoteCameraOff"),
                KOTLIN.indexOf("private fun onRemoteCameraOn"));
        assertTrue("onRemoteCameraOff uses the visible placeholder",
                offFn.contains("makeTilePlaceholder"));
        String localSync = KOTLIN.substring(KOTLIN.indexOf("private fun syncLocalPreview"),
                KOTLIN.indexOf("private fun attachLocalPreview"));
        assertTrue("local camera-off uses the visible placeholder",
                localSync.contains("makeTilePlaceholder"));
    }

    @Test
    public void tileBackgroundSeparatesParticipants() {
        String bg = repoFile("app/src/main/res/drawable/qm_bg_video_tile.xml");
        assertTrue("tile background differs from the qm_ink root (#1A1A1A)",
                !bg.contains("1A1A1A"));
        assertTrue("tile has a visible border for separation",
                bg.contains("stroke"));
        String avatar = repoFile("app/src/main/res/drawable/qm_bg_tile_avatar.xml");
        assertTrue("avatar circle exists",
                avatar.contains("oval"));
    }

    // ── grid behavior preserved (v1.5.50) ──────────────────────────

    @Test
    public void weightedGridUnchanged() {
        int ensure = KOTLIN.indexOf("private fun ensureRemoteTile");
        String ensureFn = KOTLIN.substring(ensure, ensure + 1000);
        assertTrue("remote tiles keep weight 1f",
                ensureFn.contains("MATCH_PARENT, 0, 1f"));
        int localStart = KOTLIN.indexOf("localFrame = FrameLayout(this).apply");
        String localBlock = KOTLIN.substring(localStart,
                KOTLIN.indexOf("grid.addView(localFrame)", localStart));
        assertTrue("localFrame stays weighted",
                localBlock.contains("0, 1f"));
    }

    // ── renderer cleanup ordering preserved ────────────────────────

    @Test
    public void rendererCleanupOrderingPreserved() {
        int idx = KOTLIN.indexOf("private fun destroyRenderer");
        String fn = KOTLIN.substring(idx, KOTLIN.indexOf("\n    }\n", idx));
        int removeIdx = fn.indexOf("track.removeRenderer(renderer)");
        int releaseIdx = fn.indexOf("renderer.release()");
        assertTrue(removeIdx > 0 && releaseIdx > 0);
        assertTrue("sink unregistered BEFORE release (v1.5.49 contract)",
                removeIdx < releaseIdx);
    }

    // ── diagnostics (§4) ───────────────────────────────────────────

    @Test
    public void developmentDiagnosticsPresent() {
        // QolabLive-tagged lifecycle logging exists; classification logs
        // sid/source/kind; no token/credential strings are logged.
        assertTrue(KOTLIN.contains("classify pub sid="));
        assertTrue(KOTLIN.contains("remote track unpublished sid="));
        // The TAG constant stays QolabLive (dev log filter).
        assertTrue(KOTLIN.contains("const val TAG = \"QolabLive\""));
        assertFalse("no token logging in the live room",
                KOTLIN.contains("Log.d(TAG, \"token"));
    }

    // ── Meetings navigation preserved ──────────────────────────────

    @Test
    public void meetingsNavigationUntouched() throws Exception {
        String home = new String(Files.readAllBytes(new File(
                "/home/coder/qolab-android/android/app/src/main/java/dev/drytis/qolab/HomeActivity.java").toPath()));
        // v1.5.53: hamburger Meetings deep-links into the department
        // room; the meeting detail / join chain is unchanged.
        assertTrue(home.contains("openDepartmentRoomMeetings()"));
        assertTrue(home.contains("MeetingDetailActivity.class"));
    }

    @Test
    public void versionBumped() throws Exception {
        String g = repoFile("app/build.gradle");
        assertTrue(g.contains("versionCode 69"));
        assertTrue(g.contains("versionName \"1.5.53\""));
    }
}
