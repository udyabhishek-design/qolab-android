package dev.drytis.qolab;

import android.content.Intent;
import java.util.List;
import java.util.Locale;
import java.util.List;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;

import androidx.appcompat.app.AppCompatActivity;



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

    // members
    private LinearLayout membersAvatars, membersList;
    private TextView membersCount;
    private View membersSkeleton;

    private List<RoomDataClient.Member> members;
    private boolean membersLoaded;

    private final QolabWebViewTransport transport = QolabWebViewTransport.get();

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
        // v1.5.35: discussion feed/composer/thread now come from the SHARED
        // ChannelDiscussionView (same component as the Department Room Chat
        // tab) — one messaging implementation over the channel contract.
        discussion = new ChannelDiscussionView(this, channelId,
                findViewById(R.id.lobby_discussion_pane), () -> {
                    startActivity(new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    finish();
                });
        discussion.load();
    }

    /** v1.5.35 shared discussion surface (feed + composer + threads). */
    private ChannelDiscussionView discussion;

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
    // Members (existing /office/rooms/{slug} props)
    // ------------------------------------------------------------------

    private void loadMembers() {
        membersSkeleton.setVisibility(View.VISIBLE);
        transport.getJson("/office/rooms/" + roomSlug, r -> runOnUiThread(() -> {
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
