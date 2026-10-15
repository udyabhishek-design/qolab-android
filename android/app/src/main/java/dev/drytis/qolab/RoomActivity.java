package dev.drytis.qolab;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

import dev.drytis.qolab.RoomDataClient.Meeting;
import dev.drytis.qolab.RoomDataClient.RoomPage;

/**
 * Native Engineering Room (department room) screen.
 *
 * Opened from OfficeActivity via the department card / Enter button with
 * extras { roomName, roomSlug }. Fetches:
 *   GET /office/rooms/{slug}   — Inertia HTML; props extracted from the
 *                                data-page script JSON (channel + meetings).
 * All data is real backend data — no fabricated values. Unavailable data
 * renders honest empty states.
 *
 * Tabs: Overview | Meetings | Tasks (Overview default). Meetings rows open
 * MeetingDetailActivity (native). No web UI is ever opened.
 */
public class RoomActivity extends AppCompatActivity {

    private static final String TAG = "RoomActivity";
    public static final String EXTRA_ROOM_NAME = "room_name";
    public static final String EXTRA_ROOM_SLUG = "room_slug";

    private TextView titleView, avatar, notificationBadge;
    private TextView tabOverview, tabMeetings, tabTasks;
    private View tabOverviewRoot, tabMeetingsRoot, tabTasksRoot;
    private View errorText, retryText, contentScroll;
    private View meetingsSkeleton, tasksSkeleton;

    // Overview
    private TextView membersCount, membersMore;
    private LinearLayout membersRow, membersList, upcomingCard, recentList;
    private View upcomingEmpty, recentEmpty, tasksSummary;

    // Meetings
    private View liveLabel, liveList, upcomingEmptyM, completedEmptyM;
    private LinearLayout upcomingListM, completedListM;

    // Tasks
    private LinearLayout tasksList;
    private View tasksEmpty;

