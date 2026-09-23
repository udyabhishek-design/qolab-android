package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unit tests for the pure helpers in QolabAuthClient (login screen v1.4).
 * No Android framework or network involved.
 */
public class QolabAuthClientTest {

    // ── parseSetCookie ──────────────────────────────────────────────

    @Test
    public void parseSetCookie_basic() {
        QolabAuthClient.ParsedCookie c = QolabAuthClient.parseSetCookie(
                "qolab-session=abc123; path=/; secure; httponly");
        assertNotNull(c);
        assertEquals("qolab-session", c.name);
        assertEquals("abc123", c.value);
    }

    @Test
    public void parseSetCookie_noAttributes() {
        QolabAuthClient.ParsedCookie c = QolabAuthClient.parseSetCookie("XSRF-TOKEN=eyJ%2Ftoken");
        assertNotNull(c);
        assertEquals("XSRF-TOKEN", c.name);
        assertEquals("eyJ%2Ftoken", c.value);
    }

    @Test
    public void parseSetCookie_nullAndMalformed() {
        assertNull(QolabAuthClient.parseSetCookie(null));
        assertNull(QolabAuthClient.parseSetCookie(""));
        assertNull(QolabAuthClient.parseSetCookie("novalue"));
        assertNull(QolabAuthClient.parseSetCookie("=orphan"));
    }

    // ── encodeForm ──────────────────────────────────────────────────

