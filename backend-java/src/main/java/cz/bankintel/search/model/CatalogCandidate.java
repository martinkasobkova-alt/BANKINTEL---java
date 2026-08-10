package cz.bankintel.search.model;

import cz.bankintel.search.CatalogSourceRegistry;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deep-search candidate / preview row — extends scored hit with preview lifecycle fields. */
public record CatalogCandidate(
        CatalogHit hit,
        String previewStatus,
        String status,
        String resultTier,
        String whyRelevant,
        boolean previewAvailable,
        int previewRowCount,
        String reason,
        Map<String, Object> previewPayload,
        Map<String, Object> extraFields) {

    public static CatalogCandidate fromHit(CatalogHit hit, String query) {
        return decorate(hit, query, Map.of());
    }

    public static CatalogCandidate decorate(CatalogHit hit, String query, Map<String, Object> extra) {
        Map<String, Object> mergedExtra = extra == null ? Map.of() : extra;
        String label = CatalogSourceRegistry.label(hit.sourceType());
        return new CatalogCandidate(
                hit,
                CatalogMapSupport.str(mergedExtra.getOrDefault(CatalogKeys.PREVIEW_STATUS, "pending")),
                CatalogMapSupport.str(mergedExtra.getOrDefault(CatalogKeys.STATUS, "candidate")),
                CatalogMapSupport.str(mergedExtra.getOrDefault(CatalogKeys.RESULT_TIER, "candidate")),
                CatalogMapSupport.str(mergedExtra.getOrDefault(
                        CatalogKeys.WHY_RELEVANT, "Shoda s dotazem \"" + query + "\" v katalogu " + label)),
                Boolean.TRUE.equals(mergedExtra.get(CatalogKeys.PREVIEW_AVAILABLE)),
                CatalogMapSupport.toInt(mergedExtra.get("preview_row_count"), 0),
                CatalogMapSupport.str(mergedExtra.get("reason")),
                mergedExtra.get("preview_payload") instanceof Map<?, ?> pp
                        ? CatalogMapSupport.castMap(pp)
                        : null,
                mergedExtra);
    }

    public static CatalogCandidate fromMap(Map<String, Object> map) {
        CatalogHit hit = CatalogHit.fromMap(map);
        return new CatalogCandidate(
                hit,
                CatalogMapSupport.str(map.get(CatalogKeys.PREVIEW_STATUS)),
                CatalogMapSupport.str(map.get(CatalogKeys.STATUS)),
                CatalogMapSupport.str(map.get(CatalogKeys.RESULT_TIER)),
                CatalogMapSupport.str(map.get(CatalogKeys.WHY_RELEVANT)),
                Boolean.TRUE.equals(map.get(CatalogKeys.PREVIEW_AVAILABLE)),
                CatalogMapSupport.toInt(map.get("preview_row_count"), 0),
                CatalogMapSupport.str(map.get("reason")),
                map.get("preview_payload") instanceof Map<?, ?> pp ? CatalogMapSupport.castMap(pp) : null,
                map);
    }

    /** Backward-compatible map for API / legacy callers. */
    public Map<String, Object> toMap() {
        Map<String, Object> row = new LinkedHashMap<>(hit.toMap());
        row.put(CatalogKeys.SOURCE, hit.sourceType());
        row.put(CatalogKeys.PREVIEW_STATUS, previewStatus);
        row.put(CatalogKeys.STATUS, status);
        row.put(CatalogKeys.RESULT_TIER, resultTier);
        row.put(CatalogKeys.WHY_RELEVANT, whyRelevant);
        row.put(CatalogKeys.PREVIEW_AVAILABLE, previewAvailable);
        if (previewRowCount > 0) {
            row.put("preview_row_count", previewRowCount);
        }
        if (reason != null && !reason.isBlank()) {
            row.put("reason", reason);
            row.put("verify_note", reason);
        }
        if (previewPayload != null) {
            row.put("preview_payload", previewPayload);
        }
        if (extraFields != null) {
            for (Map.Entry<String, Object> entry : extraFields.entrySet()) {
                row.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return row;
    }

    public CatalogCandidate withPreviewFields(Map<String, Object> fields) {
        Map<String, Object> merged = new LinkedHashMap<>(extraFields == null ? Map.of() : extraFields);
        if (fields != null) {
            merged.putAll(fields);
        }
        return decorate(hit, CatalogMapSupport.str(merged.get(CatalogKeys.WHY_RELEVANT)), merged);
    }
}
