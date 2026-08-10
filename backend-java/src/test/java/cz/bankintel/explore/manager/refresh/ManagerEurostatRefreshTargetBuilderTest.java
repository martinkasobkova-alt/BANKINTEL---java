package cz.bankintel.explore.manager.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cz.bankintel.explore.manager.refresh.ManagerEurostatRefreshTargetBuilder.RefreshTarget;
import cz.bankintel.sources.eurostat.EurostatDimensionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ManagerEurostatRefreshTargetBuilderTest {

    private static Map<String, Object> row(String seriesId, Map<String, Object> queryParams, List<String> geoCoverage) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("segment_id", "manufacturing_general");
        row.put("series_id", seriesId);
        row.put("dataset_id", "sts_inpr_m");
        row.put("query_params", queryParams);
        row.put("geo_coverage", geoCoverage);
        return row;
    }

    @Test
    void intersectsRequestedGeosWithRowGeoCoverageNotBlindlyAll() {
        EurostatDimensionService dimensionService = mock(EurostatDimensionService.class);
        ManagerEurostatRefreshTargetBuilder builder = new ManagerEurostatRefreshTargetBuilder(dimensionService);
        Map<String, Object> curated = Map.of("freq", "M", "unit", "I21", "s_adj", "SCA", "indic_bt", "PRD", "nace_r2", "C", "geo", "EU27_2020");
        Map<String, Object> row = row("sts_inpr_m_manufacturing_total", curated, List.of("IT", "DE", "CZ"));

        List<RefreshTarget> targets = builder.buildTargets(List.of(row), Set.of("IT", "PL", "CZ"));

        // PL requested but not in this row's geo_coverage -> must not produce a target for PL.
        List<String> geos = targets.stream().map(RefreshTarget::geo).sorted().toList();
        assertEquals(List.of("CZ", "IT"), geos);
        verifyNoInteractions(dimensionService);
    }

    @Test
    void copiesCuratedQueryParamsUnchangedExceptGeo() {
        EurostatDimensionService dimensionService = mock(EurostatDimensionService.class);
        ManagerEurostatRefreshTargetBuilder builder = new ManagerEurostatRefreshTargetBuilder(dimensionService);
        Map<String, Object> curated = Map.of("freq", "M", "unit", "I21", "s_adj", "SCA", "indic_bt", "PRD", "nace_r2", "C", "geo", "EU27_2020");
        Map<String, Object> row = row("sts_inpr_m_manufacturing_total", curated, List.of("IT"));

        List<RefreshTarget> targets = builder.buildTargets(List.of(row), Set.of("IT"));

        assertEquals(1, targets.size());
        Map<String, Object> resolved = targets.get(0).queryParams();
        assertEquals("IT", resolved.get("geo"));
        assertEquals("M", resolved.get("freq"));
        assertEquals("I21", resolved.get("unit"));
        assertEquals("SCA", resolved.get("s_adj"));
        assertEquals("PRD", resolved.get("indic_bt"));
        assertEquals("C", resolved.get("nace_r2"));
        verifyNoInteractions(dimensionService);
    }

    @Test
    void fallsBackToDimensionServiceOnlyWhenQueryParamsMissing() {
        EurostatDimensionService dimensionService = mock(EurostatDimensionService.class);
        when(dimensionService.resolvePreviewQueryParams("sts_inpr_m", "IT"))
                .thenReturn(Map.of("geo", "IT", "unit", "I21"));
        ManagerEurostatRefreshTargetBuilder builder = new ManagerEurostatRefreshTargetBuilder(dimensionService);
        Map<String, Object> row = row("sts_inpr_m_manufacturing_total", Map.of(), List.of("IT"));

        List<RefreshTarget> targets = builder.buildTargets(List.of(row), Set.of("IT"));

        assertEquals(1, targets.size());
        assertEquals("IT", targets.get(0).queryParams().get("geo"));
        org.mockito.Mockito.verify(dimensionService).resolvePreviewQueryParams("sts_inpr_m", "IT");
    }

    @Test
    void skipsRowWithNoUsableGeoCoverageIntersection() {
        EurostatDimensionService dimensionService = mock(EurostatDimensionService.class);
        ManagerEurostatRefreshTargetBuilder builder = new ManagerEurostatRefreshTargetBuilder(dimensionService);
        Map<String, Object> row = row("x", Map.of("geo", "EU27_2020"), List.of("PT", "ES"));

        List<RefreshTarget> targets = builder.buildTargets(List.of(row), Set.of("IT", "DE"));

        assertTrue(targets.isEmpty());
    }
}
