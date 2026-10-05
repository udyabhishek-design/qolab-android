package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import dev.drytis.qolab.RoomDataClient.MeetingDetail;

/**
 * v1.5.35 — meeting rejoin + department room chat wiring.
 *
 *  Backend lifecycle (code-verified): start-or-join-session reuses a
 *  live/scheduled occurrence and CREATES A NEW OCCURRENCE (same
 *  session_key) when the series has ended; cancelled is refused. can_join
 *  stays true after leave (== policy::view). So the Android action must be
 *  visible for ended meetings too, and the join must follow the id returned
 *  by start-or-join-session — that is exactly what the web does.
 */
public class MeetingRejoinTest {

    private static Path findRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path d = cwd; d != null; d = d.getParent()) {
            if (Files.exists(d.resolve(".git"))) return d;
        }
        return cwd;
    }

    private static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(findRoot().resolve("android").resolve(rel)),
                StandardCharsets.UTF_8);
    }

    // ── parser: occurrence resolution ────────────────────────────────

    @Test
    public void startOrJoinReturnsNewOccurrenceId() {
        String body = "{\"success\":true,\"meeting_id\":15,"
                + "\"studio_url\":\"https://studio.drytis.ai/meeting/mtg-86jkULTvoYk9O\","
                + "\"is_new_occurrence\":true}";
        assertEquals(15, RoomDataClient.parseStartOrJoin(body));
    }

    @Test
    public void startOrJoinReusesSameIdForLive() {
        String body = "{\"success\":true,\"meeting_id\":9,"
                + "\"studio_url\":\"https://studio.drytis.ai/meeting/mtg-x\","
                + "\"is_new_occurrence\":false}";
        assertEquals(9, RoomDataClient.parseStartOrJoin(body));
    }

    @Test
    public void startOrJoinRefusalYieldsZero() {
        assertEquals(0, RoomDataClient.parseStartOrJoin(
                "{\"success\":false,\"error\":\"This meeting series has been cancelled"
                + " and cannot be restarted.\"}"));
        assertEquals(0, RoomDataClient.parseStartOrJoin(null));
    }

    // ── action gating incl. the v1.5.35 rejoin fix ───────────────────

    @Test
    public void endedMeetingIsJoinablePerBackendContract() throws Exception {
        String src = read("app/src/main/java/dev/drytis/qolab/MeetingDetailActivity.java");
        // renderActions must accept scheduled, live AND ended (backend
        // creates a new occurrence on re-entry); cancelled stays out.
        assertTrue("ended must be a joinable status (rejoin contract)",
                src.contains("\"ended\".equals(status)"));
        assertTrue("cancelled must never be joinable",
                !src.contains("\"cancelled\".equals(status)\n"));
        // The rejoin flow must consult start-or-join-session FIRST and use
        // its resolved id for join-token — mirroring the web hook.
        // Compare the call-site line positions (comments mention the
        // endpoints earlier in the file).
        int sojs = src.indexOf("postJson(\"/meetings/\" + meetingId + \"/start-or-join-session\"");
        int jtok = src.indexOf("postJson(\"/meetings/\" + resolvedMeetingId + \"/join-token\"");
        assertTrue("must POST start-or-join-session before join-token",
                sojs >= 0 && jtok > sojs);

        assertTrue("join token must target the RESOLVED occurrence id",
                src.contains("requestJoinToken(resolvedId)"));
    }

    @Test
    public void detailRefreshesOnReturnFromLiveRoom() throws Exception {
        String src = read("app/src/main/java/dev/drytis/qolab/MeetingDetailActivity.java");
        assertTrue(src.contains("onResume"));
    }

    // ── chat tab: shared discussion surface ──────────────────────────

    @Test
    public void roomActivityHasChatTabOverSharedDiscussion() throws Exception {
        String room = read("app/src/main/java/dev/drytis/qolab/RoomActivity.java");
        assertTrue("Chat tab must be wired between Overview and Meetings",
                room.contains("room_tab_chat"));
        assertTrue("must reuse the shared ChannelDiscussionView",
                room.contains("new ChannelDiscussionView(this, parsed.channelId"));
        assertTrue("Chat must use the room's real channel id",
                room.contains("parsed.channelId"));
    }

    @Test
    public void lobbyUsesTheSameSharedDiscussionView() throws Exception {
        String lobby = read("app/src/main/java/dev/drytis/qolab/LobbyActivity.java");
        assertTrue(lobby.contains("new ChannelDiscussionView(this, channelId"));
        // No second messaging implementation may remain in LobbyActivity.
        assertFalse("Lobby must not keep its own getJson messages call",
                lobby.contains("getJson(\"/chat/channels/"));
        assertFalse("Lobby must not keep its own postJson send call",
                lobby.contains("postJson(\"/chat/channels/"));
    }

    @Test
    public void sharedDiscussionUsesCorrectedTransport() throws Exception {
        String shared = read("app/src/main/java/dev/drytis/qolab/ChannelDiscussionView.java");
        assertTrue("composer must use postJson (corrected v1.5.32 encoding)",
                shared.contains("postJson(\"/chat/channels/\" + channelId + \"/messages\""));
        assertTrue("feed must use the existing GET contract",
                shared.contains("getJson(\"/chat/channels/\" + channelId + \"/messages?per_page=50\""));
        assertFalse("no reactions implementation (contract has none)",
                shared.contains("addReaction"));
    }

    // ── cancelled stays non-joinable (parser level) ──────────────────

    @Test
    public void cancelledDetailParsesStatus() {
        MeetingDetail d = RoomDataClient.parseMeetingDetail(
                "{\"id\":12,\"title\":\"x\",\"status\":\"cancelled\",\"can_join\":true}");
        assertEquals("cancelled", d.status);
        assertTrue(d.canJoin);
        // Gating happens in renderActions (source test above); parser must
        // still surface the true backend fields, not second-guess them.
    }
}
