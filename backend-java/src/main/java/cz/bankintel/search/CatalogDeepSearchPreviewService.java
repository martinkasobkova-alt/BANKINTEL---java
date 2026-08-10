package cz.bankintel.search;

import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.connector.ConnectorFactory;
import cz.bankintel.util.BankIntelEnvVars;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Live ověření deep-search kandidátů — ref Python {@code run_previews_phase}. */
@Service
@RequiredArgsConstructor
public class CatalogDeepSearchPreviewService {

    private static final Logger log = LoggerFactory.getLogger(CatalogDeepSearchPreviewService.class);
    private static final int DEFAULT_PREVIEW_POOL = 18;
    private static final int DEFAULT_PREVIEW_CONCURRENCY = 6;
    private static final int DEFAULT_PREVIEW_TIMEOUT_SEC = 12;
    private static final int DEFAULT_VERIFIED_TARGET = 5;
    private static final int DEFAULT_WALL_BUDGET_SEC = 45;
    /** Manager Explorer can spend ~+30s for broader multi-source verification. */
    private static final int MANAGER_PREVIEW_POOL = 24;
    private static final int MANAGER_VERIFIED_TARGET = 10;
    private static final int MANAGER_WALL_BUDGET_SEC = 60;

    private final CatalogPreviewOrchestrator previewOrchestrator;
    private final ConnectorFactory connectorFactory;
    private final Environment environment;
    private final ExecutorService previewExecutor = Executors.newFixedThreadPool(resolvePreviewConcurrency());
    /** Dedicated executor for the actual (per-candidate) preview HTTP fetch, kept separate from
     * {@link #previewExecutor}. verifyOne() itself already runs ON previewExecutor (submitted from
     * verifyCandidates' fan-out loop); if the inner preview fetch were submitted to that *same*
     * bounded pool, a full pool of candidates would self-deadlock — every outer task occupies a
     * pool thread blocked on .join(), so none of the inner fetch tasks can ever be scheduled, and
     * every candidate silently "times out" after the full budget regardless of how fast the
     * underlying resolution/fetch actually is. This was the true root cause behind Zjištění C
     * ("nezaměstnanost Slovensko" looked like a cold-start latency problem, but every eurostat
     * preview was really just queued forever behind a saturated shared pool).
     */
    private final ExecutorService previewFetchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, CachedPreview> previewOutcomeCache = new ConcurrentHashMap<>();

    public PreviewPhaseResult verifyCandidates(
            List<Map<String, Object>> candidates, String query, Consumer<Map<String, Object>> onPreviewDone) {
        return verifyCandidates(candidates, query, onPreviewDone, false);
    }

