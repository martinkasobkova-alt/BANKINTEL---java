package cz.bankintel.search.model;

import cz.bankintel.search.CatalogTextUtils;
import java.util.Map;

/** Parsed upstream catalog row (FTS JSONL / SQLite payload) — isolates loose JSON to one type. */
public record CatalogRawRow(Map<String, Object> fields) {

    public static CatalogRawRow of(Map<String, Object> fields) {
        return new CatalogRawRow(fields == null ? Map.of() : fields);
    }

    public String setId() {
        return CatalogMapSupport.str(fields.get(CatalogKeys.SET_ID));
    }

    public String title() {
        return CatalogTextUtils.rowTitle(fields);
    }

    public double ftsRank() {
        return CatalogMapSupport.toDouble(fields.get(CatalogKeys.FTS_RANK));
    }

    public int matchScore() {
        return CatalogMapSupport.toInt(fields.get(CatalogKeys.MATCH), 0);
    }

    public boolean sidecarRescue() {
        return Boolean.TRUE.equals(fields.get(CatalogKeys.SIDECAR_RESCUE));
    }

    public CatalogRawRow withField(String key, Object value) {
        var copy = new java.util.LinkedHashMap<>(fields);
        copy.put(key, value);
        return new CatalogRawRow(copy);
    }
}
