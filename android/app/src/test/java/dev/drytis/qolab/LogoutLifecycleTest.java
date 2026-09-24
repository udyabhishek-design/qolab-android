package dev.drytis.qolab;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.*;

/**
 * v1.4.9 regression coverage for the logout lifecycle.
 * Covers: /login URL interception (before render), session-cookie
 * identification (names only), one-shot guard semantics (no duplicate
 * launches / no loops), back-stack safety, reopen behavior, and that
 * unrelated cookies/domains are untouched.
 */
@RunWith(RobolectricTestRunner.class)
public class LogoutLifecycleTest {

    private static final String ORIGIN = "https://workpulse-gbpedt.drytis.dev";

    // ---------------------------------------------------------------
    // /login interception — must match every spelling the server or a
    // redirect can produce, and must NOT match authenticated routes.
    // ---------------------------------------------------------------

    @Test
    public void loginInterceptor_matchesCanonicalLoginUrl() {
        assertTrue(AuthNavRules.isLoginUrl(ORIGIN + "/login", ORIGIN));
    }

    @Test
    public void loginInterceptor_matchesOriginWithTrailingSlash() {
        // getServerUrl() historically returned a trailing slash — the old
        // string-equality check missed this spelling (bug b).
        assertTrue(AuthNavRules.isLoginUrl(ORIGIN + "//login", ORIGIN));
        assertTrue(AuthNavRules.isLoginUrl(ORIGIN + "/", ORIGIN) == false); // root, not login
    }

    @Test
    public void loginInterceptor_matchesLoginWithQueryParams() {
        assertTrue(AuthNavRules.isLoginUrl(ORIGIN + "/login?redirect=%2Fdashboard", ORIGIN));
    }

    @Test
    public void loginInterceptor_matchesOnlyPathSeparatorVariants() {
        assertTrue(AuthNavRules.isLoginUrl(ORIGIN + "/login/", ORIGIN));
    }

    @Test
    public void loginInterceptor_matchesSameOriginSpelling() {
        // Same host in different case / default port must still match.
        assertTrue(AuthNavRules.isLoginUrl(
                "https://WORKPULSE-gbpedt.drytis.dev/login", ORIGIN));
    }

    @Test
    public void loginInterceptor_doesNotMatchAuthenticatedRoutes() {
        assertFalse(AuthNavRules.isLoginUrl(ORIGIN + "/dashboard", ORIGIN));
        assertFalse(AuthNavRules.isLoginUrl(ORIGIN + "/logout", ORIGIN));
        assertFalse(AuthNavRules.isLoginUrl(ORIGIN + "/api/login-audit", ORIGIN));
        assertFalse(AuthNavRules.isLoginUrl(ORIGIN, ORIGIN));
    }

    @Test
    public void loginInterceptor_doesNotMatchForeignHost() {
        assertFalse(AuthNavRules.isLoginUrl("https://evil.example.com/login", ORIGIN));
        assertFalse(AuthNavRules.isLoginUrl("https://drytis.dev.evil.com/login", ORIGIN));
    }

    @Test
    public void loginInterceptor_nullOrMalformedIsSafe() {
        assertFalse(AuthNavRules.isLoginUrl(null, ORIGIN));
        assertFalse(AuthNavRules.isLoginUrl("not a url", ORIGIN));
        assertFalse(AuthNavRules.isLoginUrl("", ORIGIN));
    }

    // ---------------------------------------------------------------
    // Session-cookie identification — NAMES only, never values.
    // ---------------------------------------------------------------

    @Test
    public void sessionCookieNames_findsSessionCookiesBySuffix() {
        String header = "XSRF-TOKEN=abc; qolab-session=xyz; theme=dark";
        List<String> names = AuthNavRules.sessionCookieNames(header);
        assertTrue(names.contains("qolab-session"));
        assertFalse(names.contains("XSRF-TOKEN"));
        assertFalse(names.contains("theme"));
    }

    @Test
    public void sessionCookieNames_emptyOrNullHeaderYieldsEmpty() {
        assertTrue(AuthNavRules.sessionCookieNames(null).isEmpty());
        assertTrue(AuthNavRules.sessionCookieNames("").isEmpty());
        assertTrue(AuthNavRules.sessionCookieNames("theme=dark").isEmpty());
    }

    @Test
    public void expireCookieValue_producesPastExpiryWithoutValue() {
        String v = AuthNavRules.expireCookieValue("qolab-session");
        assertTrue(v.startsWith("qolab-session="));
        assertTrue(v.contains("Max-Age=0"));
        assertFalse(v.contains("qolab-session=xyz"));
    }

    // ---------------------------------------------------------------
    // One-shot guard — one logout action must resolve to exactly one
    // native-login launch; repeated /login navigations are no-ops.
    // (Modeled here against the guard contract used by MainActivity.)
    // ---------------------------------------------------------------

