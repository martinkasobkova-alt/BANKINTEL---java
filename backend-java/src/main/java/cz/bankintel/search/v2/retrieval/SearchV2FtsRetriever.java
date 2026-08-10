package cz.bankintel.search.v2.retrieval;

import cz.bankintel.search.CatalogCommoditySearch;
import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.model.CatalogHit;
import cz.bankintel.search.v2.normalization.SearchV2CandidateNormalizer;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarIndex;
import cz.bankintel.search.v2.vector.SearchVectorProperties;
import cz.bankintel.search.v2.vector.SearchVectorRetriever;
import cz.bankintel.sources.stocks.StockSearchService;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SearchV2FtsRetriever {

    private static final int DEFAULT_PER_VARIANT_LIMIT = 25;
    private static final int MAX_POOL_SIZE = 240;
    private static final long FTS_QUERY_TIMEOUT_MS = 20_000;
    // Lowered from 2500ms following the completed FTS relaxed-lane timeout validation experiment:
    // Top-1/Recall@3/Recall@5/MRR identical to the 2500ms baseline across the tested query set, with
    // retrieval p90 improving from ~2513ms to ~1514ms. 500ms showed a relevance regression and 1000ms
    // wasn't fully identical to baseline, so 1500ms was the safest measured speedup.
    private static final long SIDECAR_QUERY_TIMEOUT_MS_DEFAULT = 1_500;
    // Benchmark-only override for the FTS relaxed-lane timeout validation experiment. Production
    // default (1500ms) is UNCHANGED when this env var is unset/blank/unparseable - this must never be
    // set outside a deliberate benchmark run. See docs on the experiment for the tested values.
    private static final long SIDECAR_QUERY_TIMEOUT_MS =
            resolveSidecarQueryTimeoutMs(cz.bankintel.util.BankIntelEnvVars.get(
                    "SEARCH_V2_SIDECAR_QUERY_TIMEOUT_MS_BENCHMARK_OVERRIDE"));
    private static final long VECTOR_QUERY_TIMEOUT_MS = 5_000;

    // Package-private (not private): unit-tested directly against raw override strings in
    // SearchV2FtsRetrieverSidecarTimeoutConfigTest, since the SIDECAR_QUERY_TIMEOUT_MS field above is
    // resolved once at class-load time and can't be re-resolved per test case within one JVM.
    static long resolveSidecarQueryTimeoutMs(String raw) {
        if (raw == null || raw.isBlank()) {
            return SIDECAR_QUERY_TIMEOUT_MS_DEFAULT;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : SIDECAR_QUERY_TIMEOUT_MS_DEFAULT;
        } catch (NumberFormatException ex) {
            return SIDECAR_QUERY_TIMEOUT_MS_DEFAULT;
        }
    }

    private final CatalogIndexStore indexStore;
    private final CatalogCommoditySearch commoditySearch;
    private final SearchV2QueryExpander queryExpander;
    private final SearchV2CandidateNormalizer normalizer;
    private final SearchV2CandidateMerger candidateMerger;
    private final SearchCandidateFusion candidateFusion;
    private final SearchCatalogSidecarIndex sidecarIndex;
    private final SearchVectorRetriever vectorRetriever;
    private final SearchVectorProperties vectorProperties;
    private final StockSearchService stockSearchService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public record RetrievalResult(
            List<SearchCandidate> candidates,
            List<SearchCandidate> preMergeCandidates,
            List<String> queries,
            List<Map<String, Object>> queryStats,
            long latencyMs,
            String indexMode) {
        public RetrievalResult(
                List<SearchCandidate> candidates,
                List<String> queries,
                List<Map<String, Object>> queryStats,
                long latencyMs) {
            this(candidates, candidates, queries, queryStats, latencyMs, SearchCatalogSidecarIndex.MODE_LEGACY);
        }
    }

    public RetrievalResult retrieve(SearchQueryPlan plan, List<String> allowedSources) {
        return retrieve(plan, allowedSources, FTS_QUERY_TIMEOUT_MS);
    }

    public RetrievalResult retrieve(SearchQueryPlan plan, List<String> allowedSources, long timeoutMs) {
        return retrieve(plan, allowedSources, timeoutMs, SearchCatalogSidecarIndex.MODE_LEGACY);
    }

    public RetrievalResult retrieve(SearchQueryPlan plan, List<String> allowedSources, long timeoutMs, String indexMode) {
        return retrieve(plan, allowedSources, allowedSources, timeoutMs, indexMode);
    }

    public RetrievalResult retrieve(
            SearchQueryPlan plan,
            List<String> allowedSources,
            List<String> vectorAllowedSources,
            long timeoutMs,
            String indexMode) {
        long start = System.currentTimeMillis();
        List<String> queries = queryExpander.expand(plan);
        String vectorQuery = plan == null
                ? String.join(". ", queries)
                : String.join(". ", concatQueryParts(
                        List.of(plan.originalQuery()), plan.primaryConcepts(), plan.semanticSearchTerms()));
        return retrieveQueries(
                queries, allowedSources, vectorAllowedSources, start, timeoutMs, indexMode, vectorQuery);
    }

    public RetrievalResult retrieveQueries(List<String> queries, List<String> allowedSources) {
        return retrieveQueries(queries, allowedSources, System.currentTimeMillis(), FTS_QUERY_TIMEOUT_MS);
    }

    public RetrievalResult retrieveQueries(List<String> queries, List<String> allowedSources, long timeoutMs) {
        return retrieveQueries(queries, allowedSources, System.currentTimeMillis(), timeoutMs, SearchCatalogSidecarIndex.MODE_LEGACY);
    }

    private RetrievalResult retrieveQueries(List<String> queries, List<String> allowedSources, long start, long timeoutMs) {
        return retrieveQueries(queries, allowedSources, start, timeoutMs, SearchCatalogSidecarIndex.MODE_LEGACY);
    }

    public RetrievalResult retrieveQueries(
            List<String> queries, List<String> allowedSources, long timeoutMs, String indexMode) {
        return retrieveQueries(queries, allowedSources, System.currentTimeMillis(), timeoutMs, indexMode);
    }

    private RetrievalResult retrieveQueries(
            List<String> queries, List<String> allowedSources, long start, long timeoutMs, String indexMode) {
        return retrieveQueries(queries, allowedSources, start, timeoutMs, indexMode, String.join(". ", queries));
    }

    private RetrievalResult retrieveQueries(
            List<String> queries,
            List<String> allowedSources,
            long start,
            long timeoutMs,
            String indexMode,
            String vectorQuery) {
        return retrieveQueries(queries, allowedSources, allowedSources, start, timeoutMs, indexMode, vectorQuery);
    }

    private RetrievalResult retrieveQueries(
            List<String> queries,
            List<String> allowedSources,
            List<String> vectorAllowedSources,
            long start,
            long timeoutMs,
            String indexMode,
            String vectorQuery) {
        List<String> sources = allowedSources == null || allowedSources.isEmpty()
                ? SearchV2QueryPlannerSources.all()
                : allowedSources;
        String mode = sidecarIndex.sidecarEnabled(indexMode)
                ? SearchCatalogSidecarIndex.MODE_SIDECAR
                : SearchCatalogSidecarIndex.MODE_LEGACY;
        long configuredTimeoutMs = timeoutMs <= 0 ? FTS_QUERY_TIMEOUT_MS : timeoutMs;
        long safeTimeoutMs = SearchCatalogSidecarIndex.MODE_SIDECAR.equals(mode)
                ? Math.min(configuredTimeoutMs, SIDECAR_QUERY_TIMEOUT_MS)
                : configuredTimeoutMs;
        CompletableFuture<SearchVectorRetriever.RetrievalResult> vectorFuture = CompletableFuture
                .supplyAsync(() -> vectorRetriever.retrieve(vectorQuery, vectorAllowedSources), executor)
                .completeOnTimeout(vectorRetriever.timeout(VECTOR_QUERY_TIMEOUT_MS), VECTOR_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        List<CompletableFuture<PartialResult>> futures = new ArrayList<>();
        List<String> indexedSources = sources.stream()
                .filter(source -> !Set.of("commodities", "stocks").contains(source))
                .toList();
        // Perf fix (duplicate FTS query elimination - Varianta B): shared across every query-expansion
        // variant fired below, so two variants whose final MATCH expression is identical after
        // normalization/token-capping execute the underlying SQL only once. Scoped to this one
        // retrieveQueries call (fresh map per call) - never reused across requests/retries.
        Map<SearchCatalogSidecarIndex.FtsQueryCacheKey, List<Map<String, Object>>> sidecarDedupeCache =
                new ConcurrentHashMap<>();
        if (SearchCatalogSidecarIndex.MODE_SIDECAR.equals(mode) && !indexedSources.isEmpty()) {
            for (String query : queries) {
                futures.add(CompletableFuture
                        .supplyAsync(() -> retrieveSidecar(indexedSources, query, sidecarDedupeCache), executor)
                        .completeOnTimeout(
                                timeoutResult(indexedSources, query, safeTimeoutMs, mode),
                                safeTimeoutMs,
                                TimeUnit.MILLISECONDS));
            }
        }
        for (String source : sources) {
            if (SearchCatalogSidecarIndex.MODE_SIDECAR.equals(mode) && indexedSources.contains(source)) {
                continue;
            }
            for (String query : queries) {
                futures.add(CompletableFuture
                        .supplyAsync(() -> retrieveOne(source, query, mode), executor)
                        .completeOnTimeout(timeoutResult(source, query, safeTimeoutMs, mode), safeTimeoutMs, TimeUnit.MILLISECONDS));
            }
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        List<SearchCandidate> candidates = new ArrayList<>();
        List<Map<String, Object>> stats = new ArrayList<>();
        for (CompletableFuture<PartialResult> future : futures) {
            PartialResult partial = future.join();
            candidates.addAll(partial.candidates());
            stats.addAll(partial.stats());
        }
        List<SearchCandidate> ftsMerged = candidateMerger.merge(candidates, MAX_POOL_SIZE);
        SearchVectorRetriever.RetrievalResult vectorResult = vectorFuture.join();
        SearchCandidateFusion.FusionResult fusion = candidateFusion.fuse(
                ftsMerged, vectorResult.candidates(), vectorProperties.rrfK(), MAX_POOL_SIZE);
        Map<String, Object> vectorStat = new LinkedHashMap<>(vectorResult.toStat());
        vectorStat.put("fts_candidate_count", ftsMerged.size());
        vectorStat.put("vector_candidate_count", vectorResult.candidates().size());
        vectorStat.put("vector_only_count", fusion.vectorOnlyCount());
        vectorStat.put("both_lanes_count", fusion.bothLanesCount());
        vectorStat.put("fusion_candidate_count", fusion.fusionCandidateCount());
        stats.add(vectorStat);
        return new RetrievalResult(
                fusion.candidates(),
                joinCandidates(candidates, vectorResult.candidates()),
                queries,
                stats,
                System.currentTimeMillis() - start,
                mode);
    }

    private PartialResult retrieveSidecar(
            List<String> sources,
            String query,
            Map<SearchCatalogSidecarIndex.FtsQueryCacheKey, List<Map<String, Object>>> dedupeCache) {
        long start = System.currentTimeMillis();
        try {
            int poolLimit = DEFAULT_PER_VARIANT_LIMIT * Math.min(Math.max(sources.size(), 1), 4);
            List<SearchCandidate> candidates = sidecarIndex.searchGlobal(sources, query, poolLimit, dedupeCache).stream()
                    .map(row -> normalizer.normalize(String.valueOf(row.getOrDefault("source", "")), row, query))
                    .filter(candidate -> candidate.seriesId() != null && !candidate.seriesId().isBlank())
                    .toList();
            List<Map<String, Object>> stats = sourceStats(
                    sources,
                    query,
                    candidates,
                    System.currentTimeMillis() - start,
                    true,
                    null,
                    SearchCatalogSidecarIndex.MODE_SIDECAR);
            withHybridDiagnostics(stats);
            return new PartialResult(candidates, stats);
        } catch (Exception ex) {
            List<Map<String, Object>> stats = sourceStats(
                    sources,
                    query,
                    List.of(),
                    System.currentTimeMillis() - start,
                    false,
                    ex.getMessage(),
                    SearchCatalogSidecarIndex.MODE_SIDECAR);
            withHybridDiagnostics(stats);
            return new PartialResult(List.of(), stats);
        }
    }

    /**
     * FTS timeout validation experiment (benchmark-only): attaches the strict/relaxed lane outcome
     * captured by SearchCatalogSidecarIndex for the searchGlobal call this method just made, to every
     * per-source stat entry (they all came from one shared hybrid FTS pass). Purely additive debug
     * metadata - does not change candidates, ok/error, or latency_ms already set on these entries.
     */
    private static void withHybridDiagnostics(List<Map<String, Object>> stats) {
        Map<String, Object> diagnostics = SearchCatalogSidecarIndex.drainLastHybridDiagnostics();
        if (diagnostics.isEmpty()) {
            return;
        }
        for (Map<String, Object> stat : stats) {
            stat.putAll(diagnostics);
        }
    }

    private PartialResult retrieveOne(String source, String query, String indexMode) {
        long start = System.currentTimeMillis();
        try {
            List<Map<String, Object>> rows;
            if ("commodities".equals(source)) {
                rows = commoditySearch.searchHits(query, DEFAULT_PER_VARIANT_LIMIT).stream()
                        .map(CatalogHit::toMap)
                        .toList();
            } else if ("stocks".equals(source)) {
                rows = stockRows(query, DEFAULT_PER_VARIANT_LIMIT);
            } else if (sidecarIndex.sidecarEnabled(indexMode)) {
                rows = sidecarIndex.search(source, query, DEFAULT_PER_VARIANT_LIMIT);
            } else {
                rows = indexStore.searchSourceFtsRaw(source, query, DEFAULT_PER_VARIANT_LIMIT);
            }
            List<SearchCandidate> candidates = rows.stream()
                    .map(row -> normalizer.normalize(source, row, query))
                    .filter(candidate -> candidate.seriesId() != null && !candidate.seriesId().isBlank())
                    .toList();
            return new PartialResult(
                    candidates,
                    List.of(stat(source, query, candidates.size(), System.currentTimeMillis() - start, true, null, indexMode)));
        } catch (Exception ex) {
            return new PartialResult(
                    List.of(),
                    List.of(stat(source, query, 0, System.currentTimeMillis() - start, false, ex.getMessage(), indexMode)));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stockRows(String query, int limit) {
        Map<String, Object> response = stockSearchService.search(query, false, Math.max(1, Math.min(limit, 12)));
        Object rawResults = response.get("results");
        if (!(rawResults instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) map);
            row.put("source", "stocks");
            row.put("source_type", "stocks");
            row.put("catalog_id", "stocks");
            row.put("preview_source_type", "yahoo_finance");
            row.put("primary_concept", "equity_market_price");
            row.put("measure_type", "market_price");
            row.put("economic_object", "equity");
            row.put("instrument", "equity");
            row.put("catalog_family", "markets_equities");
            row.put("dataset_family", "markets");
            row.put("concepts", List.of("equity_market_price", "market_price", "equity", "markets_equities"));
            row.put("tags", List.of("stock", "equity", "market"));
            row.put("_fts_rank", 0.0);
            row.put("_matched_query", query);
            out.add(row);
        }
        return out;
    }

    private PartialResult timeoutResult(String source, String query, long timeoutMs, String indexMode) {
        return new PartialResult(
                List.of(),
                List.of(stat(source, query, 0, timeoutMs, false, "fts_timeout_ms=" + timeoutMs, indexMode)));
    }

    private PartialResult timeoutResult(List<String> sources, String query, long timeoutMs, String indexMode) {
        return new PartialResult(
                List.of(),
                sourceStats(
                        sources,
                        query,
                        List.of(),
                        timeoutMs,
                        false,
                        "fts_timeout_ms=" + timeoutMs,
                        indexMode));
    }

    private static List<Map<String, Object>> sourceStats(
            List<String> sources,
            String query,
            List<SearchCandidate> candidates,
            long latencyMs,
            boolean ok,
            String error,
            String indexMode) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SearchCandidate candidate : candidates) {
            counts.merge(candidate.source(), 1, Integer::sum);
        }
        return sources.stream()
                .map(source -> stat(
                        source,
                        query,
                        counts.getOrDefault(source, 0),
                        latencyMs,
                        ok,
                        error,
                        indexMode))
                .toList();
    }

    private static Map<String, Object> stat(
            String source, String query, int count, long latencyMs, boolean ok, String error, String indexMode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("query", query);
        out.put("index_mode", indexMode);
        out.put("count", count);
        out.put("latency_ms", latencyMs);
        out.put("ok", ok);
        if (error != null) {
            out.put("error", error);
        }
        return out;
    }

    @SafeVarargs
    private static List<String> concatQueryParts(List<String>... values) {
        List<String> out = new ArrayList<>();
        for (List<String> list : values) {
            if (list != null) {
                list.stream().filter(value -> value != null && !value.isBlank()).forEach(out::add);
            }
        }
        return out.stream().distinct().toList();
    }

    private static List<SearchCandidate> joinCandidates(
            List<SearchCandidate> left, List<SearchCandidate> right) {
        List<SearchCandidate> out = new ArrayList<>(left.size() + right.size());
        out.addAll(left);
        out.addAll(right);
        return out;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private record PartialResult(List<SearchCandidate> candidates, List<Map<String, Object>> stats) {}

    private static final class SearchV2QueryPlannerSources {
        private static List<String> all() {
            return cz.bankintel.search.v2.planner.SearchV2QueryPlanner.ALL_SEARCH_V2_SOURCES;
        }
    }
}
