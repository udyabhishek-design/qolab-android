package dev.drytis.qolab;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v1.5.53 — native member profile (Directory detail). Opened from a
 * DirectoryActivity row.
 *
 * Data: GET /directory/{id} (Inertia Directory/Show) via the
 * authenticated WebView transport — the exact same page the web app
 * serves, read-only. Shows only fields the backend returns (honest
 * omissions — no fabricated data).
 *
 * States: skeleton while loading, error + Retry (ConnectivityWatcher
 * auto-recovery), content when parsed.
 */
public class MemberDetailActivity extends AppCompatActivity {

    public static final String EXTRA_EMPLOYEE_ID = "employee_id";
    public static final String EXTRA_EMPLOYEE_NAME = "employee_name";

    private View header, scroll, skeleton, errorBox, retry;
    private TextView avatar, name, status, bioLabel, bio;
    private LinearLayout rows;
    private QolabWebViewTransport transport;
    private DirectoryDataClient.EmployeeDetail detail;
    private long employeeId;
    private boolean fetching;
    private Object netWatcher;
    private final java.util.concurrent.atomic.AtomicInteger loadGeneration =
            new java.util.concurrent.atomic.AtomicInteger(0);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_member_detail);
        transport = QolabWebViewTransport.get();
        employeeId = getIntent().getLongExtra(EXTRA_EMPLOYEE_ID, 0);

        header = findViewById(R.id.member_detail_header);
        scroll = findViewById(R.id.member_detail_scroll);
        skeleton = findViewById(R.id.member_detail_skeleton);
        errorBox = findViewById(R.id.member_detail_error_box);
        retry = findViewById(R.id.member_detail_retry);
        avatar = findViewById(R.id.member_detail_avatar);
        name = findViewById(R.id.member_detail_name);
        status = findViewById(R.id.member_detail_status);
        bioLabel = findViewById(R.id.member_detail_bio_label);
        bio = findViewById(R.id.member_detail_bio);
        rows = findViewById(R.id.member_detail_rows);

        applyInsets();

        findViewById(R.id.member_detail_btn_back).setOnClickListener(v -> finish());
        retry.setOnClickListener(v -> load());

        netWatcher = ConnectivityWatcher.register(this, () -> runOnUiThread(() -> {
            if (detail == null && !fetching) {
                showLoading();
                load();
            }
        }));

        showLoading();
        load();
    }

    @Override
    protected void onDestroy() {
        ConnectivityWatcher.unregister(this, netWatcher);
        super.onDestroy();
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
                    header.setPadding(header.getPaddingStart(),
                            status + 12, header.getPaddingEnd(),
                            header.getPaddingBottom());
                    LinearLayout content = scroll.findViewById(
                            R.id.member_detail_content) != null
                            ? scroll.findViewById(R.id.member_detail_content) : null;
                    if (content != null) {
                        content.setPadding(content.getPaddingStart(),
                                content.getPaddingTop(), content.getPaddingEnd(),
                                navBar + 24);
                    }
                    return WindowInsetsCompat.CONSUMED;
                });
    }

    // ── data ──────────────────────────────────────────────────────────

    private void load() {
        if (fetching || employeeId <= 0) return;
        if (transport == null || !transport.isAlive()) {
            SessionGate.requireValidSession(this, origin(),
                    () -> runOnUiThread(() -> {
                        transport = QolabWebViewTransport.get();
                        load();
                    }),
                    this::routeToLogin,
                    () -> runOnUiThread(this::showError));
            return;
        }
        fetching = true;
        final int gen = loadGeneration.incrementAndGet();
        transport.getJson("/directory/" + employeeId, r -> runOnUiThread(() -> {
            fetching = false;
            if (gen != loadGeneration.get() || isFinishing() || isDestroyed()) {
                return;
            }
            if (r.status == QolabWebViewTransport.Result.STATUS_TRANSPORT_DEAD) {
                SessionGate.requireValidSession(this, origin(),
                        () -> runOnUiThread(() -> {
                            transport = QolabWebViewTransport.get();
                            load();
                        }),
                        this::routeToLogin,
                        () -> runOnUiThread(this::showError));
                return;
            }
            if (r.status == 401) {
                routeToLogin();
                return;
            }
            if (r.status < 200 || r.status >= 300 || r.body == null) {
                showError();
                return;
            }
            JSONObject inertia = RoomDataClient.extractInertiaPage(r.body);
            DirectoryDataClient.EmployeeDetail parsed =
                    DirectoryDataClient.parseDetail(inertia);
            if (parsed == null) {
                showError();
                return;
            }
            detail = parsed;
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
        skeleton.setVisibility(View.VISIBLE);
        errorBox.setVisibility(View.GONE);
        scroll.setVisibility(View.GONE);
    }

    private void showError() {
        if (detail != null) return; // keep valid content
        skeleton.setVisibility(View.GONE);
        scroll.setVisibility(View.GONE);
        errorBox.setVisibility(View.VISIBLE);
    }

    // ── rendering ─────────────────────────────────────────────────────

    private void render() {
        skeleton.setVisibility(View.GONE);
        errorBox.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);

        DirectoryDataClient.Employee e = detail.employee;
        String label = e.name != null ? e.name : "";
        name.setText(e.self
                ? getString(R.string.directory_row_you, label) : label);
        avatar.setText(HomeActivity.initialsOf(label));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(HomeActivity.avatarColorOf(label));
        avatar.setBackground(circle);
        status.setText(statusLabel(e.currentStatus));

        rows.removeAllViews();
        if (e.departmentName != null && !e.departmentName.isEmpty()) {
            rows.addView(infoRow(getString(R.string.directory_row_department),
                    e.departmentName));
        }
        if (!e.jobTitle.isEmpty()) {
            rows.addView(infoRow(getString(R.string.directory_row_designation),
                    e.jobTitle));
        }
        if (!e.email.isEmpty()) {
            rows.addView(infoRow(getString(R.string.directory_row_email), e.email));
        }
        if (detail.timezone != null && !detail.timezone.isEmpty()) {
            rows.addView(infoRow(getString(R.string.directory_row_timezone),
                    detail.timezone));
        }
        if (detail.joinedAt != null) {
            String formatted = formatDate(detail.joinedAt);
            if (formatted != null) {
                rows.addView(infoRow(getString(R.string.directory_row_joined), formatted));
            }
        }
        if (!detail.languages.isEmpty()) {
            rows.addView(infoRow(getString(R.string.directory_row_languages),
                    join(detail.languages)));
        }

        if (detail.bio != null && !detail.bio.trim().isEmpty()) {
            bioLabel.setVisibility(View.VISIBLE);
            bio.setVisibility(View.VISIBLE);
            bio.setText(detail.bio.trim());
        } else {
            bioLabel.setVisibility(View.GONE);
            bio.setVisibility(View.GONE);
        }
    }

    private View infoRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(getColor(R.color.qm_muted));
        l.setTextSize(12f);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(getColor(R.color.qm_ink));
        v.setTextSize(14f);
        row.addView(l);
        row.addView(v);
        return row;
    }

    private String statusLabel(String s) {
        if (s == null) return getString(R.string.directory_status_unknown);
        switch (s) {
            case "online": return getString(R.string.directory_status_online);
            case "away": return getString(R.string.directory_status_away);
            case "offline": return getString(R.string.directory_status_offline);
            default: return getString(R.string.directory_status_unknown);
        }
    }

    /** ISO-8601 (with or without millis/zone) → "MMM d, yyyy". Null when
     *  unparseable (the row is then simply omitted — honest absence). */
    private static String formatDate(String iso) {
        try {
            String cut = iso.length() > 23 ? iso.substring(0, 23) : iso;
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            Date d = in.parse(cut);
            if (d == null) return null;
            return new SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(d);
        } catch (Exception e) {
            return null;
        }
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String i : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(i);
        }
        return sb.toString();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