    private String roomSlug;
    private String roomName;
    private RoomPage page;
    private volatile boolean fetching;
    /** v1.5.46: automatic network recovery handle (ConnectivityWatcher). */
    private Object netWatcher;
    /** v1.5.46: generation of the in-flight load; bumped per loadRoom call
     *  so a late callback from a DESTROYED previous instance or a stale
     *  request can never mutate state. */
    private final java.util.concurrent.atomic.AtomicInteger loadGeneration =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** v1.5.40: transport origin for the SessionGate recheck. */
    private String appOrigin() {
        return BuildConfig.QOLAB_ORIGIN != null ? BuildConfig.QOLAB_ORIGIN
                : "https://workpulse-gbpedt.drytis.dev";
    }
    /** v1.5.38: guards the create→join chain against double taps. */
    private volatile boolean entering;
    private boolean overviewRendered, meetingsRendered, tasksRendered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room);

        roomSlug = getIntent().getStringExtra(EXTRA_ROOM_SLUG);
        roomName = getIntent().getStringExtra(EXTRA_ROOM_NAME);

        bindViews();
        bindActions();

        titleView.setText(roomName != null ? roomName : getString(R.string.room_title_template, ""));

        // avatar initial from room name owner (visual identity only)
        String userName = roomName;
        avatar.setText(HomeActivity.initialsOf(userName));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(HomeActivity.avatarColorOf(userName));
        avatar.setBackground(circle);

        showTab(0);

        // v1.5.46: automatic network recovery — airplane-mode cycles. When
        // connectivity returns while the room shows its error state (page
        // never loaded), reload without waiting for a manual Retry. Bounded:
        // fires only on the system "network available" edge; the fetching
        // single-flight guard prevents duplicate requests, and loadRoom()
        // itself no-ops when there is already valid rendered content (same
        // rule as the Home watcher). If the retried load fails again (the
        // edge fired before routes/DNS were usable), the retryable error
        // state stays — manual Retry remains available; no polling loop.
        netWatcher = ConnectivityWatcher.register(this, () -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (page == null && !fetching) {
                loadRoom();
            }
        }));

        loadRoom();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // v1.5.46: stop watching connectivity — no recovery callbacks after
        // destruction, no leaked callback registration.
        ConnectivityWatcher.unregister(this, netWatcher);
        netWatcher = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v1.5.38: returning from LiveRoomActivity (Leave) — reset the join
        // guard and refresh so status changes (live→ended, new occurrences)
        // are reflected immediately. Meetings tab stays where the user was.
        if (entering) entering = false;
        if (roomSlug != null && page != null) loadRoom();
        // v1.5.46: room still in its error state (e.g. airplane mode was on
        // when opened, since turned off) — one resume attempt covers the
        // case where the watcher edge fired while another screen was in
        // front and its 2s re-arm already elapsed.
        if (roomSlug != null && page == null && !fetching) loadRoom();
    }

    private void bindViews() {
        titleView = findViewById(R.id.room_title);
        avatar = findViewById(R.id.room_avatar);
        notificationBadge = findViewById(R.id.room_notification_badge);
        tabOverview = findViewById(R.id.room_tab_overview);
        tabMeetings = findViewById(R.id.room_tab_meetings);
        tabTasks = findViewById(R.id.room_tab_tasks);
        tabOverviewRoot = findViewById(R.id.room_tab_overview_root);
        tabMeetingsRoot = findViewById(R.id.room_tab_meetings_root);
        tabTasksRoot = findViewById(R.id.room_tab_tasks_root);
        errorText = findViewById(R.id.room_error);
        retryText = findViewById(R.id.room_retry);
        contentScroll = findViewById(R.id.room_content_scroll);
        meetingsSkeleton = findViewById(R.id.room_meetings_skeleton);
        tasksSkeleton = findViewById(R.id.room_tasks_skeleton);

        membersCount = findViewById(R.id.room_overview_members_count);
        membersRow = findViewById(R.id.room_overview_members_row);
        membersList = findViewById(R.id.room_overview_members_list);
        membersMore = (TextView) findViewById(R.id.room_overview_members_more);
        upcomingCard = findViewById(R.id.room_overview_upcoming_card);
        upcomingEmpty = findViewById(R.id.room_overview_upcoming_empty);
        recentList = findViewById(R.id.room_overview_recent_list);
        recentEmpty = findViewById(R.id.room_overview_recent_empty);
        tasksSummary = findViewById(R.id.room_overview_tasks_summary);

        liveLabel = findViewById(R.id.room_meetings_live_label);
        liveList = findViewById(R.id.room_meetings_live_list);
        upcomingListM = findViewById(R.id.room_meetings_upcoming_list);
        upcomingEmptyM = findViewById(R.id.room_meetings_upcoming_empty);
        completedListM = findViewById(R.id.room_meetings_completed_list);
        completedEmptyM = findViewById(R.id.room_meetings_completed_empty);

        tasksList = findViewById(R.id.room_tasks_list);
        tasksEmpty = findViewById(R.id.room_tasks_empty);
    }

    private void bindActions() {
        ImageButton back = findViewById(R.id.room_btn_back);
        back.setOnClickListener(v -> finish());
        ImageButton bell = findViewById(R.id.room_btn_notifications);
        bell.setOnClickListener(v -> showComingSoon());
        tabOverview.setOnClickListener(v -> showTab(0));
        tabMeetings.setOnClickListener(v -> showTab(1));
        tabTasks.setOnClickListener(v -> showTab(2));
        retryText.setOnClickListener(v -> loadRoom());
        // v1.5.31→v1.5.35: New Meeting moved from the tab pill row into the
        // Meetings tab header (compact "+ New Meeting") so it no longer
        // crowds the tab control. Listener binding is identical.
        TextView newMeeting = findViewById(R.id.room_new_meeting);
        if (newMeeting != null) {
            newMeeting.setOnClickListener(v -> openCreateMeeting());
        }
    }

    /**
     * v1.5.31: opens the native Create Meeting sheet against the existing
     * POST /office/rooms/{channelId}/meetings contract. Channel id comes
     * from the room page payload; the room association is server-side.
     */
    private void openCreateMeeting() {
        if (page == null || page.channelId <= 0) {
            showComingSoon(); // no usable room identity yet — honest no-op
            return;
        }
        new CreateMeetingSheet(this, page.channelId, page.roomName, page.members,
                this::onMeetingCreated).show();
    }

    /**
     * After a confirmed create.
     * Scheduled: refetch so Upcoming + Meetings reflect it (unchanged).
     * Instant (v1.5.38): chain DIRECTLY into the existing join flow for the
     * newly created meeting — start-or-join-session → join-token →
     * LiveRoomActivity — so Start-now enters the meeting without a second
     * Join tap. Reuses the same endpoint sequence as
     * MeetingDetailActivity.startOrJoin(); no LiveKit logic is duplicated —
     * LiveRoomActivity remains the single owner of the connection.
     */
    private void onMeetingCreated(long createdMeetingId, boolean instant) {
        if (instant && createdMeetingId > 0) {
            enterMeeting(createdMeetingId);
            return;
        }
        loadRoom();
    }

    /** v1.5.38: contextual row Join/Start action — identical chain. */
    private void enterMeetingFromRow(long meetingId) {
        enterMeeting(meetingId);
    }

    /** Shared join chain used by both Start-now-after-create and the row
     *  Join/Start action. Mirrors MeetingDetailActivity's verified flow. */
    private void enterMeeting(long meetingId) {
        if (entering) return; // double-tap / lifecycle guard
        entering = true;
        QolabWebViewTransport transport = QolabWebViewTransport.get();
        if (transport == null) {
            // v1.5.40: verify with the server before logging out.
            entering = false;
            SessionGate.requireValidSession(this, appOrigin(),
                    () -> runOnUiThread(() -> enterMeeting(meetingId)),
                    this::routeToLogin,
                    null);
            return;
        }
        transport.postJson("/meetings/" + meetingId + "/start-or-join-session",
                "{}", r -> runOnUiThread(() -> {
                    if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD) {
                        // v1.5.40: verify before logout (SessionGate).
                        entering = false;
                        SessionGate.requireValidSession(this, appOrigin(),
                                () -> runOnUiThread(() -> enterMeeting(meetingId)),
                                this::routeToLogin,
                                null);
                        return;
                    }
                    if (r.status == 401) {
                        entering = false;
                        routeToLogin(); // server-proven expiry
                        return;
                    }
                    long resolvedId = RoomDataClient.parseStartOrJoin(r.body);
                    if (r.status != 200 || resolvedId <= 0) {
                        entering = false;
                        loadRoom(); // reflect server truth
                        android.widget.Toast.makeText(this,
                                R.string.md_join_failed,
                                android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    requestJoinToken(resolvedId);
                }));
    }

    /** Second half of the chain: mint token → open LiveRoomActivity. */
    private void requestJoinToken(long resolvedMeetingId) {
        QolabWebViewTransport transport = QolabWebViewTransport.get();
        if (transport == null) {
            entering = false;
            SessionGate.requireValidSession(this, appOrigin(),
                    () -> runOnUiThread(() -> requestJoinToken(resolvedMeetingId)),
                    this::routeToLogin,
                    null);
            return;
        }
        transport.postJson("/meetings/" + resolvedMeetingId + "/join-token",
                "{}", r -> runOnUiThread(() -> {
                    entering = false;
                    if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD) {
                        SessionGate.requireValidSession(this, appOrigin(),
                                () -> runOnUiThread(() -> requestJoinToken(resolvedMeetingId)),
                                this::routeToLogin,
                                null);
                        return;
                    }
                    if (r.status == 401) {
                        routeToLogin(); // server-proven expiry
                        return;
                    }
                    RoomDataClient.JoinToken jt = RoomDataClient.parseJoinToken(r.body);
                    if (r.status != 200 || jt == null) {
                        android.widget.Toast.makeText(this,
                                R.string.md_join_failed,
                                android.widget.Toast.LENGTH_LONG).show();
                        loadRoom();
                        return;
                    }
                    Intent i = new Intent(this, LiveRoomActivity.class);
                    i.putExtra(LiveRoomActivity.EXTRA_TOKEN, jt.token);
                    i.putExtra(LiveRoomActivity.EXTRA_SERVER_URL, jt.url);
                    i.putExtra(LiveRoomActivity.EXTRA_MEETING_ID, resolvedMeetingId);
                    startActivity(i);
                    // list refreshes on return (onResume refetch via loadRoom)
                    loadRoom();
                }));
    }

    private void showComingSoon() {
        android.widget.Toast.makeText(this, R.string.home_coming_soon, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ── Tabs ──────────────────────────────────────────────────────────

    private void showTab(int index) {
        setTab(tabOverview, index == 0);
        setTab(tabMeetings, index == 1);
        setTab(tabTasks, index == 2);
        setVisible(tabOverviewRoot, index == 0);
        setVisible(tabMeetingsRoot, index == 1);
        setVisible(tabTasksRoot, index == 2);
        if (index == 0 && page != null) renderOverview();
        if (index == 1 && page != null) renderMeetings();
        if (index == 2 && page != null) renderTasks();
    }

    private void setTab(TextView tab, boolean active) {
        if (active) {
            tab.setBackgroundResource(R.drawable.qm_bg_room_tab_active);
            tab.setTextColor(getColor(R.color.qm_cream_on_wine));
        } else {
            tab.setBackgroundResource(0);
            tab.setTextColor(getColor(R.color.qm_muted));
        }
    }

    private static void setVisible(View v, boolean visible) {
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // ── Data ──────────────────────────────────────────────────────────

    private void loadRoom() {
        if (fetching || roomSlug == null) return;
        fetching = true;
        // v1.5.46: every load runs under a new generation; late results
        // from an older generation (activity recreated, superseded load)
        // are dropped at delivery time.
        final int gen = loadGeneration.incrementAndGet();
        showError(false);
        // v1.5.46: visible loading state on Retry — skeletons for the tab
        // panes AND the content scroll hidden, so a retry never looks like
        // a dead tap (airplane-mode cycle: retry fails fast, silently).
        setVisible(meetingsSkeleton, true);
        setVisible(tasksSkeleton, true);
        if (page == null) setVisible(contentScroll, false);

        QolabWebViewTransport transport = QolabWebViewTransport.get();
        if (transport == null) {
            fetching = false;
            SessionGate.requireValidSession(this, appOrigin(),
                    () -> runOnUiThread(this::loadRoom),
                    this::routeToLogin,
                    null);
            return;
        }
        transport.getJson("/office/rooms/" + roomSlug, r -> runOnUiThread(() -> {
            if (gen != loadGeneration.get() || isFinishing() || isDestroyed()) {
                return; // stale delivery — superseded or destroyed
            }
            fetching = false;
            Log.d(TAG, "room page status=" + r.status
                    + " bodyLen=" + (r.body == null ? -1 : r.body.length()));
            if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD) {
                // v1.5.40: verify before logout (SessionGate).
                SessionGate.requireValidSession(this, appOrigin(),
                        () -> runOnUiThread(this::loadRoom),
                        this::routeToLogin,
                        null);
                return;
            }
            if (r.status == 401) {
                routeToLogin(); // server-proven expiry
                return;
            }
            if (r.status != 200 || r.body == null) {
                showError(true);
                return;
            }
            JSONObject inertia = RoomDataClient.extractInertiaPage(r.body);
            RoomPage parsed = RoomDataClient.parseRoomPage(inertia);
            if (parsed == null) {
                Log.d(TAG, "parse failed (inertia page present=" + (inertia != null) + ")");
                showError(true);
                return;
            }
            page = parsed;
            overviewRendered = meetingsRendered = tasksRendered = false;
            if (titleView.getText().length() == 0 && parsed.roomName != null) {
                titleView.setText(parsed.roomName);
            }
            renderOverview();
            renderMeetings();
            renderTasks();
        }));
    }

    private void routeToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void showError(boolean show) {
        setVisible(errorText, show);
        setVisible(retryText, show);
        if (show) {
            setVisible(meetingsSkeleton, false);
            setVisible(tasksSkeleton, false);
            setVisible(contentScroll, false);
        } else {
            // v1.5.46 FIX: error cleared → content scroll must come back so
            // a successful Retry after a full-error state actually renders
            // (showError(true) hid it; nothing ever re-showed it).
            if (page != null) setVisible(contentScroll, true);
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────

    private void renderOverview() {
        if (overviewRendered || page == null) return;
        overviewRendered = true;
        setVisible(meetingsSkeleton, false);

        // members — compact people section (v1.5.29)
        membersCount.setText(String.format(Locale.getDefault(),
                getString(R.string.room_members_count), page.members.size()));
        membersRow.removeAllViews();
        int shown = 0;
        for (RoomDataClient.Member m : page.members) {
            if (shown >= 4) break;
            membersRow.addView(avatarView(m.name, shown > 0));
            shown++;
        }
        membersList.removeAllViews();
        int rows = 0;
        for (RoomDataClient.Member m : page.members) {
            if (rows >= 3) break;
            membersList.addView(memberRow(m));
            rows++;
        }
        // "View all N members" only when there are actually more than 3
        if (page.members.size() > 3) {
            membersMore.setVisibility(View.VISIBLE);
            membersMore.setText(String.format(Locale.getDefault(),
                    getString(R.string.room_view_all_members), page.members.size()));
        } else {
            membersMore.setVisibility(View.GONE);
        }

        // upcoming meeting — primary card (interactive in v1.5.29)
        List<Meeting> upcoming = page.upcoming;
        if (upcoming.isEmpty()) {
            setVisible(upcomingCard, false);
            setVisible(upcomingEmpty, true);
        } else {
            setVisible(upcomingEmpty, false);
            upcomingCard.removeAllViews();
            Meeting m = upcoming.get(0);
            upcomingCard.addView(meetingCard(m, true));
            setVisible(upcomingCard, true);
        }

        // recent meetings — compact cards (interactive)
        recentList.removeAllViews();
        if (page.completed.isEmpty()) {
            setVisible(recentEmpty, true);
        } else {
            setVisible(recentEmpty, false);
            int n = 0;
            for (Meeting m : page.completed) {
                if (n >= 3) break;
                recentList.addView(meetingCard(m, true));
                n++;
            }
        }

        // tasks summary — honest: room-level task list is not exposed by any
        // existing endpoint as JSON; show where task data stands.
        ((TextView) tasksSummary).setText(getString(R.string.room_no_tasks_hint));
    }

    /**
     * Compact member row: name (bold) + role/title (muted, human label).
     * Department is intentionally omitted — we are already inside the room.
     */
    private View memberRow(RoomDataClient.Member m) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));

        TextView name = new TextView(this);
        name.setText(m.name != null ? m.name : "");
        name.setTextColor(getColor(R.color.qm_ink));
        name.setTextSize(14);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(name);

        String roleLabel = humanMemberRole(m.role);
        String detail = joinNonNull(" · ", roleLabel, m.jobTitle);
        if (detail != null) {
            TextView sub = new TextView(this);
            sub.setText(detail);
            sub.setTextColor(getColor(R.color.qm_muted));
            sub.setTextSize(12);
            row.addView(sub);
        }
        return row;
    }

    /** channel_role slug → human label; unknown/null → null (omitted). */
    private static String humanMemberRole(String role) {
        if (role == null || role.trim().isEmpty()) return null;
        switch (role) {
            case "owner": return "Owner";
            case "admin": return "Admin";
            case "member": return "Member";
            default: return null;
        }
    }

    private void renderMeetings() {
        if (meetingsRendered || page == null) return;
        meetingsRendered = true;
        setVisible(meetingsSkeleton, false);

        // live
        if (page.live.isEmpty()) {
            setVisible(liveLabel, false);
            setVisible(liveList, false);
        } else {
            setVisible(liveLabel, true);
            liveList.setVisibility(View.VISIBLE);
            ((LinearLayout) liveList).removeAllViews();
            for (Meeting m : page.live) ((LinearLayout) liveList).addView(meetingCard(m, true));
        }

        // upcoming
        upcomingListM.removeAllViews();
        if (page.upcoming.isEmpty()) {
            setVisible(upcomingEmptyM, true);
        } else {
            setVisible(upcomingEmptyM, false);
            for (Meeting m : page.upcoming) upcomingListM.addView(meetingCard(m, true));
        }

        // completed (previous)
        completedListM.removeAllViews();
        if (page.completed.isEmpty()) {
            setVisible(completedEmptyM, true);
        } else {
            setVisible(completedEmptyM, false);
            for (Meeting m : page.completed) completedListM.addView(meetingCard(m, true));
        }
    }

    private void renderTasks() {
        if (tasksRendered || page == null) return;
        tasksRendered = true;
        setVisible(tasksSkeleton, false);
        // No existing endpoint exposes a room-scoped task list as JSON.
        // Honest unavailable state — never fabricated.
        setVisible(tasksList, false);
        setVisible(tasksEmpty, true);
    }

    /** Builds a meeting card row; interactive opens MeetingDetailActivity. */
    private View meetingCard(Meeting m, boolean interactive) {
        View card = getLayoutInflater().inflate(R.layout.view_room_meeting_row, null, false);
        TextView title = card.findViewById(R.id.meeting_row_title);
        TextView when = card.findViewById(R.id.meeting_row_when);
        TextView status = card.findViewById(R.id.meeting_row_status);
        TextView duration = card.findViewById(R.id.meeting_row_duration);
        TextView meta = card.findViewById(R.id.meeting_row_meta);

        title.setText(m.title != null ? m.title : "");
        when.setText(m.scheduledAtDisplay != null
                ? m.scheduledAtDisplay
                + (m.timezoneAbbr != null ? " " + m.timezoneAbbr : "")
                : "");

        int chipBg, chipText;
        String label;
        if ("live".equals(m.status)) {
            chipBg = R.drawable.qm_chip_live; chipText = R.color.qm_wine;
            label = getString(R.string.room_meeting_live);
        } else if ("cancelled".equals(m.status)) {
            chipBg = R.drawable.qm_chip_cancelled; chipText = R.color.qm_error;
            label = getString(R.string.room_meeting_cancelled);
        } else if ("ended".equals(m.status)) {
            chipBg = R.drawable.qm_chip_ended; chipText = R.color.qm_muted;
            label = getString(R.string.room_meeting_ended);
        } else {
            chipBg = R.drawable.qm_chip_scheduled; chipText = R.color.qm_mint_text;
            label = getString(R.string.room_meeting_scheduled);
        }
        status.setText(label);
        status.setBackgroundResource(chipBg);
        status.setTextColor(getColor(chipText));

        if (m.durationSeconds > 0) {
            duration.setVisibility(View.VISIBLE);
            duration.setText(String.format(Locale.getDefault(),
                    getString(R.string.room_meeting_minutes), m.durationSeconds / 60));
        }
        if (m.hasTranscript) {
            meta.setVisibility(View.VISIBLE);
            meta.setText(R.string.room_meeting_transcript);
        } else if (m.taskCount > 0) {
            meta.setVisibility(View.VISIBLE);
            meta.setText(String.format(Locale.getDefault(),
                    getString(R.string.room_meeting_tasks_n), m.taskCount));
        }

        // v1.5.33 contextual action — only for meetings that can actually
        // be entered per the backend status (live → Join, scheduled →
        // Start: first entry flips it live server-side).
        TextView enterAction = card.findViewById(R.id.meeting_row_enter);
        if (enterAction != null) {
            if ("live".equals(m.status)) {
                enterAction.setVisibility(View.VISIBLE);
                enterAction.setText(R.string.room_meeting_join);
                enterAction.setOnClickListener(v -> enterMeetingFromRow(m.id));
            } else if ("scheduled".equals(m.status)) {
                enterAction.setVisibility(View.VISIBLE);
                enterAction.setText(R.string.room_meeting_start);
                enterAction.setOnClickListener(v -> enterMeetingFromRow(m.id));
            } else {
                enterAction.setVisibility(View.GONE);
            }
        }

        if (interactive && m.id > 0) {
            card.setOnClickListener(v -> {
                Intent i = new Intent(this, MeetingDetailActivity.class);
                i.putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, m.id);
                i.putExtra(MeetingDetailActivity.EXTRA_MEETING_TITLE, m.title);
                startActivity(i);
            });
        }
        return card;
    }

    /** Overlapped initials avatar, same visual system as Home/Office. */
    private View avatarView(String name, boolean overlap) {
        TextView tv = new TextView(this);
        int size = dp(34);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(size, size);
        tv.setLayoutParams(flp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        if (overlap) lp.setMargins(-dp(10), 0, 0, 0);
        tv.setLayoutParams(lp);
        tv.setGravity(Gravity.CENTER);
        tv.setText(HomeActivity.initialsOf(name));
        tv.setTextColor(getColor(R.color.qm_white_text));
        tv.setTextSize(12);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(HomeActivity.avatarColorOf(name));
        // border in canvas color for separation
        g.setStroke(dp(2), getColor(R.color.qm_canvas));
        tv.setBackground(g);
        return tv;
    }

    private static String joinNonNull(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
