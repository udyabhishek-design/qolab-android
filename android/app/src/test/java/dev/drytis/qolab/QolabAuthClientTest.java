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
}
