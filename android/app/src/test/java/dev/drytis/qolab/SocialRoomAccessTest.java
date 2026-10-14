package dev.drytis.qolab;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/**
 * v1.5.43 regression guards for social-room access + the retired
 * /mobile/home endpoint.
 *
 * 1. Home AND Office must open ALL THREE approved social rooms (The
 *    Lobby, Music Lounge, Coffee Lounge) natively — the Lobby screen is
 *    a generic room screen (Discussion | Members) keyed by channel id +
 *    slug from the payload.
 * 2. No production code path may fetch /mobile/home anymore — the
 *    backend removed the route (404 with a valid session), which was the
 *    root cause of the permanent Home "Couldn't load" trio.
 * 3. The login session verifier must probe a live endpoint (/rooms).
 */
public class SocialRoomAccessTest {

    private static Path repoRoot() {
        Path d = new File(".").getAbsoluteFile().toPath().getParent();
        for (int i = 0; i < 8 && d != null; i++, d = d.getParent()) {
            if (Files.exists(d.resolve(".git"))) return d;
        }
        return new File(".").getAbsoluteFile().toPath().getParent();
    }

    private static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(
                repoRoot().resolve("android/app/src/main/java/dev/drytis/qolab/" + rel)));
    }

    @Test
    public void homeOpensAllThreeSocialRoomsNatively() throws Exception {
        String src = read("HomeActivity.java");
        int open = src.indexOf("private void openSocialRoom");
        assertTrue("openSocialRoom missing", open > 0);
        String body = src.substring(open, src.indexOf("private void openDepartmentRoom"));
        for (String theme : new String[]{"lobby", "music", "coffee"}) {
            assertTrue("Home must open the " + theme + " room natively",
                    body.contains("\"" + theme + "\".equals(theme)"));
        }
        assertTrue("must launch the native room screen",
                body.contains("LobbyActivity.class"));
    }

    @Test
    public void officeOpensAllThreeSocialRoomsNatively() throws Exception {
        String src = read("OfficeActivity.java");
        int open = src.indexOf("private void openRoom");
        assertTrue("openRoom missing", open > 0);
        String body = src.substring(open);
        for (String theme : new String[]{"lobby", "music", "coffee"}) {
            assertTrue("Office must open the " + theme + " room natively",
                    body.contains("\"" + theme + "\".equals(theme)"));
        }
        assertTrue("must launch the native room screen",
                body.contains("LobbyActivity.class"));
    }

    @Test
    public void noProductionCodeFetchesRetiredMobileHomeEndpoint() throws Exception {
        // Any live fetch of the retired route would 404 and break Home /
        // Office / login verification again.
        String[] files = {
                "HomeActivity.java", "OfficeActivity.java", "HomeCompositeFetch.java",
                "HomeDataClient.java", "QolabWebLoginDriver.java", "LoginActivity.java",
                "QolabWebViewTransport.java", "ChatListActivity.java",
                "ChatScreenActivity.java", "RoomActivity.java", "LobbyActivity.java",
                "SessionGate.java",
        };
        for (String f : files) {
            String src = read(f);
            assertTrue(f + " must not fetch /mobile/home (route removed from backend)",
                    !src.contains("getJson(\"/mobile/home\""));
            assertTrue(f + " must not probe origin+'/mobile/home' in JS",
                    !src.contains("'/mobile/home'"));
        }
    }

    @Test
    public void loginVerifierProbesLiveEndpoint() throws Exception {
        String src = read("QolabWebLoginDriver.java");
        assertTrue("VERIFY_JS must probe a live endpoint (/rooms)",
                src.contains("fetch(location.origin+'/rooms'"));
        assertTrue("session probe must not use the retired /mobile/home",
                !src.contains("origin+'/mobile/home'"));
    }

    @Test
    public void compositeFetchUsesExistingEndpointsOnly() throws Exception {
        String src = read("HomeCompositeFetch.java");
        assertTrue("must fetch /dashboard",
                src.contains("getJson(\"/dashboard\""));
        assertTrue("must fetch /rooms",
                src.contains("getJson(\"/rooms\""));
        assertTrue("must fetch /notifications/unread-count",
                src.contains("getJson(\"/notifications/unread-count\""));
    }
}
