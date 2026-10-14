package dev.drytis.qolab;

import android.annotation.SuppressLint;

import dev.drytis.qolab.BuildConfig;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Native Home (Home V1) — the first native content screen after login.
 *
 * Structure follows the approved reference exactly:
 *   header → greeting → next meeting → social rooms → department →
 *   continue working → today strip → fixed bottom navigation.
 *
 * All content comes from GET /mobile/home (dev-only aggregate) using the
 * existing WebView session — the Login architecture is untouched, and the
 * Logout implementation is deliberately NOT modified.
 */
public class HomeActivity extends AppCompatActivity {

    private static final String TAG = "QolabHome";
    private static final int AVATAR_MAX = 4;

    // header / greeting
    private TextView greetingDate, greetingName;
    private View notificationBadge;

    // meeting
    private View meetingCard, meetingSkeleton, meetingEmpty, meetingError;
    private TextView meetingTime, meetingTitle, meetingOverflow;
    private LinearLayout meetingAvatars;

    // rooms
    private LinearLayout roomsRow;
    private LinearLayout roomsSkeletonRow;
    private View roomsEmpty;
    private TextView roomsSeeAll;

    // department
    private View deptCard, deptSkeleton, deptEmpty, deptError;
    private TextView deptName, deptOnline, deptOverflow, deptEnter, deptDescription;
    private LinearLayout deptAvatars;

    // continue working
    private View convCard, convSkeleton, convEmpty, taskCard, taskSkeleton, taskEmpty, bothEmpty;
    private TextView convName, convMeta, taskTitle, taskDue;

    // today
    private TextView todayTasks, todayMeetings, todayUpdates;

    // bottom nav
    private LinearLayout navHome, navChat;
    private FrameLayout navAskDrytis;

    private HomeDataClient.HomeData data;
    private volatile boolean fetching = false;

    /** v1.5.41: session-scoped in-memory Home cache — KEYED BY USER so one
     *  Member's data is NEVER shown to another Member. In-memory only
     *  (cleared on process death and logout); survives warm activity
     *  restarts and navigation between native screens. Used for instant
     *  first paint; ALWAYS followed by a fresh fetch (stale-while-
     *  revalidate), so data is at most one navigation old and live. */
    private static HomeDataClient.HomeData cachedData;
    private static long cachedForUserId = Long.MIN_VALUE;

    /** v1.5.41: automatic network recovery (ConnectivityWatcher). */
    private Object netWatcher;

    /** v1.5.41: timeline for the DEBUG diagnostics panel (performance). */
    volatile long tCreateStartMs, tFirstContentMs, tDataMs;

    /** Fresh-fetch threshold for background refresh on resume (10 min). */
    private static final long STALE_MS = 10L * 60 * 1_000_000_000L; // ns
    /** Wall-clock of the last successful fetch (System.nanoTime). */
    private volatile long lastFetchNs = Long.MIN_VALUE;

    private long cachedUserId() {
        return data != null ? data.userId : Long.MIN_VALUE;
    }

    private static long cachedForUserIdOf(HomeDataClient.HomeData d) {
        return d != null ? d.userId : Long.MIN_VALUE;
    }

    /** v1.5.25: Development-only in-app updater (null-safe; inert in release). */
    private dev.drytis.qolab.devupdate.DevUpdateController devUpdater;

    /** DEBUG-only diagnostics for the new (v1.5.15) architecture:
     *  the authenticated WebView transport fetches Home data. Booleans and
     *  counts only — never cookie values, tokens, credentials, or body
     *  contents (body LENGTH only). */
    static final class HomeDiag {
        volatile boolean requestMade;
        volatile int httpStatus = -2; // -2 never run, -1 IO, -3 transport dead
        volatile long durationMs;
        volatile boolean payloadReceived;
        volatile int bodyLength = -1;      // LENGTH ONLY, never contents
        volatile boolean parseAttempted;
        volatile boolean parseOk;
        volatile boolean userPresent;
        volatile int socialRoomCount = -1;
        volatile boolean departmentPresent;
        volatile boolean nextMeetingPresent;
        volatile boolean renderExecuted;
        volatile boolean skeletonsCleared;
        volatile boolean loginTransition;
        volatile String loginReason;
        volatile String transportErrorCategory;
        volatile boolean transportAlive = true;
        /** v1.5.41 timeline, copied from the activity at fetch time. */
        volatile long tFirstContentMs, tDataMs, tCreateStartMs;

        void captureTimeline(long create, long first, long data) {
            tCreateStartMs = create; tFirstContentMs = first; tDataMs = data;
        }

        private String timelineLabel() {
            if (tFirstContentMs > 0) return (tFirstContentMs - tCreateStartMs) + " ms"
                    + ", onCreate→data "
                    + (tDataMs > 0 ? (tDataMs - tCreateStartMs) + " ms" : "n/a");
            return "n/a (cache)";
        }

