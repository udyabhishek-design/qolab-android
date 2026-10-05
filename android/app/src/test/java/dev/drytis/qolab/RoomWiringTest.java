package dev.drytis.qolab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Wiring invariants for the native Engineering Room (v1.5.28):
 *  - RoomActivity + MeetingDetailActivity registered in the manifest
 *  - Office opens the room natively (no showComingSoon on the Enter path)
 *  - DEV version stamp fully removed from layouts
 *  - room fetch path uses the existing transport with the real route
 */
public class RoomWiringTest {

    private static final String APP_MAIN = "src/main";

    private static String read(String rel) throws Exception {
        Path p = new File(rel).toPath();
        assertTrue("missing file: " + rel, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    public void manifest_registersRoomAndMeetingDetailActivities() throws Exception {
        String mf = read(APP_MAIN + "/AndroidManifest.xml");
        assertTrue(mf.contains("android:name=\".RoomActivity\""));
        assertTrue(mf.contains("android:name=\".MeetingDetailActivity\""));
    }

    @Test
    public void officeOpensRoomNatively_viaCardAndEnter() throws Exception {
        String src = read(APP_MAIN + "/java/dev/drytis/qolab/OfficeActivity.java");
        assertTrue("deptEnter must open RoomActivity",
                src.contains("deptEnter.setOnClickListener(v -> openDepartmentRoom(dept))"));
        assertTrue("dept card must open RoomActivity",
                src.contains("deptCard.setOnClickListener(v -> openDepartmentRoom(dept))"));
        assertTrue(src.contains("new Intent(this, RoomActivity.class)"));
        // the old Coming Soon fallback must be gone from the primary path
        assertFalse("department Enter must no longer be Coming Soon",
                src.contains("deptEnter.setOnClickListener(v ->\n                showComingSoon"));
    }

    @Test
    public void devStampRemovedFromLayouts() throws Exception {
        try (Stream<Path> walk = Files.walk(new File(APP_MAIN + "/res/layout").toPath())) {
            List<String> layouts = walk
                    .map(Path::toString)
                    .filter(f -> f.endsWith(".xml"))
                    .collect(Collectors.toList());
            for (String layout : layouts) {
                String src = new String(Files.readAllBytes(new File(layout).toPath()), StandardCharsets.UTF_8);
                assertFalse("office_dev_stamp must be gone: " + layout,
                        src.contains("office_dev_stamp"));
            }
        }
        String office = read(APP_MAIN + "/java/dev/drytis/qolab/OfficeActivity.java");
        assertFalse("stampDevVersion must be gone", office.contains("stampDevVersion"));
    }

    @Test
    public void roomFetchUsesRealExistingRoutes() throws Exception {
        String room = read(APP_MAIN + "/java/dev/drytis/qolab/RoomActivity.java");
        assertTrue(room.contains("\"/office/rooms/\" + roomSlug"));
        assertFalse("room must never load a fake/hardcoded fixture",
                room.contains("example.com"));

        String detail = read(APP_MAIN + "/java/dev/drytis/qolab/MeetingDetailActivity.java");
        assertTrue(detail.contains("\"/meetings/\" + meetingId + \"/detail\""));
    }

    @Test
    public void roomNeverOpensWebOrChat() throws Exception {
        String room = read(APP_MAIN + "/java/dev/drytis/qolab/RoomActivity.java");
        String detail = read(APP_MAIN + "/java/dev/drytis/qolab/MeetingDetailActivity.java");
        for (String src : new String[]{room, detail}) {
            assertFalse("must never call WebView loadUrl", src.contains(".loadUrl("));
            assertFalse("must never construct a WebView view", src.contains("new WebView"));
        }
    }
}
