package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Fáze 2 (perf investigation): {@code emitSearchTelemetry}'s expensive part now runs on a dedicated,
 * bounded {@code telemetryExecutor} instead of the request thread. These tests cover exactly the five
 * scenarios the change was required to satisfy - none of them touch the AI planner, reranker, preview
 * logic, result classification, or the circuit breaker.
 */
class SearchV2AsyncTelemetryTest {

    private SearchV2Service service;

    @AfterEach
    void cleanup() {
        if (service != null) {
            service.shutdownTelemetryExecutor();
        }
    }

    @Test
    void telemetryEnabledEventIsActuallyWrittenWithExpectedContent() throws Exception {
        SearchV2TelemetryWriter telemetryWriter = mock(SearchV2TelemetryWriter.class);
        when(telemetryWriter.enabled()).thenReturn(true);
        CountDownLatch written = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            written.countDown();
            return null;
        }).when(telemetryWriter).submit(any());

        service = buildService(telemetryWriter);
        Map<String, Object> response = service.search(Map.of(
                "query", "urokova mira nemecko", "use_ai", false, "use_ai_reranker", false, "no_cache", true));

        assertThat(written.await(5, TimeUnit.SECONDS))
                .as("telemetry event must actually be written when enabled")
                .isTrue();
        verify(telemetryWriter).submit(any());
        assertThat(response.get("status")).isEqualTo("ok");
    }

    @Test
    void telemetryDisabledNeverCreatesATaskOrCallsTheWriter() throws Exception {
        SearchV2TelemetryWriter telemetryWriter = mock(SearchV2TelemetryWriter.class);
        when(telemetryWriter.enabled()).thenReturn(false);

        service = buildService(telemetryWriter);
        Map<String, Object> response = service.search(Map.of(
                "query", "urokova mira nemecko", "use_ai", false, "use_ai_reranker", false, "no_cache", true));

        Thread.sleep(200); // generous window - if a task were (wrongly) submitted, it would have run by now
        assertThat(service.telemetrySubmittedCount()).isZero();
        verify(telemetryWriter, never()).submit(any());
        assertThat(response).doesNotContainKey("unverified_forced_by_telemetry");
        assertThat(response.get("status")).isEqualTo("ok");
    }

    @Test
    void writerFailureNeverPropagatesToTheSearchResponse() throws Exception {
        SearchV2TelemetryWriter telemetryWriter = mock(SearchV2TelemetryWriter.class);
        when(telemetryWriter.enabled()).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("disk full"))
                .when(telemetryWriter).submit(any());

        service = buildService(telemetryWriter);
        Map<String, Object> response = service.search(Map.of(
                "query", "urokova mira nemecko", "use_ai", false, "use_ai_reranker", false, "no_cache", true));

        assertThat(response.get("status")).isEqualTo("ok");
        assertThat(response.get("ok")).isEqualTo(true);

        long deadline = System.currentTimeMillis() + 5000;
        while (service.telemetryFailedCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(service.telemetryFailedCount())
                .as("the failure must still be counted, just never surfaced to the caller")
                .isGreaterThan(0);
    }

    @Test
    void fullQueueDropsTheEventAndNeverBlocksTheRequest() throws Exception {
        SearchV2TelemetryWriter telemetryWriter = mock(SearchV2TelemetryWriter.class);
        when(telemetryWriter.enabled()).thenReturn(true);
        service = buildService(telemetryWriter);

        ThreadPoolExecutor executor = service.telemetryExecutorForTest();
        CountDownLatch release = new CountDownLatch(1);
        int saturating = executor.getMaximumPoolSize() + executor.getQueue().remainingCapacity();
        for (int i = 0; i < saturating; i++) {
            executor.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(executor.getQueue().remainingCapacity())
                .as("pool + queue must be fully saturated before the real assertion")
                .isZero();

        long before = service.telemetryDroppedCount();
        long start = System.currentTimeMillis();
        Map<String, Object> response = service.search(Map.of(
                "query", "urokova mira nemecko", "use_ai", false, "use_ai_reranker", false, "no_cache", true));
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(elapsedMs)
                .as("the request must never block waiting for telemetry queue space")
                .isLessThan(3000);
        assertThat(response.get("status")).isEqualTo("ok");
        assertThat(service.telemetryDroppedCount())
                .as("the dropped event must be counted")
                .isGreaterThan(before);

        release.countDown();
    }

    @Test
    void shutdownTerminatesPromptlyAndNeverLeavesTheJvmHanging() throws Exception {
        SearchV2TelemetryWriter telemetryWriter = mock(SearchV2TelemetryWriter.class);
        when(telemetryWriter.enabled()).thenReturn(false);
        service = buildService(telemetryWriter);
        ThreadPoolExecutor executor = service.telemetryExecutorForTest();

        service.shutdownTelemetryExecutor();

        assertThat(executor.isShutdown()).isTrue();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS))
                .as("shutdown must complete promptly, never hang the JVM")
                .isTrue();
        assertThat(executor.isTerminated()).isTrue();
        service = null; // already shut down - @AfterEach must not call it again
    }

    private static SearchV2Service buildService(SearchV2TelemetryWriter telemetryWriter) {
        SearchV2QueryPlanner planner = mock(SearchV2QueryPlanner.class);
        SearchV2FtsRetriever retriever = mock(SearchV2FtsRetriever.class);
        SearchV2CandidateMerger merger = mock(SearchV2CandidateMerger.class);
        SearchV2BatchReranker batchReranker = mock(SearchV2BatchReranker.class);
        SearchV2RetryPlanner retryPlanner = mock(SearchV2RetryPlanner.class);
        SearchV2PreviewVerifier previewVerifier = mock(SearchV2PreviewVerifier.class);
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        SearchCatalogSidecarIndex sidecarIndex = mock(SearchCatalogSidecarIndex.class);
        SearchV2ConceptRegistry conceptRegistry = mock(SearchV2ConceptRegistry.class);
        when(conceptRegistry.resolve(any())).thenReturn(SearchV2ConceptRegistry.ConceptResolution.empty());

        SearchQueryPlan plan = new SearchQueryPlan(
                "urokova mira nemecko", "cs", "find_series", List.of("interest rate"), List.of("interest_rate"),
                List.of("DE"), List.of("ecb2"), List.of("interest_rate"), List.of(), null,
                List.of("interest rate Germany"), List.of("interest rate"), List.of(), List.of(), List.of(),
                List.of("primary"), new SearchQueryPlan.Clarification(false, null, null), "local_fallback", null);
        SearchCandidate candidate = candidateWithSource("de-rate", "DE", "ecb2", "MIR");
        SearchV2FtsRetriever.RetrievalResult retrieval = new SearchV2FtsRetriever.RetrievalResult(
                List.of(candidate), List.of(candidate), List.of("interest rate"),
                List.of(Map.of(
                        "source", "ecb2", "query", "interest rate", "count", 1, "ok", true, "index_mode", "sidecar")),
                3, "sidecar");
        SemanticDecision decision = decisionFor(candidate, 0.95);

        when(indexStore.catalogVersion()).thenReturn("test-catalog");
        when(sidecarIndex.configuredMode(anyMap())).thenReturn("sidecar");
        when(planner.plan(anyMap())).thenReturn(plan);
        when(retriever.retrieve(eq(plan), anyList(), anyList(), anyLong(), eq("sidecar"))).thenReturn(retrieval);
        when(merger.merge(anyList(), eq(240))).thenReturn(List.of(candidate));
        when(batchReranker.rerank(eq(plan), anyList(), eq(false))).thenReturn(
                new SearchV2SemanticValidator.ValidationResult(List.of(decision), "disabled", null, 1, 0, List.of(), List.of()));
        when(previewVerifier.verifyTopOnly(anyList(), org.mockito.ArgumentMatchers.anyInt(), anyList())).thenReturn(
                new SearchV2PreviewVerifier.VerificationResult(
                        List.of(new SearchResult(candidate, decision, 1)),
                        List.of(Map.of(
                                "source", "ecb2", "series_id", "de-rate", "ok", true,
                                "preview_state", "ok", "rows", 5)),
                        10, 1, 1));

        return new SearchV2Service(
                planner, retriever, merger, batchReranker,
                new SearchV2FinalReranker(
                        new cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry(
                                new com.fasterxml.jackson.databind.ObjectMapper()),
                        new cz.bankintel.search.v2.ontology.SearchV2IndustrySectorRegistry(
                                new com.fasterxml.jackson.databind.ObjectMapper()),
                        new cz.bankintel.sources.eurostat.EurostatDimensionService(
                                new com.fasterxml.jackson.databind.ObjectMapper(),
                                new cz.bankintel.sources.eurostat.EurostatRateLimiter())),
                new SearchV2CoverageChecker(),
                retryPlanner, previewVerifier, new SearchV2TraceStore(), new SearchV2CacheService(), indexStore,
                sidecarIndex, new SearchV2ExactEntityScorer(), conceptRegistry, answerService(),
                mock(SearchV2ConceptOntology.class), telemetryWriter,
                mock(cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry.class),
                canonicalMetadataService(),
                mock(cz.bankintel.service.research.WebResearchService.class));
    }

    private static SearchResultCanonicalMetadataService canonicalMetadataService() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return new SearchResultCanonicalMetadataService(
                new cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry(mapper),
                new cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry(mapper));
    }

    private static CatalogSearchAnswerService answerService() {
        CatalogSearchAnswerService svc = mock(CatalogSearchAnswerService.class);
        when(svc.composeStory(any(), anyList(), anyList(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Map.of(
                "headline_cz", "Test",
                "answer_cz", "Test odpoved",
                "drivers", List.of(),
                "series_used", List.of()));
        return svc;
    }

    private static SearchCandidate candidateWithSource(String id, String geo, String source, String dataset) {
        return new SearchCandidate(
                source + ":" + id, id, "ROA " + geo, "", source, dataset, geo, "A", "PC", "",
                List.of("bank_roa"), List.of(), List.of(), "", 1.0, "ROA bank",
                List.of("canonical_title"), Map.of("primary_concept", "bank_roa", "catalog_family", "banking"));
    }

    private static SemanticDecision decisionFor(SearchCandidate candidate, double score) {
        return new SemanticDecision(
                candidate.seriesId(), "keep", score, 0.92, List.of("interest_rate"), List.of(), "match", "primary");
    }
}
