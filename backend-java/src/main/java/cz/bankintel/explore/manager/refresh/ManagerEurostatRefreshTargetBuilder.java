package cz.bankintel.explore.manager.refresh;

import cz.bankintel.sources.eurostat.EurostatDimensionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns curated Eurostat bundle rows (from {@link ManagerSegmentBundleLoader}) into concrete
 * per-country fetch targets. Each row's own {@code query_params} is already a fully-resolved
 * Eurostat dimension spec (nace_r2/indic_bt/s_adj/unit/freq) hand-verified once by an offline
 * discovery/audit process - the common case here is a pure clone-and-override-geo, never a
 * live probe. {@link EurostatDimensionService#resolvePreviewQueryParams} is invoked only as a
 * defensive fallback for a row that's missing dimensions entirely (0 of 432 current Eurostat
 * rows hit this path, but a bundle file could change without Java's awareness - see {@link
 * ManagerSegmentBundleLoader}).
 */
@Service
@RequiredArgsConstructor
public class ManagerEurostatRefreshTargetBuilder {

    private static final Logger log = LoggerFactory.getLogger(ManagerEurostatRefreshTargetBuilder.class);

    private final EurostatDimensionService eurostatDimensionService;

    /** One resolved (series, geo) fetch target. {@code row} is the original curated bundle row
     * (title/unit/frequency/segment_id/manager_category/... all read from it by the doc
     * builder); {@code queryParams} is the row's own dimensions with {@code geo} overwritten. */
    public record RefreshTarget(Map<String, Object> row, String geo, Map<String, Object> queryParams) {

        public String segmentId() {
            return str(row.get("segment_id"));
        }

        public String seriesId() {
            return str(row.get("series_id"));
        }

        public String datasetId() {
            return str(row.get("dataset_id"));
        }
    }

    /** Builds every (row, geo) target for {@code rows}, restricted to {@code requestedGeos ∩
     * row.geo_coverage} per row - never a blind "every requested geo for every row" loop,
     * since {@code geo_coverage} is the pre-verified scope for that specific series (average
     * ~20 countries, not all 27). */
    public List<RefreshTarget> buildTargets(List<Map<String, Object>> rows, Set<String> requestedGeos) {
        List<RefreshTarget> out = new ArrayList<>();
        if (rows == null || rows.isEmpty() || requestedGeos == null || requestedGeos.isEmpty()) {
            return out;
        }
        Set<String> wantedGeos = upper(requestedGeos);
        for (Map<String, Object> row : rows) {
            for (String geo : geosForRow(row, wantedGeos)) {
                Map<String, Object> queryParams = resolveQueryParams(row, geo);
                if (queryParams == null || queryParams.isEmpty()) {
                    log.debug(
                            "skipping refresh target with no resolvable query_params: series_id={} geo={}",
                            row.get("series_id"),
                            geo);
                    continue;
                }
                out.add(new RefreshTarget(row, geo, queryParams));
            }
        }
        return out;
    }

    private static Set<String> geosForRow(Map<String, Object> row, Set<String> wantedGeos) {
        Set<String> coverage = upper(stringList(row.get("geo_coverage")));
        if (coverage.isEmpty()) {
            return Set.of();
        }
        return wantedGeos.stream().filter(coverage::contains).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveQueryParams(Map<String, Object> row, String geo) {
        Object rawQp = row.get("query_params");
        Map<String, Object> curated = rawQp instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        if (!curated.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>(curated);
            out.put("geo", geo);
            return out;
        }
        // Defensive fallback - see class javadoc. Not expected to fire for any currently-curated row.
        log.warn(
                "manager segment bundle row missing query_params, falling back to live dimension resolution: series_id={} dataset_id={} geo={}",
                row.get("series_id"),
                row.get("dataset_id"),
                geo);
        Map<String, Object> resolved = eurostatDimensionService.resolvePreviewQueryParams(str(row.get("dataset_id")), geo);
        if (resolved.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>(resolved);
        out.put("geo", geo);
        return out;
    }

    private static Set<String> upper(java.util.Collection<String> values) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String value : values) {
            String v = str(value).toUpperCase(Locale.ROOT);
            if (!v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
