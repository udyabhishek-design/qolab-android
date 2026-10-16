package dev.drytis.qolab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.junit.Assert.*;

/**
 * v1.5.53 regression tests — hamburger Meetings → Department Room
 * Meetings tab deep-link, and Directory parsing/filtering.
 */
public class DirectoryAndMeetingsNavTest {

    private static String read(String rel) throws IOException {
        return new String(Files.readAllBytes(
                Paths.get(System.getProperty("user.dir"), "..", rel).normalize()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── parser (pure): directory page ────────────────────────────────

    private static JSONObject directoryPage(int page, int last, String rows)
            throws Exception {
        JSONObject employees = new JSONObject();
        employees.put("data", new JSONArray(rows));
        JSONObject meta = new JSONObject();
        meta.put("current_page", page);
        meta.put("last_page", last);
        meta.put("per_page", 20);
        meta.put("total", last * 20);
        employees.put("meta", meta);
        JSONObject props = new JSONObject();
        props.put("employees", employees);
        JSONObject p = new JSONObject();
        p.put("props", props);
        return p;
    }

    @Test
    public void parsesDirectoryRowsWithRealFields() throws Exception {
        String rows = "[{\"id\":9,\"name\":\"DJ Fazal\",\"email\":\"dj@qolab.test\","
                + "\"job_title\":\"Audio Engineer\",\"department\":{\"id\":7,\"name\":\"Media\"},"
                + "\"phone\":\"+92 300 0000000\",\"shift\":\"morning\",\"timezone\":\"Asia/Karachi\","
                + "\"current_status\":\"online\",\"last_active_at\":\"2026-10-16T09:00:00Z\"},"
                + "{\"id\":10,\"name\":\"No Dept\",\"email\":\"nd@qolab.test\","
                + "\"job_title\":\"\",\"department\":null,\"current_status\":\"offline\"}]";
        DirectoryDataClient.DirectoryPage p =
                DirectoryDataClient.parsePage(directoryPage(1, 3, rows));
        assertNotNull(p);
        assertEquals(2, p.employees.size());
        assertEquals(1, p.currentPage);
        assertEquals(3, p.lastPage);
        assertEquals(60, p.total);

        DirectoryDataClient.Employee e = p.employees.get(0);
        assertEquals(9, e.id);
        assertEquals("DJ Fazal", e.name);
        assertEquals("Audio Engineer", e.jobTitle);
        assertEquals("Media", e.departmentName);
        assertEquals("online", e.currentStatus);
        assertFalse(e.self);

        DirectoryDataClient.Employee e2 = p.employees.get(1);
        assertNull(e2.departmentName);
        assertEquals("", e2.jobTitle);
        assertEquals("offline", e2.currentStatus);
    }

    @Test
    public void rowsWithoutIdAreSkipped() throws Exception {
        String rows = "[{\"name\":\"no id\"},{\"id\":3,\"name\":\"Valid\"}]";
        DirectoryDataClient.DirectoryPage p =
                DirectoryDataClient.parsePage(directoryPage(1, 1, rows));
        assertEquals(1, p.employees.size());
        assertEquals(3, p.employees.get(0).id);
    }

    @Test
    public void emptyDirectoryParsesToEmptyList() throws Exception {
        DirectoryDataClient.DirectoryPage p =
                DirectoryDataClient.parsePage(directoryPage(1, 1, "[]"));
        assertNotNull(p);
        assertTrue(p.employees.isEmpty());
        assertEquals(20, p.total); // empty data, meta still reports its value
    }

    @Test
    public void malformedPageReturnsNull() throws Exception {
        assertNull(DirectoryDataClient.parsePage(null));
        assertNull(DirectoryDataClient.parsePage(new JSONObject("{\"props\":{}}")));
        assertNull(DirectoryDataClient.parsePage(new JSONObject("{\"nope\":1}")));
        // employees without data is a valid EMPTY directory, not a failure
        DirectoryDataClient.DirectoryPage empty = DirectoryDataClient.parsePage(
                new JSONObject("{\"props\":{\"employees\":{}}}"));
        assertNotNull(empty);
        assertTrue(empty.employees.isEmpty());
    }

    // ── parser (pure): member detail ─────────────────────────────────

    @Test
    public void parsesMemberDetail() throws Exception {
        JSONObject e = new JSONObject();
        e.put("id", 9);
        e.put("name", "DJ Fazal");
        e.put("email", "dj@qolab.test");
        e.put("job_title", "Audio Engineer");
        e.put("department", new JSONObject("{\"id\":7,\"name\":\"Media\"}"));
        e.put("bio", "Loves reverb.");
        e.put("languages", new JSONArray("[\"English\",\"Urdu\"]"));
        e.put("shift", "morning");
        e.put("timezone", "Asia/Karachi");
        e.put("joined_at", "2026-01-05T10:00:00.000000Z");
        e.put("last_active_at", "2026-10-16T09:00:00.000000Z");
        e.put("current_status", "away");
        JSONObject props = new JSONObject();
        props.put("employee", e);
        props.put("is_self", false);
        JSONObject p = new JSONObject();
        p.put("props", props);

        DirectoryDataClient.EmployeeDetail d = DirectoryDataClient.parseDetail(p);
        assertNotNull(d);
        assertEquals("DJ Fazal", d.employee.name);
        assertEquals("Media", d.employee.departmentName);
        assertFalse(d.employee.self);
        assertEquals("Loves reverb.", d.bio);
        assertEquals(2, d.languages.size());
        assertEquals("morning", d.shift);
        assertEquals("Asia/Karachi", d.timezone);
        assertNotNull(d.joinedAt);

        // self flag flows through
        props.put("is_self", true);
        assertTrue(DirectoryDataClient.parseDetail(p).employee.self);
    }

    @Test
    public void detailWithoutEmployeePropIsNull() throws Exception {
        assertNull(DirectoryDataClient.parseDetail(new JSONObject("{\"props\":{}}")));
        assertNull(DirectoryDataClient.parseDetail(null));
    }

    // ── search matching (pure) ───────────────────────────────────────

    @Test
    public void searchMatchesByNameCaseInsensitive() throws Exception {
        DirectoryDataClient.Employee e = DirectoryDataClient.employee(
                new JSONObject("{\"id\":1,\"name\":\"Abhishek U\"}"), false);
        assertTrue(DirectoryDataClient.matches(e, "abhi"));
        assertTrue(DirectoryDataClient.matches(e, "  Abhishek  "));
        assertTrue(DirectoryDataClient.matches(e, "shek"));
        assertTrue(DirectoryDataClient.matches(e, ""));
        assertTrue(DirectoryDataClient.matches(e, null));
        assertFalse(DirectoryDataClient.matches(e, "maria"));
        assertFalse(DirectoryDataClient.matches(e, "zzz"));
    }

    @Test
    public void openableRequiresPositiveId() throws Exception {
        assertTrue(DirectoryDataClient.openable(DirectoryDataClient.employee(
                new JSONObject("{\"id\":5,\"name\":\"x\"}"), false)));
        assertFalse(DirectoryDataClient.openable(DirectoryDataClient.employee(
                new JSONObject("{\"id\":0,\"name\":\"x\"}"), false)));
        assertFalse(DirectoryDataClient.openable(null));
    }

    // ── wiring: hamburger Meetings deep-link ─────────────────────────

    @Test
    public void hamburgerMeetingsOpensDepartmentRoomOnMeetingsTab() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/HomeActivity.java");
        int menu = src.indexOf("private void showMenuSheet()");
        assertTrue(menu > 0);
        assertTrue("menu must route via openDepartmentRoomMeetings()",
                src.indexOf("openDepartmentRoomMeetings()", menu) > 0);
        int resolver = src.indexOf("private void openDepartmentRoomMeetings()");
        assertTrue(resolver > 0);
        String body = src.substring(resolver,
                src.indexOf("private void renderContinue"));
        assertTrue("deep-link reuses the existing RoomActivity",
                body.contains("RoomActivity.class"));
        assertTrue("deep-link preselects the Meetings tab",
                body.contains("EXTRA_INITIAL_TAB, 1"));
        assertTrue("unresolvable department never opens another room",
                body.contains("directory_error_no_department_msg"));
        assertTrue("resolver reads the user's own department room",
                body.contains("departmentRoom"));
    }

    @Test
    public void hamburgerDirectoryOpensNativeDirectory() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/HomeActivity.java");
        int menu = src.indexOf("private void showMenuSheet()");
        int menuEnd = src.indexOf("private void loadHome", menu);
        assertTrue("wiring found inside the menu sheet",
                src.indexOf("home_menu_directory", menu) > 0);
        String sheet = src.substring(menu, menuEnd > 0 ? menuEnd : src.length());
        assertTrue("Directory opens DirectoryActivity from the menu sheet",
                sheet.contains("startActivity(new Intent(this, DirectoryActivity.class))"));
    }

    @Test
    public void roomActivityHonoursInitialTabBounded() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/RoomActivity.java");
        assertTrue("EXTRA_INITIAL_TAB declared",
                src.contains("EXTRA_INITIAL_TAB"));
        assertTrue("initial tab clamped to 0..2",
                src.contains("Math.max(0, Math.min(2,"));
        assertTrue("default entry point unchanged (Overview)",
                src.contains("getIntExtra(EXTRA_INITIAL_TAB, 0)"));
    }

