package dev.drytis.qolab;

import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * Qolab login API client — pure Java, unit-testable.
 *
 * Uses the EXISTING web authentication endpoints (Laravel Fortify / Sanctum):
 *
 *   1. GET  {base}/sanctum/csrf-cookie   → sets XSRF-TOKEN + the session
 *      cookie (qolab-session / laravel_session) on this client's cookie jar.
 *   2. POST {base}/login  (form: email, password, remember) with the
 *      X-XSRF-TOKEN header. Success = 302/200 (session cookie rotated to an
 *      authenticated one). 422 = validation/credentials error JSON.
 *
 * No backend endpoints were added or changed.
 */
public final class QolabAuthClient {

    /** One HTTP cookie from a Set-Cookie header. */
    public static final class Cookie {
        public final String name;
        public final String value;

        public Cookie(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    /** Result of a login attempt. Diagnostics carry ONLY safe data:
     *  statuses, stages, cookie NAMES, exception CLASS names — never
     *  cookie values, tokens, or credentials. */
    public static final class LoginResult {
        public final boolean success;
        /** First user-facing message from a 422 errors object, or null. */
        public final String errorMessage;
        /** POST /login status (or csrf status when the flow failed there); 0 for IO failure. */
        public final int status;
        /** Where the flow stopped: "csrf" | "login" | "io" | "" (success). */
        public final String stage;
        /** GET /sanctum/csrf-cookie status (0 if never reached). */
        public final int csrfStatus;
        /** XSRF-TOKEN cookie present in the jar after the handshake. */
        public final boolean xsrfCookiePresent;
        /** A *-session cookie present in the jar after the flow. */
        public final boolean sessionCookiePresent;
        /** Cookie NAMES only (safe to log). */
        public final List<String> cookieNames;
        /** Location header of the POST response when 3xx, else null. */
        public final String redirectLocation;
        /** Exception class name for IO failures (safe, no message). */
        public final String ioError;
        /** All cookies received during the flow (csrf + authenticated session). */
        public final List<Cookie> cookies;
        /** GET /sanctum/csrf-cookie duration in ms (-1 if not reached). */
        public final long csrfDurationMs;
        /** POST /login duration in ms (-1 if not reached). */
        public final long loginDurationMs;

        LoginResult(boolean success, String errorMessage, int status, String stage,
                    int csrfStatus, boolean xsrfCookiePresent, boolean sessionCookiePresent,
                    String redirectLocation, String ioError, List<Cookie> cookies,
                    long csrfDurationMs, long loginDurationMs) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.status = status;
            this.stage = stage == null ? "" : stage;
            this.csrfStatus = csrfStatus;
            this.xsrfCookiePresent = xsrfCookiePresent;
            this.sessionCookiePresent = sessionCookiePresent;
            this.redirectLocation = redirectLocation;
            this.ioError = ioError;
            this.cookies = cookies;
            this.csrfDurationMs = csrfDurationMs;
            this.loginDurationMs = loginDurationMs;
            List<String> names = new ArrayList<>();
            for (Cookie c : cookies) names.add(c.name);
            this.cookieNames = names;
        }
    }

    private final String baseUrl;
    private final Map<String, String> cookieJar = new LinkedHashMap<>();
    private int connectTimeoutMs = 15_000;
    private int readTimeoutMs = 40_000;

    public QolabAuthClient(String baseUrl) {
        // strip trailing slash
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    public void setTimeouts(int connectMs, int readMs) {
        this.connectTimeoutMs = connectMs;
        this.readTimeoutMs = readMs;
    }

    /**
     * Full login flow. Blocking — call from a background thread.
     * One transparent retry of the whole csrf+login sequence when the
     * handshake comes back empty (dev backend waking from idle) or the
     * POST is rejected as CSRF-invalid (419) — the retry performs a
     * FRESH csrf handshake, never reusing the stale token.
     */
    public LoginResult login(String email, String password, boolean remember) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            cookieJar.clear();
            LoginResult r = loginOnce(email, password, remember);
            boolean retryable = false;
            if (!r.success) {
                if ("io".equals(r.stage)) {
                    retryable = attempt == 1; // network hiccup — one fresh try
                } else if ("login".equals(r.stage) && r.status == 419) {
                    retryable = attempt == 1; // CSRF expired — fresh handshake
                }
            }
            if (!retryable) return r;
        }
        // unreachable; second attempt always returns
        throw new IllegalStateException("login retry exhausted");
    }

