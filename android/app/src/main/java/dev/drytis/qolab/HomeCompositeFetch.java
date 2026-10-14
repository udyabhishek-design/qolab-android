package dev.drytis.qolab;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * v1.5.43 — Composite Home fetch orchestrator.
 *
 * The dev-only aggregate GET /mobile/home was removed from the backend
 * (404 NotFoundHttpException with a fully valid session). Home is now
 * composed from three EXISTING endpoints, no backend changes:
 *
 *   GET /dashboard   (Inertia HTML) → user identity, next meeting, today
 *   GET /rooms       (Inertia HTML) → social rooms + department room
 *   GET /notifications/unread-count (JSON) → unread badge / today strip
 *
 * /dashboard and /rooms are fired CONCURRENTLY through the shared
 * WebView transport (each request has its own window variable and
 * bounded Java-driven poll loop — safe to interleave). Parsing and
 * composition are the pure JVM-tested functions in {@link HomeDataClient}.
 *
 * v1.5.45 PERFORMANCE: the optional unread-count request NO LONGER
 * blocks primary content. The moment both required pages are valid the
 * composed HomeData is delivered (unread=0 placeholder preserved from
 * any previous value by the caller); the unread request is then fired
 * INDEPENDENTLY and reported through {@link UnreadCallback} so the
 * caller can patch ONLY the badge / Today indicator. A stale, failed
 * or cross-account unread result is dropped by
 * {@link HomeActivity}'s generation check (never a full re-render).
 *
 * Single-shot contract — onReady fires EXACTLY once, on the main thread:
 *   200 + data            — fully composed HomeData (unread NOT awaited)
 *   401                   — server-PROVEN session expiry → route to Login
 *   STATUS_IO             — transport/timeout failure → retryable error
 *   STATUS_TRANSPORT_DEAD — renderer gone → SessionGate recheck
 *   STATUS_COMPOSITE_PARSE— 2xx body with no Inertia payload → error state
 */
final class HomeCompositeFetch {

    /** v1.5.43: 2xx body we could not parse (no Inertia page / no auth). */
    static final int STATUS_COMPOSITE_PARSE = -3;

    interface Callback {
        void onReady(int status, HomeDataClient.HomeData data, String errorCategory);
    }

    /** v1.5.45: independent, non-blocking unread outcome. */
    interface UnreadCallback {
        /**
         * @param userId   the userId of the composed HomeData this request
         *                 belongs to (0 = unknown — caller must drop)
         * @param unread   parsed unread_count (defensive 0 on odd bodies)
         * @param parseOk  false when the response was absent/malformed —
         *                 the caller keeps the current value
         */
        void onUnread(long userId, int unread, boolean parseOk);
    }

    private HomeCompositeFetch() {}

    /** Overload preserving the old single-callback signature. */
    static void fetch(QolabWebViewTransport transport, Callback cb) {
        fetch(transport, cb, null);
    }

