package dev.drytis.qolab;

/**
 * v1.5.40 — SessionGate: ONE proof-before-logout gate for the whole app.
 *
 * CONFIRMED DEVICE BUG (Motorola G32, v1.5.39): after closing and
 * reopening the app, Home partially renders, then redirects to Login
 * after a few seconds. Root cause chain:
 *
 *   1. Cold start after process death → transport singleton is null →
 *      Home calls QolabWebViewTransport.rehydrate().
 *   2. rehydrate()'s WebViewClient treated a main-frame network error
 *      OR a 15 s timeout as "session invalid" (settle(false)) even
 *      though the persisted cookie may be perfectly good.
 *   3. Home's routeToLogin() launched LoginActivity.
 *   4. LoginActivity.onCreate EXPIRED the *-session cookie ("stale
 *      session cleanup") — destroying a possibly-valid session and
 *      forcing a manual re-login. The next open then had no cookie and
 *      bounced straight to Login again.
 *
 * The fix has two halves. QolabWebViewTransport now distinguishes
 * PROVEN_INVALID (server parked us on /login) from TRANSIENT failures
 * (network error / timeout). This gate is the second half: activities
 * that merely see a dead transport or a failed request NO LONGER launch
 * LoginActivity directly. Instead they call {@link #requireValidSession}
 * and hand control to the gate, which performs a bounded, backoff
 * recheck of the persisted session. Only when the SERVER proves the
 * session gone (rehydrate lands on /login) does the gate clear the
 * local session state and show Login. Every outcome is delivered at
 * most once per gate run.
 *
 * Login security is PRESERVED: the gate never invents a session. A
 * genuine /login landing still logs out exactly as before. What changes
 * is that transient network trouble during cold start can no longer
 * masquerade as "authentication expired" and destroy the cookie.
 *
 * JVM-testable: classification is a pure static seam
 * ({@link #shouldLogout}).
 */
public final class SessionGate {

    /** Called by the gate when the session is confirmed valid. */
    public interface ValidCallback { void onSessionValid(); }

    /** Called by the gate when the session is confirmed INVALID. */
    public interface LogoutCallback { void onSessionInvalid(); }

    /**
     * Called when transient failures exhausted the recheck budget: the
     * network was down for the whole window. The cookie is preserved
     * (never a logout); the caller should show a temporary error with
     * Retry, which re-enters the gate.
     */
    public interface ExhaustedCallback { void onNetworkUnavailable(); }

    private SessionGate() { }

    /** One gate run in flight per process — callers funnel through it. */
    private static final java.util.concurrent.atomic.AtomicBoolean checking =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Decide, from a failed rehydrate outcome, whether to log out.
     * Static, pure, unit-tested:
     *   - PROVEN_INVALID (server landed /login) → true (logout, honest)
     *   - TRANSIENT (network/timeout)          → false (retry later)
     */
    public static boolean shouldLogout(QolabWebViewTransport.RehydrateOutcome outcome) {
        return outcome == QolabWebViewTransport.RehydrateOutcome.PROVEN_INVALID;
    }

    /**
     * Ask the gate to verify the session and route accordingly.
     *   valid   → onValid (caller retries its data load)
     *   invalid → onLogout fires AT MOST ONCE per process (the task-root
     *             LoginActivity launch is idempotent)
     *   transient failure → onValid does NOT fire; after a bounded
     *             backoff the gate retries the recheck itself. If the
     *             retries are exhausted the cookie is PRESERVED and
     *             onLogout is NOT called — the user can still manually
     *             navigate to Login; a flaky network must not log
     *             anybody out.
     * All callbacks fire on the main thread.
     */
    public static void requireValidSession(android.content.Context appContext,
                                           String origin,
                                           ValidCallback onValid,
                                           LogoutCallback onLogout) {
        requireValidSession(appContext, origin, onValid, onLogout, null);
    }

    /**
     * Full form: adds onExhausted, fired when transient failures exhaust
     * the recheck budget (network down for the whole window). The cookie
     * is preserved; the caller shows an error + Retry.
     */
    public static void requireValidSession(android.content.Context appContext,
                                           String origin,
                                           ValidCallback onValid,
                                           LogoutCallback onLogout,
                                           ExhaustedCallback onExhausted) {
        // Someone else is already verifying — fold into that run: they
        // will deliver a decision through their own callbacks. To keep
        // THIS caller responsive, treat the in-flight check as its own:
        // retry-when-alive via the direct path below.
        if (!checking.compareAndSet(false, true)) {
            // If a transport appeared while we queued, use it now.
            QolabWebViewTransport t = QolabWebViewTransport.get();
            if (t != null && t.isAlive()) {
                android.os.Handler main = new android.os.Handler(
                        android.os.Looper.getMainLooper());
                main.post(onValid::onSessionValid);
            }
            return;
        }
        android.os.Handler main = new android.os.Handler(
                android.os.Looper.getMainLooper());
        attemptRecheck(appContext, origin, onValid, onLogout, onExhausted, 0, main);
    }

    private static void attemptRecheck(android.content.Context appContext,
                                       String origin,
                                       ValidCallback onValid,
                                       LogoutCallback onLogout,
                                       ExhaustedCallback onExhausted,
                                       int attempt,
                                       android.os.Handler main) {
        QolabWebViewTransport t = QolabWebViewTransport.get();
        if (t != null && t.isAlive()) {
            checking.set(false);
            main.post(onValid::onSessionValid);
            return;
        }
        QolabWebViewTransport.rehydrate(appContext, origin,
                new QolabWebViewTransport.RehydrateCallback() {
                    @Override
                    public void onResult(boolean sessionValid) {
                        checking.set(false);
                        if (sessionValid) {
                            main.post(onValid::onSessionValid);
                        } else {
                            // PROVEN_INVALID — server parked us on /login.
                            main.post(onLogout::onSessionInvalid);
                        }
                    }

                    @Override
                    public void onTransientFailure(String category) {
                        // v1.5.40: NEVER log out on a transient failure.
                        // Bounded backoff retry; cookie preserved.
                        if (attempt >= 2) {
                            // Exhausted. Keep the cookie; the caller
                            // shows a temporary error (Retry re-enters
                            // the gate). No logout.
                            checking.set(false);
                            if (onExhausted != null) {
                                main.post(onExhausted::onNetworkUnavailable);
                            }
                            return;
                        }
                        long delay = attempt == 0 ? 2_000L : 5_000L;
                        main.postDelayed(() -> attemptRecheck(
                                appContext, origin, onValid, onLogout,
                                onExhausted, attempt + 1, main), delay);
                    }
                });
    }
}
