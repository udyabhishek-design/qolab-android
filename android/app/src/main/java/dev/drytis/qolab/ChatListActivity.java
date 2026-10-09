package dev.drytis.qolab;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * v1.5.40 — Native Chat: conversation list (approved mockup UI).
 *
 * Qolab wordmark + heading + subtitle → search action → filter chips
 * (All | DMs | Groups | Announcements) → yellow Announcements banner
 * (latest real announcement from GET /announcements; hidden when none
 * are published) → Pinned (official company channels — the backend has
 * no per-user pin flag, so this is the honest deterministic rule) and
 * Recent sections → pastel conversation cards (stable color per
 * channel id) with timestamps and unread badges → floating compose FAB
 * (Create Group).
 *
 * Data: GET /chat + GET /announcements + GET /chat/unread-counts
 * (existing endpoints only; real data only — nothing hardcoded).
 * Realtime: private-user.{me} notifications refresh the list (single
 * flight) so unread badges and ordering stay current.
 *
 * No Social Rooms, no Department Rooms — they are NOT part of the Chat
 * payload (getUserChannels includeRooms=false) and never rendered here.
 */
public class ChatListActivity extends AppCompatActivity {

    public static final String EXTRA_MY_USER_ID = "chat_my_user_id";

    private LinearLayout chatList;
    private ScrollView chatScroll;
    private View chatSkeleton, chatErrorBox, chatEmpty;
    private EditText searchField;
    private TextView filterAll, filterDms, filterGroups, filterAnnouncements;
    private LinearLayout announcementBanner;

    private List<ChatDataClient.Conversation> conversations = new ArrayList<>();
    private List<ChatDataClient.Employee> employees = new ArrayList<>();
    private ChatDataClient.Announcement latestAnnouncement;
    private java.util.Map<Long, Integer> unreadCounts;
    private volatile boolean fetching = false;
    private long myUserId;
    private String lastQuery = "";
    private ChatDataClient.ChatFilter filter = ChatDataClient.ChatFilter.ALL;

    /** Pastel card palette (mockup). */
    private static final int[] PASTELS = {
            0xFFDDEBD8, 0xFFF7E8B0, 0xFFE4DDF2, 0xFFF8E0D2, 0xFFD9E4F5 };

    private final ChatRealtime.Listener realtime = (channelId, event, data) -> {
        // Any message/notification activity → refresh list data (single
        // flight; keeps unread counts and ordering current).
        load(false);
    };

    // ── lifecycle ─────────────────────────────────────────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_chat_list);

        myUserId = getIntent().getLongExtra(EXTRA_MY_USER_ID, 0);

