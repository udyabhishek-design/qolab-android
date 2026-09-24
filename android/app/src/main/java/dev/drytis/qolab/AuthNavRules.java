package dev.drytis.qolab;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure navigation/auth rules shared by MainActivity's WebView interception
 * (v1.4.9). Unit-tested — no WebView required.
 *
 * Why this exists: the old interceptor used string equality
 * (appOriginUrl() + "/login"), which (a) breaks when getServerUrl()
 * returns a trailing slash ("https://host//login") and (b) only ran in
 * onPageStarted — while Inertia's post-logout redirect swaps the page
 * via XHR, which NEVER fires onPageStarted. Both misses let the old
 * web Login render inside MainActivity.
 */
public final class AuthNavRules {

    private AuthNavRules() {
    }

    /**
     * True when the URL is a GET-able /login page on the app origin —
     * tolerant of trailing slashes and query strings, and strict about
     * the host so foreign origins never match.
     */
    public static boolean isLoginUrl(String url, String appOrigin) {
        return isAppPath(url, appOrigin, "/login");
    }

    /**
     * True for a POST /logout target on the app origin. Used by MainActivity
     * to take over the logout XHR (stale-XSRF retry) — never for GETs.
     */
    public static boolean isLogoutUrl(String url, String appOrigin) {
        return isAppPath(url, appOrigin, "/logout");
    }

    /**
     * Percent-decode an XSRF cookie value WITHOUT the '+'→space quirk of
     * URLDecoder (base64 tokens may legitimately contain '+'). This matches
     * the Laravel/Sanctum contract verified against Development: the header
     * must carry the DECODED token.
     */
    public static String decodeXsrfToken(String raw) {
        if (raw == null) return null;
        try {
            return java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * v1.4.10: JS injected after every page finish. Wraps window.fetch so
     * the SPA's POST /logout (Inertia visit) is handed ENTIRELY to the
     * native client (fresh CSRF handshake → POST → 419 retry → local
     * transition). The original fetch is NOT executed — exactly ONE
     * logical logout transaction per tap, no duplicate requests. The
     * promise resolves 204-shaped so Inertia's router treats the visit as
     * finished (progress bar completes, no retry) while the native side
     * drives the transition to LoginActivity.
     */
    public static String buildLogoutTakeoverJs() {
        return "(function(){"
            + "  if (window.__qolabLogoutTakeover) return;"
            + "  window.__qolabLogoutTakeover = true;"
            + "  var orig = window.fetch;"
            + "  if (!orig) return;"
            + "  window.fetch = function(input, init){"
            + "    try {"
            + "      var url = (typeof input === 'string') ? input"
            + "        : (input && input.url) ? input.url : '';"
            + "      var method = ((init && init.method)"
            + "        || (input && input.method) || 'GET').toUpperCase();"
            + "      if (method === 'POST' && /\\/logout(\\?|#|$)/.test(url))"
            + "        return window.QolabNative.logout();"
            + "    } catch (e) {}"
            + "    return orig.apply(this, arguments);"
            + "  };"
            + "}())";
    }

    private static boolean isAppPath(String url, String appOrigin, String expectedPath) {
        if (url == null || appOrigin == null) return false;
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            String originHost = Uri.parse(appOrigin).getHost();
            if (host == null || originHost == null || !host.equalsIgnoreCase(originHost)) {
                return false;
            }
            String path = u.getPath();
            if (path == null) return false;
            // Collapse duplicate slashes ("//login" happens when the origin
            // ends in "/" and callers append "/login") — same resource.
            while (path.contains("//")) {
                path = path.replace("//", "/");
            }
            while (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            return expectedPath.equals(path);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Names of *-session cookies in a CookieManager header (values never
     * inspected). Used to clear ONLY the app's session state after the
     * server has confirmed logout/expiry — unrelated cookies untouched.
     */
    public static List<String> sessionCookieNames(String cookieHeader) {
        List<String> names = new ArrayList<>();
        if (cookieHeader == null) return names;
        for (String part : cookieHeader.split(";")) {
            String name = part.split("=", 2)[0].trim();
            if (name.toLowerCase().endsWith("session") && !name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Build the expiry Set-Cookie value used to invalidate one cookie for
     * the app origin (Max-Age=0). Cookie NAME only — values are dropped.
     */
    public static String expireCookieValue(String name) {
        return name + "=; Path=/; Max-Age=0";
    }
}
