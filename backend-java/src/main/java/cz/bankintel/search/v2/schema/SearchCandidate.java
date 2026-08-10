package cz.bankintel.search.v2.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SearchCandidate(
        String candidateId,
        String seriesId,
        String title,
        String description,
        String source,
        String dataset,
        String geo,
        String frequency,
        String unit,
        String seasonalAdjustment,
        List<String> concepts,
        List<String> tags,
        List<String> categoryPath,
        String latestDate,
        double ftsScore,
        String matchedQuery,
        List<String> matchedFields,
        Map<String, Object> raw) {

    private static final List<String> RAW_PASSTHROUGH_KEYS = List.of(
            "query_params",
            "catalog_node_ref",
            "item_kind",
            "kind",
            "actions",
            "dataset_code",
            "indicator_id",
            "country",
            "territory",
            "period",
            "full_path",
            "catalog_path",
            "lifecycle_status",
            "lifecycle_reason",
            "lifecycle_confidence");

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("candidate_id", candidateId);
        out.put("series_id", seriesId);
        out.put("set_id", seriesId);
        out.put("title", title);
        out.put("name", title);
        out.put("description", description);
        out.put("catalog_id", source);
        out.put("source", source);
        out.put("source_type", source);
        out.put("dataset", dataset);
        out.put("geo", geo);
        out.put("frequency", frequency);
        out.put("unit", unit);
        out.put("seasonal_adjustment", seasonalAdjustment);
        out.put("concepts", concepts);
        out.put("tags", tags);
        out.put("category_path", categoryPath);
        out.put("latest_date", latestDate);
        out.put("fts_score", ftsScore);
        out.put("_fts_rank", ftsScore);
        out.put("matched_query", matchedQuery);
        out.put("matched_fields", matchedFields);
        if (raw != null) {
            for (String key : RAW_PASSTHROUGH_KEYS) {
                if (raw.containsKey(key) && !out.containsKey(key)) {
                    out.put(key, raw.get(key));
                }
            }
        }
        out.put("raw", raw);
        return out;
    }
}
