package dev.drytis.qolab;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * v1.5.30 — Lobby wiring tests (source-level contract checks).
 */
public class LobbyWiringTest {

    private static final String APP = "android/app/src/main";
    private static final String JAVA = APP + "/java/dev/drytis/qolab";

    private static String read(String rel) throws Exception {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path p = dir.resolve(rel).normalize();
            if (Files.exists(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        }
        throw new java.io.FileNotFoundException(rel + " (searched from " + cwd + ")");
    }

    @Test
    public void manifestRegistersLobbyActivity() throws Exception {
        String mf = read(APP + "/AndroidManifest.xml");
        assertTrue(mf.contains(".LobbyActivity"));
    }

    @Test
    public void officeLobbyCardOpensNativeLobbyWithRealIdentity() throws Exception {
        String src = read(JAVA + "/OfficeActivity.java");
        assertTrue(src.contains("new Intent(this, LobbyActivity.class)"));
        assertTrue(src.contains("LobbyActivity.EXTRA_ROOM_ID, room.id"));
        assertTrue(src.contains("LobbyActivity.EXTRA_ROOM_SLUG, room.roomSlug"));
        // identity gated on the theme from real Office data, not a hardcoded id
        assertTrue(src.contains("\"lobby\".equals(room.roomTheme)"));
    }

    @Test
    public void lobbyUsesExistingEndpointsOnly() throws Exception {
        // v1.5.35: messaging lives in the SHARED ChannelDiscussionView; the
        // Lobby delegates to it and keeps only the members fetch.
        String src = read(JAVA + "/LobbyActivity.java");
        String shared = read(JAVA + "/ChannelDiscussionView.java");
        assertTrue(src.contains("new ChannelDiscussionView(this, channelId"));
        assertTrue(shared.contains("transport.getJson(\"/chat/channels/\" + channelId + \"/messages"));
        assertTrue(shared.contains("transport.postJson(\"/chat/channels/\" + channelId + \"/messages\""));
        assertTrue(src.contains("transport.getJson(\"/office/rooms/\" + roomSlug"));
    }

    @Test
    public void lobbyNeverOpensWebOrChatUi() throws Exception {
        String src = read(JAVA + "/LobbyActivity.java");
        assertTrue(!src.contains("loadUrl"));
        assertTrue(!src.contains("WebView "));
    }

    @Test
    public void composerNeverSendsEmptyBody() throws Exception {
        String src = read(JAVA + "/ChannelDiscussionView.java");
        assertTrue(src.contains("if (text.isEmpty()) return;"));
        assertTrue(src.contains("payload.put(\"body\", text)"));
    }

    @Test
    public void threadsOpenNativelyOnlyWhenRepliesExist() throws Exception {
        String src = read(JAVA + "/ChannelDiscussionView.java");
        assertTrue(src.contains("if (!m.replies.isEmpty())"));
        assertTrue(src.contains("openThread(m)"));
    }

    @Test
    public void noVoiceOrCallUi() throws Exception {
        String layout = read(APP + "/res/layout/activity_lobby.xml");
        String src = read(JAVA + "/LobbyActivity.java");
        assertTrue(!layout.toLowerCase().contains("call"));
        assertTrue(!src.contains("room-call"));
        assertTrue(!src.contains("/call/"));
    }

    @Test
    public void memberRowNeverRendersLiteralNull() throws Exception {
        String src = read(JAVA + "/LobbyActivity.java");
        assertTrue(src.contains("joinNonNull(\" · \", humanRole(m.role), m.jobTitle)"));
        assertTrue(src.contains("NEVER \"Member · null\""));
    }

    @Test
    public void roomDataClientCleansLiteralNullMembers() throws Exception {
        String src = read(JAVA + "/RoomDataClient.java");
        assertTrue(src.contains("clean(m.optString(\"job_title\", null))"));
        assertTrue(src.contains("\"null\".equals(t)"));
    }

    @Test
    public void transportPostJsonStaysInWebViewBridge() throws Exception {
        String src = read(JAVA + "/QolabWebViewTransport.java");
        assertTrue(src.contains("public void postJson"));
        // XSRF header is built inside the page — token never crosses the bridge.
        // (No active Java cookie reads: no CookieManager.getInstance().getCookie call.)
        assertTrue(src.contains("XSRF-TOKEN=([^;]+)"));
        assertTrue(src.contains("X-XSRF-TOKEN"));
        assertTrue(!src.contains("CookieManager.getInstance()"));
    }
}
