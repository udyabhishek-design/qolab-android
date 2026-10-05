package dev.drytis.qolab;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * v1.5.31 — Create Meeting bottom sheet for the Engineering Room.
 * Posts to the EXISTING contract POST /office/rooms/{channelId}/meetings
 * via the transport (JSON + XSRF inside the session WebView). Room/channel
 * association is server-side from the route — the user never re-picks it
 * and no ids/slugs are visible in the UI.
 *
 * "Start now" = the contract's instant mode: same POST without scheduled_at
 * (verified in web client + MeetingController@roomStore: empty scheduled_at
 * ⇒ isInstant ⇒ filter=live).
 */
public class CreateMeetingSheet {

    public interface Listener {
        /** Called once after a successful create (schedule or instant). */
        void onMeetingCreated();
    }

    private final AppCompatActivity activity;
    private final long channelId;
    private final String roomName;
    private final List<RoomDataClient.Member> members;
    private final Listener listener;
    private final QolabWebViewTransport transport = QolabWebViewTransport.get();

    private androidx.appcompat.app.AlertDialog dialog;
    private EditText titleInput, descriptionInput;
    private TextView datePick, timePick, timezoneView, errorView, createBtn, startNowBtn;
    private ProgressBar progress;
    private LinearLayout participantsList;
    private final Set<Long> selectedIds = new HashSet<>();
    private Integer year, month, day, hour, minute; // picked values
    private boolean submitting;

    public CreateMeetingSheet(AppCompatActivity activity, long channelId, String roomName,
                              List<RoomDataClient.Member> members, Listener listener) {
        this.activity = activity;
        this.channelId = channelId;
        this.roomName = roomName;
        this.members = members != null ? members : new ArrayList<>();
        this.listener = listener;
    }

    public void show() {
        View content = LayoutInflater.from(activity)
                .inflate(R.layout.view_create_meeting_sheet, null);
        dialog = new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setView(content)
                .create();
        bind(content);
        dialog.show();
    }

