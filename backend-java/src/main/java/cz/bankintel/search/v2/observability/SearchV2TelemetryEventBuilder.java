package cz.bankintel.search.v2.observability;

import cz.bankintel.search.v2.coverage.SearchV2CoverageChecker;
import cz.bankintel.search.v2.entity.SearchV2ExactEntityScorer;
import cz.bankintel.search.v2.geo.SearchV2GeoCompatibility;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SearchQueryVariant;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SearchV2PreviewOutcome;
import cz.bankintel.search.v2.schema.SemanticDecision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure, side-effect-free assembly of a {@link SearchV2TelemetryEvent} from already-computed pipeline
 * state. Building an event must never itself change any ranking/routing decision — every value here
 * is either read directly from existing candidate/plan/decision objects, or recomputed via the SAME
 * read-only scorers already used elsewhere in the pipeline (never a new algorithm).
 *
 * <p>Some requested fields cannot be populated without changing decision-making code in other
 * classes (see {@link Context} javadoc for the exact list and reasons); those stay {@code null} with
 * an explicit "*_absent_reason" companion field, per the PR-1 instructions.
 */
public final class SearchV2TelemetryEventBuilder {

    private SearchV2TelemetryEventBuilder() {}

    public static final String SECTOR_COMPATIBILITY_ABSENT_REASON =
            "Sector compatibility is computed inside SearchV2SemanticValidator's private "
                    + "TargetProfile/CandidateProfile machinery and is not exposed as an independently "
                    + "callable signal; extracting it would require modifying reranker internals, which is "
                    + "out of scope for PR-1 (\"Neměň LLM planner/reranker logiku\").";

    public static final String QUERY_EXPANSION_TIMING_ABSENT_REASON =
            "Query expansion runs inside SearchV2FtsRetriever.retrieve() as an internal first step and "
                    + "is not separately timed today; separating it would require touching the retrieval "
                    + "class, which is out of scope for PR-1.";

    /** Minimal event for the outer search() catch-all error path, where most pipeline state never got built. */
    public static SearchV2TelemetryEvent buildError(String requestId, String query, String errorMessage) {
        return new SearchV2TelemetryEvent(
                SearchV2TelemetryEvent.SCHEMA_VERSION,
                System.currentTimeMillis(),
                requestId,
                SearchV2TelemetrySanitizer.sanitizeText(query, SearchV2TelemetrySanitizer.DEFAULT_QUERY_MAX_LENGTH),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "error",
                new SearchV2TelemetryEvent.TimingTelemetry(null, null, null, null, null, null, null, null, null),
                List.of());
    }

