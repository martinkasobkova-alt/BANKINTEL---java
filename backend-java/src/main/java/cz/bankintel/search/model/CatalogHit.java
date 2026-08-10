package cz.bankintel.search.model;

import cz.bankintel.search.CatalogSourceRegistry;
import java.util.LinkedHashMap;
import java.util.Map;

/** Scored catalog search hit returned by {@code CatalogIndexStore}. */
public record CatalogHit(
        String sourceType,
        String setId,
        String name,
        String title,
        String fullPath,
        int searchScore,
        int metadataScore,
        int lexicalScore,
        String geoAdjustment,
        String countryHint,
        CatalogRawRow rawRow,
        int nearDuplicatesCollapsed) {

    public Map<String, Object> toMap() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put(CatalogKeys.SET_ID, setId);
        item.put(CatalogKeys.NAME, name);
        item.put(CatalogKeys.TITLE, title);
        item.put(CatalogKeys.FULL_PATH, fullPath);
        item.put(CatalogKeys.CATALOG_ID, sourceType);
        item.put(CatalogKeys.CATALOG_LABEL, CatalogSourceRegistry.label(sourceType));
        item.put(CatalogKeys.SOURCE_TYPE, sourceType);
        item.put(CatalogKeys.SEARCH_SCORE, searchScore);
        item.put(CatalogKeys.METADATA_SCORE, metadataScore);
        item.put(CatalogKeys.LEXICAL_SCORE, lexicalScore);
        if (geoAdjustment != null && !geoAdjustment.isBlank()) {
            item.put(CatalogKeys.GEO_ADJUSTMENT, geoAdjustment);
        }
        if (countryHint != null && !countryHint.isBlank()) {
            item.put(CatalogKeys.COUNTRY_HINT, countryHint);
        }
        if (nearDuplicatesCollapsed > 0) {
            item.put("_near_duplicates_collapsed", nearDuplicatesCollapsed);
        }
        item.put(CatalogKeys.ROW, rawRow.fields());
        return item;
    }

    public static CatalogHit fromMap(Map<String, Object> map) {
        if (map == null) {
            return empty();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = map.get(CatalogKeys.ROW) instanceof Map<?, ?> nested
                ? CatalogMapSupport.castMap(nested)
                : map;
        return new CatalogHit(
                CatalogMapSupport.str(map.getOrDefault(CatalogKeys.SOURCE_TYPE, map.get(CatalogKeys.CATALOG_ID))),
                CatalogMapSupport.str(map.get(CatalogKeys.SET_ID)),
                CatalogMapSupport.firstNonBlank(map.get(CatalogKeys.NAME), map.get(CatalogKeys.TITLE)),
                CatalogMapSupport.firstNonBlank(map.get(CatalogKeys.TITLE), map.get(CatalogKeys.NAME)),
                CatalogMapSupport.str(map.get(CatalogKeys.FULL_PATH)),
                CatalogMapSupport.toInt(map.get(CatalogKeys.SEARCH_SCORE), 0),
                CatalogMapSupport.toInt(map.get(CatalogKeys.METADATA_SCORE), 0),
                CatalogMapSupport.toInt(map.get(CatalogKeys.LEXICAL_SCORE), 0),
                CatalogMapSupport.str(map.get(CatalogKeys.GEO_ADJUSTMENT)),
                CatalogMapSupport.str(map.get(CatalogKeys.COUNTRY_HINT)),
                CatalogRawRow.of(raw),
                CatalogMapSupport.toInt(map.get("_near_duplicates_collapsed"), 0));
    }

    public CatalogHit withNearDuplicatesCollapsed(int count) {
        return new CatalogHit(
                sourceType,
                setId,
                name,
                title,
                fullPath,
                searchScore,
                metadataScore,
                lexicalScore,
                geoAdjustment,
                countryHint,
                rawRow,
                count);
    }

    public static CatalogHit empty() {
        return new CatalogHit("", "", "", "", "", 0, 0, 0, null, null, CatalogRawRow.of(Map.of()), 0);
    }
}
