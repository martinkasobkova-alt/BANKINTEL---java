package cz.bankintel.search;

import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.CatalogMapSupport;
import java.util.Map;

/** Canonical text projection used by catalog semantic scoring and preview selection. */
final class CatalogSemanticRowText {

    private CatalogSemanticRowText() {}

    static String title(Map<String, Object> row) {
        return CatalogTextUtils.rowTitle(row);
    }

    static String haystack(Map<String, Object> row) {
        return String.join(
                " ",
                title(row),
                CatalogMapSupport.str(row.get(CatalogKeys.FULL_PATH)),
                CatalogMapSupport.str(row.get("path")),
                CatalogMapSupport.str(row.get(CatalogKeys.SET_ID)),
                CatalogMapSupport.str(row.get(CatalogKeys.CATALOG_LABEL)),
                CatalogMapSupport.str(row.get("description")),
                CatalogMapSupport.str(row.get("unit")),
                nestedString(row, "description"),
                nestedString(row, "unit"),
                nestedString(row, "search_blob"),
                nestedString(row, "_search_blob"));
    }

    static String unitHaystack(Map<String, Object> row) {
        return String.join(" ", CatalogMapSupport.str(row.get("unit")), nestedString(row, "unit"));
    }

    private static String nestedString(Map<String, Object> row, String key) {
        Object nested = row.get(CatalogKeys.ROW);
        if (nested instanceof Map<?, ?> map) {
            Object value = map.get(key);
            return value == null ? "" : String.valueOf(value);
        }
        return "";
    }
}
