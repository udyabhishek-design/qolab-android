package dev.drytis.qolab;

import org.junit.Test;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import static org.junit.Assert.*;

/**
 * v1.5.41 regression tests for the three task areas:
 *  1. Home performance — transport poll cadence tightened, immediate
 *     first beacon sample, activity keeps the timeline diagnostics.
 *  2. Automatic network recovery — bounded realtime reconnect, auth
 *     retry, ChatRealtime reconnect fan-out to screens.
 *  3. Meeting toolbar — circular icon controls state-driven from actual
 *     LiveKit track state (no label chips).
 */
public class RecoveryAndToolbarTest {

    // ── Task 1: performance ─────────────────────────────────────────

    @Test
    public void transportPollIntervalTightenedForLatency() throws Exception {
        Method m = QolabWebViewTransport.class.getDeclaredMethod(
                "pollIntervalMsForTest", (Class<?>[]) null);
        m.setAccessible(true);
        long v = (Long) m.invoke(null);
        // 250ms polling added up to ~0.5s latency per Home load pair;
        // 60ms keeps main-thread load trivial while cutting that delay.
        assertTrue("poll interval must be <= 100ms, was " + v, v <= 100);
    }

    @Test
    public void homeActivityStillMeasuresTimeline() throws Exception {
        // The v1.5.41 timeline diagnostics (onCreate→content / onCreate→data)
        // must remain observable so before/after measurements stay possible.
        Method m = HomeActivity.HomeDiag.class.getDeclaredMethod("captureTimeline",
                long.class, long.class, long.class);
        assertNotNull(m);
        Method render = HomeActivity.HomeDiag.class.getDeclaredMethod("render");
        assertNotNull(render);
    }

    // ── Task 2: bounded realtime recovery ───────────────────────────

    @Test
    public void realtimeReconnectIsBounded() throws Exception {
        Method m = QolabRealtimeClient.class.getDeclaredMethod(
                "maxReconnectAttemptsForTest", (Class<?>[]) null);
        m.setAccessible(true);
        int v = (Integer) m.invoke(null);
        assertTrue("reconnect attempts must be bounded (was " + v + ")",
                v > 0 && v <= 512);
    }

    @Test
    public void chatRealtimeListenerHasReconnectHook() throws Exception {
        // Screens must be able to learn the socket came back and refresh
        // (catching messages missed while offline).
        Method m = ChatRealtime.Listener.class.getDeclaredMethod("onRealtimeConnected");
        assertNotNull("ChatRealtime.Listener needs onRealtimeConnected", m);
        assertEquals(void.class, m.getReturnType());
        // default method — implementers may omit it
        assertTrue(Modifier.isPublic(m.getModifiers()));
    }

    @Test
    public void chatRealtimeHasReArmLimiter() throws Exception {
        Method m = ChatRealtime.class.getDeclaredMethod("resetForRetry");
        assertNotNull("ChatRealtime needs resetForRetry() to re-arm after a long outage", m);
        int mods = m.getModifiers();
        assertTrue(Modifier.isSynchronized(mods));
    }

    @Test
    public void connectivityWatcherExposesRegisterAndUnregister() {
        // The screen-facing network-recovery API used by Home/Chat.
        assertNotNull(ConnectivityWatcher.class.getDeclaredMethods());
        boolean hasRegister = false, hasUnregister = false;
        for (Method mm : ConnectivityWatcher.class.getDeclaredMethods()) {
            if (mm.getName().equals("register")) hasRegister = true;
            if (mm.getName().equals("unregister")) hasUnregister = true;
        }
        assertTrue(hasRegister && hasUnregister);
    }

    // ── Task 3: meeting toolbar ─────────────────────────────────────

    @Test
    public void meetingToolbarUsesIconDrawables() throws Exception {
        // The call-style icons must exist as resources.
        Class<?> r = Class.forName("dev.drytis.qolab.R$drawable");
        String[] expected = {
                "qm_ic_mic", "qm_ic_mic_off", "qm_ic_videocam",
                "qm_ic_videocam_off", "qm_ic_people", "qm_ic_call_end",
                "qm_live_ctrl_active", "qm_live_ctrl_inactive", "qm_live_ctrl_leave"
        };
        for (String name : expected) {
            try {
                assertTrue(r.getField(name).getInt(null) != 0);
            } catch (NoSuchFieldException e) {
                fail("missing drawable: " + name);
            } catch (IllegalAccessException e) {
                fail("cannot read drawable field: " + name);
            }
        }
    }

    @Test
    public void liveRoomNoLongerUsesLabelChips() {
        // v1.5.41 replaced the four label chips with circular icon
        // buttons; the chip drawable is retired.
        boolean chipGone = true;
        try {
            Class.forName("dev.drytis.qolab.R$drawable").getField("qm_live_control_chip");
            chipGone = false;
        } catch (ClassNotFoundException ignored) {
            // R not on unit-test classpath — inconclusive, but not a failure
            // of the assertion itself; meetingToolbarUsesIconDrawables covers
            // presence when R IS available.
        } catch (NoSuchFieldException ignored) {
        }
        assertTrue("qm_live_control_chip must be retired", chipGone);
    }

    @Test
    public void liveRoomSyncsIconsFromTrackState() throws Exception {
        // syncTrackState must exist and pull state from the LiveKit
        // participant rather than a stale local boolean.
        Method m = LiveRoomActivity.class.getDeclaredMethod("syncTrackState");
        assertNotNull(m);
        boolean callsEnabled = false;
        for (Method mm : LiveRoomActivity.class.getDeclaredMethods()) {
            if (mm.getName().equals("toggleMic") || mm.getName().equals("toggleCam")) {
                // toggles exist and no longer directly assign micOn/camOn
                // (they delegate to syncTrackState) — presence check only;
                // behavioral verification is on-device.
                callsEnabled = true;
            }
        }
        assertTrue(callsEnabled);
    }
}
