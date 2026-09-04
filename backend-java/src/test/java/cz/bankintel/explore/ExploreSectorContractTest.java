package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the {@code Map.of(...)} null-value crash reported for /api/explore/sector,
 * /api/explore/sector/preset-preview and the SSE stream: geo.continent_id is null whenever
 * geo_mode is "country", "countries" or "unknown" (see {@link ExploreGeoResolver}), and
 * {@code Map.of} throws {@link NullPointerException} on any null value.
 */
class ExploreSectorContractTest {

    @Test
    void buildEmptyContractDoesNotThrowWhenContinentIdIsNull() {
        // geo_mode=country -> ExploreGeoResolver leaves continent_id null (only "continent" mode sets it).
        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("mode", "countries");
        geo.put("country_codes", List.of("CZ"));
        geo.put("continent_id", null);
        geo.put("primary_code", "CZ");
        geo.put("display", "Česko");

        Map<String, Object> ctx = ExploreSectorContract.buildContext(
                "banking_finance", "stav bankovnictvi", geo, Map.of(), Map.of());

        Map<String, Object> out = assertDoesNotThrow(
                () -> ExploreSectorContract.buildEmptyContract(ctx, "catalog_deep_search", false));

        assertEquals(true, out.get("ok"));
        assertTrue(out.get("geo") instanceof Map<?, ?>);
        Map<?, ?> geoOut = (Map<?, ?>) out.get("geo");
        assertTrue(geoOut.containsKey("continent_id"));
        assertNull(geoOut.get("continent_id"));
    }

    @Test
    void buildEmptyContractDoesNotThrowForUnknownGeoMode() {
        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("mode", "unknown");
        geo.put("country_codes", List.of());
        geo.put("continent_id", null);
        geo.put("primary_code", "U2");
        geo.put("display", "??");

        Map<String, Object> ctx = ExploreSectorContract.buildContext(
                "banking_finance", "stav bankovnictvi", geo, Map.of(), Map.of());

        assertDoesNotThrow(() -> ExploreSectorContract.buildEmptyContract(ctx, "catalog_deep_search", false));
    }

    @Test
    void buildEmptyContractFallsBackToDefaultEcosystemWithoutThrowing() {
        // When "sector_ecosystem" is absent from ctx, buildEmptyContract falls back to
        // defaultSectorEcosystem(), whose primary_sector map previously used Map.of() with
        // literal nulls for sector_id/sector_name_cs/sector_name_en.
        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("mode", "none");
        geo.put("country_codes", List.of());
        geo.put("continent_id", null);
        geo.put("primary_code", "U2");
        geo.put("display", "Svět (globální kontext)");

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("sector", "banking_finance");
        ctx.put("query", "banking_finance");
        ctx.put("mode", "none");
        ctx.put("geo", geo);

        Map<String, Object> out = assertDoesNotThrow(
                () -> ExploreSectorContract.buildEmptyContract(ctx, "catalog_deep_search", false));

        @SuppressWarnings("unchecked")
        Map<String, Object> ecosystem = (Map<String, Object>) out.get("sector_ecosystem");
        @SuppressWarnings("unchecked")
        Map<String, Object> primarySector = (Map<String, Object>) ecosystem.get("primary_sector");
        assertNull(primarySector.get("sector_id"));
        assertNull(primarySector.get("sector_name_cs"));
        assertNull(primarySector.get("sector_name_en"));
    }

    @Test
    void mergeIndicatorsWarnsHonestlyWhenOnlyGenericMacroContextIsAvailable() {
        // ExploreDiscoveryService no longer relabels generic macro rows (GDP/inflation/FX...) as
        // "sector_indicators" when nothing sector-specific matched - sectorIndicators stays
        // genuinely empty. The user must be told plainly that only macro backdrop is shown, instead
        // of empty_hint staying silent just because macroIndicators happens to be non-empty.
        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("mode", "countries");
        geo.put("country_codes", List.of("SK"));
        geo.put("continent_id", null);
        geo.put("display", "Slovensko");
        Map<String, Object> ctx = ExploreSectorContract.buildContext(
                "automotive", "vyvoj automobiloveho prumyslu na Slovensku", geo, Map.of(), Map.of());
        Map<String, Object> base = ExploreSectorContract.buildEmptyContract(ctx, "catalog_deep_search", false);

        List<Map<String, Object>> macroOnly = List.of(Map.of("title", "Hrubý domácí produkt (HDP)"));
        Map<String, Object> out = ExploreSectorContract.mergeIndicators(base, List.of(), macroOnly, 1, "completed", false);

        assertTrue(String.valueOf(out.get("empty_hint")).contains("obecný makroekonomický kontext"));
    }

    @Test
    void buildEmptyContractDefaultsWebSourcesToEmptyAndNotAttempted() {
        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("mode", "none");
        geo.put("country_codes", List.of());
        geo.put("continent_id", null);
        geo.put("display", "Svět (globální kontext)");
        Map<String, Object> ctx = ExploreSectorContract.buildContext(
                "banking_finance", "stav bankovnictvi", geo, Map.of(), Map.of());

        Map<String, Object> out = ExploreSectorContract.buildEmptyContract(ctx, "catalog_deep_search", false);

        assertEquals(List.of(), out.get("web_sources"));
        assertEquals(0, out.get("web_sources_total"));
        assertEquals("not_attempted", out.get("web_research_status"));
    }

