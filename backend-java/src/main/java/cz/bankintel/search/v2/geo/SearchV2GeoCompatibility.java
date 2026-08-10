package cz.bankintel.search.v2.geo;

import cz.bankintel.search.CatalogCountryAliasRegistry;
import cz.bankintel.search.CatalogCountryIso3Registry;
import cz.bankintel.search.CatalogGeoIntent;
import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.v2.schema.ExactEntityResolution;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SearchResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Centralized geo compatibility rules for Search V2.
 *
 * <p>The class keeps the ranking pipeline order explicit: user/request geo first, then fixed entity
 * geo, then candidate geo evidence. It intentionally avoids query-specific branches; all decisions
 * are based on canonical geo aliases, entity metadata, candidate metadata, or source capability.
 */
public final class SearchV2GeoCompatibility {

    public record GeoAssessment(
            String status,
            boolean hardConflict,
            String candidateInferred,
            String sourceScope,
            boolean dimensionSelectable) {}

    private static final Set<String> DIMENSION_SELECTABLE_SOURCES =
            Set.of("eurostat", "ecb2", "bis", "imf", "oecd4", "data360", "worldbank");
    private static final Set<String> EU_AGGREGATE_ALIASES =
            Set.of("eu", "eu27", "eu27 2020", "europe", "evropa", "european union");
    private static final Set<String> EURO_AREA_ALIASES =
            Set.of("u2", "ea", "ea20", "euro area", "eurozone", "euro zona", "eurozona", "eurozony");
    private static final Set<String> GLOBAL_ALIASES = Set.of("global", "world", "svet", "svetovy");
    private static final Set<String> EU_MEMBERS = Set.of(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV",
            "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE");
    private static final Set<String> EURO_AREA_MEMBERS = Set.of(
            "AT", "BE", "HR", "CY", "EE", "FI", "FR", "DE", "GR", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
            "PT", "SK", "SI", "ES");
    private static final Set<String> OECD_MEMBERS = Set.of(
            "AT", "AU", "BE", "CA", "CL", "CO", "CR", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IS",
            "IE", "IL", "IT", "JP", "KR", "LV", "LT", "LU", "MX", "NL", "NZ", "NO", "PL", "PT", "SK", "SI",
            "ES", "SE", "CH", "TR", "GB", "US");

    private static final Map<String, String> STOCK_SUFFIX_GEO = Map.ofEntries(
            Map.entry("PR", "CZ"),
            Map.entry("F", "DE"),
            Map.entry("DE", "DE"),
            Map.entry("DU", "DE"),
            Map.entry("MU", "DE"),
            Map.entry("SG", "DE"),
            Map.entry("PA", "FR"),
            Map.entry("AS", "NL"),
            Map.entry("MI", "IT"),
            Map.entry("MC", "ES"),
            Map.entry("L", "GB"),
            Map.entry("SW", "CH"),
            Map.entry("VI", "AT"),
            Map.entry("WA", "PL"),
            Map.entry("PRG", "CZ"),
            Map.entry("TO", "CA"),
            Map.entry("NE", "CA"),
            Map.entry("SA", "BR"),
            Map.entry("BK", "TH"),
            Map.entry("AX", "AU"));

    private SearchV2GeoCompatibility() {}

