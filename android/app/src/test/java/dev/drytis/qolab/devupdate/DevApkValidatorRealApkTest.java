package dev.drytis.qolab.devupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

/**
 * End-to-end validation of the updater against the REAL build artifacts:
 * the freshly built APK of THIS very build (S8-adjacent, S12) — proves the
 * AXML parser in DevApkValidator reads real-world AndroidManifest.xml files,
 * not just the synthetic ones in DevApkValidatorTest.
 */
public class DevApkValidatorRealApkTest {

    private static final String APK_PATH =
            "build/outputs/apk/debug/app-debug.apk";

    @Test
    public void realApkIsValidAndNewerThanV40() {
        File apk = new File(APK_PATH);
        String path = apk.getAbsolutePath();
        if (!apk.exists()) {
            // CI safety: skip when no fresh build exists (tests may run pre-build)
            System.out.println("SKIP: " + path + " not built yet");
            return;
        }
        DevApkValidator.Result r = DevApkValidator.validate(path,
                "dev.drytis.qolab", 40);
        assertTrue("real APK must validate: " + r.reason, r.ok);
        assertEquals("dev.drytis.qolab", r.packageName);
        assertTrue("real APK versionCode must exceed 40", r.versionCode > 40);
        System.out.println("real APK validated: " + r.packageName + " v" + r.versionCode);
    }

    @Test
    public void realApkRejectedAgainstForeignPackageExpectation() {
        File apk = new File(APK_PATH);
        if (!apk.exists()) {
            System.out.println("SKIP: no fresh build");
            return;
        }
        DevApkValidator.Result r = DevApkValidator.validate(apk.getAbsolutePath(),
                "com.somebody.else", 40);
        assertFalse(r.ok);
        assertTrue(r.reason, r.reason.contains("package mismatch"));
    }

    @Test
    public void realApkRejectedWhenNotNewer() {
        File apk = new File(APK_PATH);
        if (!apk.exists()) {
            System.out.println("SKIP: no fresh build");
            return;
        }
        // installed >= remote → must reject
        DevApkValidator.Result r = DevApkValidator.validate(apk.getAbsolutePath(),
                "dev.drytis.qolab", Integer.MAX_VALUE);
        assertFalse(r.ok);
        assertTrue(r.reason, r.reason.contains("not newer"));
    }
}
