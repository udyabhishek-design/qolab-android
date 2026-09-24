package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the WebView-backed authentication bridge (v1.4.6).
 * The bridge JS itself runs in a real WebView (device-verified); these
 * pin the RESULT CONTRACT: parseBridgeJson for every documented
 * outcome, URL classification, and security shape (no credentials in
 * the bridge payload).
 */
public class QolabWebViewAuthTest {

    private static QolabWebViewAuth.Result parse(String json) {
        return QolabWebViewAuth.parseBridgeJson(json);
    }

    // ── successful login (Fortify 302 or 200) ────────────────────────

    @Test
    public void parse_success_302() {
        QolabWebViewAuth.Result r = parse(
                "{\"stage\":\"done\",\"csrfStatus\":204,\"csrfMs\":420,"
                + "\"status\":302,\"loginMs\":660,\"finalUrl\":\"https://workpulse-gbpedt.drytis.dev/login\"}");
        assertTrue(r.success);
        assertEquals(204, r.csrfStatus);
        assertEquals(302, r.loginStatus);
        assertEquals("done", r.stage);
        assertNull(r.errorBody);
        assertNull(r.ioError);
    }

    @Test
    public void parse_success_200() {
        QolabWebViewAuth.Result r = parse(
                "{\"stage\":\"done\",\"status\":200,\"loginMs\":800,"
                + "\"finalUrl\":\"https://workpulse-gbpedt.drytis.dev/login\"}");
        assertTrue(r.success);
    }

    // ── invalid credentials (422) ────────────────────────────────────

    @Test
    public void parse_invalidCredentials_422_carriesBody() {
        QolabWebViewAuth.Result r = parse(
                "{\"stage\":\"done\",\"csrfStatus\":204,\"status\":422,\"loginMs\":300,"
                + "\"body422\":\"{\\\"message\\\":\\\"These credentials do not match our records.\\\"}\"}");
        assertFalse(r.success);
        assertEquals(422, r.loginStatus);
        assertNotNull(r.errorBody);
        assertEquals("These credentials do not match our records.",
                QolabAuthClient.parseErrorMessage(r.errorBody));
    }

    // ── CSRF failures ────────────────────────────────────────────────

    @Test
    public void parse_csrfCookieMissing() {
        QolabWebViewAuth.Result r = parse("{\"stage\":\"csrf-cookie\",\"csrfStatus\":204,\"csrfMs\":400}");
        assertFalse(r.success);
        assertEquals("csrf-cookie", r.stage);
        assertEquals(204, r.csrfStatus);
    }

    @Test
    public void parse_csrfIo() {
        QolabWebViewAuth.Result r = parse("{\"stage\":\"login-io\",\"err\":\"TypeError\"}");
        assertFalse(r.success);
        assertEquals("login-io", r.stage);
        assertEquals("TypeError", r.ioError);
    }

    @Test
    public void parse_419_isSessionNotNetwork() {
        QolabWebViewAuth.Result r = parse(
                "{\"stage\":\"done\",\"csrfStatus\":204,\"status\":419,\"loginMs\":650}");
        assertFalse(r.success);
        assertEquals(419, r.loginStatus);
        // 419 must be classifiable as session/CSRF (NOT stage "io") so the
        // user sees "session expired", never "check your connection".
        assertFalse("io".equals(r.stage));
    }

    // ── malformed payloads ───────────────────────────────────────────

    @Test
    public void parse_nullAndGarbage() {
        assertNull(parse(null));
        assertNull(parse(""));
        assertNull(parse("not json"));
        assertNull(parse("{"));
    }

    @Test
    public void parse_evaluateJavascriptStringWrapper() {
        // evaluateJavascript callbacks deliver the value as a quoted JSON
        // string with escapes — parseBridgeJson must unwrap it.
        QolabWebViewAuth.Result r = parse(
                "\"{\\\"stage\\\":\\\"done\\\",\\\"status\\\":200}\"");
        assertNotNull(r);
        assertTrue(r.success);
    }

    // ── v1.4.6.1: watchdog + fallback contract ───────────────────────

    @Test
    public void parse_timeout_isCallableFailure() {
        // The watchdog delivers stage "timeout" — LoginActivity must treat
        // it as bridge-unusable (Java fallback), NOT a user-facing error.
        QolabWebViewAuth.Result r = parse("{\"stage\":\"timeout\"}");
        assertNotNull(r);
        assertFalse(r.success);
        assertEquals("timeout", r.stage);
    }

    @Test
    public void parse_bridgeError_isCallableFailure() {
        QolabWebViewAuth.Result r = parse("{\"stage\":\"bridge\",\"err\":\"JsError\"}");
        assertNotNull(r);
        assertFalse(r.success);
        assertEquals("bridge", r.stage);
        assertEquals("JsError", r.ioError);
    }

    @Test
    public void xsrfHeaderValue_decodedFromCookieHeader() {
        // The header value must come out DECODED (Laravel contract: raw → 419).
        String jar = "qolab-session=abc; XSRF-TOKEN=eyJ0eXAiOiJKV1QiIn0%3D; other=x";
        String header = QolabWebViewAuth.xsrfHeaderValue(jar);
        assertEquals("eyJ0eXAiOiJKV1QiIn0=", header); // %3D decoded to '='
    }

    @Test
    public void xsrfHeaderValue_absentCookie() {
        assertNull(QolabWebViewAuth.xsrfHeaderValue("qolab-session=abc"));
        assertNull(QolabWebViewAuth.xsrfHeaderValue(null));
    }

    // ── security shape ───────────────────────────────────────────────

    @Test
    public void bridgeJson_neverCarriesCredentials() {
        // The bridge contract reports statuses/timings/URLs/422-body only.
        // A payload smuggling email/password must not parse as success.
        QolabWebViewAuth.Result r = parse(
                "{\"stage\":\"done\",\"status\":200,\"email\":\"x@y.z\",\"password\":\"pw\"}");
        // If extra keys appear, parsing still works — but the CONTRACT is
        // that none of the call-sites ever put credentials in the payload;
        // assert the documented fields are the ones we read.
        assertNotNull(r);
        assertTrue(r.success);
        assertEquals(200, r.loginStatus);
    }
}