    public static List<String> mergeWithEntityFixedGeo(List<String> requestedGeographies, ExactEntityResolution entity) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String geography : requestedGeographies == null ? List.<String>of() : requestedGeographies) {
            String code = normalizeGeoCode(geography);
            if (!code.isBlank()) {
                out.add(code);
            }
        }
        if (out.isEmpty()) {
            String fixedGeo = entityFixedGeo(entity);
            if (!fixedGeo.isBlank()) {
                out.add(fixedGeo);
            }
        }
        return List.copyOf(out);
    }

    public static List<String> membershipsFor(List<String> geographies) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String geography : normalizeGeoList(geographies)) {
            switch (geography) {
                case "EU" -> out.add("EU");
                case "U2" -> out.add("euro_area");
                case "GLOBAL" -> out.add("global");
                default -> {
                    if (EU_MEMBERS.contains(geography)) {
                        out.add("EU");
                    }
                    if (EURO_AREA_MEMBERS.contains(geography)) {
                        out.add("euro_area");
                    }
                    if (OECD_MEMBERS.contains(geography)) {
                        out.add("OECD");
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    public static String entityFixedGeo(ExactEntityResolution entity) {
        if (entity == null || entity.attributes() == null) {
            return "";
        }
        String fixed = CatalogMapSupport.str(entity.attributes().get("fixed_geo"));
        if (fixed.isBlank()) {
            return "";
        }
        String mode = CatalogMapSupport.str(entity.attributes().get("geo_mode")).trim().toLowerCase(Locale.ROOT);
        if (!mode.isBlank() && !"fixed".equals(mode)) {
            return "";
        }
        return normalizeGeoCode(fixed);
    }

    public static String entityMarketGeo(ExactEntityResolution entity) {
        if (entity == null || entity.attributes() == null) {
            return "";
        }
        String market = CatalogMapSupport.str(entity.attributes().get("market"));
        return normalizeGeoCode(market);
    }

    public static boolean candidateMatchesRequestedGeo(
            SearchCandidate candidate,
            List<String> requestedGeographies,
            SearchQueryPlan plan) {
        List<String> requested = normalizeGeoList(requestedGeographies);
        if (candidate == null || requested.isEmpty()) {
            return true;
        }
        if (candidate.geo() != null && !candidate.geo().isBlank()) {
            if (isGlobalDatasetScope(candidate, candidate.geo())) {
                return dimensionSelectableWithoutFixedGeo(candidate, requested);
            }
            return geoMatches(candidate.geo(), requested);
        }
        String rawGeo = rawFirst(candidate, "geo", "country", "ref_area", "territory");
        if (!rawGeo.isBlank()) {
            if (isGlobalDatasetScope(candidate, rawGeo)) {
                return dimensionSelectableWithoutFixedGeo(candidate, requested);
            }
            return geoMatches(rawGeo, requested);
        }
        if (rawIterableMatches(candidate, requested, "geo_tags", "geographies", "countries")) {
            return true;
        }
        String stockGeo = stockSuffixGeo(candidate);
        if (!stockGeo.isBlank()) {
            return geoMatches(stockGeo, requested);
        }
        if (textMentionsRequestedGeo(candidate, requested)) {
            return true;
        }
        String entityFixedGeo = plan == null ? "" : entityFixedGeo(plan.entityResolution());
        if (!entityFixedGeo.isBlank()
                && geoMatches(entityFixedGeo, requested)
                && plan != null
                && plan.highConfidenceExactEntity()
                && entityCandidateMatches(plan.entityResolution(), candidate)) {
            return true;
        }
        return dimensionSelectableWithoutFixedGeo(candidate, requested);
    }

    public static String inferredCandidateGeo(SearchCandidate candidate) {
        if (candidate == null) {
            return "";
        }
        String direct = normalizeGeoCode(candidate.geo());
        if (!direct.isBlank()) {
            if (isGlobalDatasetScope(candidate, direct)) {
                return "";
            }
            return direct;
        }
        String raw = normalizeGeoCode(rawFirst(candidate, "geo", "country", "ref_area", "territory"));
        if (!raw.isBlank()) {
            if (isGlobalDatasetScope(candidate, raw)) {
                return "";
            }
            return raw;
        }
        String stock = stockSuffixGeo(candidate);
        if (!stock.isBlank()) {
            return stock;
        }
        return "";
    }

    /**
     * Produces structured evidence for the semantic validator without removing candidates.
     * A hard conflict is emitted only for an explicit candidate or fixed source geography.
     */
    public static GeoAssessment assessCandidateGeo(
            SearchCandidate candidate,
            List<String> requestedGeographies,
            SearchQueryPlan plan) {
        List<String> requested = normalizeGeoList(requestedGeographies);
        String inferred = inferredCandidateGeo(candidate);
        String sourceScope = candidate == null ? "" : CatalogGeoIntent.fixedSourceGeoScope(candidate.source());
        boolean selectable = candidate != null && dimensionSelectableWithoutFixedGeo(candidate, requested);
        if (requested.isEmpty()) {
            return new GeoAssessment("not_requested", false, inferred, sourceScope, selectable);
        }
        if (candidateMatchesRequestedGeo(candidate, requested, plan)) {
            return new GeoAssessment("compatible", false, inferred, sourceScope, selectable);
        }
        if (!inferred.isBlank()) {
            return new GeoAssessment("explicit_conflict", true, inferred, sourceScope, selectable);
        }
        String fixedSourceGeo = normalizeGeoCode(sourceScope);
        if (!fixedSourceGeo.isBlank() && !geoMatches(fixedSourceGeo, requested)) {
            return new GeoAssessment("source_scope_conflict", true, fixedSourceGeo, sourceScope, selectable);
        }
        return new GeoAssessment("unknown", false, inferred, sourceScope, selectable);
    }

    public static Map<String, Object> geoTrace(SearchQueryPlan plan, List<SearchResult> finalResults) {
        List<String> planGeographies = normalizeGeoList(plan == null ? List.of() : plan.geographies());
        List<String> entityFixed = List.of();
        String fixedGeo = plan == null ? "" : entityFixedGeo(plan.entityResolution());
        if (!fixedGeo.isBlank()) {
            entityFixed = List.of(fixedGeo);
        }
        List<String> primaryGeos = (finalResults == null ? List.<SearchResult>of() : finalResults).stream()
                .filter(result -> "primary".equals(result.role()))
                .map(result -> traceGeoForResult(result, plan, planGeographies, fixedGeo))
                .map(value -> value.isBlank() ? "unknown" : value)
                .distinct()
                .limit(8)
                .toList();
        boolean exactFixedEntitySatisfied = !fixedGeo.isBlank() && geoMatches(fixedGeo, planGeographies);
        boolean satisfied = planGeographies.isEmpty()
                || exactFixedEntitySatisfied
                || (finalResults != null
                        && finalResults.stream()
                                .filter(result -> "primary".equals(result.role()))
                                .anyMatch(result -> candidateMatchesRequestedGeo(
                                        result.candidate(), planGeographies, plan)));
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("explicit_user_geo", planGeographies);
        trace.put("normalized_geo", planGeographies);
        trace.put("entity_resolver_geo", entityFixed);
        trace.put("query_plan_geo", planGeographies);
        trace.put("retrieval_geo", planGeographies);
        trace.put("preview_geo", planGeographies);
        trace.put("final_primary_geos", primaryGeos);
        trace.put("geo_constraint_satisfied", satisfied);
        return trace;
    }

    private static String traceGeoForResult(
            SearchResult result, SearchQueryPlan plan, List<String> planGeographies, String fixedGeo) {
        String direct = inferredCandidateGeo(result.candidate());
        if (!direct.isBlank()) {
            return direct;
        }
        if (!fixedGeo.isBlank() && geoMatches(fixedGeo, planGeographies)) {
            return fixedGeo;
        }
        if (!planGeographies.isEmpty()
                && candidateMatchesRequestedGeo(result.candidate(), planGeographies, plan)) {
            return planGeographies.getFirst();
        }
        return "";
    }

    public static boolean geoMatches(String candidateGeo, List<String> requestedGeographies) {
        String candidateCode = normalizeGeoCode(candidateGeo);
        String candidateFolded = CatalogTextUtils.foldAscii(candidateGeo == null ? "" : candidateGeo);
        for (String requested : normalizeGeoList(requestedGeographies)) {
            if (!candidateCode.isBlank() && candidateCode.equals(requested)) {
                return true;
            }
            for (String alias : CatalogCountryAliasRegistry.foldedAliasMatchTerms(requested)) {
                if (candidateFolded.equals(alias) || foldedTokenMatch(candidateFolded, alias)) {
                    return true;
                }
            }
        }
        if (hasAggregateRequest(requestedGeographies) && aggregateTextMatches(candidateGeo, requestedGeographies)) {
            return true;
        }
        return false;
    }

    private static boolean dimensionSelectableWithoutFixedGeo(SearchCandidate candidate, List<String> requestedGeographies) {
        String source = candidate.source() == null ? "" : candidate.source().trim().toLowerCase(Locale.ROOT);
        if (!DIMENSION_SELECTABLE_SOURCES.contains(source)) {
            return false;
        }
        if (rawHasExplicitSupportedGeographies(candidate)) {
            if (rawIterableMatches(
                    candidate,
                    requestedGeographies,
                    "supported_geographies",
                    "available_geographies",
                    "geo_dimension_values")) {
                return true;
            }
            return geoMatchesAny(geoCoverageSample(candidate), requestedGeographies);
        }
        if (Set.of("data360", "worldbank").contains(source)) {
            String family = rawFirst(candidate, "catalog_family").toLowerCase(Locale.ROOT);
            if (!Set.of("macro", "banking", "real_estate", "sectoral", "commodities", "markets_equities").contains(family)) {
                return false;
            }
        }
        String dataset = candidate.dataset() == null ? "" : candidate.dataset().trim();
        String seriesId = candidate.seriesId() == null ? "" : candidate.seriesId().trim();
        return !dataset.isBlank() || !seriesId.isBlank();
    }

    private static boolean isGlobalDatasetScope(SearchCandidate candidate, String geo) {
        if (!"GLOBAL".equals(normalizeGeoCode(geo)) || candidate == null) {
            return false;
        }
        String source = candidate.source() == null ? "" : candidate.source().trim().toLowerCase(Locale.ROOT);
        return DIMENSION_SELECTABLE_SOURCES.contains(source);
    }

    private static boolean rawHasExplicitSupportedGeographies(SearchCandidate candidate) {
        if (candidate == null || candidate.raw() == null) {
            return false;
        }
        return candidate.raw().containsKey("supported_geographies")
                || candidate.raw().containsKey("available_geographies")
                || candidate.raw().containsKey("geo_dimension_values")
                // data360's per-row country coverage, computed at mirror time from the countries
                // actually fetched for that indicator (see the Data360 geo-propagation fix) - not a
                // hardcoded per-source rule, just one more field name this already-generic mechanism
                // recognizes. A row with a non-empty list here genuinely has that per-country data,
                // regardless of source; other sources are unaffected since they never populate it.
                || nonEmptyIterable(geoCoverageSample(candidate));
    }

    private static boolean nonEmptyIterable(Object value) {
        if (value instanceof Iterable<?> iterable) {
            return iterable.iterator().hasNext();
        }
        return false;
    }

    private static boolean geoMatchesAny(Object iterableValue, List<String> requestedGeographies) {
        if (!(iterableValue instanceof Iterable<?> iterable)) {
            return false;
        }
        for (Object item : iterable) {
            if (item != null && geoMatches(String.valueOf(item), requestedGeographies)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code geo_coverage_sample} sits at the top level of {@code candidate.raw()} for a candidate
     * that came from {@code CatalogIndexStore}'s raw FTS path, but the SIDECAR retrieval path nests
     * the original mirrored row one level deeper under a {@code "raw"} key - {@code
     * SearchCatalogSidecarDocument#toSearchRow} only promotes a small curated set of fields to the
     * top level (see that class's javadoc). Checking both keeps this working regardless of which
     * retrieval lane produced the candidate - see {@code SearchV2CandidateNormalizer}'s identical
     * dual-location lookup for the single-country case.
     */
    private static Object geoCoverageSample(SearchCandidate candidate) {
        if (candidate == null || candidate.raw() == null) {
            return null;
        }
        Object direct = candidate.raw().get("geo_coverage_sample");
        if (direct != null) {
            return direct;
        }
        if (candidate.raw().get("raw") instanceof Map<?, ?> nestedRaw) {
            return nestedRaw.get("geo_coverage_sample");
        }
        return null;
    }

    private static boolean rawIterableMatches(SearchCandidate candidate, List<String> requestedGeographies, String... keys) {
        if (candidate == null || candidate.raw() == null) {
            return false;
        }
        for (String key : keys) {
            Object raw = candidate.raw().get(key);
            if (raw instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    if (item != null && geoMatches(String.valueOf(item), requestedGeographies)) {
                        return true;
                    }
                }
            } else if (raw != null && geoMatches(String.valueOf(raw), requestedGeographies)) {
                return true;
            }
        }
        return false;
    }

    private static boolean textMentionsRequestedGeo(SearchCandidate candidate, List<String> requestedGeographies) {
        String folded = CatalogTextUtils.foldAscii(String.join(
                " ",
                safe(candidate.seriesId()),
                safe(candidate.dataset()),
                safe(candidate.title()),
                safe(candidate.description()),
                rawFirst(candidate, "canonical_title_en", "canonical_title_cs", "original_title")));
        if (hasAggregateRequest(requestedGeographies) && aggregateTextMatches(folded, requestedGeographies)) {
            return true;
        }
        for (String requested : requestedGeographies) {
            for (String alias : CatalogCountryAliasRegistry.foldedAliasMatchTerms(requested)) {
                if (alias.length() >= 2 && foldedTokenMatch(folded, alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String stockSuffixGeo(SearchCandidate candidate) {
        String source = candidate.source() == null ? "" : candidate.source().trim().toLowerCase(Locale.ROOT);
        if (!"stocks".equals(source)) {
            return "";
        }
        for (String identifier : List.of(candidate.seriesId(), candidate.dataset())) {
            String value = identifier == null ? "" : identifier.trim().toUpperCase(Locale.ROOT);
            int dot = value.lastIndexOf('.');
            if (dot > 0 && dot < value.length() - 1) {
                String suffix = value.substring(dot + 1);
                String geo = STOCK_SUFFIX_GEO.get(suffix);
                if (geo != null) {
                    return geo;
                }
            }
        }
        return "";
    }

    private static List<String> normalizeGeoList(List<String> geographies) {
        List<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String geography : geographies == null ? List.<String>of() : geographies) {
            String code = normalizeGeoCode(geography);
            if (!code.isBlank() && seen.add(code)) {
                out.add(code);
            }
        }
        return out;
    }

    private static String normalizeGeoCode(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (Set.of("EU", "EU27", "EU27_2020", "EUROPE").contains(value)) {
            return "EU";
        }
        if (Set.of("EA", "EA20", "EURO_AREA", "EUROZONE", "U2").contains(value)) {
            return "U2";
        }
        if ("GLOBAL".equals(value)) {
            return "GLOBAL";
        }
        if (value.length() == 3 && CatalogCountryIso3Registry.isKnownIso3(value)) {
            return CatalogCountryIso3Registry.iso2For(value);
        }
        if (CatalogCountryAliasRegistry.hasCode(value)) {
            return value;
        }
        String folded = CatalogTextUtils.foldAscii(raw == null ? "" : raw).replace('_', ' ').trim();
        if (EU_AGGREGATE_ALIASES.contains(folded)) {
            return "EU";
        }
        if (EURO_AREA_ALIASES.contains(folded)) {
            return "U2";
        }
        if (GLOBAL_ALIASES.contains(folded)) {
            return "GLOBAL";
        }
        for (Map.Entry<String, List<String>> entry : CatalogCountryAliasRegistry.aliasesByCode().entrySet()) {
            for (String alias : entry.getValue()) {
                String cleanAlias = alias == null ? "" : alias.replace("*", "");
                if (!cleanAlias.isBlank() && folded.equals(CatalogTextUtils.foldAscii(cleanAlias))) {
                    return entry.getKey();
                }
            }
        }
        return "";
    }

    private static boolean aggregateTextMatches(String text, List<String> requestedGeographies) {
        List<String> requested = normalizeGeoList(requestedGeographies);
        String folded = CatalogTextUtils.foldAscii(text == null ? "" : text).replace('_', ' ').trim();
        if (requested.contains("EU") && anyFoldedTokenMatch(folded, EU_AGGREGATE_ALIASES)) {
            return true;
        }
        if (requested.contains("U2") && anyFoldedTokenMatch(folded, EURO_AREA_ALIASES)) {
            return true;
        }
        return requested.contains("GLOBAL") && anyFoldedTokenMatch(folded, GLOBAL_ALIASES);
    }

    private static boolean entityCandidateMatches(ExactEntityResolution entity, SearchCandidate candidate) {
        if (entity == null || candidate == null || !"exact_entity".equals(entity.resolutionType())) {
            return false;
        }
        String series = CatalogTextUtils.foldAscii(safe(candidate.seriesId())).trim();
        String dataset = CatalogTextUtils.foldAscii(safe(candidate.dataset())).trim();
        String haystack = CatalogTextUtils.foldAscii(String.join(
                " ",
                safe(candidate.seriesId()),
                safe(candidate.dataset()),
                safe(candidate.title()),
                safe(candidate.description()),
                rawFirst(candidate, "canonical_title_en", "canonical_title_cs", "original_title", "abbreviations")));
        for (String identifier : entityIdentifiers(entity)) {
            String folded = CatalogTextUtils.foldAscii(identifier).trim();
            if (folded.isBlank()) {
                continue;
            }
            if (series.equals(folded) || dataset.equals(folded) || foldedTokenMatch(haystack, folded)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> entityIdentifiers(ExactEntityResolution entity) {
        List<String> out = new ArrayList<>();
        if (entity == null) {
            return out;
        }
        if (entity.canonicalName() != null) {
            out.add(entity.canonicalName());
        }
        if (entity.symbols() != null) {
            out.addAll(entity.symbols());
        }
        if (entity.exactTerms() != null) {
            out.addAll(entity.exactTerms());
        }
        return out;
    }

    private static boolean anyFoldedTokenMatch(String haystack, Set<String> aliases) {
        for (String alias : aliases) {
            if (foldedTokenMatch(haystack, alias)) {
                return true;
            }
        }
        return false;
    }

    private static boolean foldedTokenMatch(String haystack, String needle) {
        String foldedHaystack = CatalogTextUtils.foldAscii(haystack == null ? "" : haystack).replace('_', ' ').trim();
        String foldedNeedle = CatalogTextUtils.foldAscii(needle == null ? "" : needle).replace('_', ' ').trim();
        if (foldedHaystack.isBlank() || foldedNeedle.isBlank()) {
            return false;
        }
        if ((" " + foldedHaystack + " ").contains(" " + foldedNeedle + " ")) {
            return true;
        }
        if (foldedNeedle.length() < 4 || foldedNeedle.contains(" ")) {
            return false;
        }
        for (String token : foldedHaystack.split("[^a-z0-9]+")) {
            if (token.startsWith(foldedNeedle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAggregateRequest(List<String> requestedGeographies) {
        for (String requested : normalizeGeoList(requestedGeographies)) {
            if (Set.of("EU", "U2", "GLOBAL").contains(requested)) {
                return true;
            }
        }
        return false;
    }

    private static String rawFirst(SearchCandidate candidate, String... keys) {
        if (candidate == null || candidate.raw() == null) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            Object value = candidate.raw().get(key);
            if (value instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    String text = CatalogMapSupport.str(item);
                    if (!text.isBlank()) {
                        out.add(text);
                    }
                }
            } else {
                String text = CatalogMapSupport.str(value);
                if (!text.isBlank()) {
                    out.add(text);
                }
            }
        }
        return String.join(" ", out).trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
