package cz.bankintel.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates that a live preview really belongs to the geography requested by the query.
 */
final class CatalogPreviewGeoValidator {

    private static final Set<String> GEO_KEYS = Set.of(
            "geo",
            "geo_code",
            "geo_id",
            "ref_area",
            "refarea",
            "ref_area_code",
            "country",
            "country_code",
            "country_iso2",
            "iso2",
            "location",
            "area",
            "territory");

    private static final Set<String> GEO_LABEL_KEYS = Set.of(
            "geo_label",
            "ref_area_label",
            "country_label",
            "location_label",
            "area_label",
            "territory_label");

    private CatalogPreviewGeoValidator() {}

    static GeoPreviewCheck validate(
            Map<String, Object> candidateRow, Map<String, Object> preview, Map<String, Object> geoIntent) {
        List<String> requested = CatalogGeoIntent.requestedGeoCodes(geoIntent);
        if (requested.isEmpty()) {
            return GeoPreviewCheck.match(List.of(), List.of());
        }

        Set<String> observed = new LinkedHashSet<>(collectGeoCodesFromPreview(preview));
        if (observed.isEmpty()) {
            String rowCountry = CatalogGeoIntent.extractRowCountryCode(candidateRow);
            if (!rowCountry.isBlank()) {
                observed.add(rowCountry.toUpperCase(Locale.ROOT));
            }
        }
        if (observed.isEmpty()) {
            return GeoPreviewCheck.match(requested, List.of());
        }

        Set<String> requestedSet = new LinkedHashSet<>();
        for (String code : requested) {
            requestedSet.add(code.toUpperCase(Locale.ROOT));
        }
        boolean overlap = observed.stream().anyMatch(requestedSet::contains);
        return overlap
                ? GeoPreviewCheck.match(requested, new ArrayList<>(observed))
                : GeoPreviewCheck.mismatch(requested, new ArrayList<>(observed));
    }

    @SuppressWarnings("unchecked")
    static List<String> collectGeoCodesFromPreview(Map<String, Object> preview) {
        if (preview == null || preview.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        Object rowsObj = preview.get("rows");
        if (rowsObj instanceof List<?> rows) {
            int limit = Math.min(rows.size(), 200);
            for (int i = 0; i < limit; i++) {
                if (rows.get(i) instanceof Map<?, ?> rowRaw) {
                    collectGeoCodesFromRow((Map<String, Object>) rowRaw, out);
                }
            }
        }
        Object metaObj = preview.get("metadata");
        if (metaObj instanceof Map<?, ?> metaRaw) {
            collectGeoCodesFromRow((Map<String, Object>) metaRaw, out);
        }
        return new ArrayList<>(out);
    }

    private static void collectGeoCodesFromRow(Map<String, Object> row, Set<String> out) {
        if (row == null || row.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (!GEO_KEYS.contains(key) && !GEO_LABEL_KEYS.contains(key)) {
                continue;
            }
            addGeoValue(entry.getValue(), out);
        }
    }

    private static void addGeoValue(Object raw, Set<String> out) {
        if (raw == null) {
            return;
        }
        Iterable<?> values = raw instanceof Iterable<?> iterable ? iterable : List.of(raw);
        for (Object value : values) {
            String text = String.valueOf(value == null ? "" : value).trim();
            if (text.isBlank()) {
                continue;
            }
            String upper = text.toUpperCase(Locale.ROOT);
            if (CatalogGeoIntent.EU_AGGREGATE_GEO_CODES.contains(upper)) {
                out.add(upper);
                continue;
            }
            String code = CatalogGeoIntent.resolveTerritoryToCountryCode(text);
            if (!code.isBlank()) {
                out.add(code.toUpperCase(Locale.ROOT));
            }
        }
    }

    record GeoPreviewCheck(boolean matches, List<String> requestedCodes, List<String> observedCodes) {
        static GeoPreviewCheck match(List<String> requestedCodes, List<String> observedCodes) {
            return new GeoPreviewCheck(true, requestedCodes, observedCodes);
        }

        static GeoPreviewCheck mismatch(List<String> requestedCodes, List<String> observedCodes) {
            return new GeoPreviewCheck(false, requestedCodes, observedCodes);
        }
    }
}
