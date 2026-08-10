package cz.bankintel.search.model;

import cz.bankintel.search.CatalogGeoIntent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed search plan — replaces loose planner maps inside search core. */
public record SearchPlan(
        List<String> sources,
        List<String> searchTerms,
        List<String> likelySources,
        GeoIntentSnapshot geoIntent,
        String topic,
        String countryHint,
        String planner,
        List<String> indexProbeTerms,
        Map<String, Object> semanticProfile) {

    public SearchPlan(
            List<String> sources,
            List<String> searchTerms,
            List<String> likelySources,
            GeoIntentSnapshot geoIntent,
            String topic,
            String countryHint,
            String planner,
            List<String> indexProbeTerms) {
        this(sources, searchTerms, likelySources, geoIntent, topic, countryHint, planner, indexProbeTerms, Map.of());
    }

    public SearchPlan(
            List<String> sources,
            List<String> searchTerms,
            List<String> likelySources,
            GeoIntentSnapshot geoIntent,
            String topic,
            String countryHint,
            String planner) {
        this(sources, searchTerms, likelySources, geoIntent, topic, countryHint, planner, List.of(), Map.of());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(CatalogKeys.SOURCES, sources);
        out.put(CatalogKeys.SEARCH_TERMS, searchTerms);
        out.put(CatalogKeys.LIKELY_SOURCES, likelySources);
        out.put(CatalogKeys.GEO_INTENT, geoIntent == null ? Map.of() : geoIntent.toMap());
        out.put(CatalogKeys.TOPIC, topic == null ? "" : topic);
        out.put(CatalogKeys.COUNTRY_HINT, countryHint == null ? "" : countryHint);
        out.put(CatalogKeys.PLANNER, planner == null ? "local" : planner);
        out.put(CatalogKeys.INDEX_PROBE_TERMS, indexProbeTerms == null ? List.of() : indexProbeTerms);
        Map<String, Object> profile = semanticProfile == null ? Map.of() : semanticProfile;
        out.put(CatalogKeys.SEMANTIC_PROFILE, profile);
        putProfileAlias(out, profile, CatalogKeys.NORMALIZED_QUERY_CZ);
        putProfileAlias(out, profile, CatalogKeys.ENGLISH_QUERY);
        putProfileAlias(out, profile, CatalogKeys.INDICATORS);
        putProfileAlias(out, profile, CatalogKeys.QUERY_VARIANTS);
        putProfileAlias(out, profile, CatalogKeys.QUERY_SHAPE);
        putProfileAlias(out, profile, CatalogKeys.METRIC_TERMS);
        putProfileAlias(out, profile, CatalogKeys.DOMAIN_TERMS);
        putProfileAlias(out, profile, CatalogKeys.ACTIVE_GROUPS);
        return out;
    }

    public static SearchPlan fromMap(Map<String, Object> map) {
        if (map == null) {
            return empty();
        }
        return new SearchPlan(
                stringList(map.get(CatalogKeys.SOURCES)),
                stringList(map.get(CatalogKeys.SEARCH_TERMS)),
                stringList(map.get(CatalogKeys.LIKELY_SOURCES)),
                GeoIntentSnapshot.fromMap(map.get(CatalogKeys.GEO_INTENT)),
                CatalogMapSupport.str(map.get(CatalogKeys.TOPIC)),
                CatalogMapSupport.str(map.get(CatalogKeys.COUNTRY_HINT)),
                CatalogMapSupport.str(map.get(CatalogKeys.PLANNER)),
                stringList(map.get(CatalogKeys.INDEX_PROBE_TERMS)),
                objectMap(map.get(CatalogKeys.SEMANTIC_PROFILE)));
    }

    public static SearchPlan empty() {
        return new SearchPlan(List.of(), List.of(), List.of(), GeoIntentSnapshot.empty(), "", "", "local", List.of(), Map.of());
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String text = CatalogMapSupport.str(item);
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out;
    }

    public SearchPlan withRouting(String query, List<String> routedSources, List<String> likely) {
        GeoIntentSnapshot geo = geoIntent != null && !geoIntent.isEmpty()
                ? geoIntent
                : GeoIntentSnapshot.fromDetection(query);
        return new SearchPlan(
                routedSources,
                searchTerms,
                likely == null ? likelySources : likely,
                geo,
                topic,
                countryHint,
                planner,
                indexProbeTerms,
                semanticProfile);
    }

    public static GeoIntentSnapshot detectGeo(String query) {
        return GeoIntentSnapshot.fromDetection(query);
    }

    private static void putProfileAlias(Map<String, Object> out, Map<String, Object> profile, String key) {
        Object value = profile.get(key);
        if (value != null) {
            out.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = CatalogMapSupport.str(entry.getKey());
            if (!key.isBlank()) {
                out.put(key, entry.getValue());
            }
        }
        return out;
    }
}
