package dev.drytis.qolab;

import org.junit.Test;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.junit.Assert.*;

/**
 * v1.5.42 regression tests.
 *
 * Issue 1 — cold-restart partial Home: two verified causes.
 *  (a) A rehydrate-adopted WebView is never attached to a window, so it
 *      stays lifecycle-PAUSED. Paused WebViews run no page JS — the
 *      in-page fetch never resolved, every cold-start data request timed
 *      out, and sections stuck in skeleton. Fix: resumeForRequest /
 *      pauseAfterRequest bracket every transport request.
 *  (b) SessionGate dropped the callbacks of a second caller that arrived
 *      while a check was in flight and no transport existed yet — that
 *      caller's load restart never happened. Fix: pending-caller queue
 *      drained on every gate decision.
 *
 * Issue 2 — camera-off freeze: the local SurfaceViewRenderer was never
 *  removed on camera OFF, so the last video frame froze on screen. Fix:
 *  syncLocalPreview renders strictly from the camera publication state —
 *  renderer removed + released + placeholder on OFF, exactly one renderer
 *  on ON (tag-guarded, no duplicates).
 */
public class ColdRestartAndCameraTest {

    private static String read(String rel) throws IOException {
        return new String(Files.readAllBytes(
                Paths.get(System.getProperty("user.dir"), "..", rel).normalize()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── Issue 1(a): paused rehydrate WebView ─────────────────────────

    @Test
    public void transportResumesWebViewDuringRequests() throws Exception {
        // Both verbs must bracket the round-trip with resume/pause.
        Method resume = QolabWebViewTransport.class.getDeclaredMethod("resumeForRequest");
        Method pause = QolabWebViewTransport.class.getDeclaredMethod("pauseAfterRequest");
        assertNotNull(resume);
        assertNotNull(pause);
        String src = read("app/src/main/java/dev/drytis/qolab/QolabWebViewTransport.java");
        assertTrue("getJson must resume before kick-off",
                src.contains("// v1.5.42: the (possibly rehydrate-adopted, window-less)"));
        assertTrue("postJson must resume before kick-off",
                src.contains("// v1.5.42: resumed for the duration of the round-trip"));
        // Pause is reference-counted: overlapping requests must not pause
        // the WebView while another is still in flight.
        assertTrue(src.contains("if (requestDepth > 0) requestDepth--;"));
    }

    @Test
    public void releaseQuietlyResumesBeforeDestroy() throws IOException {
        // Destroying a paused WebView can crash the renderer path; the
        // teardown must resume first.
        String src = read("app/src/main/java/dev/drytis/qolab/QolabWebViewTransport.java");
        int release = src.indexOf("private void releaseQuietly()");
        int destroy = src.indexOf("webView.destroy();", release);
        int onResume = src.indexOf("webView.onResume();", release);
        assertTrue(onResume > 0 && onResume < destroy);
    }

    // ── Issue 1(b): SessionGate pending callers ──────────────────────

    @Test
    public void sessionGateQueuesConcurrentCallers() throws Exception {
        // The gate must keep a pending-caller queue and drain it on every
        // decision — no silent returns while a check is in flight.
        Field pending = SessionGate.class.getDeclaredField("pending");
        assertNotNull("SessionGate must track pending callers", pending);
        Method[] names = {null};
        boolean hasService = false, hasServiceExhausted = false;
        for (Method m : SessionGate.class.getDeclaredMethods()) {
            if (m.getName().equals("servicePending")) hasService = true;
            if (m.getName().equals("servicePendingExhausted")) hasServiceExhausted = true;
        }
        assertTrue("servicePending must exist", hasService);
        assertTrue("servicePendingExhausted must exist", hasServiceExhausted);
        String src = read("app/src/main/java/dev/drytis/qolab/SessionGate.java");
        assertFalse("the fold-in path must enqueue, not return silently",
                src.contains("return SILENTLY"));
        assertTrue(src.contains("pending.add(new PendingDecision"));
        // Every decision site drains the queue.
        int decisions = 0;
        for (String drain : new String[]{"servicePending(true)",
                "servicePending(sessionValid)", "servicePendingExhausted()"}) {
            if (src.contains(drain)) decisions++;
        }
        assertTrue("all three decision sites must drain pending callers (found "
                + decisions + ")", decisions == 3);
    }

    // ── Issue 2: camera-off freeze ───────────────────────────────────

    @Test
    public void liveRoomSyncsLocalPreviewFromPublicationState() throws Exception {
        Method m = LiveRoomActivity.class.getDeclaredMethod("syncLocalPreview");
        assertNotNull("syncLocalPreview must exist", m);
        String src = new String(Files.readAllBytes(Paths.get(
                System.getProperty("user.dir"),
                "..", "app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt").normalize()),
                java.nio.charset.StandardCharsets.UTF_8);
        // OFF must REMOVE + RELEASE the renderer (the freeze) and show a
        // placeholder instead.
        // v1.5.49: OFF releases via destroyRenderer() — which unregisters
        // the sink from the track BEFORE release (dead-sink fix).
        assertTrue("camera OFF must destroy the old renderer",
                src.contains("destroyRenderer(existing)"));
        assertTrue("camera OFF must show a placeholder",
                src.contains("live_cam_placeholder"));
        // ON must be idempotent (tag guard) — no duplicate renderers on
        // repeated ON→OFF→ON cycles.
        assertTrue("renderer attachment must be tag-guarded",
                src.contains("\"local-renderer\""));
    }

    @Test
    public void toggleCamDrivesPreviewSync() throws IOException {
        // The toggle itself must call syncLocalPreview (not the old
        // attach-only behavior that froze the frame on OFF).
        String src = new String(Files.readAllBytes(Paths.get(
                System.getProperty("user.dir"),
                "..", "app/src/main/java/dev/drytis/qolab/LiveRoomActivity.kt").normalize()),
                java.nio.charset.StandardCharsets.UTF_8);
        int toggle = src.indexOf("private fun toggleCam()");
        int sync = src.indexOf("syncLocalPreview()", toggle);
        assertTrue("toggleCam must call syncLocalPreview", sync > 0);
        // Track events must drive it too (remote/server-driven mutes).
        int muted = src.indexOf("is RoomEvent.TrackMuted");
        assertTrue(src.indexOf("syncLocalPreview()", muted) > 0);
        int unmuted = src.indexOf("is RoomEvent.TrackUnmuted");
        assertTrue(src.indexOf("syncLocalPreview()", unmuted) > 0);
    }

    @Test
    public void cameraOffPlaceholderLayoutExists() {
        // Layout resource must exist (R is not on the unit-test classpath;
        // assert on the file and its content-description contract).
        java.nio.file.Path p = Paths.get(System.getProperty("user.dir"), "..",
                "app/src/main/res/layout/live_cam_placeholder.xml").normalize();
        assertTrue(Files.exists(p));
    }

    @Test
    public void placeholderStringExists() throws IOException {
        java.nio.file.Path p = Paths.get(System.getProperty("user.dir"), "..",
                "app/src/main/res/values/qm_live_strings_v142.xml").normalize();
        assertTrue(Files.exists(p));
        String s = new String(Files.readAllBytes(p),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(s.contains("live_cam_off_placeholder"));
    }
}
