package dev.drytis.qolab;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * v1.4.11: in-memory, session-scoped DEBUG trace of logout attempts.
 * PHYSICAL-DEVICE OBSERVABILITY — the user has no ADB/Logcat, so this is
 * rendered on-screen (MainActivity "Auth Debug" overlay + LoginActivity
 * "Last logout diagnostics" footer, DEBUG builds only).
 *
 * SAFETY: fields are booleans, HTTP statuses, durations and URL *paths*
 * only. Never email/password/cookie values/XSRF tokens/headers/bodies.
 */
public final class LogoutTrace {

    public static final class Attempt {
        public int tapIndex;
        public long tStartMs;
        public boolean uiLogoutTapped;      // XHR send() on /logout observed
        public boolean bridgeInvoked;       // QolabNative.logout() reached
        public boolean nativeTransactionStarted;
        public boolean csrfStarted;
        public int csrfStatus;
        public long csrfMs = -1;
        public boolean postStarted;
        public int postStatus;
        public long postMs = -1;
        public boolean had419;
        public int httpAttempts;
        public boolean serverConfirmed;
        public String ioError;              // exception class only
        public String webviewUrlBefore;     // lastLoadedUrl path
        public String webviewUrlAfter;
        public boolean transitionRequested;
        public boolean nativeLoginLaunched;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ATTEMPT ").append(tapIndex).append('\n');
            sb.append("  Logout tapped (XHR /logout): ").append(yn(uiLogoutTapped)).append('\n');
            sb.append("  Bridge invoked: ").append(yn(bridgeInvoked)).append('\n');
            sb.append("  Native transaction started: ").append(yn(nativeTransactionStarted)).append('\n');
            sb.append("  CSRF GET: ").append(csrfStarted ? String.valueOf(csrfStatus) : "not started");
            if (csrfMs >= 0) sb.append(" (").append(csrfMs).append(" ms)");
            sb.append('\n');
            sb.append("  Logout POST: ").append(postStarted ? String.valueOf(postStatus) : "not started");
            if (postMs >= 0) sb.append(" (").append(postMs).append(" ms)");
            sb.append('\n');
            sb.append("  419 occurred: ").append(yn(had419)).append('\n');
            sb.append("  HTTP attempts: ").append(httpAttempts).append('\n');
            sb.append("  Server confirmed logout: ").append(yn(serverConfirmed)).append('\n');
            if (ioError != null) sb.append("  IO error class: ").append(ioError).append('\n');
            sb.append("  WebView URL before: ").append(path(webviewUrlBefore)).append('\n');
            sb.append("  WebView URL after: ").append(path(webviewUrlAfter)).append('\n');
            sb.append("  Transition requested: ").append(yn(transitionRequested)).append('\n');
            sb.append("  Native Login launched: ").append(yn(nativeLoginLaunched));
            return sb.toString();
        }
    }

    private static String yn(boolean b) { return b ? "Yes" : "No"; }

    /** Origin-stripped path only — full URLs may embed nothing sensitive,
     *  but paths are all the diagnostics need. */
    private static String path(String url) {
        if (url == null) return "—";
        int idx = url.indexOf("//");
        int cut = idx >= 0 ? url.indexOf('/', idx + 2) : 0;
        return cut >= 0 && cut < url.length() ? url.substring(cut) : url;
    }

    /** All attempts this process — survives page navigations & activity
     *  recreation (static), cleared only by process death. */
    public static final List<Attempt> attempts = new CopyOnWriteArrayList<>();

    /** The attempt currently in flight (null when idle). */
    public static volatile Attempt current;

    /** Set when the injected JS confirms the takeover + bridge are live. */
    public static volatile boolean takeoverInstalled;

    /** Set when the JS bridge reports QolabNative reachable from the page. */
    public static volatile boolean bridgeAvailable;

    public static Attempt begin(String webviewUrlBefore) {
        Attempt a = new Attempt();
        a.tapIndex = attempts.size() + 1;
        a.tStartMs = android.os.SystemClock.elapsedRealtime();
        a.webviewUrlBefore = webviewUrlBefore;
        attempts.add(a);
        current = a;
        return a;
    }

    public static boolean hasData() { return !attempts.isEmpty(); }

    public static String render() {
        if (attempts.isEmpty()) return "No logout attempts recorded this session.";
        StringBuilder sb = new StringBuilder();
        for (Attempt a : attempts) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(a.toString());
        }
        return sb.toString();
    }

    private LogoutTrace() {
    }
}
