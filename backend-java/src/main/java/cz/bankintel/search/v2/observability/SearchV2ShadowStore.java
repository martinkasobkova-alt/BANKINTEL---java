package cz.bankintel.search.v2.observability;

import cz.bankintel.search.model.CatalogMapSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SearchV2ShadowStore {

    private static final int MAX_COMPARISONS = 100;

    private final Map<String, Map<String, Object>> comparisons = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
            return size() > MAX_COMPARISONS;
        }
    };

    public synchronized Map<String, Object> save(String query, Map<String, Object> v1, Map<String, Object> v2) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("shadow_id", id);
        row.put("query", query);
        row.put("created_at_ms", System.currentTimeMillis());
        row.put("v1", summary(v1));
        row.put("v2", summary(v2));
        row.put("v2_trace_id", v2.get("trace_id"));
        comparisons.put(id, row);
        return row;
    }

    public synchronized List<Map<String, Object>> recent() {
        return new ArrayList<>(comparisons.values());
    }

    private static Map<String, Object> summary(Map<String, Object> result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", result.get("status"));
        out.put("message", result.get("message"));
        out.put("semantic_rerank_status", result.get("semantic_rerank_status"));
        out.put("coverage", result.get("coverage"));
        out.put("trace_id", result.get("trace_id"));
        out.put("top", top(result));
        out.put("result_count", resultCount(result));
        out.put("timings", result.get("timings"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> top(Map<String, Object> result) {
        List<?> list = null;
        Object raw = result.get("results");
        if (raw instanceof List<?> results) {
            list = results;
        } else if (result.get("verified") instanceof List<?> verified) {
            list = verified;
        }
        if (list == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list.stream().limit(5).toList()) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> row = CatalogMapSupport.castMap(map);
            out.add(Map.of(
                    "source", CatalogMapSupport.firstNonBlank(row.get("source"), row.get("source_type")),
                    "series_id", CatalogMapSupport.firstNonBlank(row.get("series_id"), row.get("set_id")),
                    "title", CatalogMapSupport.firstNonBlank(row.get("title"), row.get("name"))));
        }
        return out;
    }

    private static int resultCount(Map<String, Object> result) {
        Object raw = result.get("results");
        if (raw instanceof List<?> list) {
            return list.size();
        }
        raw = result.get("verified");
        if (raw instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }
}
