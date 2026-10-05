package dev.drytis.qolab;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * v1.5.29 UI-refinement wiring tests (source-level contract checks —
 * visual pass only, data/fetching must be untouched).
 */
public class RoomUiRefineTest {

    private static final String APP = "android/app/src/main";
    private static final String JAVA = APP + "/java/dev/drytis/qolab";

    private static String read(String rel) throws Exception {
        Path cwd = Paths.get("").toAbsolutePath();
        // walk up until we find settings.gradle of the android module + the rel path
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path p = dir.resolve(rel).normalize();
            if (Files.exists(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        }
        throw new java.io.FileNotFoundException(rel + " (searched from " + cwd + ")");
    }

    // Overview: compact people section
    @Test
    public void overviewCapsMemberRowsAtThreeAndViewAllAffordance() throws Exception {
        String src = read(JAVA + "/RoomActivity.java");
        assertTrue(src.contains("room_view_all_members"));
        assertTrue(src.contains("if (rows >= 3) break;"));
        assertTrue(src.contains("page.members.size() > 3"));
    }

    @Test
    public void memberRowOmitsRepeatedDepartment() throws Exception {
        String src = read(JAVA + "/RoomActivity.java");
        assertTrue(src.contains("memberRow"));
        // department is not joined into the compact row detail line
        assertTrue(!src.contains("joinNonNull(\" · \", roleLabel, m.jobTitle, m.departmentName)"));
    }

    // Summary: action items never render raw slugs / literal null
    @Test
    public void taskRowUsesHumanLabelsAndOmitsMissingFields() throws Exception {
        String src = read(JAVA + "/MeetingDetailActivity.java");
        assertTrue(src.contains("RoomFormat.statusLabel(t.status)"));
        assertTrue(src.contains("RoomFormat.statusLabel(t.priority)"));
        assertTrue(src.contains("RoomFormat.formatDueDate(t.dueDate)"));
        assertTrue(src.contains("room_task_assigned_to"));
        // no raw pass-through
        assertTrue(!src.contains("status.setText(t.status != null ? t.status : \"\")"));
        assertTrue(!src.contains("priority.setText(t.priority)"));
    }

    // Transcript: no raw ISO timestamps
    @Test
    public void sessionDateIsHumanFormatted() throws Exception {
        String src = read(JAVA + "/MeetingDetailActivity.java");
        assertTrue(src.contains("RoomFormat.formatIsoDate(s.scheduledAt)"));
        assertTrue(!src.contains("setText(s.scheduledAt != null ? s.scheduledAt : \"\")"));
    }

    // Header: compact toolbar title, full title in Summary facts card
    @Test
    public void toolbarTitleSingleLine_fullTitleInSummaryCard() throws Exception {
        String layout = read(APP + "/res/layout/activity_meeting_detail.xml");
        assertTrue(layout.contains("android:maxLines=\"1\""));
        String summary = read(APP + "/res/layout/meeting_detail_tab_summary.xml");
        assertTrue(summary.contains("meeting_summary_title_full"));
        String src = read(JAVA + "/MeetingDetailActivity.java");
        assertTrue(src.contains("meeting_summary_title_full"));
    }

    // Data layer untouched by this pass
    @Test
    public void dataClientUnchanged_noNewNetworkCalls() throws Exception {
        String src = read(JAVA + "/MeetingDetailActivity.java");
        assertTrue(src.contains("transport.getJson(\"/meetings/\" + meetingId + \"/detail\""));
        String room = read(JAVA + "/RoomActivity.java");
        assertTrue(room.contains("transport.getJson(\"/office/rooms/\" + roomSlug"));
    }

    // Decisions rendered as marker rows, not plain bullets
    @Test
    public void decisionsUseMarkerRowsFollowUpsPlain() throws Exception {
        String src = read(JAVA + "/MeetingDetailActivity.java");
        assertTrue(src.contains("detail.keyDecisions, true"));
        assertTrue(src.contains("detail.followUps, false"));
    }
}