    static void fetch(final QolabWebViewTransport transport, final Callback cb,
                      final UnreadCallback unreadCb) {
        final java.util.concurrent.atomic.AtomicInteger done =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final HomeDataClient.PartialDashboard[] dashOut =
                new HomeDataClient.PartialDashboard[1];
        final String[] roomsBody = new String[1]; // rooms HTML parked if
        // it settles before dashboard (department match needs the user's
        // department_id from dashboard)
        final boolean[] roomsParsed = new boolean[1];
        final int[] statusOut = new int[1];
        final String[] errOut = new String[1];
        final java.util.concurrent.atomic.AtomicBoolean delivered =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final Handler main = new Handler(Looper.getMainLooper());

        final Runnable settle = () -> {
            if (!delivered.compareAndSet(false, true)) return;
            if (statusOut[0] == 401) {
                cb.onReady(401, null, "auth");
                return;
            }
            if (statusOut[0] != 0) {
                cb.onReady(statusOut[0], null,
                        errOut[0] != null ? errOut[0] : "io");
                return;
            }
            HomeDataClient.PartialRooms pr = HomeDataClient.parseRoomsPage(
                    roomsBody[0],
                    dashOut[0] != null ? dashOut[0].userDepartmentId : null);
            // v1.5.45: deliver the PRIMARY content IMMEDIATELY — the
            // optional unread request is fired afterwards, independently,
            // and only ever patches the badge via unreadCb (never a
            // re-render, never a skeleton).
            HomeDataClient.HomeData data =
                    HomeDataClient.composeHomeData(dashOut[0], pr, 0);
            if (data == null) {
                cb.onReady(STATUS_COMPOSITE_PARSE, null, "inertia-missing");
                return;
            }
            final long payloadUserId = data.userId;
            cb.onReady(200, data, null);
            if (unreadCb == null) return;
            // Independent optional request — failures here are silently
            // non-fatal (caller keeps the current badge value).
            transport.getJson("/notifications/unread-count", u -> {
                int unread = 0;
                boolean ok = false;
                try {
                    if (u != null && u.body != null && u.status / 100 == 2) {
                        JSONObject o = new JSONObject(u.body);
                        unread = o.optInt("unread_count", 0);
                        ok = true;
                    }
                } catch (Exception ignored) {
                }
                unreadCb.onUnread(payloadUserId, unread, ok);
            });
        };

        // Parking guard: when rooms HTML arrives BEFORE dashboard, park the
        // body; the dashboard arm parses it once the department id is known.
        final Runnable tryFinishRooms = () -> {
            if (roomsParsed[0]) return;
            if (roomsBody[0] == null) return;
            if (dashOut[0] == null) return; // still waiting for dashboard
            HomeDataClient.PartialRooms pr =
                    HomeDataClient.parseRoomsPage(roomsBody[0],
                            dashOut[0].userDepartmentId);
            if (pr == null) {
                statusOut[0] = STATUS_COMPOSITE_PARSE;
                errOut[0] = "inertia-missing";
                settle.run();
                return;
            }
            roomsParsed[0] = true;
            markAndSettle(done, settle);
        };

        final java.util.function.BiConsumer<QolabWebViewTransport.Result, String> arm =
                (r, which) -> main.post(() -> {
                    if (delivered.get()) return;
                    if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD) {
                        statusOut[0] = r.status;
                        errOut[0] = "transport-dead";
                        settle.run();
                        return;
                    }
                    if (r.status == 401) {
                        statusOut[0] = 401;
                        errOut[0] = "auth";
                        settle.run();
                        return;
                    }
                    if (r.status == QolabWebViewTransport.Result.STATUS_IO
                            || r.body == null) {
                        statusOut[0] = r.status;
                        errOut[0] = r.errorCategory != null ? r.errorCategory : "io";
                        settle.run();
                        return;
                    }
                    if (r.status / 100 != 2) {
                        statusOut[0] = r.status;
                        errOut[0] = "http-" + r.status;
                        settle.run();
                        return;
                    }
                    if ("dashboard".equals(which)) {
                        dashOut[0] = HomeDataClient.parseDashboardPage(r.body);
                        if (dashOut[0] == null) {
                            statusOut[0] = STATUS_COMPOSITE_PARSE;
                            errOut[0] = "inertia-missing";
                            settle.run();
                            return;
                        }
                        tryFinishRooms.run(); // rooms may have been parked
                    } else {
                        roomsBody[0] = r.body;
                        tryFinishRooms.run();
                    }
                    markAndSettle(done, settle);
                });

        transport.getJson("/dashboard", r -> arm.accept(r, "dashboard"));
        transport.getJson("/rooms", r -> arm.accept(r, "rooms"));
    }

    private static void markAndSettle(java.util.concurrent.atomic.AtomicInteger done,
                                      Runnable settle) {
        if (done.incrementAndGet() >= 2) settle.run();
    }
}
