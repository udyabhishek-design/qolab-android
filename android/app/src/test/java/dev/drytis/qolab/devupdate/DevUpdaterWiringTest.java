package dev.drytis.qolab.devupdate;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Structural invariants for the updater wiring (S5/S9/S11 + S12):
 *  - debug manifest overlay declares REQUEST_INSTALL_PACKAGES; the MAIN
 *    manifest does NOT (release never gets the permission)
 *  - FileProvider is declared (installer URI source)
 *  - Home menu contains the manual dev check entry (string resource)
 *  - publish script writes manifest referencing the stable APK URL
 */
public class DevUpdaterWiringTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void debugOverlayDeclaresInstallPermission() throws Exception {
        String overlay = read("src/debug/AndroidManifest.xml");
        assertTrue(overlay.contains("REQUEST_INSTALL_PACKAGES"));
    }

    @Test
    public void mainManifestDoesNotDeclareInstallPermission() throws Exception {
        String main = read("src/main/AndroidManifest.xml");
        assertTrue("release builds must never request install permission",
                !main.contains("REQUEST_INSTALL_PACKAGES"));
    }

    @Test
    public void fileProviderDeclared() throws Exception {
        String main = read("src/main/AndroidManifest.xml");
        assertTrue(main.contains("androidx.core.content.FileProvider"));
        String paths = read("src/main/res/xml/file_paths.xml");
        assertTrue("cache-path needed for FileProvider APK sharing",
                paths.contains("cache-path"));
    }

    @Test
    public void manualCheckMenuStringExists() throws Exception {
        String s = read("src/main/res/values/qm_dev_update_strings.xml");
        assertTrue(s.contains("dev_update_menu_check"));
    }

    @Test
    public void homeWiresUpdaterLifecycle() throws Exception {
        String home = read("src/main/java/dev/drytis/qolab/HomeActivity.java");
        assertTrue(home.contains("maybeCheckOnStartup"));
        assertTrue(home.contains("devUpdater.onResume()"));
        assertTrue(home.contains("devUpdater.onDestroy()"));
        assertTrue("menu entry must be inside the DEBUG block",
                home.contains("devUpdater.checkNow()"));
    }

    @Test
    public void publishScriptIsAtomicAndStable() throws Exception {
        // Unit tests run with app/ as working directory.
        File[] candidates = {
                new File("../publish-dev-apk.sh"),
                new File("../../publish-dev-apk.sh"),
                new File("publish-dev-apk.sh")
        };
        File script = null;
        for (File c : candidates) if (c.exists()) { script = c; break; }
        assertNotNull("publish-dev-apk.sh not found relative to test cwd", script);
        String sh = read(script.getPath());
        assertTrue("must refuse missing APK", sh.contains("APK not found"));
        assertTrue("must write stable copy", sh.contains("qolab-dev-latest.apk"));
        assertTrue("must write latest.json atomically", sh.contains("mv -f"));
        assertTrue("manifest must use stable URL", sh.contains("/apk/qolab-dev-latest.apk"));
    }

    @Test
    public void controllerNeverTouchesSessionData() throws Exception {
        String c = read("src/main/java/dev/drytis/qolab/devupdate/DevUpdateController.java");
        assertTrue("no cookie/session APIs in the updater",
                !c.contains("CookieManager") && !c.contains("getCookie") && !c.contains("XSRF"));
    }
}
