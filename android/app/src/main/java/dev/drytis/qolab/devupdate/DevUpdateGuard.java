package dev.drytis.qolab.devupdate;

import dev.drytis.qolab.BuildConfig;

/**
 * Build-variant protection for the Development updater.
 *
 * The updater must NEVER run (or even reach the network) from a production
 * build. The guard is a compile-time constant per build type:
 *
 *  - DEBUG   → true  (Development build)
 *  - RELEASE → false — every call site short-circuits on this final constant,
 *    so the checker/download/install code is provably unreachable in release.
 */
public final class DevUpdateGuard {

    private DevUpdateGuard() {
    }

    /** True only in the Development build. */
    public static boolean isEnabled() {
        return BuildConfig.DEBUG;
    }
}
