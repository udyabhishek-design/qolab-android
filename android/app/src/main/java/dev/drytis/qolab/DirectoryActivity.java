package dev.drytis.qolab;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.5.53 — native Directory. Opened from the Home hamburger menu
 * (previously Coming Soon).
 *
 * Data: GET /directory (Inertia Directory/Index) via the authenticated
 * WebView transport — the exact same page the web app serves, read-only.
 * Server-side search (?search=&amp;page=) is used so filtering matches
 * backend visibility (active, non-admin members of the company); the
 * last query is also applied locally so typing is instant between
 * server responses.
 *
 * Rows open {@link MemberDetailActivity} (GET /directory/{id}).
 * States: skeleton on first load, error + Retry (ConnectivityWatcher
 * auto-recovery), empty directory, and a distinct no-search-results
 * state.
 */
public class DirectoryActivity extends AppCompatActivity {

    private static final String TAG = "DirectoryActivity";
    private static final long SEARCH_DEBOUNCE_MS = 300;

    private LinearLayout directoryList;
    private View directorySkeleton, directoryErrorBox, directoryScroll,
            directoryEmpty, directoryNoResults, directoryLoadMore, directoryHeader;
    private EditText searchField;
    private QolabWebViewTransport transport;

    private DirectoryDataClient.DirectoryPage page;
    private final List<DirectoryDataClient.Employee> loaded = new ArrayList<>();
    private String activeQuery = "";       // query the loaded list reflects
    private String shownQuery = "";        // query currently rendered
    private boolean fetching;
    private int nextPage = 1;
    private Object netWatcher;
    private Runnable pendingSearch;
    private final java.util.concurrent.atomic.AtomicInteger loadGeneration =
            new java.util.concurrent.atomic.AtomicInteger(0);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_directory);
        transport = QolabWebViewTransport.get();

        directoryHeader = findViewById(R.id.directory_header);
        directoryList = findViewById(R.id.directory_list);
        directorySkeleton = findViewById(R.id.directory_skeleton);
        directoryErrorBox = findViewById(R.id.directory_error_box);
        directoryEmpty = findViewById(R.id.directory_empty);
        directoryNoResults = findViewById(R.id.directory_no_results);
        directoryScroll = findViewById(R.id.directory_scroll);
        directoryLoadMore = findViewById(R.id.directory_load_more);

        applyInsets();

        findViewById(R.id.directory_btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.directory_retry).setOnClickListener(v -> load());
        directoryLoadMore.setOnClickListener(v -> {
            if (page != null && nextPage <= page.lastPage && !fetching) load();
        });

        searchField = findViewById(R.id.directory_search);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { onQueryChanged(); }
        });
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                scheduleSearchNow();
                return true;
            }
            return false;
        });

        // v1.5.46 pattern: bounded edge-triggered network auto-retry —
        // only when there is no valid page yet and no request running.
        netWatcher = ConnectivityWatcher.register(this, () -> runOnUiThread(() -> {
            if (page == null && !fetching) {
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
        if (pendingSearch != null) searchField.removeCallbacks(pendingSearch);
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
                    directoryHeader.setPadding(directoryHeader.getPaddingStart(),
                            status + 12, directoryHeader.getPaddingEnd(),
                            directoryHeader.getPaddingBottom());
                    directoryList.setPadding(directoryList.getPaddingStart(),
                            directoryList.getPaddingTop(),
                            directoryList.getPaddingEnd(), navBar + 24);
                    return WindowInsetsCompat.CONSUMED;
                });
    }

    // ── search ────────────────────────────────────────────────────────

    private void onQueryChanged() {
        // Instant local filter over whatever is already loaded — typing
        // never waits for the network. A debounced server query follows
        // so the set matches backend visibility exactly.
        render();
        if (pendingSearch != null) searchField.removeCallbacks(pendingSearch);
        pendingSearch = this::runPendingSearch;
        searchField.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
    }

    private void scheduleSearchNow() {
        if (pendingSearch != null) searchField.removeCallbacks(pendingSearch);
        runPendingSearch();
    }

    private void runPendingSearch() {
        pendingSearch = null;
        String q = searchField.getText().toString().trim();
        if (q.equals(activeQuery)) return;
        nextPage = 1;
        load();
    }

    // ── data ──────────────────────────────────────────────────────────

    private void load() {
        if (fetching) return;
        if (transport == null || !transport.isAlive()) {
            // Verify with the server before logging out — a dead
            // transport is not proof of expiry (v1.5.40 SessionGate).
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
        final String query = searchField.getText().toString().trim();
        if (nextPage <= 1 && page == null) showLoading();
        if (nextPage > 1) setLoadMoreBusy(true);
        StringBuilder path = new StringBuilder("/directory?page=").append(Math.max(1, nextPage));
        if (!query.isEmpty()) path.append("&search=").append(encoded(query));
        transport.getJson(path.toString(), r -> runOnUiThread(() -> {
            fetching = false;
            setLoadMoreBusy(false);
            if (gen != loadGeneration.get() || isFinishing() || isDestroyed()) {
                return; // stale delivery — superseded or destroyed
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
                routeToLogin(); // server-proven expiry
                return;
            }
            if (r.status < 200 || r.status >= 300 || r.body == null) {
                showError();
                return;
            }
            JSONObject inertia = RoomDataClient.extractInertiaPage(r.body);
            DirectoryDataClient.DirectoryPage parsed = DirectoryDataClient.parsePage(inertia);
            if (parsed == null) {
                showError();
                return;
            }
            page = parsed;
            activeQuery = query;
            if (nextPage <= 1) {
                loaded.clear();
            }
            loaded.addAll(parsed.employees);
            nextPage = parsed.currentPage + 1;
            render();
        }));
    }

    private void setLoadMoreBusy(boolean busy) {
        if (directoryLoadMore instanceof TextView) {
            ((TextView) directoryLoadMore).setText(busy
                    ? R.string.directory_loading_more
                    : R.string.directory_load_more);
        }
    }

    private static String encoded(String q) {
        try {
            return java.net.URLEncoder.encode(q, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return q.replace(" ", "+");
        }
    }

    private void routeToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    // ── states ────────────────────────────────────────────────────────

    private void showLoading() {
        directorySkeleton.setVisibility(View.VISIBLE);
        directoryErrorBox.setVisibility(View.GONE);
        directoryEmpty.setVisibility(View.GONE);
        directoryNoResults.setVisibility(View.GONE);
        directoryScroll.setVisibility(View.GONE);
        directoryLoadMore.setVisibility(View.GONE);
    }

    private void showError() {
        if (page != null) {
            // v1.5.44 rule: a failed refresh never tears down valid
            // content — non-blocking only.
            return;
        }
        directorySkeleton.setVisibility(View.GONE);
        directoryScroll.setVisibility(View.GONE);
        directoryEmpty.setVisibility(View.GONE);
        directoryNoResults.setVisibility(View.GONE);
        directoryLoadMore.setVisibility(View.GONE);
        directoryErrorBox.setVisibility(View.VISIBLE);
    }

    // ── rendering ─────────────────────────────────────────────────────

    private void render() {
        directorySkeleton.setVisibility(View.GONE);
        directoryErrorBox.setVisibility(View.GONE);

        String q = searchField.getText().toString().trim();
        shownQuery = q;

        List<DirectoryDataClient.Employee> visible = new ArrayList<>();
        for (DirectoryDataClient.Employee e : loaded) {
            if (DirectoryDataClient.matches(e, q)) visible.add(e);
        }

        // Distinct states: empty directory vs no search results.
        if (loaded.isEmpty()) {
            directoryScroll.setVisibility(View.GONE);
            directoryNoResults.setVisibility(View.GONE);
            directoryLoadMore.setVisibility(View.GONE);
            directoryEmpty.setVisibility(View.VISIBLE);
            return;
        }
        if (visible.isEmpty()) {
            directoryScroll.setVisibility(View.GONE);
            directoryEmpty.setVisibility(View.GONE);
            directoryLoadMore.setVisibility(View.GONE);
            TextView nr = (TextView) directoryNoResults;
            nr.setText(getString(R.string.directory_no_results, q));
            nr.setVisibility(View.VISIBLE);
            return;
        }
        directoryEmpty.setVisibility(View.GONE);
        directoryNoResults.setVisibility(View.GONE);
        directoryScroll.setVisibility(View.VISIBLE);

        boolean moreAvailable = page != null && nextPage <= page.lastPage;
        directoryLoadMore.setVisibility(moreAvailable ? View.VISIBLE : View.GONE);

        directoryList.removeAllViews();
        for (DirectoryDataClient.Employee e : visible) {
            directoryList.addView(employeeRow(e));
        }
    }

    /** Member row: initials avatar, name (+ You), department · designation. */
    private View employeeRow(DirectoryDataClient.Employee e) {
        View row = getLayoutInflater().inflate(R.layout.view_member_row, null, false);
        TextView avatar = row.findViewById(R.id.member_row_avatar);
        TextView name = row.findViewById(R.id.member_row_name);
        TextView meta = row.findViewById(R.id.member_row_meta);
        View check = row.findViewById(R.id.member_row_check);
        if (check != null) check.setVisibility(View.GONE); // picker artifact

        String label = e.name != null ? e.name : "";
        name.setText(e.self
                ? getString(R.string.directory_row_you, label)
                : label);
        avatar.setText(HomeActivity.initialsOf(label));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(HomeActivity.avatarColorOf(label));
        avatar.setBackground(circle);

        StringBuilder m = new StringBuilder();
        if (e.departmentName != null && !e.departmentName.isEmpty()) m.append(e.departmentName);
        if (e.jobTitle != null && !e.jobTitle.isEmpty()) {
            if (m.length() > 0) m.append(" · ");
            m.append(e.jobTitle);
        }
        if (m.length() == 0 && e.currentStatus != null && !e.currentStatus.isEmpty()) {
            m.append(statusLabel(e.currentStatus));
        }
        meta.setText(m.toString());

        if (DirectoryDataClient.openable(e)) {
            row.setOnClickListener(v -> {
                Intent i = new Intent(this, MemberDetailActivity.class);
                i.putExtra(MemberDetailActivity.EXTRA_EMPLOYEE_ID, e.id);
                i.putExtra(MemberDetailActivity.EXTRA_EMPLOYEE_NAME, label);
                startActivity(i);
            });
        } else {
            row.setClickable(false);
        }
        return row;
    }

    private String statusLabel(String status) {
        if (status == null) return getString(R.string.directory_status_unknown);
        switch (status) {
            case "online": return getString(R.string.directory_status_online);
            case "away": return getString(R.string.directory_status_away);
            case "offline": return getString(R.string.directory_status_offline);
            default: return getString(R.string.directory_status_unknown);
        }
    }
}
