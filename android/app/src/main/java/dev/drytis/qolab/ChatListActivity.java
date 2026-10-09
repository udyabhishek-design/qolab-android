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
 * v1.5.39 — Native Chat: conversation list.
 *
 * Data: GET /chat (existing Inertia endpoint, rooms excluded by the
 * backend). Realtime: private-user.{me} notifications refresh unread
 * badges and preview times without a refetch. Search filters locally on
 * the loaded list (the backend list is one page and small).
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

    private List<ChatDataClient.Conversation> conversations = new ArrayList<>();
    private List<ChatDataClient.Employee> employees = new ArrayList<>();
    private volatile boolean fetching = false;
    private long myUserId;
    private String lastQuery = "";

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

        ImageButton back = findViewById(R.id.chat_btn_back);
        back.setOnClickListener(v -> finish());
        ImageButton newGroup = findViewById(R.id.chat_btn_new_group);
        newGroup.setOnClickListener(v -> showCreateGroupSheet());

        chatList = findViewById(R.id.chat_list);
        chatScroll = findViewById(R.id.chat_list_scroll);
        chatSkeleton = findViewById(R.id.chat_skeleton);
        chatErrorBox = findViewById(R.id.chat_error_box);
        chatEmpty = findViewById(R.id.chat_empty);
        searchField = findViewById(R.id.chat_search);

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
            QolabWebViewTransport.rehydrate(this, origin(), valid -> {
                if (valid) load(false);
                else routeToLogin();
            });
            return;
        }
        fetching = true;
        if (conversations.isEmpty()) showLoading();
        transport.getJson("/chat", r -> runOnUiThread(() -> {
            fetching = false;
            if (isFinishing() || isDestroyed()) return;
            if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD || r.status == 401) {
                routeToLogin();
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

    // ── rendering ─────────────────────────────────────────────────────

    private void renderList() {
        chatSkeleton.setVisibility(View.GONE);
        chatErrorBox.setVisibility(View.GONE);

        List<ChatDataClient.Conversation> filtered = new ArrayList<>();
        if (lastQuery.isEmpty()) {
            filtered.addAll(conversations);
        } else {
            for (ChatDataClient.Conversation c : conversations) {
                if (c.name != null && c.name.toLowerCase(Locale.US).contains(lastQuery)) {
                    filtered.add(c);
                }
            }
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
        for (ChatDataClient.Conversation c : filtered) {
            chatList.addView(inflateRow(c));
        }
    }

    private View inflateRow(ChatDataClient.Conversation c) {
        View row = getLayoutInflater().inflate(R.layout.view_chat_row, chatList, false);
        TextView avatar = row.findViewById(R.id.chat_row_avatar);
        TextView name = row.findViewById(R.id.chat_row_name);
        TextView preview = row.findViewById(R.id.chat_row_preview);
        TextView unread = row.findViewById(R.id.chat_row_unread);

        avatar.setText(HomeActivity.initialsOf(c.name));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(HomeActivity.avatarColorOf(c.name));
        avatar.setBackground(circle);

        name.setText(c.name);
        preview.setText(previewLine(c));

        if (c.unreadCount > 0) {
            unread.setText(String.format(Locale.US, "%d", c.unreadCount));
            unread.setVisibility(View.VISIBLE);
            name.setTypeface(name.getTypeface(), Typeface.BOLD);
        } else {
            unread.setVisibility(View.GONE);
        }

        row.setOnClickListener(v -> openConversation(c));
        return row;
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
