package dev.drytis.qolab;

import org.junit.Test;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.junit.Assert.*;

/**
 * v1.5.48 regression tests — frozen camera frame on camera-off.
 *
 * CONFIRMED ROOT CAUSE: LiveKit Android 2.18.2 does NOT unpublish the
 * camera track on setCameraEnabled(false) — the publication stays, is
 * marked muted, capture stops, and the SurfaceViewRenderer keeps its
 * final frame. v1.5.42 keyed the local slot on track EXISTENCE; the fix
 * keys it on the publication's MUTED state via LocalVideoSlotRules.
 */
public class CameraOffFreezeTest {

    private static String read(String rel) throws IOException {
        return new String(Files.readAllBytes(
                Paths.get(System.getProperty("user.dir"), "..", rel).normalize()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── Pure decision logic (meaningful state behavior) ───────────────

    @Test
    public void localSlot_mutedPublicationShowsPlaceholder() {
        // Camera OFF: publication exists, track exists, but muted.
        assertEquals(LocalVideoSlotRules.SlotState.PLACEHOLDER,
                LocalVideoSlotRules.localSlot(true, true, true));
    }

    @Test
    public void localSlot_unmutedPublicationIsLive() {
        // Camera ON: publication exists, live track, not muted.
        assertEquals(LocalVideoSlotRules.SlotState.LIVE,
                LocalVideoSlotRules.localSlot(true, true, false));
    }

    @Test
    public void localSlot_missingPublicationShowsPlaceholder() {
        // Never joined with camera (no publication at all).
        assertEquals(LocalVideoSlotRules.SlotState.PLACEHOLDER,
                LocalVideoSlotRules.localSlot(false, true, false));
    }

    @Test
    public void localSlot_missingTrackShowsPlaceholder() {
        // Publication exists but its track object is gone.
        assertEquals(LocalVideoSlotRules.SlotState.PLACEHOLDER,
                LocalVideoSlotRules.localSlot(true, false, false));
    }

    @Test
    public void remoteSlot_mutedShowsPlaceholder() {
        assertEquals(LocalVideoSlotRules.SlotState.PLACEHOLDER,
                LocalVideoSlotRules.remoteSlot(true, true));
    }

    @Test
    public void remoteSlot_unmutedIsLive() {
        assertEquals(LocalVideoSlotRules.SlotState.LIVE,
                LocalVideoSlotRules.remoteSlot(true, false));
    }

    @Test
    public void remoteSlot_missingTrackShowsPlaceholder() {
        // Unsubscribed / departed participant.
        assertEquals(LocalVideoSlotRules.SlotState.PLACEHOLDER,
                LocalVideoSlotRules.remoteSlot(false, false));
    }

    @Test
    public void slotStateIsMutuallyExclusive() {
        // Every input combination resolves to exactly one state; no
        // combined/undefined outcomes exist.
        for (boolean pub : new boolean[]{false, true}) {
            for (boolean track : new boolean[]{false, true}) {
                for (boolean muted : new boolean[]{false, true}) {
                    LocalVideoSlotRules.SlotState s =
                            LocalVideoSlotRules.localSlot(pub, track, muted);
                    assertNotNull(s);
                    assertTrue(s == LocalVideoSlotRules.SlotState.LIVE
                            || s == LocalVideoSlotRules.SlotState.PLACEHOLDER);
                }
            }
        }
    }

    // ── Activity wiring ───────────────────────────────────────────────

    @Test
    public void syncLocalPreviewDecidesFromPublicationMutedState() throws Exception {
        // The local slot decision must read the publication's muted flag
        // (LiveKit keeps muted tracks published — existence is not enough).
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        int sync = src.indexOf("private fun syncLocalPreview()");
        assertTrue(sync > 0);
        int decision = src.indexOf("LocalVideoSlotRules.localSlot(", sync);
        assertTrue("syncLocalPreview must route through LocalVideoSlotRules",
                decision > 0);
        assertTrue("decision must read publication muted state",
                src.contains("publicationMuted = ltp?.muted ?: true"));
        // A missing publication must default to muted (placeholder), never
        // to live video.
        assertTrue(src.contains("ltp?.muted ?: true"));
    }

    @Test
    public void cameraOffReleasesRendererAndShowsPlaceholder() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        int sync = src.indexOf("private fun syncLocalPreview()");
        int elseBranch = src.indexOf("} else {", src.indexOf("SlotState.LIVE", sync));
        // v1.5.49: OFF branch destroys the renderer via the canonical
        // helper (unregisters the video sink BEFORE release) + removes it,
        // then shows the placeholder.
        int destroy = src.indexOf("destroyRenderer(existing)", elseBranch);
        int remove = src.indexOf("localFrame.removeView(existing)", elseBranch);
        int placeholder = src.indexOf("live_cam_placeholder", elseBranch);
        assertTrue(destroy > 0 && remove > 0 && placeholder > 0);
        assertTrue("destroy must precede removal", destroy < remove);
        // The canonical helper itself: removeRenderer before release.
        int helper = src.indexOf("private fun destroyRenderer");
        int sinkRemove = src.indexOf("track.removeRenderer(renderer)", helper);
        int rel = src.indexOf("renderer.release()", helper);
        assertTrue(sinkRemove > 0 && rel > 0 && sinkRemove < rel);
    }

    @Test
    public void cameraOnIsTagGuarded_noDuplicateRenderers() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        // Repeated ON states re-attach nothing: the guard checks for an
        // existing renderer by tag before creating one.
        assertTrue(src.contains("\"local-renderer\""));
        int sync = src.indexOf("private fun syncLocalPreview()");
        int guard = src.indexOf("findViewWithTag", sync);
        // v1.5.49: attach goes through attachRenderer() (registers the
        // renderer→track association).
        int add = src.indexOf("attachRenderer(videoTrack!!, renderer)", sync);
        assertTrue("tag guard must run before attaching", guard > 0 && guard < add);
        // ON must also clear a stale placeholder from a prior OFF.
        int stalePh = src.indexOf(
                "localFrame.findViewWithTag<View>(\"cam-placeholder\")?.let { localFrame.removeView(it) }",
                sync);
        assertTrue("ON must remove a stale placeholder", stalePh > 0);
    }

    @Test
    public void togglesAndTrackEventsDrivePreviewSync() throws IOException {
        // UI state follows LiveKit mute/unmute EVENTS (no polling), and
        // the toggle path drives the same sync.
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        int toggle = src.indexOf("private fun toggleCam()");
        assertTrue("toggleCam must call syncLocalPreview",
                src.indexOf("syncLocalPreview()", toggle) > 0);
        int muted = src.indexOf("is RoomEvent.TrackMuted");
        assertTrue(src.indexOf("syncLocalPreview()", muted) > 0);
        int unmuted = src.indexOf("is RoomEvent.TrackUnmuted");
        assertTrue(src.indexOf("syncLocalPreview()", unmuted) > 0);
    }

    @Test
    public void remoteMuteShowsPlaceholder_notFrozenFrame() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        // Remote camera OFF (their publication muted) must release the
        // renderer and show a placeholder in the slot.
        int off = src.indexOf("private fun onRemoteCameraOff");
        assertTrue(off > 0);
        int release = src.indexOf("renderer.release()", off);
        int ph = src.indexOf("live_cam_placeholder", off);
        assertTrue("remote OFF must release renderer", release > 0);
        assertTrue("remote OFF must show placeholder", ph > 0);
        // Wired from the TrackMuted event for remote participants.
        int ev = src.indexOf("is RoomEvent.TrackMuted");
        assertTrue("remote TrackMuted must route to onRemoteCameraOff",
                src.indexOf("onRemoteCameraOff(", ev) > 0);
    }

