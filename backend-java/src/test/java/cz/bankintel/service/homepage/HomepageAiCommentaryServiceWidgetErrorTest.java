package cz.bankintel.service.homepage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import cz.bankintel.search.openai.OpenAiClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regrese k nálezu V1 z QA reportu 2026-08-30: {@code POST /api/homepage/ai-commentary} vracelo
 * HTTP 500 s {@link NullPointerException}, kdykoli widget vrátil data s klíčem {@code error} —
 * ošetřovací větev stavěla odpověď přes {@code Map.of()}, které nepovoluje {@code null} hodnoty.
 */
class HomepageAiCommentaryServiceWidgetErrorTest {

    private final HomepageAiCommentaryService service =
            new HomepageAiCommentaryService(mock(OpenAiClient.class));

    @Test
    void widgetDataErrorReturnsExplanationInsteadOfThrowing() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("error", "Upstream ARAD timeout");

        Map<String, Object> out = assertDoesNotThrow(
                () -> service.generateVerbose("arad_view", "Nějaký widget", data, null, Map.of()));

        assertNotNull(out);
        assertNull(out.get("text"));
        assertNull(out.get("summary"));
        assertTrue(out.containsKey("text"), "null text must still be present as a key");
        assertTrue(out.containsKey("summary"), "null summary must still be present as a key");
        assertTrue(String.valueOf(out.get("reason")).contains("Upstream ARAD timeout"), String.valueOf(out.get("reason")));
        assertTrue(Boolean.FALSE.equals(out.get("fallback_used")));
    }

    @Test
    void veryLongWidgetErrorIsTruncatedNotRejected() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("error", "x".repeat(500));

        Map<String, Object> out = assertDoesNotThrow(
                () -> service.generateVerbose("arad_view", "Nějaký widget", data, null, Map.of()));

        String reason = String.valueOf(out.get("reason"));
        assertTrue(reason.length() < 300, "reason should stay bounded, was " + reason.length());
    }
}
