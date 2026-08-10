package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cz.bankintel.search.model.CatalogKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogDeepSearchServiceCandidateFilteringTest {

    @Test
    void mainPossibleListKeepsOnlyRowsWithVerifiedPreviewOrExplicitSemanticMatch() {
        List<Map<String, Object>> discarded = new ArrayList<>();

        List<Map<String, Object>> kept = CatalogDeepSearchService.keepOnlyPreviewSafePossible(
                List.of(
                        Map.of(
                                CatalogKeys.SOURCE_TYPE,
                                "fred",
                                CatalogKeys.SET_ID,
                                "ACOILBRENTEU",
                                CatalogKeys.STATUS,
                                "verified",
                                CatalogKeys.PREVIEW_STATUS,
                                "verified",
                                CatalogKeys.PREVIEW_AVAILABLE,
                                true,
                                "preview_row_count",
                                120,
                                "semantic_match_level",
                                "exact",
                                "topic_match",
                                true,
                                "metric_match",
                                true,
                                "domain_match",
                                true),
                        Map.of(
                                CatalogKeys.SOURCE_TYPE,
                                "ecb2",
                                CatalogKeys.SET_ID,
                                "MIR",
                                CatalogKeys.STATUS,
                                "candidate",
                                CatalogKeys.PREVIEW_STATUS,
                                "not_selected_for_preview",
                                CatalogKeys.PREVIEW_AVAILABLE,
                                false,
                                "semantic_match_level",
                                "partial",
                                "topic_match",
                                true,
                                "metric_match",
                                true,
                                "domain_match",
                                true),
                        Map.of(
                                CatalogKeys.SOURCE_TYPE,
                                "oecd4",
                                CatalogKeys.SET_ID,
                                "OECD4|housing_prices|dataset",
                                CatalogKeys.STATUS,
                                "candidate",
                                CatalogKeys.PREVIEW_STATUS,
                                "catalog_match",
                                CatalogKeys.PREVIEW_AVAILABLE,
                                false),
                        Map.of(
                                CatalogKeys.SOURCE_TYPE,
                                "bis",
                                CatalogKeys.SET_ID,
                                "WS_CBS_PUB||DATAFLOW",
                                CatalogKeys.STATUS,
                                "candidate",
                                CatalogKeys.PREVIEW_STATUS,
                                "unverified",
                                CatalogKeys.PREVIEW_AVAILABLE,
                                false)),
                discarded);

        assertEquals(2, kept.size());
        assertEquals("ACOILBRENTEU", kept.getFirst().get(CatalogKeys.SET_ID));
        assertEquals("MIR", kept.get(1).get(CatalogKeys.SET_ID));
        assertEquals(2, discarded.size());
        assertEquals("preview_not_verified", discarded.getFirst().get("discard_reason"));
    }

    @Test
    void mainPossibleListDropsRowsWithVerifiedPreviewButWeakSemantics() {
        List<Map<String, Object>> discarded = new ArrayList<>();

        List<Map<String, Object>> kept = CatalogDeepSearchService.keepOnlyPreviewSafePossible(
                List.of(
                        Map.of(
                                CatalogKeys.SOURCE_TYPE,
                                "arad",
                                CatalogKeys.SET_ID,
                                "1125",
                                CatalogKeys.STATUS,
                                "candidate",
                                CatalogKeys.PREVIEW_STATUS,
                                "verified",
                                CatalogKeys.PREVIEW_AVAILABLE,
                                true,
                                "preview_row_count",
                                173,
                                "semantic_match_level",
                                "mismatch",
                                "topic_match",
                                false,
                                "metric_match",
                                false,
                                "domain_match",
                                false)),
                discarded);

        assertEquals(0, kept.size());
        assertEquals(1, discarded.size());
        assertEquals("semantic_not_actionable", discarded.getFirst().get("discard_reason"));
    }

    @Test
    void retainManagerCoreMacroSeedsReinsertsGdpDroppedByLaneCap() {
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ranked.add(Map.of(CatalogKeys.SET_ID, "sts_other_" + i, CatalogKeys.SEARCH_SCORE, 100 - i));
        }
        List<Map<String, Object>> sidecar = List.of(
                Map.of(CatalogKeys.SET_ID, "nama_10_gdp", "title", "Gross domestic product (GDP)", CatalogKeys.SEARCH_SCORE, 1),
                Map.of(CatalogKeys.SET_ID, "sts_other_0", CatalogKeys.SEARCH_SCORE, 100));

        List<Map<String, Object>> kept =
                CatalogDeepSearchService.retainManagerCoreMacroSeeds("eurostat", ranked, sidecar, 8);

        assertEquals("nama_10_gdp", kept.getFirst().get(CatalogKeys.SET_ID));
        assertEquals(8, kept.size());
        assertEquals(
                1, kept.stream().filter(r -> "nama_10_gdp".equals(r.get(CatalogKeys.SET_ID))).count());
    }
}
