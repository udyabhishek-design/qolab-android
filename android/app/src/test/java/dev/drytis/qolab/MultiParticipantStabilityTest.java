package dev.drytis.qolab;

import org.junit.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.junit.Assert.*;

/**
 * v1.5.49 regression tests — multi-participant meeting stability.
 *
 * CONFIRMED ROOT CAUSE (from the v1.5.48 diagnostic): every renderer
 * teardown called renderer.release() WITHOUT first unregistering the
 * renderer as a VideoSink from its track (VideoTrack.removeRenderer).
 * LiveKit keeps the sink registered on the released EGL surface — the
 * multi-participant blank/frozen screen, only visible when frames from
 * another participant actually flow.
 *
 * Plus: participant tiles must exist independently of camera state.
 */
public class MultiParticipantStabilityTest {

    private static String read(String rel) throws IOException {
        return new String(Files.readAllBytes(
                Paths.get(System.getProperty("user.dir"), "..", rel).normalize()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── Renderer lifecycle: unregister BEFORE release ─────────────────

    @Test
    public void destroyRendererRemovesSinkBeforeRelease() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        int d = src.indexOf("private fun destroyRenderer");
        assertTrue("destroyRenderer must exist", d > 0);
        int remove = src.indexOf("track.removeRenderer(renderer)", d);
        int release = src.indexOf("renderer.release()", d);
        assertTrue("destroyRenderer must unregister the sink from the track", remove > 0);
        assertTrue("destroyRenderer must release the renderer", release > 0);
        assertTrue("removeRenderer MUST precede release (dead-sink fix)",
                remove < release);
    }

    @Test
    public void rendererTrackRegistryTracksAssociations() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        // The association is captured at attach time and consumed at
        // destroy time — never guessed from tags or stale references.
        assertTrue("registry must exist",
                src.contains("rendererTracks ="));
        assertTrue("attach must register the association",
                src.contains("rendererTracks[renderer] = track"));
        assertTrue("destroy must consume the association",
                src.contains("rendererTracks.remove(renderer)"));
        int onDestroy = src.indexOf("override fun onDestroy()");
        assertTrue("onDestroy must clear the registry",
                src.indexOf("rendererTracks.clear()", onDestroy) > 0);
    }

    @Test
    public void everyTeardownGoesThroughDestroyRenderer() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        // syncLocalPreview OFF branch, onRemoteCameraOff, and the
        // recursive sweep must all use the canonical helper. No bare
        // .release() outside destroyRenderer (except inside it).
        int first = src.indexOf("private fun destroyRenderer");
        int second = src.indexOf("private fun dp(");
        String body = src.substring(first, second);
        // All release() calls within the activity body must be inside
        // destroyRenderer itself.
        int relIdx = -1;
        int count = 0;
        while ((relIdx = body.indexOf(".release()", relIdx + 1)) >= 0) {
            count++;
            // every .release() must be preceded (within the method) by
            // removeRenderer, i.e. inside destroyRenderer or nowhere else
            assertTrue("bare release() outside destroyRenderer at offset " + relIdx,
                    body.substring(0, relIdx).contains("track.removeRenderer(renderer)")
                            && relIdx < body.indexOf("private fun", body.indexOf("track.removeRenderer(renderer)")));
        }
        assertTrue("destroyRenderer used by local OFF branch",
                src.indexOf("destroyRenderer(existing)", src.indexOf("private fun syncLocalPreview")) > 0);
        assertTrue("destroyRenderer used by remote camera OFF",
                src.indexOf("destroyRenderer(renderer)", src.indexOf("private fun onRemoteCameraOff")) > 0);
        assertTrue("releaseRenderers delegates to destroyRenderer",
                src.indexOf("destroyRenderer(child)", src.indexOf("private fun releaseRenderers")) > 0);
    }