    @Test
    void mergeWebSourcesOverlaysWebFieldsWithoutTouchingIndicatorFields() {
        // web_sources is a separate report section from sector_indicators/macro_indicators - it
        // must never leak into or be overwritten by the catalog-indicator merge, and vice versa.
        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("mode", "none");
        geo.put("country_codes", List.of());
        geo.put("continent_id", null);
        geo.put("display", "Svět (globální kontext)");
        Map<String, Object> ctx = ExploreSectorContract.buildContext(
                "banking_finance", "stav bankovnictvi", geo, Map.of(), Map.of());
        Map<String, Object> base = ExploreSectorContract.buildEmptyContract(ctx, "catalog_deep_search", false);
        Map<String, Object> merged =
                ExploreSectorContract.mergeIndicators(base, List.of(), List.of(), 0, "completed", false);

        List<Map<String, Object>> webSources =
                List.of(Map.of("title", "HDP Prahy", "url", "https://ec.europa.eu/x"));
        Map<String, Object> out = ExploreSectorContract.mergeWebSources(merged, webSources, "found");

        assertEquals(webSources, out.get("web_sources"));
        assertEquals(1, out.get("web_sources_total"));
        assertEquals("found", out.get("web_research_status"));
        assertEquals(List.of(), out.get("sector_indicators"));
    }

    private static Map<String, Object> emptyBase() {
        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("mode", "none");
        geo.put("country_codes", List.of());
        geo.put("continent_id", null);
        geo.put("display", "Svět (globální kontext)");
        Map<String, Object> ctx = ExploreSectorContract.buildContext(
                "banking_finance", "stav bankovnictvi", geo, Map.of(), Map.of());
        return ExploreSectorContract.buildEmptyContract(ctx, "catalog_deep_search", false);
    }

    // Živě zjištěno: appka slibovala 8 report sekcí, ale jen sector_indicators/macro_indicators se
    // kdy naplnily - zbylých 5 (leading/cost/financial/external/risk) bylo natvrdo vždy prázdných.

    @Test
    void mergeIndicatorsSplitsNonMacroRowsIntoFineSectionsByManagerCategory() {
        List<Map<String, Object>> sectorIndicators = List.of(
                Map.of("title", "Automotive production", "manager_category", "sector_indicators"),
                Map.of("title", "PMI", "manager_category", "leading_indicators"),
                Map.of("title", "PPI", "manager_category", "cost_indicators"),
                Map.of("title", "ROE", "manager_category", "financial_indicators"),
                Map.of("title", "Exports", "manager_category", "external_indicators"),
                Map.of("title", "NPL ratio", "manager_category", "risk_indicators"));

        Map<String, Object> out = ExploreSectorContract.mergeIndicators(
                emptyBase(), sectorIndicators, List.of(), sectorIndicators.size(), "completed", false);

        assertEquals(1, ((List<?>) out.get("sector_indicators")).size());
        assertEquals("Automotive production", ((Map<?, ?>) ((List<?>) out.get("sector_indicators")).getFirst()).get("title"));
        assertEquals("PMI", ((Map<?, ?>) ((List<?>) out.get("leading_indicators")).getFirst()).get("title"));
        assertEquals("PPI", ((Map<?, ?>) ((List<?>) out.get("cost_indicators")).getFirst()).get("title"));
        assertEquals("ROE", ((Map<?, ?>) ((List<?>) out.get("financial_indicators")).getFirst()).get("title"));
        assertEquals("Exports", ((Map<?, ?>) ((List<?>) out.get("external_indicators")).getFirst()).get("title"));
        assertEquals("NPL ratio", ((Map<?, ?>) ((List<?>) out.get("risk_indicators")).getFirst()).get("title"));
    }

    @Test
    void recommendedChartSetKeepsTheFullSectorSetAfterTheFineSplit() {
        List<Map<String, Object>> sectorIndicators = List.of(
                Map.of("title", "Automotive production", "manager_category", "sector_indicators"),
                Map.of("title", "PMI", "manager_category", "leading_indicators"));

        Map<String, Object> out = ExploreSectorContract.mergeIndicators(
                emptyBase(), sectorIndicators, List.of(), sectorIndicators.size(), "completed", false);

        // recommended_chart_set nesmí zeštíhlet o to, co odteklo do leading_indicators - jinak by
        // "Doporučené pro srovnání" nesmyslně ztratilo řady jen proto, že se lépe zařadily.
        assertEquals(2, ((List<?>) out.get("recommended_chart_set")).size());
    }

    @Test
    void macroRowsAreNeverSplitIntoFineSections() {
        List<Map<String, Object>> macroIndicators =
                List.of(Map.of("title", "GDP", "manager_category", "macro_indicators"));

        Map<String, Object> out =
                ExploreSectorContract.mergeIndicators(emptyBase(), List.of(), macroIndicators, 1, "completed", false);

        assertEquals(1, ((List<?>) out.get("macro_indicators")).size());
        assertEquals(List.of(), out.get("leading_indicators"));
        assertEquals(List.of(), out.get("cost_indicators"));
        assertEquals(List.of(), out.get("financial_indicators"));
        assertEquals(List.of(), out.get("external_indicators"));
        assertEquals(List.of(), out.get("risk_indicators"));
    }
}
