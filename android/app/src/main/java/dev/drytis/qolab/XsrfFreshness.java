package dev.drytis.qolab;

/**
 * Bounded wait-for-change poll used to read XSRF-TOKEN (and session)
 * values only AFTER the CookieManager has caught up with a Set-Cookie
 * response.
 *
 * Why this exists (v1.5.2): reading CookieManager.getCookie() the instant
 * an HTTP response resolves can return the PREVIOUS cookie value — the
 * WebView CookieManager lags the network stack's Set-Cookie commit. The
 * login POST then carried a fresh rotated session with a STALE XSRF
 * token → Laravel 419 ("session expired"). The fix is to snapshot the
 * value BEFORE the request and only accept a value that has CHANGED.
 *
 * Pure Java — the read is an injected supplier so tests run without Android.
 */
public final class XsrfFreshness {

    /**
     * Read a cookie value only once it differs from {@code before}.
     *
     * @param before     value snapshot taken BEFORE the request; null means
     *                   "any non-null value is fresh"
     * @param read       value supplier (must be quick + safe to call repeatedly)
     * @param maxWaitMs  bounded total wait (e.g. 2000)
     * @param sleepMs    poll interval (e.g. 50); sleep happens between reads
     * @return the fresh value, or null if the deadline passed without change
     */
    public static String awaitChanged(String before, java.util.function.Supplier<String> read,
            long maxWaitMs, long sleepMs) {
        if (read == null) return null;
        final long deadline = System.currentTimeMillis() + Math.max(0, maxWaitMs);
        while (true) {
            String v = read.get();
            if (v != null && (before == null || !v.equals(before))) {
                return v;
            }
            if (System.currentTimeMillis() >= deadline) return null;
            try {
                Thread.sleep(Math.max(1, sleepMs));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * Read a cookie value that exists, waiting up to maxWaitMs for it to
     * appear (freshness not required).
     */
    public static String awaitPresent(java.util.function.Supplier<String> read,
            long maxWaitMs, long sleepMs) {
        return awaitChanged("\u0000never-matches\u0000", read, maxWaitMs, sleepMs);
    }

    private XsrfFreshness() {}
}
