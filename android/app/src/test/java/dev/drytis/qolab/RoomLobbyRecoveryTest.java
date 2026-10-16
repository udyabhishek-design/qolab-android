package dev.drytis.qolab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * v1.5.46 — R1 (Department Room + Lobby network recovery) and R2 wiring
 * regression tests. These are source-contract pins (the same style the
 * project has used since v1.5.39 to guard activities without Robolectric
 * activity launching).
 */
public class RoomLobbyRecoveryTest {

    private static final String ROOM =
            "android/app/src/main/java/dev/drytis/qolab/RoomActivity.java";
    private static final String LOBBY =
            "android/app/src/main/java/dev/drytis/qolab/LobbyActivity.java";
    private static final String NOTIF =
            "android/app/src/main/java/dev/drytis/qolab/NotificationsActivity.java";
    private static final String HOME =
            "android/app/src/main/java/dev/drytis/qolab/HomeActivity.java";

    /** Resolves the repo root by walking up to .git (tests may run from
     *  app/ or android/ — same convention as SocialRoomAccessTest). */
    private static java.nio.file.Path repoRoot() {
        java.nio.file.Path d = new java.io.File(".").getAbsoluteFile()
                .toPath().getParent();
        for (int i = 0; i < 8 && d != null; i++, d = d.getParent()) {
            if (java.nio.file.Files.exists(d.resolve(".git"))) return d;
        }
        return new java.io.File(".").getAbsoluteFile().toPath().getParent();
    }

    private static String read(String rel) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(
                repoRoot().resolve(rel)),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── R1: watcher registration + lifecycle ──────────────────────────

    @Test
    public void roomRegistersConnectivityWatcherInOnCreate()
            throws Exception {
        String s = read(ROOM);
        assertTrue("RoomActivity must register ConnectivityWatcher",
                s.contains("ConnectivityWatcher.register(this"));
        assertTrue("RoomActivity must unregister in onDestroy",
                s.contains("ConnectivityWatcher.unregister(this, netWatcher)"));
        assertTrue(s.contains("protected void onDestroy()"));
    }

    @Test
    public void roomRecoveryRetriesOnlyWithoutDataAndInFlight()
            throws Exception {
        String s = read(ROOM);
        // The exact Home-proven predicate: no page + not fetching.
        assertTrue("recovery must gate on page == null && !fetching",
                s.contains("if (page == null && !fetching)"));
    }

    @Test
    public void lobbyRegistersConnectivityWatcherInOnCreate()
            throws Exception {
        String s = read(LOBBY);
        assertTrue("LobbyActivity must register ConnectivityWatcher",
                s.contains("ConnectivityWatcher.register(this"));
        assertTrue("LobbyActivity must unregister in onDestroy",
                s.contains("ConnectivityWatcher.unregister(this, netWatcher)"));
    }

    @Test
    public void lobbyRecoveryRetriesOnlyWithoutDataAndInFlight()
            throws Exception {
        String s = read(LOBBY);
        assertTrue("recovery must gate on messages == null && !fetching",
                s.contains("if (messages == null && !fetching)"));
    }

    // ── R1: single-flight + generation guards ─────────────────────────

    @Test
    public void roomHasSingleFlightAndGenerationGuard() throws Exception {
        String s = read(ROOM);
        assertTrue("loadRoom single-flight guard",
                s.contains("if (fetching || roomSlug == null) return;"));
        assertTrue("generation guard at delivery",
                s.contains("gen != loadGeneration.get()"));
        assertTrue("stale delivery drop",
                s.contains("return; // stale delivery — superseded or destroyed"));
    }

    @Test
    public void lobbyHasSingleFlightAndGenerationGuard() throws Exception {
        String s = read(LOBBY);
        assertTrue("loadDiscussion single-flight guard",
                s.contains("if (fetching) return;"));
        assertTrue("generation guard at delivery",
                s.contains("gen != loadGeneration.get()"));
    }

    @Test
    public void lobbyRenderClearsStaleErrorAndRetry() throws Exception {
        String s = read(LOBBY);
        // v1.5.44 class of bug: success after error must hide error/retry.
        String render = s.substring(s.indexOf("private void renderMessages()"));
        assertTrue(render, render.contains("errorView.setVisibility(View.GONE)"));
        assertTrue(render, render.contains("retryView.setVisibility(View.GONE)"));
    }

    @Test
    public void lobbyLoadingStateIsSymmetric() throws Exception {
        String s = read(LOBBY);
        String load = s.substring(s.indexOf("private void showLoadingState()"));
        assertTrue(load, load.contains("skeleton.setVisibility(View.VISIBLE)"));
        assertTrue(load, load.contains("errorView.setVisibility(View.GONE)"));
        assertTrue(load, load.contains("retryView.setVisibility(View.GONE)"));
    }

    @Test
    public void roomErrorClearedRestoresContentScroll() throws Exception {
        String s = read(ROOM);
        // v1.5.46 fix: showError(true) hides contentScroll; clearing the
        // error with a valid page must bring it back.
        String err = s.substring(s.indexOf("private void showError(boolean show)"));
        assertTrue(err.contains("if (page != null) setVisible(contentScroll, true);"));
    }

