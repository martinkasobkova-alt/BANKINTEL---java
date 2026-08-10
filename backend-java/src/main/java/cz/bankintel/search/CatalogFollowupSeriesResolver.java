package cz.bankintel.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves opaque LLM-selected ids strictly against series supplied by the search result. */
final class CatalogFollowupSeriesResolver {

    private CatalogFollowupSeriesResolver() {}

    static List<Map<String, Object>> resolve(
            CatalogFollowupPlan plan, List<Map<String, Object>> availableSeries) {
        if (plan == null || plan.seriesRefIds().isEmpty() || availableSeries == null) {
            return List.of();
        }
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : availableSeries) {
            String id = refId(row);
            if (!id.isBlank()) {
                byId.putIfAbsent(id, row);
            }
        }
        List<Map<String, Object>> selected = new ArrayList<>();
        for (String id : plan.seriesRefIds()) {
            Map<String, Object> row = byId.get(id);
            if (row != null) {
                selected.add(row);
            }
        }
        return selected;
    }

    @SafeVarargs
    static List<Map<String, Object>> merge(List<Map<String, Object>>... groups) {
        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (groups == null) {
            return merged;
        }
        for (List<Map<String, Object>> group : groups) {
            if (group == null) {
                continue;
            }
            for (Map<String, Object> row : group) {
                String id = refId(row);
                if (!id.isBlank() && seen.add(id)) {
                    merged.add(row);
                }
            }
        }
        return merged;
    }

    static String refId(Map<String, Object> row) {
        if (row == null) {
            return "";
        }
        String explicit = string(row.get("ref_id"));
        if (!explicit.isBlank()) {
            return explicit;
        }
        String source = string(row.get("source_type"));
        if (source.isBlank()) {
            source = string(row.get("catalog_id"));
        }
        String setId = string(row.get("set_id"));
        if (setId.isBlank()) {
            setId = string(row.get("series_id"));
        }
        return source.isBlank() || setId.isBlank() ? "" : source + "|" + setId;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
