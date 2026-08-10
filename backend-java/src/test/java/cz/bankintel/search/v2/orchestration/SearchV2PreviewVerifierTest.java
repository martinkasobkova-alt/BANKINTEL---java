package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.bankintel.search.CatalogPreviewService;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SemanticDecision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SearchV2PreviewVerifierTest {

    @Test
    void topPreviewChecksOnlyRequestedTopN() {
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(any())).thenReturn(okPreview());
        SearchV2PreviewVerifier verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        var result = verifier.verifyTopOnly(
                List.of(result("s1"), result("s2"), result("s3"), result("s4"), result("s5")),
                3,
                List.of());

        assertThat(result.statuses()).hasSize(3);
        assertThat(result.statuses().getFirst()).containsKeys("preview_payload", "preview_request_payload", "query_params");
        org.mockito.Mockito.verify(previewService, org.mockito.Mockito.times(3)).preview(any());
    }

    @Test
    void duplicateSeriesIsVerifiedOnceInsideOneRequest() {
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(any())).thenReturn(okPreview());
        SearchV2PreviewVerifier verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        var result = verifier.verifyTopOnly(List.of(result("same"), result("same")), 2, List.of("CZ"));

        assertThat(result.statuses()).hasSize(2);
        assertThat(result.accepted()).hasSize(2);
        org.mockito.Mockito.verify(previewService, org.mockito.Mockito.times(1)).preview(any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void globalSentinelIsNotSentToAProviderAsCountryFilter() {
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(any())).thenReturn(okPreview());
        SearchV2PreviewVerifier verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        verifier.verifyTopOnly(List.of(result("global-series")), 1, List.of("GLOBAL"));

        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(previewService).preview(payload.capture());
        assertThat(payload.getValue()).doesNotContainKeys("country", "geo_intent");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void datasetOrientedProviderUsesRealDatasetForSyntheticMetadataCandidate() {
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(any())).thenReturn(okPreview());
        SearchV2PreviewVerifier verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());
        SearchCandidate candidate = new SearchCandidate(
                "eurostat:synthetic",
                "dataset_wages_total",
                "Wage index",
                "",
                "eurostat",
                "dataset_wages",
                "",
                "Q",
                "Index",
                "",
                List.of("average_wages"),
                List.of(),
                List.of(),
                "",
                1,
                "wages",
                List.of(),
                Map.of());

        verifier.verifyTopOnly(
                List.of(new SearchResult(candidate, decision(candidate.seriesId()), 0)),
                1,
                List.of("AT"));

        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(previewService).preview(payload.capture());
        assertThat(payload.getValue())
                .containsEntry("set_id", "dataset_wages")
                .containsEntry("id", "dataset_wages")
                .containsEntry("country", "AT");
    }

    // --- canonical preview request identity / verificationKey collision-fix tests (2026-07-30) ---

    @Test
    void sameDatasetDifferentSeriesIdGetIndependentPreviewCallsAndOwnSeriesIdInStatus() {
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(any())).thenReturn(okPreview());
        SearchV2PreviewVerifier verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        SearchCandidate allItems = eurostatCandidate("prc_hicp_midx_hicp_all_items", "prc_hicp_midx", "", "M", Map.of());
        SearchCandidate transport = eurostatCandidate("prc_hicp_midx_hicp_transport", "prc_hicp_midx", "", "M", Map.of());

        var result = verifier.verifyTopOnly(
                List.of(
                        new SearchResult(allItems, decision(allItems.seriesId()), 0),
                        new SearchResult(transport, decision(transport.seriesId()), 1)),
                2,
                List.of("RU"));

        assertThat(result.uniqueRequestCount()).isEqualTo(2);
        assertThat(result.statuses().stream().map(s -> s.get("series_id")).toList())
                .containsExactlyInAnyOrder("prc_hicp_midx_hicp_all_items", "prc_hicp_midx_hicp_transport");
        org.mockito.Mockito.verify(previewService, org.mockito.Mockito.times(2)).preview(any());
    }

    @Test
    void sameDatasetSameSeriesIdSameParamsStillShareOnePreviewCall() {
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(any())).thenReturn(okPreview());
        SearchV2PreviewVerifier verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        SearchCandidate a = eurostatCandidate("prc_hicp_midx_hicp_all_items", "prc_hicp_midx", "", "M", Map.of());
        SearchCandidate b = eurostatCandidate("prc_hicp_midx_hicp_all_items", "prc_hicp_midx", "", "M", Map.of());

        var result = verifier.verifyTopOnly(
                List.of(new SearchResult(a, decision("x"), 0), new SearchResult(b, decision("y"), 1)), 2, List.of("RU"));

        assertThat(result.uniqueRequestCount()).isEqualTo(1);
        org.mockito.Mockito.verify(previewService, org.mockito.Mockito.times(1)).preview(any());
    }

    @Test
    void sameSeriesDifferentExplicitGeoGetIndependentPreviewCalls() {
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(any())).thenReturn(okPreview());
        SearchV2PreviewVerifier verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        SearchCandidate cz = eurostatCandidate("some_series", "some_dataset", "CZ", "A", Map.of());
        SearchCandidate de = eurostatCandidate("some_series", "some_dataset", "DE", "A", Map.of());

        var result = verifier.verifyTopOnly(
                List.of(new SearchResult(cz, decision("cz"), 0), new SearchResult(de, decision("de"), 1)), 2, List.of());

        assertThat(result.uniqueRequestCount()).isEqualTo(2);
        org.mockito.Mockito.verify(previewService, org.mockito.Mockito.times(2)).preview(any());
    }

    @Test
    void sameSeriesDifferentFrequencyGetIndependentPreviewCalls() {
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(any())).thenReturn(okPreview());
        SearchV2PreviewVerifier verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        SearchCandidate monthly = eurostatCandidate("some_series", "some_dataset", "", "M", Map.of());
        SearchCandidate annual = eurostatCandidate("some_series", "some_dataset", "", "A", Map.of());

        var result = verifier.verifyTopOnly(
                List.of(new SearchResult(monthly, decision("m"), 0), new SearchResult(annual, decision("a"), 1)),
                2,
                List.of());

        assertThat(result.uniqueRequestCount()).isEqualTo(2);
        org.mockito.Mockito.verify(previewService, org.mockito.Mockito.times(2)).preview(any());
    }

    @Test
    void queryParamsInDifferentMapInsertionOrderProduceTheSameCanonicalKey() {
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(any())).thenReturn(okPreview());
        SearchV2PreviewVerifier verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        java.util.Map<String, Object> paramsA = new java.util.LinkedHashMap<>();
        paramsA.put("coicop", "CP01");
        paramsA.put("geo", "RU");
        java.util.Map<String, Object> paramsB = new java.util.LinkedHashMap<>();
        paramsB.put("geo", "RU");
        paramsB.put("coicop", "CP01");

        SearchCandidate a = eurostatCandidate("s1", "d1", "", "M", Map.of("query_params", paramsA));
        SearchCandidate b = eurostatCandidate("s1", "d1", "", "M", Map.of("query_params", paramsB));

        var result = verifier.verifyTopOnly(
                List.of(new SearchResult(a, decision("a"), 0), new SearchResult(b, decision("b"), 1)), 2, List.of());

        assertThat(result.uniqueRequestCount())
                .as("insertion-order-only difference in query_params must not fragment the cache key")
                .isEqualTo(1);
        org.mockito.Mockito.verify(previewService, org.mockito.Mockito.times(1)).preview(any());
    }

    @Test
    void nullRawAndAbsentQueryParamsKeyShareIdentityButExplicitEmptyMapIsDistinct() {
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(any())).thenReturn(okPreview());
        SearchV2PreviewVerifier verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        SearchCandidate nullRaw = eurostatCandidate("s1", "d1", "", "M", null);
        SearchCandidate noParamsKey = eurostatCandidate("s1", "d1", "", "M", Map.of("other_field", "x"));
        SearchCandidate explicitEmptyParams = eurostatCandidate("s1", "d1", "", "M", Map.of("query_params", Map.of()));

        var absentPair = verifier.verifyTopOnly(
                List.of(new SearchResult(nullRaw, decision("a"), 0), new SearchResult(noParamsKey, decision("b"), 1)),
                2,
                List.of());
        assertThat(absentPair.uniqueRequestCount())
                .as("null raw and an absent query_params key both mean 'no filter info supplied'")
                .isEqualTo(1);

        var explicitVsAbsent = verifier.verifyTopOnly(
                List.of(
                        new SearchResult(nullRaw, decision("a"), 0),
                        new SearchResult(explicitEmptyParams, decision("c"), 1)),
                2,
                List.of());
        assertThat(explicitVsAbsent.uniqueRequestCount())
                .as("an explicitly-empty query_params map is a distinct identity from an absent one")
                .isEqualTo(2);
    }

    @Test
    void fourEurostatHicpSubSeriesEachGetOwnPreviewCallAndOwnSeriesIdInStatus() {
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(any())).thenReturn(okPreview());
        SearchV2PreviewVerifier verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        SearchCandidate allItems = eurostatCandidate("prc_hicp_midx_hicp_all_items", "prc_hicp_midx", "", "M", Map.of());
        SearchCandidate transport = eurostatCandidate("prc_hicp_midx_hicp_transport", "prc_hicp_midx", "", "M", Map.of());
        SearchCandidate recreation = eurostatCandidate("prc_hicp_midx_hicp_recreation", "prc_hicp_midx", "", "M", Map.of());
        SearchCandidate food = eurostatCandidate("prc_hicp_midx_hicp_food", "prc_hicp_midx", "", "M", Map.of());

        var result = verifier.verifyTopOnly(
                List.of(
                        new SearchResult(allItems, decision(allItems.seriesId()), 0),
                        new SearchResult(transport, decision(transport.seriesId()), 1),
                        new SearchResult(recreation, decision(recreation.seriesId()), 2),
                        new SearchResult(food, decision(food.seriesId()), 3)),
                4,
                List.of("RU"));

        assertThat(result.uniqueRequestCount()).isEqualTo(4);
        List<Object> seriesIds = result.statuses().stream().map(s -> s.get("series_id")).toList();
        assertThat(seriesIds)
                .containsExactlyInAnyOrder(
                        "prc_hicp_midx_hicp_all_items",
                        "prc_hicp_midx_hicp_transport",
                        "prc_hicp_midx_hicp_recreation",
                        "prc_hicp_midx_hicp_food");
        assertThat(seriesIds).doesNotHaveDuplicates();
        org.mockito.Mockito.verify(previewService, org.mockito.Mockito.times(4)).preview(any());
    }

    private static SearchCandidate eurostatCandidate(
            String seriesId, String dataset, String geo, String frequency, Map<String, Object> raw) {
        return new SearchCandidate(
                "eurostat:" + seriesId,
                seriesId,
                "title " + seriesId,
                "",
                "eurostat",
                dataset,
                geo,
                frequency,
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                "",
                1,
                "q",
                List.of(),
                raw);
    }

    private static Map<String, Object> okPreview() {
        return Map.of(
                "preview_state", "ok",
                "query_params", Map.of("geo", "DE"),
                "rows", List.of(Map.of("value", 1)));
    }

    private static SearchResult result(String id) {
        return new SearchResult(candidate(id), decision(id), 0);
    }

    private static SearchCandidate candidate(String id) {
        return new SearchCandidate(
                "fred:" + id,
                id,
                "Title " + id,
                "",
                "fred",
                "",
                "",
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                "",
                1,
                "q",
                List.of(),
                Map.of());
    }

    private static SemanticDecision decision(String id) {
        return new SemanticDecision(id, "keep", 0.9, 0.9, List.of(), List.of(), "ok", "primary");
    }
}
