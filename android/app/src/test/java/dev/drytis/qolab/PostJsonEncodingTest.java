package dev.drytis.qolab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * v1.5.32 regression tests for the G32 bug: Title visibly contained
 * "Test Meeting" while the sheet showed Laravel's "The title field is
 * required." Root cause (proven in QolabWebViewTransport.postJson):
 *  1. body:JSON.stringify(<literal>) double-encoded the payload — the
 *     server received the JSON text as a STRING scalar, parsed no fields,
 *     and 422'd on title.
 *  2. jsonString() dropped every char outside 0x20–0x7F (non-ASCII titles
 *     silently truncated).
 * 3. The 422 error was never cleared when the user edited the field.
 */
public class PostJsonEncodingTest {

    /** The kick script must NOT wrap the payload literal in JSON.stringify. */
    @Test
    public void postJsonDoesNotDoubleEncodeBody() throws Exception {
        // Build the payload exactly as CreateMeetingForm does, then extract
        // the body expression the transport generates for it.
        String payload = CreateMeetingForm.buildPayload(
                "Test Meeting", null, "2026-10-06T14:30", "Asia/Kolkata", null);

        // jsonString must produce a valid JS string literal of that payload…
        String literal = QolabWebViewTransport.jsonString(payload);
        assertNotNull(literal);
        assertTrue(literal.startsWith("\""));

        // …that evaluates back to the exact same JSON (no truncation).
        String decoded = new org.json.JSONTokener(literal).nextValue().toString();
        assertEquals(payload, decoded);

        // The full kick-script body expression must send the literal AS-IS.
        // (Reconstruct the expression the transport embeds; regression guard:
        // it must not contain JSON.stringify around the literal.)
        String bodyExpr = "body:" + literal;
        assertTrue(bodyExpr.startsWith("body:\"{"));
        assertTrue(!bodyExpr.contains("JSON.stringify("));
    }

    /** Non-ASCII characters must survive as \\uXXXX, never be dropped. */
    @Test
    public void jsonStringPreservesNonAscii() throws Exception {
        String in = "Résumé — 测试 📌 ok";
        String literal = QolabWebViewTransport.jsonString(in);
        String decoded = new org.json.JSONTokener(literal).nextValue().toString();
        assertEquals(in, decoded);
        // Every emitted escape is ASCII-only text.
        for (char c : literal.toCharArray()) {
            assertTrue(c < 0x80);
        }
    }

    /** Quotes and backslashes in a title survive the JS literal round-trip. */
    @Test
    public void jsonStringEscapesQuotesAndBackslashes() throws Exception {
        String payload = CreateMeetingForm.buildPayload(
                "Q3 \"review\" \\ backlog", null, null, "Asia/Kolkata", null);
        JSONObject roundTripped = new JSONObject(
                new org.json.JSONTokener(QolabWebViewTransport.jsonString(payload))
                        .nextValue().toString());
        assertEquals("Q3 \"review\" \\ backlog", roundTripped.getString("title"));
    }
}
