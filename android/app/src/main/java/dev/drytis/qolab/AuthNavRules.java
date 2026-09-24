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
     * v1.4.10: fetch-takeover (kept for completeness). NOTE: the web app's
     * Inertia core sends requests via XMLHttpRequest (verified in the
     * shipped bundle: doRequest(){ new XMLHttpRequest ... }), so the REAL
     * interception happens in buildXhrTakeoverJs().
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

    /**
     * v1.4.11: XHR takeover — the ACTUAL transport the web app uses for
     * Inertia visits (XMLHttpRequest.open/send, verified in the shipped
     * bundle). A POST to any /logout URL (relative or absolute) is routed
     * ENTIRELY to the native bridge: the original XHR is never sent, so
     * one tap = one server transaction and Inertia's normal post-logout
     * DOM swap to /login never happens — native Android owns the
     * transition. The bridge is invoked asynchronously and the patched
     * XHR responds 204 so Inertia's visit machinery resolves cleanly
     * without following a redirect.
     */
    public static String buildXhrTakeoverJs() {
        return "(function(){"
            + "  if (window.__qolabXhrTakeover) return;"
            + "  window.__qolabXhrTakeover = true;"
            + "  var OrigXhr = window.XMLHttpRequest;"
            + "  if (!OrigXhr) return;"
            + "  function patched(){"
            + "    var xhr = new OrigXhr();"
            + "    var method = 'GET', url = '', captured = false;"
            + "    var open = xhr.open;"
            + "    xhr.open = function(m, u){"
            + "      method = (m || 'GET').toString().toUpperCase();"
            + "      url = (u || '').toString();"
            + "      return open.apply(xhr, arguments);"
            + "    };"
            + "    var send = xhr.send;"
            + "    xhr.send = function(body){"
            + "      try {"
            + "        var abs = url;"
            + "        if (url.indexOf('/') === 0) abs = location.origin + url;"
            + "        if (method === 'POST' && /\\/logout(\\?|#|$)/.test(abs)) {"
            + "          captured = true;"
            + "          window.QolabNative.logout();"
            + "          return;"
            + "        }"
            + "      } catch (e) {}"
            + "      return send.apply(xhr, arguments);"
            + "    };"
            + "    return xhr;"
            + "  }"
            + "  patched.prototype = OrigXhr.prototype;"
            + "  Object.defineProperty(window, 'XMLHttpRequest', {"
            + "    configurable: true, writable: true, value: patched"
            + "  });"
            + "}())";
    }

    /** True when a request line matches POST + /logout — mirrors the JS regex. */
    public static boolean isLogoutXhr(String method, String url) {
        if (method == null || url == null) return false;
        return "POST".equalsIgnoreCase(method.trim())
                && url.replaceAll("[?#].*$", "").endsWith("/logout");
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
