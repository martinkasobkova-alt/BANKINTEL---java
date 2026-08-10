package cz.bankintel.search.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ForecastModelEngineTest {

    private final ForecastModelEngine engine = new ForecastModelEngine();

    @Test
    void computesForecastBacktestIntervalsAndScenariosInProcess() {
        Map<String, Object> response = engine.forecast(request(annualSeries(2007, 19)));

        assertTrue(List.of("ok", "warning").contains(dataQuality(response).get("status")));
        assertFalse(list(response.get("forecast")).isEmpty());
        assertEquals(5, list(response.get("scenarios")).size());
        assertFalse(list(response.get("model_alternatives")).isEmpty());
        assertNotNull(map(response.get("backtest")).get("rmse"));
        assertTrue(((Number) map(response.get("backtest")).get("n_folds")).intValue() > 0);

        Map<String, Object> firstPoint = map(list(response.get("forecast")).getFirst());
        assertNotNull(firstPoint.get("p10"));
        assertNotNull(firstPoint.get("p50"));
        assertNotNull(firstPoint.get("p90"));
        assertTrue(((Number) firstPoint.get("p10")).doubleValue() <= ((Number) firstPoint.get("p50")).doubleValue());
        assertTrue(((Number) firstPoint.get("p50")).doubleValue() <= ((Number) firstPoint.get("p90")).doubleValue());

        Map<String, Object> recommended = list(response.get("model_alternatives")).stream()
                .map(ForecastModelEngineTest::map)
                .filter(item -> Boolean.TRUE.equals(item.get("recommended")))
                .findFirst()
                .orElseThrow();
        assertEquals(map(response.get("model_selection")).get("selected_model"), recommended.get("model"));
        assertFalse(String.valueOf(recommended.get("label")).isBlank());
        assertEquals(list(response.get("forecast")).size(), list(recommended.get("forecast")).size());
    }

    @Test
    void rejectsSeriesThatIsTooShortWithoutInventingNumbers() {
        Map<String, Object> response = engine.forecast(request(annualSeries(2022, 4)));

        assertEquals("not_reliable", dataQuality(response).get("status"));
        assertTrue(list(response.get("forecast")).isEmpty());
        assertEquals("none", map(response.get("model_selection")).get("selected_model"));
        assertTrue(list(response.get("model_alternatives")).isEmpty());
    }

    @Test
    void canSelectADataBackedExogenousCandidate() {
        List<Map<String, Object>> targetObs = new ArrayList<>();
        List<Map<String, Object>> driverObs = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            String date = String.format("%04d-%02d", 2023 + i / 12, i % 12 + 1);
            double driver = 10 + Math.sin(i * 0.7) * 3 + i * 0.02;
            targetObs.add(Map.of("date", date, "value", 2.0 + 3.0 * driver));
            driverObs.add(Map.of("date", date, "value", driver));
        }
        Map<String, Object> request = request(series("target", "Target", "M", targetObs));
        request.put(
                "candidate_exog",
                List.of(series("driver", "Driver", "M", driverObs, "economic_driver")));

        Map<String, Object> response = engine.forecast(request);

        assertTrue(
                String.valueOf(map(response.get("model_selection")).get("selected_model")).startsWith("exog_regression:"),
                String.valueOf(response));
        assertFalse(list(response.get("selected_features")).isEmpty());
        assertEquals("economic_driver", map(list(response.get("selected_features")).getFirst()).get("concept"));
    }

    private static Map<String, Object> request(Map<String, Object> target) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("target", target);
        request.put("candidate_exog", List.of());
        request.put("hist_exog", List.of());
        request.put("futr_exog", List.of());
        request.put("stat_exog", Map.of());
        request.put("horizons", List.of());
        return request;
    }

    private static Map<String, Object> annualSeries(int startYear, int count) {
        List<Map<String, Object>> observations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            observations.add(Map.of("date", String.valueOf(startYear + i), "value", 8.0 + i * 0.4 + Math.sin(i) * 1.2));
        }
        return series("target", "Return on equity of banks", "Y", observations);
    }

    private static Map<String, Object> series(
            String id, String name, String frequency, List<Map<String, Object>> observations) {
        return series(id, name, frequency, observations, "");
    }

    private static Map<String, Object> series(
            String id, String name, String frequency, List<Map<String, Object>> observations, String concept) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series_id", id);
        out.put("name", name);
        out.put("source", "test");
        out.put("geo", "CZ");
        out.put("unit", "%");
        out.put("frequency", frequency);
        out.put("concept", concept);
        out.put("observations", observations);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> dataQuality(Map<String, Object> response) {
        return map(response.get("data_quality"));
    }
}
