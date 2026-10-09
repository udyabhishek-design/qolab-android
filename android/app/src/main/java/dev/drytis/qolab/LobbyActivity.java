package dev.drytis.qolab;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * v1.5.30 — The Lobby: native social room (Discussion | Members).
 *
 * Contracts consumed (existing, unchanged):
 *   GET  /chat/channels/{id}/messages  — real discussion feed + threads
 *   POST /chat/channels/{id}/messages  — composer (existing send contract,
 *        CSRF satisfied inside the transport WebView; body-or-attachment
 *        rule respected — we never send an empty body)
 *   GET  /office/rooms/{slug} Inertia props — channel members (same source
 *        as RoomActivity uses for Engineering Room)
 *
 * Identity comes from the Office payload: EXTRA_ROOM_ID (channel id) +
 * EXTRA_ROOM_SLUG + name — never hardcoded.
 *
 * Honest limitations (by design, not omission): reactions do not exist in
 * the backend; realtime (Reverb/Echo) is not implemented natively — the
 * feed refreshes on send/retry; presence/online status is not exposed, so
 * none is shown; voice/call is out of scope for this build.
 */
public class LobbyActivity extends AppCompatActivity {

    private static final String TAG = "LobbyActivity";
    public static final String EXTRA_ROOM_ID = "lobby_room_id";
    public static final String EXTRA_ROOM_SLUG = "lobby_room_slug";
    public static final String EXTRA_ROOM_NAME = "lobby_room_name";

    private long channelId;
    private String roomSlug;
    private String roomName;

    // header / tabs
    private TextView titleView, avatar;
    private TextView tabDiscussion, tabMembers;
    private View discussionPane, membersPane;

    // discussion
    private LinearLayout messagesList;
    private ScrollView messagesScroll;
    private View skeleton, errorView, retryView, sendBtn;
    private EditText composerInput;
    private boolean sending = false;

    // members
    private LinearLayout membersAvatars, membersList;
    private TextView membersCount;
    private View membersSkeleton;

    private List<LobbyDataClient.LobbyMessage> messages;
    private List<RoomDataClient.Member> members;
    private boolean membersLoaded;

    /** v1.5.40: transport origin for the SessionGate recheck. */
    private String appOrigin() {
        return BuildConfig.QOLAB_ORIGIN != null ? BuildConfig.QOLAB_ORIGIN
                : "https://workpulse-gbpedt.drytis.dev";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lobby);

        channelId = getIntent().getLongExtra(EXTRA_ROOM_ID, 0);
        roomSlug = getIntent().getStringExtra(EXTRA_ROOM_SLUG);
        roomName = getIntent().getStringExtra(EXTRA_ROOM_NAME);
        if (channelId <= 0 || roomSlug == null || roomSlug.isEmpty()) {
            finish(); // no usable room identity from Office data — nothing to show
            return;
        }

        bindViews();
        titleView.setText(roomName != null ? roomName
                : getString(R.string.lobby_title_default));
        avatar.setText(HomeActivity.initialsOf(roomName));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(HomeActivity.avatarColorOf(roomName));
        avatar.setBackground(g);

