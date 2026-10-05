package dev.drytis.qolab;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * v1.5.26 regression guard for the Office "Social Rooms heading but no room
 * cards" bug: showLoading() hides the data containers (roomsRow, deptCard);
 * renderAll() MUST make them visible again, or rendered cards stay GONE.
 * Source-invariant test (JVM-only, no device needed).
 */
public class OfficeRenderVisibilityTest {

    private static String officeSource() throws Exception {
        return new String(Files.readAllBytes(
                new File("src/main/java/dev/drytis/qolab/OfficeActivity.java").toPath()),
                StandardCharsets.UTF_8);
    }

    @Test
    public void renderAllShowsDataContainers() throws Exception {
        String src = officeSource();
        String renderAll = extractMethod(src, "private void renderAll()");
        assertTrue("renderAll() must call show(roomsRow, deptCard) — otherwise the "
                        + "containers hidden by showLoading() stay GONE (v1.5.26 bug)",
                renderAll.contains("show(roomsRow, deptCard)"));
    }

    @Test
    public void renderRoomsControlsRowVisibilityOnEveryPath() throws Exception {
        String src = officeSource();
        String renderRooms = extractMethod(src, "private void renderRooms()");
        assertTrue("renderRooms() data path must show(roomsRow)",
                renderRooms.contains("show(roomsRow)"));
        assertTrue("renderRooms() empty path must hide(roomsRow)",
                renderRooms.contains("hide(roomsRow)"));
    }

    @Test
    public void renderDepartmentControlsCardVisibilityOnEveryPath() throws Exception {
        String src = officeSource();
        String dept = extractMethod(src, "private void renderDepartment()");
        assertTrue("renderDepartment() data path must show(deptCard)",
                dept.contains("show(deptCard)"));
        assertTrue("renderDepartment() empty path must hide(deptCard)",
                dept.contains("hide(deptCard"));
    }

    private static String extractMethod(String src, String signature) {
        int i = src.indexOf(signature);
        if (i < 0) return "";
        int brace = src.indexOf('{', i);
        int depth = 0;
        for (int j = brace; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(i, j + 1);
            }
        }
        return src.substring(i);
    }
}
