package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PreviewResponseBuilderTest {

    @SuppressWarnings("unchecked")
    @Test
    void displayFieldsPreferCanonicalTimeSeriesColumns() {
        List<Map<String, Object>> rows = List.of(Map.of(
                "KEY", "ICP.M.U2.N.PCCI00.3.3MM",
                "FREQ", "M",
                "REF_AREA", "U2",
                "TIME_PERIOD", "2025-12",
                "OBS_VALUE", "1.98",
                "date", "2025-12",
                "value", 1.98,
                "amount", 1.98));

        Map<String, Object> response = PreviewResponseBuilder.buildSuccess(
                Map.of("source_type", "ecb", "set_id", "ICP/M.U2.N.PCCI00.3.3MM", "name", "Inflation"),
                Map.of(),
                rows,
                new CatalogSeriesFilter.FilterResult(rows, null, List.of(), "", List.of()));

        List<String> fields = (List<String>) response.get("fields");
        assertEquals("date", fields.get(0));
        assertEquals("value", fields.get(1));
        assertFalse(fields.contains("KEY"));
        assertFalse(fields.contains("TIME_PERIOD"));
        assertFalse(fields.contains("OBS_VALUE"));
        assertFalse(fields.contains("amount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void doesNotExposeOhlcvColumnsAsDimensionsForStockPreviews() {
        List<Map<String, Object>> rows = List.of(
                row("date", "2022-08-05", "value", 165.35, "open", 163.21, "high", 165.85, "low", 163.0,
                        "close", 165.35, "volume", 56697000, "ticker", "AAPL"),
                row("date", "2022-08-08", "value", 166.37, "open", 166.37, "high", 167.81, "low", 165.82,
                        "close", 166.37, "volume", 51876500, "ticker", "AAPL"));

        Map<String, Object> response = PreviewResponseBuilder.buildSuccess(
                Map.of("source_type", "yahoo_finance", "set_id", "AAPL", "name", "Apple Inc."),
                Map.of(),
                rows,
                new CatalogSeriesFilter.FilterResult(rows, null, List.of(), "", List.of()));

        Map<String, Object> availableDimensions = (Map<String, Object>) response.get("available_dimensions");
        assertTrue(availableDimensions.isEmpty());
        List<Map<String, Object>> selectableDimensions =
                (List<Map<String, Object>>) response.get("selectable_dimensions");
        assertTrue(selectableDimensions.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void exposesRealDimensionsButNotTimeOrMeasureFields() {
        List<Map<String, Object>> allRows = List.of(
                row(
                        "KEY", "ICP.M.ES.N.CP00.PC",
                        "TIME_PERIOD", "2025-01",
                        "OBS_VALUE", "2.1",
                        "date", "2025-01",
                        "value", 2.1,
                        "REF_AREA", "ES",
                        "ref_area_label", "Spain",
                        "FREQ", "M",
                        "freq_label", "Monthly",
                        "ADJUSTMENT", "N",
                        "ADJUSTMENT_label", "Neither seasonally nor calendar adjusted",
                        "unit", "PC",
                        "unit_label", "Percent",
                        "coicop", "CP00",
                        "coicop_label", "All-items HICP"),
                row(
                        "KEY", "ICP.A.CZ.N.CP01.I15",
                        "TIME_PERIOD", "2025",
                        "OBS_VALUE", "112.4",
                        "date", "2025",
                        "value", 112.4,
                        "REF_AREA", "CZ",
                        "ref_area_label", "Czechia",
                        "FREQ", "A",
                        "freq_label", "Annual",
                        "ADJUSTMENT", "N",
                        "ADJUSTMENT_label", "Neither seasonally nor calendar adjusted",
                        "unit", "I15",
                        "unit_label", "Index",
                        "coicop", "CP01",
                        "coicop_label", "Food"));
        List<Map<String, Object>> filteredRows = List.of(allRows.getFirst());

        Map<String, Object> response = PreviewResponseBuilder.buildSuccess(
                Map.of("source_type", "ecb", "set_id", "ICP", "name", "Inflation"),
                Map.of(),
                filteredRows,
                new CatalogSeriesFilter.FilterResult(filteredRows, null, List.of(), "", List.of(), allRows));

        Map<String, Object> dimensions = (Map<String, Object>) response.get("available_dimensions");
        assertTrue(dimensions.containsKey("REF_AREA"));
        assertTrue(dimensions.containsKey("FREQ"));
        assertTrue(dimensions.containsKey("ADJUSTMENT"));
        assertTrue(dimensions.containsKey("unit"));
        assertTrue(dimensions.containsKey("coicop"));
        assertFalse(dimensions.containsKey("TIME_PERIOD"));
        assertFalse(dimensions.containsKey("OBS_VALUE"));
        assertFalse(dimensions.containsKey("date"));
        assertFalse(dimensions.containsKey("value"));
        assertFalse(dimensions.containsKey("KEY"));

        List<Map<String, Object>> selectable = (List<Map<String, Object>>) response.get("selectable_dimensions");
        assertTrue(selectable.stream().anyMatch(row -> "REF_AREA".equals(row.get("field"))));
        assertTrue(selectable.stream().anyMatch(row -> "unit".equals(row.get("field"))));

        Map<String, Object> refArea = (Map<String, Object>) dimensions.get("REF_AREA");
        List<Map<String, Object>> refAreaOptions = (List<Map<String, Object>>) refArea.get("sample_options");
        assertEquals("Spain", refAreaOptions.getFirst().get("label"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void keepsCsuAndAradDimensionsButDropsCzechTimeAndMeasureFields() {
        String regionField = "\u00dazem\u00ed-Kraj";
        String monthsField = "M\u011bs\u00edce";
        List<Map<String, Object>> allRows = List.of(
                row(
                        "date", "2025-01",
                        "value", 152.3,
                        "Hodnota", "152.3",
                        monthsField, "leden 2025",
                        "CZ-COICOP", "CP00",
                        "Ukazatel", "HICP",
                        regionField, "Hlavni mesto Praha",
                        "indicator_id", "SMV1M1201",
                        "indicator_name", "ARAD one",
                        "snapshot_id", "snap-1"),
                row(
                        "date", "2025-02",
                        "value", 154.5,
                        "Hodnota", "154.5",
                        monthsField, "unor 2025",
                        "CZ-COICOP", "CP01",
                        "Ukazatel", "HICP",
                        regionField, "Jihomoravsky kraj",
                        "indicator_id", "SMV1M1202",
                        "indicator_name", "ARAD two",
                        "snapshot_id", "snap-1"));

        Map<String, Object> response = PreviewResponseBuilder.buildSuccess(
                Map.of("source_type", "csu", "set_id", "CEN0101CT02", "name", "CSU"),
                Map.of(),
                List.of(allRows.getFirst()),
                new CatalogSeriesFilter.FilterResult(List.of(allRows.getFirst()), null, List.of(), "", List.of(), allRows));

        Map<String, Object> dimensions = (Map<String, Object>) response.get("available_dimensions");
        assertTrue(dimensions.containsKey("CZ-COICOP"));
        assertTrue(dimensions.containsKey("Ukazatel"));
        assertTrue(dimensions.containsKey(regionField));
        assertTrue(dimensions.containsKey("indicator_id"));
        assertFalse(dimensions.containsKey(monthsField));
        assertFalse(dimensions.containsKey("Hodnota"));
        assertFalse(dimensions.containsKey("indicator_name"));
        assertFalse(dimensions.containsKey("snapshot_id"));

        List<String> fields = (List<String>) response.get("fields");
        assertFalse(fields.contains(monthsField));
        assertFalse(fields.contains("Hodnota"));
        assertFalse(fields.contains("snapshot_id"));

        Map<String, Object> aradIndicator = (Map<String, Object>) dimensions.get("indicator_id");
        assertEquals("Ukazatel", aradIndicator.get("label"));
        List<Map<String, Object>> aradOptions = (List<Map<String, Object>>) aradIndicator.get("sample_options");
        assertEquals("ARAD one", aradOptions.getFirst().get("label"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void mergesSourceMetadataDimensionsWithFilteredRows() {
        Map<String, Object> geoMeta = row(
                "label", "geo",
                "values", List.of("AT", "NO"),
                "sample_values", List.of("AT", "NO"),
                "sample_options", List.of(
                        row("code", "AT", "label", "Austria"),
                        row("code", "NO", "label", "Norway")),
                "value_labels", row("AT", "Austria", "NO", "Norway"));
        List<Map<String, Object>> rows = List.of(row(
                "TIME_PERIOD", "2025-12",
                "date", "2025-12",
                "value", 3.8,
                "geo", "AT",
                "geo_label", "Austria"));

        Map<String, Object> response = PreviewResponseBuilder.buildSuccess(
                Map.of("source_type", "eurostat", "set_id", "prc_hicp_manr", "name", "HICP"),
                Map.of("_preview_available_dimensions", Map.of("geo", geoMeta)),
                rows,
                new CatalogSeriesFilter.FilterResult(rows, null, List.of(), "", List.of(), rows));

        Map<String, Object> dimensions = (Map<String, Object>) response.get("available_dimensions");
        Map<String, Object> geo = (Map<String, Object>) dimensions.get("geo");
        assertEquals(List.of("AT", "NO"), geo.get("values"));

        List<Map<String, Object>> selectable = (List<Map<String, Object>>) response.get("selectable_dimensions");
        Map<String, Object> geoSelectable = selectable.stream()
                .filter(item -> "geo".equals(item.get("field")))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("AT", "NO"), geoSelectable.get("values"));
    }

    private static Map<String, Object> row(Object... entries) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            out.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return out;
    }
}