        showTab(0);
        loadDiscussion();
    }

    private void bindViews() {
        titleView = findViewById(R.id.lobby_title);
        avatar = findViewById(R.id.lobby_avatar);
        ((ImageButton) findViewById(R.id.lobby_btn_back)).setOnClickListener(v -> finish());
        // Notification bell present for header parity; badge stays hidden —
        // honest absence rather than a fake count.
        tabDiscussion = findViewById(R.id.lobby_tab_discussion);
        tabMembers = findViewById(R.id.lobby_tab_members);
        discussionPane = findViewById(R.id.lobby_discussion_pane);
        membersPane = findViewById(R.id.lobby_members_pane);
        tabDiscussion.setOnClickListener(v -> showTab(0));
        tabMembers.setOnClickListener(v -> {
            showTab(1);
            if (!membersLoaded) loadMembers();
        });

        messagesList = findViewById(R.id.lobby_messages_list);
        messagesScroll = findViewById(R.id.lobby_messages_scroll);
        skeleton = findViewById(R.id.lobby_skeleton);
        errorView = findViewById(R.id.lobby_error);
        retryView = findViewById(R.id.lobby_retry);
        composerInput = findViewById(R.id.lobby_composer_input);
        sendBtn = findViewById(R.id.lobby_composer_send);
        sendBtn.setOnClickListener(v -> attemptSend());
        composerInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) {
                boolean canSend = s.toString().trim().length() > 0 && !sending;
                sendBtn.setEnabled(canSend);
                sendBtn.setAlpha(canSend ? 1f : 0.45f);
            }
        });

        membersAvatars = findViewById(R.id.lobby_members_avatars);
        membersList = findViewById(R.id.lobby_members_list);
        membersCount = findViewById(R.id.lobby_members_count);
        membersSkeleton = findViewById(R.id.lobby_members_skeleton);
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    private void showTab(int idx) {
        tabDiscussion.setBackgroundResource(idx == 0 ? R.drawable.qm_bg_room_tab_active : 0);
        tabDiscussion.setTextColor(getColor(idx == 0 ? R.color.qm_cream_on_wine : R.color.qm_muted));
        tabMembers.setBackgroundResource(idx == 1 ? R.drawable.qm_bg_room_tab_active : 0);
        tabMembers.setTextColor(getColor(idx == 1 ? R.color.qm_cream_on_wine : R.color.qm_muted));
        discussionPane.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        membersPane.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------
    // Discussion
    // ------------------------------------------------------------------

    private void loadDiscussion() {
        skeleton.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        retryView.setVisibility(View.GONE);
        // v1.5.40: capture at use-time; a field-init capture was the
        // v1.5.35 intermittent-failure class.
        final QolabWebViewTransport t = QolabWebViewTransport.get();
        if (t == null || !t.isAlive()) {
            SessionGate.requireValidSession(this, appOrigin(),
                    () -> runOnUiThread(this::loadDiscussion),
                    () -> runOnUiThread(() -> {
                        startActivity(new Intent(LobbyActivity.this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                        finish();
                    }),
                    () -> runOnUiThread(this::showError));
            return;
        }
        t.getJson("/chat/channels/" + channelId + "/messages?per_page=50", r -> {
            runOnUiThread(() -> {
                skeleton.setVisibility(View.GONE);
                Log.d(TAG, "messages status=" + r.status + " bodyLen="
                        + (r.body == null ? -1 : r.body.length()));
                if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD) {
                    // v1.5.40: verify before bouncing (SessionGate).
                    SessionGate.requireValidSession(this, appOrigin(),
                            () -> runOnUiThread(this::loadDiscussion),
                            () -> runOnUiThread(() -> {
                                startActivity(new Intent(LobbyActivity.this, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                                finish();
                            }),
                            () -> runOnUiThread(this::showError));
                    return;
                }
                if (r.status == 401) {
                    startActivity(new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    finish();
                    return;
                }
                if (r.status != 200 || r.body == null) {
                    showError();
                    return;
                }
                messages = LobbyDataClient.parseMessages(r.body);
                if (messages == null) {
                    showError();
                    return;
                }
                renderMessages();
            });
        });
    }

    private void showError() {
        errorView.setVisibility(View.VISIBLE);
        retryView.setVisibility(View.VISIBLE);
        retryView.setOnClickListener(v -> loadDiscussion());
    }

    private void renderMessages() {
        messagesList.removeAllViews();
        if (messages.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.lobby_discussion_empty);
            empty.setTextColor(getColor(R.color.qm_muted));
            empty.setTextSize(13);
            empty.setPadding(0, dp(12), 0, dp(12));
            messagesList.addView(empty);
            return;
        }
        for (LobbyDataClient.LobbyMessage m : messages) {
            messagesList.addView(messageRow(m, true));
        }
        messagesScroll.post(() -> messagesScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    /** Message card; interactive (opens thread) only when replies exist. */
    private View messageRow(LobbyDataClient.LobbyMessage m, boolean interactive) {
        View card = getLayoutInflater().inflate(R.layout.view_lobby_message, messagesList, false);
        ((TextView) card.findViewById(R.id.lobby_msg_sender))
                .setText(m.senderName != null ? m.senderName : "");
        String when = RoomFormat.formatIsoDate(m.createdAtIso);
        ((TextView) card.findViewById(R.id.lobby_msg_when)).setText(when != null ? when : "");
        ((TextView) card.findViewById(R.id.lobby_msg_body))
                .setText(m.body != null ? m.body : "");
        TextView thread = card.findViewById(R.id.lobby_msg_thread);
        if (!m.replies.isEmpty()) {
            thread.setVisibility(View.VISIBLE);
            thread.setText(m.replies.size() == 1
                    ? getString(R.string.lobby_thread_one_reply)
                    : String.format(Locale.getDefault(),
                        getString(R.string.lobby_thread_replies), m.replies.size()));
            if (interactive) card.setOnClickListener(v -> openThread(m));
        }
        return card;
    }

    /** Native thread panel: parent message + its actual replies. */
    private void openThread(LobbyDataClient.LobbyMessage parent) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        panel.setPadding(pad, pad, pad, pad);

        TextView heading = new TextView(this);
        heading.setText(R.string.lobby_thread_heading);
        heading.setTextColor(getColor(R.color.qm_ink));
        heading.setTextSize(16);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setPadding(0, 0, 0, dp(10));
        panel.addView(heading);

        panel.addView(messageRow(parent, false));
        for (LobbyDataClient.LobbyMessage r : parent.replies) {
            panel.addView(messageRow(r, false));
        }

        ScrollView scroller = new ScrollView(this);
        scroller.addView(panel);
        new AlertDialog.Builder(this)
                .setView(scroller)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ------------------------------------------------------------------
    // Composer (existing POST contract)
    // ------------------------------------------------------------------

    private void attemptSend() {
        if (sending) return;
        String text = composerInput.getText().toString().trim();
        if (text.isEmpty()) return; // body-or-attachment rule: never post empty
        sending = true;
        sendBtn.setEnabled(false);
        sendBtn.setAlpha(0.45f);
        // v1.5.40: capture at use-time (field-init capture was a known
        // intermittent-failure source — v1.5.35 CreateMeetingSheet fix).
        QolabWebViewTransport t = QolabWebViewTransport.get();
        if (t == null || !t.isAlive()) {
            sending = false;
            sendBtn.setEnabled(true);
            sendBtn.setAlpha(1f);
            android.widget.Toast.makeText(this, R.string.lobby_send_failed,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("body", text);
            t.postJson("/chat/channels/" + channelId + "/messages",
                    payload.toString(), r -> runOnUiThread(() -> {
                        sending = false;
                        sendBtn.setEnabled(true);
                        sendBtn.setAlpha(1f);
                        Log.d(TAG, "send status=" + r.status);
                        if (r.status == 201 || r.status == 200) {
                            composerInput.setText("");
                            loadDiscussion(); // refetch: message + replies arrive together
                        } else if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD) {
                            // v1.5.40: verify before bouncing; keep text on transient.
                            SessionGate.requireValidSession(this, appOrigin(),
                                    () -> runOnUiThread(() -> {
                                        sendBtn.setEnabled(true);
                                        sendBtn.setAlpha(1f);
                                        sending = false;
                                    }),
                                    () -> runOnUiThread(() -> {
                                        startActivity(new Intent(LobbyActivity.this, MainActivity.class)
                                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                                        finish();
                                    }),
                                    () -> runOnUiThread(() -> {
                                        sendBtn.setEnabled(true);
                                        sendBtn.setAlpha(1f);
                                        sending = false;
                                        android.widget.Toast.makeText(this, R.string.lobby_send_failed,
                                                android.widget.Toast.LENGTH_SHORT).show();
                                    }));
                        } else if (r.status == 401) {
                            startActivity(new Intent(this, MainActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                            finish();
                        } else {
                            android.widget.Toast.makeText(this, R.string.lobby_send_failed,
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }));
        } catch (Exception e) {
            sending = false;
            sendBtn.setEnabled(true);
            sendBtn.setAlpha(1f);
            android.widget.Toast.makeText(this, R.string.lobby_send_failed,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------
    // Members (existing /office/rooms/{slug} props)
    // ------------------------------------------------------------------

    private void loadMembers() {
        membersSkeleton.setVisibility(View.VISIBLE);
        QolabWebViewTransport t = QolabWebViewTransport.get(); // use-time capture
        if (t == null || !t.isAlive()) {
            membersSkeleton.setVisibility(View.GONE);
            renderMembersError();
            return;
        }
        t.getJson("/office/rooms/" + roomSlug, r -> runOnUiThread(() -> {
            membersSkeleton.setVisibility(View.GONE);
            Log.d(TAG, "members page status=" + r.status);
            if (r.status != 200 || r.body == null) {
                renderMembersError();
                return;
            }
            RoomDataClient.RoomPage page = RoomDataClient.parseRoomPage(
                    RoomDataClient.extractInertiaPage(r.body));
            if (page == null || page.members == null) {
                renderMembersError();
                return;
            }
            members = page.members;
            membersLoaded = true;
            renderMembers();
        }));
    }

    private void renderMembersError() {
        membersList.removeAllViews();
        TextView err = new TextView(this);
        err.setText(R.string.home_error_section);
        err.setTextColor(getColor(R.color.qm_muted));
        err.setTextSize(13);
        membersList.addView(err);
    }

    private void renderMembers() {
        membersCount.setText(String.format(Locale.getDefault(),
                getString(R.string.lobby_members_count), members.size()));
        membersAvatars.removeAllViews();
        for (int i = 0; i < members.size() && i < 4; i++) {
            membersAvatars.addView(avatarView(members.get(i).name, i > 0));
        }
        membersList.removeAllViews();
        for (RoomDataClient.Member m : members) {
            membersList.addView(memberRow(m));
        }
    }

    private View memberRow(RoomDataClient.Member m) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView name = new TextView(this);
        name.setText(m.name != null ? m.name : "");
        name.setTextColor(getColor(R.color.qm_ink));
        name.setTextSize(14);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(name);
        // role only when title missing — NEVER "Member · null"
        String detail = joinNonNull(" · ", humanRole(m.role), m.jobTitle);
        if (detail != null) {
            TextView sub = new TextView(this);
            sub.setText(detail);
            sub.setTextColor(getColor(R.color.qm_muted));
            sub.setTextSize(12);
            row.addView(sub);
        }
        return row;
    }

    private String humanRole(String role) {
        if (role == null || role.trim().isEmpty()) return null;
        switch (role) {
            case "owner": return getString(R.string.lobby_member_owner);
            case "admin": return getString(R.string.lobby_member_admin);
            case "member": return getString(R.string.lobby_member_member);
            default: return null;
        }
    }

    private View avatarView(String name, boolean overlap) {
        TextView tv = new TextView(this);
        int size = dp(34);
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