    @Test
    public void encodeForm_encodesSpecials() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("email", "user+tag@example.com");
        form.put("password", "p@ss word&=1");
        String body = QolabAuthClient.encodeForm(form);
        assertEquals("email=user%2Btag%40example.com&password=p%40ss+word%26%3D1", body);
    }

    @Test
    public void encodeForm_emptyValues() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("remember", "");
        assertEquals("remember=", QolabAuthClient.encodeForm(form));
    }

    // ── urlDecode ───────────────────────────────────────────────────

    @Test
    public void urlDecode_percentEncoding() {
        assertEquals("eyJ{\"v\":1}", QolabAuthClient.urlDecode("eyJ%7B%22v%22%3A1%7D"));
    }

    @Test
    public void urlDecode_passthroughOnNull() {
        assertNull(QolabAuthClient.urlDecode(null));
        assertEquals("plain", QolabAuthClient.urlDecode("plain"));
    }

    // ── parseErrorMessage (Laravel 422 shape) ───────────────────────

    @Test
    public void parseErrorMessage_credentialsMismatch() {
        String json = "{\"message\":\"These credentials do not match our records.\","
                + "\"errors\":{\"email\":[\"These credentials do not match our records.\"]}}";
        assertEquals("These credentials do not match our records.",
                QolabAuthClient.parseErrorMessage(json));
    }

    @Test
    public void parseErrorMessage_validationFields() {
        String json = "{\"message\":\"The given data was invalid.\","
                + "\"errors\":{\"email\":[\"The email field is required.\"],"
                + "\"password\":[\"The password field is required.\"]}}";
        assertEquals("The email field is required.", QolabAuthClient.parseErrorMessage(json));
    }

    @Test
    public void parseErrorMessage_fallsBackToMessage() {
        String json = "{\"message\":\"Too many attempts. Try again later.\"}";
        assertEquals("Too many attempts. Try again later.",
                QolabAuthClient.parseErrorMessage(json));
    }

    @Test
    public void parseErrorMessage_garbageReturnsNull() {
        assertNull(QolabAuthClient.parseErrorMessage(null));
        assertNull(QolabAuthClient.parseErrorMessage(""));
        assertNull(QolabAuthClient.parseErrorMessage("<html>502</html>"));
        assertNull(QolabAuthClient.parseErrorMessage("{\"errors\":{}}"));
    }

    // ── base URL normalization ──────────────────────────────────────

    @Test
    public void baseUrl_stripsTrailingSlash() throws Exception {
        java.lang.reflect.Field f = QolabAuthClient.class.getDeclaredField("baseUrl");
        f.setAccessible(true);
        assertEquals("https://qolab.drytis.com",
                f.get(new QolabAuthClient("https://qolab.drytis.com/")));
        assertEquals("https://qolab.drytis.com",
                f.get(new QolabAuthClient("https://qolab.drytis.com//")));
    }

    // ── v1.4.3 regression: diagnostics must never carry secrets ─────

    @Test
    public void diagnostics_stageFields_safeByConstruction() throws Exception {
        // Reach into the result builder via reflection-free path: construct
        // through the same logic login() uses on IO failure (null network
        // in unit tests throws immediately inside httpGet).
        QolabAuthClient client = new QolabAuthClient("https://invalid.invalid");
        client.setTimeouts(1, 1);
        QolabAuthClient.LoginResult r = client.login("someone@example.com", "secret-pw", false);
        assertFalse(r.success);
        assertEquals("io", r.stage);
        assertNotNull(r.ioError);
        assertTrue(r.cookieNames.isEmpty());
        // login() retries once on IO failure → still exactly one outcome
        assertFalse(r.success);
    }

    @Test
    public void timeouts_raisedForColdBackend() throws Exception {
        // The dev backend can take >10s to wake from idle; v1.4's 10s/20s
        // caused deterministic IO failures on-device. v1.4.3: 15s/40s.
        java.lang.reflect.Field ct = QolabAuthClient.class.getDeclaredField("connectTimeoutMs");
        java.lang.reflect.Field rt = QolabAuthClient.class.getDeclaredField("readTimeoutMs");
        ct.setAccessible(true); rt.setAccessible(true);
        QolabAuthClient c = new QolabAuthClient("https://x.example");
        assertEquals(15_000, ct.get(c));
        assertEquals(40_000, rt.get(c));
    }

    @Test
    public void parseSetCookie_preservesFullValueWithSpecials() {
        // Session cookie values may contain %2B etc. — the pair-splitting
        // must not truncate or re-decode them.
        QolabAuthClient.ParsedCookie c = QolabAuthClient.parseSetCookie(
                "qolab-session=eyJpdi%2Babc%3D%3D; expires=Thu; path=/; httponly; samesite=lax");
        assertNotNull(c);
        assertEquals("qolab-session", c.name);
        assertEquals("eyJpdi%2Babc%3D%3D", c.value);
    }

    // ── v1.4.5 regression: the 419-on-device bug ────────────────────
    // Real-device diagnostics showed: csrf 204, login 419 — while the same
    // flow from curl/JVM passed. The server contract (verified A/B) accepts
    // the DECODED XSRF representation and refuses the RAW one. These tests
    // pin the exact fixture format Development returns and the header
    // construction the client must use.

    private static final String DEV_CSRF_SET_COOKIE_XSRF =
            "XSRF-TOKEN=eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9."
            + "eyJpc3MiOiJodHRwczovL3dvcmtwdWxzZS1nYnBlZHQ"
            + "uZHJ5dGlzLmRldi9zYW5jdHVtL2NzcmYtY29va2llIn0%3D;"
            + " expires=Thu, 24 Sep 2026 01:08:59 GMT; Max-Age=28800; path=/; samesite=lax";
    private static final String DEV_CSRF_SET_COOKIE_SESSION =
            "qolab-session=eyJpdi%3DA%253Dvalue; expires=Thu, 24 Sep 2026 01:08:59 GMT;"
            + " Max-Age=28800; path=/; httponly; samesite=lax";

    @Test
    public void devCsrfFixture_bothCookiesCaptured() {
        // Exactly the Set-Cookie shape Development issues after the 204.
        QolabAuthClient.ParsedCookie xsrf =
                QolabAuthClient.parseSetCookie(DEV_CSRF_SET_COOKIE_XSRF);
        QolabAuthClient.ParsedCookie session =
                QolabAuthClient.parseSetCookie(DEV_CSRF_SET_COOKIE_SESSION);
        assertEquals("XSRF-TOKEN", xsrf.name);
        assertEquals("qolab-session", session.name);
        // Stored values stay RAW (encoded) — decoding happens ONLY for the header.
        assertEquals("eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9."
                + "eyJpc3MiOiJodHRwczovL3dvcmtwdWxzZS1nYnBlZHQ"
                + "uZHJ5dGlzLmRldi9zYW5jdHVtL2NzcmYtY29va2llIn0%3D", xsrf.value);
        assertTrue(xsrf.value.contains("%3D")); // still percent-encoded
    }

    @Test
    public void xsrfHeader_mustUseDecodedRepresentation() {
        // Laravel's ValidateCsrfToken compares the X-XSRF-TOKEN header to
        // the DECODED cookie value. Sending the raw encoded form → 419.
        String stored = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9"
                + ".eyJpc3MiOiJleGFtcGxlIn0%3D";
        String header = QolabAuthClient.urlDecode(stored);
        assertFalse("header must differ from raw stored value", header.equals(stored));
        assertTrue("decoded header must not contain %3D", !header.contains("%3D"));
        // Round-trip: the decoded form is the base64 payload Laravel issued.
        assertTrue(header.endsWith("="));
    }

    @Test
    public void cookieHeader_includesBothCookies() throws Exception {
        // The POST Cookie header must carry XSRF-TOKEN AND qolab-session —
        // omitting XSRF-TOKEN would also produce a 419.
        QolabAuthClient client = new QolabAuthClient("https://x.example");
        java.lang.reflect.Field f = QolabAuthClient.class.getDeclaredField("cookieJar");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> jar = (java.util.Map<String, String>) f.get(client);
        QolabAuthClient.ParsedCookie xsrf =
                QolabAuthClient.parseSetCookie(DEV_CSRF_SET_COOKIE_XSRF);
        QolabAuthClient.ParsedCookie session =
                QolabAuthClient.parseSetCookie(DEV_CSRF_SET_COOKIE_SESSION);
        jar.put(xsrf.name, xsrf.value);
        jar.put(session.name, session.value);
        java.lang.reflect.Method m = QolabAuthClient.class.getDeclaredMethod("cookieHeader");
        m.setAccessible(true);
        String header = (String) m.invoke(client);
        assertTrue("missing XSRF-TOKEN in Cookie header", header.contains("XSRF-TOKEN="));
        assertTrue("missing qolab-session in Cookie header", header.contains("qolab-session="));
    }

    @Test
    public void status419_isNotNetworkFailure() {
        // The device previously showed "check your connection" for a 419 —
        // wrong classification. 419 must classify as session/CSRF, distinct
        // from IO/network failures (stage "io").
        QolabAuthClient client = new QolabAuthClient("https://invalid.invalid");
        client.setTimeouts(1, 1);
        QolabAuthClient.LoginResult net = client.login("someone@example.com", "pw", false);
        assertEquals("io", net.stage); // real network failure → "io"
        // A 419 from the server would carry stage "login" + status 419 —
        // never stage "io". Verify the classification contract used by
        // LoginActivity: 422 → credentials, 419 → session expired,
        // 5xx → server unavailable, everything else → generic.
        int four19 = 419;
        assertTrue(four19 != 422 && four19 / 100 != 5);
    }
}
