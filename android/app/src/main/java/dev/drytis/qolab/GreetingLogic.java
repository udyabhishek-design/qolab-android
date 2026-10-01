package dev.drytis.qolab;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;

/**
 * Greeting logic for the native Home — pure, unit-testable.
 *
 * Everything derives from the DEVICE LOCAL time and the AUTHENTICATED user's
 * name. Nothing is hardcoded: no fixed date, no fixed greeting word, no
 * default user name.
 */
public final class GreetingLogic {

    /** Morning before 12:00, Afternoon 12:00–16:59, Evening 17:00 onward. */
    public static String greetingWord(int hour24) {
        if (hour24 < 12) return "Good Morning";
        if (hour24 < 17) return "Good Afternoon";
        return "Good Evening";
    }

    /**
     * First name for the greeting: leading token of the full name. A
     * single-token name is used as-is; null/empty stays null (caller shows a
     * generic greeting without a name).
     */
    public static String firstName(String fullName) {
        if (fullName == null) return null;
        String trimmed = fullName.trim();
        if (trimmed.isEmpty()) return null;
        int sp = trimmed.indexOf(' ');
        String first = sp > 0 ? trimmed.substring(0, sp) : trimmed;
        return first.isEmpty() ? null : first;
    }

    /** e.g. "Thu, Oct 1" — device locale, device time. */
    public static String dateLine(int year, int month0, int day) {
        Calendar cal = new GregorianCalendar(year, month0, day);
        String dayAbbr = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault());
        String monthAbbr = cal.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault());
        if (dayAbbr == null || monthAbbr == null) {
            return String.format(Locale.getDefault(), "%d/%d/%d", month0 + 1, day, year);
        }
        return String.format(Locale.getDefault(), "%s, %s %d", dayAbbr, monthAbbr, day);
    }

    /** Greeting line: "Good Morning," — caller appends the name. */
    public static String greetingLine(int hour24) {
        return greetingWord(hour24) + ",";
    }

    private GreetingLogic() {}
}
