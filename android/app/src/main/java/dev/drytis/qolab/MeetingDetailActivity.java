package dev.drytis.qolab;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;
import java.util.Locale;

import dev.drytis.qolab.RoomDataClient.MeetingDetail;
import dev.drytis.qolab.RoomDataClient.MeetingTask;
import dev.drytis.qolab.RoomDataClient.Segment;
import dev.drytis.qolab.RoomDataClient.TranscriptSession;

/**
 * Native Meeting Details screen: Summary | Transcript.
 *
 * Data source: GET /meetings/{id}/detail — an existing backend endpoint.
 * Transcript shows the current session segments plus every historical
 * session (multi-session transcripts) when present. No fabricated data.
 */
public class MeetingDetailActivity extends AppCompatActivity {

    private static final String TAG = "MeetingDetail";
    public static final String EXTRA_MEETING_ID = "meeting_id";
    public static final String EXTRA_MEETING_TITLE = "meeting_title";

    private TextView titleView, tabSummary, tabTranscript;
    private View summaryRoot, transcriptRoot, errorText, retryText;
    private View summarySkeleton, transcriptSkeleton, transcriptNone;

    // summary views
    private View facts, whenView, metaView, descriptionView, titleFull;
    private View summaryLabel, summaryText, summaryNone;
    private View decisionsLabel, decisionsList;
    private View followUpsLabel, followUpsList;
    private View actionsLabel, actionsCount, actionsList, actionsNone;

    // transcript views
    private LinearLayout transcriptCurrent;
    private View sessionsLabel, sessionsList;

