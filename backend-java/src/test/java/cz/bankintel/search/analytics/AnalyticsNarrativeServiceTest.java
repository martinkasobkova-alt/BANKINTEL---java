package cz.bankintel.search.analytics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalyticsNarrativeServiceTest {

    private final AnalyticsNarrativeService service = new AnalyticsNarrativeService();

    @Test
    void buildsExecutiveSummaryFromMetricsWithoutFabricatingNumbers() {
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put(
                "target_resolution",
                Map.of("target_series_name", "Inflace ČR (HICP YoY)", "unit", "%"));
        analysis.put(
                "metrics",
                Map.of(
                        "current", Map.of("last_period", "2025-05", "last_value", 2.4),
                        "change", Map.of("yoy_pct_last", 2.4),
                        "distribution", Map.of("percentile_of_last_value", 65.0)));
        analysis.put("quality_warnings", List.of());

        Map<String, Object> narrative = service.buildNarrative(analysis, "Inflace");

        String summary = String.valueOf(narrative.get("executive_summary"));
        assertTrue(summary.contains("Inflace ČR"));
        assertTrue(summary.contains("2,4"));
        assertFalse(summary.toLowerCase().contains("určitě"));
        assertFalse(summary.toLowerCase().contains(" bude "));
    }

    @Test
    void includesForecastSentenceWhenForecastPointsPresent() {
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("target_resolution", Map.of("target_series_name", "Inflace", "unit", "%"));
        analysis.put("metrics", Map.of("current", Map.of("last_value", 2.0)));
        analysis.put(
                "forecasts",
                List.of(Map.of("horizon", "6M", "p10", 1.8, "p50", 2.5, "p90", 3.1)));

        Map<String, Object> narrative = service.buildNarrative(analysis, "Inflace");
        String forecastSentence = String.valueOf(narrative.get("forecast_sentence"));
        assertTrue(forecastSentence.contains("2,5"));
        assertTrue(forecastSentence.toLowerCase().contains("technick"));
    }

    @Test
    void keyNumbersUseCompactDisplayForLargeAbsoluteValues() {
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put(
                "target_resolution",
                Map.of("target_series_name", "Zisk z finanční a provozní činnosti", "unit", "CZK"));
        analysis.put(
                "metrics",
                Map.of(
                        "current", Map.of("last_period", "2026-03-31", "last_value", 132_607_667_020.09),
                        "change", Map.of(),
                        "distribution", Map.of()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keyNumbers =
                (List<Map<String, Object>>) service.buildNarrative(analysis, "Zisk bank").get("key_numbers");
        assertNotNull(keyNumbers);
        Map<String, Object> last = keyNumbers.get(0);
        assertTrue(String.valueOf(last.get("display_value")).contains("mld"));
        assertFalse(String.valueOf(last.get("display_value")).contains("132607667020"));
    }

    @Test
    void methodologySectionsListCompletedCalculations() {
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("target_resolution", Map.of("target_series_name", "HDP Česko"));
        analysis.put("metrics", Map.of("current", Map.of("last_value", 100.0)));
        analysis.put("planner", Map.of("calculation_types_completed", List.of("basic_metrics", "trend")));

        Map<String, Object> narrative = service.buildNarrative(analysis, "HDP");
        String methodology = String.valueOf(narrative.get("methodology_note"));
        assertTrue(methodology.contains("základní metriky"));
        assertTrue(methodology.contains("trend"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> sections = (List<Map<String, String>>) narrative.get("methodology_sections");
        assertNotNull(sections);
        assertTrue(sections.size() >= 2);
    }
}
