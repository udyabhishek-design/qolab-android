package dev.drytis.qolab;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Collections;
import java.util.List;

/**
 * Native Office screen (V1).
 *
 * Reuses the approved Home visual system and the SAME authenticated
 * QolabWebViewTransport — one GET /mobile/home payload feeds Social Rooms
 * and the user's Department Room. People Around renders an honest
 * unavailable state: live presence counts are resolved server-side in
 * Redis (PresenceService) and are not exposed to the app by any existing
 * endpoint, and no counts are invented.
 *
 * Home v1.5.22, Login, transport and the parse layer are untouched.
 */
public class OfficeActivity extends AppCompatActivity {

    private static final String TAG = "OfficeActivity";
    private static final int AVATAR_MAX = 4;

    private TextView notificationBadge;
    private TextView avatar;
    private LinearLayout roomsRow;
    private LinearLayout roomsSkeletonRow;
    private TextView roomsEmpty;
    private TextView roomsRetry;
    private View deptCard, deptEmpty, deptSkeleton;
    private TextView deptRetry, deptName, deptOnline, deptDescription, deptOverflow, deptEnter;
    private LinearLayout deptAvatars;

    private HomeDataClient.HomeData data;
    private volatile boolean fetching;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_office);
        bindViews();
        applyInsets();
        bindActions();
        Log.d(TAG, "onCreate build=" + BuildConfig.VERSION_NAME + "/" + BuildConfig.VERSION_CODE);
        if (data == null) {
            showLoading();
            loadOffice();
        }
    }

    private void bindViews() {
        notificationBadge = findViewById(R.id.office_notification_badge);
        avatar = findViewById(R.id.office_avatar);
        roomsRow = findViewById(R.id.office_rooms_row);
        roomsSkeletonRow = findViewById(R.id.office_rooms_skeleton_row);
        roomsEmpty = findViewById(R.id.office_rooms_empty);
        roomsRetry = findViewById(R.id.office_rooms_retry);
        deptCard = findViewById(R.id.office_dept_card);
        deptEmpty = findViewById(R.id.office_dept_empty);
        deptSkeleton = findViewById(R.id.office_dept_skeleton);
        deptRetry = findViewById(R.id.office_dept_retry);
        deptName = findViewById(R.id.office_dept_name);
        deptOnline = findViewById(R.id.office_dept_online);
        deptDescription = findViewById(R.id.office_dept_description);
        deptOverflow = findViewById(R.id.office_dept_overflow);
        deptEnter = findViewById(R.id.office_dept_enter);
        deptAvatars = findViewById(R.id.office_dept_avatars);
    }

    private void applyInsets() {
        View root = findViewById(R.id.office_scroll);
        View nav = findViewById(R.id.office_bottom_nav);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            int navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            int status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            nav.setPadding(nav.getPaddingStart(), 0, nav.getPaddingEnd(), navBar);
            ScrollView scroll = (ScrollView) root;
            scroll.setPadding(0, status, 0, 0);
            int extra = nav.getContext().getResources()
                    .getDimensionPixelSize(R.dimen.qm_home_bottom_nav_height) + navBar;
            LinearLayout content = scroll.getChildAt(0) instanceof LinearLayout
                    ? (LinearLayout) scroll.getChildAt(0) : null;
            if (content != null) {
                content.setPadding(content.getPaddingStart(), content.getPaddingTop(),
                        content.getPaddingEnd(), extra);
            }
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void bindActions() {
        ImageButton bell = findViewById(R.id.office_btn_notifications);
        if (bell != null) bell.setOnClickListener(v ->
                showComingSoon(getString(R.string.home_menu_notifications)));
        TextView createRoom = findViewById(R.id.office_create_room);
        if (createRoom != null) createRoom.setOnClickListener(v ->
                showComingSoon(getString(R.string.office_create_room)));
        TextView roomsSeeAll = findViewById(R.id.office_rooms_see_all);
        if (roomsSeeAll != null) roomsSeeAll.setOnClickListener(v ->
                showComingSoon(getString(R.string.office_social_rooms)));
        TextView deptSeeAll = findViewById(R.id.office_dept_see_all);
        if (deptSeeAll != null) deptSeeAll.setOnClickListener(v ->
                showComingSoon(getString(R.string.office_department_rooms)));
        TextView peopleSeeAll = findViewById(R.id.office_people_see_all);
        if (peopleSeeAll != null) peopleSeeAll.setOnClickListener(v ->
                showComingSoon(getString(R.string.office_people_around)));

        // Quick Access — native Coming Soon only, never web
        int[] quickIds = {R.id.office_quick_meetings, R.id.office_quick_tasks,
                R.id.office_quick_tickets, R.id.office_quick_directory};
        String[] names = {getString(R.string.home_menu_meetings), getString(R.string.home_menu_tasks),
                getString(R.string.home_menu_tickets), getString(R.string.home_menu_directory)};
        for (int i = 0; i < quickIds.length; i++) {
            View q = findViewById(quickIds[i]);
            if (q != null) {
                final String name = names[i];
                q.setOnClickListener(v -> showComingSoon(name));
            }
        }

        // Bottom navigation
        LinearLayout navHome = findViewById(R.id.office_nav_home);
        LinearLayout navOffice = findViewById(R.id.office_nav_office);
        LinearLayout navChat = findViewById(R.id.office_nav_chat);
        View navAsk = findViewById(R.id.office_nav_ask_drytis);
        if (navHome != null) navHome.setOnClickListener(v -> {
            // Return to the APPROVED native Home — finish() pops back to it.
            finish();
        });
        if (navOffice != null) navOffice.setOnClickListener(v ->
                Toast.makeText(this, R.string.office_title, Toast.LENGTH_SHORT).show());
        if (navChat != null) navChat.setOnClickListener(v ->
                showComingSoon(getString(R.string.home_nav_chat)));
        if (navAsk != null) navAsk.setOnClickListener(v ->
                showComingSoon(getString(R.string.home_nav_ask_drytis)));

        if (roomsRetry != null) roomsRetry.setOnClickListener(v -> loadOffice());
        if (deptRetry != null) deptRetry.setOnClickListener(v -> loadOffice());
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private void loadOffice() {
        if (fetching) return;
        fetching = true;
        QolabWebViewTransport transport = QolabWebViewTransport.get();
        if (transport == null) {
            fetching = false;
            routeToLogin();
            return;
        }
        transport.getJson("/mobile/home", r -> runOnUiThread(() -> {
            fetching = false;
            Log.d(TAG, "transport result status=" + r.status
                    + " bodyLen=" + (r.body == null ? "null" : r.body.length())
                    + " errCat=" + r.errorCategory);
            if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD || r.status == 401) {
                Log.d(TAG, "routing to login (transport dead / 401)");
                routeToLogin();
                return;
            }
            if (r.status == QolabWebViewTransport.Result.STATUS_IO || r.body == null) {
                Log.d(TAG, "showError path: IO or null body");
                showError();
                return;
            }
            HomeDataClient.HomeData parsed = HomeDataClient.parse(r.body);
            if (parsed == null) {
                Log.d(TAG, "showError path: parse returned null");
                showError();
                return;
            }
            Log.d(TAG, "parse ok: rooms=" + (parsed.socialRooms == null ? -1 : parsed.socialRooms.size())
                    + " dept=" + (parsed.departmentRoom == null ? "null" : parsed.departmentRoom.name));
            data = parsed;
            renderAll();
        }));
    }

    private void loadUnread() {
        QolabWebViewTransport transport = QolabWebViewTransport.get();
        if (transport == null) return;
        transport.getJson("/notifications/unread-count", u -> runOnUiThread(() -> {
            if (notificationBadge == null) return;
            int unread = -1;
            try {
                org.json.JSONObject o = new org.json.JSONObject(u.body == null ? "{}" : u.body);
                unread = o.optInt("unread_count", 0);
            } catch (Exception ignored) {
            }
            notificationBadge.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
        }));
    }

    private void routeToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    // ------------------------------------------------------------------
    // States
    // ------------------------------------------------------------------

    private void showLoading() {
        show(roomsSkeletonRow, deptSkeleton);
        hide(roomsRow, roomsEmpty, roomsRetry, deptCard, deptEmpty, deptRetry);
    }

    private void showError() {
        hide(roomsSkeletonRow, deptSkeleton, roomsRow, deptCard, deptEmpty);
        show(roomsEmpty, roomsRetry, deptRetry);
        roomsEmpty.setText(R.string.home_error_section);
    }

    private void renderAll() {
        hide(roomsSkeletonRow, deptSkeleton, roomsRetry, deptRetry);
        // v1.5.26: showLoading() hides the data containers — they MUST be
        // made visible again here or the rendered cards stay GONE
        // (root cause of "Social Rooms heading with no room cards").
        show(roomsRow, deptCard);

        // header
        String name = data.userName;
        avatar.setText(HomeActivity.initialsOf(name));
        android.graphics.drawable.GradientDrawable circle =
                new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(HomeActivity.avatarColorOf(name));
        avatar.setBackground(circle);

        renderRooms();
        renderDepartment();
        loadUnread();
    }

    private void renderRooms() {
        List<HomeDataClient.Room> rooms = data.socialRooms;
        hide(roomsSkeletonRow);
        roomsRow.removeAllViews();
        if (rooms == null || rooms.isEmpty()) {
            hide(roomsRow);
            show(roomsEmpty);
            roomsEmpty.setText(R.string.home_rooms_empty);
            Log.d(TAG, "renderRooms: EMPTY state (rooms="
                    + (rooms == null ? "null" : rooms.size()) + ")");
            return;
        }
        hide(roomsEmpty);
        show(roomsRow);
        roomsEmpty.setText(R.string.home_rooms_empty);
        Collections.sort(rooms, (a, b) ->
                Integer.compare(RoomThemeStyle.preferredOrder(a.roomTheme, a.roomSlug),
                        RoomThemeStyle.preferredOrder(b.roomTheme, b.roomSlug)));
        int count = rooms.size();
        int gap = getResources().getDimensionPixelSize(R.dimen.qm_home_room_gap);
        for (int i = 0; i < count; i++) {
            View card = inflateRoomCard(rooms.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0,
                    getResources().getDimensionPixelSize(R.dimen.qm_home_room_card_height),
                    1f);
            if (i < count - 1) lp.setMarginEnd(gap);
            card.setLayoutParams(lp);
            roomsRow.addView(card);
        }
        Log.d(TAG, "renderRooms: rendered " + roomsRow.getChildCount() + " cards"
                + (roomsRow.getVisibility() == View.VISIBLE ? ", row VISIBLE" : ", row INVISIBLE/GONE"));
    }

    private View inflateRoomCard(HomeDataClient.Room room) {
        View card = getLayoutInflater().inflate(R.layout.view_room_card, roomsRow, false);
        TextView online = card.findViewById(R.id.room_card_online);
        TextView name = card.findViewById(R.id.room_card_name);
        TextView description = card.findViewById(R.id.room_card_description);
        TextView overflow = card.findViewById(R.id.room_card_overflow);
        LinearLayout avatars = card.findViewById(R.id.room_card_avatars);
        android.widget.FrameLayout arrowCircle = card.findViewById(R.id.room_card_arrow_circle);
        ImageView arrow = card.findViewById(R.id.room_card_arrow);

        RoomThemeStyle.Style style = RoomThemeStyle.styleFor(room.roomTheme, room.roomSlug);
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
            android.graphics.drawable.GradientDrawable circle =
                    new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(style.enterBg);
            arrowCircle.setBackground(circle);
            arrowCircle.setOnClickListener(v -> openRoom(room));
            arrowCircle.setContentDescription(getString(R.string.home_room_cd_enter));
        }
        if (arrow != null) {
            arrow.setImageTintList(android.content.res.ColorStateList.valueOf(style.enterText));
        }
        // v1.5.30: Lobby (theme lobby) opens natively; other social rooms
        // keep their Coming Soon treatment — built one at a time.
        if ("lobby".equals(room.roomTheme)) {
            card.setOnClickListener(v -> openRoom(room));
        }

        online.setText(getString(R.string.home_room_online, room.membersCount));
        name.setText(room.name);
        description.setText(room.description != null ? room.description
                : defaultRoomDescription(style.descriptionKey));
        avatars.removeAllViews();
        renderAvatarRow(avatars, room.participants, room.membersCount);
        overflow.setText(overflowText(room.participants.size(), room.membersCount));
        return card;
    }

    private void renderDepartment() {
        HomeDataClient.Room dept = data.departmentRoom;
        if (dept == null) {
            hide(deptCard, deptSkeleton);
            show(deptEmpty);
            return;
        }
        hide(deptEmpty, deptSkeleton);
        show(deptCard);
        deptName.setText(dept.name);
        deptOnline.setText(getString(R.string.home_room_online, dept.membersCount));
        deptDescription.setText(dept.description != null ? dept.description
                : getString(R.string.home_dept_no_description));
        deptAvatars.removeAllViews();
        renderAvatarRow(deptAvatars, dept.participants, dept.membersCount);
        deptOverflow.setText(overflowText(dept.participants.size(), dept.membersCount));
        deptEnter.setOnClickListener(v -> openDepartmentRoom(dept));
        deptCard.setOnClickListener(v -> openDepartmentRoom(dept));
    }

    /**
     * v1.5.28: native Engineering Room (department room) screen.
     * Uses the room identity from the /mobile/home payload.
     */
    private void openDepartmentRoom(HomeDataClient.Room dept) {
        String slug = dept.roomSlug;
        if (slug == null || slug.isEmpty()) {
            showComingSoon(dept.name != null ? dept.name : getString(R.string.home_dept_empty_title));
            return;
        }
        Intent i = new Intent(this, RoomActivity.class);
        i.putExtra(RoomActivity.EXTRA_ROOM_NAME, dept.name);
        i.putExtra(RoomActivity.EXTRA_ROOM_SLUG, slug);
        startActivity(i);
    }

    /**
     * v1.5.30: The Lobby opens natively using the room identity (channel id
     * + slug) from the /mobile/home payload. Other social rooms remain
     * Coming Soon until they get their own native build.
     */
    private void openRoom(HomeDataClient.Room room) {
        if ("lobby".equals(room.roomTheme) && room.id > 0
                && room.roomSlug != null && !room.roomSlug.isEmpty()) {
            Intent i = new Intent(this, LobbyActivity.class);
            i.putExtra(LobbyActivity.EXTRA_ROOM_ID, room.id);
            i.putExtra(LobbyActivity.EXTRA_ROOM_SLUG, room.roomSlug);
            i.putExtra(LobbyActivity.EXTRA_ROOM_NAME, room.name);
            startActivity(i);
            return;
        }
        showComingSoon(room.name != null ? room.name : getString(R.string.home_room_cd_enter));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void renderAvatarRow(LinearLayout container, List<HomeDataClient.Participant> participants,
                                 int totalCount) {
        int shown = Math.min(AVATAR_MAX, participants == null ? 0 : participants.size());
        for (int i = 0; i < shown; i++) {
            HomeDataClient.Participant p = participants.get(i);
            TextView a = (TextView) getLayoutInflater().inflate(R.layout.view_avatar, container, false);
            a.setText(HomeActivity.initialsOf(p.name));
            android.graphics.drawable.GradientDrawable circle =
                    new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(HomeActivity.avatarColorOf(p.name));
            circle.setStroke(Math.round(2 * getResources().getDisplayMetrics().density), 0xFFFFFFFF);
            a.setBackground(circle);
            if (i > 0) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) a.getLayoutParams();
                lp.setMargins(-Math.round(10 * getResources().getDisplayMetrics().density), 0, 0, 0);
                a.setLayoutParams(lp);
            }
            container.addView(a);
        }
    }

    private String overflowText(int shown, int total) {
        int extra = total - shown;
        if (extra <= 0) return "";
        return getString(R.string.home_overflow_more, extra);
    }

    private String defaultRoomDescription(String key) {
        int id = getResources().getIdentifier(key, "string", getPackageName());
        return id != 0 ? getString(id) : getString(R.string.home_room_default_description);
    }

    private void showComingSoon(String what) {
        Toast.makeText(this, getString(R.string.home_coming_soon_msg, what),
                Toast.LENGTH_SHORT).show();
    }

    private void show(View... views) {
        for (View v : views) if (v != null) v.setVisibility(View.VISIBLE);
    }

    private void hide(View... views) {
        for (View v : views) if (v != null) v.setVisibility(View.GONE);
    }
}
