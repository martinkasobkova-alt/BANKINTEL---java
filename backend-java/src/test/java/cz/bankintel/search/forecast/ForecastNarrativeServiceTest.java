package cz.bankintel.search.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The narrative layer is deterministic templating over already-computed numbers ("LLM nesmí
 * dopočítávat čísla" in the product spec) — these tests pin down (a) that every number appearing
 * in a sentence traces back to the input JSON verbatim, and (b) that forbidden certainty language
 * ("bude", "určitě", ...) never appears in the generated text.
 */
class ForecastNarrativeServiceTest {

    private final ForecastNarrativeService service = new ForecastNarrativeService();

    private static final List<String> FORBIDDEN_WORDS =
            List.of("bude ", "určitě", "model ví", "predikuje přesně");

    @Test
    void buildsHedgedNarrativeFromForecastValues() {
        Map<String, Object> response = Map.of(
                "target_series",
                Map.of("name", "Inflace CR (HICP rocni zmena)", "unit", "Annual rate of change", "last_value", 1.8, "last_date", "2025-12"),
                "narrative_values",
                Map.of("horizon_label", "12M", "p10", -0.14, "p50", 1.8, "p90", 3.74, "change_pct", 0.0),
                "data_quality",
                Map.of("status", "warning", "warnings", List.of("missing_share_elevated: 0.25")),
                "model_selection",
                Map.of("selected_model", "naive", "branch", "A_short_or_no_exog", "fallback_used", false),
                "backtest",
                Map.of());

        Map<String, Object> narrative = service.buildNarrative(response, "Inflace / spotřebitelské ceny");

        String executiveSummary = String.valueOf(narrative.get("executive_summary"));
        assertTrue(executiveSummary.contains("1.80"), "expected p50 value verbatim in the summary: " + executiveSummary);
        assertTrue(executiveSummary.toLowerCase().contains("technický forecast"));
        assertTrue(String.valueOf(narrative.get("risk_sentence")).contains("-0.14"));

        String allText = String.join(" ", narrative.values().stream().map(String::valueOf).toList());
        for (String forbidden : FORBIDDEN_WORDS) {
            assertFalse(allText.toLowerCase().contains(forbidden), "narrative must not contain forbidden certainty word: " + forbidden);
        }
    }

    @Test
    void driversSentenceListsTopSelectedFeaturesByBacktestContribution() {
        Map<String, Object> response = Map.of(
                "target_series",
                Map.of("name", "Inflace CR", "unit", "%", "last_value", 1.8, "last_date", "2025-12"),
                "narrative_values",
                Map.of("horizon_label", "12M", "p10", -0.14, "p50", 1.8, "p90", 3.74, "change_pct", 0.0),
                "data_quality",
                Map.of("status", "ok"),
                "model_selection",
                Map.of("selected_model", "exog_regression", "branch", "B_medium_with_exog", "fallback_used", false),
                "backtest",
                Map.of(),
                "selected_features",
                List.of(
                        Map.of("feature_name", "wages__yoy_pct__lag3", "concept", "wages", "backtest_contribution", 0.05),
                        Map.of("feature_name", "policy_rate__diff__lag1", "concept", "policy_rate", "backtest_contribution", 0.22),
                        Map.of("feature_name", "policy_rate__level__lag0", "concept", "policy_rate", "backtest_contribution", 0.01)));

        Map<String, Object> narrative = service.buildNarrative(response, "Inflace / spotřebitelské ceny");

        String driversSentence = String.valueOf(narrative.get("drivers_sentence"));
        assertTrue(driversSentence.contains("guardrails a backtestem"));
        assertTrue(driversSentence.contains("policy_rate"), "highest-contribution concept should be listed: " + driversSentence);
        assertTrue(driversSentence.contains("wages"));
        // "policy_rate" appears twice in selected_features (two different lags) but must be
        // listed only once in the sentence.
        int firstIdx = driversSentence.indexOf("policy_rate");
        int secondIdx = driversSentence.indexOf("policy_rate", firstIdx + 1);
        assertEquals(-1, secondIdx, "concept must be deduplicated in the drivers sentence: " + driversSentence);
    }

    @Test
    void notReliableStatusProducesNoInventedForecastNumbers() {
        Map<String, Object> response = Map.of(
                "target_series", Map.of("name", "Cena zlata"),
                "data_quality",
                Map.of(
                        "status", "not_reliable",
                        "warnings", List.of("target_series_too_short: 0 obs < 8 required"),
                        "what_would_help", List.of("Alespoň 8 historických pozorování cílové řady (nyní 0).")));

        Map<String, Object> narrative = service.buildNarrative(response, null);

        assertEquals(
                "Forecast nebyl spočítán — data nesplňují minimální podmínky spolehlivosti.",
                narrative.get("main_forecast_sentence"));
        assertTrue(String.valueOf(narrative.get("executive_summary")).contains("Cena zlata"));
    }
}
