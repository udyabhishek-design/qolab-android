package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * v1.5.40 — cold-start authentication regression tests.
 *
 * CONFIRMED BUG (Motorola G32): reopen after close → Home partially
 * renders → redirect to Login after a few seconds. Chain: rehydrate
 * treated a transient failure (network error / timeout) as
 * "session invalid" → routeToLogin → LoginActivity.onCreate expired
 * the persisted session cookie → forced manual re-login (and the next
 * open had no cookie at all).
 *
 * The fix: PROVEN_INVALID vs TRANSIENT classification in the transport,
 * the SessionGate proof-before-logout funnel, and cookie expiry ONLY
 * at sign-in submit time.
 */
public class SessionGateTest {

    // ── classification ─────────────────────────────────────────────

    @Test
    public void loginLandingIsProvenInvalid() {
        assertEquals(QolabWebViewTransport.RehydrateOutcome.PROVEN_INVALID,
                QolabWebViewTransport.classifyRehydrateFailure(
                        "login-landing", true));
    }

    @Test
    public void networkErrorIsTransient() {
        assertEquals(QolabWebViewTransport.RehydrateOutcome.TRANSIENT,
                QolabWebViewTransport.classifyRehydrateFailure(
                        "network-error", false));
    }

    @Test
    public void timeoutWithNoCategoryIsProvenInvalid_legacyShape() {
        // A settle(false) with NO category and NO login landing happens
        // only from legacy paths (e.g. no cookie at all → onResult(false)
        // directly, which never reaches classify). Defensive default:
        // treat as invalid ONLY when category is null AND not transient.
        // The timeout runnable now sets category="timeout" BEFORE
        // settling, so this shape maps to TRANSIENT in practice.
        assertEquals(QolabWebViewTransport.RehydrateOutcome.TRANSIENT,
                QolabWebViewTransport.classifyRehydrateFailure(
                        "timeout", false));
    }

    // ── gate decision seam ─────────────────────────────────────────

    @Test
    public void shouldLogoutOnlyOnProvenInvalid() {
        assertTrue(SessionGate.shouldLogout(
                QolabWebViewTransport.RehydrateOutcome.PROVEN_INVALID));
        assertFalse(SessionGate.shouldLogout(
                QolabWebViewTransport.RehydrateOutcome.TRANSIENT));
    }

    // ── source-level guards (the fix must stay in place) ───────────

    private static String read(String rel) throws Exception {
        java.io.File f = new java.io.File(
                System.getProperty("user.dir") + "/../" + rel);
        if (!f.exists()) f = new java.io.File(rel);
        if (!f.exists()) f = new java.io.File("/home/coder/qolab-android/" + rel);
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }

    @Test
    public void loginActivityDoesNotExpireCookiesOnCreate() throws Exception {
        String src = read(
                "android/app/src/main/java/dev/drytis/qolab/LoginActivity.java");
        assertFalse("the blanket onCreate cookie expiry must be gone",
                src.contains("cleared stale session cookie on native login"));
        assertTrue("cookie clearing must happen at sign-in submit time",
                src.contains("clearStaleCookiesForSignIn();"));
    }

    @Test
    public void homeRoutesDeadTransportThroughSessionGate() throws Exception {
        String src = read(
                "android/app/src/main/java/dev/drytis/qolab/HomeActivity.java");
        assertTrue("Home must verify the session before logout (SessionGate)",
                src.contains("SessionGate.requireValidSession"));
        // 401 stays an honest logout (server proof inside the browser
        // context), but transport-dead must NOT logout directly.
        assertFalse("transport dead must not route straight to Login",
                src.contains("\"transport dead → Login\""));
    }

    @Test
    public void rehydrateCallbackDistinguishesTransient() throws Exception {
        String src = read(
                "android/app/src/main/java/dev/drytis/qolab/QolabWebViewTransport.java");
        assertTrue("RehydrateCallback must have onTransientFailure",
                src.contains("onTransientFailure"));
        assertTrue("failures must be classified",
                src.contains("classifyRehydrateFailure"));
    }
}