    @Test
    public void departmentRoomEntryWithoutExtraIsUnchanged() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/HomeActivity.java");
        int open = src.indexOf("private void openDepartmentRoom(");
        String body = src.substring(open, open + 900);
        assertTrue("dept card path still opens RoomActivity",
                body.contains("RoomActivity.class"));
        assertFalse("dept card path does NOT preselect Meetings",
                body.contains("EXTRA_INITIAL_TAB"));
    }

    @Test
    public void manifestRegistersDirectoryScreens() throws IOException {
        String manifest = read("app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains(".DirectoryActivity"));
        assertTrue(manifest.contains(".MemberDetailActivity"));
    }

    // ── wiring: Directory activity contract ──────────────────────────

    @Test
    public void directoryActivityHasAllFourStatesAndRealData() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/DirectoryActivity.java");
        assertTrue("skeleton while first load", src.contains("private void showLoading()"));
        assertTrue("error + Retry", src.contains("private void showError()"));
        assertTrue("refresh failure preserves content (v1.5.44 rule)",
                src.indexOf("if (page != null)", src.indexOf("private void showError")) > 0);
        assertTrue("empty state", src.contains("directory_empty"));
        assertTrue("no-search-results state distinct from empty",
                src.contains("directory_no_results"));
        // Fetch + parse contract — existing endpoint only.
        assertTrue("fetches GET /directory via the authenticated transport",
                src.contains("getJson(path.toString()")
                        && src.contains("\"/directory?page=\""));
        assertTrue("server-side search used (?search=)",
                src.contains("&search="));
        assertTrue("pagination via ?page=", src.contains("?page="));
        assertTrue("parses via the pure client",
                src.contains("DirectoryDataClient.parsePage(inertia)"));
        // Session/recovery patterns preserved.
        assertTrue("SessionGate verifies dead transport",
                src.contains("SessionGate.requireValidSession"));
        assertTrue("ConnectivityWatcher auto-retry registered",
                src.contains("ConnectivityWatcher.register"));
        assertTrue("watcher unregistered on destroy",
                src.contains("ConnectivityWatcher.unregister(this, netWatcher)"));
        // Responsive after return.
        assertTrue("generation guard against stale deliveries",
                src.contains("loadGeneration"));
    }

    @Test
    public void directoryRowsOpenMemberDetailWithRealExtras() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/DirectoryActivity.java");
        int row = src.indexOf("private View employeeRow(");
        String body = src.substring(row);
        assertTrue("rows open MemberDetailActivity",
                body.contains("MemberDetailActivity.class"));
        assertTrue("rows pass the real employee id",
                body.contains("EXTRA_EMPLOYEE_ID, e.id"));
        assertTrue("guard against invalid ids",
                body.contains("DirectoryDataClient.openable(e)"));
    }

    @Test
    public void memberDetailFetchesExistingShowEndpoint() throws IOException {
        String src = read("app/src/main/java/dev/drytis/qolab/MemberDetailActivity.java");
        assertTrue("fetches GET /directory/{id}",
                src.contains("transport.getJson(\"/directory/\" + employeeId"));
        assertTrue("parses via the pure client",
                src.contains("DirectoryDataClient.parseDetail(inertia)"));
        assertTrue("error + Retry", src.contains("private void showError()"));
        assertTrue("skeleton while loading", src.contains("private void showLoading()"));
        assertTrue("SessionGate verifies dead transport",
                src.contains("SessionGate.requireValidSession"));
    }

    @Test
    public void directoryLayoutAndStringsExist() throws IOException {
        assertTrue("directory layout exists",
                new java.io.File(System.getProperty("user.dir"),
                        "src/main/res/layout/activity_directory.xml").exists());
        assertTrue("member detail layout exists",
                new java.io.File(System.getProperty("user.dir"),
                        "src/main/res/layout/activity_member_detail.xml").exists());
        String layout = read("app/src/main/res/layout/activity_directory.xml");
        assertTrue(layout.contains("@+id/directory_skeleton"));
        assertTrue(layout.contains("@+id/directory_error_box"));
        assertTrue(layout.contains("@+id/directory_retry"));
        assertTrue(layout.contains("@+id/directory_empty"));
        assertTrue(layout.contains("@+id/directory_no_results"));
        assertTrue(layout.contains("@+id/directory_search"));
        String strings = read("app/src/main/res/values/qm_directory_strings.xml");
        assertTrue(strings.contains("directory_search_hint"));
        assertTrue(strings.contains("directory_no_results"));
        assertTrue(strings.contains("directory_error_no_department_msg"));
    }
}