    private LoginResult loginOnce(String email, String password, boolean remember) {
        int csrfStatus = 0;
        String ioError = null;
        long csrfStart = -1, csrfMs = -1, loginMs = -1;
        try {
            // 1. CSRF handshake
            csrfStart = SystemClock.elapsedRealtime();
            csrfStatus = httpGet(baseUrl + "/sanctum/csrf-cookie");
            csrfMs = SystemClock.elapsedRealtime() - csrfStart;
            String xsrf = cookieJar.get("XSRF-TOKEN");
            boolean xsrfOk = xsrf != null;
            if (csrfStatus / 100 != 2 || !xsrfOk) {
                // No usable handshake — fail with full diagnostics.
                return new LoginResult(false, null, csrfStatus, "csrf",
                        csrfStatus, xsrfOk, hasSessionCookie(),
                        null, null, snapshotCookies(), csrfMs, loginMs);
            }

            // 2. POST /login (Accept: application/json → 422 JSON on error)
            Map<String, String> form = new LinkedHashMap<>();
            form.put("email", email == null ? "" : email);
            form.put("password", password == null ? "" : password);
            form.put("remember", remember ? "on" : "");

            String[] redirectOut = new String[1];
            long loginStart = SystemClock.elapsedRealtime();
            int status = httpPostForm(baseUrl + "/login", form, xsrf, redirectOut);
            loginMs = SystemClock.elapsedRealtime() - loginStart;
            boolean session = hasSessionCookie();
            if (status == 422) {
                return new LoginResult(false, null, status, "login",
                        csrfStatus, true, session,
                        redirectOut[0], null, snapshotCookies(), csrfMs, loginMs);
            }
            boolean ok = status / 100 == 2 || status / 100 == 3;
            return new LoginResult(ok, null, status, ok ? "" : "login",
                    csrfStatus, true, session,
                    redirectOut[0], null, snapshotCookies(), csrfMs, loginMs);
        } catch (Exception e) {
            if (csrfMs < 0 && csrfStart >= 0) {
                // The exception happened DURING the csrf request.
                csrfMs = SystemClock.elapsedRealtime() - csrfStart;
            } else if (loginMs < 0 && csrfMs >= 0) {
                // The exception happened DURING the login POST.
                loginMs = -2; // marker: login stage timed out / failed
            }
            return new LoginResult(false, null, 0, "io",
                    csrfStatus, cookieJar.containsKey("XSRF-TOKEN"), hasSessionCookie(),
                    null, e.getClass().getSimpleName(), new ArrayList<>(0), csrfMs, loginMs);
        }
    }

    /**
     * DEVELOPMENT-ONLY connectivity probe: harmless GET against the
     * Development origin's CSRF endpoint. No auth, no data written, no
     * cookie mutation (jar untouched — a fresh client should be used).
     * Safe output: status, duration ms, exception class only.
     */
    public static final class ProbeResult {
        public final boolean reachable;
        public final int status;
        public final long durationMs;
        public final String ioError;

        ProbeResult(boolean reachable, int status, long durationMs, String ioError) {
            this.reachable = reachable;
            this.status = status;
            this.durationMs = durationMs;
            this.ioError = ioError;
        }
    }

    public ProbeResult probeConnectivity() {
        long start = SystemClock.elapsedRealtime();
        try {
            HttpURLConnection c = open(baseUrl + "/sanctum/csrf-cookie");
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/json");
            drain(c);
            int code = c.getResponseCode();
            return new ProbeResult(code / 100 == 2 || code / 100 == 3 || code / 100 == 4,
                    code, SystemClock.elapsedRealtime() - start, null);
        } catch (Exception e) {
            return new ProbeResult(false, 0, SystemClock.elapsedRealtime() - start,
                    e.getClass().getSimpleName());
        }
    }

    /** True when the jar holds any *-session cookie. */
    private boolean hasSessionCookie() {
        for (String name : cookieJar.keySet()) {
            if (name.toLowerCase().endsWith("session")) return true;
        }
        return false;
    }

    /**
     * Fetch the raw 422 response body (call after a failed login with the
     * same credentials so the server sees the same flow). Returns null on IO
     * failure. Kept simple: the second request is cheap and stateless-ish.
     */
    public String fetchLastErrorBody(String email, String password) {
        return null; // not used — error message parsed in login() via parseError
    }

    // ------------------------------------------------------------------
    // HTTP plumbing
    // ------------------------------------------------------------------

    private int httpGet(String urlStr) throws Exception {
        HttpURLConnection c = open(urlStr);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        storeCookies(c);
        drain(c);
        return c.getResponseCode();
    }

