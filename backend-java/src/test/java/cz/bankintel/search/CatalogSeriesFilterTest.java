package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogSeriesFilterTest {

    @Test
    void autoNarrowsEurostatHicpRowsToAllItemsBeforeGrouping() {
        List<Map<String, Object>> rows = List.of(
                Map.of("geo", "ES", "freq", "M", "unit", "I15", "coicop", "AP", "time", "2024-01", "value", 102.7),
                Map.of("geo", "ES", "freq", "M", "unit", "I15", "coicop", "AP", "time", "2024-02", "value", 103.1),
                Map.of("geo", "ES", "freq", "M", "unit", "I15", "coicop", "CP00", "time", "2024-01", "value", 120.8),
                Map.of("geo", "ES", "freq", "M", "unit", "I15", "coicop", "CP00", "time", "2024-02", "value", 121.1));

        CatalogSeriesFilter.FilterResult result =
                CatalogSeriesFilter.apply(rows, Map.of("source_type", "eurostat", "country", "ES"));

        assertEquals(2, result.records().size());
        assertTrue(result.records().stream().allMatch(row -> "CP00".equals(row.get("coicop"))));
        assertEquals(null, result.groupField());
    }

    @Test
    void doesNotTreatTimePeriodOrObservationValueAsIndicatorDimension() {
        List<Map<String, Object>> rows = List.of(
                Map.of("TIME_PERIOD", "1987-05-20", "OBS_VALUE", 18.63, "KEY", "ACOILBRENTEU"),
                Map.of("TIME_PERIOD", "1987-05-21", "OBS_VALUE", 18.45, "KEY", "ACOILBRENTEU"),
                Map.of("TIME_PERIOD", "1987-05-22", "OBS_VALUE", 18.55, "KEY", "ACOILBRENTEU"));

        CatalogSeriesFilter.FilterResult result =
                CatalogSeriesFilter.apply(rows, Map.of("source_type", "fred", "set_id", "ACOILBRENTEU"));

        assertEquals(null, result.groupField());
        assertTrue(result.indicators().isEmpty());
    }

    @Test
    void doesNotTreatOhlcvColumnsAsIndicatorDimensionForStockPreviews() {
        List<Map<String, Object>> rows = List.of(
                Map.of("date", "2022-08-05", "value", 165.35, "open", 163.21, "high", 165.85, "low", 163.0,
                        "close", 165.35, "volume", 56697000, "ticker", "AAPL"),
                Map.of("date", "2022-08-08", "value", 166.37, "open", 166.37, "high", 167.81, "low", 165.82,
                        "close", 166.37, "volume", 51876500, "ticker", "AAPL"),
                Map.of("date", "2022-08-09", "value", 164.02, "open", 164.02, "high", 165.82, "low", 163.25,
                        "close", 164.02, "volume", 56395400, "ticker", "AAPL"));

        CatalogSeriesFilter.FilterResult result =
                CatalogSeriesFilter.apply(rows, Map.of("source_type", "yahoo_finance", "set_id", "AAPL"));

        assertEquals(null, result.groupField());
        assertTrue(result.indicators().isEmpty());
        assertEquals(3, result.records().size());
    }

    @Test
    void appliesCsuDimensionFiltersAndLetsUserFilterOverridePreparedFilter() {
        List<Map<String, Object>> rows = List.of(
                Map.of("Uzemi", "Cesko", "Roky", "2024", "value", 10),
                Map.of("Uzemi", "Cesko", "Roky", "2025", "value", 11),
                Map.of("Uzemi", "Praha", "Roky", "2024", "value", 20),
                Map.of("Uzemi", "Praha", "Roky", "2025", "value", 21));

        CatalogSeriesFilter.FilterResult result = CatalogSeriesFilter.apply(
                rows,
                Map.of(
                        "source_type", "csu",
                        "query_params", Map.of(
                                "csu_filters",
                                List.of(Map.of("field", "Uzemi", "exact", "Cesko"))),
                        "dimension_filters", Map.of("Uzemi", "Praha")));

        assertEquals(2, result.records().size());
        assertTrue(result.records().stream().allMatch(row -> "Praha".equals(row.get("Uzemi"))));
    }

    @Test
    void explicitIndicatorSelectionReturnsNoRowsWhenIndicatorIsMissing() {
        List<Map<String, Object>> rows = List.of(
                Map.of("indicator_id", "AAA", "period", "2024", "value", 1),
                Map.of("indicator_id", "AAA", "period", "2025", "value", 2),
                Map.of("indicator_id", "BBB", "period", "2024", "value", 3));

        CatalogSeriesFilter.FilterResult result = CatalogSeriesFilter.apply(
                rows,
                Map.of("source_type", "arad", "selected_indicator", "MISSING"));

        assertTrue(result.records().isEmpty());
        assertEquals("indicator_id", result.groupField());
        assertEquals("MISSING", result.selectedIndicator());
        assertEquals(List.of("MISSING"), result.selectedIndicators());
    }

    @Test
    void indicatorSummaryUsesHumanLabelWhenAvailable() {
        List<Map<String, Object>> rows = List.of(
                Map.of(
                        "indicator_id", "MF-3MI-RT",
                        "indicator_name", "Three-month money market rate",
                        "date", "2025-01",
                        "value", 3.1),
                Map.of(
                        "indicator_id", "MF-3MI-RT",
                        "indicator_name", "Three-month money market rate",
                        "date", "2025-02",
                        "value", 3.0),
                Map.of(
                        "indicator_id", "MF-DDI-RT",
                        "indicator_name", "Overnight deposit rate",
                        "date", "2025-01",
                        "value", 2.8));

        CatalogSeriesFilter.FilterResult result =
                CatalogSeriesFilter.apply(rows, Map.of("source_type", "ecb", "set_id", "rates"));

        assertEquals("indicator_id", result.groupField());
        assertEquals("Three-month money market rate", result.indicators().get(0).get("name"));
        assertEquals("Overnight deposit rate", result.indicators().get(1).get("name"));
    }

    @Test
    void geoCompareIgnoresStaleSelectedIndicatorWhenGroupFieldIsGeo() {
        List<Map<String, Object>> rows = List.of(
                Map.of("geo", "CZ", "coicop", "CP09132", "date", "2025-12", "value", 1.8),
                Map.of("geo", "AT", "coicop", "CP09132", "date", "2025-12", "value", 3.8),
                Map.of("geo", "NO", "coicop", "CP09132", "date", "2025-12", "value", 2.4),
                Map.of("geo", "DE", "coicop", "CP09132", "date", "2025-12", "value", 2.1));

        CatalogSeriesFilter.FilterResult result = CatalogSeriesFilter.apply(
                rows,
                Map.of(
                        "source_type", "eurostat",
                        "selected_indicator", "CP09132",
                        "dimension_filters", Map.of(
                                "coicop", "CP09132",
                                "geo", List.of("CZ", "AT", "NO"))));

        assertEquals("geo", result.groupField());
        assertEquals(List.of("CZ", "AT", "NO"), result.selectedIndicators());
        assertEquals(3, result.records().size());
        assertTrue(result.records().stream().allMatch(row -> List.of("CZ", "AT", "NO").contains(row.get("geo"))));
    }

    @Test
    void geoDimensionFiltersMatchIso2AndIso3Codes() {
        List<Map<String, Object>> rows = List.of(
                Map.of("COUNTRY", "AUT", "date", "2024", "value", 2.1),
                Map.of("COUNTRY", "NOR", "date", "2024", "value", 3.2),
                Map.of("COUNTRY", "DEU", "date", "2024", "value", 1.8));

        CatalogSeriesFilter.FilterResult result = CatalogSeriesFilter.apply(
                rows,
                Map.of(
                        "source_type", "imf",
                        "dimension_filters", Map.of("COUNTRY", List.of("AT", "NO"))));

        assertEquals("COUNTRY", result.groupField());
        assertEquals(2, result.records().size());
        assertTrue(result.records().stream().allMatch(row -> List.of("AUT", "NOR").contains(row.get("COUNTRY"))));
    }
}
