package dev.drytis.qolab.devupdate;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** S1 (current/equal, older) + S2 (newer) decision logic. */
public class DevUpdateCheckerTest {

    private static DevUpdateManifest manifest(int code) {
        // Host check irrelevant for the decision — pass null to disable.
        return DevUpdateManifest.parse(
                "{\"versionCode\":" + code + ",\"versionName\":\"x.y.z\","
                        + "\"apkUrl\":\"https://example.invalid/a.apk\"}", null);
    }

    @Test
    public void equalVersionIsUpToDate() {
        assertEquals(DevUpdateChecker.Decision.UP_TO_DATE,
                DevUpdateChecker.check(manifest(40), 40));
    }

    @Test
    public void olderVersionIsUpToDate() {
        assertEquals(DevUpdateChecker.Decision.UP_TO_DATE,
                DevUpdateChecker.check(manifest(39), 40));
    }

    @Test
    public void newerVersionIsUpdateAvailable() {
        assertEquals(DevUpdateChecker.Decision.UPDATE_AVAILABLE,
                DevUpdateChecker.check(manifest(41), 40));
    }

    @Test
    public void nullManifestIsInvalid() {
        assertEquals(DevUpdateChecker.Decision.INVALID,
                DevUpdateChecker.check(null, 40));
    }

    @Test
    public void nonPositiveInstalledCodeIsInvalid() {
        assertEquals(DevUpdateChecker.Decision.INVALID,
                DevUpdateChecker.check(manifest(41), 0));
    }
}
