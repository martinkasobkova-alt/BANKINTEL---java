package cz.bankintel.search.v2.observability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned, structured record of one Search V2 request, emitted only when
 * {@code SEARCH_V2_TELEMETRY_ENABLED} is on. This is pure observability data: building or emitting
 * this event must never influence ranking, retrieval, or any other pipeline decision.
 *
 * <p>Every timing/nullable field uses boxed types so an absent measurement is {@code null}, never
 * {@code 0} — a step that did not run must not look like a step that ran in 0 ms.
 */
public record SearchV2TelemetryEvent(
        String schemaVersion,
        long timestampMs,
        String requestId,
        String normalizedQuery,
        String plannerPath,
        String plannerModel,
        String plannerPromptVersion,
        String plannerPromptHash,
        String rerankerModel,
        String rerankerPromptVersion,
        String rerankerPromptHash,
        String ontologyVersion,
        String lexiconVersion,
        String lexiconVersionAbsentReason,
        String sidecarIndexVersion,
        Map<String, Boolean> featureFlags,
        Double exactEntityConfidence,
        String resolvedEntityId,
        Double conceptConfidence,
        List<String> resolvedConcepts,
        List<String> detectedGeographies,
        Map<String, Object> sourceRouting,
        List<Map<String, Object>> queryVariants,
        String cachePlanStatus,
        String cacheRetrievalStatus,
        String cacheFinalStatus,
        String coverageStatus,
        Boolean retrievalDegraded,
        Boolean retryUsed,
        Integer totalResultCount,
        Integer verifiedResultCount,
        Integer possibleResultCount,
        String finalResponsePath,
        TimingTelemetry timings,
        List<CandidateTelemetry> candidates) {

    public static final String SCHEMA_VERSION = "1";

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", schemaVersion);
        out.put("timestamp_ms", timestampMs);
        out.put("request_id", requestId);
        out.put("normalized_query", normalizedQuery);
        out.put("planner_path", plannerPath);
        out.put("planner_model", plannerModel);
        out.put("planner_prompt_version", plannerPromptVersion);
        out.put("planner_prompt_hash", plannerPromptHash);
        out.put("reranker_model", rerankerModel);
        out.put("reranker_prompt_version", rerankerPromptVersion);
        out.put("reranker_prompt_hash", rerankerPromptHash);
        out.put("ontology_version", ontologyVersion);
        out.put("lexicon_version", lexiconVersion);
        out.put("lexicon_version_absent_reason", lexiconVersionAbsentReason);
        out.put("sidecar_index_version", sidecarIndexVersion);
        out.put("feature_flags", featureFlags);
        out.put("exact_entity_confidence", exactEntityConfidence);
        out.put("resolved_entity_id", resolvedEntityId);
        out.put("concept_confidence", conceptConfidence);
        out.put("resolved_concepts", resolvedConcepts);
        out.put("detected_geographies", detectedGeographies);
        out.put("source_routing", sourceRouting);
        out.put("query_variants", queryVariants);
        out.put("cache_plan_status", cachePlanStatus);
        out.put("cache_retrieval_status", cacheRetrievalStatus);
        out.put("cache_final_status", cacheFinalStatus);
        out.put("coverage_status", coverageStatus);
        out.put("retrieval_degraded", retrievalDegraded);
        out.put("retry_used", retryUsed);
        out.put("total_result_count", totalResultCount);
        out.put("verified_result_count", verifiedResultCount);
        out.put("possible_result_count", possibleResultCount);
        out.put("final_response_path", finalResponsePath);
        out.put("timings", timings == null ? Map.of() : timings.toMap());
        out.put("candidates", candidates == null ? List.of() : candidates.stream().map(CandidateTelemetry::toMap).toList());
        return out;
    }

    /**
     * Each field is boxed {@link Long} so a phase that did not run is {@code null}/absent in the
     * serialized JSON, never {@code 0}.
     */
    public record TimingTelemetry(
            Long plannerMs,
            Long queryExpansionMs,
            Long retrievalMs,
            Long rerankerMs,
            Long coverageMs,
            Long retryMs,
            Long previewVerificationMs,
            Long storyGenerationMs,
            Long totalMs) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("planner_ms", plannerMs);
            out.put("query_expansion_ms", queryExpansionMs);
            out.put("retrieval_ms", retrievalMs);
            out.put("reranker_ms", rerankerMs);
            out.put("coverage_ms", coverageMs);
            out.put("retry_ms", retryMs);
            out.put("preview_verification_ms", previewVerificationMs);
            out.put("story_generation_ms", storyGenerationMs);
            out.put("total_ms", totalMs);
            return out;
        }
    }

    public record CandidateTelemetry(
            String source,
            String seriesId,
            String title,
            Double sparseScore,
            Integer sparseRank,
            Double vectorScore,
            Integer vectorRank,
            String retrievalLane,
            Double exactEntityScore,
            Boolean conceptMatchEvidence,
            String geoCompatibilityStatus,
            Boolean geoHardConflict,
            String sectorCompatibility,
            String sectorCompatibilityAbsentReason,
            Double metadataQuality,
            String lifecycleStatus,
            String llmDecision,
            Double llmRelevanceScore,
            Double llmConfidence,
            String llmReason,
            List<String> llmSemanticConflicts,
            Integer rankBeforeRerank,
            Integer rankAfterRerank,
            Integer finalRank,
            String previewStatus,
            String previewFailureReason,
            Boolean returnedToUser,
            String resultRole) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", source);
            out.put("series_id", seriesId);
            out.put("title", title);
            out.put("sparse_score", sparseScore);
            out.put("sparse_rank", sparseRank);
            out.put("vector_score", vectorScore);
            out.put("vector_rank", vectorRank);
            out.put("retrieval_lane", retrievalLane);
            out.put("exact_entity_score", exactEntityScore);
            out.put("concept_match_evidence", conceptMatchEvidence);
            out.put("geo_compatibility_status", geoCompatibilityStatus);
            out.put("geo_hard_conflict", geoHardConflict);
            out.put("sector_compatibility", sectorCompatibility);
            out.put("sector_compatibility_absent_reason", sectorCompatibilityAbsentReason);
            out.put("metadata_quality", metadataQuality);
            out.put("lifecycle_status", lifecycleStatus);
            out.put("llm_decision", llmDecision);
            out.put("llm_relevance_score", llmRelevanceScore);
            out.put("llm_confidence", llmConfidence);
            out.put("llm_reason", llmReason);
            out.put("llm_semantic_conflicts", llmSemanticConflicts);
            out.put("rank_before_rerank", rankBeforeRerank);
            out.put("rank_after_rerank", rankAfterRerank);
            out.put("final_rank", finalRank);
            out.put("preview_status", previewStatus);
            out.put("preview_failure_reason", previewFailureReason);
            out.put("returned_to_user", returnedToUser);
            out.put("result_role", resultRole);
            return out;
        }
    }
}
