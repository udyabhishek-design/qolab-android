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
        // The non-2xx rejection must exist ...
        assertTrue("OfficeActivity must gate on r.status / 100 != 2",
                src.contains("r.status / 100 != 2"));
        // ... BEFORE the parse call, so an HTML 404 body can never reach
        // HomeDataClient.parse and masquerade as empty data.
        int gate = src.indexOf("r.status / 100 != 2");
        int parse = src.indexOf("HomeDataClient.parse(r.body)");
        assertTrue("non-2xx gate missing", gate > 0);
        assertTrue("parse call missing", parse > 0);
        assertTrue("non-2xx gate must precede HomeDataClient.parse(r.body)",
                gate < parse);
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
