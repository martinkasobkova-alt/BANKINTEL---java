package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.CatalogSearchAnswerService;
import cz.bankintel.search.v2.coverage.SearchV2CoverageChecker;
import cz.bankintel.search.v2.coverage.SearchV2RetryPlanner;
import cz.bankintel.search.v2.entity.SearchV2ExactEntityScorer;
import cz.bankintel.search.v2.observability.SearchV2TelemetryWriter;
import cz.bankintel.search.v2.observability.SearchV2TraceStore;
import cz.bankintel.search.v2.ontology.SearchV2ConceptOntology;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.normalization.SearchResultCanonicalMetadataService;
import cz.bankintel.search.v2.planner.SearchV2QueryPlanner;
import cz.bankintel.search.v2.reranking.SearchV2BatchReranker;
import cz.bankintel.search.v2.reranking.SearchV2FinalReranker;
import cz.bankintel.search.v2.reranking.SearchV2SemanticValidator;
import cz.bankintel.search.v2.retrieval.SearchV2CandidateMerger;
import cz.bankintel.search.v2.retrieval.SearchV2FtsRetriever;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SemanticDecision;
import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarIndex;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2ServiceRuntimeTest {

    @Test
    void verifiedPreviewReclassifiesSeriesFromLatestObservedPeriod() {
        Map<String, Object> row = new LinkedHashMap<>(Map.of(
                "source", "ecb2",
                "dataset_id", "CBD2",
                "frequency", "A",
                "lifecycle_status", "current",
                "lifecycle_reason", "explicit_dataset_lifecycle",
                "lifecycle_confidence", 0.9));
        Map<String, Object> preview = Map.of(
                "preview_payload", Map.of(
                        "rows", List.of(
                                Map.of("TIME_PERIOD", "2017", "OBS_VALUE", 89.1),
                                Map.of("TIME_PERIOD", "2025", "OBS_VALUE", ""))));

        SearchV2Service.refreshLifecycleFromPreview(row, preview);

        assertThat(row)
                .containsEntry("latest_period", "2017")
                .containsEntry("lifecycle_status", "historical")
                .containsEntry("lifecycle_reason", "latest_period_outside_freshness_window");
    }

    @Test
    void searchResultExposesTheSameStableRankUnderBothApiFieldNames() {
        SearchResult result = new SearchResult(
                candidate("cz", "CZ"),
                new SemanticDecision(
                        "cz", "keep", 0.95, 0.9, List.of("ROA"), List.of(), "semantic match", "primary"),
                3);

        assertThat(result.toMap()).containsEntry("rank", 3).containsEntry("final_rank", 3);
    }

    @Test
    void technicalPreviewGateNeverRestoresARejectedRankedCandidate() {
        SearchCandidate candidate = candidate("cz", "CZ");
        SemanticDecision decision = new SemanticDecision(
                "cz", "keep", 0.95, 0.9, List.of("ROA"), List.of(), "semantic match", "primary");
        List<SearchResult> ranked = List.of(new SearchResult(candidate, decision, 1));

        assertThat(SearchV2Service.selectFinalResults(ranked, List.of(), true, 10)).isEmpty();
        assertThat(SearchV2Service.selectFinalResults(ranked, List.of(), false, 10)).hasSize(1);
    }

    @Test
    void sidecarResponseReportsAdvisoryGeoEvidenceFunnel() {
        System.setProperty("SEARCH_SEMANTIC_RETRIEVAL_ENABLED", "false");
        SearchV2QueryPlanner planner = mock(SearchV2QueryPlanner.class);
        SearchV2FtsRetriever retriever = mock(SearchV2FtsRetriever.class);
        SearchV2CandidateMerger merger = mock(SearchV2CandidateMerger.class);
        SearchV2BatchReranker batchReranker = mock(SearchV2BatchReranker.class);
        SearchV2RetryPlanner retryPlanner = mock(SearchV2RetryPlanner.class);
        SearchV2PreviewVerifier previewVerifier = mock(SearchV2PreviewVerifier.class);
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        SearchCatalogSidecarIndex sidecarIndex = mock(SearchCatalogSidecarIndex.class);

        SearchQueryPlan plan = new SearchQueryPlan(
                "ROA bank Cesko",
                "cs",
                "find_series",
                List.of("ROA bank"),
                List.of(),
                List.of("CZ"),
                List.of("data360"),
                List.of(),
                List.of(),
                null,
                List.of("ROA bank Cesko"),
                List.of("ROA bank"),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "local_fallback",
                null);
        SearchCandidate austria = candidate("at", "AT");
        SearchCandidate czechia = candidate("cz", "CZ");
        SearchV2FtsRetriever.RetrievalResult retrieval = new SearchV2FtsRetriever.RetrievalResult(
                List.of(austria, czechia),
                List.of(austria, czechia),
                List.of("ROA bank"),
                List.of(Map.of("source", "ecb2", "query", "ROA bank", "count", 2, "ok", true, "index_mode", "sidecar")),
                7,
                "sidecar");

        when(indexStore.catalogVersion()).thenReturn("test-catalog");
        when(sidecarIndex.configuredMode(anyMap())).thenReturn("sidecar");
        when(planner.plan(anyMap())).thenReturn(plan);
        when(retriever.retrieve(eq(plan), anyList(), anyList(), anyLong(), eq("sidecar"))).thenReturn(retrieval);
        when(batchReranker.rerank(eq(plan), anyList(), eq(true))).thenAnswer(invocation -> {
            List<SearchCandidate> candidates = invocation.getArgument(1);
            return new SearchV2SemanticValidator.ValidationResult(
                    candidates.stream()
                            .map(candidate -> new SemanticDecision(
                                    candidate.seriesId(),
                                    "CZ".equals(candidate.geo()) ? "keep" : "drop",
                                    "CZ".equals(candidate.geo()) ? 0.95 : 0.1,
                                    0.9,
                                    List.of("ROA"),
                                    "CZ".equals(candidate.geo()) ? List.of() : List.of("wrong_geography"),
                                    "CZ".equals(candidate.geo()) ? "metadata match" : "LLM rejected geo mismatch evidence",
                                    "CZ".equals(candidate.geo()) ? "primary" : "reject"))
                            .toList(),
                    "validated",
                    "test-reranker",
                    1,
                    0,
                    List.of(),
                    List.of());
        });

        SearchV2Service service = new SearchV2Service(
                planner,
                retriever,
                merger,
                batchReranker,
                finalReranker(),
                new SearchV2CoverageChecker(),
                retryPlanner,
                previewVerifier,
                new SearchV2TraceStore(),
                new SearchV2CacheService(),
                indexStore,
                sidecarIndex,
                new SearchV2ExactEntityScorer(),
                mock(SearchV2ConceptRegistry.class),
                answerService(),
                mock(SearchV2ConceptOntology.class),
                new SearchV2TelemetryWriter(new ObjectMapper()),
                mock(cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry.class),
                canonicalMetadataService(),
                mock(cz.bankintel.service.research.WebResearchService.class));

        Map<String, Object> response = service.search(Map.of(
                "query", "ROA bank Cesko",
                "use_ai", true,
                "use_ai_reranker", true,
                "sources", List.of("ecb2"),
                "eval_mode", "metadata_only",
                "no_cache", true,
                "debug", true));
        verify(retriever).retrieve(
                eq(plan), eq(List.of("ecb2")), eq(List.of("ecb2")), anyLong(), eq("sidecar"));

        assertThat(response.get("search_engine")).isEqualTo("v2");
        assertThat(response.get("catalog_index_mode")).isEqualTo("sidecar");
        assertThat(response.get("semantic_retrieval_enabled")).isEqualTo(false);
        assertThat(response.get("fallback_to_legacy")).isEqualTo(false);
        List<?> results = (List<?>) response.get("results");
        assertThat(results).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstResult = (Map<String, Object>) results.getFirst();
        assertThat(firstResult).containsEntry("geo", "CZ");
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) response.get("candidate_counts");
        assertThat(counts).containsEntry("retrieved_raw", 2);
        assertThat(counts).containsEntry("deduplicated_unique", 2);
        assertThat(counts).containsEntry("after_hard_constraints", 2);
        assertThat(counts).containsEntry("after_source_balancing", 2);
        assertThat(counts).containsEntry("after_candidate_limit", 2);
        assertThat(counts).containsEntry("sent_to_deterministic_reranker", 0);
        assertThat(counts).containsEntry("sent_to_llm_reranker", 2);
        assertThat(counts).containsEntry("sent_to_preview", 0);
        assertThat(counts).containsEntry("preview_success", 0);
        assertThat(counts).containsEntry("preview_failed", 0);
        assertThat(counts).containsEntry("final_results", 1);
        assertSequentialFunnelDoesNotIncrease(counts);
    }

    @Test
    void retryAdoptionReportsEffectiveRerankerPool() {
        System.setProperty("SEARCH_SEMANTIC_RETRIEVAL_ENABLED", "false");
        SearchV2QueryPlanner planner = mock(SearchV2QueryPlanner.class);
        SearchV2FtsRetriever retriever = mock(SearchV2FtsRetriever.class);
        SearchV2CandidateMerger merger = mock(SearchV2CandidateMerger.class);
        SearchV2BatchReranker batchReranker = mock(SearchV2BatchReranker.class);
        SearchV2RetryPlanner retryPlanner = mock(SearchV2RetryPlanner.class);
        SearchV2PreviewVerifier previewVerifier = mock(SearchV2PreviewVerifier.class);
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        SearchCatalogSidecarIndex sidecarIndex = mock(SearchCatalogSidecarIndex.class);

        SearchQueryPlan plan = new SearchQueryPlan(
                "ROA bank Polsko",
                "cs",
                "find_series",
                List.of("ROA bank"),
                List.of(),
                List.of("PL"),
                List.of("ecb2"),
                List.of(),
                List.of(),
                null,
                List.of("ROA bank Polsko"),
                List.of("ROA bank"),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "local_fallback",
                null);
        SearchCandidate poland = candidate("pl", "PL");
        SearchV2FtsRetriever.RetrievalResult emptyRetrieval = new SearchV2FtsRetriever.RetrievalResult(
                List.of(),
                List.of(),
                List.of("ROA bank"),
                List.of(Map.of("source", "ecb2", "query", "ROA bank", "count", 0, "ok", true, "index_mode", "sidecar")),
                3,
                "sidecar");
        SearchV2FtsRetriever.RetrievalResult retryRetrieval = new SearchV2FtsRetriever.RetrievalResult(
                List.of(poland),
                List.of(poland),
                List.of("return on assets Poland"),
                List.of(Map.of("source", "ecb2", "query", "return on assets Poland", "count", 1, "ok", true, "index_mode", "sidecar")),
                4,
                "sidecar");

        when(indexStore.catalogVersion()).thenReturn("test-catalog");
        when(sidecarIndex.configuredMode(anyMap())).thenReturn("sidecar");
        when(planner.plan(anyMap())).thenReturn(plan);
        when(retriever.retrieve(eq(plan), anyList(), anyList(), anyLong(), eq("sidecar"))).thenReturn(emptyRetrieval);
        when(retryPlanner.retryTerms(eq(plan), any())).thenReturn(List.of("return on assets Poland"));
        when(retriever.retrieveQueries(anyList(), anyList(), anyLong(), eq("sidecar"))).thenReturn(retryRetrieval);
        when(merger.merge(anyList(), eq(240))).thenReturn(List.of(poland));
        when(batchReranker.rerank(eq(plan), anyList(), eq(false))).thenAnswer(invocation -> {
            List<SearchCandidate> candidates = invocation.getArgument(1);
            return new SearchV2SemanticValidator.ValidationResult(
                    candidates.stream()
                            .map(c -> new SemanticDecision(c.seriesId(), "keep", 0.95, 0.9, List.of("ROA"), List.of(), "metadata match", "primary"))
                            .toList(),
                    "disabled",
                    null,
                    1,
                    0,
                    List.of(),
                    List.of());
        });

        SearchV2Service service = new SearchV2Service(
                planner,
                retriever,
                merger,
                batchReranker,
                finalReranker(),
                new SearchV2CoverageChecker(),
                retryPlanner,
                previewVerifier,
                new SearchV2TraceStore(),
                new SearchV2CacheService(),
                indexStore,
                sidecarIndex,
                new SearchV2ExactEntityScorer(),
                mock(SearchV2ConceptRegistry.class),
                answerService(),
                mock(SearchV2ConceptOntology.class),
                new SearchV2TelemetryWriter(new ObjectMapper()),
                mock(cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry.class),
                canonicalMetadataService(),
                mock(cz.bankintel.service.research.WebResearchService.class));

        Map<String, Object> response = service.search(Map.of(
                "query", "ROA bank Polsko",
                "use_ai", false,
                "use_ai_reranker", false,
                "eval_mode", "metadata_only",
                "no_cache", true,
                "debug", true));

        assertThat(response.get("status")).isEqualTo("ok");
        assertThat(response.get("retry_adopted")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) response.get("candidate_counts");
        assertThat(counts).containsEntry("after_hard_constraints", 1);
        assertThat(counts).containsEntry("after_source_balancing", 1);
        assertThat(counts).containsEntry("after_candidate_limit", 1);
        assertThat(counts).containsEntry("sent_to_deterministic_reranker", 1);
        assertThat(counts).containsEntry("final_results", 1);
        assertSequentialFunnelDoesNotIncrease(counts);
    }

    @Test
    void geoEvidenceReachesSemanticDecisionWithoutPreLlmCandidateRemoval() {
        System.setProperty("SEARCH_SEMANTIC_RETRIEVAL_ENABLED", "false");
        SearchV2QueryPlanner planner = mock(SearchV2QueryPlanner.class);
        SearchV2FtsRetriever retriever = mock(SearchV2FtsRetriever.class);
        SearchV2CandidateMerger merger = mock(SearchV2CandidateMerger.class);
        SearchV2BatchReranker batchReranker = mock(SearchV2BatchReranker.class);
        SearchV2RetryPlanner retryPlanner = mock(SearchV2RetryPlanner.class);
        SearchV2PreviewVerifier previewVerifier = mock(SearchV2PreviewVerifier.class);
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        SearchCatalogSidecarIndex sidecarIndex = mock(SearchCatalogSidecarIndex.class);

        SearchQueryPlan plan = new SearchQueryPlan(
                "ceny nemovitosti Slovensko",
                "cs",
                "find_series",
                List.of("house price index"),
                List.of(),
                List.of("SK"),
                List.of("eurostat"),
                List.of(),
                List.of(),
                null,
                List.of("house price index"),
                List.of("house price index"),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "local_fallback",
                null);
        SearchCandidate slovakia = candidateWithSource("sk-fixed", "SK", "eurostat", "prc_hpi_a");
        SearchCandidate selectable = candidateWithSource("hpi-generic", "", "eurostat", "prc_hpi_q");
        SearchCandidate data360Selectable = candidateWithSource("fsi-net-income", "", "data360", "IMF_FSI");
        SearchCandidate localBlankGeo = candidateWithSource("arad-local-blank", "", "arad", "1022");
        SearchCandidate czechia = candidateWithSource("cz-fixed", "CZ", "eurostat", "prc_hpi_a");
        SearchV2FtsRetriever.RetrievalResult retrieval = new SearchV2FtsRetriever.RetrievalResult(
                List.of(slovakia, selectable, data360Selectable, localBlankGeo, czechia),
                List.of(slovakia, selectable, data360Selectable, localBlankGeo, czechia),
                List.of("house price index"),
                List.of(Map.of("source", "eurostat", "query", "house price index", "count", 3, "ok", true, "index_mode", "sidecar")),
                6,
                "sidecar");

        when(indexStore.catalogVersion()).thenReturn("test-catalog");
        when(sidecarIndex.configuredMode(anyMap())).thenReturn("sidecar");
        when(planner.plan(anyMap())).thenReturn(plan);
        when(retriever.retrieve(eq(plan), anyList(), anyList(), anyLong(), eq("sidecar"))).thenReturn(retrieval);
        when(batchReranker.rerank(eq(plan), anyList(), eq(false))).thenAnswer(invocation -> {
            List<SearchCandidate> candidates = invocation.getArgument(1);
            return new SearchV2SemanticValidator.ValidationResult(
                    candidates.stream()
                            .map(candidate -> {
                                boolean compatible = List.of("sk-fixed", "hpi-generic", "fsi-net-income")
                                        .contains(candidate.seriesId());
                                return new SemanticDecision(
                                        candidate.seriesId(),
                                        compatible ? "keep" : "drop",
                                        compatible ? 0.9 : 0.1,
                                        0.9,
                                        List.of("house_price_index"),
                                        compatible ? List.of() : List.of("wrong_geography"),
                                        compatible ? "metadata match" : "LLM rejected deterministic geo mismatch evidence",
                                        compatible ? "primary" : "reject");
                            })
                            .toList(),
                    "disabled",
                    null,
                    1,
                    0,
                    List.of(),
                    List.of());
        });

        SearchV2Service service = new SearchV2Service(
                planner,
                retriever,
                merger,
                batchReranker,
                finalReranker(),
                new SearchV2CoverageChecker(),
                retryPlanner,
                previewVerifier,
                new SearchV2TraceStore(),
                new SearchV2CacheService(),
                indexStore,
                sidecarIndex,
                new SearchV2ExactEntityScorer(),
                mock(SearchV2ConceptRegistry.class),
                answerService(),
                mock(SearchV2ConceptOntology.class),
                new SearchV2TelemetryWriter(new ObjectMapper()),
                mock(cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry.class),
                canonicalMetadataService(),
                mock(cz.bankintel.service.research.WebResearchService.class));

        Map<String, Object> response = service.search(Map.of(
                "query", "ceny nemovitosti Slovensko",
                "use_ai", false,
                "use_ai_reranker", false,
                "eval_mode", "metadata_only",
                "no_cache", true,
                "debug", true));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        assertThat(results).extracting(row -> row.get("series_id")).contains("sk-fixed", "hpi-generic", "fsi-net-income");
        assertThat(results).extracting(row -> row.get("series_id")).doesNotContain("cz-fixed", "arad-local-blank");
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) response.get("candidate_counts");
        assertThat(counts).containsEntry("after_hard_constraints", 5);
        assertThat(counts).containsEntry("sent_to_deterministic_reranker", 5);
        assertSequentialFunnelDoesNotIncrease(counts);
    }

    @Test
    void livePreviewVerificationIsProjectedToResultRows() {
        System.setProperty("SEARCH_SEMANTIC_RETRIEVAL_ENABLED", "false");
        SearchV2QueryPlanner planner = mock(SearchV2QueryPlanner.class);
        SearchV2FtsRetriever retriever = mock(SearchV2FtsRetriever.class);
        SearchV2CandidateMerger merger = mock(SearchV2CandidateMerger.class);
        SearchV2BatchReranker batchReranker = mock(SearchV2BatchReranker.class);
        SearchV2RetryPlanner retryPlanner = mock(SearchV2RetryPlanner.class);
        SearchV2PreviewVerifier previewVerifier = mock(SearchV2PreviewVerifier.class);
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        SearchCatalogSidecarIndex sidecarIndex = mock(SearchCatalogSidecarIndex.class);

        SearchQueryPlan plan = new SearchQueryPlan(
                "urokova mira nemecko",
                "cs",
                "find_series",
                List.of("interest rate"),
                List.of("interest_rate"),
                List.of("DE"),
                List.of("ecb2"),
                List.of("interest_rate"),
                List.of(),
                null,
                List.of("interest rate Germany"),
                List.of("interest rate"),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "local_fallback",
                null);
        SearchCandidate germanyRate = candidateWithSource("de-rate", "DE", "ecb2", "MIR");
        SearchV2FtsRetriever.RetrievalResult retrieval = new SearchV2FtsRetriever.RetrievalResult(
                List.of(germanyRate),
                List.of(germanyRate),
                List.of("interest rate"),
                List.of(Map.of("source", "ecb2", "query", "interest rate", "count", 1, "ok", true, "index_mode", "sidecar")),
                3,
                "sidecar");
        SemanticDecision decision = new SemanticDecision(
                germanyRate.seriesId(),
                "keep",
                0.95,
                0.92,
                List.of("interest_rate", "Germany"),
                List.of(),
                "preview-backed interest-rate match",
                "primary");

        when(indexStore.catalogVersion()).thenReturn("test-catalog");
        when(sidecarIndex.configuredMode(anyMap())).thenReturn("sidecar");
        when(planner.plan(anyMap())).thenReturn(plan);
        when(retriever.retrieve(eq(plan), anyList(), anyList(), anyLong(), eq("sidecar"))).thenReturn(retrieval);
        when(merger.merge(eq(List.of(germanyRate)), eq(240))).thenReturn(List.of(germanyRate));
        when(batchReranker.rerank(eq(plan), anyList(), eq(false))).thenReturn(
                new SearchV2SemanticValidator.ValidationResult(
                        List.of(decision),
                        "disabled",
                        null,
                        1,
                        0,
                        List.of(),
                        List.of()));
        when(previewVerifier.verifyTopOnly(anyList(), anyInt(), anyList())).thenReturn(
                new SearchV2PreviewVerifier.VerificationResult(
                        List.of(new SearchResult(germanyRate, decision, 0)),
                        List.of(Map.of(
                                "source", "ecb2",
                                "series_id", "de-rate",
                                "ok", true,
                                "rows", 12,
                                "query_params", Map.of("country", "DE", "indicator", "MIR"),
                                "preview_request_payload", Map.of(
                                        "source_type", "ecb",
                                        "set_id", "de-rate",
                                        "country", "DE",
                                        "query_params", Map.of("country", "DE", "indicator", "MIR")),
                                "preview_payload", Map.of(
                                        "preview_state", "ok",
                                        "query_params", Map.of("country", "DE", "indicator", "MIR"),
                                        "rows", List.of(Map.of("value", 1), Map.of("value", 2))),
                                "preview_state", "")),
                        5,
                        1,
                        1));

        SearchV2Service service = new SearchV2Service(
                planner,
                retriever,
                merger,
                batchReranker,
                finalReranker(),
                new SearchV2CoverageChecker(),
                retryPlanner,
                previewVerifier,
                new SearchV2TraceStore(),
                new SearchV2CacheService(),
                indexStore,
                sidecarIndex,
                new SearchV2ExactEntityScorer(),
                mock(SearchV2ConceptRegistry.class),
                answerService(),
                mock(SearchV2ConceptOntology.class),
                new SearchV2TelemetryWriter(new ObjectMapper()),
                mock(cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry.class),
                canonicalMetadataService(),
                mock(cz.bankintel.service.research.WebResearchService.class));

        Map<String, Object> response = service.search(Map.of(
                "query", "urokova mira nemecko",
                "use_ai", false,
                "use_ai_reranker", false,
                "no_cache", true,
                "debug", true));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.getFirst())
                .containsEntry("status", "verified")
                .containsEntry("preview_status", "verified")
                .containsEntry("preview_available", true)
                .containsEntry("preview_row_count", 12);
        assertThat(results.getFirst()).containsKeys("query_params", "preview_payload", "preview_request_payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> queryParams = (Map<String, Object>) results.getFirst().get("query_params");
        assertThat(queryParams).containsEntry("country", "DE").containsEntry("indicator", "MIR");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> verified = (List<Map<String, Object>>) response.get("verified");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> possible = (List<Map<String, Object>>) response.get("possible");
        assertThat(verified).hasSize(1);
        assertThat(possible).isEmpty();
    }

    @Test
    void timedOutPreviewCandidateIsClassifiedExplicitlyNotConflatedWithNonexistence() {
        System.setProperty("SEARCH_SEMANTIC_RETRIEVAL_ENABLED", "false");
        SearchV2QueryPlanner planner = mock(SearchV2QueryPlanner.class);
        SearchV2FtsRetriever retriever = mock(SearchV2FtsRetriever.class);
        SearchV2CandidateMerger merger = mock(SearchV2CandidateMerger.class);
        SearchV2BatchReranker batchReranker = mock(SearchV2BatchReranker.class);
        SearchV2RetryPlanner retryPlanner = mock(SearchV2RetryPlanner.class);
        SearchV2PreviewVerifier previewVerifier = mock(SearchV2PreviewVerifier.class);
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        SearchCatalogSidecarIndex sidecarIndex = mock(SearchCatalogSidecarIndex.class);

        SearchQueryPlan plan = new SearchQueryPlan(
                "urokova mira nemecko", "cs", "find_series", List.of("interest rate"), List.of("interest_rate"),
                List.of("DE"), List.of("ecb2"), List.of("interest_rate"), List.of(), null,
                List.of("interest rate Germany"), List.of("interest rate"), List.of(), List.of(), List.of(),
                List.of("primary"), new SearchQueryPlan.Clarification(false, null, null), "local_fallback", null);
        SearchCandidate germanyRate = candidateWithSource("de-rate-to", "DE", "ecb2", "MIR");
        SearchV2FtsRetriever.RetrievalResult retrieval = new SearchV2FtsRetriever.RetrievalResult(
                List.of(germanyRate), List.of(germanyRate), List.of("interest rate"),
                List.of(Map.of("source", "ecb2", "query", "interest rate", "count", 1, "ok", true, "index_mode", "sidecar")),
                3, "sidecar");
        SemanticDecision decision = new SemanticDecision(
                germanyRate.seriesId(), "keep", 0.95, 0.92, List.of("interest_rate"), List.of(), "match", "primary");

        when(indexStore.catalogVersion()).thenReturn("test-catalog");
        when(sidecarIndex.configuredMode(anyMap())).thenReturn("sidecar");
        when(planner.plan(anyMap())).thenReturn(plan);
        when(retriever.retrieve(eq(plan), anyList(), anyList(), anyLong(), eq("sidecar"))).thenReturn(retrieval);
        when(merger.merge(eq(List.of(germanyRate)), eq(240))).thenReturn(List.of(germanyRate));
        when(batchReranker.rerank(eq(plan), anyList(), eq(false))).thenReturn(
                new SearchV2SemanticValidator.ValidationResult(List.of(decision), "disabled", null, 1, 0, List.of(), List.of()));
        // Not accepted (ok=false), classic timeout status shape from SearchV2PreviewVerifier.timeoutStatus(...).
        when(previewVerifier.verifyTopOnly(anyList(), anyInt(), anyList())).thenReturn(
                new SearchV2PreviewVerifier.VerificationResult(
                        List.of(),
                        List.of(Map.of(
                                "source", "ecb2",
                                "series_id", "de-rate-to",
                                "ok", false,
                                "preview_state", "timeout",
                                "reason", "preview_timeout_ms=8000")),
                        8000, 1, 1));

        SearchV2Service service = new SearchV2Service(
                planner, retriever, merger, batchReranker, finalReranker(), new SearchV2CoverageChecker(),
                retryPlanner, previewVerifier, new SearchV2TraceStore(), new SearchV2CacheService(), indexStore,
                sidecarIndex, new SearchV2ExactEntityScorer(), mock(SearchV2ConceptRegistry.class), answerService(),
                mock(SearchV2ConceptOntology.class), new SearchV2TelemetryWriter(new ObjectMapper()),
                mock(cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry.class),
                canonicalMetadataService(),
                mock(cz.bankintel.service.research.WebResearchService.class));

        Map<String, Object> response = service.search(Map.of(
                "query", "urokova mira nemecko", "use_ai", false, "use_ai_reranker", false, "no_cache", true, "debug", true));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> previewVerification = (List<Map<String, Object>>) response.get("preview_verification");
        assertThat(previewVerification).hasSize(1);
        Map<String, Object> entry = previewVerification.get(0);
        assertThat(entry.get("preview_outcome"))
                .as("a timeout must be its own explicit outcome - never silently equivalent to "
                        + "\"this series does not exist\" (TRANSPORT_FAILURE or POSSIBLE)")
                .isEqualTo(cz.bankintel.search.v2.schema.SearchV2PreviewOutcome.TIMEOUT)
                .isNotEqualTo(cz.bankintel.search.v2.schema.SearchV2PreviewOutcome.TRANSPORT_FAILURE)
                .isNotEqualTo(cz.bankintel.search.v2.schema.SearchV2PreviewOutcome.POSSIBLE);
    }

    // ---- PR-7b: unverified results preservation ------------------------------------------------

    @org.junit.jupiter.api.AfterEach
    void clearUnverifiedResultsFlag() {
        System.clearProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED");
    }

    // ---- Validation phase (Fáze 2): all five new flags explicitly false simultaneously -----------

    @Test
    void allFiveNewFlagsExplicitlyFalseProducesExactlyTheBaselineResponseContract() {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "false");
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "false");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED", "false");
        System.setProperty("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED", "false");
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "false");
        try {
            SearchCandidate timedOut = candidateWithSource("baseline-check", "DE", "ecb2", "MIR");
            Map<String, Object> response = runPreviewScenario(
                    List.of(timedOut),
                    List.of(Map.of(
                            "source", "ecb2", "series_id", "baseline-check", "ok", false,
                            "preview_state", "timeout", "reason", "preview_timeout_ms=8000")),
                    List.of());

            assertThat(response).doesNotContainKeys("unverified", "story_mode");
            assertThat(response.get("status")).isEqualTo("no_valid_result");
            assertThat(response.get("ok")).isEqualTo(true);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> possible = (List<Map<String, Object>>) response.get("possible");
            assertThat(results).isEmpty();
            assertThat(possible).isEmpty();
        } finally {
            System.clearProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED");
            System.clearProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED");
            System.clearProperty("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED");
            System.clearProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED");
        }
    }

    @Test
    void oneVerifiedCandidateIsInResultsAndUnverifiedIsEmpty() {
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchCandidate verifiedCandidate = candidateWithSource("de-rate-verified", "DE", "ecb2", "MIR");
        Map<String, Object> response = runPreviewScenario(
                List.of(verifiedCandidate),
                List.of(Map.of(
                        "source", "ecb2", "series_id", "de-rate-verified", "ok", true, "preview_state", "ok", "rows", 5)),
                List.of(new SearchResult(verifiedCandidate, decisionFor(verifiedCandidate, 0.95), 1)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        assertThat(results).hasSize(1);
        assertThat((List<?>) response.get("unverified")).isEmpty();
        assertThat(response.get("status")).isEqualTo("ok");
    }

    // ---- PR-7c: POSSIBLE preservation -----------------------------------------------------------

    @Test
    void onePossibleCandidateNoVerifiedIsUnverifiedWithUnverifiedOnlyStatusNeverNoValidResult() {
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchCandidate inconclusive = candidateWithSource("de-rate-possible", "DE", "ecb2", "MIR");
        Map<String, Object> response = runPreviewScenario(
                List.of(inconclusive),
                List.of(Map.of(
                        "source", "ecb2", "series_id", "de-rate-possible", "ok", false,
                        "preview_state", "something_unrecognized")),
                List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        assertThat(results).isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unverified = (List<Map<String, Object>>) response.get("unverified");
        assertThat(unverified).hasSize(1);
        assertThat(unverified.get(0).get("preview_outcome")).isEqualTo(cz.bankintel.search.v2.schema.SearchV2PreviewOutcome.POSSIBLE);
        assertThat(unverified.get(0).get("verified")).isEqualTo(false);
        assertThat(response.get("status"))
                .as("a POSSIBLE candidate is relevant-but-unconfirmed, exactly like a timeout - must not claim no_valid_result")
                .isEqualTo("unverified_only");
    }

    @Test
    void verifiedAndPossibleTogetherKeepVerifiedInResultsAndPossibleInUnverifiedInOriginalOrder() {
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchCandidate verifiedCandidate = candidateWithSource("mix-verified", "DE", "ecb2", "MIR");
        SearchCandidate possibleCandidate = candidateWithSource("mix-possible", "DE", "ecb2", "MIR");

        Map<String, Object> response = runPreviewScenario(
                List.of(verifiedCandidate, possibleCandidate),
                List.of(
                        Map.of("source", "ecb2", "series_id", "mix-verified", "ok", true, "preview_state", "ok", "rows", 5),
                        Map.of("source", "ecb2", "series_id", "mix-possible", "ok", false, "preview_state", "")),
                List.of(new SearchResult(verifiedCandidate, decisionFor(verifiedCandidate, 0.95), 1)),
                List.of(0.95, 0.80));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("series_id")).isEqualTo("mix-verified");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unverified = (List<Map<String, Object>>) response.get("unverified");
        assertThat(unverified).hasSize(1);
        assertThat(unverified.get(0).get("series_id")).isEqualTo("mix-possible");
        assertThat(unverified.get(0).get("preview_outcome")).isEqualTo(cz.bankintel.search.v2.schema.SearchV2PreviewOutcome.POSSIBLE);
    }

    @Test
    void possibleCandidateFlagOffProducesExactlyTheOriginalResponseShape() {
        // SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED intentionally left unset.
        SearchCandidate inconclusive = candidateWithSource("de-rate-possible-off", "DE", "ecb2", "MIR");
        Map<String, Object> response = runPreviewScenario(
                List.of(inconclusive),
                List.of(Map.of(
                        "source", "ecb2", "series_id", "de-rate-possible-off", "ok", false,
                        "preview_state", "something_unrecognized")),
                List.of());

        assertThat(response).doesNotContainKey("unverified");
        assertThat(response.get("status")).isEqualTo("no_valid_result");
    }

    @Test
    void oneTimeoutCandidateIsUnverifiedNotSilentlyLostAndStatusIsNotNoValidResult() {
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchCandidate timedOut = candidateWithSource("de-rate-timeout", "DE", "ecb2", "MIR");
        Map<String, Object> response = runPreviewScenario(
                List.of(timedOut),
                List.of(Map.of(
                        "source", "ecb2", "series_id", "de-rate-timeout", "ok", false,
                        "preview_state", "timeout", "reason", "preview_timeout_ms=8000")),
                List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        assertThat(results).isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unverified = (List<Map<String, Object>>) response.get("unverified");
        assertThat(unverified).hasSize(1);
        Map<String, Object> row = unverified.get(0);
        assertThat(row.get("source")).isEqualTo("ecb2");
        assertThat(row.get("series_id")).isEqualTo("de-rate-timeout");
        assertThat(row.get("preview_outcome")).isEqualTo(cz.bankintel.search.v2.schema.SearchV2PreviewOutcome.TIMEOUT);
        assertThat(row.get("preview_reason")).isEqualTo("preview_timeout_ms=8000");
        assertThat(row.get("verified")).isEqualTo(false);
        assertThat(row).containsKey("title").containsKey("final_rank");
        assertThat(response.get("status"))
                .as("a relevant-but-unconfirmed candidate exists - must never claim no_valid_result")
                .isEqualTo("unverified_only");
    }

    @Test
    void transportFailureCandidateIsUnverifiedNotRejected() {
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchCandidate failed = candidateWithSource("de-rate-error", "DE", "ecb2", "MIR");
        Map<String, Object> response = runPreviewScenario(
                List.of(failed),
                List.of(Map.of(
                        "source", "ecb2", "series_id", "de-rate-error", "ok", false,
                        "preview_state", "error", "reason", "connection refused", "transport_type", "async_http")),
                List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unverified = (List<Map<String, Object>>) response.get("unverified");
        assertThat(unverified).hasSize(1);
        assertThat(unverified.get(0).get("preview_outcome"))
                .isEqualTo(cz.bankintel.search.v2.schema.SearchV2PreviewOutcome.TRANSPORT_FAILURE);
        assertThat(unverified.get(0).get("transport_type")).isEqualTo("async_http");
    }

    // ---- sync_failed fix: PreviewResponseBuilder.buildError's real production shape for every ------
    // ---- HTTP-level connector failure (429/5xx/connection) must land in `unverified`, never silently
    // ---- vanish into a false "no_valid_result" -------------------------------------------------------

    @Test
    void onlyASyncFailedCandidateIsUnverifiedNotRejectedAndStatusIsUnverifiedOnly() {
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchCandidate failed = candidateWithSource("de-rate-sync-failed", "DE", "ecb2", "MIR");
        Map<String, Object> response = runPreviewScenario(
                List.of(failed),
                List.of(Map.of(
                        "source", "ecb2", "series_id", "de-rate-sync-failed", "ok", false,
                        "preview_state", "sync_failed", "rows", 0, "http_status", 503)),
                List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        assertThat(results).isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unverified = (List<Map<String, Object>>) response.get("unverified");
        assertThat(unverified).hasSize(1);
        assertThat(unverified.get(0).get("preview_outcome"))
                .as("a real connector failure must be TRANSPORT_FAILURE (unverified), never EMPTY (rejected)")
                .isEqualTo(cz.bankintel.search.v2.schema.SearchV2PreviewOutcome.TRANSPORT_FAILURE);
        assertThat(unverified.get(0).get("verified")).isEqualTo(false);
        assertThat(response.get("status"))
                .as("a relevant-but-unconfirmed candidate exists - must never falsely claim no_valid_result")
                .isEqualTo("unverified_only");
    }

    @Test
    void verifiedAndSyncFailedTogetherKeepVerifiedInResultsAndSyncFailedInUnverifiedInOriginalOrder() {
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchCandidate verifiedCandidate = candidateWithSource("mix-verified-sf", "DE", "ecb2", "MIR");
        SearchCandidate syncFailedCandidate = candidateWithSource("mix-sync-failed", "DE", "ecb2", "MIR");

        Map<String, Object> response = runPreviewScenario(
                List.of(verifiedCandidate, syncFailedCandidate),
                List.of(
                        Map.of("source", "ecb2", "series_id", "mix-verified-sf", "ok", true, "preview_state", "ok", "rows", 5),
                        Map.of("source", "ecb2", "series_id", "mix-sync-failed", "ok", false,
                                "preview_state", "sync_failed", "rows", 0, "http_status", 429)),
                List.of(new SearchResult(verifiedCandidate, decisionFor(verifiedCandidate, 0.95), 1)),
                List.of(0.95, 0.80));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("series_id")).isEqualTo("mix-verified-sf");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unverified = (List<Map<String, Object>>) response.get("unverified");
        assertThat(unverified).hasSize(1);
        assertThat(unverified.get(0).get("series_id")).isEqualTo("mix-sync-failed");
        assertThat(unverified.get(0).get("preview_outcome"))
                .isEqualTo(cz.bankintel.search.v2.schema.SearchV2PreviewOutcome.TRANSPORT_FAILURE);
        assertThat(response.get("status")).isEqualTo("ok");
    }

    @Test
    void syncFailedCandidateFlagOffProducesExactlyTheOriginalResponseShape() {
        // SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED intentionally left unset - the public response
        // contract for a disabled flag must not change as a side effect of fixing the classification.
        SearchCandidate failed = candidateWithSource("de-rate-sync-failed-off", "DE", "ecb2", "MIR");
        Map<String, Object> response = runPreviewScenario(
                List.of(failed),
                List.of(Map.of(
                        "source", "ecb2", "series_id", "de-rate-sync-failed-off", "ok", false,
                        "preview_state", "sync_failed", "rows", 0, "http_status", 503)),
                List.of());

        assertThat(response).doesNotContainKey("unverified");
        assertThat(response.get("status")).isEqualTo("no_valid_result");
    }

    // ---- Validation phase (Fáze 4): remaining outcome types not yet exercised at service level -----

    @Test
    void cancelledUnsupportedInternalFailureAndStructurallyInvalidClassifyCorrectlyAtServiceLevel() {
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchCandidate cancelled = candidateWithSource("gap-cancelled", "DE", "ecb2", "MIR");
        SearchCandidate unsupported = candidateWithSource("gap-unsupported", "DE", "ecb2", "MIR");
        SearchCandidate internalFailure = candidateWithSource("gap-internal", "DE", "ecb2", "MIR");
        SearchCandidate structurallyInvalid = candidateWithSource("gap-invalid", "DE", "ecb2", "MIR");
        List<SearchCandidate> candidates = List.of(cancelled, unsupported, internalFailure, structurallyInvalid);

        Map<String, Object> response = runPreviewScenario(
                candidates,
                List.of(
                        Map.of("source", "ecb2", "series_id", "gap-cancelled", "ok", false, "preview_state", "cancelled"),
                        Map.of("source", "ecb2", "series_id", "gap-unsupported", "ok", false, "preview_state", "unsupported"),
                        Map.of("source", "ecb2", "series_id", "gap-internal", "ok", false, "preview_state", "internal_error"),
                        Map.of("source", "ecb2", "series_id", "gap-invalid", "ok", false, "preview_state", "structurally_invalid")),
                List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unverifiedGap = (List<Map<String, Object>>) response.get("unverified");
        Map<String, String> outcomeBySeriesId = unverifiedGap.stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> String.valueOf(row.get("series_id")), row -> String.valueOf(row.get("preview_outcome"))));

        assertThat(outcomeBySeriesId)
                .containsEntry("gap-cancelled", cz.bankintel.search.v2.schema.SearchV2PreviewOutcome.CANCELLED)
                .containsEntry("gap-unsupported", cz.bankintel.search.v2.schema.SearchV2PreviewOutcome.UNSUPPORTED)
                .containsEntry("gap-internal", cz.bankintel.search.v2.schema.SearchV2PreviewOutcome.INTERNAL_FAILURE);
        assertThat(outcomeBySeriesId)
                .as("STRUCTURALLY_INVALID is rejected, not unverified - must not appear here at all")
                .doesNotContainKey("gap-invalid");
        assertThat(unverifiedGap).hasSize(3);
    }

    @Test
    void metadataOnlyModeClassifiesEveryCandidateAsNotCheckedWithoutAnyInvariantWarning() {
        System.setProperty("SEARCH_SEMANTIC_RETRIEVAL_ENABLED", "false");
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchV2QueryPlanner planner = mock(SearchV2QueryPlanner.class);
        SearchV2FtsRetriever retriever = mock(SearchV2FtsRetriever.class);
        SearchV2CandidateMerger merger = mock(SearchV2CandidateMerger.class);
        SearchV2BatchReranker batchReranker = mock(SearchV2BatchReranker.class);
        SearchV2RetryPlanner retryPlanner = mock(SearchV2RetryPlanner.class);
        SearchV2PreviewVerifier previewVerifier = mock(SearchV2PreviewVerifier.class);
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        SearchCatalogSidecarIndex sidecarIndex = mock(SearchCatalogSidecarIndex.class);
        SearchV2TelemetryWriter telemetryWriter = mock(SearchV2TelemetryWriter.class);
        when(telemetryWriter.enabled()).thenReturn(true);

        SearchQueryPlan plan = new SearchQueryPlan(
                "gdp germany", "en", "find_series", List.of("gdp"), List.of(), List.of("DE"), List.of("fred"),
                List.of(), List.of(), null, List.of("gdp germany"), List.of("gdp germany"), List.of(), List.of(),
                List.of(), List.of("primary"), new SearchQueryPlan.Clarification(false, null, null), "local_fallback", null);
        SearchCandidate candidate = candidateWithSource("metadata-only-candidate", "DE", "fred", "");
        SearchV2FtsRetriever.RetrievalResult retrieval = new SearchV2FtsRetriever.RetrievalResult(
                List.of(candidate), List.of(candidate), List.of("gdp germany"),
                List.of(Map.of("source", "fred", "query", "gdp germany", "count", 1, "ok", true, "index_mode", "sidecar")),
                3, "sidecar");

        when(indexStore.catalogVersion()).thenReturn("test-catalog");
        when(sidecarIndex.configuredMode(anyMap())).thenReturn("sidecar");
        when(planner.plan(anyMap())).thenReturn(plan);
        when(retriever.retrieve(eq(plan), anyList(), anyList(), anyLong(), eq("sidecar"))).thenReturn(retrieval);
        when(batchReranker.rerank(eq(plan), anyList(), eq(false))).thenReturn(
                new SearchV2SemanticValidator.ValidationResult(List.of(decisionFor(candidate, 0.9)), "disabled", null, 1, 0, List.of(), List.of()));
        // metadata_only shape per SearchV2Service.verifyPreview: one synthetic, non-per-candidate status.
        when(previewVerifier.verifyTopOnly(anyList(), anyInt(), anyList())).thenReturn(
                new SearchV2PreviewVerifier.VerificationResult(
                        List.of(), List.of(Map.of("mode", "metadata_only", "skipped", true, "ok", true)), 0, 0, 0));

        SearchV2Service service = new SearchV2Service(
                planner, retriever, merger, batchReranker, finalReranker(), new SearchV2CoverageChecker(),
                retryPlanner, previewVerifier, new SearchV2TraceStore(), new SearchV2CacheService(), indexStore,
                sidecarIndex, new SearchV2ExactEntityScorer(), mock(SearchV2ConceptRegistry.class), answerService(),
                mock(SearchV2ConceptOntology.class), telemetryWriter,
                mock(cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry.class),
                canonicalMetadataService(),
                mock(cz.bankintel.service.research.WebResearchService.class));

        Map<String, Object> response = service.search(Map.of(
                "query", "gdp germany", "use_ai", false, "use_ai_reranker", false, "eval_mode", "metadata_only",
                "no_cache", true, "debug", true));

        assertThat((List<?>) response.get("unverified"))
                .as("NOT_CHECKED in the legitimate metadata_only mode must never be auto-added to unverified")
                .isEmpty();
        verify(telemetryWriter, never()).submitRaw(argThat(payload ->
                "preview_not_checked_invariant_violation".equals(payload.get("event_type"))));
    }

    @Test
    void confirmedEmptyCandidateIsInNeitherResultsNorUnverified() {
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchCandidate empty = candidateWithSource("de-rate-empty", "DE", "ecb2", "MIR");
        Map<String, Object> response = runPreviewScenario(
                List.of(empty),
                List.of(Map.of("source", "ecb2", "series_id", "de-rate-empty", "ok", false, "preview_state", "ok", "rows", 0)),
                List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        assertThat(results).isEmpty();
        assertThat((List<?>) response.get("unverified"))
                .as("a confirmed-empty candidate is rejected, not unverified - preview gave real evidence of absence")
                .isEmpty();
        assertThat(response.get("status")).isEqualTo("no_valid_result");
    }

    @Test
    void mixedBatchKeepsVerifiedInResultsPutsTimeoutAndFailureInUnverifiedRejectsEmptyAndPreservesOrder() {
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchCandidate verifiedCandidate = candidateWithSource("mix-a-verified", "DE", "ecb2", "MIR");
        SearchCandidate timedOut = candidateWithSource("mix-b-timeout", "DE", "ecb2", "MIR");
        SearchCandidate empty = candidateWithSource("mix-c-empty", "DE", "ecb2", "MIR");
        SearchCandidate transportFailed = candidateWithSource("mix-d-error", "DE", "ecb2", "MIR");
        List<SearchCandidate> candidates = List.of(verifiedCandidate, timedOut, empty, transportFailed);

        Map<String, Object> response = runPreviewScenario(
                candidates,
                List.of(
                        Map.of("source", "ecb2", "series_id", "mix-a-verified", "ok", true, "preview_state", "ok", "rows", 5),
                        Map.of("source", "ecb2", "series_id", "mix-b-timeout", "ok", false, "preview_state", "timeout",
                                "reason", "preview_timeout_ms=8000"),
                        Map.of("source", "ecb2", "series_id", "mix-c-empty", "ok", false, "preview_state", "ok", "rows", 0),
                        Map.of("source", "ecb2", "series_id", "mix-d-error", "ok", false, "preview_state", "error",
                                "reason", "connection refused")),
                List.of(new SearchResult(verifiedCandidate, decisionFor(verifiedCandidate, 0.99), 1)),
                List.of(0.99, 0.90, 0.80, 0.70));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("series_id")).isEqualTo("mix-a-verified");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unverified = (List<Map<String, Object>>) response.get("unverified");
        assertThat(unverified.stream().map(row -> row.get("series_id")).toList())
                .as("empty candidate excluded, relative pre-preview relevance order preserved for the rest")
                .containsExactly("mix-b-timeout", "mix-d-error");
    }

    @Test
    void flagOffProducesExactlyTheOriginalResponseShape() {
        // SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED intentionally left unset.
        SearchCandidate timedOut = candidateWithSource("de-rate-timeout-off", "DE", "ecb2", "MIR");
        Map<String, Object> response = runPreviewScenario(
                List.of(timedOut),
                List.of(Map.of(
                        "source", "ecb2", "series_id", "de-rate-timeout-off", "ok", false,
                        "preview_state", "timeout", "reason", "preview_timeout_ms=8000")),
                List.of());

        assertThat(response)
                .as("flag off must never add the new key at all, not even as an empty list")
                .doesNotContainKey("unverified");
        assertThat(response.get("status"))
                .as("flag off must preserve the exact pre-PR-7b status semantics")
                .isEqualTo("no_valid_result");
    }

    @Test
    void storyNeverClaimsNothingWasFoundWhenAnUnverifiedCandidateExists() {
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchCandidate timedOut = candidateWithSource("de-rate-story", "DE", "ecb2", "MIR");
        Map<String, Object> response = runPreviewScenarioWithRealAnswerService(
                List.of(timedOut),
                List.of(Map.of(
                        "source", "ecb2", "series_id", "de-rate-story", "ok", false,
                        "preview_state", "timeout", "reason", "preview_timeout_ms=8000")),
                List.of());

        String answer = String.valueOf(response.get("answer"));
        assertThat(answer)
                .as("must not falsely claim nothing relevant was found when an unverified candidate exists")
                .doesNotContain("nebyla nalezena použitelná shoda");
        assertThat(response.get("story_mode")).isEqualTo("unverified_notice");
        assertNoInterpretiveLanguage(answer);
    }

    // ---- PR-7c: story safety for unverified_only --------------------------------------------------

    private static final List<String> FORBIDDEN_TREND_WORDS = List.of(
            "rostla", "klesala", "roste", "klesá", "zhoršuje", "zlepšuje", "potvrzují", "potvrzuje",
            "ukazují", "ukazuje", "vzrostl", "poklesl", "zvýšil", "snížil");

    /** Fáze 4 guardrail: percent signs and bare decimal numbers, both signs of a smuggled-in data value. */
    private static final java.util.regex.Pattern FORBIDDEN_NUMERIC_VALUE =
            java.util.regex.Pattern.compile("\\d+[.,]?\\d*\\s*%|\\b\\d+[.,]\\d+\\b");

    private static void assertNoInterpretiveLanguage(String answer) {
        String lower = answer.toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_TREND_WORDS) {
            assertThat(lower)
                    .as("story must never interpret values/trends for unverified data: found forbidden word '%s'", forbidden)
                    .doesNotContain(forbidden);
        }
        assertThat(lower)
                .as("must never claim data does not exist when a relevant-but-unverified candidate was found")
                .doesNotContain("neexistuj");
        assertThat(FORBIDDEN_NUMERIC_VALUE.matcher(answer).find())
                .as("story must never cite a percentage or decimal data value for unverified data: %s", answer)
                .isFalse();
        assertThat(lower)
                .as("must never claim the data confirms/shows something for unverified data")
                .doesNotContain("data ukaz")
                .doesNotContain("data potvrz");
    }

    @Test
    void onlyATimeoutCandidateProducesAStoryWithNoValueOrTrendInterpretation() {
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchCandidate timedOut = candidateWithSource("inflace-timeout", "CZ", "csu", "CPI");
        Map<String, Object> response = runPreviewScenarioWithRealAnswerService(
                List.of(timedOut),
                List.of(Map.of(
                        "source", "csu", "series_id", "inflace-timeout", "ok", false,
                        "preview_state", "timeout", "reason", "preview_timeout_ms=8000")),
                List.of());

        String answer = String.valueOf(response.get("answer"));
        assertNoInterpretiveLanguage(answer);
        assertThat(answer).contains("timeoutu");
        assertThat(response.get("story_mode")).isEqualTo("unverified_notice");
    }

    @Test
    void onlyAPossibleCandidateProducesAStoryThatOnlyNotesItIsUnverified() {
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchCandidate inconclusive = candidateWithSource("de-rate-possible-story", "DE", "ecb2", "MIR");
        Map<String, Object> response = runPreviewScenarioWithRealAnswerService(
                List.of(inconclusive),
                List.of(Map.of(
                        "source", "ecb2", "series_id", "de-rate-possible-story", "ok", false,
                        "preview_state", "something_unrecognized")),
                List.of());

        String answer = String.valueOf(response.get("answer"));
        assertNoInterpretiveLanguage(answer);
        assertThat(answer)
                .as("must state the candidate is unconfirmed")
                .contains("Výsledek není potvrzený");
        assertThat(response.get("story_mode")).isEqualTo("unverified_notice");
    }

    @Test
    void transportFailureAndCircuitOpenCandidatesProduceASafeSummaryNotAClaimThatDataDoesNotExist() {
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchCandidate transportFailed = candidateWithSource("bank-a-error", "CZ", "ecb2", "MIR");
        SearchCandidate circuitOpen = candidateWithSource("bank-b-circuit", "CZ", "ecb2", "MIR");
        Map<String, Object> response = runPreviewScenarioWithRealAnswerService(
                List.of(transportFailed, circuitOpen),
                List.of(
                        Map.of("source", "ecb2", "series_id", "bank-a-error", "ok", false, "preview_state", "error",
                                "reason", "connection refused"),
                        Map.of("source", "ecb2", "series_id", "bank-b-circuit", "ok", false, "preview_state", "circuit_open",
                                "reason", "circuit_breaker_open:ecb2")),
                List.of());

        String answer = String.valueOf(response.get("answer"));
        assertNoInterpretiveLanguage(answer);
        assertThat(response.get("story_mode")).isEqualTo("unverified_notice");
    }

    @Test
    void verifiedCandidateStoryPathIsUnaffectedByTheNewUnverifiedNoticeMode() {
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchCandidate verifiedCandidate = candidateWithSource("de-rate-verified-story", "DE", "ecb2", "MIR");
        Map<String, Object> response = runPreviewScenarioWithRealAnswerService(
                List.of(verifiedCandidate),
                List.of(Map.of(
                        "source", "ecb2", "series_id", "de-rate-verified-story", "ok", true, "preview_state", "ok", "rows", 5)),
                List.of(new SearchResult(verifiedCandidate, decisionFor(verifiedCandidate, 0.95), 1)));

        assertThat(response.get("story_mode")).isEqualTo("verified_data");
        assertThat(String.valueOf(response.get("answer"))).doesNotContain("Výsledek není potvrzený");
    }

    @Test
    void explicitUseAiStoryTrueDuringUnverifiedOnlyNeverInvokesTheLlm() {
        System.setProperty("SEARCH_SEMANTIC_RETRIEVAL_ENABLED", "false");
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchV2QueryPlanner planner = mock(SearchV2QueryPlanner.class);
        SearchV2FtsRetriever retriever = mock(SearchV2FtsRetriever.class);
        SearchV2CandidateMerger merger = mock(SearchV2CandidateMerger.class);
        SearchV2BatchReranker batchReranker = mock(SearchV2BatchReranker.class);
        SearchV2RetryPlanner retryPlanner = mock(SearchV2RetryPlanner.class);
        SearchV2PreviewVerifier previewVerifier = mock(SearchV2PreviewVerifier.class);
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        SearchCatalogSidecarIndex sidecarIndex = mock(SearchCatalogSidecarIndex.class);
        cz.bankintel.search.openai.OpenAiClient openAiClient = mock(cz.bankintel.search.openai.OpenAiClient.class);
        when(openAiClient.isConfigured()).thenReturn(true); // LLM WOULD be available/used for other story modes
        cz.bankintel.search.openai.OpenAiJsonSupport openAiJsonSupport = mock(cz.bankintel.search.openai.OpenAiJsonSupport.class);
        CatalogSearchAnswerService realAnswerService =
                new CatalogSearchAnswerService(openAiJsonSupport, openAiClient, new ObjectMapper());

        SearchQueryPlan plan = new SearchQueryPlan(
                "urokova mira nemecko", "cs", "find_series", List.of("interest rate"), List.of("interest_rate"),
                List.of("DE"), List.of("ecb2"), List.of("interest_rate"), List.of(), null,
                List.of("interest rate Germany"), List.of("interest rate"), List.of(), List.of(), List.of(),
                List.of("primary"), new SearchQueryPlan.Clarification(false, null, null), "local_fallback", null);
        SearchCandidate timedOut = candidateWithSource("de-rate-force-ai", "DE", "ecb2", "MIR");
        SearchV2FtsRetriever.RetrievalResult retrieval = new SearchV2FtsRetriever.RetrievalResult(
                List.of(timedOut), List.of(timedOut), List.of("interest rate"),
                List.of(Map.of("source", "ecb2", "query", "interest rate", "count", 1, "ok", true, "index_mode", "sidecar")),
                3, "sidecar");
        SemanticDecision decision = decisionFor(timedOut, 0.9);

        when(indexStore.catalogVersion()).thenReturn("test-catalog");
        when(sidecarIndex.configuredMode(anyMap())).thenReturn("sidecar");
        when(planner.plan(anyMap())).thenReturn(plan);
        when(retriever.retrieve(eq(plan), anyList(), anyList(), anyLong(), eq("sidecar"))).thenReturn(retrieval);
        when(batchReranker.rerank(eq(plan), anyList(), eq(true))).thenReturn(
                new SearchV2SemanticValidator.ValidationResult(List.of(decision), "validated", "test-reranker", 1, 0, List.of(), List.of()));
        when(previewVerifier.verifyTopOnly(anyList(), anyInt(), anyList())).thenReturn(
                new SearchV2PreviewVerifier.VerificationResult(
                        List.of(),
                        List.of(Map.of(
                                "source", "ecb2", "series_id", "de-rate-force-ai", "ok", false,
                                "preview_state", "timeout", "reason", "preview_timeout_ms=8000")),
                        8000, 1, 1));

        SearchV2Service service = new SearchV2Service(
                planner, retriever, merger, batchReranker, finalReranker(), new SearchV2CoverageChecker(),
                retryPlanner, previewVerifier, new SearchV2TraceStore(), new SearchV2CacheService(), indexStore,
                sidecarIndex, new SearchV2ExactEntityScorer(), mock(SearchV2ConceptRegistry.class), realAnswerService,
                mock(SearchV2ConceptOntology.class), new SearchV2TelemetryWriter(new ObjectMapper()),
                mock(cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry.class),
                canonicalMetadataService(),
                mock(cz.bankintel.service.research.WebResearchService.class));

        Map<String, Object> response = service.search(Map.of(
                "query", "urokova mira nemecko", "use_ai", true, "use_ai_reranker", true,
                "use_ai_story", true, "no_cache", true, "debug", true));

        assertThat(response.get("story_mode")).isEqualTo("unverified_notice");
        assertNoInterpretiveLanguage(String.valueOf(response.get("answer")));
        verify(openAiJsonSupport, never()).chatJsonObject(any(), any(), any());
    }

    @Test
    void checkedCandidateMissingItsOwnStatusEmitsAnInvariantViolationTelemetryEventButIsNeverAutoAddedToUnverified() {
        System.setProperty("SEARCH_SEMANTIC_RETRIEVAL_ENABLED", "false");
        System.setProperty("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED", "true");
        SearchV2QueryPlanner planner = mock(SearchV2QueryPlanner.class);
        SearchV2FtsRetriever retriever = mock(SearchV2FtsRetriever.class);
        SearchV2CandidateMerger merger = mock(SearchV2CandidateMerger.class);
        SearchV2BatchReranker batchReranker = mock(SearchV2BatchReranker.class);
        SearchV2RetryPlanner retryPlanner = mock(SearchV2RetryPlanner.class);
        SearchV2PreviewVerifier previewVerifier = mock(SearchV2PreviewVerifier.class);
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        SearchCatalogSidecarIndex sidecarIndex = mock(SearchCatalogSidecarIndex.class);
        SearchV2TelemetryWriter telemetryWriter = mock(SearchV2TelemetryWriter.class);
        when(telemetryWriter.enabled()).thenReturn(true);

        SearchQueryPlan plan = new SearchQueryPlan(
                "urokova mira nemecko", "cs", "find_series", List.of("interest rate"), List.of("interest_rate"),
                List.of("DE"), List.of("ecb2"), List.of("interest_rate"), List.of(), null,
                List.of("interest rate Germany"), List.of("interest rate"), List.of(), List.of(), List.of(),
                List.of("primary"), new SearchQueryPlan.Clarification(false, null, null), "local_fallback", null);
        SearchCandidate orphan = candidateWithSource("orphan-candidate", "DE", "ecb2", "MIR");
        SearchV2FtsRetriever.RetrievalResult retrieval = new SearchV2FtsRetriever.RetrievalResult(
                List.of(orphan), List.of(orphan), List.of("interest rate"),
                List.of(Map.of(
                        "source", "ecb2", "query", "interest rate", "count", 1, "ok", true, "index_mode", "sidecar")),
                3, "sidecar");
        SemanticDecision decision = decisionFor(orphan, 0.95);

        when(indexStore.catalogVersion()).thenReturn("test-catalog");
        when(sidecarIndex.configuredMode(anyMap())).thenReturn("sidecar");
        when(planner.plan(anyMap())).thenReturn(plan);
        when(retriever.retrieve(eq(plan), anyList(), anyList(), anyLong(), eq("sidecar"))).thenReturn(retrieval);
        when(batchReranker.rerank(eq(plan), anyList(), eq(false))).thenReturn(
                new SearchV2SemanticValidator.ValidationResult(List.of(decision), "disabled", null, 1, 0, List.of(), List.of()));
        // checkedCount=1 claims one candidate was actually checked, but statuses is empty - this
        // deliberately manufactures the exact anomaly the invariant check exists to catch (in real
        // operation, checkedCount always equals statuses.size(), so this cannot happen today).
        when(previewVerifier.verifyTopOnly(anyList(), anyInt(), anyList())).thenReturn(
                new SearchV2PreviewVerifier.VerificationResult(List.of(), List.of(), 50, 1, 0));

        SearchV2Service service = new SearchV2Service(
                planner, retriever, merger, batchReranker, finalReranker(), new SearchV2CoverageChecker(),
                retryPlanner, previewVerifier, new SearchV2TraceStore(), new SearchV2CacheService(), indexStore,
                sidecarIndex, new SearchV2ExactEntityScorer(), mock(SearchV2ConceptRegistry.class), answerService(),
                mock(SearchV2ConceptOntology.class), telemetryWriter,
                mock(cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry.class),
                canonicalMetadataService(),
                mock(cz.bankintel.service.research.WebResearchService.class));

        Map<String, Object> response = service.search(Map.of(
                "query", "urokova mira nemecko", "use_ai", false, "use_ai_reranker", false, "no_cache", true, "debug", true));

        verify(telemetryWriter).submitRaw(argThat(payload ->
                "preview_not_checked_invariant_violation".equals(payload.get("event_type"))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unverified = (List<Map<String, Object>>) response.get("unverified");
        assertThat(unverified)
                .as("NOT_CHECKED must never be auto-added to unverified without proof of correct semantics")
                .isEmpty();
    }

    private static SemanticDecision decisionFor(SearchCandidate candidate, double score) {
        return new SemanticDecision(
                candidate.seriesId(), "keep", score, 0.92, List.of("interest_rate"), List.of(), "match", "primary");
    }

    private static Map<String, Object> runPreviewScenario(
            List<SearchCandidate> candidates,
            List<Map<String, Object>> previewStatuses,
            List<SearchResult> previewAccepted) {
        return runPreviewScenario(candidates, previewStatuses, previewAccepted, null);
    }

    private static Map<String, Object> runPreviewScenario(
            List<SearchCandidate> candidates,
            List<Map<String, Object>> previewStatuses,
            List<SearchResult> previewAccepted,
            List<Double> relevanceScores) {
        return runPreviewScenario(candidates, previewStatuses, previewAccepted, relevanceScores, answerService());
    }

    private static Map<String, Object> runPreviewScenarioWithRealAnswerService(
            List<SearchCandidate> candidates,
            List<Map<String, Object>> previewStatuses,
            List<SearchResult> previewAccepted) {
        cz.bankintel.search.openai.OpenAiClient openAiClient = mock(cz.bankintel.search.openai.OpenAiClient.class);
        CatalogSearchAnswerService realAnswerService = new CatalogSearchAnswerService(
                mock(cz.bankintel.search.openai.OpenAiJsonSupport.class), openAiClient, new ObjectMapper());
        return runPreviewScenario(candidates, previewStatuses, previewAccepted, null, realAnswerService);
    }

    private static Map<String, Object> runPreviewScenario(
            List<SearchCandidate> candidates,
            List<Map<String, Object>> previewStatuses,
            List<SearchResult> previewAccepted,
            List<Double> relevanceScores,
            CatalogSearchAnswerService answerService) {
        System.setProperty("SEARCH_SEMANTIC_RETRIEVAL_ENABLED", "false");
        SearchV2QueryPlanner planner = mock(SearchV2QueryPlanner.class);
        SearchV2FtsRetriever retriever = mock(SearchV2FtsRetriever.class);
        SearchV2CandidateMerger merger = mock(SearchV2CandidateMerger.class);
        SearchV2BatchReranker batchReranker = mock(SearchV2BatchReranker.class);
        SearchV2RetryPlanner retryPlanner = mock(SearchV2RetryPlanner.class);
        SearchV2PreviewVerifier previewVerifier = mock(SearchV2PreviewVerifier.class);
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        SearchCatalogSidecarIndex sidecarIndex = mock(SearchCatalogSidecarIndex.class);

        SearchQueryPlan plan = new SearchQueryPlan(
                "urokova mira nemecko", "cs", "find_series", List.of("interest rate"), List.of("interest_rate"),
                List.of("DE"), List.of("ecb2"), List.of("interest_rate"), List.of(), null,
                List.of("interest rate Germany"), List.of("interest rate"), List.of(), List.of(), List.of(),
                List.of("primary"), new SearchQueryPlan.Clarification(false, null, null), "local_fallback", null);
        SearchV2FtsRetriever.RetrievalResult retrieval = new SearchV2FtsRetriever.RetrievalResult(
                candidates, candidates, List.of("interest rate"),
                List.of(Map.of(
                        "source", "ecb2", "query", "interest rate", "count", candidates.size(), "ok", true,
                        "index_mode", "sidecar")),
                3, "sidecar");
        List<SemanticDecision> decisions = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            double score = relevanceScores != null && i < relevanceScores.size() ? relevanceScores.get(i) : 0.9 - i * 0.01;
            decisions.add(decisionFor(candidates.get(i), score));
        }

        when(indexStore.catalogVersion()).thenReturn("test-catalog");
        when(sidecarIndex.configuredMode(anyMap())).thenReturn("sidecar");
        when(planner.plan(anyMap())).thenReturn(plan);
        when(retriever.retrieve(eq(plan), anyList(), anyList(), anyLong(), eq("sidecar"))).thenReturn(retrieval);
        when(merger.merge(anyList(), eq(240))).thenReturn(candidates);
        when(batchReranker.rerank(eq(plan), anyList(), eq(false))).thenReturn(
                new SearchV2SemanticValidator.ValidationResult(decisions, "disabled", null, decisions.size(), 0, List.of(), List.of()));
        when(previewVerifier.verifyTopOnly(anyList(), anyInt(), anyList())).thenReturn(
                new SearchV2PreviewVerifier.VerificationResult(
                        previewAccepted, previewStatuses, 100, previewStatuses.size(), previewStatuses.size()));

        SearchV2Service service = new SearchV2Service(
                planner, retriever, merger, batchReranker, finalReranker(), new SearchV2CoverageChecker(),
                retryPlanner, previewVerifier, new SearchV2TraceStore(), new SearchV2CacheService(), indexStore,
                sidecarIndex, new SearchV2ExactEntityScorer(), mock(SearchV2ConceptRegistry.class), answerService,
                mock(SearchV2ConceptOntology.class), new SearchV2TelemetryWriter(new ObjectMapper()),
                mock(cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry.class),
                canonicalMetadataService(),
                mock(cz.bankintel.service.research.WebResearchService.class));

        return service.search(Map.of(
                "query", "urokova mira nemecko", "use_ai", false, "use_ai_reranker", false, "no_cache", true, "debug", true));
    }

    @Test
    void telemetryDisabledByFlagNeverSubmitsAnEvent() {
        System.setProperty("SEARCH_SEMANTIC_RETRIEVAL_ENABLED", "false");
        SearchV2TelemetryWriter telemetryWriter = mock(SearchV2TelemetryWriter.class);
        when(telemetryWriter.enabled()).thenReturn(false);

        Map<String, Object> response = runMinimalSearch(telemetryWriter, mock(SearchV2ConceptRegistry.class));

        assertThat(response.get("search_engine")).isEqualTo("v2");
        verify(telemetryWriter, never()).submit(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void telemetryWriterFailureNeverBreaksTheSearchResponse() {
        System.setProperty("SEARCH_SEMANTIC_RETRIEVAL_ENABLED", "false");
        SearchV2TelemetryWriter telemetryWriter = mock(SearchV2TelemetryWriter.class);
        when(telemetryWriter.enabled()).thenReturn(true);
        doThrow(new RuntimeException("telemetry writer boom"))
                .when(telemetryWriter)
                .submit(org.mockito.ArgumentMatchers.any());
        SearchV2ConceptRegistry conceptRegistry = mock(SearchV2ConceptRegistry.class);
        when(conceptRegistry.resolve(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(SearchV2ConceptRegistry.ConceptResolution.empty());

        Map<String, Object> response = runMinimalSearch(telemetryWriter, conceptRegistry);

        assertThat(response.get("search_engine")).isEqualTo("v2");
        assertThat(response.get("status")).isNotEqualTo("error");
        assertThat(response.get("ok")).isEqualTo(true);
    }

    /** Minimal end-to-end run (empty retrieval pool) used only to reach the telemetry emission point. */
    private static Map<String, Object> runMinimalSearch(
            SearchV2TelemetryWriter telemetryWriter, SearchV2ConceptRegistry conceptRegistry) {
        SearchV2QueryPlanner planner = mock(SearchV2QueryPlanner.class);
        SearchV2FtsRetriever retriever = mock(SearchV2FtsRetriever.class);
        SearchV2CandidateMerger merger = mock(SearchV2CandidateMerger.class);
        SearchV2BatchReranker batchReranker = mock(SearchV2BatchReranker.class);
        SearchV2RetryPlanner retryPlanner = mock(SearchV2RetryPlanner.class);
        SearchV2PreviewVerifier previewVerifier = mock(SearchV2PreviewVerifier.class);
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        SearchCatalogSidecarIndex sidecarIndex = mock(SearchCatalogSidecarIndex.class);

        SearchQueryPlan plan = new SearchQueryPlan(
                "gdp germany",
                "en",
                "find_series",
                List.of("gdp"),
                List.of(),
                List.of("DE"),
                List.of("fred"),
                List.of(),
                List.of(),
                null,
                List.of("gdp germany"),
                List.of("gdp germany"),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "local_fallback",
                null);
        SearchV2FtsRetriever.RetrievalResult emptyRetrieval = new SearchV2FtsRetriever.RetrievalResult(
                List.of(), List.of(), List.of("gdp germany"), List.of(), 1, "sidecar");

        when(indexStore.catalogVersion()).thenReturn("test-catalog");
        when(sidecarIndex.configuredMode(anyMap())).thenReturn("sidecar");
        when(planner.plan(anyMap())).thenReturn(plan);
        when(retriever.retrieve(eq(plan), anyList(), anyList(), anyLong(), eq("sidecar"))).thenReturn(emptyRetrieval);
        when(retryPlanner.retryTerms(eq(plan), any())).thenReturn(List.of());
        when(batchReranker.rerank(eq(plan), anyList(), eq(false))).thenReturn(
                new SearchV2SemanticValidator.ValidationResult(List.of(), "disabled", null, 0, 0, List.of(), List.of()));
        when(previewVerifier.verifyTopOnly(anyList(), anyInt(), anyList())).thenReturn(
                new SearchV2PreviewVerifier.VerificationResult(List.of(), List.of(), 0, 0, 0));

        SearchV2Service service = new SearchV2Service(
                planner,
                retriever,
                merger,
                batchReranker,
                finalReranker(),
                new SearchV2CoverageChecker(),
                retryPlanner,
                previewVerifier,
                new SearchV2TraceStore(),
                new SearchV2CacheService(),
                indexStore,
                sidecarIndex,
                new SearchV2ExactEntityScorer(),
                conceptRegistry,
                answerService(),
                mock(SearchV2ConceptOntology.class),
                telemetryWriter,
                mock(cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry.class),
                canonicalMetadataService(),
                mock(cz.bankintel.service.research.WebResearchService.class));

        return service.search(Map.of(
                "query", "gdp germany",
                "use_ai", false,
                "use_ai_reranker", false,
                "eval_mode", "metadata_only",
                "no_cache", true,
                "debug", true));
    }

    private static SearchCandidate candidate(String id, String geo) {
        return candidateWithSource(id, geo, "ecb2", "CBD2");
    }

    private static SearchCandidate candidateWithSource(String id, String geo, String source, String dataset) {
        return new SearchCandidate(
                source + ":" + id,
                id,
                "ROA " + geo,
                "",
                source,
                dataset,
                geo,
                "A",
                "PC",
                "",
                List.of("bank_roa"),
                List.of(),
                List.of(),
                "",
                1.0,
                "ROA bank",
                List.of("canonical_title"),
                Map.of("primary_concept", "bank_roa", "catalog_family", "banking"));
    }

    private static SearchV2FinalReranker finalReranker() {
        return new SearchV2FinalReranker(
                new cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry(
                        new com.fasterxml.jackson.databind.ObjectMapper()),
                new cz.bankintel.search.v2.ontology.SearchV2IndustrySectorRegistry(
                        new com.fasterxml.jackson.databind.ObjectMapper()),
                new cz.bankintel.sources.eurostat.EurostatDimensionService(
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        new cz.bankintel.sources.eurostat.EurostatRateLimiter()));
    }

    private static SearchResultCanonicalMetadataService canonicalMetadataService() {
        ObjectMapper mapper = new ObjectMapper();
        return new SearchResultCanonicalMetadataService(
                new cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry(mapper),
                new cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry(mapper));
    }

    private static CatalogSearchAnswerService answerService() {
        CatalogSearchAnswerService service = mock(CatalogSearchAnswerService.class);
        when(service.composeStory(any(), anyList(), anyList())).thenReturn(Map.of(
                "headline_cz", "Testovací shrnutí",
                "answer_cz", "Testovací odpověď",
                "drivers", List.of(),
                "series_used", List.of()));
        return service;
    }

    private static void assertSequentialFunnelDoesNotIncrease(Map<String, Object> counts) {
        int retrievedRaw = ((Number) counts.get("retrieved_raw")).intValue();
        int unique = ((Number) counts.get("deduplicated_unique")).intValue();
        int hard = ((Number) counts.get("after_hard_constraints")).intValue();
        int balanced = ((Number) counts.get("after_source_balancing")).intValue();
        int limited = ((Number) counts.get("after_candidate_limit")).intValue();
        int deterministic = ((Number) counts.get("sent_to_deterministic_reranker")).intValue();
        int llm = ((Number) counts.get("sent_to_llm_reranker")).intValue();
        int preview = ((Number) counts.get("sent_to_preview")).intValue();
        int finalResults = ((Number) counts.get("final_results")).intValue();

        assertThat(retrievedRaw).isGreaterThanOrEqualTo(unique);
        assertThat(unique).isGreaterThanOrEqualTo(hard);
        assertThat(hard).isGreaterThanOrEqualTo(balanced);
        assertThat(balanced).isGreaterThanOrEqualTo(limited);
        assertThat(deterministic).isLessThanOrEqualTo(limited);
        assertThat(llm).isLessThanOrEqualTo(limited);
        assertThat(Math.min(deterministic, llm)).isZero();
        assertThat(preview).isZero();
        assertThat(finalResults).isLessThanOrEqualTo(Math.max(deterministic, llm));
    }
}