    public static SearchV2TelemetryEvent build(Context ctx) {
        Map<String, SemanticDecision> decisionsBySeriesId = new LinkedHashMap<>();
        for (SemanticDecision decision : ctx.semanticDecisions == null ? List.<SemanticDecision>of() : ctx.semanticDecisions) {
            decisionsBySeriesId.putIfAbsent(decision.seriesId(), decision);
        }
        Map<String, Integer> rankAfterRerankByCandidateId = rankByCandidateId(ctx.ranked);
        Map<String, Integer> finalRankByCandidateId = rankByCandidateId(ctx.finalResults);
        Map<String, String> roleByCandidateId = roleByCandidateId(ctx.finalResults, ctx.ranked);
        Map<String, Map<String, Object>> previewByKey = previewStatusesByKey(ctx.previewStatuses);
        Map<String, Integer> sparseRankByCandidateId = new LinkedHashMap<>();
        int sparseRank = 1;
        for (SearchCandidate candidate : ctx.retrievalCandidates == null ? List.<SearchCandidate>of() : ctx.retrievalCandidates) {
            sparseRankByCandidateId.putIfAbsent(candidate.candidateId(), sparseRank++);
        }

        // Perf fix: resolved exactly once for this event, not once per candidate in the loop below -
        // same reasoning as SearchV2Service#selectRerankPool (see that method's comment and
        // SearchV2ConceptRegistry#resolveRequirement's javadoc). Behavior is unchanged: candidates get
        // exactly the same conceptMatchEvidence value either way.
        SearchV2ConceptRegistry.ResolvedConceptRequirement conceptRequirement =
                ctx.conceptRegistry.resolveRequirement(ctx.plan == null ? null : ctx.plan.primaryConcepts());
        List<SearchV2TelemetryEvent.CandidateTelemetry> candidateEvents = new ArrayList<>();
        int rankBeforeRerank = 1;
        for (SearchCandidate candidate : ctx.rerankPool == null ? List.<SearchCandidate>of() : ctx.rerankPool) {
            candidateEvents.add(buildCandidate(
                    candidate,
                    rankBeforeRerank++,
                    ctx,
                    conceptRequirement,
                    decisionsBySeriesId.get(candidate.seriesId()),
                    sparseRankByCandidateId.get(candidate.candidateId()),
                    rankAfterRerankByCandidateId.get(candidate.candidateId()),
                    finalRankByCandidateId.get(candidate.candidateId()),
                    roleByCandidateId.get(candidate.candidateId()),
                    previewByKey.get(previewKey(candidate.source(), candidate.seriesId()))));
        }

        return new SearchV2TelemetryEvent(
                SearchV2TelemetryEvent.SCHEMA_VERSION,
                ctx.timestampMs,
                ctx.requestId,
                SearchV2TelemetrySanitizer.sanitizeText(ctx.rawQuery, SearchV2TelemetrySanitizer.DEFAULT_QUERY_MAX_LENGTH),
                ctx.plan == null ? null : ctx.plan.plannerStatus(),
                ctx.plannerModel,
                ctx.plannerPromptVersion,
                ctx.plannerPromptHash,
                ctx.rerankerModel,
                ctx.rerankerPromptVersion,
                ctx.rerankerPromptHash,
                ctx.ontologyVersion,
                ctx.lexiconVersion,
                ctx.lexiconVersionAbsentReason,
                ctx.sidecarIndexVersion,
                ctx.featureFlags == null ? Map.of() : Map.copyOf(ctx.featureFlags),
                ctx.plan == null || ctx.plan.entityResolution() == null ? null : ctx.plan.entityResolution().confidence(),
                ctx.plan == null || ctx.plan.entityResolution() == null
                        ? null
                        : blankToNull(ctx.plan.entityResolution().canonicalName()),
                ctx.conceptConfidence,
                ctx.plan == null
                        ? List.of()
                        : SearchV2TelemetrySanitizer.sanitizeList(
                                ctx.plan.primaryConcepts(),
                                SearchV2TelemetrySanitizer.DEFAULT_LIST_MAX_SIZE,
                                SearchV2TelemetrySanitizer.DEFAULT_SHORT_TEXT_MAX_LENGTH),
                ctx.plan == null
                        ? List.of()
                        : SearchV2TelemetrySanitizer.sanitizeList(
                                ctx.plan.geographies(),
                                SearchV2TelemetrySanitizer.DEFAULT_LIST_MAX_SIZE,
                                32),
                ctx.plan == null || ctx.plan.sourceRouting() == null ? Map.of() : ctx.plan.sourceRouting().toMap(),
                ctx.plan == null || ctx.plan.queryVariants() == null
                        ? List.of()
                        : ctx.plan.queryVariants().stream()
                                .limit(SearchV2TelemetrySanitizer.DEFAULT_LIST_MAX_SIZE)
                                .map(SearchQueryVariant::toMap)
                                .toList(),
                ctx.cachePlanStatus,
                ctx.cacheRetrievalStatus,
                ctx.cacheFinalStatus,
                ctx.coverage == null ? null : ctx.coverage.status(),
                ctx.retrievalDegraded,
                ctx.retried,
                ctx.totalResultCount,
                ctx.verifiedResultCount,
                ctx.possibleResultCount,
                ctx.finalResponsePath,
                buildTimings(ctx.timingsSnapshot),
                candidateEvents);
    }

