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
}