    private void bind(View content) {
        ((TextView) content.findViewById(R.id.cm_subtitle)).setText(
                roomName != null ? roomName : "");
        titleInput = content.findViewById(R.id.cm_input_title);
        descriptionInput = content.findViewById(R.id.cm_input_description);
        datePick = content.findViewById(R.id.cm_pick_date);
        timePick = content.findViewById(R.id.cm_pick_time);
        timezoneView = content.findViewById(R.id.cm_timezone);
        errorView = content.findViewById(R.id.cm_error);
        createBtn = content.findViewById(R.id.cm_create);
        startNowBtn = content.findViewById(R.id.cm_start_now);
        progress = content.findViewById(R.id.cm_progress);
        participantsList = content.findViewById(R.id.cm_participants_list);

        timezoneView.setText(CreateMeetingForm.displayTimezone(
                CreateMeetingForm.deviceTimezone()));

        // Editing a field clears any visible validation error (local or
        // server 422); it reappears only if the next submit fails again.
        android.text.TextWatcher clearErr = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                clearError();
            }
        };
        titleInput.addTextChangedListener(clearErr);
        descriptionInput.addTextChangedListener(clearErr);

        int[] today = CreateMeetingForm.defaultPickerDate();
        datePick.setOnClickListener(v -> new DatePickerDialog(activity, (dp, y, m, d) -> {
            year = y; month = m + 1; day = d;
            datePick.setText(humanDate(y, m + 1, d));
            clearError();
        }, today[0], today[1] - 1, today[2]).show());
        timePick.setOnClickListener(v -> new TimePickerDialog(activity, (tp, h, min) -> {
            hour = h; minute = min;
            timePick.setText(humanTime(h, min));
            clearError();
        }, 9, 0, false).show());

        renderParticipants();

        createBtn.setOnClickListener(v -> submit(false));
        startNowBtn.setOnClickListener(v -> submit(true));
        content.findViewById(R.id.cm_cancel).setOnClickListener(v -> {
            if (!submitting) dialog.dismiss();
        });
    }

    /** Real room members only; selectable rows, creator-exclusion not
     *  needed (server force-adds the creator and ignores duplicates). */
    private void renderParticipants() {
        participantsList.removeAllViews();
        if (members.isEmpty()) {
            TextView none = new TextView(activity);
            none.setText(R.string.cm_participant_hint);
            none.setTextColor(activity.getColor(R.color.qm_muted));
            none.setTextSize(12);
            none.setPadding(0, dp(4), 0, 0);
            participantsList.addView(none);
            return;
        }
        for (RoomDataClient.Member m : members) {
            if (m.id <= 0) continue;
            participantsList.addView(participantRow(m));
        }
    }

    private View participantRow(RoomDataClient.Member m) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView avatar = new TextView(activity);
        int size = dp(32);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        avatar.setLayoutParams(lp);
        avatar.setGravity(Gravity.CENTER);
        avatar.setText(HomeActivity.initialsOf(m.name));
        avatar.setTextColor(activity.getColor(R.color.qm_white_text));
        avatar.setTextSize(12);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(HomeActivity.avatarColorOf(m.name));
        avatar.setBackground(g);
        row.addView(avatar);

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMargins(dp(10), 0, 0, 0);
        texts.setLayoutParams(tp);

        TextView name = new TextView(activity);
        name.setText(m.name != null ? m.name : "");
        name.setTextColor(activity.getColor(R.color.qm_ink));
        name.setTextSize(14);
        texts.addView(name);
        String detail = join(" · ", humanRole(m.role), m.jobTitle);
        if (detail != null) {
            TextView sub = new TextView(activity);
            sub.setText(detail);
            sub.setTextColor(activity.getColor(R.color.qm_muted));
            sub.setTextSize(12);
            texts.addView(sub);
        }
        row.addView(texts);

        CheckBox cb = new CheckBox(activity);
        cb.setOnCheckedChangeListener((b, checked) -> {
            if (checked) selectedIds.add(m.id);
            else selectedIds.remove(m.id);
        });
        row.addView(cb);
        row.setOnClickListener(v -> cb.toggle());
        return row;
    }

    private void submit(boolean instant) {
        if (submitting) return;
        // Always read CURRENT visible values; trim before local validation so
        // the checked value and the payload value are identical.
        String title = titleInput.getText().toString().trim();
        boolean hasDate = year != null, hasTime = hour != null;

        if (instant) {
            // instant contract: no scheduled_at; still needs a title
            String v = CreateMeetingForm.validate(title, true, true);
            if (v != null) { showError(R.string.cm_error_title_required); return; }
        } else {
            String v = CreateMeetingForm.validate(title, hasDate, hasTime);
            if (v == null && !hasDate) v = "when"; // both empty = incomplete
            if (v != null) {
                showError("title".equals(v)
                        ? R.string.cm_error_title_required
                        : R.string.cm_error_when_required);
                return;
            }
        }

        String scheduledAt = instant ? null
                : CreateMeetingForm.scheduledAtLocal(year, month, day, hour, minute);
        List<Long> ids = new ArrayList<>(selectedIds);
        setSubmitting(true);
        try {
            String payload = CreateMeetingForm.buildPayload(
                    title, descriptionInput.getText().toString(),
                    scheduledAt, CreateMeetingForm.deviceTimezone(), ids);
            transport.postJson("/office/rooms/" + channelId + "/meetings", payload,
                    r -> activity.runOnUiThread(() -> {
                        setSubmitting(false);
                        if (r.status == 302 || r.status == 201 || r.status == 200) {
                            // existing contract redirects on success; any of
                            // these statuses means created
                            dialog.dismiss();
                            Toast.makeText(activity,
                                    instant ? R.string.cm_started_toast
                                            : R.string.cm_created_toast,
                                    Toast.LENGTH_SHORT).show();
                            if (listener != null) listener.onMeetingCreated();
                        } else if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD
                                || r.status == 401) {
                            dialog.dismiss();
                        } else if (r.status == 422 && r.body != null) {
                            String human = CreateMeetingForm.humanError(r.body);
                            showError(human != null ? human
                                    : activity.getString(R.string.cm_error_generic));
                        } else {
                            showError(R.string.cm_error_generic);
                        }
                    }));
        } catch (Exception e) {
            setSubmitting(false);
            showError(R.string.cm_error_generic);
        }
    }

    private void setSubmitting(boolean on) {
        submitting = on;
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        createBtn.setEnabled(!on);
        startNowBtn.setEnabled(!on);
        createBtn.setAlpha(on ? 0.45f : 1f);
    }

    private void showError(int res) {
        showError(activity.getString(res));
    }

    private void showError(String msg) {
        errorView.setText(msg);
        errorView.setVisibility(View.VISIBLE);
    }

    /** Hides the visible error line (called when the user edits any field). */
    private void clearError() {
        if (errorView != null) {
            errorView.setText(null);
            errorView.setVisibility(View.GONE);
        }
    }

    private String humanRole(String role) {
        if (role == null) return null;
        switch (role) {
            case "owner": return "Owner";
            case "admin": return "Admin";
            case "member": return "Member";
            default: return null;
        }
    }

    private static String join(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private String humanDate(int y, int m, int d) {
        java.time.LocalDate ld = java.time.LocalDate.of(y, m, d);
        return ld.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()));
    }

    private String humanTime(int h, int min) {
        java.time.LocalTime lt = java.time.LocalTime.of(h, min);
        return lt.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()));
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
