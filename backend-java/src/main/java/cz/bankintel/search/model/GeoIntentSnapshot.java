package cz.bankintel.search.model;

import cz.bankintel.search.CatalogGeoIntent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Snapshot of geo intent attached to a search plan or scoring context. */
public record GeoIntentSnapshot(Map<String, Object> fields) {

    public static GeoIntentSnapshot fromDetection(String query) {
        return new GeoIntentSnapshot(CatalogGeoIntent.detectGeoIntent(query));
    }

    public static GeoIntentSnapshot fromMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return new GeoIntentSnapshot(CatalogMapSupport.castMap(map));
        }
        return empty();
    }

    public static GeoIntentSnapshot empty() {
        return new GeoIntentSnapshot(Map.of("type", "unknown"));
    }

    public Map<String, Object> toMap() {
        return fields == null ? Map.of() : new LinkedHashMap<>(fields);
    }

    public boolean isEmpty() {
        return fields == null || fields.isEmpty();
    }

    public String type() {
        return CatalogMapSupport.str(fields.get("type"));
    }

    public String countryCode() {
        return CatalogMapSupport.str(fields.get("country_code"));
    }

    @SuppressWarnings("unchecked")
    public List<String> countryCodes() {
        Object raw = fields.get("country_codes");
        if (raw instanceof List<?> list) {
            return list.stream().map(CatalogMapSupport::str).filter(s -> !s.isBlank()).toList();
        }
        return List.of();
    }
}
