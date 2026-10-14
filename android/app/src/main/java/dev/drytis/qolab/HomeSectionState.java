package dev.drytis.qolab;

/**
 * v1.5.44 — pure per-section visibility rules for HomeActivity.
 *
 * BUG being fixed (confirmed on BlueStacks at v1.5.43): a transient
 * background-refresh failure ran showError() while valid content was on
 * screen. showError() tore down the rendered Social Rooms row
 * (roomsRow.removeAllViews()), hid the Continue Working section and
 * overlaid "Couldn't load / Retry" on the Meeting/Department cards — while
 * this.data and the session-scoped cache still held the valid data. Worse,
 * the render methods never cleared sibling error/empty views, so after a
 * successful Retry a valid card and a stale error could show SIMULTANEOUSLY
 * (the screenshot's "valid content + error messages at once").
 *
 * The rules below make each section's four states — CONTENT, EMPTY, ERROR,
 * SKELETON — mutually exclusive, and make REFRESH FAILURE with retained
 * data a distinct, non-blocking outcome that preserves CONTENT.
 *
 * Pure JVM logic: no Android imports, fully unit-testable.
 */
public final class HomeSectionState {

    /** The four mutually-exclusive visuals of a Home section. */
    public enum Visual {
        CONTENT,   // the real data card / row
        EMPTY,     // approved empty state (valid data, nothing to show)
        ERROR,     // full error state + Retry (nothing else to show)
        SKELETON   // initial loading placeholder
    }

    private HomeSectionState() {}

    /**
     * Which visual a section should show after a data load settled.
     *
     * @param loadSucceeded  the composite fetch delivered a payload
     * @param sectionHasData this section has real data (meeting != null,
     *                       rooms non-empty, department != null, …)
     */
    public static Visual resolved(boolean loadSucceeded, boolean sectionHasData) {
        if (loadSucceeded) {
            return sectionHasData ? Visual.CONTENT : Visual.EMPTY;
        }
        // A failed load never fabricates an "empty account": error state.
        // (Activity keeps content only when it retained earlier data —
        // see refreshFailure(); this method is the data == null case.)
        return Visual.ERROR;
    }

    /**
     * Outcome of a REFRESH failure (a load that started while valid data
     * from a previous successful load was already on screen).
     *
     * @param retainedData this.data != null at failure time — earlier
     *                     content is still rendered / cacheable
     * @return true when the previously rendered content MUST be preserved
     *         and the failure surfaced as a small non-blocking warning;
     *         false when there is nothing to preserve (full error state)
     */
    public static boolean refreshFailureKeepsContent(boolean retainedData) {
        return retainedData;
    }

    /**
     * Whether the rendered Social Rooms row survives a refresh failure.
     * The row must NOT be cleared (removeAllViews) when it is showing
     * previously-loaded real rooms.
     */
    public static boolean keepRoomsRowOnRefreshFailure(int renderedRoomCount,
                                                       boolean retainedData) {
        return retainedData && renderedRoomCount > 0;
    }
}
