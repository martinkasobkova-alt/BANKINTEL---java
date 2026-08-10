package cz.bankintel.service.admin;

import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.CatalogMultiSearchService;
import cz.bankintel.search.CatalogSearchProperties;
import cz.bankintel.search.CatalogSourceRegistry;
import cz.bankintel.search.CatalogStatusService;
import cz.bankintel.search.CatalogWarmupService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminCatalogIndexAdminService {

    private final CatalogStatusService catalogStatusService;
    private final CatalogIndexStore indexStore;
    private final CatalogSearchProperties properties;
    private final CatalogMultiSearchService multiSearchService;
    private final CatalogWarmupService catalogWarmupService;

    public Map<String, Object> status() {
        Map<String, Object> base = new LinkedHashMap<>(catalogStatusService.status());
        List<Map<String, Object>> sourceStats = new ArrayList<>();
        Map<String, Object> sourcesMeta = new LinkedHashMap<>();
        for (String source : CatalogSourceRegistry.FTS_PILOT_SOURCES) {
            indexStore.loadMeta(source).ifPresent(meta -> {
                Map<String, Object> stat = new LinkedHashMap<>();
                stat.put("source", source);
                stat.put("count", meta.getOrDefault("document_count", meta.get("row_count")));
                stat.put("last_indexed", meta.getOrDefault("last_rebuild_at", meta.get("built_at")));
                sourceStats.add(stat);
                Map<String, Object> metaEntry = new LinkedHashMap<>();
                metaEntry.put("last_rebuild_at", meta.getOrDefault("last_rebuild_at", meta.get("built_at")));
                metaEntry.put("document_count", meta.getOrDefault("document_count", meta.get("row_count")));
                metaEntry.put("status", meta.getOrDefault("status", "ready"));
                metaEntry.put("error", meta.getOrDefault("error", null));
                sourcesMeta.put(source, metaEntry);
            });
        }
        base.put("ok", true);
        base.put("collection", "sqlite_fts_catalog_index");
        base.put("total_documents", sourceStats.stream().mapToInt(s -> toInt(s.get("count"))).sum());
        base.put("fresh", indexStore.ftsDbAvailable());
        base.put("fresh_hours_threshold", 168);
        base.put("rebuild_running", false);
        base.put("sources", sourceStats);
        base.put("sources_meta", sourcesMeta);
        base.put("index_backend", indexStore.ftsDbAvailable() ? "sqlite_fts" : "jsonl_scan");
        base.put("index_dir", properties.indexDir().toString());
        return base;
    }

    public Map<String, Object> searchTest(String query, String country, String sourcesCsv, int limit) {
        List<String> sources = parseSources(sourcesCsv);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("sources", sources.isEmpty() ? new ArrayList<>(CatalogSourceRegistry.FTS_PILOT_SOURCES) : sources);
        payload.put("limit", limit);
        if (country != null && !country.isBlank()) {
            payload.put("country", country.strip().toUpperCase());
        }
        Map<String, Object> search = multiSearchService.multiSearch(payload);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) search.getOrDefault("results", List.of());
        List<Map<String, Object>> results = hits.stream().map(this::toSearchTestHit).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("query", query);
        out.put("country", country != null && !country.isBlank() ? country.strip().toUpperCase() : null);
        out.put("sources", sources.isEmpty() ? null : sources);
        out.put("count", results.size());
        out.put("results", results);
        return out;
    }

    public Map<String, Object> rebuild(List<String> sources) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put(
                "message",
                "Java port načítá předpřipravené indexy z CATALOG_SEARCH_INDEX_DIR; spuštěn catalog warmup "
                        + "(parita s catalog_search_warmup.py). Pro plný rebuild indexu použijte externí index builder.");
        out.put("index_dir", properties.indexDir().toString());
        out.put("fts_db", properties.ftsDbPath().toString());
        out.put("fts_db_available", indexStore.ftsDbAvailable());
        out.put("requested_sources", sources != null ? sources : List.of());
        out.put("rebuild_note", "Full FTS rebuild runs outside the app — see Bankoapp-main catalog_index_builder.py");
        Map<String, Object> warmup = catalogWarmupService.triggerWarmup();
        out.put("warmup_triggered", true);
        out.put("warmup_status", warmup.get("status"));
        out.put("warmup_detail", warmup.getOrDefault("detail", warmup));
        return out;
    }

    private Map<String, Object> toSearchTestHit(Map<String, Object> doc) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", doc.get("source"));
        out.put("dataset_id", doc.getOrDefault("dataset_id", doc.get("set_id")));
        out.put("title", doc.getOrDefault("title", doc.get("name")));
        out.put("description", doc.get("description"));
        out.put("countries", doc.getOrDefault("countries", List.of()));
        out.put("frequency", doc.get("frequency"));
        out.put("unit", doc.get("unit"));
        out.put("score", doc.getOrDefault("relevance_score", doc.get("_search_score")));
        out.put("tags", doc.getOrDefault("tags", List.of()));
        out.put("has_forecast", doc.getOrDefault("has_forecast", false));
        out.put("forecast_source", doc.get("forecast_source"));
        out.put("indicator_role", doc.get("indicator_role"));
        return out;
    }

    private static List<String> parseSources(String sourcesCsv) {
        if (sourcesCsv == null || sourcesCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(sourcesCsv.split(","))
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .map(String::toLowerCase)
                .toList();
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value != null ? Integer.parseInt(String.valueOf(value)) : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
