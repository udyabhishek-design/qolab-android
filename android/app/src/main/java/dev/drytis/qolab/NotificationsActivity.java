package dev.drytis.qolab;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.5.46 — Native Notifications screen (Home bell destination).
 *
 * Contracts consumed (existing, unchanged):
 *   GET   /notifications?page=N
 *   PATCH /notifications/{id}/read
 *   PATCH /notifications/read-all
 * through the authenticated WebView transport (same session as Home).
 *
 * Behaviour:
 *   - loading / empty / error+Retry states, mutually exclusive
 *   - pagination: "Load more" appends the next page, deduped by id
 *   - already-loaded rows are PRESERVED on a transient refresh failure
 *     (v1.5.44 rule) — only a full first-page failure shows the error state
 *   - mark-read (single / all) applies the UI change ONLY after the server
 *     confirms {"success":true}; HTTP 200 {"success":false} = failure
 *   - message.sent rows with a channel_id deep-link into the native
 *     ChatScreenActivity; other types show information only (no invented
 *     destination); a channel that fails to open shows an honest notice
 *   - unread badge sync: on return to Home, HomeActivity.onResume refetches
 *     only when stale; after mark-read flows Home refreshes its badge from
 *     the composite. Late/stale unread patches are generation-guarded
 *     (LogoutFlowRules) so they can never overwrite a newer read state.
 *   - callbacks are generation + lifecycle guarded; nothing renders after
 *     destruction, and post-logout stale callbacks are dropped via the
 *     shared REQUEST_GENERATION bump (LogoutFlowTest contract).
 */
public class NotificationsActivity extends AppCompatActivity {

    private static final String TAG = "NotifActivity";

    private LinearLayout list;
    private ScrollView scroll;
    private View skeleton, errorView, retryView, emptyView;
    private TextView markAll;
    /** Load-more footer (re-created; reference kept for state flips). */
    private TextView loadMoreView;

    private List<NotificationsDataClient.Item> items = new ArrayList<>();
    private int currentPage = 0;
    private int lastPage = 1;
    private int totalLoaded = 0;
    private boolean firstLoaded = false;

    private volatile boolean fetching;
    private volatile boolean markingAll;
    /** Generation of the current load cycle — stale deliveries dropped. */
    private final java.util.concurrent.atomic.AtomicInteger generation =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** v1.5.45 logout gate: bumped globally on logout; late callbacks from
     *  the previous session (previous ACCOUNT) are dropped. */
    private final int sessionGen = HomeActivity.currentRequestGeneration();

    private String appOrigin() {
        return BuildConfig.QOLAB_ORIGIN != null ? BuildConfig.QOLAB_ORIGIN
                : "https://workpulse-gbpedt.drytis.dev";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notifications);

        list = findViewById(R.id.notif_list);
        scroll = findViewById(R.id.notif_scroll);
        skeleton = findViewById(R.id.notif_skeleton);
        errorView = findViewById(R.id.notif_error);
        retryView = findViewById(R.id.notif_retry);
        emptyView = findViewById(R.id.notif_empty);
        markAll = findViewById(R.id.notif_mark_all);

        ((ImageButton) findViewById(R.id.notif_btn_back))
                .setOnClickListener(v -> finish());
        retryView.setOnClickListener(v -> loadFirst());
        markAll.setOnClickListener(v -> markAllRead());

