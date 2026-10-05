package dev.drytis.qolab.devupdate;

/**
 * Pure decision logic for the Development update check. No Android imports —
 * fully unit-testable on the JVM.
 */
public final class DevUpdateChecker {

    public enum Decision { UPDATE_AVAILABLE, UP_TO_DATE, INVALID }

    private DevUpdateChecker() {
    }

    /**
     * @param manifest      parsed remote manifest (null → INVALID, do nothing)
     * @param installedCode BuildConfig.VERSION_CODE of the running build
     */
    public static Decision check(DevUpdateManifest manifest, int installedCode) {
        if (manifest == null) return Decision.INVALID;
        if (installedCode <= 0) return Decision.INVALID;
        if (manifest.versionCode > installedCode) return Decision.UPDATE_AVAILABLE;
        return Decision.UP_TO_DATE;
    }
}
