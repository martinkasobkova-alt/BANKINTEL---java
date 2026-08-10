package cz.bankintel.search;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CatalogSuggestService {

    private static final long LANE_TIMEOUT_SECONDS = 3L;
    private static final long CACHE_TTL_MS = 5 * 60_000L;
    private static final int CACHE_MAX_ENTRIES = 256;

    private final CatalogIndexStore indexStore;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > CACHE_MAX_ENTRIES;
        }
    };

    public Map<String, Object> suggest(String query, int limit, String scope) {
        String qs = query == null ? "" : query.trim();
        if (qs.length() < 2) {
            return Map.of("suggestions", List.of(), "query", qs);
        }
        int lim = Math.max(1, Math.min(limit, 25));
        String scopeNorm = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        String cacheKey = CatalogTextUtils.foldAscii(qs) + "|" + scopeNorm + "|" + lim;
        Map<String, Object> cached = cached(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<String> ftsSources = resolveFtsSources(scopeNorm);
        List<String> nonFtsSources = resolveNonFtsSources(scopeNorm);

        List<Map<String, Object>> results = new ArrayList<>();
        List<CompletableFuture<List<Map<String, Object>>>> lanes = new ArrayList<>();
        for (String src : ftsSources) {
            lanes.add(lane(() -> indexStore.ftsSuggest(qs, lim, List.of(src))));
        }
        int per = Math.max(3, lim / 3);
        for (String src : nonFtsSources) {
            lanes.add(lane(() -> {
                String scanSource = "oecd".equals(src) ? "oecd4" : src;
                return indexStore.searchSource(scanSource, qs, per).stream()
                        .map(hit -> toSuggestHit(src, hit))
                        .toList();
            }));
        }
        for (CompletableFuture<List<Map<String, Object>>> lane : lanes) {
            results.addAll(lane.join());
        }

        List<String> needles = CatalogTextUtils.needlesFromQuery(qs);
        for (Map<String, Object> row : results) {
            if (!row.containsKey("_match")) {
                row.put(
                        "_match",
                        CatalogTextUtils.titleMatchScore(
                                CatalogTextUtils.foldAscii(String.valueOf(row.getOrDefault("title", ""))), needles));
            }
        }

        List<Map<String, Object>> deduped = dedupe(results);
        deduped.sort(Comparator.comparingInt((Map<String, Object> r) -> {
                    int match = (int) r.getOrDefault("_match", 0);
                    int bonus = CatalogSourceRegistry.sourceBonus(String.valueOf(r.get("source")));
                    return -(match + bonus);
                })
                .thenComparingDouble(r -> (double) r.getOrDefault("_fts_rank", 0.0)));

        int perSourceCap = Math.max(2, lim / 4);
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Map<String, Object>> picked = new ArrayList<>();
        List<Map<String, Object>> overflow = new ArrayList<>();
        for (Map<String, Object> row : deduped) {
            String src = String.valueOf(row.get("source"));
            if (counts.getOrDefault(src, 0) < perSourceCap) {
                picked.add(row);
                counts.put(src, counts.getOrDefault(src, 0) + 1);
            } else {
                overflow.add(row);
            }
            if (picked.size() >= lim) {
                break;
            }
        }
        if (picked.size() < lim) {
            picked.addAll(overflow.subList(0, Math.min(overflow.size(), lim - picked.size())));
        }

        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (Map<String, Object> row : picked.subList(0, Math.min(lim, picked.size()))) {
            String src = String.valueOf(row.get("source"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", src);
            out.put("source_label", CatalogSourceRegistry.label(src));
            out.put("set_id", String.valueOf(row.get("set_id")));
            out.put("title", String.valueOf(row.get("title")));
            out.put("full_path", String.valueOf(row.getOrDefault("full_path", "")));
            out.put("indicator_id", String.valueOf(row.getOrDefault("indicator_id", "")));
            suggestions.add(out);
        }
        Map<String, Object> response = Map.of("suggestions", suggestions, "query", qs);
        synchronized (cache) {
            cache.put(cacheKey, new CacheEntry(response, System.currentTimeMillis() + CACHE_TTL_MS));
        }
        return response;
    }

    private CompletableFuture<List<Map<String, Object>>> lane(
            java.util.function.Supplier<List<Map<String, Object>>> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor)
                .completeOnTimeout(List.of(), LANE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ignored -> List.of());
    }

    private Map<String, Object> cached(String key) {
        synchronized (cache) {
            CacheEntry entry = cache.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.expiresAtMs() <= System.currentTimeMillis()) {
                cache.remove(key);
                return null;
            }
            return entry.value();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private record CacheEntry(Map<String, Object> value, long expiresAtMs) {}

    private static Map<String, Object> toSuggestHit(String displaySource, Map<String, Object> hit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source", displaySource);
        row.put("set_id", hit.get("set_id"));
        row.put("title", hit.get("title"));
        row.put("full_path", hit.get("full_path"));
        row.put("_match", hit.get("_search_score"));
        row.put("_fts_rank", hit.getOrDefault("_fts_rank", 0.0));
        return row;
    }

    private static List<Map<String, Object>> dedupe(List<Map<String, Object>> rows) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String key = String.valueOf(row.get("source")).toLowerCase(Locale.ROOT)
                    + "|"
                    + String.valueOf(row.get("set_id")).toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                out.add(row);
            }
        }
        return out;
    }

    private static List<String> resolveFtsSources(String scopeNorm) {
        if (!scopeNorm.isBlank()) {
            String normalized = CatalogSourceRegistry.normalizeSearchSource(scopeNorm);
            return CatalogSourceRegistry.SUGGEST_FTS_SOURCES.contains(normalized)
                            || CatalogSourceRegistry.FTS_PILOT_SOURCES.contains(normalized)
                    ? List.of(normalized)
                    : List.of();
        }
        return CatalogSourceRegistry.SUGGEST_FTS_SOURCES;
    }

    private static List<String> resolveNonFtsSources(String scopeNorm) {
        if (!scopeNorm.isBlank()) {
            return CatalogSourceRegistry.NON_FTS_SOURCES.contains(scopeNorm) ? List.of(scopeNorm) : List.of();
        }
        return CatalogSourceRegistry.NON_FTS_SOURCES;
    }
}
