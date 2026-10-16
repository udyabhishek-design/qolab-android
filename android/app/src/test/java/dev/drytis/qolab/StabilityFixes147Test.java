package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.5.47 — regression tests for the six approved repairs:
 *  1. Department Room content visibility on first success / recovery.
 *  2. Lobby fetching-flag cleanup on discarded deliveries.
 *  3. PATCH support in the transport (method, 419-retry, POST preserved).
 *  4. Lobby vs Chat notification routing (authoritative room mapping).
 *  5. Home badge-only resume refresh.
 *  6. Chat navigation identity fallback.
 * Plus: 405 / success:false failure interpretation contracts.
 */
public class StabilityFixes147Test {

    private static Path repoRoot() {
        Path d = new java.io.File(".").getAbsoluteFile().toPath().getParent();
        for (int i = 0; i < 8 && d != null; i++, d = d.getParent()) {
            if (Files.exists(d.resolve(".git"))) return d;
        }
        return new java.io.File(".").getAbsoluteFile().toPath().getParent();
    }

    private static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(repoRoot().resolve(rel)),
                StandardCharsets.UTF_8);
    }

    // ── Fix 1: Department Room visibility ─────────────────────────────

    @Test
    public void roomFirstSuccessRestoresContentScroll() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/RoomActivity.java");
        // The v1.5.46 regression: loadRoom hides contentScroll when
        // page==null and the success path never re-showed it. The fix must
        // sit in the SUCCESS branch, after page=parsed, before rendering.
        int parseIdx = s.indexOf("page = parsed;");
        int renderIdx = s.indexOf("renderOverview();", parseIdx);
        int restoreIdx = s.indexOf("setVisible(contentScroll, true);", parseIdx);
        assertTrue("restore must exist after 'page = parsed;'", restoreIdx > parseIdx);
        assertTrue("restore must run before rendering", restoreIdx < renderIdx);
    }

    @Test
    public void roomStillHidesContentBeforeFirstLoad() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/RoomActivity.java");
        // loading state (kept): hide content when nothing rendered yet
        assertTrue(s.contains("if (page == null) setVisible(contentScroll, false);"));
        // error state still hides everything (mutual exclusivity)
        int errIdx = s.indexOf("private void showError(boolean show)");
        int nextIdx = s.indexOf("private void renderOverview()", errIdx);
        if (nextIdx < 0) nextIdx = s.length();
        String errBlock = s.substring(errIdx, nextIdx);
        assertTrue(errBlock.contains("setVisible(contentScroll, false);"));
    }

    @Test
    public void roomRecoveryAndRetryPathsPreserved() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/RoomActivity.java");
        assertTrue(s.contains("ConnectivityWatcher.register(this"));
        assertTrue(s.contains("ConnectivityWatcher.unregister(this, netWatcher)"));
        assertTrue(s.contains("if (page == null && !fetching)"));
        assertTrue(s.contains("if (fetching || roomSlug == null) return;"));
    }

    // ── Fix 2: Lobby fetching cleanup ─────────────────────────────────

    @Test
    public void lobbyDiscardedDeliveryReleasesFetchingFlag() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/LobbyActivity.java");
        // The stale-delivery branch must release the single-flight flag on
        // the lifecycle (destroyed) path so Retry is never wedged.
        int guardIdx = s.indexOf("gen != loadGeneration.get() || isFinishing()");
        int guardClose = s.indexOf("return; // stale delivery", guardIdx);
        String branch = s.substring(guardIdx, guardClose + 60);
        assertTrue(branch, branch.contains("fetching = false;"));
    }

    @Test
    public void lobbyStaleDeliveryNeverTouchesUiState() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/LobbyActivity.java");
        // The guard branch must return BEFORE any view/flag mutation that
        // belongs to the newer load (only the flag release above runs).
        int guardIdx = s.indexOf("gen != loadGeneration.get() || isFinishing()");
        int returnIdx = s.indexOf("return;", guardIdx);
        int skeletonAfter = s.indexOf("skeleton.setVisibility", returnIdx);
        int normalPath = s.indexOf("fetching = false;\n                skeleton.setVisibility(View.GONE);");
        assertTrue(normalPath > 0); // normal path shape preserved
        assertTrue(skeletonAfter > returnIdx);
    }

    @Test
    public void lobbyShowErrorDefensivelyClearsFetching() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/LobbyActivity.java");
        int errIdx = s.indexOf("private void showError()");
        String err = s.substring(errIdx, errIdx + 400);
        assertTrue(err, err.contains("fetching = false;"));
    }

    @Test
    public void lobbySingleFlightAndGenerationGuardsPreserved() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/LobbyActivity.java");
        assertTrue(s.contains("if (fetching) return;"));
        assertTrue(s.contains("if (messages == null && !fetching)"));
        assertTrue(s.contains("ConnectivityWatcher.register(this"));
        assertTrue(s.contains("ConnectivityWatcher.unregister(this, netWatcher)"));
    }

    // ── Fix 3: transport PATCH ────────────────────────────────────────

    @Test
    public void patchKickScriptUsesPatchMethod() {
        String js = QolabWebViewTransport.buildKickScript(
                "https://example.test/x", "{}", "__k", true);
        assertTrue(js.contains("method:'PATCH'"));
        assertFalse(js.contains("method:'POST'"));
    }

    @Test
    public void postKickScriptUnchanged() {
        String js = QolabWebViewTransport.buildKickScript(
                "https://example.test/x", "{}", "__k", false);
        assertTrue(js.contains("method:'POST'"));
        // CSRF header wiring identical for both methods
        String p = QolabWebViewTransport.buildKickScript(
                "https://example.test/x", "{}", "__k2", true);
        for (String frag : new String[]{
                "XSRF-TOKEN=([^;]+)", "h['X-XSRF-TOKEN']=xsrf",
                "credentials:'same-origin'", "Accept':'application/json'"}) {
            assertTrue("missing in PATCH: " + frag, p.contains(frag));
            assertTrue("missing in POST: " + frag, js.contains(frag));
        }
    }

    @Test
    public void patch419RetryCarriesPatchThrough() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/QolabWebViewTransport.java");
        // the 419 refresh path must re-enter postJson WITH the patch flag
        assertTrue(s.contains("postJsonAfterRefresh(path, jsonBody, cb, patch)"));
        // and the delegated call preserves it
        assertTrue(s.contains("postJson(path, jsonBody, cb, patch);"));
    }

    @Test
    public void postCallersUnchangedDefaultPost() throws Exception {
        // The 3-arg overload must still exist so existing callers compile
        // and behave as POST.
        String s = read("android/app/src/main/java/dev/drytis/qolab/QolabWebViewTransport.java");
        assertTrue(s.contains("postJson(String path, String jsonBody, final Callback cb) {\n        postJson(path, jsonBody, cb, false);"));
    }

    @Test
    public void notificationMutationsUsePatchOverload() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/NotificationsActivity.java");
        int count = s.split("\\), true\\);", -1).length - 1;
        assertTrue("expected 3 PATCH call sites (single, all, row-tap), got " + count,
                count == 3);
        assertTrue(s.contains("\"/notifications/\" + it.id + \"/read\""));
        assertTrue(s.contains("\"/notifications/read-all\""));
    }

    // 405 / success:false interpretation (data-layer contract, re-pinned)
    @Test
    public void failureInterpretationContract() throws Exception {
        // 405 body from the live server (v1.5.46 failure): must be failure.
        assertFalse(NotificationsDataClient.parseSuccess(
                "{\"message\":\"The POST method is not supported...\"}"));
        // 200 {"success:false} (foreign notification): failure.
        assertFalse(NotificationsDataClient.parseSuccess("{\"success\":false}"));
        // 200 {"success":true}: the ONLY success.
        assertTrue(NotificationsDataClient.parseSuccess("{\"success\":true}"));
    }

    // ── Fix 4: routing ────────────────────────────────────────────────

    private static HomeDataClient.Room room(long id, String theme, String slug) {
        // Same shape /rooms + /mobile payloads carry for a social room.
        try {
            HomeDataClient.Room r = HomeDataClient.parseRoomForTest(
                    new org.json.JSONObject(
                            "{\"id\":" + id + ",\"name\":\"R\",\"room_slug\":\"" + slug
                            + "\",\"room_type\":\"social\",\"room_theme\":\"" + theme
                            + "\",\"members_count\":1}"));
            assertNotNull(r);
            return r;
        } catch (org.json.JSONException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void lobbyRoutingUsesAuthoritativeRoomMapping() throws Exception {
        NotificationsDataClient.Item lobbyMsg = NotificationsDataClient.parseItem(
                new org.json.JSONObject(
                        "{\"id\":1,\"type\":\"message.sent\",\"title\":\"t\","
                        + "\"body\":\"b\",\"read_at\":null,"
                        + "\"data\":{\"channel_id\":48,\"channel_name\":\"The Lobby\","
                        + "\"channel_type\":\"group\",\"is_dm\":false}}"));
        List<HomeDataClient.Room> rooms = new ArrayList<>();
        rooms.add(room(48, "lobby", "lobby"));
        assertTrue(NotificationsDataClient.isLobbyChannel(lobbyMsg, rooms));

        // Coffee/Music social rooms are NOT the lobby destination.
        rooms.clear();
        rooms.add(room(47, "coffee", "coffee-lounge"));
        assertFalse(NotificationsDataClient.isLobbyChannel(lobbyMsg, rooms));

        // id match without the lobby theme is not enough.
        rooms.clear();
        rooms.add(room(48, "music", "music-lounge"));
        assertFalse(NotificationsDataClient.isLobbyChannel(lobbyMsg, rooms));
    }

    @Test
    public void lobbyRoutingRequiresVerifiedSlug() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/NotificationsActivity.java");
        assertTrue("Lobby route must require a non-empty slug (LobbyActivity finishes without it)",
                s.contains("lobbySlug != null && !lobbySlug.isEmpty()"));
        assertTrue("must never route by title text match",
                !s.contains("getTitle().toString().contains(\"Lobby\")"));
    }

    @Test
    public void regularChatRoutingPreserved() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/NotificationsActivity.java");
        assertTrue(s.contains("new Intent(this, ChatScreenActivity.class)"));
        assertTrue(s.contains("EXTRA_MY_USER_ID, myUserId"));
    }

    // ── Fix 4b: navigate even when mark-read fails ────────────────────

    @Test
    public void rowTapNavigatesOnMarkReadFailure() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/NotificationsActivity.java");
        // in the row-tap failure branch: toast + openIfChat (no early return
        // before navigation)
        int failIdx = s.indexOf("Server rejected the mutation");
        String seg = s.substring(failIdx, s.indexOf("items = NotificationsDataClient.withRead(items, it.id);", failIdx));
        assertTrue(seg, seg.contains("openIfChat(it);"));
    }

    @Test
    public void markAllSingleFlightPreventsDuplicateTaps() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/NotificationsActivity.java");
        assertTrue(s.contains("if (markingAll) return;"));
    }

    // ── Fix 6: identity fallback ──────────────────────────────────────

    @Test
    public void identityFallsBackToDashboardWhenCacheEmpty() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/NotificationsActivity.java");
        assertTrue("must fall back to authenticated /dashboard identity",
                s.contains("t.getJson(\"/dashboard\""));
        assertTrue("must use parseDashboardPage for identity",
                s.contains("HomeDataClient.parseDashboardPage(r.body)"));
        assertTrue("must refuse navigation when identity unresolved",
                s.contains("if (uid <= 0)"));
    }

    @Test
    public void neverNavigatesWithZeroUserId() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/NotificationsActivity.java");
        // route() is only called with uid > 0 (cachedId > 0 or fetched uid > 0)
        int routeDef = s.indexOf("private void route(NotificationsDataClient.Item it, long myUserId)");
        assertTrue(routeDef > 0);
        assertTrue(s.contains("if (cachedId > 0) {\n            route(it, cachedId);"));
        assertTrue(s.contains("route(it, uid);"));
    }

    // ── Fix 5: badge-only resume refresh ──────────────────────────────

    @Test
    public void homeResumeRefreshesBadgeOnly() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/HomeActivity.java");
        int onResume = s.indexOf("protected void onResume()");
        String block = s.substring(onResume, s.indexOf("@Override", onResume + 10));
        assertTrue(block, block.contains("refreshBadgeOnly();"));
    }

    @Test
    public void badgeRefreshNeverTriggersFullRenderOrBlocksHome() throws Exception {
        String s = read("android/app/src/main/java/dev/drytis/qolab/HomeActivity.java");
        int m = s.indexOf("private void refreshBadgeOnly()");
        String body = s.substring(m, m + 2200);
        assertFalse(body, body.contains("renderAll"));
        assertFalse(body, body.contains("showLoading"));
        assertTrue(body, body.contains("if (badgeRefreshing) return;"));
        assertTrue(body, body.contains("gen != fetchGeneration.get()"));
        assertTrue(body, body.contains("LogoutFlowRules.applyUnreadResult"));
    }

    @Test
    public void badgeParseFailureKeepsCurrentBadge() throws Exception {
        // unread < 0 → return before touching the badge (contract lives in
        // NotificationsDataClient.parseUnreadCount + the guard in refreshBadgeOnly)
        assertEquals(-1, NotificationsDataClient.parseUnreadCount("garbage"));
        assertEquals(-1, NotificationsDataClient.parseUnreadCount(null));
        String s = read("android/app/src/main/java/dev/drytis/qolab/HomeActivity.java");
        assertTrue(s.contains("if (unread < 0) return; // parse failure → keep current badge"));
    }

    // ── version ───────────────────────────────────────────────────────

    @Test
    public void versionBumped() throws Exception {
        String g = read("android/app/build.gradle");
        assertTrue(g.contains("versionCode 69"));
        assertTrue(g.contains("versionName \"1.5.53\""));
    }
}