    @Test
    public void remoteUnmuteReattachesRenderer() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        int on = src.indexOf("private fun onRemoteCameraOn");
        assertTrue(on > 0);
        // Placeholder removed, fresh renderer attached (old was released).
        assertTrue("remote ON must remove placeholder",
                src.indexOf("slot.findViewWithTag<View>(\"cam-placeholder\")?.let { slot.removeView(it) }",
                        on) > 0);
        int ev = src.indexOf("is RoomEvent.TrackUnmuted");
        assertTrue("remote TrackUnmuted must route to onRemoteCameraOn",
                src.indexOf("onRemoteCameraOn(ev)", ev) > 0);
    }

    @Test
    public void remoteUnsubscribeAndDisconnectDropSlot() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        int gone = src.indexOf("private fun onRemoteGone");
        assertTrue(gone > 0);
        // Slot removed from grid with renderers released.
        assertTrue("onRemoteGone must release renderers",
                src.indexOf("releaseRenderers(slot)", gone) > 0);
        assertTrue("onRemoteGone must remove the slot",
                src.indexOf("grid.removeView(slot)", gone) > 0);
        // Wired from TrackUnsubscribed AND ParticipantDisconnected.
        int unsub = src.indexOf("is RoomEvent.TrackUnsubscribed");
        int disc = src.indexOf("is RoomEvent.ParticipantDisconnected");
        assertTrue("TrackUnsubscribed must route to onRemoteGone",
                src.indexOf("onRemoteGone(", unsub) > 0);
        assertTrue("ParticipantDisconnected must route to onRemoteGone",
                src.indexOf("onRemoteGone(", disc) > 0);
    }

    @Test
    public void remoteSlotsKeyedByIdentity_noStackedTiles() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        int remote = src.indexOf("private fun onRemoteVideo");
        // A re-subscribe for the same identity replaces slot contents —
        // repeated toggle cycles cannot stack duplicate tiles.
        assertTrue("remote slots must be tagged by identity",
                src.contains("private fun remoteSlotTag(identity: String) = \"remote-${identity}\""));
        // A re-subscribe for the same identity replaces slot contents —
        // repeated toggle cycles cannot stack duplicate tiles.
        int cleanup = src.indexOf("releaseRenderers(slot)", remote);
        assertTrue("re-subscribe must not stack tiles", cleanup > 0);
    }

    @Test
    public void destroyReleasesAllRenderers_andLeavePathUntouched() throws IOException {
        // onDestroy recursively releases every SurfaceViewRenderer (local
        // + remote slots) and disconnects the room; leave() keeps its
        // single-flight guard + POST /meetings/{id}/leave + finish.
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        int destroy = src.indexOf("override fun onDestroy()");
        assertTrue(destroy > 0);
        assertTrue("onDestroy must release renderers",
                src.indexOf("releaseRenderers(root)", destroy) > 0);
        assertTrue("onDestroy must disconnect the room",
                src.indexOf("room?.disconnect()", destroy) > 0);
        int leave = src.indexOf("private fun leave()");
        assertTrue(leave > 0);
        assertTrue("leave stays single-flight",
                src.indexOf("if (leaving) return", leave) > 0);
        assertTrue("leave posts the contract endpoint",
                src.contains("transport.postJson(\"/meetings/$meetingId/leave\""));
        // Back = the same leave path.
        int back = src.indexOf("override fun onBackPressed()");
        assertTrue("Back must call leave()",
                src.indexOf("leave()", back) > 0);
    }
}
