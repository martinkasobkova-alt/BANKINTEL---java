package cz.bankintel.explore.manager.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.bankintel.explore.manager.refresh.ManagerEurostatRefreshTargetBuilder.RefreshTarget;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class ManagerSeriesCacheDocBuilderTest {

    private static final Instant NOW = ZonedDateTime.of(2026, 8, 7, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();

    private static RefreshTarget target(String seriesId, String geo, Map<String, Object> rowExtra) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("segment_id", "manufacturing_general");
        row.put("series_id", seriesId);
        row.put("dataset_id", "sts_inpr_m");
        row.put("title", "Průmyslová produkce");
        row.put("frequency", "M");
        row.putAll(rowExtra);
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("geo", geo);
        queryParams.put("unit", "I21");
        return new RefreshTarget(row, geo, queryParams);
    }

    private static List<Map<String, Object>> observations(String... periodValuePairs) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (int i = 0; i < periodValuePairs.length; i += 2) {
            Map<String, Object> obs = new LinkedHashMap<>();
            obs.put("period", periodValuePairs[i]);
            obs.put("value", Double.parseDouble(periodValuePairs[i + 1]));
            out.add(obs);
        }
        return out;
    }

    @Test
    void hardYearGateRejectsDataOlderThanTwoYears() {
        assertTrue(ManagerSeriesCacheDocBuilder.isStaleByHardYearGate("2023-12", NOW)); // 2026 - 2 = 2024 floor
        assertFalse(ManagerSeriesCacheDocBuilder.isStaleByHardYearGate("2024-01", NOW));
        assertFalse(ManagerSeriesCacheDocBuilder.isStaleByHardYearGate("", NOW));
        assertFalse(ManagerSeriesCacheDocBuilder.isStaleByHardYearGate(null, NOW));
    }

    @Test
    void buildLoadedDocProducesExpectedIdAndCoreFields() {
        RefreshTarget target = target("sts_inpr_m_manufacturing_total", "IT", Map.of());
        List<Map<String, Object>> obs = observations("2026-05", "100.0", "2026-06", "103.4");

        Document doc = ManagerSeriesCacheDocBuilder.buildLoadedDoc(target, obs, NOW);

        assertEquals("eurostat:sts_inpr_m_manufacturing_total:IT", doc.get("_id"));
        assertEquals("eurostat", doc.get("source"));
        assertEquals("manufacturing_general", doc.get("segment_id"));
        assertEquals("IT", doc.get("geo"));
        assertEquals("2026-06", doc.get("latest_period"));
        assertEquals(103.4, doc.get("latest_value"));
        assertEquals("M", doc.get("frequency"));
        assertEquals("fresh", doc.get("freshness"));
        assertNull(doc.get("stale_reason"));
    }

    @Test
    void buildUnavailableDocHasNullLatestPeriodAndUnavailableFreshness() {
        RefreshTarget target = target("sts_inpr_m_manufacturing_total", "IT", Map.of());

        Document doc = ManagerSeriesCacheDocBuilder.buildUnavailableDoc(target, "no_observations", NOW);

        assertEquals("eurostat:sts_inpr_m_manufacturing_total:IT", doc.get("_id"));
        assertNull(doc.get("latest_period"));
        assertEquals("unavailable", doc.get("freshness"));
        assertEquals("no_observations", doc.get("unavailable_reason"));
    }

    @Test
    void monthlyFreshnessBoundaries() {
        // NOW = 2026-08 -> month index 2026*12+8. fresh<=9, usable<=15, else stale_suspicious.
        assertEquals("fresh", ManagerSeriesCacheDocBuilder.classifyFreshness("2025-11", "M", false, NOW).freshness()); // lag 9
        assertEquals("usable_lagged", ManagerSeriesCacheDocBuilder.classifyFreshness("2025-05", "M", false, NOW).freshness()); // lag 15
        assertEquals("stale_suspicious", ManagerSeriesCacheDocBuilder.classifyFreshness("2025-04", "M", false, NOW).freshness()); // lag 16
    }

    @Test
    void annualFreshnessBoundaries() {
        // A thresholds fresh<=30, usable<=42 months.
        assertEquals("fresh", ManagerSeriesCacheDocBuilder.classifyFreshness("2024", "A", false, NOW).freshness());
        assertEquals("stale_suspicious", ManagerSeriesCacheDocBuilder.classifyFreshness("2020", "A", false, NOW).freshness());
    }

    @Test
    void historicalSeriesIsClassifiedHistoricalRegardlessOfLag() {
        var result = ManagerSeriesCacheDocBuilder.classifyFreshness("1998-12", "M", true, NOW);
        assertEquals("historical", result.freshness());
        assertEquals("historical_dataset", result.freshnessCategory());
    }

    @Test
    void isHistoricalSeriesDetectsTitleTokens() {
        Map<String, Object> row = Map.of("title", "Historical GDP archive series", "series_id", "x", "dataset_id", "y");
        assertTrue(ManagerSeriesCacheDocBuilder.isHistoricalSeries(row));

        Map<String, Object> normalRow = Map.of("title", "Industrial production", "series_id", "x", "dataset_id", "y");
        assertFalse(ManagerSeriesCacheDocBuilder.isHistoricalSeries(normalRow));
    }

    @Test
    void frequencyCodeInfersFromPeriodWhenRawValueUnrecognized() {
        assertEquals("M", ManagerSeriesCacheDocBuilder.frequencyCode("", "2026-06"));
        assertEquals("Q", ManagerSeriesCacheDocBuilder.frequencyCode(null, "2026-Q2"));
        assertEquals("A", ManagerSeriesCacheDocBuilder.frequencyCode("", "2026"));
        assertEquals("M", ManagerSeriesCacheDocBuilder.frequencyCode("monthly", "irrelevant"));
    }

    @Test
    void periodToMonthIndexHandlesAllPeriodShapes() {
        assertEquals(2026 * 12 + 6, ManagerSeriesCacheDocBuilder.periodToMonthIndex("2026-06"));
        assertEquals(2026 * 12 + 4, ManagerSeriesCacheDocBuilder.periodToMonthIndex("2026-Q2"));
        assertEquals(2026 * 12 + 12, ManagerSeriesCacheDocBuilder.periodToMonthIndex("2026"));
        assertEquals(null, ManagerSeriesCacheDocBuilder.periodToMonthIndex("not-a-period"));
    }

    @Test
    void yoyMomChangeComputedForMonthlyFrequency() {
        RefreshTarget target = target("s1", "IT", Map.of());
        List<Map<String, Object>> obs = new java.util.ArrayList<>();
        // 13 monthly points spanning exactly one year, so index 0 is the YoY comparator for the
        // last point and index n-2 is the MoM comparator.
        String[] periods = {
            "2025-06", "2025-07", "2025-08", "2025-09", "2025-10", "2025-11", "2025-12",
            "2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06"
        };
        double[] values = {100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 108, 109, 110};
        for (int i = 0; i < periods.length; i++) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("period", periods[i]);
            o.put("value", values[i]);
            obs.add(o);
        }

        Document doc = ManagerSeriesCacheDocBuilder.buildLoadedDoc(target, obs, NOW);

        // mom = (110-109)/109*100 ~ 0.917 ; yoy = (110-100)/100*100 = 10.0
        assertEquals(10.0, (Double) doc.get("yoy_change_pct"), 0.001);
        assertTrue(((Double) doc.get("mom_change_pct")) > 0.9 && ((Double) doc.get("mom_change_pct")) < 0.95);
    }
}
