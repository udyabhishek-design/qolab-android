package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * v1.5.29 presentation-formatting contract tests:
 *  - never render literal "null" or raw backend slugs
 *  - never expose raw ISO timestamps
 *  - missing optional fields → null (omitted by callers)
 */
public class RoomFormatTest {

    // ── statusLabel ──────────────────────────────────────────────────

    @Test
    public void taskPoolSlugBecomesHumanLabel() {
        assertEquals("Task pool", RoomFormat.statusLabel("task-pool"));
    }

    @Test
    public void taskStatusesBecomeHumanLabels() {
        assertEquals("To do", RoomFormat.statusLabel("to-do"));
        assertEquals("In progress", RoomFormat.statusLabel("in-progress"));
        assertEquals("In review", RoomFormat.statusLabel("in-review"));
        assertEquals("Completed", RoomFormat.statusLabel("completed"));
    }

    @Test
    public void meetingStatusesBecomeHumanLabels() {
        assertEquals("Scheduled", RoomFormat.statusLabel("scheduled"));
        assertEquals("Live", RoomFormat.statusLabel("live"));
        assertEquals("Ended", RoomFormat.statusLabel("ended"));
        assertEquals("Cancelled", RoomFormat.statusLabel("cancelled"));
    }

    @Test
    public void prioritiesBecomeHumanLabels() {
        assertEquals("Urgent", RoomFormat.statusLabel("urgent"));
        assertEquals("High", RoomFormat.statusLabel("high"));
        assertEquals("Normal", RoomFormat.statusLabel("normal"));
        assertEquals("Low", RoomFormat.statusLabel("low"));
    }

    @Test
    public void nullAndEmptyStatusReturnNullNotLiteral() {
        assertNull(RoomFormat.statusLabel(null));
        assertNull(RoomFormat.statusLabel(""));
        assertNull(RoomFormat.statusLabel("   "));
    }

    @Test
    public void unknownSlugIsTitleCasedNotRaw() {
        assertEquals("Some New Status", RoomFormat.statusLabel("some_new-status"));
    }

    // ── formatIsoDate ────────────────────────────────────────────────

    @Test
    public void rawIsoUtcTimestampBecomesHumanDate() {
        String out = RoomFormat.formatIsoDate("2026-10-02T14:00:00.000000Z");
        assertNotNull(out);
        assertEquals(false, out.contains("T"));
        assertEquals(false, out.matches(".*\\d{4}-\\d{2}-\\d{2}.*")); // no raw date
        assertEquals(true, out.contains("2026"));
    }

    @Test
    public void isoWithOffsetParses() {
        assertNotNull(RoomFormat.formatIsoDate("2026-10-02T14:00:00+05:30"));
    }

    @Test
    public void plainDateTimeParses() {
        assertNotNull(RoomFormat.formatIsoDate("2026-10-02T14:00:00"));
    }

    @Test
    public void garbageReturnsNullNotRawString() {
        assertNull(RoomFormat.formatIsoDate("not-a-date"));
        assertNull(RoomFormat.formatIsoDate(null));
        assertNull(RoomFormat.formatIsoDate(""));
    }

    // ── duration ─────────────────────────────────────────────────────

    @Test
    public void durationFormatsMinutesAndHours() {
        assertEquals("45m", RoomFormat.duration(2700));
        assertEquals("1h 30m", RoomFormat.duration(5400));
        assertEquals("1h 05m", RoomFormat.duration(3900));
        assertNull(RoomFormat.duration(0));
        assertNull(RoomFormat.duration(-5));
    }

    // ── formatDateOnly / formatDueDate ───────────────────────────────

    @Test
    public void bareDateBecomesHumanDate() {
        String out = RoomFormat.formatDateOnly("2026-10-10");
        assertNotNull(out);
        assertEquals(true, out.contains("2026"));
        assertEquals(false, out.contains("2026-10-10"));
    }

    @Test
    public void dueDateFromTimestampGetsDuePrefix() {
        String out = RoomFormat.formatDueDate("2026-10-02T14:00:00.000000Z");
        assertNotNull(out);
        assertEquals(true, out.startsWith("Due "));
        assertEquals(false, out.contains("T14"));
    }

    @Test
    public void dueDateFromBareDateGetsDuePrefix() {
        assertEquals(true, RoomFormat.formatDueDate("2026-10-10").startsWith("Due "));
    }

    @Test
    public void nullDueDateReturnsNull() {
        assertNull(RoomFormat.formatDueDate(null));
        assertNull(RoomFormat.formatDueDate(""));
    }
}
