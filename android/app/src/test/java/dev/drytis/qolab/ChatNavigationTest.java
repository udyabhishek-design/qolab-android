package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * v1.5.39 — Navigation regression tests:
 *   1. Bottom nav is Home | Chat | Ask Drytis — Office removed from the
 *      layout resource (id home_nav_office must no longer exist).
 *   2. Social Rooms & Department Room entry points remain on Home
 *      (ids home_section_rooms / home_section_department stay present).
 *   3. ChatListActivity/ChatScreenActivity are registered in the manifest.
 *   4. Chat excludes rooms: parser contract (rooms skipped) is covered in
 *      ChatDataClientTest; here we assert the layout carries no room list.
 */
public class ChatNavigationTest {

    private static final String NAV =
            "android/app/src/main/res/layout/view_home_bottom_nav.xml";

    @Test
    public void bottomNavHasNoOfficeTab() throws Exception {
        String xml = read(NAV);
        assertFalse("Office must not appear in the bottom navigation",
                xml.contains("home_nav_office"));
    }

    @Test
    public void bottomNavKeepsHomeChatAndAskDrytis() throws Exception {
        String xml = read(NAV);
        assertTrue(xml.contains("home_nav_home"));
        assertTrue(xml.contains("home_nav_chat"));
        assertTrue(xml.contains("home_nav_ask_drytis"));
    }

    @Test
    public void homeKeepsRoomsAndDepartmentSections() throws Exception {
        String xml = read("android/app/src/main/res/layout/activity_home.xml");
        assertTrue("Social Rooms section must stay on Home",
                xml.contains("home_section_rooms"));
        assertTrue("Department section must stay on Home",
                xml.contains("home_section_department"));
    }

    @Test
    public void homeActivityNoLongerBindsOfficeNav() throws Exception {
        String src = read(
                "android/app/src/main/java/dev/drytis/qolab/HomeActivity.java");
        assertFalse("navOffice field must be gone",
                src.contains("navOffice"));
        assertTrue("Chat must be wired to ChatListActivity",
                src.contains("ChatListActivity.class"));
        assertTrue("Social rooms open the native Lobby (same as Office did)",
                src.contains("LobbyActivity.class"));
    }

    @Test
    public void officeCodeIsPreservedNotDeleted() throws Exception {
        // The task: do NOT delete Office code/backend. The activity and its
        // layout stay in the tree (reachable from nothing in primary nav).
        assertTrue(readable("android/app/src/main/java/dev/drytis/qolab/OfficeActivity.java"));
        assertTrue(readable("android/app/src/main/res/layout/activity_office.xml"));
        String manifest = read("android/app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("OfficeActivity"));
    }

    @Test
    public void chatActivitiesAreRegistered() throws Exception {
        String manifest = read("android/app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("ChatListActivity"));
        assertTrue(manifest.contains("ChatScreenActivity"));
    }

    @Test
    public void chatListLayoutHasSearchUnreadAndList() throws Exception {
        String xml = read("android/app/src/main/res/layout/activity_chat_list.xml");
        assertTrue(xml.contains("chat_search"));
        assertTrue(xml.contains("chat_list"));
        String row = read("android/app/src/main/res/layout/view_chat_row.xml");
        assertTrue("unread badge on the row", row.contains("chat_row_unread"));
        // no room tabs in Chat (rooms are NOT part of Chat)
        assertFalse(xml.contains("lobby_tab_"));
        assertFalse(xml.contains("@layout/view_room_card"));
    }

    private static String read(String path) throws Exception {
        java.io.File f = new java.io.File(
                System.getProperty("user.dir") + "/../" + path);
        // unit tests run in android/app — normalize to repo root
        if (!f.exists()) {
            f = new java.io.File(path);
        }
        if (!f.exists()) {
            f = new java.io.File("/home/coder/qolab-android/" + path);
        }
        byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
        return new String(b, "UTF-8");
    }

    private static boolean readable(String path) {
        return new java.io.File("/home/coder/qolab-android/" + path).exists()
                || new java.io.File(path).exists();
    }
}
