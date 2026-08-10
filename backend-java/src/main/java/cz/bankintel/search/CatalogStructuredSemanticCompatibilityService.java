package cz.bankintel.search;

import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.v2.normalization.SearchResultCanonicalMetadataService;
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
 * Compares a candidate with the immutable structured part of a Search V2 plan. Only canonical,
 * evidenced metadata can prove a conflict; missing metadata remains unknown.
 */
@Service
@RequiredArgsConstructor
public class CatalogStructuredSemanticCompatibilityService {

    private final SearchResultCanonicalMetadataService canonicalMetadataService;

    public Map<String, Object> evaluate(Map<String, Object> candidate, Map<String, Object> semanticProfile) {
        Map<String, Object> row = canonicalMetadataService.enrich(candidate);
        Map<String, Object> evidence = new LinkedHashMap<>();

        Compatibility geo = compare(
                values(semanticProfile, CatalogKeys.REQUIRED_GEO_CODES),
                values(row, "canonical_geo_codes"));
        Compatibility sector = compare(
                values(semanticProfile, CatalogKeys.INSTITUTIONAL_SECTORS),
                values(row, "canonical_sector_ids"));
        Compatibility metric = compare(
                values(semanticProfile, CatalogKeys.METRIC_INTENTS),
                values(row, "canonical_metric_intents"));

        evidence.put("geo", geo.toMap());
        evidence.put("sector", sector.toMap());
        evidence.put("metric", metric.toMap());
        List<Compatibility> dimensions = List.of(geo, sector, metric);
        String status;
        if (dimensions.stream().anyMatch(Compatibility::mismatch)) {
            status = "mismatch";
        } else {
            List<Compatibility> requiredDimensions = dimensions.stream()
                    .filter(Compatibility::hasRequirement)
                    .toList();
            if (requiredDimensions.isEmpty()) {
                status = "unknown";
            } else if (requiredDimensions.stream().allMatch(Compatibility::match)) {
                status = "match";
            } else if (requiredDimensions.stream().anyMatch(Compatibility::match)) {
                status = "partial";
            } else {
                status = "unknown";
            }
        }
        row.put(CatalogKeys.STRUCTURED_SEMANTIC_STATUS, status);
        row.put(CatalogKeys.STRUCTURED_SEMANTIC_EVIDENCE, evidence);
        return row;
    }

    private static Compatibility compare(Set<String> required, Set<String> observed) {
        if (required.isEmpty() || observed.isEmpty()) {
            return new Compatibility(required, observed, "unknown");
        }
        boolean intersects = required.stream().anyMatch(observed::contains);
        return new Compatibility(required, observed, intersects ? "match" : "mismatch");
    }

    private static Set<String> values(Map<String, Object> row, String key) {
        if (row == null) {
            return Set.of();
        }
        Object raw = row.get(key);
        List<String> out = new ArrayList<>();
        if (raw instanceof Collection<?> values) {
            for (Object value : values) {
                add(out, value);
            }
        } else {
            add(out, raw);
        }
        return new LinkedHashSet<>(out);
    }

    private static void add(List<String> out, Object raw) {
        String value = CatalogMapSupport.str(raw).trim().toLowerCase(Locale.ROOT);
        if (!value.isBlank()) {
            out.add(value);
        }
    }

    private record Compatibility(Set<String> required, Set<String> observed, String status) {
        boolean hasRequirement() {
            return !required.isEmpty();
        }

        boolean match() {
            return "match".equals(status);
        }

        boolean mismatch() {
            return "mismatch".equals(status);
        }

        Map<String, Object> toMap() {
            return Map.of("required", required, "observed", observed, "status", status);
        }
    }
}
