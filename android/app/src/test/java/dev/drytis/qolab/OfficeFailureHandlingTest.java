package dev.drytis.qolab;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * v1.5.36 regression guards for "failed requests never appear as valid
 * empty results":
 *
 *  1. HomeDataClient.parse() must return null on unparseable bodies —
 *     its catch block previously fabricated an empty HomeData, which made
 *     Office render a Laravel 404 HTML page as "No social rooms yet".
 *  2. OfficeActivity must reject any non-2xx HTTP status BEFORE parsing
 *     (Home already did; Office fell through to the parser).
 *
 * Source-level assertions match the project's existing wiring-test style
 * (OfficeLayoutTest / MeetingDetailBindingTest).
 */
public class OfficeFailureHandlingTest {

    private static final Path OFFICE = repoRoot().resolve(
            "android/app/src/main/java/dev/drytis/qolab/OfficeActivity.java");
    private static final Path PARSER = repoRoot().resolve(
            "android/app/src/main/java/dev/drytis/qolab/HomeDataClient.java");

    /** Walk up from the test working dir until the repo root (.git). */
    private static Path repoRoot() {
        Path d = new File(".").getAbsoluteFile().toPath().getParent();
        for (int i = 0; i < 8 && d != null; i++, d = d.getParent()) {
            if (Files.exists(d.resolve(".git"))) return d;
        }
        return new File(".").getAbsoluteFile().toPath().getParent();
    }

    private static String read(Path p) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(p));
    }

    @Test
    public void officeRejectsNon2xxBeforeParse() throws Exception {
        String src = read(OFFICE);
        // v1.5.43: Office loads through HomeCompositeFetch (the /mobile/home
        // aggregate was removed from the backend). The gate now lives in
        // HomeCompositeFetch: non-2xx / null-body / 401 are rejected BEFORE
        // any Inertia parse, and Office's callback only renders on status 200.
        assertTrue("OfficeActivity must use the composite fetch",
                src.contains("HomeCompositeFetch.fetch(transport"));
        int fetchCall = src.indexOf("HomeCompositeFetch.fetch(transport");
        int render = src.indexOf("data = parsed;", fetchCall);
        assertTrue("render path must come after the composite fetch",
                render > fetchCall);
        // Non-2xx must route to showError, never to the parse/render path.
        String gate = src.substring(fetchCall, render);
        assertTrue("composite callback must gate on status != 200 before render",
                gate.contains("status != 200 || parsed == null"));

        // The transport-level non-2xx rejection itself:
        String composite = new String(java.nio.file.Files.readAllBytes(
                new File("src/main/java/dev/drytis/qolab/HomeCompositeFetch.java")
                        .toPath()));
        assertTrue("HomeCompositeFetch must reject non-2xx before parsing",
                composite.contains("r.status / 100 != 2"));
        // The gate must sit inside the response arm BEFORE the dashboard
        // parse it guards. (parseRoomsPage also appears in the park helper
        // tryFinishRooms, which is only reachable from the arm's 2xx
        // branch — the guard ordering that matters is arm: gate → parse.)
        int armIdx = composite.indexOf("final java.util.function.BiConsumer");
        int httpGate = composite.indexOf("r.status / 100 != 2", armIdx);
        int dashParse = composite.indexOf("HomeDataClient.parseDashboardPage", armIdx);
        assertTrue("response arm not found", armIdx > 0);
        assertTrue("non-2xx gate missing in arm", httpGate > armIdx);
        assertTrue("dashboard parse missing in arm", dashParse > armIdx);
        assertTrue("non-2xx gate must precede the dashboard parse in the arm",
                httpGate < dashParse);
    }

    @Test
    public void parserCatchReturnsNullNotFabricatedEmpty() throws Exception {
        String src = read(PARSER);
        int catchIdx = src.indexOf("} catch (Exception e) {");
        assertTrue("parse() catch block not found", catchIdx > 0);
        String tail = src.substring(catchIdx, Math.min(src.length(), catchIdx + 600));
        assertTrue("parse() catch must return null (failure), not fabricate a HomeData",
                tail.contains("return null;"));
        assertTrue("parse() catch must not fabricate empty data",
                !tail.contains("new HomeData("));
    }

    @Test
    public void htmlErrorPageParsesToNull() {
        // Behavior-level pin of the v1.5.35 field bug: a Laravel 404 page.
        assertNullHelper();
    }

    private static void assertNullHelper() {
        HomeDataClient.HomeData d = HomeDataClient.parse(
            "<!DOCTYPE html><html><body><h1>404 Not Found</h1></body></html>");
        assertTrue("HTML body must parse to null", d == null);
    }
}
