package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.bankintel.explore.ExploreManagerDiscoveryTerms;
import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.SearchPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogDeepSearchServiceSourceRoutingTest {

    @Test
    void explicitSourceSelectionIsNotExpandedByForeignGeoRouting() {
        SearchPlan plan = new SearchPlan(
                List.of("eurostat"),
                List.of("inflace spanelsko"),
                List.of("eurostat", "imf", "data360", "oecd4"),
                SearchPlan.detectGeo("inflace spanelsko"),
                "inflation",
                "ES",
                "local",
                List.of(),
                Map.of());

        List<String> sources =
                CatalogDeepSearchService.narrowSourcesForGeo("inflace spanelsko", plan, List.of("eurostat"));

        assertEquals(List.of("eurostat"), sources);
    }

    @Test
    void managerDiscoveryKeepsFredEcbImfForForeignGeoInsteadOfCappingToFour() {
        SearchPlan plan = new SearchPlan(
                List.of("eurostat", "imf", "data360", "oecd4"),
                List.of("vyroba rakousko"),
                List.of("eurostat", "imf", "data360", "oecd4"),
                SearchPlan.detectGeo("vyroba rakousko"),
                "manufacturing",
                "AT",
                "local",
                List.of(),
                Map.of());

        List<String> sources =
                CatalogDeepSearchService.narrowSourcesForGeo("vyroba rakousko", plan, List.of(), true);

        assertTrue(sources.contains("eurostat"));
        assertTrue(sources.contains("imf"));
        assertTrue(sources.contains("fred"));
        assertTrue(sources.contains("ecb2"));
        assertTrue(sources.contains("oecd4"));
        assertTrue(!sources.contains("arad"));
        assertTrue(!sources.contains("csu"));
        assertTrue(sources.size() >= 6, "manager foreign discovery must not stay capped at 4 sources");
    }

    @Test
    void sidecarStrengthCountsOnlyPreviewFetchableSeeds() {
        List<Map<String, Object>> seeds = List.of(
                Map.of(CatalogKeys.SOURCE_TYPE, "bis", CatalogKeys.SET_ID, "WS_CBTA||DATAFLOW"),
                Map.of(CatalogKeys.SOURCE_TYPE, "bis", CatalogKeys.SET_ID, "BIS|WS_CBTA|M.AT"));

        assertEquals(1, CatalogDeepSearchService.previewFetchableSeedCount("bis", seeds));
    }

    @Test
    void laneTermsKeepDeterministicProbesAheadOfBroadVariants() {
        assertEquals(
                List.of("eurusd", "euro us dollar exchange rate", "exchange rate", "currency"),
                List.copyOf(CatalogDeepSearchService.buildLaneTerms(
                        "eurusd",
                        List.of("currency", "exchange rate"),
                        List.of("euro us dollar exchange rate", "exchange rate"))));
    }

    @Test
    void managerLaneReservesIntentSlotsForRoe() {
        String q = "ziskovost bank na Slovensku";
        List<String> managerProbes = ExploreManagerDiscoveryTerms.probeTermsFor(q);
        List<String> synonyms = CatalogSearchSynonyms.expandSearchQueries(q);
        LinkedHashSet<String> lane =
                CatalogDeepSearchService.buildManagerLaneTerms(q, synonyms, managerProbes, 13);
        assertTrue(
                lane.stream().anyMatch(t -> t.toLowerCase().contains("return on equity") || "ROE".equals(t)),
                () -> "lane missing ROE: " + lane);
        assertTrue(lane.contains("Gross domestic product") || lane.contains("nama_10_gdp"), () -> "lane missing macro: " + lane);
        assertTrue(lane.size() <= 13);
    }

    @Test
    void managerLaneKeepsTradeSurfaces() {
        String q = "obchod NL DK export import";
        LinkedHashSet<String> lane = CatalogDeepSearchService.buildManagerLaneTerms(
                q,
                List.of("banks", "banking"),
                ExploreManagerDiscoveryTerms.probeTermsFor(q),
                13);
        assertTrue(lane.stream().anyMatch(t -> t.toLowerCase().contains("export")), () -> "lane=" + lane);
        assertTrue(lane.contains("inflation") || lane.contains("Gross domestic product"));
    }

    @Test
    void managerTopicKeepRetainsVerifiedRoeWithWeakSemantics() {
        List<Map<String, Object>> discarded = new ArrayList<>();
        Map<String, Object> roe = new LinkedHashMap<>();
        roe.put(CatalogKeys.SOURCE_TYPE, "ecb2");
        roe.put(CatalogKeys.SET_ID, "CBD2.ROE");
        roe.put("title", "Return on equity of euro area banks");
        roe.put(CatalogKeys.STATUS, "candidate");
        roe.put(CatalogKeys.PREVIEW_STATUS, "verified");
        roe.put(CatalogKeys.PREVIEW_AVAILABLE, true);
        roe.put("preview_row_count", 40);
        roe.put("semantic_match_level", "mismatch");
        roe.put("topic_match", false);
        roe.put("metric_match", false);
        roe.put("domain_match", false);

        List<Map<String, Object>> kept = CatalogDeepSearchService.keepOnlyPreviewSafePossible(
                List.of(roe), discarded, true, "ziskovost bank na Slovensku");

        assertEquals(1, kept.size());
        assertEquals(Boolean.TRUE, kept.getFirst().get("manager_topic_keep"));
        assertEquals(0, discarded.size());
    }

    @Test
    void managerIntentExcludeDropsDwellingsFromPossibleUnderProduction() {
        List<Map<String, Object>> discarded = new ArrayList<>();
        Map<String, Object> dwellings = new LinkedHashMap<>();
        dwellings.put(CatalogKeys.SOURCE_TYPE, "eurostat");
        dwellings.put(CatalogKeys.SET_ID, "nama_10_an6");
        dwellings.put("title", "Gross fixed capital formation - dwellings");
        dwellings.put(CatalogKeys.STATUS, "candidate");
        dwellings.put(CatalogKeys.PREVIEW_STATUS, "verified");
        dwellings.put(CatalogKeys.PREVIEW_AVAILABLE, true);
        dwellings.put("preview_row_count", 20);
        dwellings.put("semantic_match_level", "exact");
        dwellings.put("topic_match", true);
        dwellings.put("metric_match", true);
        dwellings.put("domain_match", true);

        List<Map<String, Object>> kept = CatalogDeepSearchService.keepOnlyPreviewSafePossible(
                List.of(dwellings), discarded, true, "chci investovat do vyroby v rakousku");

        assertEquals(0, kept.size());
        assertEquals(1, discarded.size());
        assertEquals(Boolean.TRUE, discarded.getFirst().get("manager_intent_excluded"));
    }
}
