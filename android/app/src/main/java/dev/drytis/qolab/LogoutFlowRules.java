package dev.drytis.qolab;

import java.util.List;

/**
 * v1.5.45 — pure rules for the NATIVE logout flow (menu sheet →
 * confirmation dialog → webview POST /logout → transition to Login).
 *
 * Extracted from the flow so every gating decision is JVM-testable.
 * The HTTP transaction itself is NOT duplicated: HomeActivity evaluates
 * the EXISTING AuthNavRules.buildWebviewLogoutJs() inside the
 * authenticated session WebView (native HTTP logout is proven-419 and
 * forbidden). MainActivity.handleLoggedOut()-equivalent cleanup for the
 * native stack is decided by the rules below.
 */
public final class LogoutFlowRules {

    private LogoutFlowRules() {}

    /** Server status counts as a CONFIRMED logout (2xx or 3xx redirect). */
    public static boolean serverConfirmed(int httpStatus) {
        return httpStatus >= 200 && httpStatus < 400;
    }

    /**
     * Actions to take after a CONFIRMED server logout, in order.
     * "release_transport" destroys the adopted session WebView so no
     * stale in-flight fetch can ever write into another account's UI.
     */
    public static final String[] CONFIRMED_ACTIONS = {
            "clear_home_cache",      // cachedData / cachedForUserId → gone
            "shutdown_realtime",     // ChatRealtime (Reverb) hub
            "cancel_pending_fetches",// Home composite + unread callbacks
            "release_transport",     // destroy session WebView
            "expire_session_cookies",// app-origin *-session only
            "route_to_login",        // NEW_TASK | CLEAR_TASK (Back-proof)
    };

    /**
     * After a FAILED logout attempt the session MUST remain fully
     * intact — nothing from CONFIRMED_ACTIONS may run.
     */
    public static boolean failureKeepsEverything() {
        return true;
    }

    /** Whether the confirmation dialog is required before any logout. */
    public static boolean confirmationRequired() {
        return true;
    }

    /**
     * Account-switch safety: a Home payload belongs to the logged-in
     * user only when its userId matches the account that was current
     * when the request started. A late callback from a previous
     * account (or after logout) must be dropped.
     *
     * @param payloadUserId   userId carried by the fetched payload
     * @param currentUserId   userId of the active session (0 = none)
     * @param requestAborted  true once logout/refresh invalidated the cycle
     */
    public static boolean payloadBelongsToCurrentSession(long payloadUserId,
                                                         long currentUserId,
                                                         boolean requestAborted) {
        if (requestAborted) return false;
        if (currentUserId == 0) return false;   // logged out
        if (payloadUserId == 0) return false;   // unidentified payload
        return payloadUserId == currentUserId;
    }

    /**
     * Unread-count application rule: a stale/late unread result (previous
     * request generation, other user, post-logout) must never touch the
     * badge. "Preserve current value until a valid replacement arrives."
     */
    public static boolean applyUnreadResult(long resultUserId,
                                            long currentUserId,
                                            boolean requestAborted,
                                            boolean parseOk) {
        if (!parseOk) return false;
        return payloadBelongsToCurrentSession(resultUserId, currentUserId,
                requestAborted);
    }

    /** True when the session cookie must be treated as already gone
     *  (used to short-circuit the dialog when there is nothing to end). */
    public static boolean sessionCookieLooksPresent(String cookieHeader) {
        if (cookieHeader == null) return false;
        List<String> names = AuthNavRules.sessionCookieNames(cookieHeader);
        return !names.isEmpty();
    }
}
