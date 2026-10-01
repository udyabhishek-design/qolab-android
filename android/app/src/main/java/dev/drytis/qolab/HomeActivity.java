package dev.drytis.qolab;

import android.annotation.SuppressLint;
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

    private HomeDataClient client;

    // header / greeting
    private TextView greetingDate, greetingName;
    private View notificationBadge;

    // meeting
    private View meetingCard, meetingSkeleton, meetingEmpty, meetingError;
    private TextView meetingTime, meetingTitle, meetingOverflow;
    private LinearLayout meetingAvatars;

    // rooms
    private LinearLayout roomsRow;
    private View roomsEmpty;
    private TextView roomsSeeAll;

    // department
    private View deptCard, deptSkeleton, deptEmpty, deptError;
    private TextView deptName, deptOnline, deptOverflow, deptEnter;
    private LinearLayout deptAvatars;

    // continue working
    private View convCard, convSkeleton, taskCard, taskSkeleton, bothEmpty;
    private TextView convName, convMeta, taskTitle, taskDue;

    // today
    private TextView todayTasks, todayMeetings, todayUpdates;

    // bottom nav
    private LinearLayout navHome, navOffice, navChat;
    private FrameLayout navAskDrytis;

    private HomeDataClient.HomeData data;
    private volatile boolean fetching = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        client = new HomeDataClient(BuildConfig.QOLAB_ORIGIN.replaceAll("/+$", ""));
        bindViews();
        applyInsets();
        bindActions();

        renderGreeting();

        if (data == null) {
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
        }
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
        roomsEmpty = findViewById(R.id.home_rooms_empty);
        roomsSeeAll = findViewById(R.id.home_rooms_see_all);

        deptCard = findViewById(R.id.home_dept_card);
        deptSkeleton = findViewById(R.id.home_dept_skeleton);
        deptEmpty = findViewById(R.id.home_dept_empty);
        deptError = findViewById(R.id.home_dept_error);
        deptName = findViewById(R.id.home_dept_name);
        deptOnline = findViewById(R.id.home_dept_online);
        deptOverflow = findViewById(R.id.home_dept_overflow);
        deptEnter = findViewById(R.id.home_dept_enter);
        deptAvatars = findViewById(R.id.home_dept_avatars);
        TextView deptRetry = findViewById(R.id.home_dept_retry);
        if (deptRetry != null) deptRetry.setOnClickListener(v -> loadHome());

        convCard = findViewById(R.id.home_continue_conversation);
        convSkeleton = findViewById(R.id.home_continue_conversation_skeleton);
        taskCard = findViewById(R.id.home_continue_task);
        taskSkeleton = findViewById(R.id.home_continue_task_skeleton);
        bothEmpty = findViewById(R.id.home_continue_both_empty);
        convName = findViewById(R.id.home_continue_conversation_name);
        convMeta = findViewById(R.id.home_continue_conversation_meta);
        taskTitle = findViewById(R.id.home_continue_task_title);
        taskDue = findViewById(R.id.home_continue_task_due);

        todayTasks = findViewById(R.id.home_today_tasks);
        todayMeetings = findViewById(R.id.home_today_meetings);
        todayUpdates = findViewById(R.id.home_today_updates);

        navHome = findViewById(R.id.home_nav_home);
        navOffice = findViewById(R.id.home_nav_office);
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
        notifications.setOnClickListener(v -> openShell("/notifications"));

        ImageButton search = findViewById(R.id.home_btn_search);
        search.setOnClickListener(v ->
                Toast.makeText(this, R.string.home_coming_soon, Toast.LENGTH_SHORT).show());

        roomsSeeAll.setOnClickListener(v -> openShell("/rooms"));

        deptEnter.setOnClickListener(v -> {
            if (data != null && data.departmentRoom != null) {
                openRoom(data.departmentRoom.roomSlug);
            }
        });

        navHome.setOnClickListener(v -> smoothScrollToTop());
        navOffice.setOnClickListener(v -> openShell("/rooms"));
        navChat.setOnClickListener(v -> openShell("/chat"));
        navAskDrytis.setOnClickListener(v ->
                Toast.makeText(this, R.string.home_coming_soon, Toast.LENGTH_SHORT).show());
    }

    private void openShell(String path) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("qolab_start_path", path);
        startActivity(i);
    }

    private void openRoom(String roomSlug) {
        if (roomSlug == null || roomSlug.isEmpty()) return;
        openShell("/office/rooms/" + roomSlug);
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

        String[][] destinations = {
                {getString(R.string.home_menu_meetings), "/meetings"},
                {getString(R.string.home_menu_tasks), "/tasks"},
                {getString(R.string.home_menu_tickets), "/tickets"},
                {getString(R.string.home_menu_directory), "/directory"},
                {getString(R.string.home_menu_settings), "/settings"},
                {getString(R.string.home_menu_profile), "/profile"},
        };
        for (String[] dest : destinations) {
            TextView item = new TextView(this);
            item.setText(dest[0]);
            item.setTextColor(0xFF1A1A1A);
            item.setTextSize(16);
            int itemPad = pad / 2;
            item.setPadding(0, itemPad, 0, itemPad);
            item.setOnClickListener(v -> {
                sheet.dismiss();
                openShell(dest[1]);
            });
            box.addView(item);
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
        showLoading();
        final String cookieHeader = sessionCookieHeader();
        new Thread(() -> {
            HomeDataClient.Result result = client.fetchHome(cookieHeader);
            int unread = client.fetchUnreadCount(cookieHeader);
            runOnUiThread(() -> {
                fetching = false;
                if (result.authLost) {
                    routeToLogin();
                    return;
                }
                if (result.data == null) {
                    showError();
                    return;
                }
                data = result.data;
                renderAll(unread);
            });
        }, "home-fetch").start();
    }

    private String sessionCookieHeader() {
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            String cookies = cm.getCookie(BuildConfig.QOLAB_ORIGIN.replaceAll("/+$", ""));
            return cookies;
        } catch (Exception e) {
            return null;
        }
    }

    private void routeToLogin() {
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
        show(convSkeleton, taskSkeleton); hide(convCard, taskCard, bothEmpty);
        // rooms row keeps its skeleton children until data arrives
    }

    private void showError() {
        hide(meetingSkeleton); show(meetingError);
        hide(deptSkeleton); show(deptError);
        hide(convSkeleton, taskSkeleton); hide(convCard, taskCard); show(bothEmpty);
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
        roomsRow.removeAllViews();
        if (rooms == null || rooms.isEmpty()) {
            show(roomsEmpty);
            return;
        }
        hide(roomsEmpty);
        for (HomeDataClient.Room room : rooms) {
            roomsRow.addView(inflateRoomCard(room));
        }
    }

    private View inflateRoomCard(HomeDataClient.Room room) {
        View card = getLayoutInflater().inflate(R.layout.view_room_card, roomsRow, false);
        TextView online = card.findViewById(R.id.room_card_online);
        TextView name = card.findViewById(R.id.room_card_name);
        TextView description = card.findViewById(R.id.room_card_description);
        TextView overflow = card.findViewById(R.id.room_card_overflow);
        TextView enter = card.findViewById(R.id.room_card_enter);
        LinearLayout avatars = card.findViewById(R.id.room_card_avatars);

        // Approved popping palette by room theme — colors ARE the identity.
        int bg; int title; int text; int enterBg; int enterText;
        String theme = room.roomTheme != null ? room.roomTheme : "";
        String slug = room.roomSlug != null ? room.roomSlug : "";
        if (theme.contains("music") || slug.contains("music")) {
            bg = 0xFF6B21A8; title = 0xFFF4FF6B; text = 0xFFFFFFFF;
            enterBg = 0xFFF4FF6B; enterText = 0xFF6B21A8;
        } else if (theme.contains("coffee") || slug.contains("coffee")) {
            bg = 0xFF14532D; title = 0xFFF4FF6B; text = 0xFFF4FF6B;
            enterBg = 0xFFF4FF6B; enterText = 0xFF14532D;
        } else { // lobby and everything else: cobalt
            bg = 0xFF2148C0; title = 0xFFFFFFFF; text = 0xFFF5F0E4;
            enterBg = 0xFFF5F0E4; enterText = 0xFF2148C0;
        }
        card.setBackgroundColor(bg);
        name.setTextColor(title);
        online.setTextColor(text);
        description.setTextColor(text);
        overflow.setTextColor(text);
        enter.setBackgroundColor(enterBg);
        enter.setTextColor(enterText);

        online.setText(getString(R.string.home_room_online, room.membersCount));
        name.setText(room.name);
        description.setText(room.description != null ? room.description
                : getString(R.string.home_room_default_description));
        avatars.removeAllViews();
        renderAvatarRow(avatars, room.participants, room.membersCount, false);
        overflow.setText(overflowText(room.participants.size(), room.membersCount));
        enter.setOnClickListener(v -> openRoom(room.roomSlug));
        return card;
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
        deptAvatars.removeAllViews();
        renderAvatarRow(deptAvatars, dept.participants, dept.membersCount, false);
        deptOverflow.setText(overflowText(dept.participants.size(), dept.membersCount));
    }

    private void renderContinue() {
        hide(convSkeleton, taskSkeleton);
        HomeDataClient.Conversation conv = data.recentConversation;
        HomeDataClient.Task task = data.priorityTask;

        if (conv != null) {
            show(convCard);
            convName.setText(conv.type != null && conv.type.equals("dm") && conv.dmPartnerName != null
                    ? conv.dmPartnerName : conv.name);
            String meta = relativeTime(conv.lastMessageAt);
            if (conv.unreadCount > 0) {
                meta = getString(R.string.home_continue_unread, conv.unreadCount) + " · " + meta;
            }
            convMeta.setText(meta);
            convCard.setOnClickListener(v -> openShell("/chat"));
        } else {
            hide(convCard);
        }

        if (task != null) {
            show(taskCard);
            taskTitle.setText(task.title);
            taskDue.setText(dueLabel(task.dueDate));
            taskCard.setOnClickListener(v -> openShell("/tasks"));
        } else {
            hide(taskCard);
        }

        if (conv == null && task == null) {
            show(bothEmpty);
        } else {
            hide(bothEmpty);
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

    private static int avatarColor(String name) {
        int[] palette = {0xFF2148C0, 0xFF8B1E2D, 0xFF14532D, 0xFF6B21A8, 0xFFB45309, 0xFF0E7490};
        if (name == null || name.isEmpty()) return palette[0];
        return palette[Math.abs(name.hashCode()) % palette.length];
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