        ImageButton newGroup = findViewById(R.id.chat_btn_new_group);
        newGroup.setOnClickListener(v -> showCreateGroupSheet());
        ImageButton searchBtn = findViewById(R.id.chat_btn_search);
        searchBtn.setOnClickListener(v -> {
            // v1.5.40: search is an ACTION (mockup) — reveal + focus field.
            if (searchField.getVisibility() != View.VISIBLE) {
                searchField.setVisibility(View.VISIBLE);
                searchField.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(searchField,
                            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            } else {
                searchField.setVisibility(View.GONE);
                if (!lastQuery.isEmpty()) {
                    lastQuery = "";
                    searchField.setText("");
                    renderList();
                }
            }
        });

        chatList = findViewById(R.id.chat_list);
        chatScroll = findViewById(R.id.chat_list_scroll);
        chatSkeleton = findViewById(R.id.chat_skeleton);
        chatErrorBox = findViewById(R.id.chat_error_box);
        chatEmpty = findViewById(R.id.chat_empty);
        searchField = findViewById(R.id.chat_search);
        filterAll = findViewById(R.id.chat_filter_all);
        filterDms = findViewById(R.id.chat_filter_dms);
        filterGroups = findViewById(R.id.chat_filter_groups);
        filterAnnouncements = findViewById(R.id.chat_filter_announcements);
        announcementBanner = findViewById(R.id.chat_announcement_banner);

        filterAll.setOnClickListener(v -> selectFilter(ChatDataClient.ChatFilter.ALL));
        filterDms.setOnClickListener(v -> selectFilter(ChatDataClient.ChatFilter.DMS));
        filterGroups.setOnClickListener(v -> selectFilter(ChatDataClient.ChatFilter.GROUPS));
        filterAnnouncements.setOnClickListener(
                v -> selectFilter(ChatDataClient.ChatFilter.ANNOUNCEMENTS));

        findViewById(R.id.chat_retry).setOnClickListener(v -> load(true));
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim().toLowerCase(Locale.US);
                if (q.equals(lastQuery)) return;
                lastQuery = q;
                renderList();
            }
        });

        // Floating compose → Create Group (existing native flow).
        findViewById(R.id.chat_fab).setOnClickListener(v -> showCreateGroupSheet());

        applyInsets();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ChatRealtime rt = ChatRealtime.get();
        rt.configure(myUserId, origin());
        rt.listen("user", realtime);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ChatRealtime.get().release("user", realtime);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh on every resume (returning from a conversation may have
        // changed unread counts). Single-flight prevents duplicates.
        load(false);
    }

    private String origin() {
        return BuildConfig.QOLAB_ORIGIN != null ? BuildConfig.QOLAB_ORIGIN
                : "https://workpulse-gbpedt.drytis.dev";
    }

    @SuppressLint("ClickableViewAccessibility")
    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(android.R.id.content), (v, insets) -> {
                    int navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                    int status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                    View header = findViewById(R.id.chat_header);
                    header.setPadding(header.getPaddingStart(),
                            status + 10, header.getPaddingEnd(), header.getPaddingBottom());
                    chatList.setPadding(chatList.getPaddingStart(), chatList.getPaddingTop(),
                            chatList.getPaddingEnd(), navBar + 24);
                    return WindowInsetsCompat.CONSUMED;
                });
    }

    // ── data ──────────────────────────────────────────────────────────

    private void load(boolean manualRetry) {
        if (fetching) return;
        QolabWebViewTransport transport = QolabWebViewTransport.get();
        if (transport == null || !transport.isAlive()) {
            // v1.5.40: verify with the server before logging out — a dead
            // transport is not proof of expiry.
            SessionGate.requireValidSession(this, origin(),
                    () -> runOnUiThread(() -> load(false)),
                    this::routeToLogin,
                    () -> runOnUiThread(this::showError));
            return;
        }
        fetching = true;
        if (conversations.isEmpty()) showLoading();
        transport.getJson("/chat", r -> runOnUiThread(() -> {
            fetching = false;
            if (isFinishing() || isDestroyed()) return;
            if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD) {
                // v1.5.40: verify before logout (SessionGate).
                SessionGate.requireValidSession(this, origin(),
                        () -> runOnUiThread(() -> load(false)),
                        this::routeToLogin,
                        () -> runOnUiThread(this::showError));
                return;
            }
            if (r.status == 401) {
                routeToLogin(); // server-proven expiry (JSON Accept → 401)
                return;
            }
            if (r.status < 200 || r.status >= 300 || r.body == null) {
                showError();
                return;
            }
            JSONObject inertia = RoomDataClient.extractInertiaPage(r.body);
            ChatDataClient.ChatIndex parsed = ChatDataClient.parseInertiaIndex(inertia);
            if (parsed == null) {
                showError();
                return;
            }
            conversations = parsed.conversations;
            employees = parsed.employees;
            renderList();
            // Non-blocking extras: latest announcement + precise unread
            // counts (both best-effort; the list already renders).
            fetchAnnouncement();
            fetchUnreadCounts();
        }));
    }

    /** Best-effort latest published announcement for the yellow banner. */
    private void fetchAnnouncement() {
        QolabWebViewTransport transport = QolabWebViewTransport.get();
        if (transport == null || !transport.isAlive()) return;
        transport.getJson("/announcements", r -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (r.status != 200 || r.body == null) {
                latestAnnouncement = null; // honest: banner hidden
                renderBanner();
                return;
            }
            JSONObject page = RoomDataClient.extractInertiaPage(r.body);
            List<ChatDataClient.Announcement> list =
                    ChatDataClient.parseAnnouncements(page);
            latestAnnouncement = list.isEmpty() ? null : list.get(0);
            renderBanner();
        }));
    }

    /** Best-effort per-channel unread counts (GET /chat/unread-counts). */
    private void fetchUnreadCounts() {
        QolabWebViewTransport transport = QolabWebViewTransport.get();
        if (transport == null || !transport.isAlive()) return;
        transport.getJson("/chat/unread-counts", r -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (r.status != 200 || r.body == null) return;
            java.util.Map<Long, Integer> map = ChatDataClient.parseUnreadCounts(r.body);
            if (map != null) {
                unreadCounts = map;
                renderList();
            }
        }));
    }

    private void routeToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void showLoading() {
        chatSkeleton.setVisibility(View.VISIBLE);
        chatErrorBox.setVisibility(View.GONE);
        chatEmpty.setVisibility(View.GONE);
        chatList.removeAllViews();
    }

    private void showError() {
        chatSkeleton.setVisibility(View.GONE);
        chatList.removeAllViews();
        chatEmpty.setVisibility(View.GONE);
        chatErrorBox.setVisibility(View.VISIBLE);
    }

    // ── filters + banner ──────────────────────────────────────────────

    private void selectFilter(ChatDataClient.ChatFilter f) {
        if (filter == f) return;
        filter = f;
        renderFilterChips();
        renderList();
    }

    private void renderFilterChips() {
        setChip(filterAll, filter == ChatDataClient.ChatFilter.ALL);
        setChip(filterDms, filter == ChatDataClient.ChatFilter.DMS);
        setChip(filterGroups, filter == ChatDataClient.ChatFilter.GROUPS);
        setChip(filterAnnouncements, filter == ChatDataClient.ChatFilter.ANNOUNCEMENTS);
    }

    private void setChip(TextView chip, boolean active) {
        chip.setBackgroundResource(active
                ? R.drawable.qm_bg_filter_active
                : R.drawable.qm_bg_filter_idle);
        chip.setTextColor(active ? 0xFFFFFFFF
                : getColor(R.color.qm_ink));
        chip.setTypeface(active ? Typeface.create(chip.getTypeface(), Typeface.BOLD)
                : Typeface.create(chip.getTypeface(), Typeface.NORMAL));
    }

    /** Yellow Announcements banner — only when a real announcement exists. */
    private void renderBanner() {
        if (announcementBanner == null) return;
        if (latestAnnouncement == null) {
            announcementBanner.setVisibility(View.GONE);
            return;
        }
        announcementBanner.setVisibility(View.VISIBLE);
        TextView title = findViewById(R.id.chat_banner_title);
        TextView meta = findViewById(R.id.chat_banner_meta);
        title.setText(latestAnnouncement.title != null
                ? latestAnnouncement.title : "");
        String when = relativeTime(latestAnnouncement.publishedAtIso);
        String by = latestAnnouncement.creatorName != null
                ? latestAnnouncement.creatorName : "";
        meta.setText(when != null
                ? getString(R.string.chat_announcements_latest, when, by)
                : by);
        announcementBanner.setOnClickListener(v -> openAnnouncements());
    }

    private void openAnnouncements() {
        // Route through the existing authenticated WebView shell at the
        // real Announcements page (no native announcements screen exists).
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("qolab_start_path", "/announcements");
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    // ── rendering ─────────────────────────────────────────────────────

    private void renderList() {
        chatSkeleton.setVisibility(View.GONE);
        chatErrorBox.setVisibility(View.GONE);
        renderFilterChips();

        List<ChatDataClient.Conversation> filtered = new ArrayList<>();
        for (ChatDataClient.Conversation c : conversations) {
            if (!ChatDataClient.matchesFilter(c, filter)) continue;
            if (!lastQuery.isEmpty()
                    && (c.name == null
                    || !c.name.toLowerCase(Locale.US).contains(lastQuery))) {
                continue;
            }
            filtered.add(c);
        }

        if (conversations.isEmpty()) {
            chatList.removeAllViews();
            ((TextView) chatEmpty).setText(R.string.chat_empty);
            chatEmpty.setVisibility(View.VISIBLE);
            return;
        }
        if (filtered.isEmpty()) {
            chatList.removeAllViews();
            ((TextView) chatEmpty).setText(
                    getString(R.string.chat_empty_filtered, lastQuery));
            chatEmpty.setVisibility(View.VISIBLE);
            return;
        }
        chatEmpty.setVisibility(View.GONE);
        chatList.removeAllViews();

        List<ChatDataClient.Conversation> pinned = new ArrayList<>();
        List<ChatDataClient.Conversation> recent = new ArrayList<>();
        for (ChatDataClient.Conversation c : filtered) {
            if (ChatDataClient.isPinned(c)) pinned.add(c);
            else recent.add(c);
        }

        if (!pinned.isEmpty()) {
            chatList.addView(sectionHeader(R.string.chat_section_pinned, true));
            for (ChatDataClient.Conversation c : pinned) {
                chatList.addView(inflateRow(c));
            }
        }
        if (!recent.isEmpty()) {
            chatList.addView(sectionHeader(R.string.chat_section_recent, false));
            for (ChatDataClient.Conversation c : recent) {
                chatList.addView(inflateRow(c));
            }
        }
        if (pinned.isEmpty() && recent.isEmpty()) {
            // filtered nonempty guaranteed above; safety net only
            chatEmpty.setVisibility(View.VISIBLE);
        }
    }

    private View sectionHeader(int labelRes, boolean pinned) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(2);
        header.setPadding(pad, dp(8), pad, dp(6));

        if (pinned) {
            android.widget.ImageView pin = new android.widget.ImageView(this);
            pin.setImageResource(R.drawable.qm_ic_pin);
            pin.setBackgroundResource(R.drawable.qm_bg_pin_circle);
            int s = dp(18);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.rightMargin = dp(6);
            pin.setLayoutParams(lp);
            header.addView(pin);
        }
        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextColor(getColor(R.color.qm_ink));
        label.setTextSize(13);
        label.setTypeface(label.getTypeface(), Typeface.BOLD);
        header.addView(label);
        return header;
    }

    private View inflateRow(ChatDataClient.Conversation c) {
        View row = getLayoutInflater().inflate(R.layout.view_chat_row, chatList, false);
        View card = row.findViewById(R.id.chat_row_card);
        TextView avatar = row.findViewById(R.id.chat_row_avatar);
        TextView name = row.findViewById(R.id.chat_row_name);
        TextView preview = row.findViewById(R.id.chat_row_preview);
        TextView time = row.findViewById(R.id.chat_row_time);
        TextView unread = row.findViewById(R.id.chat_row_unread);

        // Pastel card background (stable per channel id, mockup palette).
        int pastel = PASTELS[ChatDataClient.pastelIndexFor(c.id, PASTELS.length)];
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setCornerRadius(dp(16));
        cardBg.setColor(pastel);
        card.setBackground(cardBg);
        row.setClickable(true);
        row.setFocusable(true);
        row.setForeground(null);
        card.setClickable(true);
        card.setFocusable(true);

        avatar.setText(HomeActivity.initialsOf(c.name));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(HomeActivity.avatarColorOf(c.name));
        avatar.setBackground(circle);

        name.setText(c.name);
        preview.setText(previewLine(c));

        // Timestamp (mockup): short relative time or weekday-time.
        String rel = relativeTime(c.lastMessageAt);
        time.setText(rel != null ? rel : "");

        int unreadCount = unreadCountOf(c);
        if (unreadCount > 0) {
            unread.setText(String.format(Locale.US, "%d", unreadCount));
            unread.setVisibility(View.VISIBLE);
            name.setTypeface(name.getTypeface(), Typeface.BOLD);
        } else {
            unread.setVisibility(View.GONE);
        }

        row.setOnClickListener(v -> openConversation(c));
        return row;
    }

    /** Channel unread: prefer the fresh unread-counts map, else payload. */
    private int unreadCountOf(ChatDataClient.Conversation c) {
        if (unreadCounts != null) {
            Integer v = unreadCounts.get(c.id);
            if (v != null) return v;
        }
        return c.unreadCount;
    }

    /** Honest preview: member count + relative activity (the payload has
     *  no last-message body; nothing is fabricated). */
    private String previewLine(ChatDataClient.Conversation c) {
        String rel = relativeTime(c.lastMessageAt);
        long m = Math.max(c.membersCount, c.dm ? 2 : 1);
        if (rel != null) {
            return m == 2
                    ? getString(R.string.chat_preview_two, rel)
                    : getString(R.string.chat_preview_members, m, rel);
        }
        return getString(R.string.chat_say_hello);
    }

    /** Same relative-time buckets Home uses. */
    static String relativeTime(String iso) {
        long t = ChatDataClient.parseIsoMillis(iso);
        if (t <= 0) return null;
        long diff = System.currentTimeMillis() - t;
        if (diff < 60_000L) return "just now";
        if (diff < 3_600_000L) return (diff / 60_000L) + "m ago";
        if (diff < 86_400_000L) return (diff / 3_600_000L) + "h ago";
        return (diff / 86_400_000L) + "d ago";
    }

    private void openConversation(ChatDataClient.Conversation c) {
        Intent i = new Intent(this, ChatScreenActivity.class);
        i.putExtra(ChatScreenActivity.EXTRA_CHANNEL_ID, c.id);
        i.putExtra(ChatScreenActivity.EXTRA_CHANNEL_NAME, c.name);
        i.putExtra(ChatScreenActivity.EXTRA_CHANNEL_TYPE, c.type);
        i.putExtra(ChatScreenActivity.EXTRA_MY_USER_ID, myUserId);
        startActivity(i);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ── create group sheet ────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private void showCreateGroupSheet() {
        Dialog sheet = new Dialog(this);
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
        sheet.setContentView(R.layout.view_create_group_sheet);
        Window w = sheet.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
        }

        EditText nameField = sheet.findViewById(R.id.group_name);
        EditText memberSearch = sheet.findViewById(R.id.group_member_search);
        LinearLayout membersList = sheet.findViewById(R.id.group_members_list);
        View skeleton = sheet.findViewById(R.id.group_members_skeleton);
        View empty = sheet.findViewById(R.id.group_members_empty);
        TextView selectedCount = sheet.findViewById(R.id.group_selected_count);
        TextView createBtn = sheet.findViewById(R.id.group_create);
        TextView errorText = sheet.findViewById(R.id.group_error);

        final Set<Long> selected = new HashSet<>();
        final String[] query = {""};
        final boolean[] submitting = {false};

        if (employees.isEmpty()) {
            // List page carried no employees (shouldn't happen — /chat always
            // includes them) — show skeleton + reload list data.
            skeleton.setVisibility(View.VISIBLE);
            load(false);
        }

        Runnable renderMembers = () -> {
            skeleton.setVisibility(employees.isEmpty() ? View.VISIBLE : View.GONE);
            membersList.removeAllViews();
            List<ChatDataClient.Employee> matches = new ArrayList<>();
            for (ChatDataClient.Employee e : employees) {
                if (query[0].isEmpty()
                        || (e.name != null
                        && e.name.toLowerCase(Locale.US).contains(query[0]))) {
                    matches.add(e);
                }
            }
            empty.setVisibility(matches.isEmpty() ? View.VISIBLE : View.GONE);
            for (ChatDataClient.Employee e : matches) {
                membersList.addView(inflateMemberRow(e, selected, selectedCount));
            }
        };

        memberSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query[0] = s.toString().trim().toLowerCase(Locale.US);
                renderMembers.run();
            }
        });

        renderMembers.run();

        createBtn.setOnClickListener(v -> {
            if (submitting[0]) return;
            errorText.setVisibility(View.GONE);
            String name = nameField.getText().toString().trim();
            if (name.isEmpty()) {
                errorText.setText(R.string.chat_group_error_name);
                errorText.setVisibility(View.VISIBLE);
                return;
            }
            if (selected.isEmpty()) {
                errorText.setText(R.string.chat_group_error_members);
                errorText.setVisibility(View.VISIBLE);
                return;
            }
            submitting[0] = true;
            createBtn.setAlpha(0.5f);

            StringBuilder ids = new StringBuilder();
            for (Long id : selected) {
                if (ids.length() > 0) ids.append(',');
                ids.append(id);
            }
            // POST /chat/channels {name, type:"group", member_ids} → 302 to
            // /chat/channels/{id}; recover id from finalUrl (v1.5.38 pattern).
            String body = "{\"name\":" + QolabWebViewTransport.jsonStringSafe(name)
                    + ",\"type\":\"group\",\"member_ids\":[" + ids + "]}";
            QolabWebViewTransport t = QolabWebViewTransport.get();
            if (t == null || !t.isAlive()) {
                submitting[0] = false;
                createBtn.setAlpha(1f);
                errorText.setText(R.string.chat_create_failed);
                errorText.setVisibility(View.VISIBLE);
                return;
            }
            t.postJson("/chat/channels", body, r -> runOnUiThread(() -> {
                submitting[0] = false;
                createBtn.setAlpha(1f);
                if (isFinishing() || isDestroyed()) return;
                long id = ChatDataClient.channelIdFromUrl(r.finalUrl);
                if (id > 0) {
                    sheet.dismiss();
                    Toast.makeText(this, getString(R.string.chat_group_created, name),
                            Toast.LENGTH_SHORT).show();
                    Intent i = new Intent(this, ChatScreenActivity.class);
                    i.putExtra(ChatScreenActivity.EXTRA_CHANNEL_ID, id);
                    i.putExtra(ChatScreenActivity.EXTRA_CHANNEL_NAME, name);
                    i.putExtra(ChatScreenActivity.EXTRA_CHANNEL_TYPE, "group");
                    i.putExtra(ChatScreenActivity.EXTRA_MY_USER_ID, myUserId);
                    startActivity(i);
                } else if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD
                        || r.status == 401) {
                    sheet.dismiss();
                    routeToLogin();
                } else {
                    errorText.setText(R.string.chat_create_failed);
                    errorText.setVisibility(View.VISIBLE);
                }
            }));
        });

        sheet.show();
    }

    private View inflateMemberRow(ChatDataClient.Employee e, Set<Long> selected,
                                  TextView selectedCount) {
        View row = getLayoutInflater().inflate(R.layout.view_member_row, null, false);
        TextView avatar = row.findViewById(R.id.member_row_avatar);
        TextView name = row.findViewById(R.id.member_row_name);
        TextView meta = row.findViewById(R.id.member_row_meta);
        View check = row.findViewById(R.id.member_row_check);

        avatar.setText(HomeActivity.initialsOf(e.name));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(HomeActivity.avatarColorOf(e.name));
        avatar.setBackground(circle);
        name.setText(e.name);
        String metaText = e.jobTitle != null ? e.jobTitle
                : (e.departmentName != null ? e.departmentName : "");
        meta.setText(metaText);
        check.setVisibility(selected.contains(e.id) ? View.VISIBLE : View.INVISIBLE);

        row.setOnClickListener(v -> {
            if (selected.contains(e.id)) {
                selected.remove(e.id);
                check.setVisibility(View.INVISIBLE);
            } else {
                selected.add(e.id);
                check.setVisibility(View.VISIBLE);
            }
            if (selected.isEmpty()) {
                selectedCount.setVisibility(View.GONE);
            } else {
                selectedCount.setText(getString(R.string.chat_members_selected, selected.size()));
                selectedCount.setVisibility(View.VISIBLE);
            }
        });
        return row;
    }
}
