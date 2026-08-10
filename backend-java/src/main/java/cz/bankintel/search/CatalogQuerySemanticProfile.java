package cz.bankintel.search;

import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.GeoIntentSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a source-agnostic query profile from data-driven lexicons.
 *
 * <p>The profile is deliberately descriptive, not prescriptive: it explains metric, domain, geo,
 * and expansion signals that other services can inspect without hardcoding one-off topics such as
 * a specific macro indicator.
 */
public final class CatalogQuerySemanticProfile {

    private CatalogQuerySemanticProfile() {}

    public static Map<String, Object> build(
            String query,
            GeoIntentSnapshot geoIntent,
            List<String> searchTerms,
            List<String> indexProbeTerms,
            List<String> likelySources) {
        String normalizedQuery = normalizeWhitespace(query);
        GeoIntentSnapshot geo = geoIntent == null || geoIntent.isEmpty()
                ? GeoIntentSnapshot.fromDetection(normalizedQuery)
                : geoIntent;
        Map<String, Object> geoMap = geo == null ? Map.of() : geo.toMap();
        CatalogQueryIntent.QueryIntent intent = CatalogQueryIntent.classifyQueryIntent(normalizedQuery, geoMap);

        List<Map<String, Object>> metricTerms = termMaps(intent.metricTerms());
        List<Map<String, Object>> domainTerms = termMaps(intent.domainTerms());
        List<Map<String, Object>> geoTerms = termMaps(intent.geoTerms());
        List<String> queryVariants = mergeStrings(searchTerms, indexProbeTerms);
        List<String> indicators = indicatorLabels(intent.metricTerms(), queryVariants);
        String queryShape = queryShape(metricTerms, domainTerms, geoTerms, queryVariants);
        String topic = topicLabel(metricTerms, domainTerms, intent.activeGroups(), normalizedQuery);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", 1);
        out.put(CatalogKeys.NORMALIZED_QUERY_CZ, normalizedQuery);
        out.put("normalized_query_ascii", CatalogTextUtils.foldAscii(normalizedQuery));
        out.put(CatalogKeys.ENGLISH_QUERY, firstDifferentVariant(normalizedQuery, queryVariants));
        out.put(CatalogKeys.TOPIC, topic);
        out.put(CatalogKeys.INDICATORS, indicators);
        out.put(CatalogKeys.QUERY_VARIANTS, queryVariants);
        out.put(CatalogKeys.QUERY_SHAPE, queryShape);
        out.put(CatalogKeys.METRIC_TERMS, metricTerms);
        out.put(CatalogKeys.DOMAIN_TERMS, domainTerms);
        out.put("geo_terms", geoTerms);
        out.put(CatalogKeys.ACTIVE_GROUPS, List.copyOf(intent.activeGroups()));
        out.put(CatalogKeys.INDEX_PROBE_TERMS, cleanList(indexProbeTerms, 12));
        out.put("source_hints", sourceHints(likelySources));
        out.put("confidence", confidence(metricTerms, domainTerms, geoTerms, queryVariants));
        out.put("explanation", "Query profile built from catalog intent, synonym, geo, and source-routing lexicons.");
        return out;
    }

    private static List<Map<String, Object>> termMaps(List<CatalogQueryIntent.IntentTerm> terms) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CatalogQueryIntent.IntentTerm term : terms == null ? List.<CatalogQueryIntent.IntentTerm>of() : terms) {
            String raw = normalizeWhitespace(term.raw());
            if (raw.isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", raw);
            row.put("surfaces", cleanList(term.surfaces(), 8));
            out.add(row);
        }
        return out;
    }

    private static List<String> indicatorLabels(
            List<CatalogQueryIntent.IntentTerm> metricTerms, List<String> queryVariants) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (CatalogQueryIntent.IntentTerm term :
                metricTerms == null ? List.<CatalogQueryIntent.IntentTerm>of() : metricTerms) {
            addIfUseful(out, term.raw());
            for (String surface : term.surfaces()) {
                addIfUseful(out, surface);
                if (out.size() >= 8) {
                    return List.copyOf(out);
                }
            }
        }
        for (String variant : queryVariants) {
            addIfUseful(out, variant);
            if (out.size() >= 8) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static String queryShape(
            List<Map<String, Object>> metricTerms,
            List<Map<String, Object>> domainTerms,
            List<Map<String, Object>> geoTerms,
            List<String> queryVariants) {
        boolean metric = !metricTerms.isEmpty();
        boolean domain = !domainTerms.isEmpty();
        boolean geo = !geoTerms.isEmpty();
        if (metric && domain && geo) return "metric_domain_geo";
        if (metric && domain) return "metric_domain";
        if (metric && geo) return "metric_geo";
        if (domain && geo) return "domain_geo";
        if (metric) return "metric";
        if (domain) return "domain";
        if (geo) return "open_topic_geo";
        return queryVariants.size() > 1 ? "expanded_open_topic" : "open_topic";
    }

    private static String topicLabel(
            List<Map<String, Object>> metricTerms,
            List<Map<String, Object>> domainTerms,
            List<String> activeGroups,
            String normalizedQuery) {
        String metric = firstLabel(metricTerms);
        String domain = firstLabel(domainTerms);
        if (!metric.isBlank() && !domain.isBlank()) {
            return metric + " / " + domain;
        }
        if (!metric.isBlank()) {
            return metric;
        }
        if (!domain.isBlank()) {
            return domain;
        }
        if (activeGroups != null && !activeGroups.isEmpty()) {
            return activeGroups.getFirst();
        }
        return normalizedQuery;
    }

    private static List<Map<String, Object>> sourceHints(List<String> likelySources) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String source : cleanList(likelySources, 8)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", source);
            row.put("label", CatalogSourceRegistry.label(source));
            out.add(row);
        }
        return out;
    }

    private static double confidence(
            List<Map<String, Object>> metricTerms,
            List<Map<String, Object>> domainTerms,
            List<Map<String, Object>> geoTerms,
            List<String> queryVariants) {
        double score = 0.2;
        if (!metricTerms.isEmpty()) score += 0.25;
        if (!domainTerms.isEmpty()) score += 0.2;
        if (!geoTerms.isEmpty()) score += 0.15;
        if (queryVariants.size() > 1) score += 0.1;
        return Math.min(0.95, Math.round(score * 100.0) / 100.0);
    }

    private static String firstLabel(List<Map<String, Object>> terms) {
        if (terms == null || terms.isEmpty()) {
            return "";
        }
        Object label = terms.getFirst().get("label");
        return normalizeWhitespace(label == null ? "" : String.valueOf(label));
    }

    private static String firstDifferentVariant(String query, List<String> variants) {
        String q = CatalogTextUtils.foldAscii(query);
        for (String variant : variants) {
            if (!CatalogTextUtils.foldAscii(variant).equals(q)) {
                return variant;
            }
        }
        return "";
    }

    @SafeVarargs
    private static List<String> mergeStrings(List<String>... lists) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (List<String> list : lists) {
            for (String value : list == null ? List.<String>of() : list) {
                addIfUseful(out, value);
                if (out.size() >= 16) {
                    return List.copyOf(out);
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<String> cleanList(List<String> values, int limit) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            addIfUseful(out, value);
            if (out.size() >= limit) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static void addIfUseful(Set<String> out, String value) {
        String text = normalizeWhitespace(value);
        if (text.length() >= 2) {
            out.add(text);
        }
    }

    private static String normalizeWhitespace(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
