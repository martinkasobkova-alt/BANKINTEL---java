package cz.bankintel.search.v2.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.v2.coverage.SearchV2CoverageChecker;
import cz.bankintel.search.v2.entity.SearchV2ExactEntityScorer;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SemanticDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Sanity-check benchmark, not a strict performance gate: measures wall-clock cost of building +
 * submitting one telemetry event for a realistic rerank-pool size (60 candidates, the
 * MAX_RERANK_CANDIDATES pipeline constant), to give an actual measured number for the PR-1 report
 * instead of a guess. Threshold is intentionally generous to avoid CI flakiness.
 */
class SearchV2TelemetryLatencyBenchmarkTest {

    private static final int REALISTIC_RERANK_POOL_SIZE = 60;

    @Test
    void buildingAndSubmittingOneEventForAFullRerankPoolStaysWellUnderTenMilliseconds() {
        SearchV2ExactEntityScorer scorer = mock(SearchV2ExactEntityScorer.class);
        when(scorer.exactScore(any(), any())).thenReturn(0.0);
        SearchV2ConceptRegistry conceptRegistry = mock(SearchV2ConceptRegistry.class);

        List<SearchCandidate> candidates = new ArrayList<>();
        List<SemanticDecision> decisions = new ArrayList<>();
        List<SearchResult> ranked = new ArrayList<>();
        for (int i = 0; i < REALISTIC_RERANK_POOL_SIZE; i++) {
            SearchCandidate candidate = new SearchCandidate(
                    "fred:series-" + i,
                    "series-" + i,
                    "Title series " + i,
                    "A reasonably long description field, as real catalog rows tend to have, for series " + i,
                    "fred",
                    "dataset-" + i,
                    "US",
                    "A",
                    "PC",
                    "",
                    List.of("concept-a", "concept-b"),
                    List.of("tag-a"),
                    List.of("root", "macro"),
                    "2025-01-01",
                    1.0 - i * 0.01,
                    "gdp united states",
                    List.of(),
                    Map.of(
                            "_vector_score", 0.5,
                            "_vector_rank", i,
                            "metadata_quality_score", 0.8,
                            "lifecycle_status", "current"));
            candidates.add(candidate);
            SemanticDecision decision =
                    new SemanticDecision("series-" + i, i % 3 == 0 ? "drop" : "keep", 0.7, 0.8, List.of(), List.of(), "reason", "primary");
            decisions.add(decision);
            if (i % 3 != 0) {
                ranked.add(new SearchResult(candidate, decision, ranked.size() + 1));
            }
        }

        SearchQueryPlan plan = new SearchQueryPlan(
                "gdp united states",
                "en",
                "find_series",
                List.of("gdp"),
                List.of(),
                List.of("US"),
                List.of("fred"),
                List.of(),
                List.of(),
                null,
                List.of("gdp united states"),
                List.of("gdp united states"),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "openai",
                "test-planner-model");
        SearchV2CoverageChecker.CoverageResult coverage =
                new SearchV2CoverageChecker.CoverageResult("complete", List.of(), false, "ok");
        SearchV2TelemetryWriter writer = new SearchV2TelemetryWriter(new ObjectMapper(), true, 1000, java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "search-v2-telemetry-benchmark.jsonl"));

        // Long-running production JVMs are always warm (JIT, class-loading, Jackson reflection
        // caches). A single cold call measures ~100ms of one-time warmup noise, not steady-state
        // per-request cost, so we discard warmup iterations and measure only steady state.
        SearchV2TelemetryEvent lastEvent = null;
        int warmupIterations = 20;
        int measuredIterations = 30;
        double[] measuredMs = new double[measuredIterations];
        for (int i = 0; i < warmupIterations + measuredIterations; i++) {
            SearchV2TelemetryEventBuilder.Context ctx = new SearchV2TelemetryEventBuilder.Context(
                    System.currentTimeMillis(),
                    "req-bench-" + i,
                    "gdp united states",
                    plan,
                    candidates,
                    candidates,
                    decisions,
                    ranked,
                    ranked,
                    List.of(),
                    coverage,
                    false,
                    false,
                    "miss",
                    "miss",
                    "bypassed",
                    "deterministic_story",
                    Map.of("planner_ms", 800L, "retrieval_ms", 120L),
                    0.9,
                    "test-planner-model",
                    "test-reranker-model",
                    "v1",
                    "hash1",
                    "v1",
                    "hash2",
                    "2026-07-11",
                    null,
                    "no lexicon version",
                    "7",
                    Map.of("search_v2_telemetry_enabled", true),
                    ranked.size(),
                    ranked.size(),
                    0,
                    scorer,
                    conceptRegistry);
            long start = System.nanoTime();
            SearchV2TelemetryEvent event = SearchV2TelemetryEventBuilder.build(ctx);
            writer.submit(event);
            long elapsedNanos = System.nanoTime() - start;
            lastEvent = event;
            if (i >= warmupIterations) {
                measuredMs[i - warmupIterations] = elapsedNanos / 1_000_000.0;
            }
        }
        double totalMs = 0;
        double maxMs = 0;
        for (double value : measuredMs) {
            totalMs += value;
            maxMs = Math.max(maxMs, value);
        }
        double avgMs = totalMs / measuredIterations;
        System.out.printf(
                "SearchV2TelemetryLatencyBenchmarkTest measured: avg=%.4f ms, worst-of-%d=%.4f ms (warm, %d-candidate pool)%n",
                avgMs, measuredIterations, maxMs, REALISTIC_RERANK_POOL_SIZE);

        assertThat(lastEvent.candidates()).hasSize(REALISTIC_RERANK_POOL_SIZE);
        assertThat(writer.droppedCount()).isZero();
        assertThat(avgMs)
                .as(
                        "warm-state build+submit for a full %d-candidate rerank pool: avg=%.3f ms, worst-of-%d=%.3f ms",
                        REALISTIC_RERANK_POOL_SIZE,
                        avgMs,
                        measuredIterations,
                        maxMs)
                .isLessThan(5.0);
    }
}