    private static SearchV2TelemetryEvent.CandidateTelemetry buildCandidate(
            SearchCandidate candidate,
            int rankBeforeRerank,
            Context ctx,
            SearchV2ConceptRegistry.ResolvedConceptRequirement conceptRequirement,
            SemanticDecision decision,
            Integer sparseRank,
            Integer rankAfterRerank,
            Integer finalRank,
            String resultRole,
            Map<String, Object> previewStatus) {
        Map<String, Object> raw = candidate.raw() == null ? Map.of() : candidate.raw();
        SearchV2GeoCompatibility.GeoAssessment geo = SearchV2GeoCompatibility.assessCandidateGeo(
                candidate, ctx.plan == null ? List.of() : ctx.plan.geographies(), ctx.plan);
        Boolean conceptMatchEvidence = null;
        if (ctx.plan != null && ctx.plan.primaryConcepts() != null && !ctx.plan.primaryConcepts().isEmpty()) {
            conceptMatchEvidence = ctx.conceptRegistry.candidateMatchesRequiredConcepts(
                    candidateConceptEvidenceText(candidate), conceptRequirement);
        }
        Double exactEntityScore = ctx.plan == null || ctx.plan.entityResolution() == null
                ? null
                : ctx.exactEntityScorer.exactScore(ctx.plan.entityResolution(), candidate);

        return new SearchV2TelemetryEvent.CandidateTelemetry(
                candidate.source(),
                candidate.seriesId(),
                SearchV2TelemetrySanitizer.sanitizeTextOrNull(
                        candidate.title(), SearchV2TelemetrySanitizer.DEFAULT_TEXT_MAX_LENGTH),
                candidate.ftsScore(),
                sparseRank,
                toDouble(raw.get("_vector_score")),
                toInteger(raw.get("_vector_rank")),
                blankToNull(String.valueOf(raw.getOrDefault("_retrieval_lanes", ""))),
                exactEntityScore,
                conceptMatchEvidence,
                geo.status(),
                geo.hardConflict(),
                null,
                SECTOR_COMPATIBILITY_ABSENT_REASON,
                toDouble(raw.get("metadata_quality_score")),
                blankToNull(String.valueOf(raw.getOrDefault("lifecycle_status", ""))),
                decision == null ? null : decision.decision(),
                decision == null ? null : decision.relevanceScore(),
                decision == null ? null : decision.confidence(),
                decision == null
                        ? null
                        : SearchV2TelemetrySanitizer.sanitizeTextOrNull(
                                decision.reason(), SearchV2TelemetrySanitizer.DEFAULT_SHORT_TEXT_MAX_LENGTH),
                decision == null
                        ? List.of()
                        : SearchV2TelemetrySanitizer.sanitizeList(
                                decision.semanticConflicts(),
                                SearchV2TelemetrySanitizer.DEFAULT_LIST_MAX_SIZE,
                                SearchV2TelemetrySanitizer.DEFAULT_SHORT_TEXT_MAX_LENGTH),
                rankBeforeRerank,
                rankAfterRerank,
                finalRank,
                // PR-7: canonical 5-state classification, not the raw (sometimes absent, e.g. on a
                // technical exception before that fix) preview_state string. `null` is preserved for
                // "never sent to preview at all" - a distinct case from any of the 5 classified outcomes.
                previewStatus == null ? null : SearchV2PreviewOutcome.classify(previewStatus),
                previewStatus == null || Boolean.TRUE.equals(previewStatus.get("ok"))
                        ? null
                        : SearchV2TelemetrySanitizer.sanitizeTextOrNull(
                                String.valueOf(previewStatus.get("reason")),
                                SearchV2TelemetrySanitizer.DEFAULT_SHORT_TEXT_MAX_LENGTH),
                finalRank != null,
                resultRole);
    }

    /**
     * Minimal, read-only text join used only to feed {@link SearchV2ConceptRegistry#candidateMatchesRequiredConcepts}
     * for telemetry purposes. Mirrors (does not replace) the private helper of the same intent already
     * used by the live pipeline in SearchV2Service; duplicated here rather than exposing that private
     * method, to avoid touching orchestration internals for an observability-only need.
     */
    private static String candidateConceptEvidenceText(SearchCandidate candidate) {
        Map<String, Object> raw = candidate.raw() == null ? Map.of() : candidate.raw();
        return String.join(
                " ",
                safe(candidate.title()),
                safe(candidate.description()),
                safe(candidate.dataset()),
                String.join(" ", candidate.concepts() == null ? List.of() : candidate.concepts()),
                safe(String.valueOf(raw.getOrDefault("primary_concept", ""))),
                safe(String.valueOf(raw.getOrDefault("canonical_title_cs", ""))),
                safe(String.valueOf(raw.getOrDefault("canonical_title_en", ""))),
                safe(String.valueOf(raw.getOrDefault("original_title", ""))));
    }