        String render() {
            return "Home request diagnostics (WebView transport)\n"
                    + "• transport alive at request time: " + (transportAlive ? "Yes" : "No") + "\n"
                    + "• request made: " + (requestMade ? "Yes" : "No") + "\n"
                    + "• /mobile/home HTTP status: "
                    + (httpStatus == -2 ? "not run" : httpStatus == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD
                            ? "transport dead" : httpStatus == QolabWebViewTransport.Result.STATUS_IO
                            ? "IO failure" : String.valueOf(httpStatus)) + "\n"
                    + "• response time: " + durationMs + " ms\n"
                    + "• v1.5.41 timeline: onCreate→content "
                    + timelineLabel()
                    + "\n"
                    + "• body received: " + (payloadReceived ? "Yes" : "No")
                    + (bodyLength >= 0 ? " (length " + bodyLength + ")" : "") + "\n"
                    + "• parse attempted: " + (parseAttempted ? "Yes" : "No") + "\n"
                    + "• parse succeeded: " + (parseOk ? "Yes" : "No") + "\n"
                    + "• parsed user present: " + (userPresent ? "Yes" : "No") + "\n"
                    + "• parsed social rooms: " + (socialRoomCount < 0 ? "n/a" : String.valueOf(socialRoomCount)) + "\n"
                    + "• parsed department present: " + (departmentPresent ? "Yes" : "No") + "\n"
                    + "• parsed next meeting present: " + (nextMeetingPresent ? "Yes" : "No") + "\n"
                    + "• render callback executed: " + (renderExecuted ? "Yes" : "No") + "\n"
                    + "• skeletons cleared: " + (skeletonsCleared ? "Yes" : "No") + "\n"
                    + "• transport error category: " + (transportErrorCategory == null ? "none" : transportErrorCategory) + "\n"
                    + "• Login transition requested: " + (loginTransition ? "Yes" : "No")
                    + (loginReason == null ? "" : "\n• reason: " + loginReason);
        }
    }

    static final HomeDiag lastDiag = new HomeDiag();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        tCreateStartMs = android.os.SystemClock.elapsedRealtime();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // v1.5.15: Home parsing stays in HomeDataClient (pure JSON layer);
        // the authenticated transport fetches the payload.
        bindViews();
        applyInsets();
        bindActions();

        renderGreeting();

        // v1.5.25: Development-only in-app updater (guard-checked inside;
        // no-op in release builds). Async — never blocks Home startup.
        devUpdater = new dev.drytis.qolab.devupdate.DevUpdateController(this);
        devUpdater.maybeCheckOnStartup();

