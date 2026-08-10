package cz.bankintel.search.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for frequency inference — a full ISO date shape ({@code YYYY-MM-DD}) does
 * NOT by itself mean daily data: sources like FRED encode monthly/quarterly/annual observations
 * as the first day of the period, so the normalizer has to look at the actual gaps between
 * observation dates instead of just the date string's shape.
 */
class ForecastSeriesNormalizerTest {

    @Test
    void inferQuarterlyFrequencyFromFullIsoDatesWithNoExplicitFreqField() {
        List<Map<String, Object>> rows = quarterlyRows("1995-01-01", 20);

        ForecastSeriesNormalizer.NormalizedSeries series = ForecastSeriesNormalizer.normalize(
                rows, "fred:CLVMNACSCAB1GQPL", "Real GDP Poland", "fred", "PL", null, null);

        assertEquals("Q", series.frequency());
    }

    @Test
    void inferMonthlyFrequencyFromFullIsoDatesWithNoExplicitFreqField() {
        List<Map<String, Object>> rows = monthlyRows("2020-01-01", 24);

        ForecastSeriesNormalizer.NormalizedSeries series = ForecastSeriesNormalizer.normalize(
                rows, "fred:SOMEMONTHLY", "Some monthly series", "fred", "US", null, null);

        assertEquals("M", series.frequency());
    }

    @Test
    void inferDailyFrequencyFromFullIsoDatesWithNoExplicitFreqField() {
        List<Map<String, Object>> rows = dailyRows("2024-01-01", 30);

        ForecastSeriesNormalizer.NormalizedSeries series = ForecastSeriesNormalizer.normalize(
                rows, "fred:SOMEDAILY", "Some daily series", "fred", "US", null, null);

        assertEquals("D", series.frequency());
    }

    @Test
    void explicitFrequencyFieldTakesPrecedenceOverDateShapeInference() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date", "2020-01-01");
        row.put("value", 1.0);
        row.put("freq", "Q");
        rows.add(row);

        ForecastSeriesNormalizer.NormalizedSeries series = ForecastSeriesNormalizer.normalize(
                rows, "eurostat:x", "X", "eurostat", "CZ", null, null);

        assertEquals("Q", series.frequency());
    }

    @Test
    void sortsDescendingApiRowsBackIntoChronologicalOrder() {
        List<Map<String, Object>> rows = List.of(
                row("2024-03-01", 103.0),
                row("2024-02-01", 102.0),
                row("2024-01-01", 101.0));

        ForecastSeriesNormalizer.NormalizedSeries series = ForecastSeriesNormalizer.normalize(
                rows, "fred:DESC", "Descending API series", "fred", "US", null, null);

        assertEquals("2024-01-01", series.observations().get(0).get("date"));
        assertEquals("2024-03-01", series.observations().get(2).get("date"));
        assertEquals("M", series.frequency());
    }

    private static List<Map<String, Object>> quarterlyRows(String startIso, int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        java.time.LocalDate d = java.time.LocalDate.parse(startIso);
        for (int i = 0; i < count; i++) {
            rows.add(row(d.plusMonths(3L * i).toString(), 100.0 + i));
        }
        return rows;
    }

    private static List<Map<String, Object>> monthlyRows(String startIso, int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        java.time.LocalDate d = java.time.LocalDate.parse(startIso);
        for (int i = 0; i < count; i++) {
            rows.add(row(d.plusMonths(i).toString(), 100.0 + i));
        }
        return rows;
    }

    private static List<Map<String, Object>> dailyRows(String startIso, int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        java.time.LocalDate d = java.time.LocalDate.parse(startIso);
        for (int i = 0; i < count; i++) {
            rows.add(row(d.plusDays(i).toString(), 100.0 + i));
        }
        return rows;
    }

    private static Map<String, Object> row(String date, double value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date", date);
        row.put("value", value);
        return row;
    }
}
