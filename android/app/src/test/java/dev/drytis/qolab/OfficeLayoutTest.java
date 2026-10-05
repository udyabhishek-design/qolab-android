package dev.drytis.qolab;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Office V1 layout invariants (parsed from the layout XML — JVM-only):
 *   - no HorizontalScrollView anywhere in the Office layouts (all rooms
 *     must be visible without horizontal scrolling)
 *   - core section ids exist (rooms grid + skeleton sibling, department
 *     card/empty/skeleton, people cards, quick access, bottom nav)
 */
public class OfficeLayoutTest {

    private static Document layout(String name) throws Exception {
        File f = new File("src/main/res/layout/" + name + ".xml");
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f);
    }

    private static NodeList byTag(Document d, String tag) {
        return d.getElementsByTagName(tag);
    }

    @Test
    public void officeLayoutsContainNoHorizontalScrollView() throws Exception {
        String[] layouts = {"activity_office", "office_section_header", "office_section_title",
                "office_section_rooms", "office_section_department", "office_section_people",
                "office_section_quick", "view_office_bottom_nav"};
        for (String name : layouts) {
            assertEquals("no HorizontalScrollView in " + name,
                    0, byTag(layout(name), "HorizontalScrollView").getLength());
        }
    }

    @Test
    public void officeRoomsGridHasRowAndSkeletonSibling() throws Exception {
        Document d = layout("office_section_rooms");
        assertTrue(hasId(d, "office_rooms_row"));
        assertTrue(hasId(d, "office_rooms_skeleton_row"));
        assertTrue(hasId(d, "office_rooms_empty"));
        assertTrue(hasId(d, "office_rooms_retry"));
    }

    @Test
    public void officeDepartmentHasCardEmptySkeletonStates() throws Exception {
        Document d = layout("office_section_department");
        assertTrue(hasId(d, "office_dept_card"));
        assertTrue(hasId(d, "office_dept_empty"));
        assertTrue(hasId(d, "office_dept_skeleton"));
        assertTrue(hasId(d, "office_dept_retry"));
        assertTrue(hasId(d, "office_dept_enter"));
    }

    @Test
    public void officePeopleAroundHasThreeStatusCards() throws Exception {
        Document d = layout("office_section_people");
        assertTrue(hasId(d, "office_people_online"));
        assertTrue(hasId(d, "office_people_meeting"));
        assertTrue(hasId(d, "office_people_offline"));
        assertTrue(hasId(d, "office_people_note"));
    }

    @Test
    public void officeQuickAccessHasFourCards() throws Exception {
        Document d = layout("office_section_quick");
        assertTrue(hasId(d, "office_quick_meetings"));
        assertTrue(hasId(d, "office_quick_tasks"));
        assertTrue(hasId(d, "office_quick_tickets"));
        assertTrue(hasId(d, "office_quick_directory"));
    }

    @Test
    public void officeBottomNavHasFourDestinations() throws Exception {
        Document d = layout("view_office_bottom_nav");
        assertTrue(hasId(d, "office_nav_home"));
        assertTrue(hasId(d, "office_nav_office"));
        assertTrue(hasId(d, "office_nav_ask_drytis"));
        assertTrue(hasId(d, "office_nav_chat"));
    }

    private static boolean hasId(Document d, String id) {
        NodeList nodes = d.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.NamedNodeMap attrs = nodes.item(i).getAttributes();
            if (attrs == null) continue;
            org.w3c.dom.Node idAttr = attrs.getNamedItem("android:id");
            if (idAttr != null && idAttr.getNodeValue().endsWith("/" + id)) {
                return true;
            }
        }
        return false;
    }
}