        loadFirst();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from a Chat deep-link: refresh the FIRST page so read
        // state matches the server (rows opened via deep-link were marked
        // read there). Only when nothing is in flight.
        if (firstLoaded && !fetching) {
            refreshFirstPage();
        }
    }

    // ── Loading ───────────────────────────────────────────────────────

    private void loadFirst() {
        if (fetching) return;
        fetching = true;
        final int gen = generation.incrementAndGet();
        if (!firstLoaded) {
            showSkeleton();
        } else {
            showListLoadingMore(); // keep rows; footer spinner
        }
        QolabWebViewTransport t = QolabWebViewTransport.get();
        if (t == null || !t.isAlive()) {
            fetching = false;
            SessionGate.requireValidSession(this, appOrigin(),
                    () -> runOnUiThread(this::loadFirst),
                    this::routeToLogin,
                    () -> runOnUiThread(this::showError));
            return;
        }
        t.getJson("/notifications?page=1", r -> runOnUiThread(() -> {
            if (stale(gen)) return;
            fetching = false;
            if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD) {
                SessionGate.requireValidSession(this, appOrigin(),
                        () -> runOnUiThread(this::loadFirst),
                        this::routeToLogin,
                        () -> runOnUiThread(this::showError));
                return;
            }
            if (r.status == 401) {
                routeToLogin(); // server-proven expiry
                return;
            }
            if (r.status != 200 || r.body == null) {
                showError();
                return;
            }
            NotificationsDataClient.Page p =
                    NotificationsDataClient.parsePage(r.body);
            if (p == null) {
                showError();
                return;
            }
            items = new ArrayList<>(p.items);
            currentPage = p.currentPage;
            lastPage = p.lastPage;
            totalLoaded = p.items.size();
            firstLoaded = true;
            renderAll();
        }));
    }

    private void loadNext() {
        if (fetching || !firstLoaded) return;
        if (currentPage >= lastPage) return;
        fetching = true;
        final int gen = generation.get();
        final int nextPage = currentPage + 1;
        setLoadingMore(true);
        QolabWebViewTransport t = QolabWebViewTransport.get();
        if (t == null || !t.isAlive()) {
            fetching = false;
            setLoadingMore(false);
            // v1.5.44 rule: rows already on screen are preserved; the
            // "Load more" footer stays tappable for a manual retry.
            Toast.makeText(this, R.string.notif_load_more_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        t.getJson("/notifications?page=" + nextPage, r -> runOnUiThread(() -> {
            if (stale(gen)) return;
            fetching = false;
            setLoadingMore(false);
            if (r.status != 200 || r.body == null) {
                // Transient failure appending a page: PRESERVE loaded rows
                // (v1.5.44 rule) — footer stays as "Load more" for retry.
                Toast.makeText(this, R.string.notif_load_more_failed,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            NotificationsDataClient.Page p =
                    NotificationsDataClient.parsePage(r.body);
            if (p == null) {
                Toast.makeText(this, R.string.notif_load_more_failed,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            // Dedupe by id — server pagination can overlap after reads.
            items = NotificationsDataClient.mergeItems(items, p.items);
            currentPage = p.currentPage;
            lastPage = p.lastPage;
            totalLoaded = items.size();
            renderAll();
        }));
    }

    /** Silent refresh of page 1 after returning from a deep-link. */
    private void refreshFirstPage() {
        if (fetching) return;
        fetching = true;
        final int gen = generation.get();
        QolabWebViewTransport t = QolabWebViewTransport.get();
        if (t == null || !t.isAlive()) {
            fetching = false;
            return; // silent — rows stay
        }
        t.getJson("/notifications?page=1", r -> runOnUiThread(() -> {
            if (stale(gen)) return;
            fetching = false;
            if (r.status != 200 || r.body == null) return;
            NotificationsDataClient.Page p =
                    NotificationsDataClient.parsePage(r.body);
            if (p == null) return;
            items = new ArrayList<>(p.items);
            currentPage = p.currentPage;
            lastPage = p.lastPage;
            totalLoaded = p.items.size();
            renderAll(); // silent refresh: rendered state IS the result
        }));
    }

    // ── Mark as read ──────────────────────────────────────────────────

    private void markRead(final NotificationsDataClient.Item it) {
        if (it.read) {
            openIfChat(it);
            return; // already read server-side; just navigate
        }
        QolabWebViewTransport t = QolabWebViewTransport.get();
        if (t == null || !t.isAlive()) {
            Toast.makeText(this, R.string.notif_mark_failed,
                    Toast.LENGTH_SHORT).show();
            openIfChat(it);
            return;
        }
        // v1.5.47: true PATCH (the route rejects POST with 405).
        t.postJson("/notifications/" + it.id + "/read", "{}",
                r -> runOnUiThread(() -> {
            if (stale(-1)) return;
            boolean ok = r.status == 200
                    && NotificationsDataClient.parseSuccess(r.body);
            if (!ok) {
                // Preserve previous read state + honest feedback.
                Toast.makeText(this, R.string.notif_mark_failed,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            items = NotificationsDataClient.withRead(items, it.id);
            renderAll();
        }), true);
    }

    private void markAllRead() {
        if (markingAll) return;
        markingAll = true;
        QolabWebViewTransport t = QolabWebViewTransport.get();
        if (t == null || !t.isAlive()) {
            markingAll = false;
            Toast.makeText(this, R.string.notif_mark_all_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // v1.5.47: true PATCH (the route rejects POST with 405).
        t.postJson("/notifications/read-all", "{}", r -> runOnUiThread(() -> {
            markingAll = false;
            if (stale(-1)) return;
            boolean ok = r.status == 200
                    && NotificationsDataClient.parseSuccess(r.body);
            if (!ok) {
                Toast.makeText(this, R.string.notif_mark_all_failed,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            items = NotificationsDataClient.allRead(items);
            renderAll();
        }), true);
    }

    // ── Deep-link ─────────────────────────────────────────────────────

    /**
     * v1.5.47 Fix 6: resolve the authenticated user id independently of
     * Home's volatile cache. Order: (1) Home cache when present; (2) a
     * one-shot authenticated /dashboard fetch (parseDashboardPage exposes
     * the same auth.user identity every other screen trusts). We NEVER
     * fabricate a user id — if identity can't be resolved the chat
     * destination is still opened with the best available value only when
     * it is > 0; otherwise the user gets an honest notice instead of a
     * broken permission check.
     */
    private void openIfChat(final NotificationsDataClient.Item it) {
        if (!NotificationsDataClient.isChatOpenable(it)) return;
        long cachedId = peekMyUserId();
        if (cachedId > 0) {
            route(it, cachedId);
            return;
        }
        QolabWebViewTransport t = QolabWebViewTransport.get();
        if (t == null || !t.isAlive()) {
            Toast.makeText(this, R.string.notif_channel_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        t.getJson("/dashboard", r -> runOnUiThread(() -> {
            if (stale(-1)) return;
            long uid = 0;
            if (r.status == 200 && r.body != null) {
                HomeDataClient.PartialDashboard d =
                        HomeDataClient.parseDashboardPage(r.body);
                if (d != null) uid = d.userId;
            }
            if (uid <= 0) {
                // No fabricated identity — honest notice, row untouched.
                Toast.makeText(this, R.string.notif_channel_unavailable,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            route(it, uid);
        }));
    }

    private long peekMyUserId() {
        try {
            Object cached = HomeActivity.peekCachedHomeData();
            if (cached instanceof HomeDataClient.HomeData) {
                return ((HomeDataClient.HomeData) cached).userId;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * v1.5.47 Fix 4 (approved product decision): message.sent in THE LOBBY
     * opens the native Lobby room (Discussion | Members), ordinary chat
     * channels open ChatScreenActivity. The Lobby is identified by matching
     * the notification's channel_id against the social rooms from the
     * backend /rooms payload (room_theme == "lobby") — the authoritative
     * mapping openSocialRoom() already uses; never a title match.
     */
    private void route(NotificationsDataClient.Item it, long myUserId) {
        HomeDataClient.HomeData cached = null;
        try {
            Object o = HomeActivity.peekCachedHomeData();
            if (o instanceof HomeDataClient.HomeData) cached =
                    (HomeDataClient.HomeData) o;
        } catch (Exception ignored) {
        }
        String lobbySlug = null;
        if (cached != null
                && NotificationsDataClient.isLobbyChannel(it, cached.socialRooms)) {
            lobbySlug = lobbySlugFor(it, cached);
        }
        if (lobbySlug != null && !lobbySlug.isEmpty()) {
            // LobbyActivity requires id + slug; without a verified slug it
            // would finish() instantly — route to Chat instead (both read
            // the same channel feed).
            Intent i = new Intent(this, LobbyActivity.class);
            i.putExtra(LobbyActivity.EXTRA_ROOM_ID, it.channelId);
            i.putExtra(LobbyActivity.EXTRA_ROOM_SLUG, lobbySlug);
            i.putExtra(LobbyActivity.EXTRA_ROOM_NAME,
                    it.channelName != null ? it.channelName : "");
            startActivity(i);
            return;
        }
        Intent i = new Intent(this, ChatScreenActivity.class);
        i.putExtra(ChatScreenActivity.EXTRA_CHANNEL_ID, it.channelId);
        i.putExtra(ChatScreenActivity.EXTRA_CHANNEL_NAME,
                it.channelName != null ? it.channelName : "");
        i.putExtra(ChatScreenActivity.EXTRA_CHANNEL_TYPE,
                it.isDm ? "dm" : (it.channelType != null ? it.channelType : "group"));
        i.putExtra(ChatScreenActivity.EXTRA_MY_USER_ID, myUserId);
        startActivity(i);
        // If the channel is unavailable, the destination screen surfaces its
        // own error/Retry state — the user returns here with rows intact.
    }

    private static String lobbySlugFor(NotificationsDataClient.Item it,
            HomeDataClient.HomeData cached) {
        if (cached != null && cached.socialRooms != null) {
            for (HomeDataClient.Room r : cached.socialRooms) {
                if (r != null && r.id == it.channelId && r.roomSlug != null) {
                    return r.roomSlug;
                }
            }
        }
        return ""; // LobbyActivity derives identity from id when slug absent
    }

    // ── Rendering ─────────────────────────────────────────────────────

    private void renderAll() {
        hideStateViews();
        list.removeAllViews();
        markAll.setVisibility(items.isEmpty()
                ? View.GONE
                : (NotificationsDataClient.countUnread(items) > 0
                    ? View.VISIBLE : View.GONE));
        if (items.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        for (NotificationsDataClient.Item it : items) {
            list.addView(row(it));
        }
        if (currentPage < lastPage) {
            list.addView(loadMoreFooter());
        }
    }

    private View row(final NotificationsDataClient.Item it) {
        View card = getLayoutInflater().inflate(
                R.layout.view_notification_row, list, false);
        TextView title = card.findViewById(R.id.notif_row_title);
        TextView body = card.findViewById(R.id.notif_row_body);
        TextView when = card.findViewById(R.id.notif_row_when);
        View dot = card.findViewById(R.id.notif_row_dot);

        title.setText(it.title != null ? it.title : "");
        title.setAlpha(it.read ? 0.75f : 1f);
        body.setText(it.body != null ? it.body : "");
        String rel = RoomFormat.formatIsoDate(it.createdAtIso);
        when.setText(rel != null ? rel : "");

        dot.setVisibility(it.read ? View.GONE : View.VISIBLE);
        if (!it.read) {
            dot.setContentDescription(getString(R.string.notif_row_unread_cd));
        }

        boolean openable = NotificationsDataClient.isChatOpenable(it);
        card.setOnClickListener(v -> {
            if (it.read) {
                openIfChat(it);
            } else {
                // Mark read server-first, then navigate.
                QolabWebViewTransport t = QolabWebViewTransport.get();
                if (t == null || !t.isAlive()) {
                    Toast.makeText(this, R.string.notif_mark_failed,
                            Toast.LENGTH_SHORT).show();
                    openIfChat(it);
                    return;
                }
                t.postJson("/notifications/" + it.id + "/read", "{}",
                        r -> runOnUiThread(() -> {
                            boolean ok = r.status == 200
                                    && NotificationsDataClient.parseSuccess(r.body);
                            if (!ok) {
                                // Server rejected the mutation — keep the
                                // unread state (never falsely marked read),
                                // but STILL open the destination (approved
                                // v1.5.47 behavior: navigation is not held
                                // hostage by a failed mark-read).
                                Toast.makeText(this, R.string.notif_mark_failed,
                                        Toast.LENGTH_SHORT).show();
                                openIfChat(it);
                                return;
                            }
                            items = NotificationsDataClient.withRead(items, it.id);
                            renderAll();
                            openIfChat(it);
                        }), true);
            }
        });
        return card;
    }

    private View loadMoreFooter() {
        TextView tv = new TextView(this);
        loadMoreView = tv;
        tv.setText(R.string.notif_load_more);
        tv.setTextColor(getColor(R.color.qm_wine));
        tv.setTextSize(13f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(10), 0, dp(10));
        tv.setOnClickListener(v -> loadNext());
        return tv;
    }

    private void setLoadingMore(boolean loading) {
        if (loadMoreView != null) {
            loadMoreView.setText(loading
                    ? R.string.notif_loading_more : R.string.notif_load_more);
            loadMoreView.setEnabled(!loading);
        }
    }

    // ── State views ───────────────────────────────────────────────────

    private void showSkeleton() {
        hideStateViews();
        skeleton.setVisibility(View.VISIBLE);
        scroll.setVisibility(View.GONE);
        markAll.setVisibility(View.GONE);
    }

    private void showListLoadingMore() {
        errorView.setVisibility(View.GONE);
        retryView.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);
        skeleton.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        setLoadingMore(true);
    }

    private void showError() {
        if (firstLoaded) {
            // v1.5.44 rule: transient failure with content on screen —
            // preserve rows, non-blocking feedback only.
            Toast.makeText(this, R.string.notif_mark_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        hideStateViews();
        errorView.setVisibility(View.VISIBLE);
        retryView.setVisibility(View.VISIBLE);
        scroll.setVisibility(View.GONE);
    }

    private void hideStateViews() {
        skeleton.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        retryView.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
    }

    // ── Guards ────────────────────────────────────────────────────────

    /** Stale delivery check: destroyed, logged out (global generation), or
     *  from a superseded load cycle (gen mismatch; pass -1 to skip). */
    private boolean stale(int gen) {
        if (isFinishing() || isDestroyed()) return true;
        if (HomeActivity.currentRequestGeneration() != sessionGen) return true;
        if (gen >= 0 && gen != generation.get()) return true;
        return false;
    }

    private void routeToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
