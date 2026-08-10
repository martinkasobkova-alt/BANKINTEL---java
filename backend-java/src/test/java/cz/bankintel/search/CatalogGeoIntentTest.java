package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogGeoIntentTest {

    @Test
    void franceDetectedFromFrancieAndFrance() {
        assertCountryDetected("Francie", "FR");
        assertCountryDetected("France unemployment", "FR");
    }

    @Test
    void italyDetectedFromItalieAndItaly() {
        assertCountryDetected("Itálie", "IT");
        assertCountryDetected("Italy GDP", "IT");
    }

    @Test
    void fredTerritoryUsaMapsToUs() {
        Map<String, Object> row = Map.of("territory", "USA / FRED", "set_id", "NROU", "name", "Test");
        assertEquals("US", CatalogGeoIntent.extractRowCountryCode(row));
    }

    @Test
    void targetCountryInTitleOverridesProviderTerritory() {
        Map<String, Object> row = Map.of(
                "territory", "USA / FRED",
                "set_id", "CCUSSP01CZA650N",
                "name", "US Dollar Exchange Rate for Czech Republic");

        assertEquals("CZ", CatalogGeoIntent.extractRowCountryCode(row));
    }

    @Test
    void lowercaseDatasetPrefixIsNotMistakenForCountryCode() {
        Map<String, Object> row = Map.of(
                "source", "eurostat",
                "series_id", "lc_lci_r2_q_wages_salaries_total",
                "name", "Labour cost index by economic activity");

        assertEquals("", CatalogGeoIntent.extractRowCountryCode(row));
    }

    @Test
    void explicitUppercaseCountryTokenInSeriesIdRemainsSupported() {
        Map<String, Object> row = Map.of(
                "source", "ecb2",
                "set_id", "ICP/M.HU.N.000000.4.ANR",
                "name", "HICP inflation");

        assertEquals("HU", CatalogGeoIntent.extractRowCountryCode(row));
    }

    @Test
    void ordinaryWordsContainingCountryFragmentsDoNotCreateGeoIntent() {
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent("spotreba vody v municipalitach");
        assertEquals("unknown", geo.get("type"), "geo=" + geo);
    }

    @Test
    void countryQueryRejectsAggregateRowsFromNestedMetadata() {
        Map<String, Object> geo = Map.of("type", "country", "country_code", "HU", "country_codes", List.of("HU"));
        Map<String, Object> row = Map.of(
                "source_type",
                "imf",
                "set_id",
                "IMF|IMF.RES|WEO|9.0.0|G001.PCPIEPCH",
                "name",
                "Inflace (konec období)",
                "row",
                Map.of("title_original", "World · Inflation, end of period consumer prices"));

        CatalogGeoIntent.GeoRowAdjustment adj = CatalogGeoIntent.rowCountryGeoAdjustment(row, geo);

        assertTrue(adj.hardReject(), adj.toString());
        assertEquals("row_aggregate_for_country_query", adj.reason());
    }

    @Test
    void countryQueryDoesNotRejectEuropeanInstitutionWordingAsAggregate() {
        Map<String, Object> geo = Map.of("type", "country", "country_code", "HU", "country_codes", List.of("HU"));
        Map<String, Object> row = Map.of(
                "source_type",
                "ecb2",
                "set_id",
                "ICP/M.HU.N.000000.4.ANR",
                "title",
                "European Central Bank · HICP inflation");

        CatalogGeoIntent.GeoRowAdjustment adj = CatalogGeoIntent.rowCountryGeoAdjustment(row, geo);

        assertTrue(!adj.hardReject() || !"row_aggregate_for_country_query".equals(adj.reason()), adj.toString());
    }

    private static void assertCountryDetected(String query, String expectedCode) {
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) geo.get("country_codes");
        String countryCode = String.valueOf(geo.getOrDefault("country_code", "")).strip();
        boolean matched = expectedCode.equals(countryCode) || (codes != null && codes.contains(expectedCode));
        assertTrue(matched, "query='" + query + "' geo=" + geo);
    }
}