    public PreviewPhaseResult verifyCandidates(
            List<Map<String, Object>> candidates,
            String query,
            Consumer<Map<String, Object>> onPreviewDone,
            boolean managerDiscovery) {
        if (candidates == null || candidates.isEmpty()) {
            return PreviewPhaseResult.empty();
        }

        Map<String, Object> geoIntent = CatalogGeoIntent.detectGeoIntent(query);
        CatalogQueryRelevanceProfile semanticProfile = CatalogQueryRelevanceProfile.from(query, geoIntent);
        List<Map<String, Object>> orderedFetchable = candidates.stream()
                .filter(c -> CatalogPreviewSetIdSupport.isPreviewFetchable(
                        CatalogMapSupport.str(c.get(CatalogKeys.SOURCE_TYPE)),
                        CatalogMapSupport.str(c.get(CatalogKeys.SET_ID)),
                        c))
                .sorted(Comparator.comparingInt((Map<String, Object> row) ->
                                -previewSelectionScore(row, query, geoIntent, semanticProfile))
                        .thenComparingInt(row -> -CatalogMapSupport.toInt(row.get(CatalogKeys.SEARCH_SCORE), 0)))
                .toList();
        List<Map<String, Object>> fetchable = dedupePreviewIdentities(orderedFetchable);
        List<Map<String, Object>> pool =
                selectBalancedPreviewPool(fetchable, resolvePreviewPoolSize(managerDiscovery), managerDiscovery);

        List<Map<String, Object>> verified = new ArrayList<>();
        List<Map<String, Object>> possible = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Long> previewMsBySource = new LinkedHashMap<>();
        int previewSuccessCount = 0;
        int previewActionableSuccessCount = 0;
        int previewTimeoutCount = 0;
        int previewCacheHitCount = 0;
        int previewRemoteFetchCount = 0;
        long previewQueueWaitMs = 0L;
        long previewFetchMs = 0L;
        long previewParseMs = 0L;
        long previewValidationMs = 0L;
        List<Map<String, Object>> previewItems = new ArrayList<>();
        Map<String, Map<String, Object>> previewBySource = new LinkedHashMap<>();
        long phaseStarted = System.currentTimeMillis();
        long wallDeadline = phaseStarted + resolveWallBudgetMs(managerDiscovery);
        int verifiedTarget = resolveVerifiedTarget(managerDiscovery);

        List<CompletableFuture<PreviewItemResult>> futures = new ArrayList<>();
        Map<CompletableFuture<PreviewItemResult>, Map<String, Object>> candidateByFuture = new LinkedHashMap<>();
        for (Map<String, Object> candidate : pool) {
            long queuedAt = System.currentTimeMillis();
            CompletableFuture<PreviewItemResult> future =
                    CompletableFuture.supplyAsync(() -> verifyOne(candidate, query, queuedAt), previewExecutor);
            futures.add(future);
            candidateByFuture.put(future, candidate);
        }

        List<CompletableFuture<PreviewItemResult>> pending = new ArrayList<>(futures);
        while (!pending.isEmpty()
                && previewActionableSuccessCount < verifiedTarget
                && System.currentTimeMillis() < wallDeadline) {
            long waitMs = Math.max(50, Math.min(1500, wallDeadline - System.currentTimeMillis()));
            CompletableFuture<Object> any = CompletableFuture.anyOf(pending.toArray(new CompletableFuture[0]));
            try {
                any.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                continue;
            } catch (Exception ex) {
                log.debug("preview batch wait failed: {}", ex.getMessage());
                continue;
            }
            Iterator<CompletableFuture<PreviewItemResult>> it = pending.iterator();
            while (it.hasNext()) {
                CompletableFuture<PreviewItemResult> future = it.next();
                if (!future.isDone()) {
                    continue;
                }
                it.remove();
                try {
                    PreviewItemResult item = future.join();
                    if (Boolean.TRUE.equals(item.row().get("preview_cache_hit"))) {
                        previewCacheHitCount++;
                    } else if (Boolean.TRUE.equals(item.row().get("preview_remote_fetch"))) {
                        previewRemoteFetchCount++;
                    }
                    if (item.verified()) {
                        verified.add(item.row());
                        previewSuccessCount++;
                        if (isPreviewSemanticallyActionable(item.row(), query, geoIntent, semanticProfile)) {
                            previewActionableSuccessCount++;
                        }
                    } else {
                        possible.add(item.row());
                        if ("preview_timeout".equals(item.failureCode())) {
                            previewTimeoutCount++;
                        }
                    }
                    String source = CatalogMapSupport.str(item.row().get(CatalogKeys.SOURCE_TYPE));
                    previewMsBySource.merge(source, item.elapsedMs(), Long::sum);
                    previewQueueWaitMs += item.queueWaitMs();
                    previewFetchMs += item.fetchMs();
                    previewParseMs += item.parseMs();
                    previewValidationMs += item.validationMs();
                    previewItems.add(item.telemetry());
                    mergePreviewSourceTelemetry(previewBySource, source, item);
                    if (onPreviewDone != null) {
                        onPreviewDone.accept(item.row());
                    }
                } catch (Exception ex) {
                    log.debug("deep search preview task failed: {}", ex.getMessage());
                }
            }
        }

        // Reap results that completed concurrently with the verified-target decision. Previously these rows
        // stayed in pending and then vanished from both verified and possible buckets.
        Iterator<CompletableFuture<PreviewItemResult>> completed = pending.iterator();
        while (completed.hasNext()) {
            CompletableFuture<PreviewItemResult> future = completed.next();
            if (!future.isDone()) {
                continue;
            }
            completed.remove();
            try {
                PreviewItemResult item = future.join();
                if (Boolean.TRUE.equals(item.row().get("preview_cache_hit"))) {
                    previewCacheHitCount++;
                } else if (Boolean.TRUE.equals(item.row().get("preview_remote_fetch"))) {
                    previewRemoteFetchCount++;
                }
                if (item.verified()) {
                    verified.add(item.row());
                    previewSuccessCount++;
                    if (isPreviewSemanticallyActionable(item.row(), query, geoIntent, semanticProfile)) {
                        previewActionableSuccessCount++;
                    }
                } else {
                    possible.add(item.row());
                    if ("preview_timeout".equals(item.failureCode())) {
                        previewTimeoutCount++;
                    }
                }
                String source = CatalogMapSupport.str(item.row().get(CatalogKeys.SOURCE_TYPE));
                previewMsBySource.merge(source, item.elapsedMs(), Long::sum);
                previewQueueWaitMs += item.queueWaitMs();
                previewFetchMs += item.fetchMs();
                previewParseMs += item.parseMs();
                previewValidationMs += item.validationMs();
                previewItems.add(item.telemetry());
                mergePreviewSourceTelemetry(previewBySource, source, item);
                if (onPreviewDone != null) {
                    onPreviewDone.accept(item.row());
                }
            } catch (Exception ex) {
                log.debug("deep search completed preview task failed: {}", ex.getMessage());
            }
        }

        int previewUnfinishedCount = pending.size();
        // Manager discovery: never cancel a pending core GDP / intent-preview-seed preview just
        // because the actionable target was already filled by other hits — GDP is a scaffold for
        // every Manager query, and an intent-preview-seed row (e.g. a NACE C29 series pinned for an
        // automotive query) is exactly as load-bearing for ITS query as GDP is for every query; both
        // were deliberately pinned upstream specifically so scoring/target-based cutoffs can't drop
        // them, so this cancellation-avoidance rescue must protect both the same way.
        if (managerDiscovery) {
            List<CompletableFuture<PreviewItemResult>> scaffoldPending = new ArrayList<>();
            for (CompletableFuture<PreviewItemResult> future : pending) {
                Map<String, Object> candidate = candidateByFuture.get(future);
                if (candidate != null
                        && cz.bankintel.explore.ExploreManagerDiscoveryTerms.isManagerPinnedSeedRow(candidate)) {
                    scaffoldPending.add(future);
                }
            }
            for (CompletableFuture<PreviewItemResult> future : scaffoldPending) {
                long waitMs = Math.max(50, wallDeadline - System.currentTimeMillis());
                if (waitMs <= 0) {
                    break;
                }
                try {
                    future.get(waitMs, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    // Fall through to cancellation / reap below.
                }
            }
            Iterator<CompletableFuture<PreviewItemResult>> scaffoldDone = pending.iterator();
            while (scaffoldDone.hasNext()) {
                CompletableFuture<PreviewItemResult> future = scaffoldDone.next();
                if (!future.isDone()) {
                    continue;
                }
                scaffoldDone.remove();
                try {
                    PreviewItemResult item = future.join();
                    if (Boolean.TRUE.equals(item.row().get("preview_cache_hit"))) {
                        previewCacheHitCount++;
                    } else if (Boolean.TRUE.equals(item.row().get("preview_remote_fetch"))) {
                        previewRemoteFetchCount++;
                    }
                    if (item.verified()) {
                        verified.add(item.row());
                        previewSuccessCount++;
                        if (isPreviewSemanticallyActionable(item.row(), query, geoIntent, semanticProfile)) {
                            previewActionableSuccessCount++;
                        }
                    } else {
                        possible.add(item.row());
                        if ("preview_timeout".equals(item.failureCode())) {
                            previewTimeoutCount++;
                        }
                    }
                    String source = CatalogMapSupport.str(item.row().get(CatalogKeys.SOURCE_TYPE));
                    previewMsBySource.merge(source, item.elapsedMs(), Long::sum);
                    previewQueueWaitMs += item.queueWaitMs();
                    previewFetchMs += item.fetchMs();
                    previewParseMs += item.parseMs();
                    previewValidationMs += item.validationMs();
                    previewItems.add(item.telemetry());
                    mergePreviewSourceTelemetry(previewBySource, source, item);
                    if (onPreviewDone != null) {
                        onPreviewDone.accept(item.row());
                    }
                } catch (Exception ex) {
                    log.debug("deep search scaffold preview task failed: {}", ex.getMessage());
                }
            }
            previewUnfinishedCount = pending.size();
        }
        long cancellationStarted = System.currentTimeMillis();
        for (CompletableFuture<PreviewItemResult> future : pending) {
            future.cancel(true);
            Map<String, Object> candidate = candidateByFuture.get(future);
            if (candidate != null) {
                Map<String, Object> row = CatalogDeepSearchService.decorateCatalogCandidate(candidate, query);
                row.put("preview_status", "cancelled_after_sufficient_verified");
                row.put("status", "candidate");
                row.put("result_tier", "candidate");
                row.put("preview_available", false);
                row.put("preview_error", "Preview nebyl dokončen před ukončením ověřovací fáze.");
                possible.add(row);
            }
        }
        long cancellationDelayMs = System.currentTimeMillis() - cancellationStarted;

        Set<String> previewedKeys = new LinkedHashSet<>();
        for (Map<String, Object> row : pool) {
            previewedKeys.add(rowKey(row));
        }
        for (Map<String, Object> candidate : candidates) {
            String key = rowKey(candidate);
            if (previewedKeys.contains(key)) {
                continue;
            }
            Map<String, Object> row = CatalogDeepSearchService.decorateCatalogCandidate(candidate, query);
            row.put("preview_status", "not_selected_for_preview");
            row.put("status", "candidate");
            row.put("result_tier", "candidate");
            row.put("preview_available", false);
            possible.add(row);
            if (possible.size() >= 20) {
                break;
            }
        }

        if (previewTimeoutCount > 0) {
            notes.add("preview_timeout");
        }
        if (System.currentTimeMillis() >= wallDeadline) {
            notes.add("preview_wall_budget");
        }
        if (verified.isEmpty() && !possible.isEmpty()) {
            notes.add("catalog_only_candidates");
        }

        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("preview_pool_size", pool.size());
        timing.put("preview_ms_total", System.currentTimeMillis() - phaseStarted);
        timing.put("preview_ms_by_source", previewMsBySource);
        timing.put("preview_queue_wait_ms", previewQueueWaitMs);
        timing.put("preview_fetch_ms", previewFetchMs);
        timing.put("preview_parse_ms", previewParseMs);
        timing.put("preview_validation_ms", previewValidationMs);
        timing.put("preview_cancellation_delay_ms", cancellationDelayMs);
        timing.put("preview_items", previewItems);
        timing.put("preview_by_source", previewBySource);
        timing.put("preview_success_count", previewSuccessCount);
        timing.put("preview_actionable_success_count", previewActionableSuccessCount);
        timing.put("preview_timeout_count", previewTimeoutCount);
        timing.put("preview_cache_hit_count", previewCacheHitCount);
        timing.put("preview_cache_bypassed", previewOutcomeCacheBypassedForColdPathProfile());
        timing.put("preview_remote_fetch_count", previewRemoteFetchCount);
        timing.put("preview_unfinished_count", previewUnfinishedCount);
        timing.put("preview_verified_target", verifiedTarget);
        timing.put("preview_wall_budget_ms", resolveWallBudgetMs(managerDiscovery));

        verified = CatalogSearchVariantDedup.consolidateDisplayRows(verified);
        possible = CatalogSearchVariantDedup.consolidateDisplayRows(possible);
        // Cap after prioritising: otherwise fast FX/EER previews fill the 12-slot verified
        // window and crowd out slower but decision-critical macro rows (industrial production,
        // HICP, unemployment) that Manager Explorer still needs as a scaffold.
        verified = prioritizeVerifiedForDisplay(verified, query, geoIntent, semanticProfile);

        return new PreviewPhaseResult(
                verified.stream().limit(12).toList(),
                possible.stream().limit(20).toList(),
                timing,
                notes);
    }

    static List<Map<String, Object>> prioritizeVerifiedForDisplay(
            List<Map<String, Object>> verified,
            String query,
            Map<String, Object> geoIntent,
            CatalogQueryRelevanceProfile semanticProfile) {
        if (verified == null || verified.size() <= 1) {
            return verified == null ? List.of() : verified;
        }
        List<Map<String, Object>> ranked = new ArrayList<>(verified);
        ranked.sort(Comparator.comparingInt((Map<String, Object> row) -> {
                    if (isPreviewSemanticallyActionable(row, query, geoIntent, semanticProfile)) {
                        return 0;
                    }
                    if (cz.bankintel.explore.ExploreManagerDiscoveryTerms.isCoreMacroSeedRow(row)) {
                        return 1;
                    }
                    if (cz.bankintel.explore.ExploreManagerDiscoveryTerms.isMacroScaffoldRow(row)) {
                        return 2;
                    }
                    return 3;
                })
                .thenComparingInt(row -> -CatalogMapSupport.toInt(row.get(CatalogKeys.SEARCH_SCORE), 0)));
        return ranked;
    }

    static boolean isPreviewSemanticallyActionable(
            Map<String, Object> row,
            String query,
            Map<String, Object> geoIntent,
            CatalogQueryRelevanceProfile semanticProfile) {
        if (CatalogAiDataResolver.isAiRelevant(row)) {
            return true;
        }
        String structuredStatus = CatalogMapSupport.str(row.get(CatalogKeys.STRUCTURED_SEMANTIC_STATUS));
        if ("mismatch".equalsIgnoreCase(structuredStatus)) {
            return false;
        }
        if ("match".equalsIgnoreCase(structuredStatus)) {
            return true;
        }
        CatalogQueryRelevanceProfile profile =
                semanticProfile == null ? CatalogQueryRelevanceProfile.from(query, geoIntent) : semanticProfile;
        CatalogQueryRelevanceProfile.SemanticFit fit =
                profile.match(CatalogSemanticRowText.title(row), CatalogSemanticRowText.haystack(row));
        CatalogQueryIntent.IntentScoreAdjustments intentAdj =
                CatalogQueryIntent.computeIntentScoreAdjustments(
                        CatalogSemanticRowText.haystack(row), query, geoIntent);
        if (intentAdj.negativePenalty() > 0) {
            return false;
        }
        if (profile.groupCount() == 0) {
            return true;
        }
        if (fit.totalHits() == 0) {
            return false;
        }
        if (profile.metricGroupCount() > 0 && fit.metricHits() < profile.metricGroupCount()) {
            return false;
        }
        return profile.domainGroupCount() <= 0 || fit.domainHits() >= profile.domainGroupCount();
    }

    @PreDestroy
    void shutdownPreviewExecutor() {
        previewExecutor.shutdown();
        previewFetchExecutor.shutdown();
        try {
            if (!previewExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                previewExecutor.shutdownNow();
            }
            if (!previewFetchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                previewFetchExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            previewExecutor.shutdownNow();
            previewFetchExecutor.shutdownNow();
        }
    }

    static List<Map<String, Object>> selectBalancedPreviewPool(
            List<Map<String, Object>> fetchable, int poolSize) {
        return selectBalancedPreviewPool(fetchable, poolSize, false);
    }

    static List<Map<String, Object>> selectBalancedPreviewPool(
            List<Map<String, Object>> fetchable, int poolSize, boolean pinCoreMacro) {
        if (fetchable == null || fetchable.isEmpty() || poolSize <= 0) {
            return List.of();
        }
        if (!pinCoreMacro) {
            return selectBalancedPreviewPoolUnpinned(fetchable, poolSize);
        }
        List<Map<String, Object>> pinned = new ArrayList<>();
        List<Map<String, Object>> rest = new ArrayList<>();
        Set<String> pinnedIds = new LinkedHashSet<>();
        for (Map<String, Object> row : fetchable) {
            if (cz.bankintel.explore.ExploreManagerDiscoveryTerms.isManagerPinnedSeedRow(row)
                    || cz.bankintel.explore.ExploreManagerDiscoveryTerms.isCoreGdpDatasetRow(row)) {
                String id = CatalogMapSupport.str(row.get(CatalogKeys.SET_ID)).toLowerCase(Locale.ROOT);
                if (!id.isBlank() && pinnedIds.add(id)) {
                    pinned.add(row);
                    continue;
                }
            }
            rest.add(row);
        }
        if (pinned.isEmpty()) {
            return selectBalancedPreviewPoolUnpinned(fetchable, poolSize);
        }
        int remaining = Math.max(0, poolSize - pinned.size());
        List<Map<String, Object>> balanced = selectBalancedPreviewPoolUnpinned(rest, remaining);
        List<Map<String, Object>> pool = new ArrayList<>(pinned.size() + balanced.size());
        pool.addAll(pinned);
        pool.addAll(balanced);
        return pool.size() > poolSize ? pool.subList(0, poolSize) : pool;
    }

    private static List<Map<String, Object>> selectBalancedPreviewPoolUnpinned(
            List<Map<String, Object>> fetchable, int poolSize) {
        if (fetchable == null || fetchable.isEmpty() || poolSize <= 0) {
            return List.of();
        }
        if (fetchable.size() <= poolSize) {
            return fetchable;
        }
        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> row : fetchable) {
            String source = CatalogMapSupport.str(row.get(CatalogKeys.SOURCE_TYPE)).toLowerCase(Locale.ROOT);
            buckets.computeIfAbsent(source, key -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> pool = new ArrayList<>(poolSize);
        while (pool.size() < poolSize) {
            boolean added = false;
            for (List<Map<String, Object>> bucket : buckets.values()) {
                if (bucket.isEmpty()) {
                    continue;
                }
                pool.add(bucket.remove(0));
                added = true;
                if (pool.size() >= poolSize) {
                    break;
                }
            }
            if (!added) {
                break;
            }
        }
        return pool;
    }

    static List<Map<String, Object>> dedupePreviewIdentities(List<Map<String, Object>> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> candidate : candidates) {
            unique.putIfAbsent(rowKey(candidate), candidate);
        }
        return List.copyOf(unique.values());
    }

    private PreviewItemResult verifyOne(Map<String, Object> candidate, String query, long queuedAt) {
        long started = System.currentTimeMillis();
        long queueWaitMs = Math.max(0L, started - queuedAt);
        long fetchMs = 0L;
        long parseMs = 0L;
        long validationMs = 0L;
        String fetchType = "none";
        Map<String, Object> row = CatalogDeepSearchService.decorateCatalogCandidate(candidate, query);
        row.put("_telemetry_queue_wait_ms", queueWaitMs);
        row.put("_telemetry_fetch_ms", fetchMs);
        row.put("_telemetry_parse_ms", parseMs);
        row.put("_telemetry_validation_ms", validationMs);
        row.put("_telemetry_fetch_type", fetchType);
        String source = CatalogMapSupport.str(row.get(CatalogKeys.SOURCE_TYPE));
        String connectorType = connectorSourceType(source);
        String setId = CatalogMapSupport.str(row.get(CatalogKeys.SET_ID));
        String resolvedSetId = CatalogPreviewSetIdSupport.resolvePreviewSetId(source, setId, row);
        if (!resolvedSetId.isBlank() && !resolvedSetId.equals(setId)) {
            // Keep the enrichment alias for display/debug, but fetch the real Eurostat dataset.
            row.putIfAbsent("_original_set_id", setId);
            row.put(CatalogKeys.SET_ID, resolvedSetId);
            setId = resolvedSetId;
        }
        Map<String, Object> payload = buildPreviewPayload(row, connectorType, query);
        Map<String, Object> geoIntent = payload.get("geo_intent") instanceof Map<?, ?> geoMap ? castMap(geoMap) : Map.of();
        String cacheKey = cacheKey(source, setId, payload);

        boolean bypassPreviewCache = previewOutcomeCacheBypassedForColdPathProfile();
        CachedPreview cached = bypassPreviewCache ? null : previewOutcomeCache.get(cacheKey);
        if (cached != null && cached.rowCount() > 0) {
            row.put("_telemetry_fetch_type", "cache");
            row.put("preview_cache_hit", true);
            long validationStarted = System.nanoTime();
            CatalogPreviewGeoValidator.GeoPreviewCheck geoCheck =
                    CatalogPreviewGeoValidator.validate(row, cached.previewPayload(), geoIntent);
            row.put("_telemetry_validation_ms", elapsedMillis(validationStarted));
            attachGeoDiagnostics(row, geoCheck);
            if (!geoCheck.matches()) {
                if (!allowManagerPinnedGeoSoftPass(row, query)) {
                    return fail(row, started, "preview_geo_mismatch", geoMismatchMessage(geoCheck));
                }
            }
            row.put("preview_status", "verified");
            row.put("preview_available", true);
            row.put("preview_row_count", cached.rowCount());
            if (cached.previewPayload() != null) {
                row.put("preview_payload", new LinkedHashMap<>(cached.previewPayload()));
            }
            row.put("reason", "Řada ověřena z cache náhledu.");
            if (!countsTowardVerifiedTarget(row, query, geoIntent)
                    && !cz.bankintel.explore.ExploreManagerDiscoveryTerms.isManagerPinnedSeedRow(query, row)) {
                row.put("status", "candidate");
                row.put("result_tier", "candidate");
                row.put("demotion_reason", "intent_negative_match");
                row.put(
                        CatalogKeys.WHY_RELEVANT,
                        "Live nahled je dostupny, ale tematicka shoda s dotazem je slaba; ponechano pouze jako nizka relevance.");
                return new PreviewItemResult(false, row, started, "intent_negative_match");
            }
            row.put("status", "verified");
            row.put("result_tier", "verified");
            return new PreviewItemResult(true, row, started, null);
        }

        if (setId.isBlank()) {
            return fail(row, started, "missing_set_id", "Chybí identifikátor datové sady.");
        }
        if (!connectorFactory.isSupported(connectorType)) {
            return fail(row, started, "unsupported_source", "Live náhled pro zdroj '" + source + "' není podporován.");
        }

        try {
            row.put("preview_remote_fetch", true);
            row.put("_telemetry_fetch_type", "remote");
            long fetchStarted = System.nanoTime();
            Map<String, Object> preview = CompletableFuture
                    .supplyAsync(() -> previewOrchestrator.preview(payload), previewFetchExecutor)
                    .orTimeout(resolvePreviewTimeoutSec(), TimeUnit.SECONDS)
                    .join();
            row.put("_telemetry_fetch_ms", elapsedMillis(fetchStarted));
            row.put("_telemetry_fetch_type", previewFetchType(preview));
            long parseStarted = System.nanoTime();
            int rowCount = previewRowCount(preview);
            String previewState = str(preview.get("preview_state"));
            boolean ok = rowCount > 0 && !Set.of("sync_failed", "unsupported", "no_data").contains(previewState);
            row.put("_telemetry_parse_ms", elapsedMillis(parseStarted));
            if (!ok) {
                Map<String, Object> retryPayload = buildResilientPreviewPayload(row, connectorType, query, preview);
                if (retryPayload != null) {
                    log.info(
                            "preview resilience retry source={} set_id={} state={} http={}",
                            source,
                            setId,
                            previewState,
                            firstNonBlank(preview.get("http_status"), preview.get("status")));
                    row.put("preview_retry", true);
                    row.put(
                            "preview_retry_reason",
                            firstNonBlank(preview.get("message"), previewState, "empty_preview"));
                    Object httpStatus = firstPresent(preview.get("http_status"), preview.get("status"));
                    if (httpStatus != null && !str(httpStatus).isBlank()) {
                        row.put("preview_http_status", httpStatus);
                    }
                    long retryStarted = System.nanoTime();
                    preview = CompletableFuture
                            .supplyAsync(() -> previewOrchestrator.preview(retryPayload), previewFetchExecutor)
                            .orTimeout(resolvePreviewTimeoutSec(), TimeUnit.SECONDS)
                            .join();
                    row.put(
                            "_telemetry_fetch_ms",
                            CatalogMapSupport.toInt(row.get("_telemetry_fetch_ms"), 0)
                                    + elapsedMillis(retryStarted));
                    row.put("_telemetry_fetch_type", previewFetchType(preview));
                    rowCount = previewRowCount(preview);
                    previewState = str(preview.get("preview_state"));
                    ok = rowCount > 0 && !Set.of("sync_failed", "unsupported", "no_data").contains(previewState);
                    if (ok) {
                        row.put("preview_retry_ok", true);
                    }
                }
            }
            if (ok) {
                long validationStarted = System.nanoTime();
                CatalogPreviewGeoValidator.GeoPreviewCheck geoCheck =
                        CatalogPreviewGeoValidator.validate(row, preview, geoIntent);
                row.put("_telemetry_validation_ms", elapsedMillis(validationStarted));
                attachGeoDiagnostics(row, geoCheck);
                if (!geoCheck.matches()) {
                    if (!allowManagerPinnedGeoSoftPass(row, query)) {
                        return fail(row, started, "preview_geo_mismatch", geoMismatchMessage(geoCheck));
                    }
                }
                if (!bypassPreviewCache) {
                    previewOutcomeCache.put(cacheKey, new CachedPreview(rowCount, new LinkedHashMap<>(preview)));
                }
                row.put("preview_status", "verified");
                row.put("preview_available", true);
                row.put("preview_row_count", rowCount);
                row.put("preview_payload", preview);
                row.put("reason", "Řada ověřena náhledem aplikace (" + rowCount + " řádků v ukázce).");
                row.put("verify_note", row.get("reason"));
                if (!countsTowardVerifiedTarget(row, query, geoIntent)
                        && !cz.bankintel.explore.ExploreManagerDiscoveryTerms.isManagerPinnedSeedRow(query, row)) {
                    row.put("status", "candidate");
                    row.put("result_tier", "candidate");
                    row.put("demotion_reason", "intent_negative_match");
                    row.put(
                            CatalogKeys.WHY_RELEVANT,
                            "Live nahled je dostupny, ale tematicka shoda s dotazem je slaba; ponechano pouze jako nizka relevance.");
                    return new PreviewItemResult(false, row, started, "intent_negative_match");
                }
                row.put("status", "verified");
                row.put("result_tier", "verified");
                return new PreviewItemResult(true, row, started, null);
            }
            String err = firstNonBlank(preview.get("message"), "Prázdná odpověď náhledu");
            Object httpStatus = firstPresent(preview.get("http_status"), preview.get("status"));
            if (httpStatus != null && !str(httpStatus).isBlank()) {
                row.put("preview_http_status", httpStatus);
            }
            log.info(
                    "preview failed source={} set_id={} state={} http={} msg={}",
                    source,
                    setId,
                    previewState,
                    httpStatus,
                    err);
            return fail(row, started, "empty_preview", err);
        } catch (Exception ex) {
            if (!(row.get("_telemetry_fetch_ms") instanceof Number measured) || measured.longValue() == 0L) {
                row.put("_telemetry_fetch_ms", Math.max(0L, System.currentTimeMillis() - started));
            }
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof TimeoutException || cause.getCause() instanceof TimeoutException) {
                return fail(row, started, "preview_timeout", "Ověření náhledu překročilo čas — položka je jen kandidát.");
            }
            if (cause instanceof ResponseStatusException rse) {
                return fail(row, started, "preview_error", rse.getReason() != null ? rse.getReason() : rse.getMessage());
            }
            log.debug("deep search preview failed {}/{}: {}", source, setId, cause.getMessage());
            return fail(row, started, "preview_error", str(cause.getMessage()));
        }
    }

    public boolean previewOutcomeCacheBypassedForColdPathProfile() {
        return environment.acceptsProfiles(Profiles.of("cold-path-profile"));
    }

    static int previewSelectionScore(
            Map<String, Object> row,
            String query,
            Map<String, Object> geoIntent,
            CatalogQueryRelevanceProfile semanticProfile) {
        int score = CatalogMapSupport.toInt(row.get(CatalogKeys.SEARCH_SCORE), 0);
        String structuredStatus = CatalogMapSupport.str(row.get(CatalogKeys.STRUCTURED_SEMANTIC_STATUS));
        if ("match".equalsIgnoreCase(structuredStatus)) {
            score += 4_000;
        } else if ("partial".equalsIgnoreCase(structuredStatus)) {
            score += 1_000;
        } else if ("mismatch".equalsIgnoreCase(structuredStatus)) {
            score -= 8_000;
        }
        CatalogQueryRelevanceProfile.SemanticFit fit = semanticProfile.match(
                CatalogSemanticRowText.title(row), CatalogSemanticRowText.haystack(row));
        score += CatalogResultSpecificityScorer.adjustment(query, row);
        score += fit.titleHits() * 500;
        score += Math.max(0, fit.totalHits() - fit.titleHits()) * 180;
        if (semanticProfile.groupCount() > 0 && fit.totalHits() >= semanticProfile.groupCount()) {
            score += 900;
        } else if (semanticProfile.groupCount() > 0 && fit.totalHits() == 0) {
            score -= 2_500;
        }
        CatalogQueryIntent.IntentScoreAdjustments intentAdj =
                CatalogQueryIntent.computeIntentScoreAdjustments(CatalogSemanticRowText.haystack(row), query, geoIntent);
        if (intentAdj.negativePenalty() > 0) {
            score -= 5_000 + intentAdj.negativePenalty() * 5;
        }
        // Bare "GDP" FTS floods enrichment *_gdp_share aliases. Prefer real national-accounts GDP
        // and other force-seeded Manager macro datasets (HICP, unemployment, policy rate).
        if (cz.bankintel.explore.ExploreManagerDiscoveryTerms.isCoreMacroSeedRow(row)
                || cz.bankintel.explore.ExploreManagerDiscoveryTerms.isCoreGdpDatasetRow(row)) {
            score += 5_000;
        } else if (cz.bankintel.explore.ExploreManagerDiscoveryTerms.isGdpShareProxyRow(row)) {
            score -= 6_000;
        }
        return score;
    }

    static boolean countsTowardVerifiedTarget(Map<String, Object> row, String query, Map<String, Object> geoIntent) {
        CatalogQueryIntent.IntentScoreAdjustments intentAdj =
                CatalogQueryIntent.computeIntentScoreAdjustments(CatalogSemanticRowText.haystack(row), query, geoIntent);
        return intentAdj.negativePenalty() <= 0;
    }

    private static void attachGeoDiagnostics(
            Map<String, Object> row, CatalogPreviewGeoValidator.GeoPreviewCheck geoCheck) {
        row.put("preview_geo_match", geoCheck.matches());
        if (!geoCheck.requestedCodes().isEmpty()) {
            row.put("preview_geo_requested", geoCheck.requestedCodes());
        }
        if (!geoCheck.observedCodes().isEmpty()) {
            row.put("preview_geo_codes", geoCheck.observedCodes());
        }
    }

    /**
     * Manager pinned seeds (core macro + intent preview seeds) may carry publisher geo (US/EA) while
     * the user asked for a member country. Soft-pass so scaffolds still verify.
     */
    private static boolean allowManagerPinnedGeoSoftPass(Map<String, Object> row, String query) {
        if (!cz.bankintel.explore.ExploreManagerDiscoveryTerms.isManagerPinnedSeedRow(query, row)) {
            return false;
        }
        row.put("preview_geo_soft_pass", true);
        if (cz.bankintel.explore.ExploreManagerDiscoveryTerms.isCoreMacroSeedRow(row)) {
            row.putIfAbsent("manager_macro_scaffold", true);
        }
        if (cz.bankintel.explore.ExploreManagerDiscoveryTerms.matchesIntentPreviewSeed(query, row)) {
            row.put("manager_intent_seed", true);
        }
        return true;
    }

    private static String geoMismatchMessage(CatalogPreviewGeoValidator.GeoPreviewCheck geoCheck) {
        return "Nahled vratil jinou geografii ("
                + String.join(", ", geoCheck.observedCodes())
                + ") nez hledanou ("
                + String.join(", ", geoCheck.requestedCodes())
                + "); polozka ponechana jen jako kandidat.";
    }

    private PreviewItemResult fail(Map<String, Object> row, long started, String code, String message) {
        row.put("preview_status", "unverified");
        row.put("status", "candidate");
        row.put("result_tier", "candidate");
        row.put("preview_available", false);
        row.put("preview_row_count", 0);
        row.put("preview_error", message);
        row.put("reason", message);
        return new PreviewItemResult(false, row, started, code);
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    private static String previewFetchType(Map<String, Object> preview) {
        String declared = firstNonBlank(
                        preview != null ? preview.get("fetch_type") : null,
                        preview != null ? preview.get("access_path") : null,
                        preview != null ? preview.get("transport") : null)
                .toLowerCase(Locale.ROOT);
        if (declared.contains("local")) {
            return "local";
        }
        if (declared.contains("mirror")) {
            return "mirror";
        }
        return "remote";
    }

    private static void mergePreviewSourceTelemetry(
            Map<String, Map<String, Object>> bySource, String source, PreviewItemResult item) {
        String sourceKey = source == null || source.isBlank() ? "unknown" : source;
        Map<String, Object> target = bySource.computeIfAbsent(sourceKey, ignored -> new LinkedHashMap<>());
        incrementMetric(target, "items", 1L);
        incrementMetric(target, item.verified() ? "verified" : "unverified", 1L);
        String terminal = item.failureCode() == null
                ? "success"
                : item.failureCode().contains("timeout") ? "timeout"
                : item.failureCode().contains("empty") ? "empty"
                : "error";
        incrementMetric(target, terminal, 1L);
        incrementMetric(target, item.fetchType() + "_calls", 1L);
        incrementMetric(target, "queue_wait_ms", item.queueWaitMs());
        incrementMetric(target, "fetch_ms", item.fetchMs());
        incrementMetric(target, "parse_ms", item.parseMs());
        incrementMetric(target, "validation_ms", item.validationMs());
    }

    private static void incrementMetric(Map<String, Object> target, String key, long delta) {
        long current = target.get(key) instanceof Number number ? number.longValue() : 0L;
        target.put(key, current + Math.max(0L, delta));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> buildPreviewPayload(Map<String, Object> row, String connectorType, String query) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_type", connectorType);
        String rawSetId = str(row.get("set_id"));
        String resolvedSetId = CatalogPreviewSetIdSupport.resolvePreviewSetId(connectorType, rawSetId, row);
        payload.put("set_id", resolvedSetId.isBlank() ? rawSetId : resolvedSetId);
        payload.put("name", firstNonBlank(row.get("name"), row.get("title"), resolvedSetId, rawSetId));

        Map<String, Object> raw = flattenNestedRow(row);
        Object qp = raw.get("query_params");
        if (!(qp instanceof Map<?, ?>)) {
            qp = raw.get("filters_used");
        }
        if (qp instanceof Map<?, ?> qpMap) {
            payload.put("query_params", castMap(qpMap));
        }
        // Structured, source-specific hints (ecb_country/imf_country/oecd4_ref_area/territory) may
        // already carry the exact native code format that connector expects (e.g. IMF/OECD often
        // key on ISO3), so those are copied through as-is. Free-text hints ("country" when it is
        // plain text, and "country_hint" e.g. "Slovakia") are normalized to ISO2 via the country
        // alias registry — passing them through raw previously sent e.g. "Slovakia" straight into
        // Eurostat's geo dimension, which only matches codes like "SK", forcing dimension
        // resolution down the slow exhaustive cascade path (kolo 6, Zjištění C).
        for (String key : List.of("country", "ecb_country", "territory", "imf_country", "oecd4_ref_area")) {
            Object hint = firstPresent(raw.get(key), row.get(key));
            if (hint != null && !str(hint).isBlank()) {
                // Only the generic/free-text "country" key is normalized to ISO2; the other keys
                // are source-specific and may already be in the native format their connector
                // expects (e.g. IMF/OECD reference areas are frequently ISO3), so pass them through.
                String value = "country".equals(key)
                        ? firstNonBlank(CatalogGeoIntent.resolveTerritoryToCountryCode(hint), str(hint))
                        : str(hint);
                payload.putIfAbsent("country", value);
                break;
            }
        }
        Object countryHint = row.get("country_hint");
        if (countryHint != null && !str(countryHint).isBlank()) {
            String resolved = CatalogGeoIntent.resolveTerritoryToCountryCode(countryHint);
            payload.putIfAbsent("country", !resolved.isBlank() ? resolved : str(countryHint));
        }
        Map<String, Object> geoIntent = CatalogGeoIntent.detectGeoIntent(query);
        payload.put("geo_intent", geoIntent);
        // Defaulting an unresolved "country" to the QUERY's own detected geo only makes sense for a
        // connector where one dataset genuinely spans many countries and needs a geo filter chosen
        // from context (Eurostat). It must never apply to a connector whose set_id already encodes
        // ONE specific, different country per series (IMF: "IMF|agency|flow|version|PAN.LUR") - that
        // would silently overwrite a row's real identity. Confirmed live: an IMF candidate for
        // Panama's unemployment series got its "country" forced to "DE" (the query's own geo, from
        // "nezaměstnanost v Německu") here, which then out-prioritized InMemorySourceBuilder's own
        // (correct, but only-if-nothing-else-set) set_id-parsed country - so the actual fetch
        // returned Germany's data mislabeled under the Panama candidate's own title/set_id.
        List<String> geoCodes = "eurostat".equals(connectorType)
                ? CatalogGeoIntent.requestedGeoCodes(geoIntent)
                : List.of();
        if (!geoCodes.isEmpty()) {
            payload.putIfAbsent("country", geoCodes.get(0));
        }
        for (String key : List.of("ecb_indicator_id", "ecb_country", "ecb_flow", "oecd4_key", "oecd4_ref_area")) {
            Object value = firstPresent(raw.get(key), row.get(key));
            if (value != null && !str(value).isBlank()) {
                payload.putIfAbsent(key, value);
            }
        }
        return payload;
    }

    /**
     * One-shot recovery payload after a failed live preview: force resolved parent set_id, geo from
     * {@code geo_intent}, and a narrow {@code lastTimePeriod=1} window so large Eurostat tables can
     * still verify. Returns {@code null} when nothing actionable changed.
     *
     * <p>The {@code country}/{@code geo} override below is Eurostat-specific by design (a single
     * Eurostat dataset genuinely covers many countries and needs an explicit geo dimension to
     * narrow a huge response) - it must never run for a connector where each row/series already
     * has its OWN specific, different country baked into its own set_id (IMF, ECB, ...). Confirmed
     * live: retrying a failed IMF preview for Panama's series ({@code ...|PAN.LUR}) forced
     * {@code country=DE} (the query's own detected geo, from "nezaměstnanost v Německu") into the
     * refetch, returning Germany's unemployment data mislabeled under the Panama/Honduras
     * candidates' own titles - the row's real country was never wrong or unknown, this retry just
     * overwrote it with the query's country regardless.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> buildResilientPreviewPayload(
            Map<String, Object> row, String connectorType, String query, Map<String, Object> failedPreview) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        boolean allowGeoOverride = "eurostat".equals(connectorType);
        String state = str(failedPreview != null ? failedPreview.get("preview_state") : null).toLowerCase(Locale.ROOT);
        String message = str(failedPreview != null ? failedPreview.get("message") : null).toLowerCase(Locale.ROOT);
        Object httpStatus = firstPresent(
                failedPreview != null ? failedPreview.get("http_status") : null,
                failedPreview != null ? failedPreview.get("status") : null);
        String httpText = str(httpStatus);
        boolean sizeOrSyncFailure = "sync_failed".equals(state)
                || "no_data".equals(state)
                || "unsupported".equals(state)
                || state.isBlank()
                || httpText.contains("413")
                || httpText.contains("400")
                || message.contains("413")
                || message.contains("too large")
                || message.contains("payload")
                || message.contains("empty");
        if (!sizeOrSyncFailure && previewRowCount(failedPreview != null ? failedPreview : Map.of()) > 0) {
            return null;
        }

        Map<String, Object> payload = buildPreviewPayload(row, connectorType, query);
        boolean changed = false;

        String rawSetId = firstNonBlank(row.get("_original_set_id"), row.get("set_id"));
        String resolved = CatalogPreviewSetIdSupport.resolvePreviewSetId(connectorType, rawSetId, row);
        if (!resolved.isBlank() && !resolved.equalsIgnoreCase(str(payload.get("set_id")))) {
            payload.put("set_id", resolved);
            changed = true;
        }

        Map<String, Object> geoIntent = CatalogGeoIntent.detectGeoIntent(query);
        List<String> geoCodes = allowGeoOverride ? CatalogGeoIntent.requestedGeoCodes(geoIntent) : List.of();
        if (!geoCodes.isEmpty()) {
            String code = geoCodes.get(0).trim().toUpperCase(Locale.ROOT);
            if (!code.isBlank() && !code.equalsIgnoreCase(str(payload.get("country")))) {
                payload.put("country", code);
                changed = true;
            }
            Map<String, Object> qp = payload.get("query_params") instanceof Map<?, ?> existing
                    ? new LinkedHashMap<>(castMap(existing))
                    : new LinkedHashMap<>();
            Object currentGeo = firstPresent(qp.get("geo"), qp.get("GEO"));
            if (!code.isBlank() && !code.equalsIgnoreCase(str(currentGeo))) {
                qp.put("geo", code);
                changed = true;
            }
            if (!qp.containsKey("lastTimePeriod") && !qp.containsKey("sinceTimePeriod")) {
                qp.put("lastTimePeriod", "1");
                changed = true;
            }
            if (!qp.isEmpty()) {
                payload.put("query_params", qp);
            }
        } else {
            Map<String, Object> qp = payload.get("query_params") instanceof Map<?, ?> existing
                    ? new LinkedHashMap<>(castMap(existing))
                    : new LinkedHashMap<>();
            if (!qp.containsKey("lastTimePeriod") && !qp.containsKey("sinceTimePeriod")) {
                qp.put("lastTimePeriod", "1");
                payload.put("query_params", qp);
                changed = true;
            }
        }

        return changed ? payload : null;
    }

    private static Map<String, Object> flattenNestedRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> current = row != null ? row : Map.of();
        for (int depth = 0; depth < 8; depth++) {
            out.putAll(current);
            Object nested = current.get("row");
            if (!(nested instanceof Map<?, ?> nestedMap)) {
                break;
            }
            current = castMap(nestedMap);
        }
        return out;
    }

    private static Object firstPresent(Object... values) {
        for (Object value : values) {
            if (value != null && !str(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static int previewRowCount(Map<String, Object> preview) {
        Object rows = preview.get("rows");
        if (rows instanceof List<?> list) {
            return list.size();
        }
        Object total = preview.get("total_count");
        if (total instanceof Number number) {
            return number.intValue();
        }
        Object metadata = preview.get("metadata");
        if (metadata instanceof Map<?, ?> meta) {
            Object count = meta.get("row_count");
            if (count instanceof Number number) {
                return number.intValue();
            }
        }
        return 0;
    }

    static String connectorSourceType(String searchSource) {
        String normalized = CatalogSourceRegistry.normalizeSearchSource(searchSource);
        return switch (normalized) {
            case "oecd4" -> "oecd";
            case "data360" -> ConnectorFactory.normalizeSourceType("data360");
            case "commodities" -> "worldbank_pink_sheet";
            default -> ConnectorFactory.normalizeSourceType(normalized);
        };
    }

    private static String rowKey(Map<String, Object> row) {
        String source = CatalogMapSupport.str(row.get(CatalogKeys.SOURCE_TYPE)).toLowerCase(Locale.ROOT);
        String setId = CatalogMapSupport.str(row.get(CatalogKeys.SET_ID));
        String resolved = CatalogPreviewSetIdSupport.resolvePreviewSetId(source, setId, row);
        return source + "|" + (resolved.isBlank() ? setId : resolved);
    }

    private static String cacheKey(String source, String setId, Map<String, Object> payload) {
        Map<String, String> parts = new TreeMap<>();
        parts.put("source", source == null ? "" : source.toLowerCase(Locale.ROOT));
        parts.put("set_id", setId == null ? "" : setId);
        for (String key : List.of(
                "country",
                "ecb_country",
                "ecb_indicator_id",
                "imf_country",
                "oecd4_ref_area",
                "selected_indicator")) {
            String value = str(payload.get(key));
            if (!value.isBlank()) {
                parts.put(key, value);
            }
        }
        Object selectedMany = payload.get("selected_indicators");
        if (selectedMany instanceof List<?> list && !list.isEmpty()) {
            parts.put(
                    "selected_indicators",
                    String.join(",", list.stream().map(CatalogDeepSearchPreviewService::str).toList()));
        }
        Object qpObj = payload.get("query_params");
        if (qpObj instanceof Map<?, ?> qpMap) {
            for (Map.Entry<?, ?> entry : qpMap.entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).trim();
                String value = str(entry.getValue());
                if (!key.isBlank() && !value.isBlank()) {
                    parts.put("qp." + key, value);
                }
            }
        }
        return parts.toString();
    }

    private static int resolvePreviewPoolSize() {
        return resolvePreviewPoolSize(false);
    }

    private static int resolvePreviewPoolSize(boolean managerDiscovery) {
        if (managerDiscovery) {
            return parseIntEnv("DEEP_SEARCH_MANAGER_PREVIEW_POOL", MANAGER_PREVIEW_POOL, 4, 64);
        }
        return parseIntEnv("DEEP_SEARCH_PREVIEW_POOL", DEFAULT_PREVIEW_POOL, 4, 24);
    }

    private static int resolvePreviewConcurrency() {
        return parseIntEnv("DEEP_SEARCH_PREVIEW_MAX_CONCURRENCY", DEFAULT_PREVIEW_CONCURRENCY, 1, 8);
    }

    private static int resolvePreviewTimeoutSec() {
        return parseIntEnv("DEEP_SEARCH_PREVIEW_TIMEOUT_SEC", DEFAULT_PREVIEW_TIMEOUT_SEC, 4, 30);
    }

    private static int resolveVerifiedTarget() {
        return resolveVerifiedTarget(false);
    }

    private static int resolveVerifiedTarget(boolean managerDiscovery) {
        if (managerDiscovery) {
            return parseIntEnv("DEEP_SEARCH_MANAGER_PREVIEW_VERIFIED_TARGET", MANAGER_VERIFIED_TARGET, 2, 24);
        }
        return parseIntEnv("DEEP_SEARCH_PREVIEW_VERIFIED_TARGET", DEFAULT_VERIFIED_TARGET, 2, 12);
    }

    private static long resolveWallBudgetMs() {
        return resolveWallBudgetMs(false);
    }

    private static long resolveWallBudgetMs(boolean managerDiscovery) {
        int defaultSec = managerDiscovery ? MANAGER_WALL_BUDGET_SEC : DEFAULT_WALL_BUDGET_SEC;
        String envName = managerDiscovery
                ? "DEEP_SEARCH_MANAGER_PREVIEW_WALL_BUDGET_SEC"
                : "DEEP_SEARCH_PREVIEW_WALL_BUDGET_SEC";
        return parseIntEnv(envName, defaultSec, 10, 120) * 1000L;
    }

    private static int parseIntEnv(String name, int defaultValue, int min, int max) {
        try {
            String raw = BankIntelEnvVars.get(name);
            if (raw.isBlank()) {
                return defaultValue;
            }
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = str(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    public record PreviewPhaseResult(
            List<Map<String, Object>> verified,
            List<Map<String, Object>> possible,
            Map<String, Object> previewTiming,
            List<String> notes) {
        static PreviewPhaseResult empty() {
            return new PreviewPhaseResult(List.of(), List.of(), Map.of(), List.of());
        }
    }

    private record CachedPreview(int rowCount, Map<String, Object> previewPayload) {
    }

    private record PreviewItemResult(
            boolean verified,
            Map<String, Object> row,
            long startedMs,
            String failureCode,
            long queueWaitMs,
            long fetchMs,
            long parseMs,
            long validationMs,
            String fetchType) {

        PreviewItemResult(boolean verified, Map<String, Object> row, long startedMs, String failureCode) {
            this(
                    verified,
                    row,
                    startedMs,
                    failureCode,
                    takeLong(row, "_telemetry_queue_wait_ms"),
                    takeLong(row, "_telemetry_fetch_ms"),
                    takeLong(row, "_telemetry_parse_ms"),
                    takeLong(row, "_telemetry_validation_ms"),
                    takeString(row, "_telemetry_fetch_type", "none"));
        }

        long elapsedMs() {
            return Math.max(0, System.currentTimeMillis() - startedMs);
        }

        Map<String, Object> telemetry() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", CatalogMapSupport.str(row.get(CatalogKeys.SOURCE_TYPE)));
            item.put("set_id", CatalogMapSupport.str(row.get(CatalogKeys.SET_ID)));
            item.put("terminal_status", failureCode == null ? "success" : failureCode);
            item.put("verified", verified);
            item.put("queue_wait_ms", queueWaitMs);
            item.put("fetch_ms", fetchMs);
            item.put("parse_ms", parseMs);
            item.put("validation_ms", validationMs);
            item.put("fetch_type", fetchType);
            return item;
        }

        private static long takeLong(Map<String, Object> row, String key) {
            Object value = row.remove(key);
            return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
        }

        private static String takeString(Map<String, Object> row, String key, String fallback) {
            String value = CatalogMapSupport.str(row.remove(key));
            return value.isBlank() ? fallback : value;
        }
    }
}
