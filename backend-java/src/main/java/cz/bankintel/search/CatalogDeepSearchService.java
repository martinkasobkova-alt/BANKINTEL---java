package cz.bankintel.search;

import cz.bankintel.explore.ExploreManagerDiscoveryTerms;
import cz.bankintel.search.model.CatalogHit;
import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.model.GeoIntentSnapshot;
import cz.bankintel.search.model.SearchPlan;
import cz.bankintel.search.scoring.CatalogScoringPipeline;
import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarIndex;
import cz.bankintel.service.research.WebResearchService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CatalogDeepSearchService {

    private static final Logger log = LoggerFactory.getLogger(CatalogDeepSearchService.class);
    private static final ExecutorService LANE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final List<String> CZ_ONLY_SOURCES = List.of("arad", "csu");
    private static final int FOREIGN_GEO_MAX_SOURCES = 4;
    // With parallel per-term FTS, depth/term-count barely affect latency (per-query cost is the MATCH scan, not
    // the LIMIT), so keep them generous for recall.
    private static final int LANE_RETRIEVAL_DEPTH = 40;
    private static final int LANE_PREVIEW_CANDIDATE_FLOOR = 18;
    private static final int LANE_PREVIEW_CANDIDATE_MULTIPLIER = 3;
    private static final int MAX_LANE_TERMS = 5;
    /** Manager Explorer reserves a few slots for macro/production probes on top of planner terms. */
    private static final int MANAGER_MAX_LANE_TERMS = 13;
    private static final long LANE_WALL_BUDGET_MS = 22_000;
    private static final long LANE_TIMEOUT_GRACE_MS = 4_000;
    private static final long LANE_TERM_WALL_BUDGET_MS = 17_000;
    private static final long LANE_TERM_WALL_BUDGET_WITH_SIDECAR_MS = 3_000;

    private final CatalogQueryPlanner queryPlanner;
    private final CatalogIndexStore indexStore;
    private final CatalogDeepSearchPreviewService previewService;
    private final CatalogSearchAnswerService searchAnswerService;
    private final CatalogCommoditySearch commoditySearch;
    private final CatalogScoringPipeline scoringPipeline;
    private final CatalogAiDataResolver aiDataResolver;
    private final CatalogSourceYieldTelemetry sourceYieldTelemetry;
    private final CatalogStructuredSemanticCompatibilityService structuredCompatibilityService;
    private final SearchCatalogSidecarIndex sidecarIndex;
    private final WebResearchService webResearchService;

    public Map<String, Object> deepSearch(Map<String, Object> request) {
        return deepSearchWithLanes(request, null);
    }

    public Map<String, Object> deepSearchWithLanes(Map<String, Object> request, Consumer<Map<String, Object>> onLane) {
        long tRequestStart = System.currentTimeMillis();
        String query = CatalogMapSupport.firstNonBlank(request, CatalogKeys.Q, CatalogKeys.QUERY);
        if (query.length() < 2) {
            return emptyResponse("Dotaz je příliš krátký.");
        }

        int limitPerSource = parseLimit(request.get("limit_per_source"), 8);
        List<String> requestedSources = readSources(request);
        boolean useAi = parseBoolean(request.get("use_ai"), true);
        boolean metadataOnly = metadataOnlyEval(request);
        boolean managerDiscovery = parseBoolean(request.get("manager_discovery"), false);
        SearchPlan plan = queryPlanner.planTyped(query, requestedSources, useAi);
        long tPlanDone = System.currentTimeMillis();
        long sourceRoutingStarted = System.nanoTime();
        List<String> sources = narrowSourcesForGeo(query, plan, requestedSources, managerDiscovery);
        long sourceRoutingMs = elapsedMillis(sourceRoutingStarted);
        List<String> searchTerms = plan.searchTerms();
        List<String> indexProbeTerms =
                mergeExtraProbeTerms(plan.indexProbeTerms(), readStringList(request.get("extra_index_probe_terms")));
        String countryHint = plan.countryHint();
        Map<String, Object> geoIntent = plan.geoIntent() != null ? plan.geoIntent().toMap() : Map.of();
        int maxLaneTerms = managerDiscovery ? MANAGER_MAX_LANE_TERMS : MAX_LANE_TERMS;

        List<Map<String, Object>> candidates = new ArrayList<>();
        List<Map<String, Object>> sourceStatuses = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!indexStore.ftsDbAvailable()) {
            warnings.add("SQLite FTS databáze není k dispozici — používá se scan JSONL indexů.");
        }

        long laneDispatchStarted = System.currentTimeMillis();
        Map<String, List<Map<String, Object>>> sidecarResults = new ConcurrentHashMap<>();
        final List<String> laneSearchTerms = searchTerms;
        final List<String> laneProbeTerms = indexProbeTerms;
        final int laneTermCap = maxLaneTerms;
        final boolean laneManagerDiscovery = managerDiscovery;
        List<CompletableFuture<LaneResult>> futures = sources.stream()
                .map(source -> CompletableFuture.supplyAsync(
                        () -> searchLane(
                                source,
                                query,
                                laneSearchTerms,
                                laneProbeTerms,
                                limitPerSource,
                                countryHint,
                                onLane,
                                metadataOnly,
                                laneDispatchStarted,
                                sidecarResults,
                                laneTermCap,
                                laneManagerDiscovery),
                        LANE_EXECUTOR))
                .toList();
        // Wait for all lanes in parallel for the full wall budget. The old sequential future.get(remaining)
        // pattern starved later sources once an earlier lane (e.g. fred bm25+COUNT) consumed most of the budget.
        CompletableFuture<Void> allLanes =
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        try {
            allLanes.get(metadataOnly ? 6_000 : LANE_WALL_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            futures.forEach(future -> future.cancel(true));
            Thread.currentThread().interrupt();
            throw new CancellationException("Deep search was cancelled by the requesting client");
        } catch (Exception ex) {
            log.debug("deep-search lane wall budget exceeded: {}", ex.getMessage());
            harvestNearlyFinishedLanes(futures, metadataOnly ? 500 : LANE_TIMEOUT_GRACE_MS);
        }
        for (CompletableFuture<LaneResult> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
                continue;
            }
            try {
                LaneResult lane = future.get();
                candidates.addAll(lane.candidates());
                sourceStatuses.add(lane.status());
            } catch (Exception ex) {
                log.debug("deep-search lane dropped (slow or failed): {}", ex.getMessage());
            }
        }
        addFastSidecarFallbacksForTimedOutSources(
                candidates,
                sourceStatuses,
                sources,
                query,
                searchTerms,
                indexProbeTerms,
                limitPerSource,
                countryHint,
                onLane,
                metadataOnly,
                sidecarResults);

        if (shouldAddCommodityFallback(query, requestedSources, sources)) {
            commoditySearch.searchHits(query, limitPerSource * 2).stream()
                    .map(CatalogHit::toMap)
                    .map(hit -> decorateCatalogCandidate(hit, query, countryHint))
                    .forEach(candidates::add);
        }

        long tRetrievalDone = System.currentTimeMillis();
        normalizeSourceTelemetry(sources, sourceStatuses, laneDispatchStarted, tRetrievalDone);
        int candidateCountRetrieved = candidates.size();
        long candidateNormalizationStarted = System.nanoTime();
        candidates = dedupeCandidates(candidates);
        int candidateCountDeduped = candidates.size();
        long candidateNormalizationMs = elapsedMillis(candidateNormalizationStarted);
        long geoCompatibilityStarted = System.nanoTime();
        candidates = rerankCandidatesForGeo(geoIntent, candidates);
        long geoCompatibilityMs = elapsedMillis(geoCompatibilityStarted);
        long variantNormalizationStarted = System.nanoTime();
        candidates = CatalogSearchVariantDedup.consolidateDisplayRows(candidates);
        candidates = candidates.stream()
                .map(candidate -> structuredCompatibilityService.evaluate(candidate, plan.semanticProfile()))
                .toList();
        int candidateCountPostVariantDedup = candidates.size();
        candidateNormalizationMs += elapsedMillis(variantNormalizationStarted);
        long tPostRetrievalDone = System.currentTimeMillis();
        // AI-over-data: let the LLM pick/order the candidates that truly match the query meaning (AI path only).
        if (useAi) {
            candidates = aiDataResolver.rankCandidates(query, candidates);
        }
        long tResolverDone = System.currentTimeMillis();
        if (metadataOnly) {
            return metadataOnlyResponse(
                    query,
                    plan.toMap(),
                    candidates,
                    sourceStatuses,
                    warnings,
                    tPlanDone - tRequestStart,
                    tRetrievalDone - tPlanDone,
                    System.currentTimeMillis() - tRequestStart,
                    CatalogMapSupport.toInt(request.get("limit"), 20));
        }
        CatalogDeepSearchPreviewService.PreviewPhaseResult previewPhase =
                previewService.verifyCandidates(candidates, query, onPreviewDone(onLane), managerDiscovery);
        long tInitialPreviewDone = System.currentTimeMillis();

        DisplayBuckets display = finalizePreviewPhase(query, geoIntent, previewPhase, managerDiscovery);
        long tInitialRankDone = System.currentTimeMillis();
        List<Map<String, Object>> verified = new ArrayList<>(display.verified());
        List<Map<String, Object>> possible = new ArrayList<>(display.possible());
        List<Map<String, Object>> discardedCandidates = new ArrayList<>(display.discarded());
        CatalogDeepSearchPreviewService.PreviewPhaseResult retryPreviewPhase = null;
        long retryPreviewMs = 0L;
        long retryRankMs = 0L;
        if (verified.isEmpty() && possible.isEmpty() && !candidates.isEmpty()) {
            long retryStarted = System.currentTimeMillis();
            retryPreviewPhase = previewService.verifyCandidates(candidates, query, null, managerDiscovery);
            long retryPreviewDone = System.currentTimeMillis();
            DisplayBuckets retryDisplay = finalizePreviewPhase(query, geoIntent, retryPreviewPhase, managerDiscovery);
            retryPreviewMs = retryPreviewDone - retryStarted;
            retryRankMs = System.currentTimeMillis() - retryPreviewDone;
            if (!retryDisplay.verified().isEmpty() || !retryDisplay.possible().isEmpty()) {
                verified = new ArrayList<>(retryDisplay.verified());
                possible = new ArrayList<>(retryDisplay.possible());
                discardedCandidates.addAll(retryDisplay.discarded());
            }
        }
        enrichSourceYieldTelemetry(sourceStatuses, verified, possible, previewPhase, retryPreviewPhase);
        String queryShape = CatalogMapSupport.str(plan.semanticProfile().get(CatalogKeys.QUERY_SHAPE));
        Map<String, Map<String, Object>> sourceYieldHistory =
                sourceYieldTelemetry.observe(queryShape, sourceStatuses);
        attachSourceYieldHistory(sourceStatuses, queryShape, sourceYieldHistory);

        String requestId = CatalogMapSupport.firstNonBlank(request, "_request_id", "request_id");
        if (requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().substring(0, 12);
        }

        Map<String, Object> planMap = plan.toMap();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("error", null);
        out.put(CatalogKeys.STATUS, verified.isEmpty() && possible.isEmpty() ? "no_valid_result" : "ok");
        out.put(CatalogKeys.QUERY, query);
        out.put("search_plan", planMap);
        out.put("plan", planMap);
        boolean aiPlanUsed = "openai".equals(plan.planner());
        out.put("ai_requested", useAi);
        out.put("ai_active", aiPlanUsed);
        out.put("ai_plan_used", aiPlanUsed);
        out.put(CatalogKeys.VERIFIED, verified);
        out.put(CatalogKeys.POSSIBLE, possible);
        out.put("discarded_candidates", discardedCandidates.stream().limit(40).toList());
        out.put("variants_used", searchTerms);
        out.put("planned_sources", List.copyOf(sources));
        out.put("source_statuses", sourceStatuses);
        out.put("catalog_index_warnings", warnings);
        out.put("grouped_results", Map.of(CatalogKeys.VERIFIED, verified, "candidates", possible, "beta", List.of()));
        out.put("request_id", requestId);
        out.put("access_mode", "development_full");
        out.put("preview_phase", previewPhase.previewTiming());
        out.put("preview_notes", previewPhase.notes());
        if (retryPreviewPhase != null) {
            out.put("preview_retry_phase", retryPreviewPhase.previewTiming());
            out.put("preview_retry_notes", retryPreviewPhase.notes());
        }
        out.put("live_preview_verification", true);
        boolean useAiStory = parseBoolean(request.get("use_ai_story"), true);
        long tAnswerStarted = System.currentTimeMillis();
        attachSearchAnswer(out, query, verified, possible, discardedCandidates, useAiStory);
        long tAnswerDone = System.currentTimeMillis();
        long tTotal = System.currentTimeMillis() - tRequestStart;
        Map<String, Object> performanceProfile = new LinkedHashMap<>();
        performanceProfile.put("planned_sources", List.copyOf(sources));
        performanceProfile.put("manager_discovery", managerDiscovery);
        if (managerDiscovery) {
            List<String> intentIds = ExploreManagerDiscoveryTerms.detectedIntentIds(query);
            performanceProfile.put("manager_intent_ids", intentIds);
            LinkedHashSet<String> seedIds = new LinkedHashSet<>();
            for (String source : sources) {
                seedIds.addAll(ExploreManagerDiscoveryTerms.managerPinnedSeedsForSource(query, source));
            }
            performanceProfile.put("manager_pinned_seed_ids", List.copyOf(seedIds));
        }
        performanceProfile.put("planner_ms", tPlanDone - tRequestStart);
        performanceProfile.put("source_routing_ms", sourceRoutingMs);
        performanceProfile.put("retrieval_wall_ms", tRetrievalDone - tPlanDone);
        performanceProfile.put("term_expansion_ms", sumSourceMetric(sourceStatuses, "term_expansion_ms"));
        performanceProfile.put("per_term_fts_ms", collectPerTermTimings(sourceStatuses));
        performanceProfile.put("per_source_sidecar_scan_ms", collectSourceMetric(sourceStatuses, "sidecar_scan_ms"));
        performanceProfile.put("candidate_normalization_ms", candidateNormalizationMs);
        performanceProfile.put("geo_compatibility_ms", geoCompatibilityMs);
        performanceProfile.put("post_retrieval_ms", tPostRetrievalDone - tRetrievalDone);
        performanceProfile.put("ai_resolver_ms", tResolverDone - tPostRetrievalDone);
        performanceProfile.put("preview_initial_ms", tInitialPreviewDone - tResolverDone);
        performanceProfile.put("preview_queue_wait_ms", longMetric(previewPhase.previewTiming(), "preview_queue_wait_ms"));
        performanceProfile.put("preview_fetch_ms", longMetric(previewPhase.previewTiming(), "preview_fetch_ms"));
        performanceProfile.put("preview_parse_ms", longMetric(previewPhase.previewTiming(), "preview_parse_ms"));
        performanceProfile.put("preview_validation_ms", longMetric(previewPhase.previewTiming(), "preview_validation_ms"));
        performanceProfile.put("validator_ms", longMetric(previewPhase.previewTiming(), "preview_validation_ms"));
        performanceProfile.put("cancellation_delay_ms", longMetric(previewPhase.previewTiming(), "preview_cancellation_delay_ms"));
        performanceProfile.put("final_rank_initial_ms", tInitialRankDone - tInitialPreviewDone);
        performanceProfile.put("preview_retry_ms", retryPreviewMs);
        performanceProfile.put("retry_fallback_ms", retryPreviewMs + retryRankMs);
        performanceProfile.put("final_rank_retry_ms", retryRankMs);
        performanceProfile.put("answer_ms", tAnswerDone - tAnswerStarted);
        performanceProfile.put("total_ms", tTotal);
        performanceProfile.put("sources_requested", sources.size());
        performanceProfile.put("sources_completed", sourceStatuses.size());
        performanceProfile.put(
                "fts_result_cache_bypassed", indexStore.ftsResultCacheBypassedForColdPathProfile());
        performanceProfile.put("source_statuses", sourceStatuses);
        performanceProfile.put("source_yield_model", sourceYieldHistory);
        performanceProfile.put("source_yield_model_mode", "observe_only");
        performanceProfile.put("candidate_count_retrieved", candidateCountRetrieved);
        performanceProfile.put("candidate_count_deduped", candidateCountDeduped);
        performanceProfile.put("candidate_count_post_variant_dedup", candidateCountPostVariantDedup);
        performanceProfile.put("candidate_count_resolver_output", candidates.size());
        performanceProfile.put("verified_count", verified.size());
        performanceProfile.put("possible_count", possible.size());
        performanceProfile.put("discarded_count", discardedCandidates.size());
        performanceProfile.put("preview_initial", previewPhase.previewTiming());
        performanceProfile.put(
                "preview_retry", retryPreviewPhase == null ? Map.of() : retryPreviewPhase.previewTiming());
        out.put("performance_profile", performanceProfile);
        log.info(
                "deep-search timing query='{}' planner={} plan_ms={} retrieval_ms={} post_ms={} resolver_ms={} preview_ms={} retry_preview_ms={} answer_ms={} total_ms={}",
                query,
                plan.planner(),
                tPlanDone - tRequestStart,
                tRetrievalDone - tPlanDone,
                tPostRetrievalDone - tRetrievalDone,
                tResolverDone - tPostRetrievalDone,
                tInitialPreviewDone - tResolverDone,
                retryPreviewMs,
                tAnswerDone - tAnswerStarted,
                tTotal);
        // Web-search fallback: only when the catalog found nothing valid at all, and never for
        // Manager Explorer discovery (managerDiscovery) - that path runs its own web fallback in
        // ExploreSectorService. Kept last so it only ever delays a genuinely empty response.
        applyWebFallback(out, query, verified.isEmpty() && possible.isEmpty(), managerDiscovery, searchTerms, sources);
        return out;
    }

    /**
     * Attaches the classic-search web-research fallback contract to a deep-search response. The
     * three {@code web_*} fields are always present (stable contract); the web call itself only
     * fires when the catalog returned no valid result AND this is not a Manager Explorer discovery
     * request. A failure here never fails the search - it just leaves {@code web_research_status}
     * at {@code "failed"} with an empty list. Kept separate from catalog {@code verified}/{@code
     * possible}: web findings are context, not catalog indicators, and the UI renders them in their
     * own section.
     */
    private void applyWebFallback(
            Map<String, Object> out,
            String query,
            boolean noValidResult,
            boolean managerDiscovery,
            List<String> searchTerms,
            List<String> sources) {
        out.put("web_sources", List.of());
        out.put("web_sources_total", 0);
        out.put("web_research_status", "not_attempted");
        if (!noValidResult || managerDiscovery) {
            return;
        }
        try {
            Map<String, Object> searchContext = new LinkedHashMap<>();
            searchContext.put("search_terms", searchTerms == null ? List.of() : searchTerms);
            searchContext.put("sources", sources == null ? List.of() : sources);
            Map<String, Object> webResult = webResearchService.researchCatalogFallback(query, searchContext);
            List<Map<String, Object>> findings =
                    webResult == null ? List.of() : castWebFindings(webResult.get("findings"));
            out.put("web_sources", findings);
            out.put("web_sources_total", findings.size());
            out.put("web_research_status", findings.isEmpty() ? "empty" : "found");
        } catch (Exception ex) {
            log.warn("classic-search web-research fallback failed query='{}': {}", query, ex.getMessage());
            out.put("web_research_status", "failed");
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

    private static DisplayBuckets finalizePreviewPhase(
            String query,
            Map<String, Object> geoIntent,
            CatalogDeepSearchPreviewService.PreviewPhaseResult previewPhase,
            boolean managerDiscovery) {
        List<Map<String, Object>> verified = new ArrayList<>(previewPhase.verified());
        List<Map<String, Object>> possible = new ArrayList<>(previewPhase.possible());
        if (verified.isEmpty() && !possible.isEmpty()) {
            CatalogDeepSearchPromotion.promoteCatalogMatches(query, geoIntent, possible, verified);
        }
        CatalogDeepSearchFinalRanker.RankedBuckets ranked =
                CatalogDeepSearchFinalRanker.rank(query, geoIntent, verified, possible);
        verified = new ArrayList<>();
        possible = new ArrayList<>();
        int macroScaffoldKept = 0;
        int topicKeepKept = 0;
        for (Map<String, Object> row : ranked.verified()) {
            // Keep a verified-preview row when the semantic gate is happy OR the AI resolver judged it relevant
            // (the AI's judgment over the actual data outranks the brittle keyword-topic gate).
            boolean actionable = CatalogDeepSearchFinalRanker.isSemanticallyActionable(row)
                    || CatalogAiDataResolver.isAiRelevant(row);
            boolean macroScaffold = managerDiscovery
                    && CatalogDeepSearchFinalRanker.hasVerifiedDataPreview(row)
                    && ExploreManagerDiscoveryTerms.isMacroScaffoldRow(row)
                    && macroScaffoldKept < ExploreManagerDiscoveryTerms.MAX_MANAGER_MACRO_SCAFFOLD;
            boolean intentExcluded = managerDiscovery
                    && !macroScaffold
                    && ExploreManagerDiscoveryTerms.isIntentExcludedRow(query, row);
            if (intentExcluded) {
                Map<String, Object> demoted = demoteWeakVerifiedPreview(row);
                demoted.put("manager_intent_excluded", true);
                demoted.putIfAbsent(
                        "demotion_reason", "manager_intent_exclude");
                demoted.putIfAbsent(
                        CatalogKeys.WHY_RELEVANT,
                        "Odloženo — řada koliduje s detekovaným záměrem dotazu (šum mimo téma).");
                possible.add(demoted);
                continue;
            }
            boolean topicKeep = managerDiscovery
                    && CatalogDeepSearchFinalRanker.hasVerifiedDataPreview(row)
                    && ExploreManagerDiscoveryTerms.isTopicIntentRow(query, row)
                    && topicKeepKept < ExploreManagerDiscoveryTerms.MAX_MANAGER_TOPIC_KEEP;
            boolean intentSeedKeep = managerDiscovery
                    && CatalogDeepSearchFinalRanker.hasVerifiedDataPreview(row)
                    && ExploreManagerDiscoveryTerms.matchesIntentPreviewSeed(query, row)
                    && topicKeepKept < ExploreManagerDiscoveryTerms.MAX_MANAGER_TOPIC_KEEP;
            if (CatalogDeepSearchFinalRanker.hasVerifiedDataPreview(row)
                    && (actionable || macroScaffold || topicKeep || intentSeedKeep)) {
                if (macroScaffold && !actionable) {
                    Map<String, Object> kept = new LinkedHashMap<>(row);
                    kept.put("manager_macro_scaffold", true);
                    kept.putIfAbsent(
                            CatalogKeys.WHY_RELEVANT,
                            "Manager makro scaffold (HDP / inflace / nezaměstnanost / průmyslová výroba) — "
                                    + "ponecháno i bez úzké tematické shody s produktem v dotazu.");
                    verified.add(kept);
                    macroScaffoldKept++;
                } else if ((topicKeep || intentSeedKeep) && !actionable) {
                    Map<String, Object> kept = new LinkedHashMap<>(row);
                    kept.put("manager_topic_keep", true);
                    if (intentSeedKeep) {
                        kept.put("manager_intent_seed", true);
                    }
                    kept.putIfAbsent(
                            CatalogKeys.WHY_RELEVANT,
                            "Manager topic keep — řada odpovídá detekovanému záměru dotazu "
                                    + "(ziskovost / obchod / výroba / …) i bez úzké semantic shody.");
                    verified.add(kept);
                    topicKeepKept++;
                } else {
                    verified.add(row);
                }
            } else {
                possible.add(demoteWeakVerifiedPreview(row));
            }
        }
        possible.addAll(ranked.possible());
        possible = sortByFinalRank(possible);
        List<Map<String, Object>> discardedCandidates = new ArrayList<>();
        possible = keepOnlyPreviewSafePossible(possible, discardedCandidates, managerDiscovery, query);
        return new DisplayBuckets(verified, possible, discardedCandidates);
    }

    private record DisplayBuckets(
            List<Map<String, Object>> verified,
            List<Map<String, Object>> possible,
            List<Map<String, Object>> discarded) {}

    private static Map<String, Object> metadataOnlyResponse(
            String query,
            Map<String, Object> planMap,
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> sourceStatuses,
            List<String> warnings,
            long plannerMs,
            long lanesMs,
            long totalMs,
            int limit) {
        int safeLimit = Math.max(1, Math.min(50, limit <= 0 ? 20 : limit));
        List<Map<String, Object>> top = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> candidate : candidates.stream().limit(safeLimit).toList()) {
            Map<String, Object> row = new LinkedHashMap<>(candidate);
            row.putIfAbsent("rank", rank++);
            row.putIfAbsent("preview_status", "metadata_only");
            row.putIfAbsent("status", "candidate");
            top.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("error", null);
        out.put(CatalogKeys.STATUS, top.isEmpty() ? "no_valid_result" : "ok");
        out.put(CatalogKeys.QUERY, query);
        out.put("search_plan", planMap);
        out.put("plan", planMap);
        out.put(CatalogKeys.VERIFIED, top);
        out.put(CatalogKeys.POSSIBLE, List.of());
        out.put("results", top);
        out.put("discarded_candidates", List.of());
        out.put("source_statuses", sourceStatuses);
        out.put("catalog_index_warnings", warnings);
        out.put("grouped_results", Map.of(CatalogKeys.VERIFIED, top, "candidates", List.of(), "beta", List.of()));
        out.put("preview_phase", Map.of("mode", "metadata_only", "skipped", true));
        out.put("preview_notes", List.of("metadata_only eval skipped live preview"));
        out.put("live_preview_verification", false);
        out.put("timings", Map.of(
                "planner_ms", plannerMs,
                "fts_ms", lanesMs,
                "preview_verification_ms", 0,
                "total_ms", totalMs));
        return out;
    }

    private static boolean metadataOnlyEval(Map<String, Object> request) {
        String mode = CatalogMapSupport.firstNonBlank(request.get("eval_mode"), request.get("preview_mode"));
        return "metadata_only".equalsIgnoreCase(mode)
                || parseBoolean(request.get("metadata_only"), false);
    }

    private void addFastSidecarFallbacksForTimedOutSources(
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> sourceStatuses,
            List<String> sources,
            String query,
            List<String> searchTerms,
            List<String> indexProbeTerms,
            int limitPerSource,
            String countryHint,
            Consumer<Map<String, Object>> onLane,
            boolean metadataOnly,
            Map<String, List<Map<String, Object>>> sidecarResults) {
        Set<String> completedSources = sourceStatuses.stream()
                .map(status -> CatalogMapSupport.str(status.get(CatalogKeys.SOURCE)))
                .filter(source -> !source.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (metadataOnly) {
            for (String source : sources) {
                if (!completedSources.contains(source)) {
                    sourceStatuses.add(Map.of(
                            CatalogKeys.SOURCE,
                            source,
                            "count",
                            0,
                            "duration_ms",
                            0,
                            "ok",
                            false,
                            "mode",
                            "metadata_only_timeout_skipped"));
                }
            }
            return;
        }
        for (String source : sources) {
            if (completedSources.contains(source)) {
                continue;
            }
            long t0 = System.currentTimeMillis();
            int retrievalDepth = Math.max(limitPerSource * 3, LANE_RETRIEVAL_DEPTH);
            List<Map<String, Object>> rows = sidecarResults.get(source);
            if (rows == null) {
                sourceStatuses.add(Map.of(
                        CatalogKeys.SOURCE,
                        source,
                        "count",
                        0,
                        "duration_ms",
                        0,
                        "ok",
                        true,
                        "terminal_status",
                        "budget_exhausted",
                        "mode",
                        "sidecar_timeout_fallback_unavailable"));
                continue;
            }
            if (rows.isEmpty()) {
                sourceStatuses.add(Map.of(
                        CatalogKeys.SOURCE,
                        source,
                        "count",
                        0,
                        "duration_ms",
                        System.currentTimeMillis() - t0,
                        "ok",
                        true,
                        "terminal_status",
                        "empty",
                        "mode",
                        "sidecar_timeout_fallback_empty"));
                continue;
            }
            rows = scoringPipeline.scoreAndRankAsMaps(source, query, rows, Math.max(limitPerSource, rows.size()));
            int previewCandidateLimit =
                    Math.max(LANE_PREVIEW_CANDIDATE_FLOOR, limitPerSource * LANE_PREVIEW_CANDIDATE_MULTIPLIER);
            if (rows.size() > previewCandidateLimit) {
                rows = rows.subList(0, previewCandidateLimit);
            }
            List<Map<String, Object>> decoratedRows = rows.stream()
                    .map(hit -> decorateCatalogCandidate(hit, query, countryHint))
                    .toList();
            candidates.addAll(decoratedRows);
            sourceStatuses.add(Map.of(
                    CatalogKeys.SOURCE,
                    source,
                    "count",
                    decoratedRows.size(),
                    "duration_ms",
                    System.currentTimeMillis() - t0,
                    "ok",
                    true,
                    "mode",
                    "sidecar_fast_timeout_fallback"));
            if (onLane != null) {
                onLane.accept(Map.of(
                        CatalogKeys.SOURCE,
                        source,
                        "rows",
                        decoratedRows,
                        "count",
                        decoratedRows.size(),
                        "phase",
                        "catalog_sidecar_timeout_fallback"));
            }
        }
    }

    private static void harvestNearlyFinishedLanes(List<CompletableFuture<LaneResult>> futures, long graceMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, graceMs);
        while (System.currentTimeMillis() < deadline) {
            List<CompletableFuture<LaneResult>> pending = futures.stream()
                    .filter(future -> !future.isDone())
                    .toList();
            if (pending.isEmpty()) {
                return;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return;
            }
            try {
                CompletableFuture.anyOf(pending.toArray(CompletableFuture[]::new)).get(remaining, TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                return;
            }
        }
    }

    private static Map<String, Object> demoteWeakVerifiedPreview(Map<String, Object> row) {
        if (!CatalogDeepSearchFinalRanker.hasVerifiedDataPreview(row)
                || CatalogDeepSearchFinalRanker.isSemanticallyActionable(row)) {
            return row;
        }
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.put(CatalogKeys.STATUS, "candidate");
        copy.put(CatalogKeys.RESULT_TIER, "candidate");
        copy.putIfAbsent("demotion_reason", "semantic_mismatch_verified_preview");
        copy.put(
                CatalogKeys.WHY_RELEVANT,
                "Live nahled je dostupny, ale tematicka shoda s dotazem je slaba; ponechano pouze jako nizka relevance.");
        return copy;
    }

    private static void addLaneTerm(LinkedHashSet<String> terms, Set<String> seenNormalized, String term) {
        addLaneTerm(terms, seenNormalized, term, MAX_LANE_TERMS);
    }

    private static void addLaneTerm(
            LinkedHashSet<String> terms, Set<String> seenNormalized, String term, int maxLaneTerms) {
        if (term == null || terms.size() >= maxLaneTerms) {
            return;
        }
        String trimmed = term.trim();
        if (trimmed.length() < 2) {
            return;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.isEmpty() || !seenNormalized.add(normalized)) {
            return;
        }
        terms.add(trimmed);
    }

    @PreDestroy
    void shutdownExecutors() {
        LANE_EXECUTOR.shutdown();
    }

    private void attachSearchAnswer(
            Map<String, Object> out,
            String query,
            List<Map<String, Object>> verified,
            List<Map<String, Object>> possible,
            List<Map<String, Object>> discardedCandidates,
            boolean useAiStory) {
        List<Map<String, Object>> narrativeCandidates =
                possible.isEmpty() && verified.isEmpty()
                        ? semanticallyUsefulDiscardedCandidates(discardedCandidates)
                        : possible;
        // `useAiStory` was previously accepted by callers (e.g. CatalogSearchStreamService's
        // "use_ai_story": false) but never actually read here - this path always called the
        // 3-arg composeStory(...), which hardcodes useAi=true. Explore's discovery pipeline
        // (ExploreDiscoveryService) never reads "answer"/"story"/"ai_result_layer" from this
        // response at all (see IndicatorBundle), so it was paying for a full LLM call on every
        // discovery request purely to build a narrative nobody displays.
        Map<String, Object> story = searchAnswerService.composeStory(query, verified, narrativeCandidates, useAiStory);
        String narrative = CatalogMapSupport.str(story.get("answer_cz"));
        String headline = CatalogMapSupport.str(story.get("headline_cz"));
        if (headline.isBlank()) {
            headline = verified.isEmpty() ? "Shrnutí hledání" : "Příběh dat";
        }
        out.put(CatalogKeys.ANSWER, narrative);
        out.put("story", story);
        Map<String, Object> aiLayer = new LinkedHashMap<>();
        aiLayer.put("headline_cz", headline);
        aiLayer.put("answer_cz", narrative);
        aiLayer.put("drivers", story.getOrDefault("drivers", List.of()));
        aiLayer.put("schema_version", 2);
        out.put("ai_result_layer", aiLayer);
    }

    private LaneResult searchLane(
            String source,
            String query,
            List<String> searchTerms,
            List<String> indexProbeTerms,
            int limitPerSource,
            String countryHint,
            Consumer<Map<String, Object>> onLane,
            boolean metadataOnly,
            long queuedAtMs,
            Map<String, List<Map<String, Object>>> sidecarResults,
            int maxLaneTerms,
            boolean managerDiscovery) {
        long t0 = System.currentTimeMillis();
        long queueMs = Math.max(0L, t0 - queuedAtMs);
        String sourceStartedAt = Instant.ofEpochMilli(t0).toString();
        List<Map<String, Object>> laneHits = new ArrayList<>();
        // Collapse near-identical terms (eurusd / eur-usd / eur usd -> one) and cap the count: each extra term
        // is a full FTS scan + scoring pass per source, and duplicates add cost without adding recall.
        long termExpansionStarted = System.nanoTime();
        LinkedHashSet<String> terms = managerDiscovery
                ? buildManagerLaneTerms(query, searchTerms, indexProbeTerms, maxLaneTerms)
                : buildLaneTerms(query, searchTerms, indexProbeTerms, maxLaneTerms);
        long termExpansionMs = elapsedMillis(termExpansionStarted);
        // Retrieve deeper than the final cap so relevance scoring — not the raw FTS/bm25 order — decides the
        // per-lane top-N. Without this, a series that is bm25-tied with many siblings (e.g. "Euro area · US
        // dollar exchange rates" among 100+ country FX rows) never reaches the scorer.
        int retrievalDepth = Math.max(limitPerSource * 3, LANE_RETRIEVAL_DEPTH);
        long sidecarStarted = System.currentTimeMillis();
        List<Map<String, Object>> sidecarSeeds = sidecarSearchSeeds(source, terms, retrievalDepth);
        if (managerDiscovery) {
            List<String> pinnedSeeds = ExploreManagerDiscoveryTerms.managerPinnedSeedsForSource(query, source);
            if (!pinnedSeeds.isEmpty()) {
                sidecarSeeds = new ArrayList<>(sidecarSeeds);
                Set<String> intentSeedIds = new LinkedHashSet<>(
                        ExploreManagerDiscoveryTerms.intentPreviewSeedsForSource(query, source).stream()
                                .map(id -> id.toLowerCase(Locale.ROOT))
                                .toList());
                // Intent seeds first — a shared batch lookup previously let macro prefix expansion
                // consume the limit before tipsbd40 / sts_trtu_m / … were resolved.
                List<String> orderedSeeds = new ArrayList<>();
                for (String seedId : ExploreManagerDiscoveryTerms.intentPreviewSeedsForSource(query, source)) {
                    if (seedId != null && !seedId.isBlank()) {
                        orderedSeeds.add(seedId.trim());
                    }
                }
                for (String seedId : ExploreManagerDiscoveryTerms.coreMacroSeedsForSource(source)) {
                    if (seedId != null && !seedId.isBlank() && orderedSeeds.stream().noneMatch(seedId::equalsIgnoreCase)) {
                        orderedSeeds.add(seedId.trim());
                    }
                }
                for (String seedId : pinnedSeeds) {
                    if (seedId != null
                            && !seedId.isBlank()
                            && orderedSeeds.stream().noneMatch(seedId::equalsIgnoreCase)) {
                        orderedSeeds.add(seedId.trim());
                    }
                }
                Set<String> foundIds = new LinkedHashSet<>();
                for (String seedId : orderedSeeds) {
                    List<Map<String, Object>> lookedUp =
                            indexStore.lookupRowsBySetIds(source, List.of(seedId), 1);
                    Map<String, Object> stamped;
                    if (!lookedUp.isEmpty()) {
                        stamped = new LinkedHashMap<>(lookedUp.get(0));
                    } else {
                        stamped = new LinkedHashMap<>();
                        stamped.put(CatalogKeys.SET_ID, seedId);
                        stamped.put("dataset", seedId);
                        stamped.put("title", seedId);
                        stamped.put("name", seedId);
                        stamped.put("manager_synthetic_seed", true);
                    }
                    stamped.put(CatalogKeys.SOURCE_TYPE, source);
                    stamped.put(CatalogKeys.SOURCE, source);
                    stamped.putIfAbsent("catalog_id", source);
                    String sid = CatalogMapSupport.str(stamped.get(CatalogKeys.SET_ID)).toLowerCase(Locale.ROOT);
                    if (sid.isBlank()) {
                        sid = seedId.toLowerCase(Locale.ROOT);
                        stamped.put(CatalogKeys.SET_ID, seedId);
                    }
                    if (intentSeedIds.contains(sid) || intentSeedIds.contains(seedId.toLowerCase(Locale.ROOT))) {
                        stamped.put("manager_intent_seed", true);
                    }
                    if (foundIds.add(sid)) {
                        sidecarSeeds.add(stamped);
                    }
                }
                sidecarSeeds = dedupeBySetId(sidecarSeeds);
            }
        }
        sidecarResults.put(source, List.copyOf(sidecarSeeds));
        long sidecarMs = System.currentTimeMillis() - sidecarStarted;
        laneHits.addAll(sidecarSeeds);
        boolean sidecarStrongEnough =
                previewFetchableSeedCount(source, sidecarSeeds) >= Math.min(3, Math.max(1, limitPerSource));
        // Run per-term lookups concurrently. CatalogIndexStore can apply a benchmark-calibrated global
        // concurrency bound so independent source lanes do not overwhelm the shared SQLite file.
        long termBudget = metadataOnly
                ? 2_500
                : (sidecarStrongEnough ? LANE_TERM_WALL_BUDGET_WITH_SIDECAR_MS : LANE_TERM_WALL_BUDGET_MS);
        long termDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(termBudget);
        List<String> termList = new ArrayList<>(terms);
        List<CompletableFuture<CatalogIndexStore.FtsSearchResult>> termFutures = termList.stream()
                .map(term -> CompletableFuture.supplyAsync(
                        () -> managerDiscovery
                                ? searchManagerTermHitsWithinBudget(source, term, retrievalDepth, termDeadlineNanos)
                                : indexStore.searchFtsHitsWithinBudget(
                                        source, term, retrievalDepth, termDeadlineNanos),
                        LANE_EXECUTOR))
                .toList();
        CompletableFuture<Void> allTerms =
                CompletableFuture.allOf(termFutures.toArray(CompletableFuture[]::new));
        try {
            allTerms.get(termBudget, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            log.debug("deep-search term budget exceeded source={} terms={}: {}", source, terms.size(), ex.getMessage());
        }
        int completedTerms = 0;
        int budgetExhaustedTerms = 0;
        int ftsCacheHits = 0;
        int cancelledTerms = 0;
        long ftsQueueWaitMs = 0L;
        List<Map<String, Object>> termTimings = new ArrayList<>();
        int termIndex = 0;
        for (CompletableFuture<CatalogIndexStore.FtsSearchResult> termFuture : termFutures) {
            String term = termList.get(termIndex++);
            if (!termFuture.isDone()) {
                termFuture.cancel(true);
                cancelledTerms++;
                termTimings.add(Map.of(
                        "term", term,
                        "terminal_status", "cancelled",
                        "duration_ms", termBudget,
                        "queue_wait_ms", 0L,
                        "candidate_count", 0,
                        "budget_exhausted", false,
                        "cache_hit", false));
                continue;
            }
            try {
                CatalogIndexStore.FtsSearchResult result = termFuture.getNow(null);
                if (result == null) {
                    termTimings.add(Map.of(
                            "term", term,
                            "terminal_status", "error",
                            "duration_ms", 0,
                            "queue_wait_ms", 0L,
                            "candidate_count", 0,
                            "budget_exhausted", false,
                            "cache_hit", false));
                    continue;
                }
                laneHits.addAll(result.hits().stream().map(CatalogHit::toMap).toList());
                if (result.budgetExhausted()) {
                    budgetExhaustedTerms++;
                }
                if (result.cacheHit()) {
                    ftsCacheHits++;
                }
                ftsQueueWaitMs += result.queueWaitMs();
                completedTerms++;
                termTimings.add(Map.of(
                        "term", term,
                        "terminal_status", result.budgetExhausted() ? "budget_exhausted" : "completed",
                        "duration_ms", result.durationMs(),
                        "queue_wait_ms", result.queueWaitMs(),
                        "candidate_count", result.hits().size(),
                        "budget_exhausted", result.budgetExhausted(),
                        "cache_hit", result.cacheHit()));
            } catch (Exception ex) {
                log.debug("deep-search term lookup dropped source={}: {}", source, ex.getMessage());
                termTimings.add(Map.of(
                        "term", term,
                        "terminal_status", "error",
                        "duration_ms", 0,
                        "queue_wait_ms", 0L,
                        "candidate_count", 0,
                        "budget_exhausted", false,
                        "cache_hit", false,
                        "error_category", errorCategory(ex)));
            }
        }
        long retrievalMs = System.currentTimeMillis() - t0;
        laneHits = dedupeBySetId(laneHits);
        int retrievedCount = laneHits.size();
        long scoringStarted = System.currentTimeMillis();
        laneHits = scoringPipeline.scoreAndRankAsMaps(source, query, laneHits, Math.max(limitPerSource, laneHits.size()));
        long scoreMs = System.currentTimeMillis() - scoringStarted;
        if (log.isDebugEnabled() || retrievalMs + scoreMs > 4000) {
            log.info(
                    "lane source={} sidecar={} terms_done={}/{} retrieved={} fts_ms={} score_ms={}",
                    source, sidecarSeeds.size(), completedTerms, terms.size(), retrievedCount, retrievalMs, scoreMs);
        }
        int previewCandidateLimit = Math.max(LANE_PREVIEW_CANDIDATE_FLOOR, limitPerSource * LANE_PREVIEW_CANDIDATE_MULTIPLIER);
        // Manager: scoring against a sector query (manufacturing / inflation / …) routinely ranks
        // nama_10_gdp — and narrow intent-probe rows such as NACE C29 series — below the lane cap.
        // retainManagerPinnedSeeds re-pins them and enforces previewCandidateLimit itself, so the
        // naive cap below must be skipped here: applying it first would drop a pinned row before
        // the rescue ever sees it, silently reintroducing the bug this fixes.
        if (managerDiscovery) {
            laneHits = retainManagerPinnedSeeds(source, query, laneHits, sidecarSeeds, previewCandidateLimit);
        } else if (laneHits.size() > previewCandidateLimit) {
            laneHits = laneHits.subList(0, previewCandidateLimit);
        }
        List<Map<String, Object>> candidates = laneHits.stream()
                .map(hit -> decorateCatalogCandidate(hit, query, countryHint))
                .collect(Collectors.toCollection(ArrayList::new));
        if (onLane != null) {
            onLane.accept(Map.of(
                    CatalogKeys.SOURCE,
                    source,
                    "rows",
                    laneHits.stream().map(h -> decorateCatalogCandidate(h, query, countryHint)).toList(),
                    "count",
                    laneHits.size(),
                    "phase",
                    "catalog_index"));
        }
        Map<String, Object> status = new LinkedHashMap<>();
        long finishedAt = System.currentTimeMillis();
        status.put(CatalogKeys.SOURCE, source);
        status.put("count", laneHits.size());
        if (managerDiscovery) {
            status.put(
                    "manager_intent_seed_count",
                    candidates.stream()
                            .filter(row -> Boolean.TRUE.equals(row.get("manager_intent_seed")))
                            .count());
            status.put(
                    "manager_pinned_in_lane",
                    candidates.stream()
                            .map(row -> CatalogMapSupport.str(row.get(CatalogKeys.SET_ID)).toLowerCase(Locale.ROOT))
                            .filter(id -> !id.isBlank()
                                    && ExploreManagerDiscoveryTerms.managerPinnedSeedsForSource(query, source).stream()
                                            .anyMatch(seed -> seed.equalsIgnoreCase(id)))
                            .distinct()
                            .toList());
        }
        status.put("duration_ms", finishedAt - t0);
        status.put("queue_ms", queueMs);
        status.put("source_started_at", sourceStartedAt);
        status.put("source_finished_at", Instant.ofEpochMilli(finishedAt).toString());
        status.put("queue_wait_ms", queueMs);
        status.put("active_work_ms", finishedAt - t0);
        status.put("term_expansion_ms", termExpansionMs);
        status.put("term_count", terms.size());
        status.put("term_completed", completedTerms);
        status.put("term_cancelled", cancelledTerms);
        status.put("term_timings", termTimings);
        status.put("fts_queue_wait_ms", ftsQueueWaitMs);
        status.put("sidecar_ms", sidecarMs);
        status.put("sidecar_scan_ms", sidecarMs);
        status.put("sidecar_seed_count", sidecarSeeds.size());
        status.put("terms_requested", terms.size());
        status.put("terms_completed", completedTerms);
        status.put("terms_budget_exhausted", budgetExhaustedTerms);
        status.put("fts_cache_hits", ftsCacheHits);
        status.put("retrieved_before_cap", retrievedCount);
        status.put("candidate_count", laneHits.size());
        status.put("unique_candidate_count", retrievedCount);
        status.put("retrieval_ms", retrievalMs);
        status.put("scoring_ms", scoreMs);
        status.put("empty_result", laneHits.isEmpty());
        status.put("budget_exhausted", budgetExhaustedTerms > 0 || cancelledTerms > 0);
        status.put("timeout", false);
        status.put("error_category", "");
        status.put("remote_call_count", 0);
        status.put("local_index_call_count", 1 + terms.size());
        status.put("terminal_status", sourceTerminalStatus(
                laneHits.size(), budgetExhaustedTerms > 0 || cancelledTerms > 0, false, false, false));
        status.put("ok", true);
        return new LaneResult(candidates, status);
    }

    /**
     * Manager Explorer's per-term retrieval used {@code indexStore.searchFtsHitsWithinBudget}, which reads
     * {@code catalog_fts} in {@code classic_catalog_search.sqlite} - an older, thinner index where, for
     * example, every ARAD row's {@code title} column is just its numeric {@code set_id} (a bug in the
     * indexer that built it), and where rows carry none of the richer semantic fields (primary_concept,
     * institutional_sector, industry_sector, geo, aliases) that already exist for classic search. Classic
     * search (SearchV2Service) retrieves from a separate, richer index instead - {@code sidecar_fts} in
     * search_v2_sidecar.sqlite via {@link SearchCatalogSidecarIndex} - where ARAD titles are correct and
     * the extra semantic fields are present. This gives Manager Explorer specifically (managerDiscovery)
     * that same richer retrieval, without changing anything for classic search's own deep-search callers
     * (CatalogController/CatalogSearchStreamService/CatalogFollowupService), which keep using catalog_fts.
     */
    private CatalogIndexStore.FtsSearchResult searchManagerTermHitsWithinBudget(
            String source, String queryRaw, int limit, long deadlineNanos) {
        long started = System.nanoTime();
        if (System.nanoTime() > deadlineNanos) {
            return new CatalogIndexStore.FtsSearchResult(List.of(), true, false, elapsedMillis(started), 0L);
        }
        List<Map<String, Object>> rows;
        try {
            rows = sidecarIndex.search(source, queryRaw, Math.max(1, Math.min(limit, 100)));
        } catch (Exception ex) {
            log.debug("manager sidecar term lookup failed source={}: {}", source, ex.getMessage());
            rows = List.of();
        }
        List<CatalogHit> hits = rows.stream().map(CatalogHit::fromMap).toList();
        return new CatalogIndexStore.FtsSearchResult(
                hits, System.nanoTime() > deadlineNanos, false, elapsedMillis(started), 0L);
    }

    private List<Map<String, Object>> sidecarSearchSeeds(String source, LinkedHashSet<String> terms, int retrievalDepth) {
        List<Map<String, Object>> rows = new ArrayList<>(indexStore
                .sidecarSearchHits(source, new ArrayList<>(terms), retrievalDepth)
                .stream()
                .map(CatalogHit::toMap)
                .toList());
        rows = dedupeBySetId(rows);
        if (previewFetchableSeedCount(source, rows) < 3) {
            List<String> seedIds = rows.stream()
                    .map(row -> CatalogMapSupport.str(row.get(CatalogKeys.SET_ID)))
                    .filter(id -> !id.isBlank())
                    .distinct()
                    .toList();
            if (!seedIds.isEmpty()) {
                rows.addAll(indexStore.lookupRowsBySetIds(source, seedIds, retrievalDepth));
                rows = dedupeBySetId(rows);
            }
        }
        return rows;
    }

    static LinkedHashSet<String> buildLaneTerms(String query, List<String> searchTerms, List<String> indexProbeTerms) {
        return buildLaneTerms(query, searchTerms, indexProbeTerms, MAX_LANE_TERMS);
    }

    static LinkedHashSet<String> buildLaneTerms(
            String query, List<String> searchTerms, List<String> indexProbeTerms, int maxLaneTerms) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Set<String> seenNormalized = new HashSet<>();
        int termCap = Math.max(1, maxLaneTerms);
        addLaneTerm(terms, seenNormalized, query, termCap);
        // Deterministic probes are built for recall. Keep them ahead of broad/model terms so the lane cap
        // cannot hide exact catalog phrases such as "exchange rate" or "return on assets".
        if (indexProbeTerms != null) {
            for (String term : indexProbeTerms) {
                addLaneTerm(terms, seenNormalized, term, termCap);
            }
        }
        if (searchTerms != null) {
            for (String term : searchTerms) {
                addLaneTerm(terms, seenNormalized, term, termCap);
            }
        }
        return terms;
    }

    /**
     * Manager lane assembly with reserved tiers so macro scaffold cannot crowd out topic/intent FTS
     * surfaces (ROE, trade balance, manufacturing, …).
     *
     * <p>Tier: query → intent (reserve 4) → macro core (reserve 6) → remaining probes/synonyms fill.
     */
    static LinkedHashSet<String> buildManagerLaneTerms(
            String query, List<String> searchTerms, List<String> indexProbeTerms, int maxLaneTerms) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Set<String> seenNormalized = new HashSet<>();
        int termCap = Math.max(1, maxLaneTerms);
        addLaneTerm(terms, seenNormalized, query, termCap);

        List<String> intentProbes = ExploreManagerDiscoveryTerms.intentProbeTermsFor(query);
        int intentBudget = Math.min(
                ExploreManagerDiscoveryTerms.MANAGER_INTENT_LANE_RESERVE,
                Math.max(0, termCap - terms.size()));
        int intentAdded = 0;
        for (String term : intentProbes) {
            if (intentAdded >= intentBudget) {
                break;
            }
            int before = terms.size();
            addLaneTerm(terms, seenNormalized, term, termCap);
            if (terms.size() > before) {
                intentAdded++;
            }
        }

        List<String> macroProbes = ExploreManagerDiscoveryTerms.macroLaneProbeTermsFor();
        int macroBudget = Math.min(
                ExploreManagerDiscoveryTerms.MANAGER_MACRO_LANE_RESERVE,
                Math.max(0, termCap - terms.size()));
        int macroAdded = 0;
        for (String term : macroProbes) {
            if (macroAdded >= macroBudget) {
                break;
            }
            int before = terms.size();
            addLaneTerm(terms, seenNormalized, term, termCap);
            if (terms.size() > before) {
                macroAdded++;
            }
        }

        if (indexProbeTerms != null) {
            for (String term : indexProbeTerms) {
                addLaneTerm(terms, seenNormalized, term, termCap);
            }
        }
        if (searchTerms != null) {
            for (String term : searchTerms) {
                addLaneTerm(terms, seenNormalized, term, termCap);
            }
        }
        return terms;
    }

    static long previewFetchableSeedCount(String source, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        // Count distinct connector-facing ids. Eurostat enrichment aliases often share one parent
        // dataset; counting raw rows inflated "sidecar strong enough" and skipped real catalog recall.
        return rows.stream()
                .filter(row -> CatalogPreviewSetIdSupport.isPreviewFetchable(
                        source,
                        CatalogMapSupport.str(row.get(CatalogKeys.SET_ID)),
                        row))
                .map(row -> CatalogPreviewSetIdSupport.resolvePreviewSetId(
                        source,
                        CatalogMapSupport.str(row.get(CatalogKeys.SET_ID)),
                        row))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .count();
    }

    private static void normalizeSourceTelemetry(
            List<String> sources,
            List<Map<String, Object>> sourceStatuses,
            long dispatchStartedAt,
            long retrievalFinishedAt) {
        Map<String, Map<String, Object>> bySource = new LinkedHashMap<>();
        for (Map<String, Object> raw : sourceStatuses) {
            String source = CatalogMapSupport.str(raw.get(CatalogKeys.SOURCE));
            if (!source.isBlank()) {
                bySource.put(source, normalizedSourceStatus(raw, dispatchStartedAt, retrievalFinishedAt));
            }
        }
        for (String source : sources) {
            bySource.computeIfAbsent(source, ignored -> {
                Map<String, Object> cancelled = new LinkedHashMap<>();
                cancelled.put(CatalogKeys.SOURCE, source);
                cancelled.put("source_started_at", Instant.ofEpochMilli(dispatchStartedAt).toString());
                cancelled.put("source_finished_at", Instant.ofEpochMilli(retrievalFinishedAt).toString());
                cancelled.put("queue_wait_ms", 0L);
                cancelled.put("active_work_ms", Math.max(0L, retrievalFinishedAt - dispatchStartedAt));
                cancelled.put("term_count", 0);
                cancelled.put("term_completed", 0);
                cancelled.put("term_cancelled", 0);
                cancelled.put("candidate_count", 0);
                cancelled.put("unique_candidate_count", 0);
                cancelled.put("verified_yield", 0);
                cancelled.put("empty_result", true);
                cancelled.put("budget_exhausted", true);
                cancelled.put("timeout", false);
                cancelled.put("error_category", "");
                cancelled.put("remote_call_count", 0);
                cancelled.put("local_index_call_count", 0);
                cancelled.put("terminal_status", "cancelled");
                cancelled.put("ok", false);
                return cancelled;
            });
        }
        sourceStatuses.clear();
        sourceStatuses.addAll(bySource.values());
    }

    private static Map<String, Object> normalizedSourceStatus(
            Map<String, Object> raw, long dispatchStartedAt, long retrievalFinishedAt) {
        Map<String, Object> status = new LinkedHashMap<>(raw);
        long duration = Math.max(0L, longValue(status.get("duration_ms")));
        long queueWait = Math.max(
                0L,
                longValue(status.getOrDefault("queue_wait_ms", status.getOrDefault("queue_ms", 0L))));
        int count = Math.max(
                0,
                CatalogMapSupport.toInt(
                        status.getOrDefault("candidate_count", status.getOrDefault("count", 0)), 0));
        String mode = CatalogMapSupport.str(status.get("mode"));
        String terminal = CatalogMapSupport.str(status.get("terminal_status"));
        if (terminal.isBlank()) {
            if (!parseBoolean(status.getOrDefault("ok", true), true)) {
                terminal = "error";
            } else if (mode.contains("timeout")) {
                terminal = "timeout";
            } else {
                terminal = count == 0 ? "empty" : "completed";
            }
        }
        status.putIfAbsent("source_started_at", Instant.ofEpochMilli(dispatchStartedAt + queueWait).toString());
        status.putIfAbsent("source_finished_at", Instant.ofEpochMilli(Math.min(
                        retrievalFinishedAt, dispatchStartedAt + queueWait + duration))
                .toString());
        status.put("queue_wait_ms", queueWait);
        status.putIfAbsent("active_work_ms", duration);
        status.putIfAbsent("term_count", CatalogMapSupport.toInt(status.get("terms_requested"), 0));
        status.putIfAbsent("term_completed", CatalogMapSupport.toInt(status.get("terms_completed"), 0));
        status.putIfAbsent("term_cancelled", 0);
        status.put("candidate_count", count);
        status.putIfAbsent("unique_candidate_count", Math.max(
                count, CatalogMapSupport.toInt(status.get("retrieved_before_cap"), count)));
        status.putIfAbsent("verified_yield", 0);
        status.put("empty_result", count == 0);
        status.putIfAbsent("budget_exhausted", "budget_exhausted".equals(terminal));
        status.putIfAbsent("timeout", "timeout".equals(terminal));
        status.putIfAbsent("error_category", "error".equals(terminal) ? "lane_error" : "");
        status.putIfAbsent("remote_call_count", 0);
        status.putIfAbsent("local_index_call_count", 0);
        status.put("terminal_status", terminal);
        return status;
    }

    static String sourceTerminalStatus(
            int candidateCount,
            boolean budgetExhausted,
            boolean timeout,
            boolean error,
            boolean cancelled) {
        if (error) {
            return "error";
        }
        if (timeout) {
            return "timeout";
        }
        if (cancelled) {
            return "cancelled";
        }
        if (budgetExhausted) {
            return "budget_exhausted";
        }
        return candidateCount <= 0 ? "empty" : "completed";
    }

    @SuppressWarnings("unchecked")
    private static void enrichSourceYieldTelemetry(
            List<Map<String, Object>> sourceStatuses,
            List<Map<String, Object>> verified,
            List<Map<String, Object>> possible,
            CatalogDeepSearchPreviewService.PreviewPhaseResult initial,
            CatalogDeepSearchPreviewService.PreviewPhaseResult retry) {
        Map<String, Integer> verifiedBySource = countRowsBySource(verified);
        Map<String, Integer> possibleBySource = countRowsBySource(possible);
        Map<String, Map<String, Object>> previewBySource = new LinkedHashMap<>();
        for (CatalogDeepSearchPreviewService.PreviewPhaseResult phase :
                new CatalogDeepSearchPreviewService.PreviewPhaseResult[] {initial, retry}) {
            if (phase == null) {
                continue;
            }
            Object raw = phase.previewTiming().get("preview_by_source");
            if (!(raw instanceof Map<?, ?> sourceMap)) {
                continue;
            }
            for (Map.Entry<?, ?> entry : sourceMap.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> metrics)) {
                    continue;
                }
                String source = String.valueOf(entry.getKey());
                Map<String, Object> target = previewBySource.computeIfAbsent(source, ignored -> new LinkedHashMap<>());
                for (Map.Entry<?, ?> metric : metrics.entrySet()) {
                    String key = String.valueOf(metric.getKey());
                    if (metric.getValue() instanceof Number number) {
                        target.merge(key, number.longValue(), (left, right) ->
                                ((Number) left).longValue() + ((Number) right).longValue());
                    }
                }
            }
        }
        for (int index = 0; index < sourceStatuses.size(); index++) {
            Map<String, Object> status = new LinkedHashMap<>(sourceStatuses.get(index));
            String source = CatalogMapSupport.str(status.get(CatalogKeys.SOURCE));
            status.put("verified_yield", verifiedBySource.getOrDefault(source, 0));
            status.put("possible_yield", possibleBySource.getOrDefault(source, 0));
            Map<String, Object> preview = previewBySource.getOrDefault(source, Map.of());
            status.put("preview", preview);
            status.put(
                    "remote_call_count",
                    longValue(status.get("remote_call_count"))
                            + longMetric(preview, "remote_call_count"));
            sourceStatuses.set(index, status);
        }
    }

    private static Map<String, Integer> countRowsBySource(List<Map<String, Object>> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            String source = CatalogMapSupport.str(row.get(CatalogKeys.SOURCE_TYPE));
            if (!source.isBlank()) {
                counts.merge(source, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static void attachSourceYieldHistory(
            List<Map<String, Object>> sourceStatuses,
            String queryShape,
            Map<String, Map<String, Object>> sourceYieldHistory) {
        for (int index = 0; index < sourceStatuses.size(); index++) {
            Map<String, Object> status = new LinkedHashMap<>(sourceStatuses.get(index));
            String source = CatalogMapSupport.str(status.get(CatalogKeys.SOURCE));
            status.put("query_shape", queryShape == null || queryShape.isBlank() ? "unspecified" : queryShape);
            status.put("rolling_yield", sourceYieldHistory.getOrDefault(source, Map.of()));
            sourceStatuses.set(index, status);
        }
    }

    private static long sumSourceMetric(List<Map<String, Object>> sourceStatuses, String key) {
        return sourceStatuses.stream().mapToLong(status -> longMetric(status, key)).sum();
    }

    private static Map<String, Long> collectSourceMetric(List<Map<String, Object>> sourceStatuses, String key) {
        Map<String, Long> metrics = new LinkedHashMap<>();
        for (Map<String, Object> status : sourceStatuses) {
            String source = CatalogMapSupport.str(status.get(CatalogKeys.SOURCE));
            if (!source.isBlank()) {
                metrics.put(source, longMetric(status, key));
            }
        }
        return metrics;
    }

    private static List<Map<String, Object>> collectPerTermTimings(List<Map<String, Object>> sourceStatuses) {
        List<Map<String, Object>> timings = new ArrayList<>();
        for (Map<String, Object> status : sourceStatuses) {
            Object raw = status.get("term_timings");
            if (!(raw instanceof List<?> terms)) {
                continue;
            }
            String source = CatalogMapSupport.str(status.get(CatalogKeys.SOURCE));
            for (Object term : terms) {
                if (term instanceof Map<?, ?> map) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    map.forEach((key, value) -> item.put(String.valueOf(key), value));
                    item.put("source", source);
                    timings.add(item);
                }
            }
        }
        return timings;
    }

    private static long longMetric(Map<String, Object> values, String key) {
        return longValue(values == null ? null : values.get(key));
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(CatalogMapSupport.str(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    private static String errorCategory(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException) {
            return "timeout";
        }
        if (cause instanceof CancellationException) {
            return "cancelled";
        }
        return cause.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private record LaneResult(List<Map<String, Object>> candidates, Map<String, Object> status) {}

    static Map<String, Object> decorateCatalogCandidate(Map<String, Object> hit, String query) {
        return decorateCatalogCandidate(hit, query, "");
    }

    static Map<String, Object> decorateCatalogCandidate(Map<String, Object> hit, String query, String countryHint) {
        CatalogHit typed = CatalogHit.fromMap(hit);
        Map<String, Object> row = new LinkedHashMap<>(typed.toMap());
        row.put(CatalogKeys.SOURCE, typed.sourceType());
        row.put(CatalogKeys.WHY_RELEVANT, "Shoda s dotazem \"" + query + "\" v katalogu " + typed.toMap().get(CatalogKeys.CATALOG_LABEL));
        row.put(CatalogKeys.PREVIEW_STATUS, "pending");
        row.put(CatalogKeys.PREVIEW_AVAILABLE, false);
        row.put(CatalogKeys.STATUS, "candidate");
        row.put(CatalogKeys.RESULT_TIER, "candidate");
        if (!countryHint.isBlank()) {
            row.put(CatalogKeys.COUNTRY_HINT, countryHint);
        }
        // Preserve AI-resolver markers and Manager seed stamps: they aren't part of the typed
        // CatalogHit and would be lost on round-trip (breaking intent-seed pin / geo soft-pass).
        for (String keepKey : List.of(
                "_ai_relevant",
                "_ai_rank",
                "_ai_reason_cz",
                "manager_intent_seed",
                "manager_macro_scaffold",
                "manager_synthetic_seed",
                "manager_topic_keep")) {
            if (hit.containsKey(keepKey)) {
                row.put(keepKey, hit.get(keepKey));
            }
        }
        return row;
    }

    static List<String> narrowSourcesForGeo(String query, SearchPlan plan, List<String> requestedSources) {
        return narrowSourcesForGeo(query, plan, requestedSources, false);
    }

    static List<String> narrowSourcesForGeo(
            String query, SearchPlan plan, List<String> requestedSources, boolean managerDiscovery) {
        List<String> sources = new ArrayList<>(plan.sources());
        GeoIntentSnapshot geo = plan.geoIntent();
        List<String> codes = geo == null || geo.isEmpty()
                ? List.of()
                : CatalogGeoIntent.requestedGeoCodes(geo.toMap());
        boolean foreignGeo = !codes.isEmpty() && codes.stream().anyMatch(cc -> !"CZ".equals(cc));
        if (requestedSources != null && !requestedSources.isEmpty()) {
            // Explicit source lists still need Manager foreign cores (FRED/ECB/…). The planner often
            // drops those lanes for sector queries, which previously skipped policy-rate seeds.
            if (managerDiscovery) {
                LinkedHashSet<String> merged = new LinkedHashSet<>();
                for (String source : requestedSources) {
                    String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
                    if (!normalized.isBlank() && !(foreignGeo && CZ_ONLY_SOURCES.contains(normalized))) {
                        merged.add(normalized);
                    }
                }
                for (String source : sources) {
                    String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
                    if (!normalized.isBlank() && !(foreignGeo && CZ_ONLY_SOURCES.contains(normalized))) {
                        merged.add(normalized);
                    }
                }
                return ensureManagerForeignSources(new ArrayList<>(merged), foreignGeo);
            }
            return sources;
        }
        if (geo == null || geo.isEmpty()) {
            return managerDiscovery ? ensureManagerForeignSources(sources, false) : sources;
        }
        if (!foreignGeo) {
            return sources;
        }
        List<String> narrowed = sources.stream().filter(src -> !CZ_ONLY_SOURCES.contains(src)).toList();
        if (narrowed.size() < 2) {
            narrowed = CatalogGeoIntent.boostSourcesForGeoIntent(
                            CatalogLikelySources.inferLikelyCatalogSources(query), geo.toMap())
                    .stream()
                    .filter(src -> !CZ_ONLY_SOURCES.contains(src))
                    .toList();
        }
        if (managerDiscovery) {
            // Manager Explorer needs FRED/ECB/IMF macro coverage abroad. The generic FOREIGN_GEO
            // cap of 4 was dropping those lanes and the SSE layer then painted them as timeouts.
            return ensureManagerForeignSources(narrowed, true);
        }
        if (narrowed.size() > FOREIGN_GEO_MAX_SOURCES) {
            return narrowed.subList(0, FOREIGN_GEO_MAX_SOURCES);
        }
        return narrowed.isEmpty() ? sources : narrowed;
    }

    private static List<String> ensureManagerForeignSources(List<String> planned, boolean foreignGeo) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String source : planned == null ? List.<String>of() : planned) {
            String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
            if (normalized.isBlank()) {
                continue;
            }
            if (foreignGeo && CZ_ONLY_SOURCES.contains(normalized)) {
                continue;
            }
            out.add(normalized);
        }
        if (foreignGeo) {
            for (String source : ExploreManagerDiscoveryTerms.normalizedForeignCoreSources()) {
                out.add(source);
            }
        }
        return new ArrayList<>(out);
    }

    private static List<String> mergeExtraProbeTerms(List<String> base, List<String> extra) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        // Manager / caller probes first: lane term caps otherwise drop GDP/manufacturing scaffolds
        // behind long LLM synonym lists and IMF/FRED lanes go empty again.
        if (extra != null) {
            for (String term : extra) {
                if (term != null && !term.isBlank()) {
                    out.add(term.trim());
                }
            }
        }
        if (base != null) {
            for (String term : base) {
                if (term != null && !term.isBlank()) {
                    out.add(term.trim());
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static List<String> readStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String value = String.valueOf(item).trim();
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return out;
    }

    private static List<Map<String, Object>> rerankCandidatesForGeo(
            Map<String, Object> geoIntent, List<Map<String, Object>> candidates) {
        List<String> codes = CatalogGeoIntent.requestedGeoCodes(geoIntent);
        if (codes.isEmpty() || codes.stream().allMatch("CZ"::equals)) {
            return candidates;
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : candidates) {
            CatalogGeoIntent.GeoRowAdjustment adj = CatalogGeoIntent.rowCountryGeoAdjustment(row, geoIntent);
            if (adj.hardReject()) {
                // Manager pinned seeds (macro + intent preview set_ids) must survive geo hard-reject;
                // otherwise tipsbd40 / sts_inpr_m never reach live preview for foreign-country queries.
                if (!ExploreManagerDiscoveryTerms.isManagerPinnedSeedRow(row)) {
                    continue;
                }
            }
            if (adj.multiplier() != 1.0 && !adj.hardReject()) {
                Map<String, Object> copy = new LinkedHashMap<>(row);
                int score = CatalogMapSupport.toInt(copy.get(CatalogKeys.SEARCH_SCORE), 0);
                copy.put(CatalogKeys.SEARCH_SCORE, (int) Math.round(score * adj.multiplier()));
                copy.put(CatalogKeys.GEO_ADJUSTMENT, adj.reason());
                filtered.add(copy);
            } else {
                filtered.add(row);
            }
        }
        filtered.sort(Comparator.comparingInt((Map<String, Object> row) ->
                -CatalogMapSupport.toInt(row.get(CatalogKeys.SEARCH_SCORE), 0)));
        return filtered.isEmpty() ? candidates : filtered;
    }

    private static Consumer<Map<String, Object>> onPreviewDone(Consumer<Map<String, Object>> onLane) {
        if (onLane == null) {
            return null;
        }
        return row -> onLane.accept(Map.of(
                "phase", "preview",
                CatalogKeys.SOURCE, row.getOrDefault(CatalogKeys.SOURCE_TYPE, ""),
                CatalogKeys.SET_ID, row.getOrDefault(CatalogKeys.SET_ID, ""),
                CatalogKeys.STATUS, row.getOrDefault(CatalogKeys.STATUS, "candidate"),
                CatalogKeys.PREVIEW_STATUS, row.getOrDefault(CatalogKeys.PREVIEW_STATUS, "unverified"),
                CatalogKeys.PREVIEW_AVAILABLE, row.getOrDefault(CatalogKeys.PREVIEW_AVAILABLE, false),
                "preview_row_count", row.getOrDefault("preview_row_count", 0)));
    }

    private static List<Map<String, Object>> dedupeCandidates(List<Map<String, Object>> candidates) {
        Map<String, Map<String, Object>> best = new LinkedHashMap<>();
        for (Map<String, Object> candidate : candidates) {
            String key = CatalogMapSupport.str(candidate.get(CatalogKeys.SOURCE_TYPE)).toLowerCase(Locale.ROOT)
                    + "|"
                    + CatalogMapSupport.str(candidate.get(CatalogKeys.SET_ID));
            if (key.endsWith("|")) {
                continue;
            }
            Map<String, Object> prev = best.get(key);
            if (prev == null
                    || CatalogMapSupport.toInt(candidate.get(CatalogKeys.SEARCH_SCORE), 0)
                            > CatalogMapSupport.toInt(prev.get(CatalogKeys.SEARCH_SCORE), 0)) {
                best.put(key, candidate);
            }
        }
        return best.values().stream()
                .sorted(Comparator.comparingInt((Map<String, Object> row) ->
                        -CatalogMapSupport.toInt(row.get(CatalogKeys.SEARCH_SCORE), 0)))
                .toList();
    }

    private static List<Map<String, Object>> dedupeBySetId(List<Map<String, Object>> hits) {
        Map<String, Map<String, Object>> best = new LinkedHashMap<>();
        for (Map<String, Object> hit : hits) {
            String key = CatalogMapSupport.str(hit.get(CatalogKeys.SET_ID)).toLowerCase(Locale.ROOT);
            if (key.isBlank()) {
                continue;
            }
            Map<String, Object> prev = best.get(key);
            if (prev == null
                    || CatalogMapSupport.toInt(hit.get(CatalogKeys.SEARCH_SCORE), 0)
                            > CatalogMapSupport.toInt(prev.get(CatalogKeys.SEARCH_SCORE), 0)) {
                Map<String, Object> chosen = new LinkedHashMap<>(hit);
                mergeManagerSeedFlags(prev, chosen);
                best.put(key, chosen);
            } else {
                mergeManagerSeedFlags(hit, prev);
            }
        }
        return new ArrayList<>(best.values());
    }

    private static void mergeManagerSeedFlags(Map<String, Object> donor, Map<String, Object> target) {
        if (donor == null || target == null) {
            return;
        }
        if (Boolean.TRUE.equals(donor.get("manager_intent_seed"))) {
            target.put("manager_intent_seed", true);
        }
        if (Boolean.TRUE.equals(donor.get("manager_macro_scaffold"))) {
            target.put("manager_macro_scaffold", true);
        }
    }

    /**
     * Re-insert Manager core macro seeds (e.g. {@code nama_10_gdp}) that scoring dropped below the
     * lane candidate cap. Seeds are prepended so later global preview selection still sees them.
     */
    static List<Map<String, Object>> retainManagerCoreMacroSeeds(
            String source,
            List<Map<String, Object>> ranked,
            List<Map<String, Object>> sidecarSeeds,
            int limit) {
        return retainManagerPinnedSeeds(source, "", ranked, sidecarSeeds, limit);
    }

    /**
     * Re-pin Manager macro + intent preview seeds that scoring dropped below the lane candidate cap.
     */
    static List<Map<String, Object>> retainManagerPinnedSeeds(
            String source,
            String query,
            List<Map<String, Object>> ranked,
            List<Map<String, Object>> sidecarSeeds,
            int limit) {
        int cap = Math.max(1, limit);
        List<Map<String, Object>> base = ranked == null ? List.of() : ranked;
        List<String> seedIds = ExploreManagerDiscoveryTerms.managerPinnedSeedsForSource(query, source);
        Set<String> want = new LinkedHashSet<>();
        for (String seedId : seedIds) {
            if (seedId != null && !seedId.isBlank()) {
                want.add(seedId.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (want.isEmpty()) {
            return base.size() > cap ? new ArrayList<>(base.subList(0, cap)) : base;
        }

        List<Map<String, Object>> pinned = new ArrayList<>();
        Set<String> pinnedIds = new LinkedHashSet<>();
        Set<String> intentSeedIds = new LinkedHashSet<>(
                ExploreManagerDiscoveryTerms.intentPreviewSeedsForSource(query, source).stream()
                        .map(id -> id.toLowerCase(Locale.ROOT))
                        .toList());
        if (sidecarSeeds != null) {
            for (Map<String, Object> row : sidecarSeeds) {
                String id = CatalogMapSupport.str(row.get(CatalogKeys.SET_ID)).toLowerCase(Locale.ROOT);
                if (want.contains(id) && pinnedIds.add(id)) {
                    Map<String, Object> stamped = new LinkedHashMap<>(row);
                    if (intentSeedIds.contains(id)) {
                        stamped.put("manager_intent_seed", true);
                    }
                    pinned.add(stamped);
                }
            }
        }
        for (Map<String, Object> row : base) {
            String id = CatalogMapSupport.str(row.get(CatalogKeys.SET_ID)).toLowerCase(Locale.ROOT);
            if (want.contains(id) && pinnedIds.add(id)) {
                Map<String, Object> stamped = new LinkedHashMap<>(row);
                if (intentSeedIds.contains(id)) {
                    stamped.put("manager_intent_seed", true);
                }
                pinned.add(stamped);
            }
        }
        if (pinned.isEmpty()) {
            return base.size() > cap ? new ArrayList<>(base.subList(0, cap)) : base;
        }

        List<Map<String, Object>> rest = new ArrayList<>();
        for (Map<String, Object> row : base) {
            String id = CatalogMapSupport.str(row.get(CatalogKeys.SET_ID)).toLowerCase(Locale.ROOT);
            if (!want.contains(id)) {
                rest.add(row);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(pinned.size() + rest.size());
        out.addAll(pinned);
        out.addAll(rest);
        return out.size() > cap ? new ArrayList<>(out.subList(0, cap)) : out;
    }

    private static List<Map<String, Object>> sortByFinalRank(List<Map<String, Object>> rows) {
        return rows.stream()
                .sorted(Comparator.comparingInt((Map<String, Object> row) -> {
                            int rank = CatalogMapSupport.toInt(row.get("final_rank"), Integer.MAX_VALUE);
                            return rank > 0 ? rank : Integer.MAX_VALUE;
                        })
                        .thenComparing(
                        row -> CatalogTextUtils.rowTitle(row),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    static List<Map<String, Object>> keepOnlyPreviewSafePossible(
            List<Map<String, Object>> rows, List<Map<String, Object>> discarded) {
        return keepOnlyPreviewSafePossible(rows, discarded, false, "");
    }

    static List<Map<String, Object>> keepOnlyPreviewSafePossible(
            List<Map<String, Object>> rows, List<Map<String, Object>> discarded, boolean managerDiscovery) {
        return keepOnlyPreviewSafePossible(rows, discarded, managerDiscovery, "");
    }

    static List<Map<String, Object>> keepOnlyPreviewSafePossible(
            List<Map<String, Object>> rows,
            List<Map<String, Object>> discarded,
            boolean managerDiscovery,
            String query) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int macroScaffoldKept = 0;
        int topicKeepKept = 0;
        for (Map<String, Object> row : rows) {
            if (CatalogDeepSearchFinalRanker.hasVerifiedDataPreview(row)
                    && (CatalogDeepSearchFinalRanker.isSemanticallyActionable(row)
                            || CatalogAiDataResolver.isAiRelevant(row))) {
                if (managerDiscovery && ExploreManagerDiscoveryTerms.isIntentExcludedRow(query, row)
                        && !ExploreManagerDiscoveryTerms.isMacroScaffoldRow(row)) {
                    Map<String, Object> demoted = new LinkedHashMap<>(row);
                    demoted.put("manager_intent_excluded", true);
                    demoted.putIfAbsent("discard_reason", "manager_intent_exclude");
                    discarded.add(demoted);
                    continue;
                }
                out.add(row);
                continue;
            }
            if (managerDiscovery
                    && CatalogDeepSearchFinalRanker.hasVerifiedDataPreview(row)
                    && ExploreManagerDiscoveryTerms.isMacroScaffoldRow(row)
                    && macroScaffoldKept < ExploreManagerDiscoveryTerms.MAX_MANAGER_MACRO_SCAFFOLD) {
                Map<String, Object> kept = new LinkedHashMap<>(row);
                kept.put("manager_macro_scaffold", true);
                out.add(kept);
                macroScaffoldKept++;
                continue;
            }
            if (managerDiscovery
                    && CatalogDeepSearchFinalRanker.hasVerifiedDataPreview(row)
                    && !ExploreManagerDiscoveryTerms.isIntentExcludedRow(query, row)
                    && (ExploreManagerDiscoveryTerms.isTopicIntentRow(query, row)
                            || ExploreManagerDiscoveryTerms.matchesIntentPreviewSeed(query, row))
                    && topicKeepKept < ExploreManagerDiscoveryTerms.MAX_MANAGER_TOPIC_KEEP) {
                Map<String, Object> kept = new LinkedHashMap<>(row);
                kept.put("manager_topic_keep", true);
                if (ExploreManagerDiscoveryTerms.matchesIntentPreviewSeed(query, row)) {
                    kept.put("manager_intent_seed", true);
                }
                out.add(kept);
                topicKeepKept++;
                continue;
            }
            if (hasExplicitSemanticSignal(row)
                    && CatalogDeepSearchFinalRanker.isSemanticallyActionable(row)
                    && !CatalogAiDataResolver.isAiRejected(row)) {
                out.add(row);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(row);
            if (CatalogDeepSearchFinalRanker.hasVerifiedDataPreview(row)) {
                copy.putIfAbsent("discard_reason", "semantic_not_actionable");
                copy.putIfAbsent(
                        "reason",
                        "Kandidat zustal mimo hlavni vysledky, protoze live nahled sice funguje, ale tematicky neodpovida dotazu.");
            } else {
                copy.putIfAbsent("discard_reason", "preview_not_verified");
                copy.putIfAbsent(
                        "reason",
                        "Kandidat zustal mimo hlavni vysledky, protoze live nahled nevratil pouzitelnou casovou radu.");
            }
            discarded.add(copy);
        }
        return out;
    }

    private static boolean hasExplicitSemanticSignal(Map<String, Object> row) {
        return row != null
                && (row.containsKey("semantic_match_level")
                        || row.containsKey("topic_match")
                        || row.containsKey("metric_match")
                        || row.containsKey("domain_match")
                        || CatalogAiDataResolver.isAiRelevant(row));
    }

    private static List<Map<String, Object>> semanticallyUsefulDiscardedCandidates(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .filter(row -> row != null
                        && CatalogDeepSearchFinalRanker.isSemanticallyActionable(row)
                        && !CatalogAiDataResolver.isAiRejected(row))
                .limit(8)
                .toList();
    }

    private boolean shouldAddCommodityFallback(String query, List<String> requestedSources, List<String> plannedSources) {
        if (!commoditySearch.commodityQueryAllowed(query)) {
            return false;
        }
        if (requestedSources == null || requestedSources.isEmpty()) {
            return true;
        }
        return requestedSources.contains("commodities") || plannedSources.contains("commodities");
    }

    private static Map<String, Object> emptyResponse(String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", error);
        out.put("search_plan", null);
        out.put("plan", null);
        out.put(CatalogKeys.VERIFIED, List.of());
        out.put(CatalogKeys.POSSIBLE, List.of());
        out.put("grouped_results", Map.of(CatalogKeys.VERIFIED, List.of(), "candidates", List.of(), "beta", List.of()));
        out.put("source_statuses", List.of());
        out.put("catalog_index_warnings", List.of());
        out.put("live_preview_verification", true);
        return out;
    }

    private static List<String> readSources(Map<String, Object> request) {
        Object raw = request.get(CatalogKeys.SOURCES);
        if (raw == null) {
            raw = request.get("catalog_sources");
        }
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(v -> CatalogSourceRegistry.normalizeSearchSource(String.valueOf(v)))
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private static int parseLimit(Object raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(String.valueOf(raw));
            return Math.max(2, Math.min(value, 20));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static boolean parseBoolean(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (List.of("1", "true", "yes", "on").contains(value)) {
            return true;
        }
        if (List.of("0", "false", "no", "off").contains(value)) {
            return false;
        }
        return defaultValue;
    }
}
