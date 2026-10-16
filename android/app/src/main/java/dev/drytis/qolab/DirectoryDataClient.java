package dev.drytis.qolab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v1.5.53 — pure, JVM-testable parser for the native Directory screen.
 *
 * Source contract — the EXISTING Employee Directory endpoints (no backend
 * change, exactly what the web Directory pages receive):
 *   GET /directory?search=&page=N  → Inertia Directory/Index with props
 *        employees{ data[], meta{current_page,last_page,total} }
 *        row: id, name, email, avatar_path, job_title,
 *             department{id,name}|null, phone, shift, timezone,
 *             current_status, last_active_at
 *   GET /directory/{id}            → Inertia Directory/Show with props
 *        employee{ id, name, email, avatar_path, job_title,
 *                  department{id,name}|null, phone, shift, timezone, bio,
 *                  languages[], current_status, last_active_at, joined_at },
 *        is_self
 *
 * This class contains NO network code — DirectoryActivity fetches via
 * QolabWebViewTransport and hands raw strings to these parsers.
 */
public final class DirectoryDataClient {

    private DirectoryDataClient() { }

    public static final class Employee {
        public final long id;
        public final String name;
        public final String email;
        public final String jobTitle;          // designation; may be empty
        public final String departmentName;    // may be null
        public final String currentStatus;     // online | away | offline | …
        public final boolean self;

        Employee(long id, String name, String email, String jobTitle,
                 String departmentName, String currentStatus, boolean self) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.jobTitle = jobTitle;
            this.departmentName = departmentName;
            this.currentStatus = currentStatus;
            this.self = self;
        }
    }

    public static final class DirectoryPage {
        public final List<Employee> employees;
        public final int currentPage;   // 1-based
        public final int lastPage;      // 1-based
        public final int total;

        DirectoryPage(List<Employee> employees, int currentPage, int lastPage, int total) {
            this.employees = employees;
            this.currentPage = currentPage;
            this.lastPage = lastPage;
            this.total = total;
        }
    }

    public static final class EmployeeDetail {
        public final Employee employee; // shared shape
        public final String bio;
        public final List<String> languages;
        public final String shift;
        public final String timezone;
        public final String joinedAt;  // ISO or null
        public final String lastActiveAt;

        EmployeeDetail(Employee employee, String bio, List<String> languages,
                       String shift, String timezone, String joinedAt,
                       String lastActiveAt) {
            this.employee = employee;
            this.bio = bio;
            this.languages = languages;
            this.shift = shift;
            this.timezone = timezone;
            this.joinedAt = joinedAt;
            this.lastActiveAt = lastActiveAt;
        }
    }

    /** Parses the /directory Inertia page JSON (already extracted via
     *  {@link RoomDataClient#extractInertiaPage}). Null when the payload
     *  does not carry the employees collection. */
    public static DirectoryPage parsePage(JSONObject page) {
        if (page == null) return null;
        JSONObject props = page.optJSONObject("props");
        if (props == null) return null;
        JSONObject employees = props.optJSONObject("employees");
        if (employees == null) return null;
        List<Employee> rows = new ArrayList<>();
        JSONArray data = employees.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject e = data.optJSONObject(i);
                if (e == null) continue;
                Employee row = employee(e, false);
                if (row != null) rows.add(row);
            }
        }
        JSONObject meta = employees.optJSONObject("meta");
        int current = meta != null ? Math.max(1, meta.optInt("current_page", 1)) : 1;
        int last = meta != null ? Math.max(1, meta.optInt("last_page", 1)) : 1;
        int total = meta != null ? meta.optInt("total", rows.size()) : rows.size();
        return new DirectoryPage(rows, current, last, total);
    }

    /** Parses the /directory/{id} Inertia page JSON. Null when the
     *  employee prop is missing. */
    public static EmployeeDetail parseDetail(JSONObject page) {
        if (page == null) return null;
        JSONObject props = page.optJSONObject("props");
        if (props == null) return null;
        JSONObject e = props.optJSONObject("employee");
        if (e == null) return null;
        boolean self = props.optBoolean("is_self", false);
        Employee base = employee(e, self);
        if (base == null) return null;
        List<String> langs = new ArrayList<>();
        JSONArray arr = e.optJSONArray("languages");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String l = arr.optString(i, null);
                if (l != null && !l.isEmpty()) langs.add(l);
            }
        }
        return new EmployeeDetail(base,
                optOrNull(e, "bio"),
                langs,
                optOrNull(e, "shift"),
                optOrNull(e, "timezone"),
                optOrNull(e, "joined_at"),
                optOrNull(e, "last_active_at"));
    }

    /** One employee row; null when the row lacks an id (unusable). */
    public static Employee employee(JSONObject e, boolean self) {
        if (e == null || !e.has("id")) return null;
        JSONObject dept = e.optJSONObject("department");
        String deptName = dept != null ? optOrNull(dept, "name") : null;
        return new Employee(
                e.optLong("id", 0),
                orEmpty(optOrNull(e, "name")),
                orEmpty(optOrNull(e, "email")),
                orEmpty(optOrNull(e, "job_title")),
                deptName,
                optOrNull(e, "current_status"),
                self);
    }

    /** Case-insensitive name contains-match; empty query matches all. */
    public static boolean matches(Employee e, String query) {
        if (e == null) return false;
        if (query == null || query.trim().isEmpty()) return true;
        String q = query.trim().toLowerCase(Locale.ROOT);
        String n = e.name == null ? "" : e.name.toLowerCase(Locale.ROOT);
        return n.contains(q);
    }

    /** True when the employee can be opened on the detail screen. */
    public static boolean openable(Employee e) {
        return e != null && e.id > 0;
    }

    private static String optOrNull(JSONObject o, String key) {
        return o != null && !o.isNull(key) ? o.optString(key, null) : null;
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }
}
