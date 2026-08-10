package cz.bankintel.service.calculations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.bankintel.domain.entity.ComputedIndicatorEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ComputedAdvancedEngineTest {

    @Mock
    private SeriesOperandLoader operandLoader;

    private ComputedAdvancedEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ComputedAdvancedEngine(operandLoader);
    }

    @Test
    void zscoreComputesStandardizedValues() {
        ComputedIndicatorEntity doc = doc("zscore", seriesA(), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(seriesAValues());
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertFalse(result.rows().isEmpty(), result.warnings().toString());
        assertTrue(result.rows().size() >= 3);
    }

    @Test
    void drawdownPctTracksPeakDecline() {
        ComputedIndicatorEntity doc = doc("drawdown_pct", seriesA(), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(seriesAValues());
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertFalse(result.rows().isEmpty());
        double last = toDouble(result.rows().getLast().get("value"));
        assertTrue(last < 0, "drawdown should be negative after peak");
    }

    @Test
    void yoyPctAutoUsesLag() {
        ComputedIndicatorEntity doc = doc("yoy_pct_auto", seriesA(), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(seriesAValues());
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertFalse(result.rows().isEmpty(), result.warnings().toString());
        assertTrue(result.rows().size() >= 2);
    }

    @Test
    void pctPointsSubtractsSeries() {
        Map<String, Object> left = Map.of("values", Map.of("2020Q1", 10.0));
        Map<String, Object> right = Map.of("source_id", "b", "values", Map.of("2020Q1", 9.0));
        ComputedIndicatorEntity doc = doc("pct_points", left, right);
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    Map<String, Object> ref = inv.getArgument(0);
                    @SuppressWarnings("unchecked")
                    Map<String, Double> values = (Map<String, Double>) ref.get("values");
                    return values != null ? values : Map.of();
                });
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertEquals(1, result.rows().size());
        assertEquals(1.0, toDouble(result.rows().getFirst().get("value")));
    }

    @Test
    void pctRankHistComputesIntermediatePercentiles() {
        Map<String, Double> series = new LinkedHashMap<>();
        series.put("2020-01", 1.0);
        series.put("2020-02", 2.0);
        series.put("2020-03", 3.0);
        series.put("2020-04", 4.0);
        series.put("2020-05", 5.0);
        ComputedIndicatorEntity doc = doc("pct_rank_hist", Map.of("values", series), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertEquals(5, result.rows().size(), result.warnings().toString());
        for (int i = 0; i < 5; i++) {
            assertEquals(100.0, toDouble(result.rows().get(i).get("value")), 0.01, "percentile at index " + i);
        }
    }

    @Test
    void pctRankHistUsesFloatingDivisionForPartialPercentiles() {
        Map<String, Double> series = new LinkedHashMap<>();
        series.put("2020-01", 1.0);
        series.put("2020-02", 2.0);
        series.put("2020-03", 3.0);
        series.put("2020-04", 4.0);
        series.put("2020-05", 3.0);
        ComputedIndicatorEntity doc = doc("pct_rank_hist", Map.of("values", series), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertEquals(50.0, toDouble(result.rows().get(4).get("value")), 0.01);
    }

    @Test
    void logASkipsNonPositiveValues() {
        Map<String, Double> series = new LinkedHashMap<>();
        series.put("2020-01", -1.0);
        series.put("2020-02", 2.0);
        ComputedIndicatorEntity doc = doc("log_a", Map.of("values", series), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertEquals(1, result.rows().size());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("ln je definován jen pro kladné hodnoty")));
    }

    @Test
    void logAComputesNaturalLogForPositiveValues() {
        Map<String, Double> series = Map.of("2020-01", 1.0, "2020-02", Math.E);
        ComputedIndicatorEntity doc = doc("log_a", Map.of("values", series), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertEquals(2, result.rows().size());
        assertEquals(0.0, toDouble(result.rows().get(0).get("value")), 0.001);
        assertEquals(1.0, toDouble(result.rows().get(1).get("value")), 0.001);
    }

    @Test
    void logASkipsZeroValues() {
        Map<String, Double> series = new LinkedHashMap<>();
        series.put("2020-01", 0.0);
        series.put("2020-02", 5.0);
        ComputedIndicatorEntity doc = doc("log_a", Map.of("values", series), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertEquals(1, result.rows().size());
    }

    @Test
    void indexFirstNormalizesToHundredAtFirstPeriod() {
        Map<String, Double> series = new LinkedHashMap<>();
        series.put("2019Q1", 50.0);
        series.put("2019Q2", 75.0);
        ComputedIndicatorEntity doc = doc("index_100_first", Map.of("values", series), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertEquals(100.0, toDouble(result.rows().get(0).get("value")), 0.01);
        assertEquals(150.0, toDouble(result.rows().get(1).get("value")), 0.01);
    }

    @Test
    void indexFirstWarnsOnZeroBase() {
        Map<String, Double> series = Map.of("2019Q1", 0.0, "2019Q2", 10.0);
        ComputedIndicatorEntity doc = doc("index_100_first", Map.of("values", series), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertTrue(result.rows().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("nulov")));
    }

    @Test
    void cagrRangeComputesGrowthBetweenEndpoints() {
        Map<String, Double> series = new LinkedHashMap<>();
        series.put("2018", 100.0);
        series.put("2020", 121.0);
        ComputedIndicatorEntity doc = doc("cagr_range", Map.of("values", series), Map.of());
        doc.setOptions(Map.of("start_period", "2018", "end_period", "2020"));
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertFalse(result.rows().isEmpty(), result.warnings().toString());
        double cagr = toDouble(result.rows().getFirst().get("value"));
        assertTrue(cagr > 0, "cagr=" + cagr);
    }

    @Test
    void cagrRangeRequiresTwoPoints() {
        Map<String, Double> series = Map.of("2020", 100.0);
        ComputedIndicatorEntity doc = doc("cagr_range", Map.of("values", series), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertTrue(result.rows().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("alespoň dva")));
    }

    @Test
    void pctRankHistFirstPointIsFullPercentile() {
        Map<String, Double> series = Map.of("2020-01", 42.0);
        ComputedIndicatorEntity doc = doc("pct_rank_hist", Map.of("values", series), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertEquals(100.0, toDouble(result.rows().getFirst().get("value")), 0.01);
    }

    @Test
    void pctRankHistMinimumIsZeroForLowestValue() {
        Map<String, Double> series = new LinkedHashMap<>();
        series.put("2020-01", 3.0);
        series.put("2020-02", 2.0);
        series.put("2020-03", 1.0);
        ComputedIndicatorEntity doc = doc("pct_rank_hist", Map.of("values", series), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertEquals(0.0, toDouble(result.rows().get(2).get("value")), 0.01);
    }

    @Test
    void trendLinearTimeFitsIncreasingSeries() {
        Map<String, Double> series = new LinkedHashMap<>();
        series.put("2019Q1", 1.0);
        series.put("2019Q2", 2.0);
        series.put("2019Q3", 3.0);
        series.put("2019Q4", 4.0);
        ComputedIndicatorEntity doc = doc("trend_linear_time", Map.of("values", series), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertEquals(4, result.rows().size());
        assertTrue(toDouble(result.rows().get(3).get("value")) > toDouble(result.rows().get(0).get("value")));
    }

    @Test
    void trendLinearTimeRequiresThreePoints() {
        Map<String, Double> series = Map.of("2019Q1", 1.0, "2019Q2", 2.0);
        ComputedIndicatorEntity doc = doc("trend_linear_time", Map.of("values", series), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertTrue(result.rows().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("tři")));
    }

    @Test
    void cumChangeFirstTracksDeltaFromBaseline() {
        Map<String, Double> series = new LinkedHashMap<>();
        series.put("2020Q1", 100.0);
        series.put("2020Q2", 105.0);
        ComputedIndicatorEntity doc = doc("cum_change_first", Map.of("values", series), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertEquals(0.0, toDouble(result.rows().get(0).get("value")), 0.01);
        assertEquals(5.0, toDouble(result.rows().get(1).get("value")), 0.01);
    }

    @Test
    void rollMeanSmoothsSeries() {
        Map<String, Double> series = new LinkedHashMap<>();
        series.put("2020-01", 1.0);
        series.put("2020-02", 3.0);
        series.put("2020-03", 5.0);
        ComputedIndicatorEntity doc = doc("roll_mean", Map.of("values", series), Map.of());
        doc.setOptions(Map.of("window", 2));
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertFalse(result.rows().isEmpty());
        assertEquals(4.0, toDouble(result.rows().getLast().get("value")), 0.01);
    }

    @Test
    void volatilityRetProducesRowsForLongSeries() {
        Map<String, Double> series = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            series.put("2020-" + String.format("%02d", i + 1), 100.0 + i);
        }
        ComputedIndicatorEntity doc = doc("volatility_ret", Map.of("values", series), Map.of());
        org.mockito.Mockito.when(operandLoader.loadSeriesMap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(series);
        ComputedIndicatorRunner.RunResult result = engine.run(doc, "user-1");
        assertFalse(result.rows().isEmpty());
    }

    private static ComputedIndicatorEntity doc(String op, Map<String, Object> left, Map<String, Object> right) {
        ComputedIndicatorEntity doc = new ComputedIndicatorEntity();
        doc.setOperation(op);
        doc.setLeft(left);
        doc.setRight(right);
        doc.setOptions(Map.of("window", 2));
        return doc;
    }

    private static Map<String, Object> seriesA() {
        return Map.of("values", seriesAValues());
    }

    private static Map<String, Object> seriesB() {
        return Map.of("values", seriesBValues());
    }

    private static Map<String, Double> seriesAValues() {
        Map<String, Double> series = new LinkedHashMap<>();
        series.put("2019Q1", 100.0);
        series.put("2019Q2", 102.0);
        series.put("2019Q3", 101.0);
        series.put("2019Q4", 105.0);
        series.put("2020Q1", 110.0);
        series.put("2020Q2", 90.0);
        series.put("2020Q3", 95.0);
        return series;
    }

    private static Map<String, Double> seriesBValues() {
        return Map.of("2020Q1", 9.0, "2020Q2", 11.0, "2020Q3", 13.0);
    }

    private static double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
    }
}
