package dev.drytis.qolab;

import java.util.List;
import java.util.Map;

/**
 * Single authoritative session-cookie architecture for native screens.
 *
 * Lesson from Login (and now Home V1): the authenticated Laravel/Fortify
 * session lives in the ANDROID WEBVIEW CookieManager. Any native HTTP
 * client that keeps its own cookie jar (or a one-shot snapshot taken at
 * the wrong moment) races the WebView cookie pipeline and sends stale or
 * anonymous session ids → 401s that look like "logged out".
 *
 * This class is the ONLY sanctioned bridge:
 *   - cookies are read from the authoritative store AT REQUEST TIME
 *   - any Set-Cookie response headers are synchronized back into the store
 *   - the store is flushed after mutation
 *   - there is no second, independent cookie universe
 *
 * The store is an interface so unit tests can run without Android.
 */
public final class QolabCookieSession {

    /** Authoritative cookie store. Implementations must be thread-safe. */
    public interface CookieStore {
        /** Current cookie header for a URL, or null. */
        String cookieHeaderFor(String url);

        /** Apply one raw Set-Cookie value for a URL. */
        void setCookie(String url, String setCookieValue);

        /** Persist (no-op for in-memory stores). */
        void flush();
    }

    /** Production store backed by the WebView CookieManager. */
    public static class WebViewCookieStore implements CookieStore {
        @Override
        public String cookieHeaderFor(String url) {
            try {
                return android.webkit.CookieManager.getInstance().getCookie(url);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void setCookie(String url, String setCookieValue) {
            try {
                android.webkit.CookieManager.getInstance().setCookie(url, setCookieValue);
            } catch (Exception ignored) {
            }
        }

        @Override
        public void flush() {
            try {
                android.webkit.CookieManager.getInstance().flush();
            } catch (Exception ignored) {
            }
        }
    }

    /** True if a cookie header contains a *-session cookie (name check only). */
    public static boolean containsSessionCookie(String cookieHeader) {
        if (cookieHeader == null) return false;
        for (String part : cookieHeader.split(";")) {
            String name = part.split("=", 2)[0].trim();
            if (name.toLowerCase().endsWith("session")) return true;
        }
        return false;
    }

    /**
     * Synchronize response Set-Cookie headers back into the authoritative
     * store and flush. Safe on null/absent headers.
     */
    public static void synchronize(Map<String, List<String>> headers, String url, CookieStore store) {
        if (headers == null || store == null) return;
        List<String> setCookies = headers.get("Set-Cookie");
        if (setCookies == null) setCookies = headers.get("set-cookie");
        if (setCookies == null || setCookies.isEmpty()) return;
        for (String raw : setCookies) {
            if (raw == null || raw.isEmpty()) continue;
            store.setCookie(url, raw);
        }
        store.flush();
    }

    private QolabCookieSession() {}
}