    private long meetingId;
    private MeetingDetail detail;
    private volatile boolean fetching;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_meeting_detail);

        meetingId = getIntent().getLongExtra(EXTRA_MEETING_ID, 0);
        String initialTitle = getIntent().getStringExtra(EXTRA_MEETING_TITLE);

        bindViews();
        bindActions();
        titleView.setText(initialTitle != null ? initialTitle : "");
        showTab(0);
        loadDetail();
    }

    private void bindViews() {
        titleView = findViewById(R.id.meeting_detail_title);
        titleFull = findViewById(R.id.meeting_summary_title_full);
        tabSummary = findViewById(R.id.meeting_detail_tab_summary);
        tabTranscript = findViewById(R.id.meeting_detail_tab_transcript);
        summaryRoot = findViewById(R.id.meeting_summary_root);
        transcriptRoot = findViewById(R.id.meeting_transcript_root);
        errorText = findViewById(R.id.meeting_detail_error);
        retryText = findViewById(R.id.meeting_detail_retry);

        summarySkeleton = findViewById(R.id.meeting_summary_skeleton);
        transcriptSkeleton = findViewById(R.id.meeting_transcript_skeleton);
        transcriptNone = findViewById(R.id.meeting_transcript_none);

        facts = findViewById(R.id.meeting_summary_facts);
        whenView = findViewById(R.id.meeting_summary_when);
        metaView = findViewById(R.id.meeting_summary_meta);
        descriptionView = findViewById(R.id.meeting_summary_description);
        summaryLabel = findViewById(R.id.meeting_summary_label);
        summaryText = findViewById(R.id.meeting_summary_text);
        summaryNone = findViewById(R.id.meeting_summary_none);
        decisionsLabel = findViewById(R.id.meeting_decisions_label);
        decisionsList = findViewById(R.id.meeting_decisions_list);
        followUpsLabel = findViewById(R.id.meeting_followups_label);
        followUpsList = findViewById(R.id.meeting_followups_list);
        actionsLabel = findViewById(R.id.meeting_actions_label);
        actionsCount = findViewById(R.id.meeting_actions_count);
        actionsList = findViewById(R.id.meeting_actions_list);
        actionsNone = findViewById(R.id.meeting_actions_none);

        transcriptCurrent = findViewById(R.id.meeting_transcript_current);
        sessionsLabel = findViewById(R.id.meeting_transcript_sessions_label);
        sessionsList = findViewById(R.id.meeting_transcript_sessions_list);
    }

    private void bindActions() {
        ImageButton back = findViewById(R.id.meeting_detail_btn_back);
        back.setOnClickListener(v -> finish());
        tabSummary.setOnClickListener(v -> showTab(0));
        tabTranscript.setOnClickListener(v -> showTab(1));
        retryText.setOnClickListener(v -> loadDetail());
    }

    private void showTab(int index) {
        boolean s = index == 0, t = index == 1;
        setTab(tabSummary, s);
        setTab(tabTranscript, t);
        vis(summaryRoot, s);
        vis(transcriptRoot, t);
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

    private static void vis(View v, boolean visible) {
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void loadDetail() {
        if (fetching || meetingId <= 0) return;
        fetching = true;
        showError(false);
        vis(summarySkeleton, true);
        vis(transcriptSkeleton, true);

        QolabWebViewTransport transport = QolabWebViewTransport.get();
        if (transport == null) {
            fetching = false;
            routeToLogin();
            return;
        }
        transport.getJson("/meetings/" + meetingId + "/detail", r -> runOnUiThread(() -> {
            fetching = false;
            Log.d(TAG, "detail status=" + r.status
                    + " bodyLen=" + (r.body == null ? -1 : r.body.length()));
            if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD || r.status == 401) {
                routeToLogin();
                return;
            }
            if (r.status != 200 || r.body == null) {
                showError(true);
                return;
            }
            MeetingDetail parsed = RoomDataClient.parseMeetingDetail(r.body);
            if (parsed == null) {
                showError(true);
                return;
            }
            detail = parsed;
            if (parsed.title != null) titleView.setText(parsed.title);
            renderSummary();
            renderTranscript();
        }));
    }

    private void routeToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void showError(boolean show) {
        vis(errorText, show);
        vis(retryText, show);
        if (show) {
            vis(summarySkeleton, false);
            vis(transcriptSkeleton, false);
        }
    }

    // ── Summary tab ───────────────────────────────────────────────────

    private void renderSummary() {
        if (detail == null) return;
        vis(summarySkeleton, false);

        vis(facts, true);
        if (titleFull != null) {
            ((TextView) titleFull).setText(detail.title != null ? detail.title : "");
        }
        ((TextView) whenView).setText(detail.scheduledAtDisplay != null
                ? detail.scheduledAtDisplay
                + (detail.timezoneAbbr != null ? " " + detail.timezoneAbbr : "")
                : "");
        StringBuilder meta = new StringBuilder();
        meta.append(statusLabel(detail.status));
        if (detail.durationSeconds > 0) {
            meta.append(" · ").append(String.format(Locale.getDefault(),
                    getString(R.string.meeting_detail_duration_min), detail.durationSeconds / 60));
        }
        ((TextView) metaView).setText(meta);
        if (detail.description != null && !detail.description.isEmpty()) {
            vis(descriptionView, true);
            ((TextView) descriptionView).setText(detail.description);
        } else {
            vis(descriptionView, true);
            ((TextView) descriptionView).setText(R.string.meeting_detail_no_meeting_desc);
        }

        // AI summary
        vis(summaryLabel, true);
        if (detail.aiSummary != null && !detail.aiSummary.isEmpty()) {
            vis(summaryText, true);
            ((TextView) summaryText).setText(detail.aiSummary);
        } else {
            vis(summaryNone, true);
        }

        // key decisions / follow-ups (structured rows)
        renderBulletList(decisionsLabel, decisionsList, detail.keyDecisions, true);
        renderBulletList(followUpsLabel, followUpsList, detail.followUps, false);

        // action items
        List<MeetingTask> tasks = detail.tasks;
        if (tasks.isEmpty()) {
            vis(actionsLabel, true);
            vis(actionsNone, true);
        } else {
            vis(actionsLabel, true);
            vis(actionsCount, true);
            ((TextView) actionsCount).setText(String.format(Locale.getDefault(),
                    getString(R.string.meeting_detail_task_count),
                    detail.taskCompleted, detail.taskTotal));
            LinearLayout list = (LinearLayout) actionsList;
            list.removeAllViews();
            for (MeetingTask t : tasks) list.addView(taskRow(t));
            vis(actionsList, true);
        }
    }

    private void renderBulletList(View label, View list, List<String> items, boolean marker) {
        if (items == null || items.isEmpty()) return;
        vis(label, true);
        LinearLayout l = (LinearLayout) list;
        l.removeAllViews();
        for (String item : items) {
            if (marker) {
                View row = getLayoutInflater().inflate(R.layout.view_summary_item_row, null, false);
                ((TextView) row.findViewById(R.id.summary_item_text))
                        .setText(item != null ? item : "");
                l.addView(row);
            } else {
                TextView tv = new TextView(this);
                tv.setText(item != null ? item : "");
                tv.setTextColor(getColor(R.color.qm_ink));
                tv.setTextSize(13.5f);
                tv.setLineSpacing(dp(2), 1f);
                tv.setPadding(dp(2), dp(4), dp(2), dp(4));
                l.addView(tv);
            }
        }
        vis(list, true);
    }

    private View taskRow(MeetingTask t) {
        View row = getLayoutInflater().inflate(R.layout.view_room_task_row, null, false);
        ((TextView) row.findViewById(R.id.room_task_title)).setText(t.title != null ? t.title : "");

        TextView status = row.findViewById(R.id.room_task_status);
        String statusLabel = RoomFormat.statusLabel(t.status);
        if (statusLabel != null) {
            status.setVisibility(View.VISIBLE);
            status.setText(statusLabel);
            if ("Completed".equals(statusLabel)) {
                status.setBackgroundResource(R.drawable.qm_chip_scheduled);
                status.setTextColor(getColor(R.color.qm_mint_text));
            } else {
                status.setBackgroundResource(R.drawable.qm_chip_ended);
                status.setTextColor(getColor(R.color.qm_muted));
            }
        } else {
            status.setVisibility(View.GONE);
        }

        TextView assignee = row.findViewById(R.id.room_task_assignee);
        if (t.assigneeName != null && !t.assigneeName.isEmpty()) {
            assignee.setVisibility(View.VISIBLE);
            assignee.setText(getString(R.string.room_task_assigned_to, t.assigneeName));
        }
        TextView due = row.findViewById(R.id.room_task_due);
        String dueLabel = RoomFormat.formatDueDate(t.dueDate);
        if (dueLabel != null) {
            due.setVisibility(View.VISIBLE);
            due.setText(dueLabel);
        }
        TextView priority = row.findViewById(R.id.room_task_priority);
        String priorityLabel = RoomFormat.statusLabel(t.priority);
        if (priorityLabel != null) {
            priority.setVisibility(View.VISIBLE);
            priority.setTextColor(getColor(R.color.qm_peach_text));
            priority.setText(priorityLabel);
        }
        return row;
    }

    // ── Transcript tab ────────────────────────────────────────────────

    private void renderTranscript() {
        if (detail == null) return;
        vis(transcriptSkeleton, false);

        boolean currentHas = (detail.transcriptText != null && !detail.transcriptText.isEmpty())
                || !detail.segments.isEmpty();
        List<TranscriptSession> sessions = detail.historicalSessions;

        if (!currentHas && sessions.isEmpty()) {
            vis(transcriptNone, true);
            return;
        }

        if (currentHas) {
            transcriptCurrent.removeAllViews();
            if (!detail.segments.isEmpty()) {
                for (Segment s : detail.segments) transcriptCurrent.addView(segmentRow(s));
            } else {
                TextView plain = new TextView(this);
                plain.setText(detail.transcriptText);
                plain.setTextColor(getColor(R.color.qm_ink));
                plain.setTextSize(14);
                plain.setLineSpacing(dp(3), 1f);
                transcriptCurrent.addView(plain);
            }
            vis(transcriptCurrent, true);
        }

        if (!sessions.isEmpty()) {
            vis(sessionsLabel, true);
            LinearLayout list = (LinearLayout) sessionsList;
            list.removeAllViews();
            int i = 1;
            for (TranscriptSession s : sessions) {
                list.addView(sessionBlock(s, i));
                i++;
            }
            vis(sessionsList, true);
        }
    }

    private View segmentRow(Segment s) {
        View row = getLayoutInflater().inflate(R.layout.view_transcript_segment, null, false);
        TextView header = row.findViewById(R.id.transcript_segment_header);
        String time = s.timestamp > 0
                ? String.format(Locale.US, "%02d:%02d",
                        s.timestamp / 60, s.timestamp % 60)
                : null;
        header.setText(time != null
                ? (s.speaker != null ? s.speaker : "") + "  ·  " + time
                : (s.speaker != null ? s.speaker : ""));
        TextView text = row.findViewById(R.id.transcript_segment_text);
        text.setText(s.text != null ? s.text : "");
        return row;
    }

    private View sessionBlock(TranscriptSession s, int index) {
        View block = getLayoutInflater().inflate(R.layout.view_transcript_session, null, false);
        ((TextView) block.findViewById(R.id.transcript_session_title))
                .setText(String.format(Locale.getDefault(),
                        getString(R.string.meeting_detail_session), index));
        ((TextView) block.findViewById(R.id.transcript_session_when))
                .setText(RoomFormat.formatIsoDate(s.scheduledAt) != null
                        ? RoomFormat.formatIsoDate(s.scheduledAt) : "");

        LinearLayout body = block.findViewById(R.id.transcript_session_body);
        boolean has = (s.transcriptText != null && !s.transcriptText.isEmpty())
                || !s.segments.isEmpty();
        if (has) {
            if (!s.segments.isEmpty()) {
                for (Segment seg : s.segments) body.addView(segmentRow(seg));
            } else {
                TextView plain = new TextView(this);
                plain.setText(s.transcriptText);
                plain.setTextColor(getColor(R.color.qm_ink));
                plain.setTextSize(13);
                plain.setLineSpacing(dp(3), 1f);
                body.addView(plain);
            }
        } else {
            TextView none = new TextView(this);
            none.setText(R.string.meeting_detail_transcript_none_session);
            none.setTextColor(getColor(R.color.qm_muted));
            none.setTextSize(13);
            body.addView(none);
        }
        return block;
    }

    private String statusLabel(String status) {
        String l = RoomFormat.statusLabel(status);
        return l != null ? l : getString(R.string.room_meeting_scheduled);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