    private static SearchV2TelemetryEvent.TimingTelemetry buildTimings(Map<String, Object> timings) {
        Map<String, Object> snapshot = timings == null ? Map.of() : timings;
        return new SearchV2TelemetryEvent.TimingTelemetry(
                toLong(snapshot.get("planner_ms")),
                null, // see QUERY_EXPANSION_TIMING_ABSENT_REASON
                toLong(snapshot.get("retrieval_ms")),
                toLong(snapshot.get("reranker_ms")),
                toLong(snapshot.get("coverage_ms")),
                toLong(snapshot.get("retry_ms")),
                toLong(snapshot.get("preview_verification_ms")),
                toLong(snapshot.get("answer_ms")),
                toLong(snapshot.get("total_pipeline_ms")));
    }

    private static Map<String, Integer> rankByCandidateId(List<SearchResult> results) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (SearchResult result : results == null ? List.<SearchResult>of() : results) {
            out.putIfAbsent(result.candidate().candidateId(), result.rank());
        }
        return out;
    }

    private static Map<String, String> roleByCandidateId(List<SearchResult> finalResults, List<SearchResult> ranked) {
        Map<String, String> out = new LinkedHashMap<>();
        for (SearchResult result : ranked == null ? List.<SearchResult>of() : ranked) {
            out.putIfAbsent(result.candidate().candidateId(), result.role());
        }
        for (SearchResult result : finalResults == null ? List.<SearchResult>of() : finalResults) {
            out.put(result.candidate().candidateId(), result.role());
        }
        return out;
    }

    private static Map<String, Map<String, Object>> previewStatusesByKey(List<Map<String, Object>> statuses) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> status : statuses == null ? List.<Map<String, Object>>of() : statuses) {
            String key = previewKey(String.valueOf(status.get("source")), String.valueOf(status.get("series_id")));
            if (Boolean.TRUE.equals(status.get("ok")) || !out.containsKey(key)) {
                out.put(key, status);
            }
        }
        return out;
    }

    private static String previewKey(String source, String seriesId) {
        return (source == null ? "" : source).toLowerCase(Locale.ROOT)
                + "::"
                + (seriesId == null ? "" : seriesId).toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "null".equals(value) ? null : value;
    }

    private static Double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer toInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    /**
     * All inputs are values already computed by {@code SearchV2Service.runSearch(...)}; this record
     * only carries them into the builder. Fields with no live source are documented at their
     * declaration site (see the class javadoc constants) rather than silently invented here.
     */
    public record Context(
            long timestampMs,
            String requestId,
            String rawQuery,
            SearchQueryPlan plan,
            List<SearchCandidate> retrievalCandidates,
            List<SearchCandidate> rerankPool,
            List<SemanticDecision> semanticDecisions,
            List<SearchResult> ranked,
            List<SearchResult> finalResults,
            List<Map<String, Object>> previewStatuses,
            SearchV2CoverageChecker.CoverageResult coverage,
            Boolean retrievalDegraded,
            Boolean retried,
            String cachePlanStatus,
            String cacheRetrievalStatus,
            String cacheFinalStatus,
            String finalResponsePath,
            Map<String, Object> timingsSnapshot,
            Double conceptConfidence,
            String plannerModel,
            String rerankerModel,
            String plannerPromptVersion,
            String plannerPromptHash,
            String rerankerPromptVersion,
            String rerankerPromptHash,
            String ontologyVersion,
            String lexiconVersion,
            String lexiconVersionAbsentReason,
            String sidecarIndexVersion,
            Map<String, Boolean> featureFlags,
            Integer totalResultCount,
            Integer verifiedResultCount,
            Integer possibleResultCount,
            SearchV2ExactEntityScorer exactEntityScorer,
            SearchV2ConceptRegistry conceptRegistry) {}
}
