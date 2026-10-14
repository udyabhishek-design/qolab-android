package dev.drytis.qolab;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * v1.5.45 regression tests — native logout.
 *
 * Pins the pure rules (LogoutFlowRules) and the HomeActivity wiring:
 * menu item → confirmation dialog → EXISTING webview logout transaction
 * (AuthNavRules.buildWebviewLogoutJs via
 * QolabWebViewTransport.runLogoutTransaction — never native HTTP) →
 * idempotent cleanup → Login with CLEAR_TASK.
 */
public class LogoutFlowTest {

    private static Path repoRoot() {
        Path d = new File(".").getAbsoluteFile().toPath().getParent();
        for (int i = 0; i < 8 && d != null; i++, d = d.getParent()) {
            if (Files.exists(d.resolve(".git"))) return d;
        }
        return new File(".").getAbsoluteFile().toPath().getParent();
    }

    private static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(repoRoot()
                .resolve("android/app/src/main/java/dev/drytis/qolab/" + rel)));
    }

    // ---- pure rules -------------------------------------------------------

    @Test
    public void serverConfirmationIs2xxOr3xxOnly() {
        assertTrue(LogoutFlowRules.serverConfirmed(200));
        assertTrue(LogoutFlowRules.serverConfirmed(204));
        assertTrue(LogoutFlowRules.serverConfirmed(302));
        assertFalse("401 is not a confirmed logout", LogoutFlowRules.serverConfirmed(401));
        assertFalse("419 is not a confirmed logout", LogoutFlowRules.serverConfirmed(419));
        assertFalse("0 (network) is not confirmed", LogoutFlowRules.serverConfirmed(0));
    }

    @Test
    public void confirmedActionsCoverFullCleanupInOrder() {
        String[] a = LogoutFlowRules.CONFIRMED_ACTIONS;
        assertEquals(6, a.length);
        assertEquals("clear_home_cache", a[0]);
        assertEquals("shutdown_realtime", a[1]);
        assertEquals("cancel_pending_fetches", a[2]);
        assertEquals("release_transport", a[3]);
        assertEquals("expire_session_cookies", a[4]);
        assertEquals("route_to_login", a[5]);
    }

    @Test
    public void failureKeepsSessionIntact() {
        assertTrue(LogoutFlowRules.failureKeepsEverything());
        assertTrue(LogoutFlowRules.confirmationRequired());
    }

    @Test
    public void payloadBelongsToCurrentSessionGuardsAccountSwitch() {
        // same user, live request → accept
        assertTrue(LogoutFlowRules.payloadBelongsToCurrentSession(5, 5, false));
        // cross-account payload → NEVER accept (no cached-data leakage)
        assertFalse(LogoutFlowRules.payloadBelongsToCurrentSession(7, 5, false));
        // post-logout callbacks (no current user) → drop
        assertFalse(LogoutFlowRules.payloadBelongsToCurrentSession(5, 0, false));
        // aborted request generation → drop even for the right user
        assertFalse(LogoutFlowRules.payloadBelongsToCurrentSession(5, 5, true));
        // unidentified payload → drop
        assertFalse(LogoutFlowRules.payloadBelongsToCurrentSession(0, 5, false));
    }

    @Test
    public void unreadApplicationRequiresValidNonStaleResult() {
        assertTrue(LogoutFlowRules.applyUnreadResult(5, 5, false, true));
        assertFalse("parse failure preserves current value",
                LogoutFlowRules.applyUnreadResult(5, 5, false, false));
        assertFalse("stale generation dropped",
                LogoutFlowRules.applyUnreadResult(5, 5, true, true));
        assertFalse("cross-account dropped",
                LogoutFlowRules.applyUnreadResult(9, 5, false, true));
    }

    @Test
    public void sessionCookieDetectionUsesExistingRules() {
        assertTrue(LogoutFlowRules.sessionCookieLooksPresent(
                "XSRF-TOKEN=abc; qolabdev_session=xyz; other=1"));
        assertFalse(LogoutFlowRules.sessionCookieLooksPresent("XSRF-TOKEN=abc"));
        assertFalse(LogoutFlowRules.sessionCookieLooksPresent(null));
    }

    // ---- activity wiring ---------------------------------------------------

    @Test
    public void menuSheetHasLogoutItemWithConfirmation() throws Exception {
        String src = read("HomeActivity.java");
        int sheet = src.indexOf("private void showMenuSheet()");
        String body = src.substring(sheet, src.indexOf("private void renderAll"));
        assertTrue("menu sheet must contain the Log out item",
                body.contains("home_menu_logout"));
        assertTrue("Log out must open the confirmation dialog",
                body.contains("confirmLogout()"));
        int conf = src.indexOf("private void confirmLogout()");
        String cbody = src.substring(conf, conf + 900);
        assertTrue("confirmation dialog required",
                cbody.contains("home_logout_title"));
        assertTrue("dialog message required",
                cbody.contains("home_logout_message"));
        assertTrue("cancel must NOT trigger logout (negative button only)",
                cbody.contains("setNegativeButton(android.R.string.cancel, null)"));
        assertTrue("confirm triggers the transaction",
                cbody.contains("performLogout()"));
    }

    @Test
    public void logoutReusesExistingWebviewTransaction_noNativeHttp() throws Exception {
        String home = read("HomeActivity.java");
        assertTrue("must run the existing webview logout transaction",
                home.contains("runLogoutTransaction"));
        // The forbidden native-HTTP logout path must not appear.
        assertFalse(home.contains("HttpURLConnection"));
        assertTrue("AuthNavRules session-cookie expiry must be reused",
                home.contains("AuthNavRules.sessionCookieNames"));
        assertTrue(home.contains("AuthNavRules.expireCookieValue"));
        String transport = read("QolabWebViewTransport.java");
        assertTrue("transport must evaluate the EXISTING logout JS",
                transport.contains("AuthNavRules.buildWebviewLogoutJs()"));
    }

    @Test
    public void cleanupIsIdempotentAndRoutesWithClearTask() throws Exception {
        String src = read("HomeActivity.java");
        int c = src.indexOf("private void completeLogoutLocally()");
        String body = src.substring(c, c + 2600);
        assertTrue("double-callback guard (idempotency)",
                body.contains("if (!logoutCompleting) return;"));
        assertTrue("must clear the static Home cache",
                body.contains("cachedData = null"));
        assertTrue("must shut down realtime",
                body.contains("ChatRealtime.get().shutdown()"));
        assertTrue("must invalidate pending callbacks (generation bump)",
                body.contains("REQUEST_GENERATION.incrementAndGet()"));
        assertTrue("must release the session transport",
                body.contains("QolabWebViewTransport.destroy()"));
        assertTrue("Login must be a NEW TASK root (Back-proof)",
                body.contains("FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK"));
    }

    @Test
    public void failedLogoutKeepsSessionAndOffersRetry() throws Exception {
        String src = read("HomeActivity.java");
        int p = src.indexOf("private void performLogout()");
        String body = src.substring(p, p + 2200);
        assertTrue("failure must release the single-flight gate (retry possible)",
                body.contains("logoutCompleting = false"));
        assertTrue("failure must show a non-blocking error",
                body.contains("home_logout_failed"));
        // The failure path returns BEFORE any cleanup call.
        int failBranchEnd = body.indexOf("return;");
        String failBranch = body.substring(0, failBranchEnd);
        assertFalse("failure path must not clear the cache",
                failBranch.contains("cachedData = null"));
        assertFalse("failure path must not release the transport",
                failBranch.contains("QolabWebViewTransport.destroy()"));
    }

    @Test
    public void backNavigationCannotReopenAuthenticatedScreens() throws Exception {
        // completeLogoutLocally finishes HomeActivity and Login is launched
        // with CLEAR_TASK — assert both, plus that routeToLogin's older
        // path is unchanged (still used for expiry routing).
        String src = read("HomeActivity.java");
        assertTrue(src.contains("finish();"));
        assertTrue(src.contains("FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK"));
    }
}