    @Test
    public void roomVisibleLoadingStateOnRetry() throws Exception {
        String s = read(ROOM);
        String load = s.substring(s.indexOf("private void loadRoom()"));
        assertTrue("retry must show skeletons again",
                load.contains("setVisible(meetingsSkeleton, true)"));
        assertTrue("retry must hide stale content when no page yet",
                load.contains("if (page == null) setVisible(contentScroll, false);"));
    }

    // ── R1: Home/Chat recovery untouched ──────────────────────────────

    @Test
    public void homeAndChatRecoveryStillRegistered() throws Exception {
        String home = read(HOME);
        assertTrue(home.contains("ConnectivityWatcher.register(this"));
        String chat = read(
                "android/app/src/main/java/dev/drytis/qolab/ChatListActivity.java");
        assertTrue(chat.contains("ConnectivityWatcher.register(this"));
        String screen = read(
                "android/app/src/main/java/dev/drytis/qolab/ChatScreenActivity.java");
        assertTrue(screen.contains("ConnectivityWatcher.register(this"));
    }

    // ── R2: bell wiring ───────────────────────────────────────────────

    @Test
    public void homeBellOpensNotificationsActivity() throws Exception {
        String s = read(HOME);
        assertTrue("bell must start NotificationsActivity",
                s.contains("new Intent(this, NotificationsActivity.class)"));
        // Badge behaviour preserved: still driven by renderAll/unread patch.
        assertFalse("bell must no longer be a Coming Soon placeholder",
                s.contains("notifications.setOnClickListener(v -> showComingSoon"));
    }

    @Test
    public void homeStillPatchesBadgeWithoutFullRender() throws Exception {
        String s = read(HOME);
        assertTrue("unread patch must not call renderAll",
                s.contains("Never a full renderAll"));
        assertTrue(s.contains("notificationBadge.setVisibility(unread > 0"));
    }

    // ── R2: notifications screen contract ─────────────────────────────

    @Test
    public void notificationsActivityWiredToRealEndpoints() throws Exception {
        String s = read(NOTIF);
        assertTrue("fetches the existing list endpoint",
                s.contains("\"/notifications?page=1\""));
        assertTrue("mark-read via existing PATCH endpoint",
                s.contains("\"/notifications/\" + it.id + \"/read\""));
        assertTrue("mark-all via existing PATCH endpoint",
                s.contains("\"/notifications/read-all\""));
        assertFalse("must never fetch a non-existent detail endpoint",
                s.contains("/notifications/{id}\""));
    }

    @Test
    public void notificationsDropsPostLogoutCallbacks() throws Exception {
        String s = read(NOTIF);
        assertTrue("must capture the global generation",
                s.contains("HomeActivity.currentRequestGeneration()"));
        assertTrue("must compare it in the stale check",
                s.contains("currentRequestGeneration() != sessionGen"));
    }

    @Test
    public void notificationsUiUpdatesOnlyAfterServerConfirmation()
            throws Exception {
        String s = read(NOTIF);
        // The UI change (withRead/allRead) may only run after
        // parseSuccess(r.body) is true — the success branch.
        int markReadIdx = s.indexOf("private void markRead(final");
        String segment = s.substring(markReadIdx);
        int okIdx = segment.indexOf("parseSuccess(r.body)");
        int withReadIdx = segment.indexOf("withRead(items, it.id)");
        assertTrue(okIdx >= 0 && withReadIdx > okIdx);
    }

    @Test
    public void notificationsPreservesRowsOnTransientAppendFailure()
            throws Exception {
        String s = read(NOTIF);
        String loadNext = s.substring(s.indexOf("private void loadNext()"),
                s.indexOf("private void refreshFirstPage()"));
        assertTrue(loadNext,
                loadNext.contains("// v1.5.44 rule") // preserved-rows rule
                        || loadNext.contains("PRESERVE loaded rows"));
        assertFalse(loadNext.contains("showError()"));
    }

    @Test
    public void notificationsDeepLinkOnlyForMessageSentWithChannel()
            throws Exception {
        String s = read(NOTIF);
        assertTrue("must gate deep-link on isChatOpenable",
                s.contains("NotificationsDataClient.isChatOpenable(it)"));
        assertTrue("deep-link opens native ChatScreenActivity",
                s.contains("new Intent(this, ChatScreenActivity.class)"));
    }

    @Test
    public void notificationsManifestRegistered() throws Exception {
        String m = read("android/app/src/main/AndroidManifest.xml");
        assertTrue(m.contains(".NotificationsActivity"));
    }

    @Test
    public void notificationsLayoutHasAllStates() throws Exception {
        String l = read("android/app/src/main/res/layout/activity_notifications.xml");
        assertTrue(l.contains("@+id/notif_skeleton"));
        assertTrue(l.contains("@+id/notif_error"));
        assertTrue(l.contains("@+id/notif_retry"));
        assertTrue(l.contains("@+id/notif_empty"));
        assertTrue(l.contains("@+id/notif_list"));
        assertTrue(l.contains("@+id/notif_mark_all"));
        assertTrue(l.contains("@+id/notif_btn_back"));
    }

    @Test
    public void versionBumped() throws Exception {
        String g = read("android/app/build.gradle");
        assertTrue(g.contains("versionCode 67"));
        assertTrue(g.contains("versionName \"1.5.51\""));
    }
}
