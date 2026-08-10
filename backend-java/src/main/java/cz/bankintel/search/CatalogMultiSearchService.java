package cz.bankintel.search;

import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.CatalogMapSupport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CatalogMultiSearchService {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final CatalogIndexStore indexStore;
    private final CatalogClassicSearchService classicSearchService;
    private final CatalogSearchAnswerService searchAnswerService;

    public Map<String, Object> multiSearch(Map<String, Object> payload) {
        return multiSearch(payload, null);
    }

    public Map<String, Object> multiSearch(Map<String, Object> payload, BiConsumer<String, List<Map<String, Object>>> onLaneDone) {
        String query = CatalogMapSupport.firstNonBlank(payload, CatalogKeys.QUERY, CatalogKeys.Q);
        List<String> sources = parseSources(payload);
        int limit = parseLimit(payload.get("limit"), payload.get("limit_per_source"), sources.size());
        long t0 = System.currentTimeMillis();

        if (query.length() < 2) {
            return emptyMultiResponse(query, "Zadejte alespoň 2 znaky.", 0);
        }
        if (sources.isEmpty()) {
            return emptyMultiResponse(query, "Vyberte alespoň jeden katalog.", 0);
        }

        List<CompletableFuture<Map<String, Object>>> futures = sources.stream()
                .map(source -> CompletableFuture.supplyAsync(() -> searchLane(source, query, limit), EXECUTOR))
                .toList();

        List<Map<String, Object>> merged = new ArrayList<>();
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            Map<String, Object> lane = futures.get(i).join();
            String source = sources.get(i);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hits = (List<Map<String, Object>>) lane.getOrDefault("hits", List.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) lane.get("summary");
            merged.addAll(hits);
            if (summary != null) {
                summaries.add(summary);
            }
            if (onLaneDone != null) {
                onLaneDone.accept(source, hits);
            }
        }

        merged.sort(Comparator.comparingInt((Map<String, Object> row) ->
                        -CatalogMapSupport.toInt(row.get(CatalogKeys.SEARCH_SCORE), 0))
                .thenComparing(row -> CatalogMapSupport.str(row.get(CatalogKeys.NAME)).toLowerCase(Locale.ROOT)));
        merged = CatalogSearchVariantDedup.consolidateDisplayRows(merged);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(CatalogKeys.QUERY, query);
        out.put("results", merged);
        out.put("themes_triggered", List.of());
        out.put("elapsed_ms", System.currentTimeMillis() - t0);
        out.put("source_summaries", summaries.stream().sorted(Comparator.comparing(s -> CatalogMapSupport.str(s.get("label")))).toList());
        List<Map<String, Object>> topVerified = merged.stream().limit(5).toList();
        out.put(CatalogKeys.ANSWER, searchAnswerService.composeAnswer(query, topVerified, List.of()));
        return out;
    }

    private Map<String, Object> searchLane(String source, String query, int limit) {
        String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
        long t0 = System.currentTimeMillis();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", normalized);
        summary.put("label", CatalogSourceRegistry.label(normalized));
        if (normalized.isBlank()) {
            summary.put("hits", 0);
            summary.put("elapsed_ms", 0);
            summary.put("message_cs", "Neznámý katalog.");
            summary.put("upstream_unavailable", false);
            return Map.of("hits", List.<Map<String, Object>>of(), "summary", summary);
        }
        try {
            Map<String, Object> body = classicSearchService.search(Map.of(CatalogKeys.SOURCE, normalized, CatalogKeys.QUERY, query, "limit", limit));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hits = (List<Map<String, Object>>) body.getOrDefault("results", List.of());
            summary.put("hits", hits.size());
            summary.put("elapsed_ms", System.currentTimeMillis() - t0);
            summary.put("message_cs", body.get("message_cs"));
            summary.put("upstream_unavailable", body.getOrDefault("upstream_unavailable", false));
            summary.put("local_index_missing", hits.isEmpty() && !indexStore.ftsDbAvailable());
            return Map.of("hits", hits, "summary", summary);
        } catch (Exception ex) {
            summary.put("hits", 0);
            summary.put("elapsed_ms", System.currentTimeMillis() - t0);
            summary.put("message_cs", "Vyhledávání selhalo.");
            summary.put("upstream_unavailable", true);
            return Map.of("hits", List.of(), "summary", summary);
        }
    }

    private static Map<String, Object> emptyMultiResponse(String query, String message, long elapsedMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(CatalogKeys.QUERY, query);
        out.put("results", List.of());
        out.put("themes_triggered", List.of());
        out.put("elapsed_ms", elapsedMs);
        out.put("source_summaries", List.of());
        out.put("message_cs", message);
        return out;
    }

    private static List<String> parseSources(Map<String, Object> payload) {
        List<String> out = new ArrayList<>();
        Object raw = payload.get(CatalogKeys.SOURCES);
        if (raw == null) {
            raw = payload.get("catalog_sources");
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String sid = CatalogSourceRegistry.normalizeSearchSource(String.valueOf(item));
                if (!sid.isBlank() && !out.contains(sid)) {
                    out.add(sid);
                }
            }
        }
        return out;
    }

    private static int parseLimit(Object limitRaw, Object limitPerSourceRaw, int sourceCount) {
        Object raw = limitRaw != null ? limitRaw : limitPerSourceRaw;
        int defaultValue = Math.max(8, 50 / Math.max(1, sourceCount));
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(String.valueOf(raw)), 100));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