    @Test
    public void teardownIsIdempotent() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        // removeRenderer on an unregistered sink and release() on a
        // released renderer are both guarded — repeated toggles, double
        // disconnects and post-destroy events are safe.
        int d = src.indexOf("private fun destroyRenderer");
        String body = src.substring(d, src.indexOf("private fun", d + 10));
        assertTrue(body.contains("renderer ?: return"));
        assertTrue(body.contains("try { track.removeRenderer"));
        assertTrue(body.contains("try { renderer.release() }"));
    }

    // ── Participant grid independent of camera state ──────────────────

    @Test
    public void participantConnectedCreatesTileWithPlaceholder() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        int ev = src.indexOf("is RoomEvent.ParticipantConnected -> {");
        assertTrue(ev > 0);
        assertTrue("ParticipantConnected must ensure a tile",
                src.indexOf("ensureRemoteTile(it)", ev) > 0);
        int e = src.indexOf("private fun ensureRemoteTile");
        String body = src.substring(e, src.indexOf("private fun", e + 10));
        assertTrue("ensureRemoteTile is idempotent (returns existing)",
                body.contains("grid.findViewWithTag<FrameLayout>(tag)?.let { return it }"));
        assertTrue("new tile starts with a camera-off placeholder",
                body.contains("live_cam_placeholder"));
    }

    @Test
    public void existingParticipantsSeededAfterConnect() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        int c = src.indexOf("private fun connect()");
        assertTrue("connect must seed existing remote participants",
                src.indexOf("seedExistingParticipants()", c) > 0);
        int s = src.indexOf("private fun seedExistingParticipants");
        String body = src.substring(s, src.indexOf("private fun", s + 10));
        assertTrue("sweep iterates room.remoteParticipants",
                body.contains("r.remoteParticipants.values.forEach"));
        assertTrue("sweep creates tiles for every existing participant",
                body.contains("ensureRemoteTile(id)"));
        assertTrue("sweep attaches video when already publishing unmuted",
                body.contains("onRemoteVideo(track, id, p.name, alreadyMuted = false)"));
    }

    @Test
    public void unsubscribePreservesTileDisconnectRemovesIt() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        // TrackUnsubscribed → drop video, keep tile + placeholder.
        int un = src.indexOf("is RoomEvent.TrackUnsubscribed -> {");
        assertTrue("TrackUnsubscribed must route to onRemoteTrackDropped",
                src.indexOf("onRemoteTrackDropped(it)", un) > 0);
        int drop = src.indexOf("private fun onRemoteTrackDropped");
        String dropBody = src.substring(drop, src.indexOf("private fun onRemoteGone"));
        assertTrue("dropped video must show placeholder (tile preserved)",
                dropBody.contains("live_cam_placeholder"));
        assertFalse("dropped video must NOT remove the tile",
                dropBody.contains("grid.removeView"));
        // ParticipantDisconnected → remove tile entirely.
        int disc = src.indexOf("is RoomEvent.ParticipantDisconnected -> {");
        assertTrue("ParticipantDisconnected must route to onRemoteGone",
                src.indexOf("onRemoteGone(it)", disc) > 0);
        int gone = src.indexOf("private fun onRemoteGone");
        String goneBody = src.substring(gone, src.indexOf("private fun makeRenderer"));
        assertTrue("gone tile must be removed from the grid",
                goneBody.contains("grid.removeView(slot)"));
    }

    @Test
    public void noDuplicateRenderersOnResubscribe() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        int v = src.indexOf("private fun onRemoteVideo");
        String body = src.substring(v, src.indexOf("private fun onRemoteCameraOff"));
        // Resubscribe replaces slot contents instead of stacking.
        assertTrue(body.contains("releaseRenderers(slot)"));
        assertTrue(body.contains("slot.removeAllViews()"));
        assertTrue(body.contains("ensureRemoteTile(identity)"));
        assertTrue("attach goes through the registry",
                body.contains("attachRenderer(track, renderer)"));
    }

    @Test
    public void cameraToggleFailuresAreSurfacedNotSwallowed() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        int cam = src.indexOf("private fun toggleCam");
        String body = src.substring(cam, src.indexOf("private fun togglePeople"));
        assertTrue("camera toggle catch must log the failure",
                body.contains("Log.w(TAG, \"camera toggle failed"));
        assertTrue("camera toggle catch must keep the icon honest",
                body.indexOf("syncTrackState()", body.indexOf("catch")) > 0);
        assertTrue("camera toggle catch must show user feedback",
                body.contains("R.string.live_cam_toggle_failed"));
        int mic = src.indexOf("private fun toggleMic");
        String micBody = src.substring(mic, src.indexOf("private fun toggleCam"));
        assertTrue("mic toggle catch must show user feedback",
                micBody.contains("R.string.live_mic_toggle_failed"));
    }

    @Test
    public void diagnosticLoggingTagExists_noTokenLogging() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        assertTrue(src.contains("private const val TAG = \"QolabLive\""));
        // Never log tokens or credentials.
        assertFalse(src.contains("Log.*token"));
        assertFalse(src.contains("Log.d(TAG, \"token"));
    }

    @Test
    public void leaveAndDestroyPathsUnchanged() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt");
        int leave = src.indexOf("private fun leave()");
        String leaveBody = src.substring(leave, src.indexOf("override fun onRequestPermissionsResult"));
        assertTrue("leave stays single-flight", leaveBody.contains("if (leaving) return"));
        assertTrue("leave disconnects the room", leaveBody.contains("room?.disconnect()"));
        assertTrue("leave posts the contract endpoint",
                leaveBody.contains("/meetings/$meetingId/leave"));
        int back = src.indexOf("override fun onBackPressed()");
        assertTrue("Back = leave()", src.indexOf("leave()", back) > 0);
        int destroy = src.indexOf("override fun onDestroy()");
        String destroyBody = src.substring(destroy, src.indexOf("private fun releaseRenderers"));
        assertTrue("onDestroy disconnects unconditionally",
                destroyBody.contains("try { room?.disconnect() }"));
        assertTrue("onDestroy releases all renderers",
                destroyBody.contains("releaseRenderers(root)"));
    }
}
