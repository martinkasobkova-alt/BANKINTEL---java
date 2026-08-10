package cz.bankintel.search.v2.normalization;

import cz.bankintel.search.AradSeriesIdentity;
import cz.bankintel.search.CatalogGeoIntent;
import cz.bankintel.search.CatalogSourceRegistry;
import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.v2.schema.SearchCandidate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SearchV2CandidateNormalizer {

    public SearchCandidate normalize(String source, Map<String, Object> row, String matchedQuery) {
        String src = CatalogSourceRegistry.normalizeSearchSource(
                CatalogMapSupport.firstNonBlank(row.get("source_type"), row.get("source"), source));
        String seriesId = seriesId(src, row);
        String title = CatalogTextUtils.rowTitle(row);
        if (title.isBlank()) {
            title = seriesId;
        }
        String dataset = CatalogMapSupport.firstNonBlank(
                row.get("dataset"), row.get("dataset_id"), row.get("flow"), row.get("table"), row.get("category"));
        String description = CatalogMapSupport.firstNonBlank(
                row.get("description"),
                row.get("full_path"),
                row.get("tree_path"),
                row.get("human_label_cs"),
                row.get("human_label_en"));
        List<String> path = splitPath(CatalogMapSupport.firstNonBlank(row.get("full_path"), row.get("tree_path"), row.get("path")));
        double ftsScore = CatalogMapSupport.toDouble(row.get("_fts_rank"));
        Map<String, Object> raw = new LinkedHashMap<>(row);
        return new SearchCandidate(
                src + ":" + seriesId.toLowerCase(Locale.ROOT),
                seriesId,
                title,
                description,
                src,
                dataset,
                geo(src, row),
                CatalogMapSupport.firstNonBlank(row.get("frequency"), row.get("freq"), row.get("FREQ")),
                CatalogMapSupport.firstNonBlank(row.get("unit"), row.get("unit_label"), row.get("UNIT_MEASURE")),
                CatalogMapSupport.firstNonBlank(row.get("seasonal_adjustment"), row.get("adjustment"), row.get("s_adj")),
                stringList(row.get("concepts")),
                stringList(row.get("tags")),
                path,
                CatalogMapSupport.firstNonBlank(row.get("latest_date"), row.get("last_date"), row.get("time_period")),
                ftsScore,
                CatalogMapSupport.firstNonBlank(row.get("_matched_query"), matchedQuery),
                List.of(),
                raw);
    }

    private static String seriesId(String source, Map<String, Object> row) {
        if ("arad".equals(source)) {
            String composite = AradSeriesIdentity.fromRow(row);
            if (!composite.isBlank()) {
                return composite;
            }
        }
        return CatalogMapSupport.firstNonBlank(row.get("set_id"), row.get("id"), row.get("series_id"), row.get("key"));
    }

    private static String geo(String source, Map<String, Object> row) {
        String fixedSourceGeo = CatalogGeoIntent.fixedSourceGeoScope(source);
        if (!fixedSourceGeo.isBlank()) {
            return fixedSourceGeo;
        }
        String extracted = CatalogGeoIntent.extractRowCountryCode(row);
        if (!extracted.isBlank()) {
            return extracted;
        }
        String explicit = CatalogMapSupport.firstNonBlank(
                row.get("geo"),
                row.get("geo_code"),
                row.get("geo_label"),
                row.get("country"),
                row.get("REF_AREA"),
                row.get("ref_area"));
        String resolvedExplicit = CatalogGeoIntent.resolveTerritoryToCountryCode(explicit);
        if (!resolvedExplicit.isBlank()) {
            return resolvedExplicit;
        }
        String singleCoverageCountry = singleGeoCoverageSampleCountry(row);
        if (!singleCoverageCountry.isBlank()) {
            return singleCoverageCountry;
        }
        return "";
    }

    /**
     * {@code geo_coverage_sample} (currently populated by data360's mirror - see the Data360
     * geo-propagation fix) is the row's actual per-country data coverage, computed from the countries
     * genuinely fetched for that indicator - unlike {@code territory}, which is often a generic
     * placeholder like "GLOBAL" for a series that in fact only has data for a handful of countries.
     * When it lists exactly one country, that country IS the row's geo, not just a hint - safe to use
     * directly. A multi-country list is intentionally NOT collapsed to one value here: that would
     * misrepresent a genuinely multi-country series as single-country. The multi-country case is
     * instead handled as a coverage-set fallback in {@code SearchV2GeoCompatibility} (matching "is the
     * requested country somewhere in this row's coverage", not "is it THE row's country").
     */
    private static String singleGeoCoverageSampleCountry(Map<String, Object> row) {
        Object raw = geoCoverageSample(row);
        if (!(raw instanceof Iterable<?> iterable)) {
            return "";
        }
        String only = null;
        for (Object item : iterable) {
            String text = CatalogMapSupport.str(item).trim();
            if (text.isBlank()) {
                continue;
            }
            if (only != null && !only.equalsIgnoreCase(text)) {
                return "";
            }
            only = text;
        }
        return only == null ? "" : CatalogGeoIntent.resolveTerritoryToCountryCode(only);
    }

    /**
     * {@code geo_coverage_sample} lives at the top level of a row coming straight from
     * {@code CatalogIndexStore}'s raw FTS path, but the SIDECAR retrieval path
     * ({@code SearchCatalogSidecarIndex}/{@code SearchCatalogSidecarDocument#toSearchRow}) nests the
     * original mirrored row one level deeper, under a {@code "raw"} key - {@code
     * SearchCatalogSidecarDocument} only promotes a small curated set of fields to the top level.
     * Checking both keeps this working regardless of which retrieval lane produced the candidate.
     */
    private static Object geoCoverageSample(Map<String, Object> row) {
        Object direct = row.get("geo_coverage_sample");
        if (direct != null) {
            return direct;
        }
        if (row.get("raw") instanceof Map<?, ?> nestedRaw) {
            return nestedRaw.get("geo_coverage_sample");
        }
        return null;
    }

    private static List<String> splitPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[>/|•]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .limit(12)
                .toList();
    }

    private static List<String> stringList(Object raw) {
        if (raw instanceof Iterable<?> iterable) {
            List<String> out = new ArrayList<>();
            for (Object item : iterable) {
                String text = CatalogMapSupport.str(item);
                if (!text.isBlank()) {
                    out.add(text);
                }
            }
            return out.stream().distinct().limit(20).toList();
        }
        String text = CatalogMapSupport.str(raw);
        if (text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("[,;|]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .limit(20)
                .toList();
    }
}
