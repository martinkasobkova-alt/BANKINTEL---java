package cz.bankintel.search.v2.normalization;

import cz.bankintel.search.CatalogGeoIntent;
import cz.bankintel.search.CatalogSourceRegistry;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Adds stable, machine-readable identity metadata to a final search row. The service only promotes
 * evidence already present in catalog metadata, dimensions, preview rows or coverage sets. It does
 * not invent a country or sector when the evidence is missing or contradictory.
 */
@Service
@RequiredArgsConstructor
public class SearchResultCanonicalMetadataService {

    private static final Set<String> GEO_KEYS = Set.of(
            "geo", "geo_code", "country", "country_code", "ref_area", "reference_area", "territory");
    private static final Set<String> GEO_COVERAGE_KEYS = Set.of(
            "geo_coverage", "geo_coverage_sample", "country_coverage", "coverage_countries");
    private static final Set<String> SECTOR_KEYS = Set.of(
            "institutional_sector", "institutional_sectors", "ref_sector", "reference_sector", "sector_id");
    private static final Set<String> METRIC_KEYS = Set.of(
            "metric_intent", "metric_intents", "primary_concept", "primary_metric", "metric");

    private final SearchV2InstitutionalSectorRegistry sectorRegistry;
    private final SearchV2MetricIntentRegistry metricRegistry;

    public Map<String, Object> enrich(Map<String, Object> input) {
        Map<String, Object> row = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        Map<String, List<String>> provenance = new LinkedHashMap<>();

        String sourceField = firstPresentField(row, List.of("source", "source_type", "catalog_id"));
        String source = CatalogSourceRegistry.normalizeSearchSource(CatalogMapSupport.str(row.get(sourceField)));
        row.put("canonical_source_id", source);
        provenance.put("source", source.isBlank() ? List.of() : List.of("fixed_field:" + sourceField));

        CanonicalValues geo = canonicalGeo(row, source);
        row.put("canonical_geo_codes", geo.values());
        row.put("geo_scope", geo.scope());
        provenance.put("geo", geo.provenance());

        CanonicalValues sectors = canonicalSectors(row);
        row.put("canonical_sector_ids", sectors.values());
        row.put("sector_scope", sectors.scope());
        provenance.put("sector", sectors.provenance());

        CanonicalValues metrics = canonicalMetrics(row);
        row.put("canonical_metric_intents", metrics.values());
        provenance.put("metric", metrics.provenance());
        row.put("canonical_metadata_provenance", provenance);
        return row;
    }

    private CanonicalValues canonicalGeo(Map<String, Object> row, String source) {
        List<Evidence> all = collectEvidence(row, GEO_KEYS, GEO_COVERAGE_KEYS);
        List<Evidence> fixed = all.stream().filter(e -> e.kind().equals("fixed_field")).toList();
        CanonicalValues explicit = resolveGeoGroup(fixed, "single_country", true);
        if (!explicit.values().isEmpty() || "ambiguous".equals(explicit.scope())) {
            return explicit;
        }

        List<Evidence> dimensions = all.stream().filter(e -> e.kind().equals("dimensions")).toList();
        CanonicalValues dimensional = resolveGeoGroup(dimensions, "single_country", false);
        if (!dimensional.values().isEmpty()) {
            return dimensional;
        }

        List<Evidence> coverage = all.stream().filter(e -> e.kind().equals("coverage_set")).toList();
        CanonicalValues covered = resolveGeoGroup(coverage, "single_country", false);
        if (!covered.values().isEmpty()) {
            return covered;
        }

        String fixedSourceGeo = CatalogGeoIntent.fixedSourceGeoScope(source);
        if (!fixedSourceGeo.isBlank()) {
            return new CanonicalValues(List.of(fixedSourceGeo), "single_country", List.of("fixed_source:" + source));
        }
        return CanonicalValues.unknown();
    }

