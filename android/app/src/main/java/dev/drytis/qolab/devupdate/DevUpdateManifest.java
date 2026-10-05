package dev.drytis.qolab.devupdate;

import org.json.JSONObject;

/**
 * Parsed Development update manifest (latest.json).
 *
 * Strict parsing: ANY malformed shape yields null from {@link #parse} — the
 * caller then simply does nothing. No field is trusted beyond validation.
 */
public final class DevUpdateManifest {

    public final int versionCode;
    public final String versionName;
    public final String apkUrl;

    private DevUpdateManifest(int versionCode, String versionName, String apkUrl) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apkUrl = apkUrl;
    }

    /**
     * @param body        raw manifest body (may be null / non-JSON)
     * @param originHost  expected distribution host, e.g. "workpulse-gbpedt.drytis.dev".
     *                    The apkUrl must be https and end with "." + originHost (or be
     *                    exactly originHost). Null disables the host check.
     * @return manifest or null when anything is malformed / untrusted.
     */
    public static DevUpdateManifest parse(String body, String originHost) {
        if (body == null) return null;
        try {
            JSONObject o = new JSONObject(body);
            int code = o.optInt("versionCode", -1);
            if (code <= 0) return null;
            String name = o.optString("versionName", null);
            if (name == null || name.trim().isEmpty()) return null;
            String url = o.optString("apkUrl", null);
            if (!isTrustedApkUrl(url, originHost)) return null;
            return new DevUpdateManifest(code, name.trim(), url.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** https-only and (when given) same-distribution-host URL check. */
    static boolean isTrustedApkUrl(String url, String originHost) {
        if (url == null) return false;
        String u = url.trim();
        if (!u.startsWith("https://")) return false;
        if (originHost == null || originHost.isEmpty()) return true;
        try {
            java.net.URI uri = java.net.URI.create(u);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            String expected = originHost.toLowerCase();
            return host.equals(expected) || host.endsWith("." + expected);
        } catch (Exception e) {
            return false;
        }
    }
}
