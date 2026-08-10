package cz.bankintel.explore.manager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.explore.ExploreGeoCatalog;
import cz.bankintel.explore.ExploreGeoResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the /api/explore/manager/analysis-plan 500 reported for country=CZ:
 * {@code country_context} put {@code geo.get("continent_id")} straight into {@code Map.of(...)},
 * and continent_id is null for geo_mode=country/countries (see {@link ExploreGeoResolver}), which
 * makes {@code Map.of} throw {@link NullPointerException}.
 */
class ManagerAnalysisPlanServiceTest {

    private static ManagerAnalysisPlanService newService() {
        ObjectMapper objectMapper = new ObjectMapper();
        ExploreGeoCatalog geoCatalog = new ExploreGeoCatalog(objectMapper);
        ExploreGeoResolver geoResolver = new ExploreGeoResolver(geoCatalog);
        ManagerRelationshipsService relationshipsService = new ManagerRelationshipsService(objectMapper, geoCatalog);
        ManagerSeriesCatalogService catalogService = new ManagerSeriesCatalogService(objectMapper);
        return new ManagerAnalysisPlanService(geoResolver, relationshipsService, catalogService);
    }

    @Test
    void buildManagerAnalysisPlanSucceedsForSectorWithCountryAndNullCompanyId() {
        ManagerAnalysisPlanService service = newService();

        Map<String, Object> plan = assertDoesNotThrow(() -> service.buildManagerAnalysisPlan(
                "sector",
                "stav bankovnictvi",
                "banking_finance",
                "CZ",
                "country",
                "",
                null,
                List.of(),
                null));

        assertEquals(true, plan.get("ok"));
        assertEquals("sector", plan.get("analysis_mode"));

        assertTrue(plan.get("country_context") instanceof Map<?, ?>);
        Map<?, ?> countryContext = (Map<?, ?>) plan.get("country_context");
        assertTrue(countryContext.containsKey("continent_id"));
        assertNull(countryContext.get("continent_id"));

        assertTrue(plan.get("user_upload_context") instanceof Map<?, ?>);
        Map<?, ?> uploadContext = (Map<?, ?>) plan.get("user_upload_context");
        assertTrue(uploadContext.containsKey("company_id"));
        assertNull(uploadContext.get("company_id"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sectorRefs = (List<Map<String, Object>>) plan.get("sector_series_refs");
        assertFalse(sectorRefs.isEmpty(), "expected banking_finance to resolve non-empty sector series refs");
    }
}
