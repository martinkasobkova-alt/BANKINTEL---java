package cz.bankintel.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CatalogClassicSearchService {

    private final CatalogIndexStore indexStore;
    private final CatalogSearchProperties properties;

    public Map<String, Object> search(Map<String, Object> payload) {
        String src = firstNonBlank(payload, "source", "catalog_id");
        String query = firstNonBlank(payload, "query", "q");
        int limit = parseLimit(payload.get("limit"), 50);
        String normalized = CatalogSourceRegistry.normalizeSearchSource(src);

        if (query.length() < 2) {
            return baseResponse(src, query, List.of(), "Zadejte alespoň 2 znaky.", false, properties.fredApiKeyConfigured());
        }
        if (normalized.isBlank()) {
            return baseResponse(
                    src, query, List.of(), "Neznámý zdroj pro katalogové vyhledávání.", false, properties.fredApiKeyConfigured());
        }

        try {
            List<Map<String, Object>> results = indexStore.searchSource(normalized, query, limit);
            Map<String, Object> body =
                    baseResponse(src, query, results, null, false, properties.fredApiKeyConfigured());
            body.put("index_backend", indexStore.ftsDbAvailable() ? "sqlite_fts" : "jsonl_scan");
            body.put("index_dir", properties.indexDir().toString());
            return body;
        } catch (Exception ex) {
            return baseResponse(
                    src,
                    query,
                    List.of(),
                    "Vyhledávání se nepodařilo dokončit. Zkuste to prosím znovu.",
                    true,
                    properties.fredApiKeyConfigured());
        }
    }

    private static Map<String, Object> baseResponse(
            String source,
            String query,
            List<Map<String, Object>> results,
            String messageCs,
            boolean upstream,
            boolean fredConfigured) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source == null ? "" : source.trim().toLowerCase());
        body.put("query", query == null ? "" : query.trim());
        body.put("results", results);
        body.put("themes_triggered", List.of());
        if (messageCs != null) {
            body.put("message_cs", messageCs);
        }
        body.put("upstream_unavailable", upstream);
        body.put("fred_api_configured", fredConfigured);
        body.put("result_cache_hit", false);
        return body;
    }

    private static String firstNonBlank(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isBlank()) {
                    return text.toLowerCase();
                }
            }
        }
        return "";
    }

    private static int parseLimit(Object raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(String.valueOf(raw));
            return Math.max(1, Math.min(value, 100));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
