package cz.bankintel.search.v2.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.bankintel.search.v2.coverage.SearchV2CoverageChecker;
import cz.bankintel.search.v2.entity.SearchV2ExactEntityScorer;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SemanticDecision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2TelemetryEventBuilderTest {

    @Test
    void candidatesAreMatchedByTheirOwnSeriesIdNotByListPosition() {
        SearchCandidate ecbCandidate = candidate("ecb2", "cbd2-a", 5.0, Map.of());
        SearchCandidate fredCandidate = candidate(
                "fred", "gdp-b", 3.0, Map.of("_vector_score", 0.81, "_vector_rank", 2, "metadata_quality_score", 0.9,
                        "lifecycle_status", "current"));

        // Rerank pool is deliberately in the OPPOSITE order from the retrieval pool, and decisions are
        // supplied in yet another order, to prove matching happens by series_id, not by index.
        SemanticDecision dropForEcb = decision("cbd2-a", "drop", 0.1, "wrong entity");
        SemanticDecision keepForFred = decision("gdp-b", "keep", 0.95, "direct match");
        SearchResult fredRanked = new SearchResult(fredCandidate, keepForFred, 1);

        SearchV2ExactEntityScorer scorer = mock(SearchV2ExactEntityScorer.class);
        when(scorer.exactScore(any(), any())).thenReturn(0.0);
        SearchV2ConceptRegistry conceptRegistry = mock(SearchV2ConceptRegistry.class);

        SearchQueryPlan plan = plan(List.of());
        SearchV2CoverageChecker.CoverageResult coverage =
                new SearchV2CoverageChecker.CoverageResult("complete", List.of(), false, "ok");

        SearchV2TelemetryEventBuilder.Context ctx = new SearchV2TelemetryEventBuilder.Context(
                1_700_000_000_000L,
                "req-42",
                "gdp germany",
                plan,
                List.of(ecbCandidate, fredCandidate), // retrieval order: ecb first, fred second
                List.of(fredCandidate, ecbCandidate), // rerank pool order: fred first, ecb second
                List.of(dropForEcb, keepForFred),
                List.of(fredRanked),
                List.of(fredRanked),
                List.of(Map.of("source", "fred", "series_id", "gdp-b", "ok", true, "preview_state", "ok")),
                coverage,
                false,
                false,
                "hit",
                "miss",
                "bypassed",
                "deterministic_story",
                Map.of(),
                null,
                "test-planner-model",
                "test-reranker-model",
                "v1",
                "hash-planner",
                "v1",
                "hash-reranker",
                "2026-07-11",
                null,
                "no lexicon version",
                "7",
                Map.of("search_v2_telemetry_enabled", true),
                1,
                1,
                0,
                scorer,
                conceptRegistry);

        SearchV2TelemetryEvent event = SearchV2TelemetryEventBuilder.build(ctx);

        Map<String, SearchV2TelemetryEvent.CandidateTelemetry> bySeriesId = event.candidates().stream()
                .collect(java.util.stream.Collectors.toMap(SearchV2TelemetryEvent.CandidateTelemetry::seriesId, c -> c));

        SearchV2TelemetryEvent.CandidateTelemetry ecbTelemetry = bySeriesId.get("cbd2-a");
        assertThat(ecbTelemetry.source()).isEqualTo("ecb2");
        assertThat(ecbTelemetry.llmDecision()).isEqualTo("drop");
        assertThat(ecbTelemetry.sparseRank()).isEqualTo(1); // first in retrieval pool
        assertThat(ecbTelemetry.rankBeforeRerank()).isEqualTo(2); // second in rerank pool
        assertThat(ecbTelemetry.rankAfterRerank()).isNull(); // dropped, never ranked
        assertThat(ecbTelemetry.finalRank()).isNull();
        assertThat(ecbTelemetry.returnedToUser()).isFalse();
        assertThat(ecbTelemetry.previewStatus()).isNull(); // never sent to preview

        SearchV2TelemetryEvent.CandidateTelemetry fredTelemetry = bySeriesId.get("gdp-b");
        assertThat(fredTelemetry.source()).isEqualTo("fred");
        assertThat(fredTelemetry.llmDecision()).isEqualTo("keep");
        assertThat(fredTelemetry.sparseRank()).isEqualTo(2); // second in retrieval pool
        assertThat(fredTelemetry.rankBeforeRerank()).isEqualTo(1); // first in rerank pool
        assertThat(fredTelemetry.rankAfterRerank()).isEqualTo(1);
        assertThat(fredTelemetry.finalRank()).isEqualTo(1);
        assertThat(fredTelemetry.returnedToUser()).isTrue();
        // PR-7: canonical classification ("verified"), not the raw per-connector preview_state
        // string ("ok") - see SearchV2PreviewOutcome.
        assertThat(fredTelemetry.previewStatus()).isEqualTo(cz.bankintel.search.v2.schema.SearchV2PreviewOutcome.VERIFIED);
        assertThat(fredTelemetry.vectorScore()).isEqualTo(0.81);
        assertThat(fredTelemetry.vectorRank()).isEqualTo(2);
        assertThat(fredTelemetry.metadataQuality()).isEqualTo(0.9);
        assertThat(fredTelemetry.lifecycleStatus()).isEqualTo("current");
    }

    @Test
    void sectorCompatibilityIsAlwaysAbsentWithAnExplicitReason() {
        SearchCandidate onlyCandidate = candidate("ecb2", "cbd2-a", 1.0, Map.of());
        SearchV2TelemetryEvent event = SearchV2TelemetryEventBuilder.build(minimalContext(onlyCandidate));

        SearchV2TelemetryEvent.CandidateTelemetry telemetry = event.candidates().get(0);
        assertThat(telemetry.sectorCompatibility()).isNull();
        assertThat(telemetry.sectorCompatibilityAbsentReason())
                .isEqualTo(SearchV2TelemetryEventBuilder.SECTOR_COMPATIBILITY_ABSENT_REASON);
    }

    @Test
    void absentTimingPhasesAreNullNeverZero() {
        SearchCandidate onlyCandidate = candidate("ecb2", "cbd2-a", 1.0, Map.of());
        SearchV2TelemetryEventBuilder.Context base = minimalContext(onlyCandidate);
        SearchV2TelemetryEventBuilder.Context withPartialTimings = withTimings(
                base, Map.of("planner_ms", 120L, "reranker_ms", 340L));

        SearchV2TelemetryEvent event = SearchV2TelemetryEventBuilder.build(withPartialTimings);

        assertThat(event.timings().plannerMs()).isEqualTo(120L);
        assertThat(event.timings().rerankerMs()).isEqualTo(340L);
        assertThat(event.timings().retrievalMs()).isNull();
        assertThat(event.timings().coverageMs()).isNull();
        assertThat(event.timings().retryMs()).isNull();
        assertThat(event.timings().previewVerificationMs()).isNull();
        assertThat(event.timings().storyGenerationMs()).isNull();
        assertThat(event.timings().totalMs()).isNull();
        // Always null in PR-1 regardless of what is in the timings map — see the documented reason.
        assertThat(event.timings().queryExpansionMs()).isNull();
    }

    @Test
    void conceptMatchEvidenceIsNullRatherThanFalseWhenNoPrimaryConceptsExist() {
        SearchCandidate onlyCandidate = candidate("ecb2", "cbd2-a", 1.0, Map.of());
        SearchV2TelemetryEventBuilder.Context ctx = minimalContext(onlyCandidate); // plan has empty primaryConcepts

        SearchV2TelemetryEvent event = SearchV2TelemetryEventBuilder.build(ctx);

        assertThat(event.candidates().get(0).conceptMatchEvidence()).isNull();
    }

    private static SearchV2TelemetryEventBuilder.Context minimalContext(SearchCandidate candidate) {
        SearchV2ExactEntityScorer scorer = mock(SearchV2ExactEntityScorer.class);
        when(scorer.exactScore(any(), any())).thenReturn(0.0);
        SearchV2ConceptRegistry conceptRegistry = mock(SearchV2ConceptRegistry.class);
        when(conceptRegistry.candidateMatchesRequiredConcepts(anyString(), anyList())).thenReturn(false);
        SearchQueryPlan plan = plan(List.of());
        SearchV2CoverageChecker.CoverageResult coverage =
                new SearchV2CoverageChecker.CoverageResult("complete", List.of(), false, "ok");
        return new SearchV2TelemetryEventBuilder.Context(
                1_700_000_000_000L,
                "req-1",
                "test query",
                plan,
                List.of(candidate),
                List.of(candidate),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                coverage,
                false,
                false,
                "miss",
                "miss",
                "bypassed",
                "deterministic_story",
                Map.of(),
                null,
                "test-planner-model",
                "test-reranker-model",
                "v1",
                "hash-planner",
                "v1",
                "hash-reranker",
                "2026-07-11",
                null,
                "no lexicon version",
                "1",
                Map.of(),
                0,
                0,
                0,
                scorer,
                conceptRegistry);
    }

    private static SearchV2TelemetryEventBuilder.Context withTimings(
            SearchV2TelemetryEventBuilder.Context base, Map<String, Object> timings) {
        return new SearchV2TelemetryEventBuilder.Context(
                base.timestampMs(),
                base.requestId(),
                base.rawQuery(),
                base.plan(),
                base.retrievalCandidates(),
                base.rerankPool(),
                base.semanticDecisions(),
                base.ranked(),
                base.finalResults(),
                base.previewStatuses(),
                base.coverage(),
                base.retrievalDegraded(),
                base.retried(),
                base.cachePlanStatus(),
                base.cacheRetrievalStatus(),
                base.cacheFinalStatus(),
                base.finalResponsePath(),
                timings,
                base.conceptConfidence(),
                base.plannerModel(),
                base.rerankerModel(),
                base.plannerPromptVersion(),
                base.plannerPromptHash(),
                base.rerankerPromptVersion(),
                base.rerankerPromptHash(),
                base.ontologyVersion(),
                base.lexiconVersion(),
                base.lexiconVersionAbsentReason(),
                base.sidecarIndexVersion(),
                base.featureFlags(),
                base.totalResultCount(),
                base.verifiedResultCount(),
                base.possibleResultCount(),
                base.exactEntityScorer(),
                base.conceptRegistry());
    }

    private static SearchCandidate candidate(String source, String seriesId, double ftsScore, Map<String, Object> raw) {
        return new SearchCandidate(
                source + ":" + seriesId,
                seriesId,
                "Title " + seriesId,
                "",
                source,
                "dataset-" + seriesId,
                "",
                "A",
                "PC",
                "",
                List.of(),
                List.of(),
                List.of(),
                "",
                ftsScore,
                "gdp germany",
                List.of(),
                raw);
    }

    private static SemanticDecision decision(String seriesId, String decisionValue, double score, String reason) {
        return new SemanticDecision(seriesId, decisionValue, score, 0.9, List.of(), List.of(), reason, "primary");
    }

    private static SearchQueryPlan plan(List<String> primaryConcepts) {
        return new SearchQueryPlan(
                "gdp germany",
                "en",
                "find_series",
                primaryConcepts,
                List.of(),
                List.of("DE"),
                List.of(),
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
                "openai",
                "test-planner-model");
    }
}