        // v1.5.41: automatic network recovery — when connectivity returns
        // while Home is showing an error state (or never loaded), reload
        // without waiting for a manual Retry tap. Bounded: fires only on
        // the system "network available" edge; the single-flight fetching
        // flag prevents duplicate requests.
        netWatcher = ConnectivityWatcher.register(this, () -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (data == null && !fetching) {
                loadHome();
            }
        }));

        if (data == null) {
            // v1.5.41 PERFORMANCE: if THIS user already has session-scoped
            // cached Home data (warm activity restart / navigation back),
            // render it instantly and refresh in the background — no
            // skeleton phase at all. The fresh fetch still runs (live data
            // guaranteed); cache is never shown across users.
            if (cachedData != null && cachedForUserIdOf(cachedData) == cachedUserId()) {
                this.data = cachedData;
                renderAll(-1);
                tFirstContentMs = android.os.SystemClock.elapsedRealtime();
            }
            loadHome();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh greeting word when the user returns hours later.
        if (data != null || !fetching) {
            renderGreeting();
        }
        if (data == null && !fetching) {
            loadHome();
        } else if (data != null && !fetching && System.nanoTime() - lastFetchNs > STALE_MS) {
            // v1.5.41: returning after a long pause (hours) — refresh in
            // the background without a skeleton; the cached render stays up.
            loadHome();
        }
        if (devUpdater != null) devUpdater.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (devUpdater != null) devUpdater.onDestroy();
        // v1.5.41: stop watching connectivity.
        ConnectivityWatcher.unregister(this, netWatcher);
        netWatcher = null;
    }

    // ------------------------------------------------------------------
    // View binding
    // ------------------------------------------------------------------

    private void bindViews() {
        greetingDate = findViewById(R.id.home_greeting_date);
        greetingName = findViewById(R.id.home_greeting_name);
        notificationBadge = findViewById(R.id.home_notification_badge);

        meetingCard = findViewById(R.id.home_meeting_card);
        meetingSkeleton = findViewById(R.id.home_meeting_skeleton);
        meetingEmpty = findViewById(R.id.home_meeting_empty);
        meetingError = findViewById(R.id.home_meeting_error);
        meetingTime = findViewById(R.id.home_meeting_time);
        meetingTitle = findViewById(R.id.home_meeting_title);
        meetingOverflow = findViewById(R.id.home_meeting_overflow);
        meetingAvatars = findViewById(R.id.home_meeting_avatars);
        TextView meetingRetry = findViewById(R.id.home_meeting_retry);
        if (meetingRetry != null) meetingRetry.setOnClickListener(v -> loadHome());

        roomsRow = findViewById(R.id.home_rooms_row);
        roomsSkeletonRow = findViewById(R.id.home_rooms_skeleton_row);
        roomsEmpty = findViewById(R.id.home_rooms_empty);
        roomsSeeAll = findViewById(R.id.home_rooms_see_all);

        deptCard = findViewById(R.id.home_dept_card);
        deptSkeleton = findViewById(R.id.home_dept_skeleton);
        deptEmpty = findViewById(R.id.home_dept_empty);
        deptError = findViewById(R.id.home_dept_error);
        deptName = findViewById(R.id.home_dept_name);
        deptOnline = findViewById(R.id.home_dept_online);
        deptDescription = findViewById(R.id.home_dept_description);
        deptOverflow = findViewById(R.id.home_dept_overflow);
        deptEnter = findViewById(R.id.home_dept_enter);
        deptAvatars = findViewById(R.id.home_dept_avatars);
        TextView deptRetry = findViewById(R.id.home_dept_retry);
        if (deptRetry != null) deptRetry.setOnClickListener(v -> loadHome());

        convCard = findViewById(R.id.home_continue_conversation);
        convSkeleton = findViewById(R.id.home_continue_conversation_skeleton);
        taskCard = findViewById(R.id.home_continue_task);
        taskSkeleton = findViewById(R.id.home_continue_task_skeleton);
        convEmpty = findViewById(R.id.home_continue_conversation_empty);
        taskEmpty = findViewById(R.id.home_continue_task_empty);
        convName = findViewById(R.id.home_continue_conversation_name);
        convMeta = findViewById(R.id.home_continue_conversation_meta);
        taskTitle = findViewById(R.id.home_continue_task_title);
        taskDue = findViewById(R.id.home_continue_task_due);

        todayTasks = findViewById(R.id.home_today_tasks);
        todayMeetings = findViewById(R.id.home_today_meetings);
        todayUpdates = findViewById(R.id.home_today_updates);

        navHome = findViewById(R.id.home_nav_home);
        navChat = findViewById(R.id.home_nav_chat);
        navAskDrytis = findViewById(R.id.home_nav_ask_drytis);
    }

    // ------------------------------------------------------------------
    // Insets / safe areas
    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private void applyInsets() {
        View root = findViewById(R.id.home_scroll);
        View nav = findViewById(R.id.home_bottom_nav);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            int navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            int status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            nav.setPadding(nav.getPaddingStart(), 0, nav.getPaddingEnd(), navBar);
            ScrollView scroll = (ScrollView) root;
            scroll.setPadding(0, status, 0, 0);
            int extra = (nav.getContext().getResources().getDimensionPixelSize(R.dimen.qm_home_bottom_nav_height)) + navBar;
            LinearLayout content = scroll.getChildAt(0) instanceof LinearLayout
                    ? (LinearLayout) scroll.getChildAt(0) : null;
            if (content != null) {
                content.setPadding(content.getPaddingStart(), content.getPaddingTop(),
                        content.getPaddingEnd(), extra);
            }
            return WindowInsetsCompat.CONSUMED;
        });
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void bindActions() {
        ImageButton menu = findViewById(R.id.home_btn_menu);
        menu.setOnClickListener(v -> showMenuSheet());

        ImageButton notifications = findViewById(R.id.home_btn_notifications);
        notifications.setOnClickListener(v -> showComingSoon(getString(R.string.home_menu_notifications)));

        ImageButton search = findViewById(R.id.home_btn_search);
        search.setOnClickListener(v ->
                showComingSoon(getString(R.string.home_menu_search)));

        roomsSeeAll.setOnClickListener(v ->
                showComingSoon(getString(R.string.home_menu_rooms)));

        deptEnter.setOnClickListener(v -> {
            // v1.5.34: Enter now opens the shared native RoomActivity with
            // the real department room identity (was Coming Soon).
            if (data != null && data.departmentRoom != null) {
                openDepartmentRoom(data.departmentRoom);
            }
        });

        navHome.setOnClickListener(v -> smoothScrollToTop());
        // v1.5.39: Office removed from primary navigation (code retained,
        // reachable nowhere in the primary flow). Chat is now native.
        navChat.setOnClickListener(v -> openChat());
        navAskDrytis.setOnClickListener(v ->
                showComingSoon(getString(R.string.home_nav_ask_drytis)));
    }

    /**
     * v1.5.16: native destinations only — while a destination's native
     * screen is not implemented, we NEVER fall back to the web app.
     * A small native "Coming soon" response keeps the user in Home.
     */
    private void showComingSoon(String what) {
        Toast.makeText(this, getString(R.string.home_coming_soon_msg, what),
                Toast.LENGTH_SHORT).show();
    }

    private void smoothScrollToTop() {
        ScrollView scroll = findViewById(R.id.home_scroll);
        scroll.smoothScrollTo(0, 0);
    }

    // ------------------------------------------------------------------
    // Menu bottom sheet (native secondary navigation)
    // ------------------------------------------------------------------

    private void showMenuSheet() {
        final Dialog sheet = new Dialog(this);
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.home_menu_sheet_title);
        title.setTextColor(0xFF1A1A1A);
        title.setTextSize(16);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, pad);
        box.addView(title);

        String[] destinations = {
                getString(R.string.home_menu_meetings),
                getString(R.string.home_menu_tasks),
                getString(R.string.home_menu_tickets),
                getString(R.string.home_menu_directory),
                getString(R.string.home_menu_settings),
                getString(R.string.home_menu_profile),
        };
        for (String dest : destinations) {
            TextView item = new TextView(this);
            item.setText(dest);
            item.setTextColor(0xFF1A1A1A);
            item.setTextSize(16);
            int itemPad = pad / 2;
            item.setPadding(0, itemPad, 0, itemPad);
            item.setOnClickListener(v -> {
                sheet.dismiss();
                // v1.5.16: native Coming Soon — never route to web pages
                showComingSoon(dest);
            });
            box.addView(item);
        }

        // DEBUG-only: Home fetch diagnostics (names/booleans only)
        if (BuildConfig.DEBUG) {
            // v1.5.25: manual Development update check (dev builds only)
            TextView upd = new TextView(this);
            upd.setText(R.string.dev_update_menu_check);
            upd.setTextColor(0xFF7A756D);
            upd.setTextSize(13);
            upd.setPadding(0, pad / 2, 0, pad / 2);
            box.addView(upd);
            upd.setOnClickListener(v -> {
                sheet.dismiss();
                if (devUpdater != null) devUpdater.checkNow();
            });

            TextView diag = new TextView(this);
            diag.setText(R.string.home_diag_title);
            diag.setTextColor(0xFF7A756D);
            diag.setTextSize(13);
            diag.setPadding(0, pad, 0, pad / 2);
            box.addView(diag);
            diag.setOnClickListener(v -> {
                sheet.dismiss();
                lastDiag.captureTimeline(tCreateStartMs, tFirstContentMs, tDataMs);
                android.app.AlertDialog d = new android.app.AlertDialog.Builder(HomeActivity.this)
                        .setTitle(R.string.home_diag_title)
                        .setMessage(lastDiag.render())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }

        sheet.setContentView(box);
        sheet.show();
        Window w = sheet.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setBackgroundDrawableResource(R.drawable.qm_bg_card_white);
        }
    }

    // ------------------------------------------------------------------
    // Data loading
    // ------------------------------------------------------------------

    private void loadHome() {
        if (fetching) return;
        fetching = true;
        // v1.5.41 PERFORMANCE: only run the skeleton phase when there is
        // nothing to show. With cached/stale data up, a background refresh
        // keeps the current content (no skeleton flash, no empty states).
        if (data == null) showLoading();
        lastDiag.loginTransition = false;
        lastDiag.loginReason = null;
        // v1.5.15 (Architecture B): Home data comes from the AUTHENTICATED
        // WEBVIEW TRANSPORT — the same browser context that physically
        // returned 200 for /mobile/home after login. Java HTTP no longer
        // carries the session; no cookies are read or reconstructed here.
        QolabWebViewTransport transport = QolabWebViewTransport.get();
        lastDiag.transportAlive = transport != null && transport.isAlive();
        if (transport == null || !transport.isAlive()) {
            // v1.5.40: cold start after process death — the persisted
            // session cookie may still be valid. The SessionGate rechecks
            // with the SERVER (bounded retries, transient network errors
            // are NEVER treated as session expiry). Only a proven /login
            // landing routes to native Login; a dead network shows the
            // retryable error state instead of destroying the session.
            lastDiag.loginReason = "transport gone → verifying session (SessionGate)";
            lastDiag.loginTransition = false;
            fetching = false;
            String origin = BuildConfig.QOLAB_ORIGIN.replaceAll("/+$", "");
            SessionGate.requireValidSession(getApplicationContext(), origin,
                    () -> runOnUiThread(() -> {
                        fetching = false;
                        loadHome(); // transport adopted — retry deterministically
                    }),
                    () -> runOnUiThread(() -> {
                        lastDiag.loginReason = "session expired (server-proven) → Login";
                        lastDiag.loginTransition = true;
                        routeToLogin();
                    }),
                    () -> runOnUiThread(() -> {
                        // Network unavailable for the whole recheck window:
                        // keep the cookie, show the retryable error state.
                        lastDiag.loginReason = "network unavailable → error state";
                        showError();
                    }));
            return;
        }
        long t0 = android.os.SystemClock.elapsedRealtime();
        // v1.5.43: /mobile/home was REMOVED from the backend (404 even
        // with a valid session — the cause of the permanent "Couldn't
        // load" on Next Meeting / Social Rooms / Your Department). Home
        // data is now composed from the existing /dashboard + /rooms
        // Inertia pages + /notifications/unread-count. Same transport,
        // same session, same auth/transport-dead/401 handling.
        HomeCompositeFetch.fetch(transport, (status, data, errCat) ->
                runOnUiThread(() -> {
            lastDiag.durationMs = android.os.SystemClock.elapsedRealtime() - t0;
            lastDiag.httpStatus = status;
            lastDiag.transportErrorCategory = errCat;
            lastDiag.requestMade = true;
            lastDiag.payloadReceived = data != null;
            lastDiag.bodyLength = -1;
            lastDiag.parseAttempted = true;
            lastDiag.parseOk = data != null;
            fetching = false;
            if (status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD) {
                // v1.5.40: a dead transport is NOT proof of session expiry
                // (renderer kill, memory pressure). Verify with the server
                // via the SessionGate before ever showing Login.
                lastDiag.loginReason = "transport dead → verifying session (SessionGate)";
                SessionGate.requireValidSession(getApplicationContext(),
                        BuildConfig.QOLAB_ORIGIN.replaceAll("/+$", ""),
                        () -> runOnUiThread(() -> loadHome()),
                        () -> runOnUiThread(() -> {
                            lastDiag.loginReason =
                                    "session expired (server-proven) → Login";
                            lastDiag.loginTransition = true;
                            routeToLogin();
                        }),
                        () -> runOnUiThread(() -> showError()));
                return;
            }
            if (status == 401) {
                // Genuine session loss INSIDE the browser context itself:
                // the server answered 401 to an authenticated request with
                // Accept: application/json (verified live — unauthenticated
                // requests to protected routes return 401, not a redirect,
                // when the transport's Accept header is set). This is the
                // server PROVING expiry; routing to Login is honest.
                lastDiag.loginReason = "401 from WebView transport → Login";
                lastDiag.loginTransition = true;
                routeToLogin();
                return;
            }
            if (status != 200 || data == null) {
                lastDiag.transportErrorCategory = errCat != null
                        ? errCat : ("http-" + status);
                showError(); // retry — never a logout
                return;
            }
            // v1.5.17 FIX: render Home data IMMEDIATELY — the unread badge
            // is a separate, non-blocking update. Waiting for the second
            // request before renderAll was a second way Home could stay
            // skeleton forever.
            this.data = data;
            // v1.5.41: session-scoped cache (per-USER; in-memory only).
            if (data.userId != 0) {
                cachedData = data;
                cachedForUserId = data.userId;
            }
            lastFetchNs = System.nanoTime();
            if (tDataMs == 0) tDataMs = android.os.SystemClock.elapsedRealtime();
            if (tFirstContentMs == 0) tFirstContentMs = tDataMs;
            lastDiag.userPresent = data.userName != null;
            lastDiag.socialRoomCount = data.socialRooms.size();
            lastDiag.departmentPresent = data.departmentRoom != null;
            lastDiag.nextMeetingPresent = data.nextMeeting != null;
            lastDiag.renderExecuted = true;
            lastDiag.skeletonsCleared = true;
            renderAll(data.today.unreadNotifications);
        }));
    }


    private void routeToLogin() {
        // v1.5.41: leaving the session — drop the per-user Home cache so a
        // different Member can NEVER see this user's cached data.
        cachedData = null;
        cachedForUserId = Long.MIN_VALUE;
        ChatRealtime.get().shutdown();
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void showLoading() {
        show(meetingSkeleton); hide(meetingCard, meetingEmpty, meetingError);
        show(deptSkeleton); hide(deptCard, deptEmpty, deptError);
        show(convSkeleton, taskSkeleton);
        hide(convCard, convEmpty, taskCard, taskEmpty);
        // rooms row keeps its skeleton children until data arrives
    }

    private void showError() {
        lastDiag.skeletonsCleared = true;
        hide(meetingSkeleton); show(meetingError);
        hide(deptSkeleton); show(deptError);
        hide(convSkeleton, taskSkeleton, convCard, convEmpty, taskCard, taskEmpty);
        // rooms: hide grid skeletons, show the empty/error placeholder
        hide(roomsSkeletonRow);
        roomsRow.removeAllViews();
        show(roomsEmpty);
        ((TextView) roomsEmpty).setText(R.string.home_error_section);
    }

    private void renderAll(int unread) {
        renderGreeting();
        renderMeeting();
        renderRooms();
        renderDepartment();
        renderContinue();
        renderToday();
        notificationBadge.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
    }

    private void renderGreeting() {
        Calendar now = Calendar.getInstance();
        String dateLine = GreetingLogic.dateLine(now.get(Calendar.YEAR),
                now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
        String greeting = GreetingLogic.greetingWord(now.get(Calendar.HOUR_OF_DAY));
        String name = data != null ? GreetingLogic.firstName(data.userName) : null;
        greetingDate.setText(dateLine);
        greetingName.setText(name != null
                ? getString(R.string.home_greeting_line, greeting, name)
                : getString(R.string.home_greeting_line_no_name, greeting));
    }

    private void renderMeeting() {
        hide(meetingSkeleton);
        HomeDataClient.Meeting m = data.nextMeeting;
        if (m == null) {
            show(meetingEmpty);
            hide(meetingCard);
            return;
        }
        show(meetingCard);
        meetingTime.setText(m.timeDisplay != null ? m.timeDisplay : "");
        meetingTitle.setText(m.title != null ? m.title : "");
        meetingAvatars.removeAllViews();
        renderAvatarRow(meetingAvatars, m.participants, m.participantsCount, true);
        meetingOverflow.setText(overflowText(m.participants.size(), m.participantsCount));
    }

    private void renderRooms() {
        List<HomeDataClient.Room> rooms = data.socialRooms;
        // The skeleton row is a SIBLING of the data row — always retire it
        // once data (or an empty/error state) resolves. This was the cause
        // of the 3 permanent blank beige cards under Social Rooms.
        hide(roomsSkeletonRow);
        roomsRow.removeAllViews();
        if (rooms == null || rooms.isEmpty()) {
            show(roomsEmpty);
            ((TextView) roomsEmpty).setText(R.string.home_rooms_empty);
            return;
        }
        hide(roomsEmpty);
        ((TextView) roomsEmpty).setText(R.string.home_rooms_empty);
        // Preferred visual order: Lobby → Music Lounge → Coffee Lounge
        // (stable sort keeps payload order within equal ranks / unknowns).
        java.util.Collections.sort(rooms, (a, b) ->
                Integer.compare(RoomThemeStyle.preferredOrder(a.roomTheme, a.roomSlug),
                        RoomThemeStyle.preferredOrder(b.roomTheme, b.roomSlug)));
        int count = rooms.size();
        int gap = getResources().getDimensionPixelSize(R.dimen.qm_home_room_gap);
        for (int i = 0; i < count; i++) {
            View card = inflateRoomCard(rooms.get(i));
            // Responsive grid: each card takes an equal weight share of the
            // row width (match_parent row), so all cards are fully visible
            // with no horizontal scrolling on any phone width. Height stays
            // the fixed card dimen so all cards are equal.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0,
                    getResources().getDimensionPixelSize(R.dimen.qm_home_room_card_height),
                    1f);
            if (i < count - 1) lp.setMarginEnd(gap);
            card.setLayoutParams(lp);
            roomsRow.addView(card);
        }
    }

    private View inflateRoomCard(HomeDataClient.Room room) {
        View card = getLayoutInflater().inflate(R.layout.view_room_card, roomsRow, false);
        TextView online = card.findViewById(R.id.room_card_online);
        TextView name = card.findViewById(R.id.room_card_name);
        TextView description = card.findViewById(R.id.room_card_description);
        TextView overflow = card.findViewById(R.id.room_card_overflow);
        LinearLayout avatars = card.findViewById(R.id.room_card_avatars);
        FrameLayout arrowCircle = card.findViewById(R.id.room_card_arrow_circle);
        ImageView arrow = card.findViewById(R.id.room_card_arrow);

        // Approved popping palette by room theme — colors ARE the identity
        // (pure mapping lives in RoomThemeStyle; unit-tested).
        RoomThemeStyle.Style style = RoomThemeStyle.styleFor(room.roomTheme, room.roomSlug);
        // Rounded card background in the theme color (radius from dimens).
        android.graphics.drawable.GradientDrawable cardBg =
                new android.graphics.drawable.GradientDrawable();
        cardBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        cardBg.setColor(style.bg);
        cardBg.setCornerRadius(getResources().getDimensionPixelSize(R.dimen.qm_home_card_radius));
        card.setBackground(cardBg);
        name.setTextColor(style.title);
        online.setTextColor(style.text);
        description.setTextColor(style.text);
        overflow.setTextColor(style.text);
        if (arrowCircle != null) {
            // Circular arrow action (approved reference) — tinted to the
            // theme's enter color; icon stays readable against it.
            android.graphics.drawable.GradientDrawable circle =
                    new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(style.enterBg);
            arrowCircle.setBackground(circle);
            // v1.5.39: Social Rooms stay accessible from Home — the lobby
            // theme opens the native LobbyActivity exactly like Office did
            // (same identity contract: channel id + slug + name).
            arrowCircle.setOnClickListener(v -> openSocialRoom(room));
            arrowCircle.setContentDescription(getString(R.string.home_room_cd_enter));
        }
        if (arrow != null) {
            arrow.setImageTintList(ColorStateList.valueOf(style.enterText));
        }

        online.setText(getString(R.string.home_room_online, room.membersCount));
        name.setText(room.name);
        description.setText(room.description != null ? room.description
                : defaultRoomDescription(style.descriptionKey));
        avatars.removeAllViews();
        renderAvatarRow(avatars, room.participants, room.membersCount, false);
        overflow.setText(overflowText(room.participants.size(), room.membersCount));
        return card;
    }

    /** Resolve the per-theme default description key to its string resource. */
    private String defaultRoomDescription(String key) {
        int resId = getResources().getIdentifier(key, "string", getPackageName());
        return resId != 0 ? getString(resId) : getString(R.string.home_room_default_description);
    }

    private void renderDepartment() {
        hide(deptSkeleton);
        HomeDataClient.Room dept = data.departmentRoom;
        if (dept == null) {
            show(deptEmpty);
            hide(deptCard);
            return;
        }
        show(deptCard);
        deptName.setText(dept.name);
        deptOnline.setText(getString(R.string.home_room_online, dept.membersCount));
        deptDescription.setText(dept.description != null ? dept.description
                : getString(R.string.home_dept_no_description));
        deptAvatars.removeAllViews();
        renderAvatarRow(deptAvatars, dept.participants, dept.membersCount, false);
        deptOverflow.setText(overflowText(dept.participants.size(), dept.membersCount));
        // v1.5.34: open the SAME native RoomActivity Office uses, with the
        // real department room identity from /mobile/home. No duplicate
        // screen, no layout/data change.
        deptCard.setOnClickListener(v -> openDepartmentRoom(dept));
        deptEnter.setOnClickListener(v -> openDepartmentRoom(dept));
    }

    /**
     * v1.5.34: mirrors OfficeActivity.openDepartmentRoom — the card now
     * opens the shared native room screen using the room_slug from the
     * /mobile/home payload. Missing slug → Coming Soon (same rule as
     * Office).
     */
    private void openChat() {
        Intent i = new Intent(this, ChatListActivity.class);
        i.putExtra(ChatListActivity.EXTRA_MY_USER_ID,
                data != null ? data.userId : 0L);
        startActivity(i);
    }

    /**
     * v1.5.39: mirrors OfficeActivity.openRoom — the Lobby theme opens the
     * shared native Lobby screen (Discussion | Members). Other themes keep
     * Coming Soon, exactly as Office behaved; nothing was deleted.
     */
    private void openSocialRoom(HomeDataClient.Room room) {
        // v1.5.43: ALL three approved social rooms open natively. The
        // Lobby screen is a generic room screen (Discussion | Members)
        // keyed by channel id + slug — identity comes from the payload,
        // never hardcoded. Backend room_capabilities confirm discussion +
        // members for The Lobby, Music Lounge AND Coffee Lounge.
        boolean openable = room.id > 0 && room.roomSlug != null && !room.roomSlug.isEmpty();
        String theme = room.roomTheme == null ? "" : room.roomTheme;
        if (openable && ("lobby".equals(theme) || "music".equals(theme)
                || "coffee".equals(theme))) {
            Intent i = new Intent(this, LobbyActivity.class);
            i.putExtra(LobbyActivity.EXTRA_ROOM_ID, room.id);
            i.putExtra(LobbyActivity.EXTRA_ROOM_SLUG, room.roomSlug);
            i.putExtra(LobbyActivity.EXTRA_ROOM_NAME,
                    room.name != null ? room.name : room.roomSlug);
            startActivity(i);
        } else {
            showComingSoon(room.name != null ? room.name
                    : getString(R.string.home_room_cd_enter));
        }
    }

    private void openDepartmentRoom(HomeDataClient.Room dept) {
        String slug = dept.roomSlug;
        if (slug == null || slug.isEmpty()) {
            showComingSoon(dept.name != null ? dept.name
                    : getString(R.string.home_dept_empty_title));
            return;
        }
        Intent i = new Intent(this, RoomActivity.class);
        i.putExtra(RoomActivity.EXTRA_ROOM_NAME, dept.name);
        i.putExtra(RoomActivity.EXTRA_ROOM_SLUG, slug);
        startActivity(i);
    }

    private void renderContinue() {
        hide(convSkeleton, taskSkeleton);
        HomeDataClient.Conversation conv = data.recentConversation;
        HomeDataClient.Task task = data.priorityTask;

        // Each slot always resolves to exactly one of data / empty —
        // never removed, never a permanent skeleton.
        if (conv != null) {
            show(convCard);
            hide(convEmpty);
            convName.setText(conv.type != null && conv.type.equals("dm") && conv.dmPartnerName != null
                    ? conv.dmPartnerName : conv.name);
            String meta = relativeTime(conv.lastMessageAt);
            if (conv.unreadCount > 0) {
                meta = getString(R.string.home_continue_unread, conv.unreadCount) + " · " + meta;
            }
            convMeta.setText(meta);
            // v1.5.39: opens the actual conversation in native Chat.
            convCard.setOnClickListener(v -> {
                Intent i = new Intent(this, ChatScreenActivity.class);
                i.putExtra(ChatScreenActivity.EXTRA_CHANNEL_ID, conv.id);
                i.putExtra(ChatScreenActivity.EXTRA_CHANNEL_NAME,
                        conv.type != null && conv.type.equals("dm") && conv.dmPartnerName != null
                                ? conv.dmPartnerName : conv.name);
                i.putExtra(ChatScreenActivity.EXTRA_CHANNEL_TYPE, conv.type);
                i.putExtra(ChatScreenActivity.EXTRA_MY_USER_ID,
                        data != null ? data.userId : 0L);
                startActivity(i);
            });
        } else {
            hide(convCard);
            show(convEmpty);
        }

        if (task != null) {
            show(taskCard);
            hide(taskEmpty);
            taskTitle.setText(task.title);
            taskDue.setText(dueLabel(task.dueDate));
            taskCard.setOnClickListener(v ->
                    showComingSoon(getString(R.string.home_menu_tasks)));
        } else {
            hide(taskCard);
            show(taskEmpty);
        }
    }

    private void renderToday() {
        HomeDataClient.TodayCounts t = data.today;
        todayTasks.setText(String.valueOf(t.tasksDueToday + t.tasksOverdue));
        todayMeetings.setText(String.valueOf(t.meetingsToday));
        todayUpdates.setText(String.valueOf(t.unreadNotifications));
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private void renderAvatarRow(LinearLayout container, List<HomeDataClient.Participant> participants,
                                 int totalCount, boolean onYellow) {
        int shown = Math.min(AVATAR_MAX, participants == null ? 0 : participants.size());
        for (int i = 0; i < shown; i++) {
            HomeDataClient.Participant p = participants.get(i);
            TextView avatar = (TextView) getLayoutInflater().inflate(R.layout.view_avatar, container, false);
            avatar.setText(initials(p.name));
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(avatarColor(p.name));
            circle.setStroke(Math.round(2 * getResources().getDisplayMetrics().density),
                    onYellow ? 0xFFFFC940 : 0xFFFFFFFF);
            avatar.setBackground(circle);
            avatar.setTextColor(onYellow ? 0xFF1A1A1A : 0xFFFFFFFF);
            if (i > 0) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) avatar.getLayoutParams();
                lp.setMargins(-Math.round(10 * getResources().getDisplayMetrics().density), 0, 0, 0);
                avatar.setLayoutParams(lp);
        }
            container.addView(avatar);
        }
    }

    private static String initials(String name) {
        if (name == null || name.trim().isEmpty()) return "?";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(parts[0].charAt(0)));
        if (parts.length > 1) sb.append(Character.toUpperCase(parts[parts.length - 1].charAt(0)));
        return sb.toString();
    }

    /** Public alias for other native screens (Office V1). */
    public static String initialsOf(String name) {
        return initials(name);
    }

    private static int avatarColor(String name) {
        int[] palette = {0xFF2148C0, 0xFF8B1E2D, 0xFF14532D, 0xFF6B21A8, 0xFFB45309, 0xFF0E7490};
        if (name == null || name.isEmpty()) return palette[0];
        return palette[Math.abs(name.hashCode()) % palette.length];
    }

    /** Public alias for other native screens (Office V1). */
    public static int avatarColorOf(String name) {
        return avatarColor(name);
    }

    private String overflowText(int shown, int total) {
        int extra = total - shown;
        if (extra <= 0) return "";
        return getString(R.string.home_overflow_more, extra);
    }

    private String dueLabel(String dueDate) {
        if (dueDate == null) return getString(R.string.home_task_no_due_date);
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date due = fmt.parse(dueDate);
            Calendar today = Calendar.getInstance();
            Calendar dueCal = Calendar.getInstance();
            dueCal.setTime(due);
            long dayMs = 86_400_000L;
            long todayStart = startOfDay(today.getTimeInMillis());
            long dueStart = startOfDay(dueCal.getTimeInMillis());
            if (dueStart < todayStart) return getString(R.string.home_task_overdue);
            if (dueStart == todayStart) return getString(R.string.home_task_due_today);
            return getString(R.string.home_task_due_date,
                    new SimpleDateFormat("MMM d", Locale.getDefault()).format(due));
        } catch (ParseException e) {
            return dueDate;
        }
    }

    private long startOfDay(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private String relativeTime(String iso) {
        if (iso == null) return "";
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            String cut = iso.length() > 19 ? iso.substring(0, 19) : iso;
            Date d = fmt.parse(cut);
            if (d == null) return "";
            long diff = System.currentTimeMillis() - d.getTime();
            long min = diff / 60000L;
            if (min < 1) return getString(R.string.home_time_just_now);
            if (min < 60) return getString(R.string.home_time_minutes_ago, (int) min);
            long hours = min / 60;
            if (hours < 24) return getString(R.string.home_time_hours_ago, (int) hours);
            long days = hours / 24;
            return getString(R.string.home_time_days_ago, (int) days);
        } catch (ParseException e) {
            return "";
        }
    }

    private static void show(View... views) {
        for (View v : views) if (v != null) v.setVisibility(View.VISIBLE);
    }

    private static void hide(View... views) {
        for (View v : views) if (v != null) v.setVisibility(View.GONE);
    }
}
