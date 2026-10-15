package dev.drytis.qolab;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;


/**
 * v1.5.49 — native Meetings list. Opened from the Home hamburger menu
 * (previously Coming Soon) and replaces the web page as the Meetings
 * destination.
 *
 * Data: GET /meetings (Inertia Meetings/Index) via the authenticated
 * WebView transport — the exact same page the web app serves; parsed
 * read-only, no backend contract change. Rows open the EXISTING
 * {@link MeetingDetailActivity} (id + title extras) which already owns
 * join/start/copy-link behavior.
 *
 * States: skeleton while first load, error + Retry (with
 * ConnectivityWatcher auto-recovery), empty states per section, content
 * preserved across transient refresh failures.
 */
public class MeetingsListActivity extends AppCompatActivity {

    public static final String EXTRA_AUTOFETCH = "dev.drytis.qolab.AUTOFETCH";

    private LinearLayout meetingsList;
    private View meetingsSkeleton, meetingsErrorBox, meetingsEmpty, meetingsScroll, meetingsRetry, meetingsHeader;
    private QolabWebViewTransport transport;

    private MeetingsDataClient.MeetingsPage page;
    private boolean fetching;
    private Object netWatcher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_meetings_list);
        transport = QolabWebViewTransport.get();
        meetingsList = findViewById(R.id.meetings_list);
        meetingsSkeleton = findViewById(R.id.meetings_skeleton);
        meetingsErrorBox = findViewById(R.id.meetings_error_box);
        meetingsEmpty = findViewById(R.id.meetings_empty);
        meetingsScroll = findViewById(R.id.meetings_scroll);
        meetingsRetry = findViewById(R.id.meetings_retry);
        meetingsHeader = findViewById(R.id.meetings_header);

        applyInsets();

        findViewById(R.id.meetings_btn_back).setOnClickListener(v -> finish());
        meetingsRetry.setOnClickListener(v -> load());

        // v1.5.46 pattern: bounded edge-triggered network auto-retry —
        // only when there is no valid page yet and no request running.
        netWatcher = ConnectivityWatcher.register(this, () -> runOnUiThread(() -> {
            if (page == null && !fetching) {
                showLoading();
                load();
            }
        }));

        load();
    }

    @Override
    protected void onDestroy() {
        ConnectivityWatcher.unregister(this, netWatcher);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh on return from a meeting (status may have changed) —
        // only a light re-fetch, never a forced skeleton over content.
        if (page != null && !fetching) load();
    }

    private String origin() {
        return BuildConfig.QOLAB_ORIGIN != null ? BuildConfig.QOLAB_ORIGIN
                : "https://workpulse-gbpedt.drytis.dev";
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(android.R.id.content), (v, insets) -> {
                    int navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                    int status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                    View header = findViewById(R.id.meetings_header);
                    header.setPadding(header.getPaddingStart(),
                            status + 12, header.getPaddingEnd(), header.getPaddingBottom());
                    meetingsList.setPadding(meetingsList.getPaddingStart(),
                            meetingsList.getPaddingTop(),
                            meetingsList.getPaddingEnd(), navBar + 24);
                    return WindowInsetsCompat.CONSUMED;
                });
    }

    // ── data ──────────────────────────────────────────────────────────

    private void load() {
        if (fetching) return;
        if (transport == null || !transport.isAlive()) {
            // Verify with the server before logging out — a dead
            // transport is not proof of expiry (v1.5.40 SessionGate).
            SessionGate.requireValidSession(this, origin(),
                    () -> runOnUiThread(this::load),
                    this::routeToLogin,
                    () -> runOnUiThread(this::showError));
            return;
        }
        fetching = true;
        if (page == null) showLoading();
        transport.getJson("/meetings", r -> runOnUiThread(() -> {
            fetching = false;
            if (isFinishing() || isDestroyed()) return;
            if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD) {
                SessionGate.requireValidSession(this, origin(),
                        () -> runOnUiThread(this::load),
                        this::routeToLogin,
                        () -> runOnUiThread(this::showError));
                return;
            }
            if (r.status == 401) {
                routeToLogin(); // server-proven expiry
                return;
            }
            if (r.status < 200 || r.status >= 300 || r.body == null) {
                showError();
                return;
            }
            JSONObject inertia = RoomDataClient.extractInertiaPage(r.body);
            MeetingsDataClient.MeetingsPage parsed = MeetingsDataClient.parse(inertia);
            if (parsed == null) {
                showError();
                return;
            }
            page = parsed;
            render();
        }));
    }

    private void routeToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    // ── states ────────────────────────────────────────────────────────

    private void showLoading() {
        meetingsSkeleton.setVisibility(View.VISIBLE);
        meetingsErrorBox.setVisibility(View.GONE);
        meetingsEmpty.setVisibility(View.GONE);
        meetingsScroll.setVisibility(View.GONE);
    }

    private void showError() {
        if (page != null) {
            // v1.5.44 rule: a failed refresh never tears down valid
            // content — non-blocking only.
            return;
        }
        meetingsSkeleton.setVisibility(View.GONE);
        meetingsScroll.setVisibility(View.GONE);
        meetingsEmpty.setVisibility(View.GONE);
        meetingsErrorBox.setVisibility(View.VISIBLE);
    }

    private void render() {
        meetingsSkeleton.setVisibility(View.GONE);
        meetingsErrorBox.setVisibility(View.GONE);
        meetingsScroll.setVisibility(View.VISIBLE);

        meetingsList.removeAllViews();

        if (page.upcoming.isEmpty() && page.past.isEmpty()) {
            meetingsEmpty.setVisibility(View.VISIBLE);
            return;
        }
        meetingsEmpty.setVisibility(View.GONE);

        // Upcoming first (reverse-chronological list from the backend),
        // then Past.
        if (!page.upcoming.isEmpty()) {
            meetingsList.addView(sectionLabel(getString(R.string.meetings_section_upcoming)));
            for (MeetingsDataClient.MeetingRow m : page.upcoming) {
                meetingsList.addView(meetingCard(m));
            }
        }
        if (!page.past.isEmpty()) {
            meetingsList.addView(sectionLabel(getString(R.string.meetings_section_past)));
            for (MeetingsDataClient.MeetingRow m : page.past) {
                meetingsList.addView(meetingCard(m));
            }
        }
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getColor(R.color.qm_muted));
        tv.setTextSize(13f);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(14), 0, dp(8));
        return tv;
    }

    /** Proven room meeting-row visual; opens MeetingDetailActivity. */
    private View meetingCard(MeetingsDataClient.MeetingRow m) {
        View card = getLayoutInflater().inflate(R.layout.view_room_meeting_row, null, false);
        TextView title = card.findViewById(R.id.meeting_row_title);
        TextView when = card.findViewById(R.id.meeting_row_when);
        TextView status = card.findViewById(R.id.meeting_row_status);
        TextView duration = card.findViewById(R.id.meeting_row_duration);
        TextView meta = card.findViewById(R.id.meeting_row_meta);

        title.setText(m.title != null ? m.title : "");
        when.setText(m.scheduledAtDisplay != null
                ? m.scheduledAtDisplay
                + (m.timezoneAbbr != null && !m.timezoneAbbr.isEmpty()
                        ? " " + m.timezoneAbbr : "")
                : "");

        int chipBg, chipText, label;
        switch (m.status == null ? "" : m.status) {
            case "live":
                chipBg = R.drawable.qm_chip_live; chipText = R.color.qm_wine;
                label = R.string.room_meeting_live; break;
            case "cancelled":
                chipBg = R.drawable.qm_chip_cancelled; chipText = R.color.qm_error;
                label = R.string.room_meeting_cancelled; break;
            case "ended":
                chipBg = R.drawable.qm_chip_ended; chipText = R.color.qm_muted;
                label = R.string.room_meeting_ended; break;
            default:
                chipBg = R.drawable.qm_chip_scheduled; chipText = R.color.qm_mint_text;
                label = R.string.room_meeting_scheduled; break;
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
        } else if (m.participantsCount > 0) {
            meta.setVisibility(View.VISIBLE);
            meta.setText(String.format(Locale.getDefault(),
                    getString(R.string.meetings_participants_n), m.participantsCount));
        }

        if (MeetingsDataClient.openable(m)) {
            card.setOnClickListener(v -> {
                Intent i = new Intent(this, MeetingDetailActivity.class);
                i.putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, m.id);
                i.putExtra(MeetingDetailActivity.EXTRA_MEETING_TITLE,
                        m.title != null ? m.title : "");
                startActivity(i);
            });
        }
        return card;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
