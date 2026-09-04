package cz.bankintel.search.v2.orchestration;

import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.CatalogSearchAnswerService;
import cz.bankintel.search.CatalogSourceRegistry;
import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.v2.coverage.SearchV2CoverageChecker;
import cz.bankintel.search.v2.coverage.SearchV2RetryPlanner;
import cz.bankintel.search.v2.entity.SearchV2ExactEntityScorer;
import cz.bankintel.search.v2.geo.SearchV2GeoCompatibility;
import cz.bankintel.search.v2.normalization.SearchResultCanonicalMetadataService;
import cz.bankintel.search.v2.observability.SearchV2Trace;
import cz.bankintel.search.v2.observability.SearchV2TraceStore;
import cz.bankintel.search.v2.observability.SearchV2TelemetryEventBuilder;
import cz.bankintel.search.v2.observability.SearchV2TelemetryWriter;
import cz.bankintel.search.v2.ontology.SearchV2ConceptOntology;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.planner.SearchV2QueryPlanner;
import cz.bankintel.search.v2.reranking.SearchV2BatchReranker;
import cz.bankintel.search.v2.reranking.SearchV2FinalReranker;
import cz.bankintel.search.v2.reranking.SearchV2SemanticValidator;
import cz.bankintel.search.v2.retrieval.SearchV2CandidateMerger;
import cz.bankintel.search.v2.retrieval.SearchV2FtsRetriever;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SearchV2PreviewOutcome;
import cz.bankintel.search.v2.schema.SemanticDecision;
import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarIndex;
import cz.bankintel.search.v2.sidecar.SearchSeriesLifecycleClassifier;
import cz.bankintel.service.research.WebResearchService;
import cz.bankintel.util.BankIntelEnvVars;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SearchV2Service {

    private static final Logger log = LoggerFactory.getLogger(SearchV2Service.class);
    // Plan skladá jazykový model (SearchV2QueryPlanner); i s pevným seedem a teplotou 0 OpenAI
    // nezaručuje bitově identický výstup napříč voláními, takže po vypršení cache mohl stejný
    // dotaz vrátit jinou sadu výsledků - v praxi kdykoli po hodině neaktivity. Klíč teď nese i
    // catalogVersion (viz plan(...)), takže reindex plán zneplatní sám; čas je jen záložní limit,
    // proto může být dlouhý.
    private static final Duration PLAN_TTL = Duration.ofDays(7);
    private static final Duration RETRIEVAL_TTL = Duration.ofMinutes(30);
    private static final Duration FINAL_TTL = Duration.ofMinutes(10);
    private static final String RETRIEVAL_CACHE_SCHEMA = "source-routing-v2";
    private static final int DEFAULT_LIMIT = 12;
    private static final int MAX_LIMIT = 30;
    private static final int MAX_PREVIEW_VERIFY = 8;
    /**
     * Raised 60 -> 240 (2026-08-01, effectively unbounded - retrieval itself never merges more than
     * 240 candidates) after "eurusd" surfaced a case where ECB's own official EUR/USD reference rate
     * never reached the AI reranker at all: {@code selectRerankPool}'s cheap pre-filter (concept
     * match / strong title evidence / natural retrieval order) cut the pool to 60 before the smarter
     * reranker ever saw it, the same shallow-token-overlap failure mode diagnosed for the reranker
     * default (see {@link #resolveUseAiReranker}). A/B measured on 10 diverse queries (FX, commodity,
     * bank, macro, stock): +400ms reranker_ms / +130ms total_ms on average - negligible, since
     * {@code SearchV2SemanticValidator} batches run concurrently on virtual threads, so wall time is
     * bounded by the slowest single batch, not the candidate count. Confirmed fix: eurusd's top
     * result changed from 0 verified to the correct {@code ecb2:EXR/*} rate.
     */
    private static final int MAX_RERANK_CANDIDATES = 240;

    private static int maxRerankCandidates() {
        String raw = BankIntelEnvVars.get("SEARCH_V2_MAX_RERANK_CANDIDATES");
        if (raw.isBlank()) {
            return MAX_RERANK_CANDIDATES;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : MAX_RERANK_CANDIDATES;
        } catch (NumberFormatException ex) {
            return MAX_RERANK_CANDIDATES;
        }
    }
    private static final Pattern PERIOD_YEAR_PATTERN = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)");
    private static final List<String> PERIOD_FIELDS = List.of(
            "date", "DATE", "time_period", "TIME_PERIOD", "period", "PERIOD", "time", "TIME");
    private static final List<String> VALUE_FIELDS = List.of(
            "value", "VALUE", "obs_value", "OBS_VALUE", "amount", "AMOUNT");

    private final SearchV2QueryPlanner planner;
    private final SearchV2FtsRetriever retriever;
    private final SearchV2CandidateMerger candidateMerger;
    private final SearchV2BatchReranker batchReranker;
    private final SearchV2FinalReranker finalReranker;
    private final SearchV2CoverageChecker coverageChecker;
    private final SearchV2RetryPlanner retryPlanner;
    private final SearchV2PreviewVerifier previewVerifier;
    private final SearchV2TraceStore traceStore;
    private final SearchV2CacheService cacheService;
    private final CatalogIndexStore indexStore;
    private final SearchCatalogSidecarIndex sidecarIndex;
    private final SearchV2ExactEntityScorer exactEntityScorer;
    private final SearchV2ConceptRegistry conceptRegistry;
    private final CatalogSearchAnswerService searchAnswerService;
    private final SearchV2ConceptOntology conceptOntology;
    private final SearchV2TelemetryWriter telemetryWriter;
    private final SearchV2InstitutionalSectorRegistry institutionalSectorRegistry;
    private final SearchResultCanonicalMetadataService canonicalMetadataService;
    private final WebResearchService webResearchService;

    // --- Search V2 telemetry: dedicated bounded executor, off the request-handling path ------------
    // Fáze 2 (perf investigation): emitSearchTelemetry's per-candidate cost (concept-match/geo/exact-
    // entity re-evaluation inside SearchV2TelemetryEventBuilder) used to run synchronously between
    // "total_pipeline_ms" being recorded and the response actually being returned - the client waited
    // for it even though it never affects the response body. This executor takes over exactly that
    // work; the request thread only ever does the (cheap) trace snapshot + submit.
    private static final int TELEMETRY_EXECUTOR_THREAD_COUNT = 2;
    private static final int TELEMETRY_QUEUE_CAPACITY = 500;
    private static final long TELEMETRY_DROP_WARNING_INTERVAL_MS = 30_000;

    private final AtomicLong telemetrySubmittedCount = new AtomicLong();
    private final AtomicLong telemetryDroppedCount = new AtomicLong();
    private final AtomicLong telemetryFailedCount = new AtomicLong();
    private final AtomicLong telemetryLastDropWarningAtMs = new AtomicLong();
    private final ThreadPoolExecutor telemetryExecutor = new ThreadPoolExecutor(
            TELEMETRY_EXECUTOR_THREAD_COUNT,
            TELEMETRY_EXECUTOR_THREAD_COUNT,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(TELEMETRY_QUEUE_CAPACITY),
            new ThreadFactory() {
                private final AtomicInteger sequence = new AtomicInteger();

                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "search-v2-telemetry-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            },
            (task, executor) -> {
                // Never CallerRunsPolicy (would run telemetry on the request thread) and never a
                // policy that throws (would surface as a search-endpoint exception) - drop the event
                // and count it. A full queue means the writer/consumer side is behind, not that the
                // request did anything wrong.
                long dropped = telemetryDroppedCount.incrementAndGet();
                long now = System.currentTimeMillis();
                long lastWarn = telemetryLastDropWarningAtMs.get();
                if (now - lastWarn >= TELEMETRY_DROP_WARNING_INTERVAL_MS
                        && telemetryLastDropWarningAtMs.compareAndSet(lastWarn, now)) {
                    log.warn(
                            "search v2 telemetry: queue full, dropped {} event(s) so far (rate-limited warning, "
                                    + "next repeat in {}ms)",
                            dropped,
                            TELEMETRY_DROP_WARNING_INTERVAL_MS);
                }
            });

    @PreDestroy
    void shutdownTelemetryExecutor() {
        telemetryExecutor.shutdown();
        try {
            if (!telemetryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                telemetryExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            telemetryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @PostConstruct
    void logSearchV2Configuration() {
        Map<String, Object> limits = candidateLimits();
        log.info(
                "Search V2 configuration: catalog index={}, semantic retrieval={}, LLM reranking={}, preview mode={}, candidate limits={}",
                sidecarIndex.configuredMode(Map.of()),
                semanticRetrievalEnabled() ? "enabled" : "disabled",
                "request/planner gated",
                "request-controlled(default=full, top_n=" + configuredPreviewTopN() + ")",
                limits);
    }

    public Map<String, Object> search(Map<String, Object> request) {
        Map<String, Object> payload = request != null ? request : Map.of();
        String query = CatalogMapSupport.firstNonBlank(payload, "q", "query");
        SearchV2Trace trace = new SearchV2Trace(query);
        String catalogVersion = indexStore.catalogVersion();
        String cacheIndexMode = sidecarIndex.configuredMode(payload);
        String finalCacheKey = finalCacheKey(
                payload, query, catalogVersion, cacheIndexMode, sidecarIndex.contentRevision());
        if (!finalCacheKey.isBlank() && !truthy(payload.get("debug")) && !truthy(payload.get("no_cache"))) {
            Object cached = cacheService.get(finalCacheKey).orElse(null);
            if (cached instanceof Map<?, ?> map) {
                Map<String, Object> cachedResult = CatalogMapSupport.castMap(map);
                cachedResult.put("cache_hit", true);
                telemetryWriter.recordFinalCacheHit();
                return cachedResult;
            }
        }
        try {
            Map<String, Object> result = runSearch(payload, query, catalogVersion, trace);
            traceStore.save(trace);
            if (!finalCacheKey.isBlank() && !truthy(result.get("retrieval_degraded"))) {
                cacheService.getOrCompute(finalCacheKey, FINAL_TTL, () -> result);
            }
            return result;
        } catch (Exception ex) {
            trace.event("error", ex.getMessage());
            Map<String, Object> error = baseResponse(query, null, trace, catalogVersion);
            error.put("ok", false);
            error.put("status", "error");
            error.put("error", ex.getMessage());
            traceStore.save(trace);
            emitErrorTelemetry(query, trace, ex);
            return error;
        }
    }

    /**
     * Emits a minimal telemetry event for the outer catch-all error path, where most pipeline state
     * (plan, candidates, coverage...) never got built. Never allowed to affect the error response
     * itself — any failure here is only logged.
     */
    private void emitErrorTelemetry(String query, SearchV2Trace trace, Exception ex) {
        if (!telemetryWriter.enabled()) {
            return;
        }
        try {
            telemetryWriter.submit(SearchV2TelemetryEventBuilder.buildError(trace.traceId(), query, ex.getMessage()));
        } catch (Exception telemetryEx) {
            log.warn(
                    "search v2 telemetry: failed to build/submit error event for trace_id={}: {}",
                    trace.traceId(),
                    telemetryEx.getMessage());
        }
    }

    /**
     * Attaches the classic-search web-research fallback contract to a v2 response. Mirrors the v1
     * engine (CatalogDeepSearchService#applyWebFallback): the three {@code web_*} fields are always
     * present (stable contract shared by both engines) and the web call only fires on {@code
     * no_valid_result}. A failure never fails the search - it just leaves {@code
     * web_research_status="failed"}. Web findings are context, kept out of {@code verified}/{@code
     * possible}; the UI renders them in their own section.
     */
    private void applyWebFallback(
            Map<String, Object> response, String query, boolean noValidResult, List<String> allowedSources) {
        response.put("web_sources", List.of());
        response.put("web_sources_total", 0);
        response.put("web_research_status", "not_attempted");
        if (!noValidResult) {
            return;
        }
        try {
            Map<String, Object> searchContext = new LinkedHashMap<>();
            searchContext.put("sources", allowedSources == null ? List.of() : allowedSources);
            Map<String, Object> webResult = webResearchService.researchCatalogFallback(query, searchContext);
            List<Map<String, Object>> findings =
                    webResult == null ? List.of() : castWebFindings(webResult.get("findings"));
            response.put("web_sources", findings);
            response.put("web_sources_total", findings.size());
            response.put("web_research_status", findings.isEmpty() ? "empty" : "found");
        } catch (Exception ex) {
            log.warn("search v2 web-research fallback failed query='{}': {}", query, ex.getMessage());
            response.put("web_research_status", "failed");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castWebFindings(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private Map<String, Object> runSearch(
            Map<String, Object> payload, String query, String catalogVersion, SearchV2Trace trace) {
        long pipelineStart = System.currentTimeMillis();
        long planStart = System.currentTimeMillis();
        boolean noCache = truthy(payload.get("no_cache"));
        // use_ai_planner / use_ai_reranker are independent knobs (AI reranker A/B experiment
        // concluded NO-GO for running the reranker by default: median +8.2s latency for a mixed-to-
        // negative relevance effect on the gold set - MRR 0.520 -> 0.433, Recall@5 6/10 -> 5/10).
        // Legacy "use_ai" is preserved unchanged for the planner (backward compatible with existing
        // callers), but no longer implies reranker AI - the reranker now defaults OFF and requires
        // explicit opt-in via use_ai_reranker, independent of the planner setting.
        boolean useAiPlanner = resolveUseAiPlanner(payload);
        boolean useAiReranker = resolveUseAiReranker(payload);
        boolean useAiStory = useAiPlanner && parseBoolean(payload.get("use_ai_story"), true);
        SearchQueryPlan plan = plan(payload, query, useAiPlanner, noCache, trace, catalogVersion);
        trace.timing("planner_ms", System.currentTimeMillis() - planStart);
        trace.put("query_plan", plan.toMap());
        trace.put("llm_planner", plan.llmPlannerTrace());
        trace.put("fallback_trace", plan.fallbackTrace());
        trace.put("entity_resolution", plan.entityResolution().toMap());
        trace.put("source_routing", plan.sourceRouting().toMap());
        trace.put("query_variants", plan.queryVariants().stream().map(cz.bankintel.search.v2.schema.SearchQueryVariant::toMap).toList());
        boolean clarificationSuggested = plan.clarification() != null && plan.clarification().required();
        if (clarificationSuggested) {
            trace.event(
                    "clarification_deferred",
                    "Planner suggested clarification; retrieval continues so a broad but safe result can still be offered.");
        }
        SearchV2SectorRoutingGuard.Assessment sectorAssessment =
                SearchV2SectorRoutingGuard.assess(plan, conceptRegistry, institutionalSectorRegistry);
        trace.put("institutional_sectors", plan.institutionalSectors());
        trace.put("concept_registry_status", sectorAssessment.conceptRegistryStatus());
        trace.put("concept_implied_sector", sectorAssessment.impliedSector());
        trace.put("concept_sector_conflict", sectorAssessment.conceptSectorConflict());
        trace.put("sector_fan_out_triggered", sectorAssessment.fanOutTriggered());

        int limit = clampLimit(CatalogMapSupport.toInt(payload.get("limit"), DEFAULT_LIMIT));
        List<String> allowedSources = allowedSources(plan, payload, sectorAssessment);
        List<String> vectorAllowedSources = vectorAllowedSources(plan, payload, allowedSources);
        trace.put("allowed_sources", allowedSources);
        trace.put("vector_allowed_sources", vectorAllowedSources.isEmpty() ? List.of("*") : vectorAllowedSources);
        String catalogIndexMode = sidecarIndex.configuredMode(payload);
        trace.put("catalog_index_mode", catalogIndexMode);
        trace.put("semantic_retrieval_enabled", semanticRetrievalEnabled());
        trace.put("fallback_to_legacy", false);
        trace.put("candidate_limits", candidateLimits());

        long ftsTimeoutMs = ftsTimeoutMs(payload);
        trace.put("fts_timeout_ms", ftsTimeoutMs);
        SearchV2FtsRetriever.RetrievalResult rawRetrieval =
                retrieve(
                        plan,
                        allowedSources,
                        vectorAllowedSources,
                        catalogVersion,
                        trace,
                        noCache,
                        ftsTimeoutMs,
                        catalogIndexMode);
        SearchV2FtsRetriever.RetrievalResult retrieval = assessDeterministicConstraints(rawRetrieval, plan, trace);
        boolean retrievalDegraded = retrievalHadFailures(retrieval);
        Map<String, Object> vectorDiagnostics = vectorDiagnostics(retrieval.queryStats());
        trace.timing("fts_ms", retrieval.latencyMs());
        trace.timing("retrieval_ms", retrieval.latencyMs());
        trace.timing("embedding_ms", CatalogMapSupport.toInt(vectorDiagnostics.get("embedding_ms"), 0));
        trace.timing("vector_search_ms", CatalogMapSupport.toInt(vectorDiagnostics.get("vector_search_ms"), 0));
        trace.put("fts_queries", retrieval.queries());
        trace.put("fts_query_stats", retrieval.queryStats());
        trace.put("vector_retrieval", vectorDiagnostics);
        putVectorTraceFields(trace, vectorDiagnostics);
        trace.put("candidate_count_pre_merge", retrieval.preMergeCandidates().size());
        trace.put("candidate_count", retrieval.candidates().size());
        long preRerankerPoolCompactionStart = System.currentTimeMillis();
        trace.put("candidate_pool_pre_merge_top_200", compactCandidates(retrieval.preMergeCandidates(), 200));
        trace.put("candidate_pool_merged_top_200", compactCandidates(retrieval.candidates(), 200));
        trace.timing("candidate_pool_compaction_pre_reranker_ms", System.currentTimeMillis() - preRerankerPoolCompactionStart);
        trace.put("candidate_pool_compaction_pre_reranker_in_count",
                retrieval.preMergeCandidates().size() + retrieval.candidates().size());

        long selectRerankPoolStart = System.currentTimeMillis();
        List<SearchCandidate> rerankPool = selectRerankPool(
                retrieval.candidates(), maxRerankCandidates(), plan, conceptRegistry);
        trace.timing("select_rerank_pool_ms", System.currentTimeMillis() - selectRerankPoolStart);
        trace.put("select_rerank_pool_in_count", retrieval.candidates().size());
        trace.put("select_rerank_pool_out_count", rerankPool.size());
        trace.put(
                "rerank_pool_geo_conflicts_deprioritized",
                retrieval.candidates().size()
                        - prioritizeGeoEligible(retrieval.candidates(), plan, maxRerankCandidates()).size());
        int effectiveRerankPoolSize = rerankPool.size();
        SearchV2SemanticValidator.ValidationResult validation = rerank(plan, rerankPool, useAiReranker);
        List<SemanticDecision> semanticDecisions = validation.decisions();
        trace.timing("reranker_ms", validation.latencyMs());
        trace.put("semantic_rerank_status", validation.status());
        trace.put("semantic_batches", validation.batches());
        trace.put("semantic_errors", validation.errors());
        trace.put("semantic_decisions", semanticDecisions.stream().map(SemanticDecision::toMap).toList());

        long finalRankStart = System.currentTimeMillis();
        List<SearchResult> ranked = finalReranker.finalRank(retrieval.candidates(), semanticDecisions, rankWindow(limit), plan);
        trace.timing("final_rank_ms", System.currentTimeMillis() - finalRankStart);
        trace.put("final_rank_in_count", retrieval.candidates().size());
        trace.put("final_rank_out_count", ranked.size());
        long coverageStart = System.currentTimeMillis();
        SearchV2CoverageChecker.CoverageResult coverage = coverageChecker.check(plan, ranked, validation.status());
        trace.timing("coverage_ms", System.currentTimeMillis() - coverageStart);
        trace.put("coverage_first_pass", coverage.toMap());

        boolean retried = false;
        boolean retryAdopted = false;
        SearchV2FtsRetriever.RetrievalResult retryRetrieval = null;
        SearchV2SemanticValidator.ValidationResult retryValidation = null;
        boolean retryAllowed = coverage.retryRecommended()
                && !retrievalInfrastructureFailed(retrieval)
                && !semanticFallbackAlreadyHasResults(validation.status(), ranked);
        if (retryAllowed) {
            List<String> retryTerms = retryPlanner.retryTerms(plan, coverage);
            if (!retryTerms.isEmpty()) {
                retried = true;
                long retryStart = System.currentTimeMillis();
                retryRetrieval = retriever.retrieveQueries(retryTerms, allowedSources, ftsTimeoutMs, catalogIndexMode);
                retryRetrieval = assessDeterministicConstraints(retryRetrieval, plan, trace);
                List<SearchCandidate> merged =
                        candidateMerger.merge(join(retrieval.candidates(), retryRetrieval.candidates()), 240);
                List<SearchCandidate> retryPool = selectRerankPool(
                        merged, maxRerankCandidates(), plan, conceptRegistry);
                retryValidation = rerank(plan, retryPool, useAiReranker);
                List<SearchResult> retryRanked =
                        finalReranker.finalRank(merged, retryValidation.decisions(), rankWindow(limit), plan);
                SearchV2CoverageChecker.CoverageResult retryCoverage =
                        coverageChecker.check(plan, retryRanked, retryValidation.status());
                trace.timing("retry_ms", System.currentTimeMillis() - retryStart);
                trace.put("retry", Map.of(
                        "reason", coverage.reason(),
                        "missing_aspects", coverage.missingAspects(),
                        "queries", retryTerms,
                        "candidate_count", retryRetrieval.candidates().size(),
                        "semantic_status", retryValidation.status(),
                        "coverage", retryCoverage.toMap()));
                retryAdopted = retryImproves(coverage, retryCoverage, ranked, retryRanked);
                trace.put("retry_adopted", retryAdopted);
                if (retryAdopted) {
                    ranked = retryRanked;
                    coverage = retryCoverage;
                    validation = retryValidation;
                    effectiveRerankPoolSize = retryPool.size();
                    retrieval = new SearchV2FtsRetriever.RetrievalResult(
                            merged,
                            join(retrieval.preMergeCandidates(), retryRetrieval.preMergeCandidates()),
                            joinStrings(retrieval.queries(), retryRetrieval.queries()),
                            joinMaps(retrieval.queryStats(), retryRetrieval.queryStats()),
                            retrieval.latencyMs() + retryRetrieval.latencyMs(),
                            retrieval.indexMode());
                }
            }
        } else if (coverage.retryRecommended()) {
            trace.put("retry_adopted", false);
            String reason = retrievalInfrastructureFailed(retrieval)
                    ? "Initial FTS retrieval failed technically; retry would repeat the same infrastructure failure."
                    : "Semantic rerank is unavailable/disabled and fallback already has candidates; retry would add latency without a new signal.";
            trace.event("retry_skipped", reason);
        }

        PreviewPolicy previewPolicy = previewPolicy(payload, limit);
        SearchV2PreviewVerifier.VerificationResult verified = verifyPreview(ranked, plan, previewPolicy, trace);
        trace.timing("preview_verification_ms", verified.latencyMs());
        // PR-7: every checked candidate keeps an explicit, non-ambiguous outcome here regardless of
        // the accept-gate below — this is the one place a timed-out/cancelled/failed candidate is
        // never lost, even when it does not make it into `results`/`possible` (see PR-7 report on the
        // pre-existing selectFinalResults gate this does not change).
        long previewVerificationMappingStart = System.currentTimeMillis();
        List<Map<String, Object>> previewVerificationWithOutcome = withPreviewOutcome(verified.statuses());
        trace.timing("preview_verification_mapping_ms", System.currentTimeMillis() - previewVerificationMappingStart);
        trace.put("preview_verification_mapping_in_count", verified.statuses().size());
        trace.put("preview_verification", previewVerificationWithOutcome);
        trace.put("preview_mode", previewPolicy.mode());
        trace.put("preview_requested_count", previewPolicy.verifyLimit());
        trace.put("preview_checked_count", verified.checkedCount());
        trace.put("preview_unique_request_count", verified.uniqueRequestCount());
        // TEMP PERF INSTRUMENTATION (Gap A investigation, additive only - no behavior/result change):
        long finalSelectionStart = System.currentTimeMillis();
        List<SearchResult> finalResults = selectFinalResults(
                ranked, verified.accepted(), previewPolicy.usesPreviewAsGate(), limit);
        trace.timing("final_selection_ms", System.currentTimeMillis() - finalSelectionStart);
        trace.put("final_selection_in_count", ranked.size());
        trace.put("final_selection_out_count", finalResults.size());
        boolean exactRetrievalSucceeded = exactRetrievalSucceeded(plan, finalResults);
        trace.put("exact_retrieval_succeeded", exactRetrievalSucceeded);
        trace.put("broad_expansion_used", plan.highConfidenceExactEntity() ? !exactRetrievalSucceeded && retried : retried);
        if (previewPolicy.usesPreviewAsGate() && verified.accepted().isEmpty() && !ranked.isEmpty()) {
            coverage = new SearchV2CoverageChecker.CoverageResult(
                    "partial",
                    joinStrings(coverage.missingAspects(), List.of("preview_verification")),
                    false,
                    "Semantic candidates were found, but live preview verification did not confirm them.");
        }
        long candidateCountsStart = System.currentTimeMillis();
        Map<String, Object> candidateCounts = candidateCounts(
                rawRetrieval,
                retrieval,
                effectiveRerankPoolSize,
                validation,
                verified,
                finalResults.size());
        trace.timing("candidate_counts_ms", System.currentTimeMillis() - candidateCountsStart);
        trace.put("candidate_counts", candidateCounts);
        long geoTraceStart = System.currentTimeMillis();
        Map<String, Object> geoTrace = SearchV2GeoCompatibility.geoTrace(plan, finalResults);
        trace.timing("geo_trace_build_ms", System.currentTimeMillis() - geoTraceStart);
        trace.put("geo_trace_in_count", finalResults.size());
        trace.put("geo_trace", geoTrace);

        Map<String, Object> response = baseResponse(query, plan, trace, catalogVersion);
        response.put("ok", true);
        response.put("retrieval_degraded", retrievalDegraded);
        long finalResultMapsStart = System.currentTimeMillis();
        List<Map<String, Object>> finalResultMaps = finalResults.stream()
                .map(result -> resultMapWithPreview(result, verified))
                .toList();
        trace.timing("final_result_maps_build_ms", System.currentTimeMillis() - finalResultMapsStart);
        trace.put("final_result_maps_in_count", finalResults.size());
        trace.put("final_result_maps_out_count", finalResultMaps.size());
        long verifiedMappingStart = System.currentTimeMillis();
        List<Map<String, Object>> verifiedMaps = finalResultMaps.stream().filter(SearchV2Service::previewVerified).toList();
        trace.timing("verified_mapping_ms", System.currentTimeMillis() - verifiedMappingStart);
        long possibleMappingStart = System.currentTimeMillis();
        List<Map<String, Object>> possibleMaps = finalResultMaps.stream().filter(row -> !previewVerified(row)).toList();
        trace.timing("possible_mapping_ms", System.currentTimeMillis() - possibleMappingStart);
        trace.put("verified_mapping_out_count", verifiedMaps.size());
        trace.put("possible_mapping_out_count", possibleMaps.size());
        // PR-7b/PR-7c: relevant candidates whose preview outcome is POSSIBLE/TIMEOUT/CANCELLED/
        // TRANSPORT_FAILURE/UNSUPPORTED/INTERNAL_FAILURE/CIRCUIT_OPEN/BULKHEAD_REJECTED - present in
        // `ranked` (so preview gave no evidence of irrelevance) but absent from `finalResultMaps` above
        // (so lost today whenever `previewPolicy.usesPreviewAsGate()`). Ordered exactly as in `ranked`
        // (pre-preview relevance order) - preview outcome must never move a candidate's rank. Never
        // populated when the flag is off, so the response is byte-for-byte unchanged in that case.
        boolean unverifiedResultsEnabled = unverifiedResultsEnabled();
        long unverifiedMappingStart = System.currentTimeMillis();
        List<Map<String, Object>> unverifiedMaps = unverifiedResultsEnabled
                ? buildUnverifiedResults(ranked, verified, finalResultMaps)
                : List.of();
        trace.timing("unverified_mapping_ms", System.currentTimeMillis() - unverifiedMappingStart);
        trace.put("unverified_mapping_in_count", ranked.size());
        trace.put("unverified_mapping_out_count", unverifiedMaps.size());
        // PR-7c: diagnostic only (never affects the response) - flags the case where a candidate that
        // was actually dispatched to preview (within verified.checkedCount()'s prefix of `ranked`) still
        // classifies as NOT_CHECKED. That combination should not be possible with the current
        // SearchV2PreviewVerifier - see the javadoc below for why NOT_CHECKED is otherwise legitimate.
        warnIfCheckedCandidateIsUnclassifiable(ranked, verified, previewPolicy.mode(), trace);
        String status;
        if (!finalResults.isEmpty()) {
            status = "ok";
        } else if (clarificationSuggested) {
            status = "clarification_required";
        } else if (unverifiedResultsEnabled && !unverifiedMaps.isEmpty()) {
            // PR-7b: a relevant-but-unconfirmed candidate exists - never claim "no valid result" here,
            // that would falsely imply nothing relevant was even found.
            status = "unverified_only";
        } else {
            status = "no_valid_result";
        }
        response.put("status", status);
        // Web-search fallback: only when nothing valid was found at all, and never for metadata_only
        // eval/benchmark runs (mirrors v1's early metadata_only return - those must not spend an
        // OpenAI web call per empty query). Same contract as the v1 engine (CatalogDeepSearchService)
        // so the frontend renders both identically. v2 never serves Manager Explorer discovery, so no
        // manager guard is needed. Included before the response is returned/cached, so a repeat of an
        // empty query reuses the cached web finding.
        boolean webFallbackEligible =
                "no_valid_result".equals(status) && !"metadata_only".equals(previewPolicy.mode());
        applyWebFallback(response, query, webFallbackEligible, allowedSources);
        response.put("results", finalResultMaps);
        response.put("verified", verifiedMaps);
        response.put("possible", possibleMaps);
        if (unverifiedResultsEnabled) {
            response.put("unverified", unverifiedMaps);
        }
        long answerStart = System.currentTimeMillis();
        // PR-7c: three mutually exclusive story modes. UNVERIFIED_NOTICE applies whenever there is no
        // verified result but at least one relevant-but-unverified candidate exists - deliberately
        // checked directly, not via `status`, so it also covers the (rarer) case where clarification
        // is ALSO suggested; the response's `status` field and the story's own safety are separate
        // concerns. This mode never calls composeStory/the LLM at all - see
        // composeUnverifiedNotice's javadoc for why an LLM asked to "explain the data" must never run
        // when there is no verified data to ground it. Deliberately NOT gated further by useAiStory:
        // "may change wording, must not interpret" is satisfied by the deterministic template's own
        // reason-category wording, not by still permitting an LLM call here.
        long storyModeSelectionStart = System.currentTimeMillis();
        String storyMode = !verifiedMaps.isEmpty()
                ? "verified_data"
                : (unverifiedResultsEnabled && !unverifiedMaps.isEmpty()) ? "unverified_notice" : "no_result";
        trace.timing("story_mode_selection_ms", System.currentTimeMillis() - storyModeSelectionStart);
        Map<String, Object> story = "unverified_notice".equals(storyMode)
                ? searchAnswerService.composeUnverifiedNotice(query, unverifiedMaps)
                : searchAnswerService.composeStory(query, verifiedMaps, possibleMaps, useAiStory);
        trace.timing("answer_ms", System.currentTimeMillis() - answerStart);
        trace.put("answer_mode", useAiStory ? "llm" : "deterministic_non_blocking");
        trace.put("story_mode", storyMode);
        if (unverifiedResultsEnabled) {
            // Gated the same way as `unverified` itself - flag off must leave the response untouched.
            response.put("story_mode", storyMode);
        }
        long responseMapBuildStart = System.currentTimeMillis();
        response.put("ai_result_layer", story);
        response.put("answer", story.get("answer_cz"));
        response.put("dropped_summary", droppedSummary(validation.decisions()));
        response.put(
                "clarification",
                finalResults.isEmpty() && clarificationSuggested ? plan.clarification().toMap() : null);
        response.put(
                "suggested_clarification",
                !finalResults.isEmpty() && clarificationSuggested ? plan.clarification().toMap() : null);
        response.put("coverage", coverage.toMap());
        response.put("semantic_rerank_status", validation.status());
        response.put("semantic_model", validation.model());
        response.put("semantic_prompt_tokens_estimate", validation.approxPromptTokens());
        response.put("source_statuses", sourceStats(retrieval.queryStats()));
        response.put("vector_retrieval", vectorDiagnostics);
        response.put("retried", retried);
        response.put("retry_adopted", retryAdopted);
        response.put("preview_mode", previewPolicy.mode());
        response.put("live_preview_verification", !"metadata_only".equals(previewPolicy.mode()));
        response.put("preview_verification", previewVerificationWithOutcome);
        response.put("catalog_index_mode", retrieval.indexMode());
        response.put("semantic_retrieval_enabled", semanticRetrievalEnabled());
        response.put("fallback_to_legacy", fallbackToLegacy(catalogIndexMode, retrieval.indexMode()));
        response.put("candidate_limits", candidateLimits());
        response.put("candidate_counts", candidateCounts);
        response.put("geo_trace", geoTrace);
        response.put("preview_checked_count", verified.checkedCount());
        response.put("preview_unique_request_count", verified.uniqueRequestCount());
        response.put("candidate_count_pre_merge", retrieval.preMergeCandidates().size());
        response.put("candidate_count", retrieval.candidates().size());
        response.put("entity_resolution", plan.entityResolution().toMap());
        response.put("source_routing", plan.sourceRouting().toMap());
        response.put("query_variants", plan.queryVariants().stream().map(cz.bankintel.search.v2.schema.SearchQueryVariant::toMap).toList());
        response.put("llm_planner", plan.llmPlannerTrace());
        response.put("fallback_trace", plan.fallbackTrace());
        response.put("institutional_sectors", plan.institutionalSectors());
        response.put("concept_registry_status", sectorAssessment.conceptRegistryStatus());
        response.put("concept_implied_sector", sectorAssessment.impliedSector());
        response.put("concept_sector_conflict", sectorAssessment.conceptSectorConflict());
        response.put("sector_fan_out_triggered", sectorAssessment.fanOutTriggered());
        response.put("allowed_sources", allowedSources);
        response.put("exact_retrieval_succeeded", exactRetrievalSucceeded);
        response.put("broad_expansion_used", plan.highConfidenceExactEntity() ? !exactRetrievalSucceeded && retried : retried);
        if (truthy(payload.get("debug")) || truthy(payload.get("include_retrieval_diagnostics"))) {
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("index_mode", retrieval.indexMode());
            diagnostics.put("semantic_retrieval_enabled", semanticRetrievalEnabled());
            diagnostics.put("fallback_to_legacy", fallbackToLegacy(catalogIndexMode, retrieval.indexMode()));
            diagnostics.put("candidate_counts", candidateCounts);
            diagnostics.put("candidate_limits", candidateLimits());
            diagnostics.put("queries", retrieval.queries());
            diagnostics.put("query_stats", retrieval.queryStats());
            diagnostics.put("vector", vectorDiagnostics);
            long candidatePoolCompactionStart = System.currentTimeMillis();
            diagnostics.put("pre_merge_top_200", compactCandidates(retrieval.preMergeCandidates(), 200));
            diagnostics.put("merged_top_200", compactCandidates(retrieval.candidates(), 200));
            trace.timing("candidate_pool_compaction_ms", System.currentTimeMillis() - candidatePoolCompactionStart);
            trace.put("candidate_pool_compaction_in_count",
                    retrieval.preMergeCandidates().size() + retrieval.candidates().size());
            diagnostics.put("semantic_decisions", semanticDecisions.stream().map(SemanticDecision::toMap).toList());
            response.put("retrieval_diagnostics", diagnostics);
        } else {
            trace.timing("candidate_pool_compaction_ms", 0);
        }
        if (retryRetrieval != null) {
            response.put("retry_candidate_count", retryRetrieval.candidates().size());
        }
        if (retryValidation != null) {
            response.put("retry_semantic_status", retryValidation.status());
        }
        trace.timing("response_map_build_ms", System.currentTimeMillis() - responseMapBuildStart);
        trace.timing("total_pipeline_ms", System.currentTimeMillis() - pipelineStart);
        response.put("timings", trace.snapshot().get("timings"));
        emitSearchTelemetry(
                payload,
                query,
                plan,
                retrieval,
                rerankPool,
                semanticDecisions,
                ranked,
                finalResults,
                verified,
                coverage,
                retrievalDegraded,
                retried,
                useAiStory,
                validation,
                finalResultMaps.size(),
                verifiedMaps.size(),
                possibleMaps.size(),
                trace);
        return response;
    }

    /**
     * Fáze 2 (perf investigation): enqueues one telemetry event onto {@link #telemetryExecutor} -
     * never runs the expensive part (concept/geo/exact-entity re-evaluation inside
     * {@code SearchV2TelemetryEventBuilder.build}) on the request thread. Only the trace snapshot
     * (a cheap shallow copy) and the {@code execute(...)} call itself happen here, timed as
     * {@code telemetry_enqueue_ms}. Guarded by the feature flag so nothing is computed/submitted when
     * telemetry is off. A full queue or a build/write failure is handled entirely inside
     * {@link #buildAndWriteSearchTelemetry} / the executor's rejection handler - neither can ever
     * affect the response already assembled by {@code runSearch}, since that response was built and
     * returned before this method is even called.
     */
    private void emitSearchTelemetry(
            Map<String, Object> payload,
            String query,
            SearchQueryPlan plan,
            SearchV2FtsRetriever.RetrievalResult retrieval,
            List<SearchCandidate> rerankPool,
            List<SemanticDecision> semanticDecisions,
            List<SearchResult> ranked,
            List<SearchResult> finalResults,
            SearchV2PreviewVerifier.VerificationResult verified,
            SearchV2CoverageChecker.CoverageResult coverage,
            boolean retrievalDegraded,
            boolean retried,
            boolean useAiStory,
            SearchV2SemanticValidator.ValidationResult validation,
            int totalResultCount,
            int verifiedResultCount,
            int possibleResultCount,
            SearchV2Trace trace) {
        if (!telemetryWriter.enabled()) {
            return;
        }
        long enqueueStart = System.currentTimeMillis();
        Map<String, Object> traceSnapshot = trace.snapshot();
        String traceId = trace.traceId();
        telemetryExecutor.execute(() -> buildAndWriteSearchTelemetry(
                payload,
                query,
                plan,
                retrieval,
                rerankPool,
                semanticDecisions,
                ranked,
                finalResults,
                verified,
                coverage,
                retrievalDegraded,
                retried,
                useAiStory,
                validation,
                totalResultCount,
                verifiedResultCount,
                possibleResultCount,
                traceSnapshot,
                traceId));
        telemetrySubmittedCount.incrementAndGet();
        trace.timing("telemetry_enqueue_ms", System.currentTimeMillis() - enqueueStart);
    }

    /**
     * Runs entirely on {@link #telemetryExecutor} (never the request thread) - this is where all of
     * {@code emitSearchTelemetry}'s previous cost actually lives. Takes only already-immutable inputs
     * (records, {@code List.of()}/{@code .toList()} results, and {@code traceSnapshot} - a frozen copy,
     * not the live mutable {@link SearchV2Trace}) so it is safe to run at an arbitrary later time,
     * including after the request that produced them has already returned its response.
     */
    private void buildAndWriteSearchTelemetry(
            Map<String, Object> payload,
            String query,
            SearchQueryPlan plan,
            SearchV2FtsRetriever.RetrievalResult retrieval,
            List<SearchCandidate> rerankPool,
            List<SemanticDecision> semanticDecisions,
            List<SearchResult> ranked,
            List<SearchResult> finalResults,
            SearchV2PreviewVerifier.VerificationResult verified,
            SearchV2CoverageChecker.CoverageResult coverage,
            boolean retrievalDegraded,
            boolean retried,
            boolean useAiStory,
            SearchV2SemanticValidator.ValidationResult validation,
            int totalResultCount,
            int verifiedResultCount,
            int possibleResultCount,
            Map<String, Object> traceSnapshot,
            String traceId) {
        long buildStart = System.currentTimeMillis();
        try {
            Object timingsRaw = traceSnapshot.get("timings");
            Map<String, Object> timingsSnapshot =
                    timingsRaw instanceof Map<?, ?> timingsMap ? CatalogMapSupport.castMap(timingsMap) : Map.of();
            SearchV2ConceptRegistry.ConceptResolution conceptResolution = conceptRegistry.resolve(plan.originalQuery());
            Double conceptConfidence = conceptResolution.matches().isEmpty() ? null : conceptResolution.confidence();
            Map<String, Boolean> featureFlags = new LinkedHashMap<>();
            featureFlags.put("search_v2_telemetry_enabled", telemetryWriter.enabled());
            featureFlags.put("search_semantic_retrieval_enabled", semanticRetrievalEnabled());
            featureFlags.put(
                    "search_vector_retrieval_enabled", BankIntelEnvVars.isTruthy("SEARCH_VECTOR_RETRIEVAL_ENABLED"));
            featureFlags.put("search_v2_shadow_mode", BankIntelEnvVars.isTruthy("SEARCH_V2_SHADOW_MODE"));
            boolean finalCacheBypassed = truthy(payload.get("debug"))
                    || truthy(payload.get("no_cache"))
                    || query == null
                    || query.isBlank();
            SearchV2TelemetryEventBuilder.Context context = new SearchV2TelemetryEventBuilder.Context(
                    System.currentTimeMillis(),
                    traceId,
                    query,
                    plan,
                    retrieval.candidates(),
                    rerankPool,
                    semanticDecisions,
                    ranked,
                    finalResults,
                    verified.statuses(),
                    coverage,
                    retrievalDegraded,
                    retried,
                    String.valueOf(traceSnapshot.get("plan_cache_status")),
                    String.valueOf(traceSnapshot.get("retrieval_cache_status")),
                    finalCacheBypassed ? "bypassed" : "miss",
                    useAiStory ? "llm_story" : "deterministic_story",
                    timingsSnapshot,
                    conceptConfidence,
                    plan.model(),
                    validation.model(),
                    SearchV2QueryPlanner.PLANNER_PROMPT_VERSION,
                    SearchV2QueryPlanner.promptContentHash(),
                    SearchV2SemanticValidator.RERANKER_PROMPT_VERSION,
                    SearchV2SemanticValidator.promptContentHash(),
                    conceptOntology.version(),
                    null,
                    "catalog/search_synonyms.json and related classic lexicon resources do not declare a "
                            + "version field today; introducing one is out of scope for PR-1.",
                    String.valueOf(sidecarIndex.contentRevision()),
                    featureFlags,
                    totalResultCount,
                    verifiedResultCount,
                    possibleResultCount,
                    exactEntityScorer,
                    conceptRegistry);
            var event = SearchV2TelemetryEventBuilder.build(context);
            long buildMs = System.currentTimeMillis() - buildStart;
            long writeStart = System.currentTimeMillis();
            telemetryWriter.submit(event);
            long writeMs = System.currentTimeMillis() - writeStart;
            log.debug(
                    "search v2 telemetry: built+submitted event for trace_id={} telemetry_build_ms={} "
                            + "telemetry_write_ms={}",
                    traceId,
                    buildMs,
                    writeMs);
        } catch (Exception ex) {
            telemetryFailedCount.incrementAndGet();
            log.warn(
                    "search v2 telemetry: failed to build/submit event for trace_id={}, dropping: {}",
                    traceId,
                    ex.getMessage());
        }
    }

    /** Test/diagnostic visibility into the telemetry executor - never used by request-handling logic. */
    long telemetrySubmittedCount() {
        return telemetrySubmittedCount.get();
    }

    long telemetryDroppedCount() {
        return telemetryDroppedCount.get();
    }

    /** Test-only access to the executor itself (e.g. to saturate its queue deterministically). */
    ThreadPoolExecutor telemetryExecutorForTest() {
        return telemetryExecutor;
    }

    long telemetryFailedCount() {
        return telemetryFailedCount.get();
    }

    private SearchV2PreviewVerifier.VerificationResult verifyPreview(
            List<SearchResult> ranked, SearchQueryPlan plan, PreviewPolicy previewPolicy, SearchV2Trace trace) {
        if ("metadata_only".equals(previewPolicy.mode())) {
            trace.event("preview_skipped", "metadata_only");
            return new SearchV2PreviewVerifier.VerificationResult(
                    List.of(),
                    List.of(Map.of("mode", "metadata_only", "skipped", true, "ok", true)),
                    0,
                    0,
                    0);
        }
        // Preview-candidate selection fix: a naive "top N of ranked" slice let one source (typically a
        // multi-country aggregate catalog whose candidates carry a blank geo field) occupy every
        // preview slot for a non-covered-country query, even when candidates from other sources with
        // an explicit geo match existed lower in `ranked`. This selector only decides WHICH candidates
        // get a chance at live preview - it never changes relevance scores or `ranked` itself, and the
        // accepted subset is re-sorted back into `ranked`'s original order below so final result order
        // is unaffected.
        SearchV2PreviewCandidateSelector.Selection selection = SearchV2PreviewCandidateSelector.select(
                ranked, previewPolicy.verifyLimit(), plan.geographies(), plan.sourceRouting());
        trace.put("preview_input_count", selection.telemetry().inputCount());
        trace.put("preview_deduped_count", selection.telemetry().dedupedCount());
        trace.put("preview_selected_count", selection.telemetry().selectedCount());
        trace.put("preview_explicit_geo_selected_count", selection.telemetry().explicitGeoSelectedCount());
        trace.put("preview_source_counts", selection.telemetry().sourceCounts());
        trace.put("preview_soft_cap_relaxed", selection.telemetry().softCapRelaxed());
        SearchV2PreviewVerifier.VerificationResult verified =
                previewVerifier.verifyTopOnly(selection.candidates(), previewPolicy.verifyLimit(), plan.geographies());
        List<SearchResult> reorderedAccepted =
                SearchV2PreviewCandidateSelector.restoreRankedOrder(verified.accepted(), ranked);
        return new SearchV2PreviewVerifier.VerificationResult(
                reorderedAccepted, verified.statuses(), verified.latencyMs(),
                verified.checkedCount(), verified.uniqueRequestCount());
    }

    private SearchV2FtsRetriever.RetrievalResult retrieve(
            SearchQueryPlan plan,
            List<String> allowedSources,
            List<String> vectorAllowedSources,
            String catalogVersion,
            SearchV2Trace trace,
            boolean noCache,
            long ftsTimeoutMs,
            String catalogIndexMode) {
        long start = System.currentTimeMillis();
        if (noCache) {
            trace.put("retrieval_cache_status", "bypassed");
            SearchV2FtsRetriever.RetrievalResult result =
                    retriever.retrieve(plan, allowedSources, vectorAllowedSources, ftsTimeoutMs, catalogIndexMode);
            trace.timing("retrieval_cache_wrapper_ms", System.currentTimeMillis() - start);
            return result;
        }
        String cacheKey = "retrieval:"
                + RETRIEVAL_CACHE_SCHEMA
                + ":"
                + catalogVersion
                + ":index="
                + catalogIndexMode
                + sidecarRevisionSuffix(catalogIndexMode, sidecarIndex.contentRevision())
                + ":timeout="
                + ftsTimeoutMs
                + ":"
                + String.join(",", allowedSources)
                + ":vector="
                + (vectorAllowedSources.isEmpty() ? "*" : String.join(",", vectorAllowedSources))
                + ":"
                + normalized(String.join("|", plan.firstPassSearchTerms()));
        // Peeked only for telemetry visibility; getOrComputeIf below performs its own authoritative
        // check, so this cannot change which branch (cached vs freshly computed) actually runs.
        trace.put("retrieval_cache_status", cacheService.get(cacheKey).isPresent() ? "hit" : "miss");
        SearchV2FtsRetriever.RetrievalResult result = cacheService.getOrComputeIf(
                cacheKey,
                RETRIEVAL_TTL,
                () -> retriever.retrieve(
                        plan, allowedSources, vectorAllowedSources, ftsTimeoutMs, catalogIndexMode),
                candidate -> !retrievalHadFailures(candidate));
        trace.timing("retrieval_cache_wrapper_ms", System.currentTimeMillis() - start);
        return result;
    }

    private SearchV2FtsRetriever.RetrievalResult assessDeterministicConstraints(
            SearchV2FtsRetriever.RetrievalResult retrieval, SearchQueryPlan plan, SearchV2Trace trace) {
        if (retrieval == null) {
            return retrieval;
        }
        List<SearchCandidate> rawPool = retrieval.preMergeCandidates().isEmpty()
                ? retrieval.candidates()
                : retrieval.preMergeCandidates();
        if (rawPool.isEmpty()) {
            return retrieval;
        }
        List<String> requestedGeo = plan == null || plan.geographies() == null ? List.of() : plan.geographies();
        long explicitGeoConflicts = rawPool.stream()
                .filter(candidate -> SearchV2GeoCompatibility
                        .assessCandidateGeo(candidate, requestedGeo, plan)
                        .hardConflict())
                .count();
        int candidateCount = uniqueCandidateCount(rawPool);
        trace.put("deterministic_constraint_assessment", Map.of(
                "mode", "advisory_evidence_for_llm",
                "requested_geographies", requestedGeo,
                "candidate_count", candidateCount,
                "geo_compatible_or_dimension_selectable", Math.max(0, candidateCount - explicitGeoConflicts),
                "explicit_geo_conflicts", explicitGeoConflicts,
                "removed_before_llm", 0));
        return retrieval;
    }

    private SearchV2SemanticValidator.ValidationResult rerank(
            SearchQueryPlan plan, List<SearchCandidate> candidates, boolean requestedAi) {
        return batchReranker.rerank(plan, candidates, requestedAi);
    }

    static List<SearchCandidate> selectRerankPool(List<SearchCandidate> candidates, int limit) {
        return selectRerankPool(candidates, limit, null, null);
    }

    static List<SearchCandidate> selectRerankPool(
            List<SearchCandidate> candidates,
            int limit,
            SearchQueryPlan plan,
            SearchV2ConceptRegistry conceptRegistry) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        candidates = prioritizeGeoEligible(candidates, plan, limit);
        LinkedHashMap<String, SearchCandidate> selected = new LinkedHashMap<>();
        boolean ambiguousPlan = plan != null
                && plan.clarification() != null
                && plan.clarification().required();
        if (ambiguousPlan) {
            reserveAmbiguousBranchCoverage(candidates, limit, plan, selected);
        }
        if (plan != null && conceptRegistry != null && plan.primaryConcepts() != null) {
            // Perf fix: resolved exactly once per selectRerankPool call, not once per candidate - see
            // SearchV2ConceptRegistry#resolveRequirement's javadoc for why this is safe (same result
            // for every candidate in this call) and SearchV2ConceptRegistryPerfTest for the before/after
            // equivalence proof.
            SearchV2ConceptRegistry.ResolvedConceptRequirement requirement =
                    conceptRegistry.resolveRequirement(plan.primaryConcepts());
            candidates.stream()
                    .filter(candidate -> conceptRegistry.candidateMatchesRequiredConcepts(
                            candidateConceptEvidence(candidate), requirement))
                    .sorted(Comparator
                            .comparing((SearchCandidate candidate) -> SearchV2GeoCompatibility
                                    .assessCandidateGeo(candidate, plan.geographies(), plan)
                                    .hardConflict())
                            .thenComparingInt(candidate -> preferredSourceRank(plan, candidate))
                            .thenComparingDouble(SearchCandidate::ftsScore))
                    .limit(Math.max(1, limit / 3))
                    .forEach(candidate -> selected.putIfAbsent(candidate.candidateId(), candidate));
        }
        candidates.stream()
                .filter(SearchV2Service::hasStrongTitleEvidence)
                .limit(Math.max(1, limit / 3))
                .forEach(candidate -> selected.putIfAbsent(candidate.candidateId(), candidate));
        for (SearchCandidate candidate : candidates) {
            selected.putIfAbsent(candidate.candidateId(), candidate);
            if (selected.size() >= limit) {
                break;
            }
        }
        return selected.values().stream().limit(limit).toList();
    }

    /**
     * Podíl rerank poolu, který smí obsadit kandidáti s prokazatelně nesedící geografií.
     * Zbytek patří kandidátům, kteří geo dotazu neodporují.
     */
    private static final int GEO_CONFLICT_POOL_FRACTION = 5;

    /**
     * Seřadí kandidáty tak, aby ti, kdo geo dotazu neodporují, šli do rerank poolu první,
     * a omezí, kolik z poolu smí zabrat prokazatelně nesedící geografie.
     *
     * <p>Naměřeno na „Ziskovost bank a objem úvěrů v ČR" (geo = CZ): do rerankeru šlo 240
     * kandidátů a 231 jich zahodil s odůvodněním typu „Geography mismatch: Belgium vs
     * requested Czech Republic" nebo „Explicitly Eurozóna (EA20), not Česká republika".
     * Pool byl tedy z 96 % zaplněný řadami, které nemohly projít — a české řady, které projít
     * měly, se do něj vůbec nevešly. Platilo se za ně promptem a měnilo se mezi běhy, které
     * z nich reranker zahodí, což byl zdroj rozptylu ve výsledcích Manager Exploreru.
     *
     * <p><b>Záměrně to není tvrdý předfiltr.</b> {@code
     * SearchV2ServiceRuntimeTest#geoEvidenceReachesSemanticDecisionWithoutPreLlmCandidateRemoval}
     * drží dřívější rozhodnutí, že deterministická geo evidence je pro LLM POradní, ne
     * rozhodující — aby špatný odhad země tiše nezabil dobrého kandidáta a aby funnel
     * v {@code candidate_counts} zůstal poctivý. Strop se proto uplatní až tam, kde je
     * kandidátů víc než {@link #GEO_CONFLICT_POOL_FRACTION}-krát strop poolu; malé pooly
     * projdou beze změny a LLM u nich pořád vidí i nesedící kandidáty.
     */
    static List<SearchCandidate> prioritizeGeoEligible(
            List<SearchCandidate> candidates, SearchQueryPlan plan, int limit) {
        if (candidates == null || candidates.isEmpty() || plan == null || limit <= 0) {
            return candidates == null ? List.of() : candidates;
        }
        List<String> geographies = plan.geographies();
        if (geographies == null || geographies.isEmpty()) {
            return candidates;
        }
        List<SearchCandidate> eligible = new ArrayList<>();
        List<SearchCandidate> conflicting = new ArrayList<>();
        for (SearchCandidate candidate : candidates) {
            if (SearchV2GeoCompatibility.assessCandidateGeo(candidate, geographies, plan).hardConflict()) {
                conflicting.add(candidate);
            } else {
                eligible.add(candidate);
            }
        }
        // Nic k přeuspořádání, nebo by po omezení nezbylo nic — nechat tak, jak přišlo.
        if (conflicting.isEmpty() || eligible.isEmpty()) {
            return candidates;
        }
        int conflictBudget = Math.max(1, limit / GEO_CONFLICT_POOL_FRACTION);
        List<SearchCandidate> ordered = new ArrayList<>(eligible);
        ordered.addAll(conflicting.subList(0, Math.min(conflicting.size(), conflictBudget)));
        return ordered;
    }

    private static void reserveAmbiguousBranchCoverage(
            List<SearchCandidate> candidates,
            int limit,
            SearchQueryPlan plan,
            LinkedHashMap<String, SearchCandidate> selected) {
        int reservationLimit = Math.max(1, limit / 2);
        Map<String, Integer> perSource = new LinkedHashMap<>();
        Map<String, Integer> perVariant = new LinkedHashMap<>();
        for (SearchCandidate candidate : candidates) {
            if (selected.size() >= reservationLimit) {
                break;
            }
            if (SearchV2GeoCompatibility.assessCandidateGeo(candidate, plan.geographies(), plan).hardConflict()) {
                continue;
            }
            String source = candidate.source() == null ? "" : candidate.source().trim().toLowerCase(Locale.ROOT);
            String variant = CatalogTextUtils.normalizeTokenBoundaries(candidate.matchedQuery());
            int sourceCount = perSource.getOrDefault(source, 0);
            int variantCount = perVariant.getOrDefault(variant, 0);
            if (sourceCount >= 3 || variantCount >= 3) {
                continue;
            }
            selected.putIfAbsent(candidate.candidateId(), candidate);
            perSource.put(source, sourceCount + 1);
            perVariant.put(variant, variantCount + 1);
        }
    }

    private static String candidateConceptEvidence(SearchCandidate candidate) {
        if (candidate == null) {
            return "";
        }
        Map<String, Object> raw = candidate.raw() == null ? Map.of() : candidate.raw();
        return String.join(
                " ",
                candidate.title() == null ? "" : candidate.title(),
                candidate.description() == null ? "" : candidate.description(),
                candidate.dataset() == null ? "" : candidate.dataset(),
                String.join(" ", candidate.concepts() == null ? List.of() : candidate.concepts()),
                CatalogMapSupport.str(raw.get("primary_concept")),
                CatalogMapSupport.str(raw.get("canonical_title_cs")),
                CatalogMapSupport.str(raw.get("canonical_title_en")),
                CatalogMapSupport.str(raw.get("original_title")));
    }

    private static int preferredSourceRank(SearchQueryPlan plan, SearchCandidate candidate) {
        if (plan == null || plan.sourceRouting() == null || candidate == null) {
            return Integer.MAX_VALUE;
        }
        List<String> preferred = plan.sourceRouting().preferredSources();
        String source = candidate.source() == null ? "" : candidate.source().trim().toLowerCase(Locale.ROOT);
        for (int index = 0; index < preferred.size(); index++) {
            if (source.equals(String.valueOf(preferred.get(index)).trim().toLowerCase(Locale.ROOT))) {
                return index;
            }
        }
        return preferred.size();
    }

    private static boolean hasStrongTitleEvidence(SearchCandidate candidate) {
        if (candidate == null || candidate.title() == null || candidate.matchedQuery() == null) {
            return false;
        }
        String title = CatalogTextUtils.normalizeTokenBoundaries(candidate.title());
        String geo = CatalogTextUtils.normalizeTokenBoundaries(candidate.geo());
        LinkedHashSet<String> queryTokens = new LinkedHashSet<>();
        for (String token : CatalogTextUtils.normalizeTokenBoundaries(candidate.matchedQuery()).split("\\s+")) {
            if (token.length() >= 2 && !token.equals(geo)) {
                queryTokens.add(token);
            }
        }
        return queryTokens.size() >= 2
                && queryTokens.stream().allMatch(token -> (" " + title + " ").contains(" " + token + " "));
    }

    private SearchQueryPlan plan(
            Map<String, Object> payload,
            String query,
            boolean useAi,
            boolean noCache,
            SearchV2Trace trace,
            String catalogVersion) {
        String key = planCacheKey(payload, query, useAi, catalogVersion);
        if (noCache) {
            trace.put("plan_cache_status", "bypassed");
            return planner.plan(payload);
        }
        Object cached = cacheService.get(key).orElse(null);
        if (cached instanceof SearchQueryPlan searchQueryPlan) {
            trace.put("plan_cache_status", "hit");
            return searchQueryPlan;
        }
        SearchQueryPlan planned = planner.plan(payload);
        if (!useAi || "openai".equals(planned.plannerStatus())) {
            cacheService.put(key, PLAN_TTL, planned);
        }
        trace.put("plan_cache_status", "miss");
        return planned;
    }

    /**
     * catalogVersion je v klici stejne jako u retrieval/final cache (viz {@code finalCacheKey},
     * {@code retrievalCacheKey}) - kdyz se katalog prereindexuje, stary plan pro stejny dotaz uz
     * nedostane cache hit sam od sebe, misto aby az hodinu (drivejsi PLAN_TTL) cekal na vyprseni.
     */
    static String planCacheKey(Map<String, Object> payload, String query, boolean useAi, String catalogVersion) {
        return "plan:" + cacheScope(payload) + ":cv=" + catalogVersion + ":ai=" + useAi + ":geo="
                + normalized(CatalogMapSupport.firstNonBlank(payload.get("selected_geo"), payload.get("geo"), payload.get("country"), ""))
                + ":" + normalized(query);
    }

    private Map<String, Object> baseResponse(
            String query, SearchQueryPlan plan, SearchV2Trace trace, String catalogVersion) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("search_engine", "v2");
        out.put("query", query);
        out.put("query_plan", plan == null ? null : plan.toMap());
        out.put("llm_planner", plan == null ? Map.of() : plan.llmPlannerTrace());
        out.put("fallback_trace", plan == null ? Map.of() : plan.fallbackTrace());
        out.put("trace_id", trace.traceId());
        out.put("catalog_version", catalogVersion);
        out.put("timings", trace.snapshot().get("timings"));
        out.put("cache_hit", false);
        return out;
    }

    private static List<String> allowedSources(
            SearchQueryPlan plan, Map<String, Object> payload, SearchV2SectorRoutingGuard.Assessment sectorAssessment) {
        List<String> fromRequest = readSources(payload.get("sources"));
        if (fromRequest.isEmpty()) {
            fromRequest = readSources(payload.get("catalog"));
        }
        if (!fromRequest.isEmpty()) {
            return prioritizeAllowedSources(fromRequest, plan);
        }
        if (plan.explicitSources() != null && !plan.explicitSources().isEmpty()) {
            return plan.explicitSources();
        }
        List<String> preferred = plan.sourceRouting() != null && plan.sourceRouting().preferredSources() != null
                ? plan.sourceRouting().preferredSources()
                : List.of();
        if (preferred.isEmpty()) {
            return defaultSearchSources();
        }
        // Conditional safety fan-out (SearchV2SectorRoutingGuard): concept-driven routing is otherwise
        // trusted as-is. Widening only happens when the concept is unknown/unregistered, conflicts with
        // an institutional sector the user's own wording explicitly names, or leaves that sector
        // unaddressed entirely - a correctly-routed bank query (or any query with no sector at stake)
        // never reaches this branch and keeps its existing narrow, fast, precise source list. No static
        // per-source institutional-sector metadata exists today (SourceCapabilityRegistry only tracks
        // catalog_families/entity_types - see the coverage audit), so the "sector-compatible sources"
        // tier is the full default source list, reordered with the planner's own preferences first via
        // prioritizeAllowedSources - the same list the coverage audit confirmed actually surfaces
        // correctly-tagged sector data once the concept-driven narrowing is bypassed.
        if (sectorAssessment != null && sectorAssessment.fanOutTriggered()) {
            return prioritizeAllowedSources(defaultSearchSources(), plan);
        }
        return preferred;
    }

    private static List<String> vectorAllowedSources(
            SearchQueryPlan plan, Map<String, Object> payload, List<String> allowedSources) {
        boolean requestConstrained = !readSources(payload.get("sources")).isEmpty()
                || !readSources(payload.get("catalog")).isEmpty();
        boolean queryConstrained = plan.explicitSources() != null && !plan.explicitSources().isEmpty();
        return requestConstrained || queryConstrained ? allowedSources : List.of();
    }

    static List<String> prioritizeAllowedSources(List<String> allowed, SearchQueryPlan plan) {
        if (allowed == null || allowed.size() <= 1 || plan == null || plan.sourceRouting() == null) {
            return allowed == null ? List.of() : allowed;
        }
        List<String> ordered = new ArrayList<>();
        for (String preferred : plan.sourceRouting().preferredSources()) {
            if (allowed.contains(preferred) && !ordered.contains(preferred)) {
                ordered.add(preferred);
            }
        }
        for (String source : defaultSearchSources()) {
            if (!ordered.contains(source)) {
                if (allowed.contains(source)) {
                    ordered.add(source);
                }
            }
        }
        allowed.stream()
                .filter(source -> !ordered.contains(source))
                .sorted()
                .forEach(ordered::add);
        return ordered;
    }

    private boolean exactRetrievalSucceeded(SearchQueryPlan plan, List<SearchResult> results) {
        if (plan == null || !plan.highConfidenceExactEntity()) {
            return false;
        }
        return (results == null ? List.<SearchResult>of() : results).stream()
                .limit(3)
                .anyMatch(result -> exactEntityScorer.exactScore(plan.entityResolution(), result.candidate()) >= 0.82);
    }

    private static List<String> defaultSearchSources() {
        return SearchV2QueryPlanner.ALL_SEARCH_V2_SOURCES.stream()
                .filter(source -> !"stocks".equals(source))
                .toList();
    }

    private static List<String> readSources(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addSource(out, item);
            }
        } else {
            for (String part : String.valueOf(raw).split(",")) {
                addSource(out, part);
            }
        }
        return out;
    }

    private static void addSource(List<String> out, Object raw) {
        String normalized = CatalogSourceRegistry.normalizeSearchSource(String.valueOf(raw));
        if (!normalized.isBlank()
                && SearchV2QueryPlanner.ALL_SEARCH_V2_SOURCES.contains(normalized)
                && !out.contains(normalized)) {
            out.add(normalized);
        }
    }

    private static List<SearchCandidate> join(List<SearchCandidate> left, List<SearchCandidate> right) {
        List<SearchCandidate> out = new ArrayList<>();
        if (left != null) {
            out.addAll(left);
        }
        if (right != null) {
            out.addAll(right);
        }
        return out;
    }

    private static List<Map<String, Object>> compactCandidates(List<SearchCandidate> candidates, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        int rank = 1;
        for (SearchCandidate candidate : (candidates == null ? List.<SearchCandidate>of() : candidates).stream()
                .limit(Math.max(1, limit))
                .toList()) {
            Map<String, Object> raw = candidate.raw() == null ? Map.of() : candidate.raw();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank++);
            row.put("source", candidate.source());
            row.put("series_id", candidate.seriesId());
            row.put("dataset", candidate.dataset());
            row.put("title", candidate.title());
            row.put("description", candidate.description());
            row.put("geo", candidate.geo());
            row.put("unit", candidate.unit());
            row.put("frequency", candidate.frequency());
            row.put("fts_score", candidate.ftsScore());
            row.put("matched_query", candidate.matchedQuery());
            row.put("matched_fields", raw.getOrDefault("_matched_fields", candidate.matchedFields()));
            row.put("original_title", raw.get("original_title"));
            row.put("canonical_title_cs", raw.get("canonical_title_cs"));
            row.put("canonical_title_en", raw.get("canonical_title_en"));
            row.put("primary_concept", raw.get("primary_concept"));
            row.put("metadata_quality_score", raw.get("metadata_quality_score"));
            row.put("catalog_index", raw.get("_catalog_index"));
            row.put("retrieval_lanes", raw.get("_retrieval_lanes"));
            row.put("rrf_score", raw.get("_rrf_score"));
            row.put("vector_rank", raw.get("_vector_rank"));
            row.put("vector_score", raw.get("_vector_score"));
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> vectorDiagnostics(List<Map<String, Object>> queryStats) {
        for (Map<String, Object> stat : queryStats == null ? List.<Map<String, Object>>of() : queryStats) {
            if ("_vector".equals(CatalogMapSupport.str(stat.get("source")))) {
                return new LinkedHashMap<>(stat);
            }
        }
        return Map.of(
                "vector_enabled", false,
                "vector_available", false,
                "embedding_model", "",
                "embedding_ms", 0,
                "vector_search_ms", 0,
                "fts_candidate_count", 0,
                "vector_candidate_count", 0,
                "vector_only_count", 0,
                "fusion_candidate_count", 0);
    }

    private static void putVectorTraceFields(SearchV2Trace trace, Map<String, Object> diagnostics) {
        for (String field : List.of(
                "vector_enabled",
                "vector_available",
                "embedding_model",
                "fts_candidate_count",
                "vector_candidate_count",
                "vector_only_count",
                "fusion_candidate_count")) {
            trace.put(field, diagnostics.get(field));
        }
    }

    private static List<SearchResult> renumber(List<SearchResult> results) {
        List<SearchResult> out = new ArrayList<>();
        int rank = 1;
        for (SearchResult result : results == null ? List.<SearchResult>of() : results) {
            out.add(new SearchResult(result.candidate(), result.decision(), rank++));
        }
        return out;
    }

    static List<SearchResult> selectFinalResults(
            List<SearchResult> ranked,
            List<SearchResult> previewAccepted,
            boolean previewIsTechnicalGate,
            int limit) {
        List<SearchResult> selected = previewIsTechnicalGate
                ? (previewAccepted == null ? List.of() : previewAccepted)
                : (ranked == null ? List.of() : ranked);
        return renumber(selected.stream().limit(Math.max(1, limit)).toList());
    }

    /**
     * PR-7: attaches an explicit {@code preview_outcome} (verified/timeout/cancelled/failed/possible)
     * to every checked candidate's raw status, without mutating the original maps (some of which are
     * shared with {@code SearchV2CacheService} entries). This is the diagnostic feed that survives
     * regardless of {@code selectFinalResults}'s accept-gate — see the PR-7 completion report for why
     * `results`/`verified`/`possible` themselves are not changed by this PR.
     */
    private static List<Map<String, Object>> withPreviewOutcome(List<Map<String, Object>> statuses) {
        if (statuses == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(statuses.size());
        for (Map<String, Object> status : statuses) {
            Map<String, Object> enriched = new LinkedHashMap<>(status);
            enriched.put("preview_outcome", SearchV2PreviewOutcome.classify(status));
            out.add(enriched);
        }
        return out;
    }

    private static boolean unverifiedResultsEnabled() {
        return BankIntelEnvVars.isTruthy("SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED");
    }

    /**
     * PR-7b: candidates from {@code ranked} whose preview outcome falls in
     * {@link SearchV2PreviewOutcome#isUnverifiedBucket} - relevant, but not confirmed - and that are
     * not already present in {@code alreadyIncluded} (so nothing is ever reported twice, and modes
     * where {@code finalResults} already includes everything from {@code ranked}, e.g.
     * {@code top_preview}/{@code metadata_only}, correctly produce an empty list here: nothing was
     * actually lost in those modes). Confirmed-{@code empty}/{@code invalid} candidates
     * ({@link SearchV2PreviewOutcome#isRejectedBucket}) are deliberately excluded - the "rejected"
     * bucket, per the PR-7b response model. Order matches {@code ranked} exactly (pre-preview
     * relevance order); this method never re-sorts or re-scores.
     */
    private List<Map<String, Object>> buildUnverifiedResults(
            List<SearchResult> ranked,
            SearchV2PreviewVerifier.VerificationResult verification,
            List<Map<String, Object>> alreadyIncluded) {
        if (ranked == null || ranked.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> includedKeys = alreadyIncluded.stream()
                .map(row -> previewKey(row.get("source"), row.get("series_id")))
                .collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (SearchResult result : ranked) {
            String key = previewKey(result.candidate().source(), result.candidate().seriesId());
            if (includedKeys.contains(key)) {
                continue;
            }
            Map<String, Object> preview = previewStatusFor(result, verification);
            String outcome = SearchV2PreviewOutcome.classify(preview);
            if (!SearchV2PreviewOutcome.isUnverifiedBucket(outcome)) {
                continue;
            }
            Map<String, Object> row = resultMapWithPreview(result, verification);
            row.put("verified", false);
            row.put("preview_reason", CatalogMapSupport.str(preview.get("reason")));
            Object transportType = preview.get("transport_type");
            if (transportType != null) {
                row.put("transport_type", transportType);
            }
            out.add(row);
        }
        return out;
    }

    /**
     * PR-7c: {@code NOT_CHECKED} is legitimate whenever a candidate was never even sent to preview -
     * either because the whole request runs in {@code metadata_only} mode (preview is deliberately
     * skipped for everyone), or because the candidate ranked below the small scan window
     * {@code SearchV2PreviewVerifier.verify(...)} actually dispatches (it stops as soon as enough
     * accepted candidates are found, so plenty of lower-ranked candidates in {@code ranked} are simply
     * never checked at all - that is by design, not a bug).
     *
     * <p>What should never happen: a candidate that WAS part of the checked prefix - i.e. at an index
     * strictly less than {@code verification.checkedCount()}, since {@code verify(...)} always adds
     * one status entry per checked candidate in that exact prefix order - still classifying as
     * {@code NOT_CHECKED}. That would mean {@code previewStatusFor}'s source+series_id matching failed
     * to find an entry that must exist. This method only ever emits a diagnostic telemetry event for
     * that specific, narrow anomaly; it never adds anything to {@code unverified} on the strength of
     * this check alone - the instruction is explicit that NOT_CHECKED must not be reclassified without
     * proof of correct semantics.
     */
    private void warnIfCheckedCandidateIsUnclassifiable(
            List<SearchResult> ranked,
            SearchV2PreviewVerifier.VerificationResult verified,
            String previewMode,
            SearchV2Trace trace) {
        if ("metadata_only".equals(previewMode) || ranked == null || verified == null || verified.checkedCount() <= 0) {
            return;
        }
        int checkedPrefix = Math.min(ranked.size(), verified.checkedCount());
        List<String> anomalies = new ArrayList<>();
        for (int i = 0; i < checkedPrefix; i++) {
            SearchResult result = ranked.get(i);
            Map<String, Object> preview = previewStatusFor(result, verified);
            if (SearchV2PreviewOutcome.NOT_CHECKED.equals(SearchV2PreviewOutcome.classify(preview))) {
                anomalies.add(result.candidate().source() + ":" + result.candidate().seriesId());
            }
        }
        if (anomalies.isEmpty()) {
            return;
        }
        trace.event(
                "preview_not_checked_invariant_violation",
                "Candidate(s) within the checked preview prefix classified as NOT_CHECKED: " + anomalies);
        if (telemetryWriter.enabled()) {
            telemetryWriter.submitRaw(Map.of(
                    "schema_version", "1",
                    "timestamp_ms", System.currentTimeMillis(),
                    "event_type", "preview_not_checked_invariant_violation",
                    "candidates", anomalies,
                    "checked_count", verified.checkedCount()));
        }
    }

    private Map<String, Object> resultMapWithPreview(
            SearchResult result,
            SearchV2PreviewVerifier.VerificationResult verification) {
        Map<String, Object> row = new LinkedHashMap<>(result.toMap());
        Map<String, Object> preview = previewStatusFor(result, verification);
        if (preview == null || preview.isEmpty()) {
            row.putIfAbsent("preview_available", false);
            row.putIfAbsent("preview_checked", false);
            row.put("preview_outcome", SearchV2PreviewOutcome.NOT_CHECKED);
            return canonicalMetadataService.enrich(row);
        }
        boolean ok = Boolean.TRUE.equals(preview.get("ok"));
        int rows = CatalogMapSupport.toInt(preview.get("rows"), 0);
        String state = CatalogMapSupport.str(preview.get("preview_state"));
        row.put("preview_checked", true);
        row.put("preview_available", ok);
        row.put("preview_row_count", rows);
        copyPreviewMap(row, preview, "preview_payload");
        copyPreviewMap(row, preview, "preview_request_payload");
        Map<String, Object> queryParams = reusableQueryParams(preview);
        if (!queryParams.isEmpty()) {
            row.put("query_params", queryParams);
        }
        if (!state.isBlank()) {
            row.put("preview_state", state);
        }
        // PR-7: explicit, never-ambiguous classification — a timeout or cancellation is never reported
        // the same way as "this series does not exist" (see SearchV2PreviewOutcome for the full model).
        row.put("preview_outcome", SearchV2PreviewOutcome.classify(preview));
        promoteEcbPreviewContext(row, result, preview);
        if (ok) {
            refreshLifecycleFromPreview(row, preview);
            row.put("preview_status", "verified");
            row.put("status", "verified");
            row.put("result_tier", "verified");
        } else {
            row.put("preview_status", "unverified");
            row.put("status", "candidate");
            String reason = CatalogMapSupport.str(preview.get("reason"));
            if (!reason.isBlank()) {
                row.put("preview_error", reason);
            }
        }
        return canonicalMetadataService.enrich(row);
    }

    static void refreshLifecycleFromPreview(Map<String, Object> row, Map<String, Object> preview) {
        Map<String, Object> payload = mapValue(preview.get("preview_payload"));
        String latestPeriod = latestObservedPeriod(payload);
        if (latestPeriod.isBlank()) {
            return;
        }
        row.put("latest_period", latestPeriod);
        String frequency = CatalogMapSupport.firstNonBlank(row.get("frequency"), row.get("freq"));
        if (frequency.isBlank()) {
            frequency = previewFrequency(payload);
        }
        Map<String, Object> enriched = SearchSeriesLifecycleClassifier.enrich(row, frequency);
        for (String key : List.of("lifecycle_status", "lifecycle_reason", "lifecycle_confidence", "latest_period")) {
            if (enriched.containsKey(key)) {
                row.put(key, enriched.get(key));
            }
        }
    }

    static String latestObservedPeriod(Map<String, Object> payload) {
        Object rowsValue = payload.get("rows");
        if (!(rowsValue instanceof List<?> rows)) {
            return "";
        }
        String latest = "";
        int latestYear = Integer.MIN_VALUE;
        for (Object value : rows) {
            if (!(value instanceof Map<?, ?> rawRow)) {
                continue;
            }
            Map<String, Object> row = CatalogMapSupport.castMap(rawRow);
            if (!hasObservedValue(row)) {
                continue;
            }
            String period = firstPresent(row, PERIOD_FIELDS);
            int year = periodYear(period);
            if (year > latestYear || (year == latestYear && period.compareTo(latest) > 0)) {
                latest = period;
                latestYear = year;
            }
        }
        return latest;
    }

    private static boolean hasObservedValue(Map<String, Object> row) {
        for (String key : VALUE_FIELDS) {
            if (!row.containsKey(key)) {
                continue;
            }
            Object value = row.get(key);
            if (value instanceof Number) {
                return true;
            }
            String text = CatalogMapSupport.str(value);
            if (!text.isBlank() && !"null".equalsIgnoreCase(text) && !"nan".equalsIgnoreCase(text)) {
                return true;
            }
        }
        return false;
    }

    private static String previewFrequency(Map<String, Object> payload) {
        Object rowsValue = payload.get("rows");
        if (!(rowsValue instanceof List<?> rows) || rows.isEmpty() || !(rows.getFirst() instanceof Map<?, ?> first)) {
            return "";
        }
        Map<String, Object> row = CatalogMapSupport.castMap(first);
        return CatalogMapSupport.firstNonBlank(row.get("frequency"), row.get("freq"), row.get("FREQ"));
    }

    private static String firstPresent(Map<String, Object> row, List<String> keys) {
        for (String key : keys) {
            String value = CatalogMapSupport.str(row.get(key));
            if (!value.isBlank() && !"null".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return "";
    }

    private static int periodYear(String period) {
        Matcher matcher = PERIOD_YEAR_PATTERN.matcher(period == null ? "" : period);
        int latest = Integer.MIN_VALUE;
        while (matcher.find()) {
            latest = Math.max(latest, Integer.parseInt(matcher.group(1)));
        }
        return latest;
    }

    private static void promoteEcbPreviewContext(
            Map<String, Object> row, SearchResult result, Map<String, Object> preview) {
        String source = result != null && result.candidate() != null
                ? CatalogMapSupport.str(result.candidate().source()).toLowerCase(Locale.ROOT)
                : "";
        if (!source.equals("ecb") && !source.equals("ecb2")) {
            return;
        }
        Map<String, Object> requestPayload = mapValue(preview.get("preview_request_payload"));
        String country = CatalogMapSupport.str(requestPayload.get("country")).toUpperCase(Locale.ROOT);
        if (country.isBlank()) {
            return;
        }
        row.putIfAbsent("country", country);
        row.putIfAbsent("ecb_country", country);
        Map<String, Object> qp = row.get("query_params") instanceof Map<?, ?> map
                ? new LinkedHashMap<>(CatalogMapSupport.castMap(map))
                : new LinkedHashMap<>();
        qp.putIfAbsent("country", country);
        row.put("query_params", qp);
    }

    private static void copyPreviewMap(Map<String, Object> row, Map<String, Object> preview, String key) {
        if (preview.get(key) instanceof Map<?, ?> map && !map.isEmpty()) {
            row.put(key, new LinkedHashMap<>(CatalogMapSupport.castMap(map)));
        }
    }

    private static Map<String, Object> reusableQueryParams(Map<String, Object> preview) {
        Map<String, Object> direct = mapValue(preview.get("query_params"));
        if (!direct.isEmpty()) {
            return direct;
        }
        Map<String, Object> previewPayload = mapValue(preview.get("preview_payload"));
        direct = mapValue(previewPayload.get("query_params"));
        if (!direct.isEmpty()) {
            return direct;
        }
        direct = mapValue(previewPayload.get("filters_used"));
        if (!direct.isEmpty()) {
            return direct;
        }
        Map<String, Object> requestPayload = mapValue(preview.get("preview_request_payload"));
        return mapValue(requestPayload.get("query_params"));
    }

    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            return new LinkedHashMap<>(CatalogMapSupport.castMap(map));
        }
        return Map.of();
    }

    private static Map<String, Object> previewStatusFor(
            SearchResult result,
            SearchV2PreviewVerifier.VerificationResult verification) {
        if (result == null || verification == null || verification.statuses() == null) {
            return Map.of();
        }
        String key = previewKey(result.candidate().source(), result.candidate().seriesId());
        Map<String, Object> fallback = Map.of();
        for (Map<String, Object> status : verification.statuses()) {
            if (!key.equals(previewKey(status.get("source"), status.get("series_id")))) {
                continue;
            }
            if (Boolean.TRUE.equals(status.get("ok"))) {
                return status;
            }
            fallback = status;
        }
        return fallback;
    }

    private static String previewKey(Object source, Object seriesId) {
        return CatalogMapSupport.str(source).toLowerCase(Locale.ROOT)
                + "::"
                + CatalogMapSupport.str(seriesId).toLowerCase(Locale.ROOT);
    }

    private static boolean previewVerified(Map<String, Object> row) {
        if (row == null) {
            return false;
        }
        boolean statusVerified = "verified".equalsIgnoreCase(CatalogMapSupport.str(row.get("status")))
                || "verified".equalsIgnoreCase(CatalogMapSupport.str(row.get("preview_status")));
        boolean available = Boolean.TRUE.equals(row.get("preview_available"))
                || CatalogMapSupport.toInt(row.get("preview_row_count"), 0) > 0;
        return statusVerified && available;
    }

    private static List<String> joinStrings(List<String> left, List<String> right) {
        List<String> out = new ArrayList<>();
        if (left != null) {
            out.addAll(left);
        }
        if (right != null) {
            out.addAll(right);
        }
        return out.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
    }

    private static List<Map<String, Object>> joinMaps(List<Map<String, Object>> left, List<Map<String, Object>> right) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (left != null) {
            out.addAll(left);
        }
        if (right != null) {
            out.addAll(right);
        }
        return out;
    }

    private static int coverageRank(SearchV2CoverageChecker.CoverageResult coverage) {
        return switch (coverage.status()) {
            case "complete" -> 3;
            case "partial" -> 2;
            default -> 1;
        };
    }

    private static boolean retryImproves(
            SearchV2CoverageChecker.CoverageResult initialCoverage,
            SearchV2CoverageChecker.CoverageResult retryCoverage,
            List<SearchResult> initialResults,
            List<SearchResult> retryResults) {
        int initialRank = coverageRank(initialCoverage);
        int retryRank = coverageRank(retryCoverage);
        if (retryRank != initialRank) {
            return retryRank > initialRank;
        }
        return resultQuality(retryResults) > resultQuality(initialResults) + 0.001;
    }

    private static double resultQuality(List<SearchResult> results) {
        List<SearchResult> safeResults = results == null ? List.of() : results;
        long primaryCount = safeResults.stream().filter(result -> "primary".equals(result.role())).count();
        double bestPrimary = safeResults.stream()
                .filter(result -> "primary".equals(result.role()))
                .mapToDouble(result -> result.decision().relevanceScore())
                .max()
                .orElse(0.0);
        return primaryCount * 10.0 + bestPrimary + Math.min(safeResults.size(), 10) * 0.01;
    }

    private static Map<String, Object> droppedSummary(List<SemanticDecision> decisions) {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        int count = 0;
        for (SemanticDecision decision : decisions == null ? List.<SemanticDecision>of() : decisions) {
            if (!"drop".equalsIgnoreCase(decision.decision())) {
                continue;
            }
            count++;
            String key = decision.semanticConflicts().isEmpty() ? "semantic_drop" : decision.semanticConflicts().get(0);
            reasons.put(key, reasons.getOrDefault(key, 0) + 1);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", count);
        out.put("main_reasons", reasons.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(e -> Map.of("reason", e.getKey(), "count", e.getValue()))
                .toList());
        return out;
    }

    private static Map<String, Object> sourceStats(List<Map<String, Object>> queryStats) {
        Map<String, Map<String, Object>> bySource = new LinkedHashMap<>();
        for (Map<String, Object> stat : queryStats == null ? List.<Map<String, Object>>of() : queryStats) {
            String source = CatalogMapSupport.str(stat.get("source"));
            if (source.isBlank()) {
                continue;
            }
            Map<String, Object> bucket = bySource.computeIfAbsent(source, ignored -> new LinkedHashMap<>());
            bucket.put("source", source);
            bucket.put("queries", CatalogMapSupport.toInt(bucket.get("queries"), 0) + 1);
            bucket.put("count", CatalogMapSupport.toInt(bucket.get("count"), 0) + CatalogMapSupport.toInt(stat.get("count"), 0));
            bucket.put("ok", !Boolean.FALSE.equals(bucket.get("ok")) && Boolean.TRUE.equals(stat.get("ok")));
        }
        return Map.of("sources", new ArrayList<>(bySource.values()));
    }

    private static Map<String, Object> candidateCounts(
            SearchV2FtsRetriever.RetrievalResult rawRetrieval,
            SearchV2FtsRetriever.RetrievalResult retrieval,
            int rerankPoolSize,
            SearchV2SemanticValidator.ValidationResult validation,
            SearchV2PreviewVerifier.VerificationResult verified,
            int finalResultCount) {
        List<SearchCandidate> constrainedPreMerge = retrieval == null ? List.of() : retrieval.preMergeCandidates();
        List<SearchCandidate> constrainedMerged = retrieval == null ? List.of() : retrieval.candidates();
        List<SearchCandidate> rawPreMerge = rawRetrieval == null ? List.of() : rawRetrieval.preMergeCandidates();
        if (rawPreMerge.isEmpty() && !constrainedPreMerge.isEmpty()) {
            rawPreMerge = constrainedPreMerge;
        }
        boolean llmRerankUsed = validation != null
                && List.of("validated", "partial").contains(String.valueOf(validation.status()).toLowerCase(Locale.ROOT));
        int previewChecked = verified == null ? 0 : verified.checkedCount();
        int previewSuccess = previewChecked <= 0 || verified == null ? 0 : (int) verified.statuses().stream()
                .filter(status -> Boolean.TRUE.equals(status.get("ok")))
                .count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("retrieved_raw", rawPreMerge.size());
        out.put("deduplicated_unique", uniqueCandidateCount(rawPreMerge));
        out.put("after_hard_constraints", uniqueCandidateCount(constrainedPreMerge));
        out.put("after_source_balancing", constrainedMerged.size());
        out.put("after_candidate_limit", constrainedMerged.size());
        out.put("sent_to_deterministic_reranker", llmRerankUsed ? 0 : rerankPoolSize);
        out.put("sent_to_llm_reranker", llmRerankUsed ? rerankPoolSize : 0);
        out.put("sent_to_preview", previewChecked);
        out.put("preview_success", previewSuccess);
        out.put("preview_failed", Math.max(0, previewChecked - previewSuccess));
        out.put("unique_preview_requests", verified == null ? 0 : verified.uniqueRequestCount());
        out.put("final_results", finalResultCount);
        out.put("candidate_limit_note", "source_balancing_and_candidate_limit_are_applied_in_one_merge_step");
        return out;
    }

    private static int uniqueCandidateCount(List<SearchCandidate> candidates) {
        return (int) (candidates == null ? List.<SearchCandidate>of() : candidates).stream()
                .filter(candidate -> candidate.seriesId() != null && !candidate.seriesId().isBlank())
                .map(candidate -> (candidate.source() + ":" + candidate.seriesId()).toLowerCase(Locale.ROOT))
                .distinct()
                .count();
    }

    private static boolean fallbackToLegacy(String requestedMode, String actualMode) {
        return SearchCatalogSidecarIndex.MODE_SIDECAR.equalsIgnoreCase(requestedMode)
                && SearchCatalogSidecarIndex.MODE_LEGACY.equalsIgnoreCase(actualMode);
    }

    private static boolean semanticRetrievalEnabled() {
        return BankIntelEnvVars.isTruthy("SEARCH_SEMANTIC_RETRIEVAL_ENABLED");
    }

    private static Map<String, Object> candidateLimits() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("retrieval_per_variant", 25);
        out.put("retrieval_pool_after_merge", 240);
        out.put("reranker_max_candidates", maxRerankCandidates());
        out.put("preview_top_n_default", configuredPreviewTopN());
        out.put("preview_max_verify", MAX_PREVIEW_VERIFY);
        out.put("final_result_default", DEFAULT_LIMIT);
        out.put("final_result_max", MAX_LIMIT);
        return out;
    }

    private static boolean retrievalInfrastructureFailed(SearchV2FtsRetriever.RetrievalResult retrieval) {
        if (retrieval == null || !retrieval.candidates().isEmpty()) {
            return false;
        }
        List<Map<String, Object>> stats = retrieval.queryStats();
        return stats != null && !stats.isEmpty() && stats.stream().noneMatch(stat -> Boolean.TRUE.equals(stat.get("ok")));
    }

    static boolean retrievalHadFailures(SearchV2FtsRetriever.RetrievalResult retrieval) {
        if (retrieval == null || retrieval.queryStats() == null) {
            return true;
        }
        return retrieval.queryStats().stream()
                .filter(stat -> !"_vector".equals(CatalogMapSupport.str(stat.get("source"))))
                .anyMatch(stat -> Boolean.FALSE.equals(stat.get("ok")));
    }

    private static boolean semanticFallbackAlreadyHasResults(String status, List<SearchResult> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            return false;
        }
        return "disabled".equalsIgnoreCase(status) || "unavailable".equalsIgnoreCase(status);
    }

    private static String finalCacheKey(
            Map<String, Object> payload,
            String query,
            String catalogVersion,
            String catalogIndexMode,
            long sidecarRevision) {
        if (query == null || query.isBlank() || truthy(payload.get("no_cache")) || truthy(payload.get("debug"))) {
            return "";
        }
        return "final:"
                + catalogVersion
                + ":index="
                + catalogIndexMode
                + sidecarRevisionSuffix(catalogIndexMode, sidecarRevision)
                + ":"
                + finalCacheScope(payload)
                + ":"
                + normalized(query);
    }

    private static String sidecarRevisionSuffix(String catalogIndexMode, long sidecarRevision) {
        return SearchCatalogSidecarIndex.MODE_SIDECAR.equalsIgnoreCase(catalogIndexMode)
                ? ":revision=" + sidecarRevision
                : "";
    }

    /** Include every request option that can change ranking, verification or response cardinality. */
    private static String finalCacheScope(Map<String, Object> payload) {
        List<String> keys = List.of(
                "sources",
                "catalog",
                "source",
                "scope",
                "mode",
                "search_profile",
                "use_ai",
                "use_ai_story",
                "preview_mode",
                "eval_mode",
                "preview_top_n",
                "limit",
                "limit_per_source",
                "geo",
                "geographies",
                "country",
                "countries",
                "filters");
        List<String> parts = new ArrayList<>();
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null) {
                parts.add(key + "=" + normalized(String.valueOf(value)));
            }
        }
        if (parts.stream().noneMatch(part -> part.startsWith("use_ai="))) {
            parts.add("use_ai=true");
        }
        if (parts.stream().noneMatch(part -> part.startsWith("use_ai_story="))) {
            parts.add("use_ai_story=true");
        }
        if (parts.stream().noneMatch(part -> part.startsWith("preview_mode=") || part.startsWith("eval_mode="))) {
            parts.add("preview_mode=full");
        }
        if (parts.stream().noneMatch(part -> part.startsWith("limit="))) {
            parts.add("limit=" + DEFAULT_LIMIT);
        }
        return String.join("|", parts);
    }

    private static String cacheScope(Map<String, Object> payload) {
        return normalized(CatalogMapSupport.firstNonBlank(payload.get("sources"), payload.get("catalog"), payload.get("source"), "all"));
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(MAX_LIMIT, limit <= 0 ? DEFAULT_LIMIT : limit));
    }

    private static int rankWindow(int limit) {
        return Math.max(limit * 6, MAX_PREVIEW_VERIFY * 3);
    }

    private static long ftsTimeoutMs(Map<String, Object> payload) {
        int configured = CatalogMapSupport.toInt(payload.get("fts_timeout_ms"), 0);
        if (configured > 0) {
            return Math.max(500, Math.min(20_000, configured));
        }
        String mode = CatalogMapSupport.firstNonBlank(payload.get("eval_mode"), payload.get("preview_mode"));
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "metadata_only" -> 4_000;
            case "top_preview" -> 6_000;
            default -> 20_000;
        };
    }

    private static PreviewPolicy previewPolicy(Map<String, Object> payload, int resultLimit) {
        String raw = CatalogMapSupport.firstNonBlank(
                payload.get("eval_mode"),
                payload.get("preview_mode"),
                payload.get("SEARCH_EVAL_MODE"),
                "full");
        String mode = raw.trim().toLowerCase(Locale.ROOT);
        if (!List.of("metadata_only", "top_preview", "full").contains(mode)) {
            mode = "full";
        }
        int requestedTopN = CatalogMapSupport.toInt(payload.get("preview_top_n"), configuredPreviewTopN());
        int verifyLimit = switch (mode) {
            case "metadata_only" -> 0;
            case "top_preview" -> Math.max(1, Math.min(MAX_PREVIEW_VERIFY, requestedTopN));
            default -> Math.min(resultLimit, MAX_PREVIEW_VERIFY);
        };
        return new PreviewPolicy(mode, verifyLimit);
    }

    private static int configuredPreviewTopN() {
        int configured = parseIntEnv("SEARCH_PREVIEW_TOP_N", 5);
        return Math.max(1, Math.min(MAX_PREVIEW_VERIFY, configured));
    }

    private static int parseIntEnv(String name, int fallback) {
        String raw = BankIntelEnvVars.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private record PreviewPolicy(String mode, int verifyLimit) {
        boolean usesPreviewAsGate() {
            return "full".equals(mode);
        }
    }

    private static boolean truthy(Object raw) {
        if (raw == null) {
            return false;
        }
        return List.of("1", "true", "yes", "on").contains(String.valueOf(raw).trim().toLowerCase(Locale.ROOT));
    }

    private static boolean parseBoolean(Object raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (List.of("0", "false", "no", "off").contains(value)) {
            return false;
        }
        if (List.of("1", "true", "yes", "on").contains(value)) {
            return true;
        }
        return fallback;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Package-private (not private): unit-tested directly in SearchV2ServiceAiFlagsTest against raw
     * request maps. "use_ai" is preserved unchanged for the planner - existing callers (including the
     * production frontend, which hardcodes use_ai=true) keep today's planner behavior exactly.
     */
    static boolean resolveUseAiPlanner(Map<String, Object> payload) {
        return parseBoolean(payload.get("use_ai_planner"), parseBoolean(payload.get("use_ai"), true));
    }

    /**
     * Package-private (not private): unit-tested directly in SearchV2ServiceAiFlagsTest. AI reranker
     * The original A/B experiment concluded NO-GO for running by default on AGGREGATE gold-set MRR
     * (0.520 -> 0.433, Recall@5 6/10 -> 5/10, median +8.2s latency) - but that aggregate hid a real
     * failure mode for topics the fallback (non-AI) scorer cannot disambiguate at all: FX pairs and
     * commodities (confirmed live for "eurusd" and "cena zlata"/"gold price"), where the fallback's
     * bag-of-words match ties dozens of unrelated candidates at an identical score (e.g. a bank
     * loan denominated in EUR scores identically to the actual EUR/USD rate) and the tie is broken
     * by an essentially arbitrary downstream signal. Re-enabled by default (2026-07-31, explicit
     * user decision after reviewing both the aggregate regression and this failure mode) - the
     * latency cost is accepted as the trade for not returning topically-wrong results. Still
     * independent of "use_ai" (the planner setting) and still overridable via "use_ai_reranker".
     */
    static boolean resolveUseAiReranker(Map<String, Object> payload) {
        return parseBoolean(payload.get("use_ai_reranker"), true);
    }
}
