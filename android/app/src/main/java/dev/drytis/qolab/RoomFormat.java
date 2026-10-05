package dev.drytis.qolab;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Presentation-only formatting helpers for the native Room screens
 * (v1.5.29). No data/contract changes — input values are exactly what
 * the existing parsers produce; this class only renders them for humans.
 *
 *  - statusLabel: backend enum values → human-readable labels
 *  - formatIsoDate: raw ISO-8601 strings → "Oct 2, 2026 · 2:30 PM"
 *  - duration: seconds → "45m" / "1h 05m"
 */
public final class RoomFormat {

    private RoomFormat() {}

    private static final Map<String, String> STATUS_LABELS = buildStatusLabels();

    private static Map<String, String> buildStatusLabels() {
        Map<String, String> m = new HashMap<>();
        // meeting statuses
        m.put("scheduled", "Scheduled");
        m.put("live", "Live");
        m.put("ended", "Ended");
        m.put("cancelled", "Cancelled");
        // task statuses (TaskService::formatTask)
        m.put("task-pool", "Task pool");
        m.put("to-do", "To do");
        m.put("in-progress", "In progress");
        m.put("in-review", "In review");
        m.put("completed", "Completed");
        m.put("cancelled", "Cancelled");
        // priorities
        m.put("urgent", "Urgent");
        m.put("high", "High");
        m.put("normal", "Normal");
        m.put("low", "Low");
        return m;
    }

    /** "task-pool" → "Task pool"; null/unknown → null (caller omits). */
    public static String statusLabel(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String label = STATUS_LABELS.get(raw);
        if (label != null) return label;
        // unknown value: title-case words instead of showing raw slug
        String[] words = raw.trim().split("[-_]");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Formats an ISO-8601 timestamp ("2026-10-02T14:00:00.000000Z" or
     * "...+05:30") as "Oct 2, 2026 · 2:30 PM" in the local zone.
     * Returns null when unparseable (caller omits rather than showing raw).
     */
    public static String formatIsoDate(String iso) {
        if (iso == null || iso.trim().isEmpty()) return null;
        String s = iso.trim().replace(' ', 'T');
        // strip microseconds — SimpleDateFormat can't handle them
        if (s.contains(".")) {
            int dot = s.indexOf('.');
            int end = dot + 1;
            while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
            String tail = s.substring(end);
            s = s.substring(0, dot) + tail;
        }
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ssZ",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat in = new SimpleDateFormat(pattern, Locale.US);
                if (pattern.endsWith("'Z'")) in.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = in.parse(s);
                if (d == null) continue;
                SimpleDateFormat out = new SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault());
                if (pattern.equals("yyyy-MM-dd")) {
                    out = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault());
                }
                return out.format(d);
            } catch (ParseException ignored) {
            }
        }
        return null;
    }

    /** 2700 → "45m"; 5400 → "1h 30m"; <=0 → null. */
    public static String duration(long seconds) {
        if (seconds <= 0) return null;
        long m = seconds / 60;
        if (m < 60) return m + "m";
        return (m / 60) + "h " + String.format(Locale.US, "%02dm", m % 60);
    }

    /** "2026-10-10" → "Oct 10, 2026"; null/unparseable → null. */
    public static String formatDateOnly(String ymd) {
        if (ymd == null || ymd.trim().isEmpty()) return null;
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date d = in.parse(ymd.trim());
            if (d == null) return null;
            SimpleDateFormat out = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault());
            return out.format(d);
        } catch (ParseException e) {
            return null;
        }
    }

    /**
     * Due-date value → human label. Accepts ISO timestamps ("2026-10-02T…Z"
     * → "Due Oct 2, 2026 · 2:00 PM") and bare dates ("2026-10-10" →
     * "Due Oct 10, 2026"). Unparseable/null → null (caller omits).
     */
    public static String formatDueDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        if (raw.contains("T")) {
            String f = formatIsoDate(raw);
            return f != null ? "Due " + f : null;
        }
        String d = formatDateOnly(raw);
        return d != null ? "Due " + d : null;
    }
}