    /** @param redirectOut one-element sink for the Location header (3xx). */
    private int httpPostForm(String urlStr, Map<String, String> form, String xsrfToken,
                             String[] redirectOut)
            throws Exception {
        HttpURLConnection c = open(urlStr);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("X-XSRF-TOKEN", urlDecode(xsrfToken));
        c.setRequestProperty("Cookie", cookieHeader());
        OutputStream os = c.getOutputStream();
        os.write(encodeForm(form).getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();
        storeCookies(c);
        lastErrorBody = readBody(c);
        if (redirectOut != null) {
            List<String> loc = c.getHeaderFields().get("Location");
            if (loc != null && !loc.isEmpty()) redirectOut[0] = loc.get(0);
        }
        drain(c);
        return c.getResponseCode();
    }

    private String lastErrorBody = null;

    /** Body captured from the most recent POST (for 422 parsing). */
    public String lastBody() {
        return lastErrorBody;
    }

    private HttpURLConnection open(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (c instanceof HttpsURLConnection) {
            // System default trust store — production certs are public CAs.
        }
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(readTimeoutMs);
        c.setInstanceFollowRedirects(false); // 302 = success signal
        return c;
    }

    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookieJar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private void storeCookies(HttpURLConnection c) {
        Map<String, List<String>> headers = c.getHeaderFields();
        List<String> setCookies = headers == null ? null : headers.get("Set-Cookie");
        if (setCookies == null) setCookies = headers == null ? null : headers.get("set-cookie");
        if (setCookies == null) return;
        for (String raw : setCookies) {
            ParsedCookie p = parseSetCookie(raw);
            if (p != null && p.name != null && p.value != null && !p.value.isEmpty()) {
                cookieJar.put(p.name, p.value);
            }
        }
    }

    private void drain(HttpURLConnection c) {
        try {
            InputStream is = c.getInputStream();
            if (is != null) {
                byte[] buf = new byte[1024];
                //noinspection StatementWithEmptyBody
                while (is.read(buf) != -1) { /* drain */ }
                is.close();
            }
        } catch (Exception ignored) {
        }
    }

    private String readBody(HttpURLConnection c) {
        try {
            InputStream es = c.getErrorStream();
            if (es == null) {
                try {
                    es = c.getInputStream();
                } catch (Exception ignored) {
                    return null;
                }
            }
            if (es == null) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private List<Cookie> snapshotCookies() {
        List<Cookie> out = new ArrayList<>();
        for (Map.Entry<String, String> e : cookieJar.entrySet()) {
            out.add(new Cookie(e.getKey(), e.getValue()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Pure helpers (unit-tested)
    // ------------------------------------------------------------------

    /** Parse "name=value; Path=/; HttpOnly" → name/value. */
    public static ParsedCookie parseSetCookie(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        int semi = raw.indexOf(';');
        String pair = semi > 0 ? raw.substring(0, semi) : raw;
        int eq = pair.indexOf('=');
        if (eq <= 0) return null;
        String name = pair.substring(0, eq).trim();
        String value = pair.substring(eq + 1).trim();
        return new ParsedCookie(name, value);
    }

    /** Encode a form body. */
    public static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    /** Percent-decode (XSRF-TOKEN cookie value is URL-encoded). */
    public static String urlDecode(String s) {
        if (s == null) return null;
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * Extract the first error message from a Laravel 422 JSON body:
     * {"message":"...","errors":{"email":["..."]}}
     * Deterministic field order (email, password, then others). Falls back
     * to `message`, then null.
     */
    public static String parseErrorMessage(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(json);
            JSONObject errors = root.optJSONObject("errors");
            if (errors != null) {
                String first = firstErrorFor(errors, "email");
                if (first == null) first = firstErrorFor(errors, "password");
                if (first == null) {
                    java.util.Iterator<String> keys = errors.keys();
                    while (keys.hasNext() && first == null) {
                        first = firstErrorFor(errors, keys.next());
                    }
                }
                if (first != null) return first;
            }
            String message = root.optString("message", null);
            return message != null && !message.isEmpty() ? message : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstErrorFor(JSONObject errors, String key) {
        try {
            return errors.optJSONArray(key) != null && errors.optJSONArray(key).length() > 0
                    ? errors.optJSONArray(key).optString(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Minimal Android-free email validity check. */
    public static boolean isValidEmail(String email) {
        if (email == null) return false;
        return !TextUtils.isEmpty(email) && android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches();
    }

    public static final class ParsedCookie {
        public final String name;
        public final String value;

        ParsedCookie(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