    private CanonicalValues resolveGeoGroup(List<Evidence> evidence, String singleScope, boolean conflictingPathsAmbiguous) {
        Map<String, Set<String>> byPath = new LinkedHashMap<>();
        List<String> provenance = new ArrayList<>();
        for (Evidence item : evidence) {
            Set<String> resolved = new LinkedHashSet<>();
            for (String value : item.values()) {
                String code = CatalogGeoIntent.resolveTerritoryToCountryCode(value);
                if (!code.isBlank()) {
                    resolved.add(code);
                }
            }
            if (!resolved.isEmpty()) {
                byPath.computeIfAbsent(item.path(), ignored -> new LinkedHashSet<>()).addAll(resolved);
                provenance.add(item.kind() + ":" + item.path());
            }
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        byPath.values().forEach(values::addAll);
        if (conflictingPathsAmbiguous && values.size() > 1 && byPath.size() > 1) {
            return new CanonicalValues(List.of(), "ambiguous", List.copyOf(provenance));
        }
        String scope = values.size() > 1 ? "multi_country" : values.size() == 1 ? singleScope : "unknown";
        return new CanonicalValues(List.copyOf(values), scope, List.copyOf(provenance));
    }

    private CanonicalValues canonicalSectors(Map<String, Object> row) {
        List<Evidence> evidence = collectEvidence(row, SECTOR_KEYS, Set.of());
        List<Evidence> explicit = evidence.stream().filter(e -> e.kind().equals("fixed_field")).toList();
        CanonicalValues fixed = resolveRegistryGroup(explicit, true, sectorRegistry::resolve);
        if (!fixed.values().isEmpty() || "ambiguous".equals(fixed.scope())) {
            return fixed;
        }
        List<Evidence> dimensions = evidence.stream().filter(e -> e.kind().equals("dimensions")).toList();
        CanonicalValues dimensional = resolveRegistryGroup(dimensions, false, sectorRegistry::resolve);
        if (!dimensional.values().isEmpty()) {
            return dimensional;
        }
        String inferred = sectorRegistry.resolve(evidenceText(row));
        return inferred.isBlank()
                ? CanonicalValues.unknown()
                : new CanonicalValues(List.of(inferred), "single", List.of("inference:registry"));
    }

    private CanonicalValues canonicalMetrics(Map<String, Object> row) {
        List<Evidence> evidence = collectEvidence(row, METRIC_KEYS, Set.of());
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        List<String> provenance = new ArrayList<>();
        for (Evidence item : evidence) {
            for (String value : item.values()) {
                String metric = metricRegistry.resolve(value);
                if (!metric.isBlank()) {
                    resolved.add(metric);
                }
            }
            if (!resolved.isEmpty()) {
                provenance.add(item.kind() + ":" + item.path());
            }
        }
        if (!resolved.isEmpty()) {
            return new CanonicalValues(List.copyOf(resolved), resolved.size() > 1 ? "multi" : "single", provenance);
        }
        String inferred = metricRegistry.resolve(evidenceText(row));
        return inferred.isBlank()
                ? CanonicalValues.unknown()
                : new CanonicalValues(List.of(inferred), "single", List.of("inference:registry"));
    }

    private CanonicalValues resolveRegistryGroup(
            List<Evidence> evidence,
            boolean conflictingPathsAmbiguous,
            java.util.function.Function<String, String> resolver) {
        Map<String, Set<String>> byPath = new LinkedHashMap<>();
        List<String> provenance = new ArrayList<>();
        for (Evidence item : evidence) {
            for (String value : item.values()) {
                String resolved = resolver.apply(value);
                if (!resolved.isBlank()) {
                    byPath.computeIfAbsent(item.path(), ignored -> new LinkedHashSet<>()).add(resolved);
                }
            }
            if (byPath.containsKey(item.path())) {
                provenance.add(item.kind() + ":" + item.path());
            }
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        byPath.values().forEach(values::addAll);
        if (conflictingPathsAmbiguous && values.size() > 1 && byPath.size() > 1) {
            return new CanonicalValues(List.of(), "ambiguous", provenance);
        }
        return new CanonicalValues(
                List.copyOf(values), values.size() > 1 ? "multi" : values.size() == 1 ? "single" : "unknown", provenance);
    }

    private static List<Evidence> collectEvidence(
            Map<String, Object> row, Set<String> targetKeys, Set<String> coverageKeys) {
        List<Evidence> out = new ArrayList<>();
        collectEvidence(row, "", 0, targetKeys, coverageKeys, out);
        return out;
    }

    private static void collectEvidence(
            Object node,
            String path,
            int depth,
            Set<String> targetKeys,
            Set<String> coverageKeys,
            List<Evidence> out) {
        if (node == null || depth > 5) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = normalizeKey(entry.getKey());
                String childPath = path.isBlank() ? key : path + "." + key;
                if (targetKeys.contains(key) || coverageKeys.contains(key)) {
                    String kind = coverageKeys.contains(key)
                            ? "coverage_set"
                            : isDimensionPath(path) ? "dimensions" : "fixed_field";
                    List<String> values = scalarValues(entry.getValue());
                    if (!values.isEmpty()) {
                        out.add(new Evidence(kind, childPath, values));
                    }
                }
                if (shouldDescend(key, entry.getValue())) {
                    collectEvidence(entry.getValue(), childPath, depth + 1, targetKeys, coverageKeys, out);
                }
            }
        } else if (node instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) {
                if (index++ >= 100) {
                    break;
                }
                collectEvidence(item, path + "[]", depth + 1, targetKeys, coverageKeys, out);
            }
        }
    }

    private static boolean shouldDescend(String key, Object value) {
        if (!(value instanceof Map<?, ?>) && !(value instanceof Iterable<?>)) {
            return false;
        }
        return Set.of("raw", "preview_payload", "rows", "dimensions", "available_dimensions", "filters", "source")
                .contains(key);
    }

    private static boolean isDimensionPath(String path) {
        return path.contains("dimension") || path.contains("preview_payload.rows") || path.contains("filters");
    }

    private static List<String> scalarValues(Object raw) {
        List<String> out = new ArrayList<>();
        collectScalarValues(raw, out, 0);
        return out.stream().filter(value -> !value.isBlank()).distinct().limit(250).toList();
    }

    private static void collectScalarValues(Object raw, List<String> out, int depth) {
        if (raw == null || depth > 3) {
            return;
        }
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = normalizeKey(entry.getKey());
                if (Set.of("value", "values", "code", "id", "label", "selected", "options").contains(key)) {
                    collectScalarValues(entry.getValue(), out, depth + 1);
                }
            }
            return;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectScalarValues(item, out, depth + 1);
            }
            return;
        }
        if (raw.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(raw); i++) {
                collectScalarValues(Array.get(raw, i), out, depth + 1);
            }
            return;
        }
        String value = CatalogMapSupport.str(raw).trim();
        if (!value.isBlank()) {
            out.add(value);
        }
    }

    private static String evidenceText(Map<String, Object> row) {
        List<String> parts = new ArrayList<>();
        for (String key : List.of("title", "name", "description", "concepts", "tags", "category_path")) {
            parts.addAll(scalarValues(row.get(key)));
        }
        if (row.get("raw") instanceof Map<?, ?> raw) {
            for (String key : List.of("canonical_title_cs", "canonical_title_en", "primary_concept", "concepts", "tags")) {
                parts.addAll(scalarValues(raw.get(key)));
            }
        }
        return String.join(" ", parts);
    }

    private static String firstPresentField(Map<String, Object> row, List<String> keys) {
        for (String key : keys) {
            if (!CatalogMapSupport.str(row.get(key)).isBlank()) {
                return key;
            }
        }
        return "source";
    }

    private static String normalizeKey(Object key) {
        return String.valueOf(key).trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private record Evidence(String kind, String path, List<String> values) {}

    private record CanonicalValues(List<String> values, String scope, List<String> provenance) {
        private static CanonicalValues unknown() {
            return new CanonicalValues(List.of(), "unknown", List.of());
        }
    }
}