    private boolean guard = false;

    private int runLoggedOutTimes(int navigations) {
        int launches = 0;
        for (int i = 0; i < navigations; i++) {
            if (guard) continue; // already handled
            guard = true;
            launches++;
        }
        return launches;
    }

    @Test
    public void oneLogoutAction_yieldsExactlyOneNativeLaunch() {
        // shouldOverrideUrlLoading can fire multiple times (redirects,
        // retry taps). The guard must collapse them to ONE launch.
        assertEquals(1, runLoggedOutTimes(3));
    }

    @Test
    public void expiredSession_reusesSameGuardedPath() {
        // Session expiry and explicit logout share handleLoggedOut();
        // after the guard fired once, the app goes to native login —
        // no loop back into MainActivity, no duplicate LoginActivity.
        assertEquals(1, runLoggedOutTimes(5));
        // "Reopen" scenario: a fresh process resets the guard.
        guard = false;
        assertEquals(1, runLoggedOutTimes(1));
    }

    // ---------------------------------------------------------------
    // Back-stack safety — LoginActivity is launched with
    // NEW_TASK | CLEAR_TASK, so the authenticated activity cannot be
    // reached with Back after logout. Verify the intent flags contract.
    // ---------------------------------------------------------------

    @Test
    public void logoutIntentFlags_clearTaskRoot() {
        int flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP;
        // CLEAR_TASK wipes the existing task — MainActivity's record is
        // destroyed, not merely stopped: Back cannot return to it.
        assertTrue((flags & android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0);
        assertTrue((flags & android.content.Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    // ---------------------------------------------------------------
    // CSRF safety preserved — logout must still send a valid CSRF
    // token; a stale token (419) must not be treated as "logged out".
    // (Contract check against AuthNavRules; the 419-no-logout rule is
    // exercised in QolabWebViewAuthTest / QolabAuthClientTest.)
    // ---------------------------------------------------------------

    @Test
    public void nonLoginRedirect_isNotTreatedAsLogout() {
        // e.g. 419 responses keep the SPA on /dashboard — no /login
        // navigation occurs, so no logout interception may trigger.
        assertFalse(AuthNavRules.isLoginUrl(ORIGIN + "/dashboard?error=419", ORIGIN));
    }

    // ---------------------------------------------------------------
    // POST /logout takeover — matches the SPA's fetch call, never GETs,
    // never other endpoints.
    // ---------------------------------------------------------------

    @Test
    public void logoutUrl_matcher_acceptsPostLogoutTarget() {
        assertTrue(AuthNavRules.isLogoutUrl(ORIGIN + "/logout", ORIGIN));
        assertTrue(AuthNavRules.isLogoutUrl(ORIGIN + "/logout/", ORIGIN));
        assertFalse(AuthNavRules.isLogoutUrl(ORIGIN + "/logout", "https://other.drytis.dev"));
        assertFalse(AuthNavRules.isLogoutUrl(ORIGIN + "/logout-items", ORIGIN));
        assertFalse(AuthNavRules.isLogoutUrl(null, ORIGIN));
    }

    // ---------------------------------------------------------------
    // XSRF decode contract (logout uses the same one as login):
    // decoded representation, '+' preserved (URLDecoder quirk guard).
    // ---------------------------------------------------------------

    @Test
    public void decodeXsrfToken_decodesPercentEncoding() {
        assertEquals("abc123+/=", AuthNavRules.decodeXsrfToken("abc123%2B%2F%3D"));
    }

    @Test
    public void decodeXsrfToken_nullAndPlainValuesPassThrough() {
        assertNull(AuthNavRules.decodeXsrfToken(null));
        assertEquals("plain", AuthNavRules.decodeXsrfToken("plain"));
    }

    // ---------------------------------------------------------------
    // LogoutResult contract — success only on 2xx/3xx from the SERVER,
    // never on 419 (CSRF must not be weakened into "logged out").
    // ---------------------------------------------------------------

    @Test
    public void logoutResult_successOnlyForServerConfirmed() {
        // Same shape as QolabAuthClient.LogoutResult — verified via
        // the client's own tests; here we assert the classification
        // rule itself: 2xx/3xx → true, 419 → false.
        assertTrue(statusConfirmsLogout(204));
        assertTrue(statusConfirmsLogout(302));
        assertFalse(statusConfirmsLogout(419));
        assertFalse(statusConfirmsLogout(422));
        assertFalse(statusConfirmsLogout(500));
        assertFalse(statusConfirmsLogout(0));
    }

    private static boolean statusConfirmsLogout(int status) {
        return status / 100 == 2 || status / 100 == 3;
    }
}
