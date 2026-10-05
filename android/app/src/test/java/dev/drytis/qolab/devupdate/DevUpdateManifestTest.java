package dev.drytis.qolab.devupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** S3 (malformed manifest) + URL trust checks for DevUpdateManifest. */
public class DevUpdateManifestTest {

    private static final String HOST = "workpulse-gbpedt.drytis.dev";

    private static String manifest(int code, String name, String url) {
        return "{\"versionCode\":" + code + ",\"versionName\":\"" + name
                + "\",\"apkUrl\":\"" + url + "\"}";
    }

    @Test
    public void validManifestParses() {
        DevUpdateManifest m = DevUpdateManifest.parse(manifest(41, "1.5.25",
                "https://" + HOST + "/apk/qolab-dev-latest.apk"), HOST);
        assertNotNull(m);
        assertEquals(41, m.versionCode);
        assertEquals("1.5.25", m.versionName);
        assertTrue(m.apkUrl.endsWith("/apk/qolab-dev-latest.apk"));
    }

    @Test
    public void nonJsonBodyReturnsNull() {
        assertNull(DevUpdateManifest.parse("not json", HOST));
    }

    @Test
    public void missingFieldsReturnNull() {
        assertNull(DevUpdateManifest.parse("{\"foo\":1}", HOST));
        assertNull(DevUpdateManifest.parse("{\"versionCode\":41}", HOST));
        assertNull(DevUpdateManifest.parse("{\"versionCode\":41,\"versionName\":\"x\"}", HOST));
        assertNull(DevUpdateManifest.parse("{\"versionCode\":0,\"versionName\":\"x\","
                + "\"apkUrl\":\"https://" + HOST + "/a.apk\"}", HOST));
    }

    @Test
    public void nullBodyReturnsNull() {
        assertNull(DevUpdateManifest.parse(null, HOST));
    }

    @Test
    public void httpUrlRejected() {
        assertNull(DevUpdateManifest.parse(manifest(41, "1.5.25",
                "http://" + HOST + "/apk/qolab-dev-latest.apk"), HOST));
    }

    @Test
    public void foreignHostRejected() {
        assertNull(DevUpdateManifest.parse(manifest(41, "1.5.25",
                "https://evil.example.com/apk.apk"), HOST));
    }

    @Test
    public void subdomainOfOriginHostAccepted() {
        assertNotNull(DevUpdateManifest.parse(manifest(41, "1.5.25",
                "https://cdn." + HOST + "/apk/x.apk"), HOST));
    }
}
