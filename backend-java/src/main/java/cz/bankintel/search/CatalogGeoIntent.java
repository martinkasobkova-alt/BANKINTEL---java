package cz.bankintel.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.model.CatalogKeys;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Geo intent detection for catalog search — port of basics from
 * {@code Bankoapp-main/backend/services/catalog_geo_intent.py}.
 *
 * <p>European ISO2 codes and EU/global aggregate terms are loaded from
 * {@code catalog/geo_scopes.json} at startup (same pattern as {@link CatalogQueryIntent}'s
 * {@code intent_groups.json}) so no per-country literals are hardcoded here.
 */
public final class CatalogGeoIntent {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final GeoScopes SCOPES = loadGeoScopes();

    public static final Set<String> EUROPEAN_COUNTRY_CODES = SCOPES.europeanIso2();

    public static final Set<String> EU_AGGREGATE_GEO_CODES =
            Set.of("EU", "EA", "EUROPE", "U2", "EU27_2020", "EU27", "EA20", "EA19");

    /** Country aliases loaded from {@code catalog/world_country_aliases.json} at startup. */
    public static Map<String, List<String>> countryAliases() {
        return CatalogCountryAliasRegistry.aliasesByCode();
    }

    private static final List<String> EU_AGGREGATE_TERMS = SCOPES.euAggregateTerms();

    private static final List<String> GLOBAL_AGGREGATE_TERMS = SCOPES.globalAggregateTerms();

    private static final Set<String> AMBIGUOUS_AGGREGATE_WORDS =
            Set.of("european", "europe", "evropa", "evrope", "evrop");

    private static final Pattern ISO_TOKEN = Pattern.compile("\\b[A-Z]{2,3}\\b");

    // Perf fix (geo-detection regex-compilation storm - see detectedCountryCodes()/matchAlias() javadocs):
    // EU_AGGREGATE_TERMS/GLOBAL_AGGREGATE_TERMS are matched via the exact same per-call
    // Pattern.compile(...) as country aliases were. Precompiled once here, in list order, so
    // hasEuAggregate/hasGlobalAggregate below no longer compile a fresh Pattern per term per call.
    private static final List<Pattern> EU_AGGREGATE_PATTERNS = compileTermPatterns(EU_AGGREGATE_TERMS);
    private static final List<Pattern> GLOBAL_AGGREGATE_PATTERNS = compileTermPatterns(GLOBAL_AGGREGATE_TERMS);

    // Perf fix: stemMatchedCountryCodes() used to rebuild this whole index (619 aliases, each through
    // CzTextStemmer.stemTokens - a Normalizer.normalize call per alias) on EVERY detectedCountryCodes()
    // call, i.e. once per candidate per request, despite depending on nothing but the static alias
    // registry. Built once here instead; semantics of countryStemIndex() itself are untouched.
    private static final Map<String, List<StemEntry>> COUNTRY_STEM_INDEX = buildCountryStemIndex();

    // Test-only instrumentation (perf investigation only, not an operational metric).
    private static final java.util.concurrent.atomic.AtomicLong DETECT_GEO_INTENT_CALL_COUNT =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong MATCHER_EXECUTION_COUNT =
            new java.util.concurrent.atomic.AtomicLong();

    private CatalogGeoIntent() {}

    public static long detectGeoIntentCallCountForTest() {
        return DETECT_GEO_INTENT_CALL_COUNT.get();
    }

    public static long matcherExecutionCountForTest() {
        return MATCHER_EXECUTION_COUNT.get();
    }

    /** Test-only: raw EU aggregate terms loaded from geo_scopes.json (for oracle/equivalence tests). */
    public static List<String> euAggregateTermsForTest() {
        return EU_AGGREGATE_TERMS;
    }

    /** Test-only: raw global aggregate terms loaded from geo_scopes.json (for oracle/equivalence tests). */
    public static List<String> globalAggregateTermsForTest() {
        return GLOBAL_AGGREGATE_TERMS;
    }

    /** Port of {@code detect_geo_intent} (simplified, no scope resolver enrichment). */
    public static Map<String, Object> detectGeoIntent(String query) {
        DETECT_GEO_INTENT_CALL_COUNT.incrementAndGet();
        String normalizedQuery = normalizeGeoQueryText(query);
        DetectedCountries detected = detectedCountryCodes(query, normalizedQuery);
        List<String> matchedCodes = detected.codes();
        List<String> matchedTerms = detected.terms();

        boolean hasEuAggregate = matchesAnyPattern(EU_AGGREGATE_PATTERNS, normalizedQuery);
        boolean hasGlobalAggregate = matchesAnyPattern(GLOBAL_AGGREGATE_PATTERNS, normalizedQuery);
        boolean hasComparativeMarker = normalizedQuery.contains(",")
                || normalizedQuery.contains(" vs ")
                || normalizedQuery.contains(" versus ")
                || normalizedQuery.contains(" oproti ")
                || normalizedQuery.contains(" compare ");

        List<String> dedupCodes = dedupUpper(matchedCodes);

        String geoType = "unknown";
        String countryCode = null;
        List<String> countryCodes = List.of();
        boolean comparative = false;
        double confidence = 0.0;
        String regionScope = null;

        if (hasEuAggregate && !dedupCodes.isEmpty()) {
            geoType = "mixed_geo_compare";
            countryCode = dedupCodes.get(0);
            countryCodes = dedupCodes;
            comparative = true;
            confidence = hasComparativeMarker ? 0.94 : 0.9;
        } else if (dedupCodes.size() >= 2) {
            geoType = "multi_country";
            countryCodes = dedupCodes;
            comparative = true;
            confidence = hasComparativeMarker ? 0.94 : 0.9;
        } else if (dedupCodes.size() == 1) {
            geoType = "country";
            countryCode = dedupCodes.get(0);
            countryCodes = List.of(countryCode);
            confidence = 0.88;
        } else if (hasEuAggregate) {
            geoType = "eu_aggregate";
            confidence = 0.86;
            regionScope = resolveEuAggregateScope(normalizedQuery);
        } else if (hasGlobalAggregate) {
            geoType = "global_aggregate";
            confidence = 0.84;
            regionScope = "GLOBAL";
        }

        if (hasComparativeMarker && dedupCodes.size() >= 2) {
            comparative = true;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", geoType);
        result.put("country_code", countryCode);
        result.put("country_codes", countryCodes);
        result.put("region_code", null);
        result.put("region_scope", regionScope);
        result.put("comparative", comparative);
        result.put("confidence", Math.round(confidence * 1000.0) / 1000.0);
        result.put("matched_terms", dedupStrings(matchedTerms));
        result.put("normalized_query", normalizedQuery);
        return result;
    }

    /** Port of {@code requested_geo_codes}. */
    @SuppressWarnings("unchecked")
    public static List<String> requestedGeoCodes(Map<String, Object> geoIntent) {
        Map<String, Object> geo = geoIntent == null ? Map.of() : geoIntent;
        String geoType = String.valueOf(geo.getOrDefault("type", "unknown")).strip().toLowerCase(Locale.ROOT);
        if ("country".equals(geoType)) {
            String cc = String.valueOf(geo.getOrDefault("country_code", "")).strip().toUpperCase(Locale.ROOT);
            return cc.isEmpty() ? List.of() : List.of(cc);
        }
        if ("multi_country".equals(geoType) || "mixed_geo_compare".equals(geoType)) {
            List<String> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            Object raw = geo.get("country_codes");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    String cc = String.valueOf(item).strip().toUpperCase(Locale.ROOT);
                    if (!cc.isEmpty() && !EU_AGGREGATE_GEO_CODES.contains(cc) && seen.add(cc)) {
                        out.add(cc);
                    }
                }
            }
            return out;
        }
        if ("czech_region".equals(geoType)) {
            return List.of("CZ");
        }
        if ("global_aggregate".equals(geoType)) {
            String scope = String.valueOf(geo.getOrDefault("region_scope", "GLOBAL")).strip().toUpperCase(Locale.ROOT);
            return scope.isEmpty() ? List.of("GLOBAL") : List.of(scope);
        }
        if ("eu_aggregate".equals(geoType)) {
            String scope = String.valueOf(geo.getOrDefault("region_scope", "EU")).strip().toUpperCase(Locale.ROOT);
            if ("EA".equals(scope) || "EU".equals(scope) || "EUROPE".equals(scope)) {
                return List.of(scope);
            }
            return List.of("EU");
        }
        return List.of();
    }

    /** Simplified port of {@code filter_catalog_source_list}. */
    public static List<String> filterCatalogSourceList(List<String> sourceIds, Map<String, Object> geoIntent) {
        List<String> out = new ArrayList<>();
        for (String source : sourceIds) {
            if (isCatalogSourceGeoApplicable(source, geoIntent)) {
                out.add(source);
            }
        }
        return out;
    }

    /** Simplified port of {@code is_catalog_source_geo_applicable}. */
    public static boolean isCatalogSourceGeoApplicable(String sourceId, Map<String, Object> geoIntent) {
        String src = sourceId == null ? "" : sourceId.strip().toLowerCase(Locale.ROOT);
        List<String> codes = requestedGeoCodes(geoIntent);
        String scope = sourceGeoScope(src);
        if (codes.isEmpty()) {
            return true;
        }
        if ("CZ".equals(scope)) {
            return codes.contains("CZ") || codes.stream().allMatch("CZ"::equals);
        }
        if ("US".equals(scope)) {
            return codes.contains("US");
        }
        if ("EUROPE".equals(scope)) {
            return codes.stream().allMatch(cc -> EUROPEAN_COUNTRY_CODES.contains(cc) || EU_AGGREGATE_GEO_CODES.contains(cc));
        }
        if ("GLOBAL".equals(scope)) {
            return true;
        }
        return true;
    }

    public static String sourceGeoScope(String sourceId) {
        String src = sourceId == null ? "" : sourceId.strip().toLowerCase(Locale.ROOT);
        if ("csu".equals(src) || "arad".equals(src)) {
            return "CZ";
        }
        if ("eurostat".equals(src) || "ecb".equals(src) || "ecb2".equals(src)) {
            return "EUROPE";
        }
        if ("fred".equals(src)) {
            return "US";
        }
        if ("oecd".equals(src) || "oecd4".equals(src) || "imf".equals(src) || "bis".equals(src) || "data360".equals(src)) {
            return "GLOBAL";
        }
        return "unknown";
    }

    /** Returns a country only for catalogs whose complete connector is fixed to that country. */
    public static String fixedSourceGeoScope(String sourceId) {
        String src = sourceId == null ? "" : sourceId.strip().toLowerCase(Locale.ROOT);
        return SCOPES.fixedSourceScopes().getOrDefault(src, "");
    }

    public static String normalizeGeoQueryText(String rawQuery) {
        String q = CatalogSearchSynonyms.foldCs(rawQuery == null ? "" : rawQuery);
        q = q.replace("&", " and ").replace("/", " ").replace(";", " ");
        q = q.replaceAll("\\bversus\\b", " vs ");
        q = q.replaceAll("\\bvs\\.\\b", " vs ");
        q = q.replaceAll("\\bvs\\b", " vs ");
        q = q.replace(",", " , ");
        q = q.replaceAll("[^\\w,\\s]", " ");
        q = q.replaceAll("\\s+", " ").trim();
        return q;
    }

    /** Strip detected country/geo terms so FTS recall uses topic tokens only — ref catalog_geo_intent.py. */
    public static String topicQueryWithoutGeo(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        Map<String, Object> geo = detectGeoIntent(rawQuery);
        String q = normalizeGeoQueryText(rawQuery);
        @SuppressWarnings("unchecked")
        List<String> matchedTerms = (List<String>) geo.getOrDefault("matched_terms", List.of());
        for (String term : matchedTerms) {
            if (term == null || term.startsWith("stem:")) {
                continue;
            }
            q = removeGeoAliasFromQuery(q, term);
        }
        for (String code : requestedGeoCodes(geo)) {
            for (String alias : CatalogCountryAliasRegistry.aliasesFor(code)) {
                q = removeGeoAliasFromQuery(q, alias);
            }
        }
        for (String aggregate : EU_AGGREGATE_TERMS) {
            q = removeAliasFromQuery(q, aggregate);
        }
        return q.replaceAll("\\s+", " ").trim();
    }

    public static boolean looksLikeGeoToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String folded = CatalogSearchSynonyms.foldCs(token).trim();
        if (folded.isEmpty()) {
            return false;
        }
        // Exact token match only — substring checks falsely flag credit/research/healthcare (cr/ea).
        for (List<String> aliases : CatalogCountryAliasRegistry.aliasesByCode().values()) {
            for (String alias : aliases) {
                if (alias.endsWith("*")) {
                    continue;
                }
                String af = CatalogSearchSynonyms.foldCs(alias);
                if (!af.isBlank() && folded.equals(af)) {
                    return true;
                }
            }
        }
        for (String aggregate : EU_AGGREGATE_TERMS) {
            if (folded.equals(aggregate)) {
                return true;
            }
        }
        return false;
    }

    /** ISO country from candidate row — port {@code extract_row_country_code}. */
    public static String extractRowCountryCode(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return "";
        }
        String setId = str(firstNonBlank(row.get("set_id"), row.get("series_id")));
        if (setId.toLowerCase(Locale.ROOT).startsWith("ecb:")) {
            String[] parts = setId.split(":", 3);
            if (parts.length == 3) {
                String cc = parts[1].strip().toUpperCase(Locale.ROOT);
                if (cc.length() == 2 && cc.chars().allMatch(Character::isLetter)) {
                    return cc;
                }
            }
        }
        String fromSetId = countryCodeFromSetId(setId);
        if (!fromSetId.isBlank()) {
            return fromSetId;
        }

        // Prefer the observation geography over the provider's technical territory.
        // A global provider may host a Czech series while its own territory remains USA.
        for (String field : List.of("country_or_region", "country", "country_hint", "ref_area", "geo")) {
            String cc = resolveTerritoryToCountryCode(row.get(field));
            if (!cc.isBlank()) {
                return cc;
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> qp = row.get("query_params") instanceof Map<?, ?> map ? castMap(map) : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = row.get("filters_used") instanceof Map<?, ?> fmap ? castMap(fmap) : Map.of();
        for (Map<String, Object> params : List.of(qp, filters)) {
            for (String key : List.of("geo", "REF_AREA", "ref_area", "country", "country_code")) {
                Object raw = params.get(key);
                String cc = resolveTerritoryToCountryCode(raw);
                if (!cc.isBlank()) {
                    return cc;
                }
                if (raw instanceof List<?> list) {
                    for (Object item : list) {
                        cc = resolveTerritoryToCountryCode(item);
                        if (!cc.isBlank()) {
                            return cc;
                        }
                    }
                }
            }
        }

        String titleCountry = CatalogCountryAliasRegistry.countryCodeInTitle(CatalogTextUtils.rowTitle(row))
                .orElse("");
        if (!titleCountry.isBlank()) {
            return titleCountry;
        }
        String territory = resolveTerritoryToCountryCode(row.get("territory"));
        if (!territory.isBlank()) {
            return territory;
        }
        return "";
    }

    private static final Pattern ISO3_PATH_TOKEN = Pattern.compile("(?:^|[./|_])([A-Z]{3})(?:$|[./|_])");

    private static String countryCodeFromSetId(String setId) {
        if (setId == null || setId.isBlank()) {
            return "";
        }
        String upper = setId.toUpperCase(Locale.ROOT);

        // Generic ISO-3 path token (OECD/IMF style: /CZE/, .SVK., |USA|) — works for any country
        // via the ISO2<->ISO3 registry, no per-country literals needed.
        Matcher iso3 = ISO3_PATH_TOKEN.matcher(upper);
        while (iso3.find()) {
            String iso2FromIso3 = CatalogCountryIso3Registry.iso2For(iso3.group(1));
            if (!iso2FromIso3.isBlank()) {
                return iso2FromIso3;
            }
        }

        // A two-letter token is only strong evidence when the provider emitted it as an explicit uppercase
        // code. Lowercase dataset prefixes such as Eurostat's "lc_" are technical abbreviations, not countries.
        java.util.regex.Matcher dot = java.util.regex.Pattern.compile("(?:^|[./|_])([A-Z]{2})(?=$|[./|_])")
                .matcher(setId);
        if (dot.find()) {
            String cc = dot.group(1);
            if (CatalogCountryAliasRegistry.hasCode(cc)) {
                return cc;
            }
        }
        int dotIdx = setId.lastIndexOf('.');
        if (dotIdx >= 0 && dotIdx + 3 == setId.length()) {
            String cc = setId.substring(dotIdx + 1);
            if (cc.length() == 2 && cc.chars().allMatch(Character::isLetter) && CatalogCountryAliasRegistry.hasCode(cc)) {
                return cc;
            }
        }
        return "";
    }

    /** Map territory label / country name to ISO-2 using registry (no per-query hardcoding). */
    public static String resolveTerritoryToCountryCode(Object raw) {
        if (raw == null) {
            return "";
        }
        String text = str(raw);
        if (text.isBlank()) {
            return "";
        }
        String upper = text.strip().toUpperCase(Locale.ROOT);
        if (upper.length() == 2 && upper.chars().allMatch(Character::isLetter) && CatalogCountryAliasRegistry.hasCode(upper)) {
            return upper;
        }
        if (upper.length() == 3 && upper.chars().allMatch(Character::isLetter)) {
            String iso2FromIso3 = CatalogCountryIso3Registry.iso2For(upper);
            if (!iso2FromIso3.isBlank()) {
                return iso2FromIso3;
            }
        }
        for (String part : text.split("[/|,>·]+")) {
            String p = part.strip().toUpperCase(Locale.ROOT);
            if (p.length() == 2 && p.chars().allMatch(Character::isLetter) && CatalogCountryAliasRegistry.hasCode(p)) {
                return p;
            }
            if (p.length() == 3 && p.chars().allMatch(Character::isLetter)) {
                String iso2FromIso3 = CatalogCountryIso3Registry.iso2For(p);
                if (!iso2FromIso3.isBlank()) {
                    return iso2FromIso3;
                }
            }
        }
        String folded = CatalogTextUtils.foldAscii(text);
        for (Map.Entry<String, List<String>> entry : CatalogCountryAliasRegistry.aliasesByCode().entrySet()) {
            for (String alias : entry.getValue()) {
                if (CatalogCountryAliasRegistry.matchAlias(folded, CatalogTextUtils.foldAscii(alias))) {
                    return entry.getKey();
                }
            }
        }
        return "";
    }

    /** Score multiplier + hard reject for wrong-country rows — port {@code row_country_geo_adjustment}. */
    public static GeoRowAdjustment rowCountryGeoAdjustment(Map<String, Object> row, Map<String, Object> geoIntent) {
        String rowCc = extractRowCountryCode(row);
        List<String> codes = requestedGeoCodes(geoIntent);
        String geoType = String.valueOf(geoIntent == null ? "unknown" : geoIntent.getOrDefault("type", "unknown"))
                .strip()
                .toLowerCase(Locale.ROOT);

        if (rowCc.isBlank()) {
            String source = str(firstNonBlank(
                    row == null ? null : row.get(CatalogKeys.SOURCE_TYPE),
                    row == null ? null : row.get(CatalogKeys.CATALOG_ID),
                    row == null ? null : row.get("source")));
            String scope = sourceGeoScope(source);
            if (!codes.isEmpty() && rowLooksLikeAggregate(row)) {
                return new GeoRowAdjustment(0.05, true, "row_aggregate_for_country_query");
            }
            if (!codes.isEmpty() && !codes.contains("CZ") && "CZ".equals(scope)) {
                return new GeoRowAdjustment(0.06, true, "row_cz_source_for_foreign_query");
            }
            if (!codes.isEmpty()) {
                if ("US".equals(scope) && !codes.contains("US")) {
                    return new GeoRowAdjustment(0.35, false, "row_us_source_ambiguous_penalty");
                }
                if ("CZ".equals(scope) && !codes.contains("CZ")) {
                    return new GeoRowAdjustment(0.08, true, "row_wrong_country_cz_for_foreign_query");
                }
                return new GeoRowAdjustment(0.45, false, "row_ambiguous_geo_penalty");
            }
            if (codes.isEmpty() && "CZ".equals(scope)) {
                return new GeoRowAdjustment(1.15, false, "row_default_czech_priority");
            }
            return GeoRowAdjustment.neutral();
        }

        if (codes.isEmpty()) {
            if ("CZ".equals(rowCc)) {
                return new GeoRowAdjustment(1.15, false, "row_default_czech_priority");
            }
            if (EUROPEAN_COUNTRY_CODES.contains(rowCc)) {
                return new GeoRowAdjustment(0.72, false, "row_foreign_duplicate_deboosted");
            }
            return new GeoRowAdjustment(0.85, false, "row_non_eu_deboosted");
        }
        if (codes.contains(rowCc)) {
            // Druhý parametr je hardReject — řádek se zahodí. Tady jde ale o řádek, jehož země
            // PŘESNĚ odpovídá dotazu, takže se má naopak zvýhodnit (1.55). S `true` se zahazoval
            // a scoring nikdy nedostal šanci boost uplatnit: dotaz na konkrétní zemi ("GDP
            // Germany", "unemployment Germany") vracel u FRED/ECB/Eurostatu nulu, přestože ty
            // řady v indexu jsou — vypadly právě ty, které se trefily.
            return new GeoRowAdjustment(1.55, false, "row_requested_country_match");
        }
        if ("country".equals(geoType) || "multi_country".equals(geoType) || "czech_region".equals(geoType)) {
            return new GeoRowAdjustment(0.07, true, "row_country_mismatch");
        }
        if ("CZ".equals(rowCc) && !codes.contains("CZ")) {
            return new GeoRowAdjustment(0.08, true, "row_wrong_country_cz_for_foreign_query");
        }
        if (EUROPEAN_COUNTRY_CODES.contains(rowCc) && codes.stream().anyMatch(EUROPEAN_COUNTRY_CODES::contains)) {
            return new GeoRowAdjustment(0.12, true, "row_wrong_european_country");
        }
        return new GeoRowAdjustment(0.35, false, "row_country_mismatch");
    }

    private static boolean rowLooksLikeAggregate(Map<String, Object> row) {
        String text = aggregateDetectionText(row);
        if (text.isBlank()) {
            return false;
        }
        for (String term : aggregateTermsForRowDetection()) {
            if (CatalogTextUtils.containsWholeTokenOrPhrase(text, term)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> aggregateTermsForRowDetection() {
        List<String> out = new ArrayList<>();
        for (String term : GLOBAL_AGGREGATE_TERMS) {
            addAggregateDetectionTerm(out, term);
        }
        for (String term : EU_AGGREGATE_TERMS) {
            addAggregateDetectionTerm(out, term);
        }
        return out;
    }

    private static void addAggregateDetectionTerm(List<String> out, String raw) {
        String term = CatalogTextUtils.normalizeTokenBoundaries(raw);
        if (term.length() < 2 || AMBIGUOUS_AGGREGATE_WORDS.contains(term)) {
            return;
        }
        out.add(term);
    }

    @SuppressWarnings("unchecked")
    private static String aggregateDetectionText(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String key : List.of(
                CatalogKeys.NAME,
                CatalogKeys.TITLE,
                CatalogKeys.FULL_PATH,
                CatalogKeys.SET_ID,
                "title_original",
                "human_label_cs",
                "human_label_en")) {
            Object value = row.get(key);
            if (value != null) {
                parts.add(String.valueOf(value));
            }
        }
        Object nestedRaw = row.get(CatalogKeys.ROW);
        if (nestedRaw instanceof Map<?, ?> nested) {
            Map<String, Object> nestedMap = castMap(nested);
            for (String key : List.of(
                    CatalogKeys.NAME,
                    CatalogKeys.TITLE,
                    CatalogKeys.FULL_PATH,
                    CatalogKeys.SET_ID,
                    "title_original",
                    "human_label_cs",
                    "human_label_en")) {
                Object value = nestedMap.get(key);
                if (value != null) {
                    parts.add(String.valueOf(value));
                }
            }
        }
        return CatalogTextUtils.normalizeTokenBoundaries(String.join(" ", parts));
    }

    /** When query names a non-CZ country, prefer Eurostat/IMF/Data360 over CZ-only mirrors. */
    public static List<String> boostSourcesForGeoIntent(List<String> sources, Map<String, Object> geoIntent) {
        List<String> codes = requestedGeoCodes(geoIntent);
        if (codes.isEmpty() || codes.stream().allMatch("CZ"::equals)) {
            return sources;
        }
        List<String> preferred = List.of("eurostat", "imf", "data360", "oecd4", "ecb2", "ecb", "fred", "bis");
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String pref : preferred) {
            if (sources.contains(pref)) {
                merged.add(pref);
            }
        }
        for (String src : sources) {
            if (isCatalogSourceGeoApplicable(src, geoIntent)) {
                merged.add(src);
            }
        }
        for (String czOnly : List.of("csu", "arad")) {
            merged.remove(czOnly);
            if (sources.contains(czOnly)) {
                merged.add(czOnly);
            }
        }
        return new ArrayList<>(merged);
    }

    public record GeoRowAdjustment(double multiplier, boolean hardReject, String reason) {
        static GeoRowAdjustment neutral() {
            return new GeoRowAdjustment(1.0, false, null);
        }
    }

    private static String removeGeoAliasFromQuery(String query, String alias) {
        if (query.isBlank() || alias == null || alias.isBlank()) {
            return query;
        }
        if (alias.endsWith("*") && alias.length() > 2) {
            String stem = CatalogSearchSynonyms.foldCs(alias.substring(0, alias.length() - 1));
            if (stem.isBlank()) {
                return query;
            }
            return query
                    .replaceAll("(?:^|[^a-z0-9])" + Pattern.quote(stem) + "[a-z0-9]*", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
        }
        return removeAliasFromQuery(query, CatalogSearchSynonyms.foldCs(alias));
    }

    private static String removeAliasFromQuery(String query, String aliasFolded) {
        if (query.isBlank() || aliasFolded.isBlank()) {
            return query;
        }
        return query
                .replaceAll("(?:^|[^a-z0-9])" + Pattern.quote(aliasFolded) + "(?:[^a-z0-9]|$)", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = str(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private record DetectedCountries(List<String> codes, List<String> terms) {}

    private static DetectedCountries detectedCountryCodes(String rawQuery, String normalizedQuery) {
        List<String> matchedCodes = new ArrayList<>();
        List<String> matchedTerms = new ArrayList<>();
        Set<String> seenCodes = new LinkedHashSet<>();

        // Perf fix: iterates the SAME (country, alias) pairs in the SAME order as the old
        // aliasesByCode().entrySet() double loop, but against patterns compiled once at class-init
        // (see CatalogCountryAliasRegistry.compileAliases()) instead of compiling fresh Patterns here
        // on every call (i.e. once per candidate, per request). The substring prefilter below is a
        // provably safe gate (see CompiledCountryAlias.prefilterSubstring javadoc): precompilation
        // alone still left ~1200 Matcher.find() calls per resolve() against long candidate evidence
        // text as the dominant remaining cost (measured), so skipping the ones that cannot possibly
        // match is required to hit the latency targets - not merely a further micro-optimization.
        for (CatalogCountryAliasRegistry.CompiledCountryAlias compiled : CatalogCountryAliasRegistry.compiledAliases()) {
            if (!normalizedQuery.contains(compiled.prefilterSubstring())) {
                continue;
            }
            boolean matched = matchesPattern(compiled.foldedPattern(), normalizedQuery)
                    || matchesPattern(compiled.rawPattern(), normalizedQuery);
            if (matched) {
                if (seenCodes.add(compiled.countryCode())) {
                    matchedCodes.add(compiled.countryCode());
                }
                matchedTerms.add(compiled.originalAlias());
            }
        }

        for (String code : stemMatchedCountryCodes(normalizedQuery)) {
            if (seenCodes.add(code)) {
                matchedCodes.add(code);
                matchedTerms.add("stem:" + code);
            }
        }

        Matcher isoMatcher = ISO_TOKEN.matcher(rawQuery == null ? "" : rawQuery);
        Set<String> allCodes = new LinkedHashSet<>(CatalogCountryAliasRegistry.aliasesByCode().keySet());
        allCodes.addAll(EUROPEAN_COUNTRY_CODES);
        allCodes.add("XK");
        while (isoMatcher.find()) {
            String tok = isoMatcher.group().strip().toUpperCase(Locale.ROOT);
            String code = "CR".equals(tok)
                    && !(normalizedQuery.contains("costa rica") || normalizedQuery.contains("kostarika"))
                    ? "CZ"
                    : tok;
            if (allCodes.contains(code) && seenCodes.add(code)) {
                matchedCodes.add(code);
                matchedTerms.add(tok);
            }
        }

        if ((normalizedQuery.matches(".*\\bczech\\b.*") || normalizedQuery.matches(".*\\bcesk\\w*\\b.*"))
                && normalizedQuery.matches(".*(econom|market|trh|statistik|inflac|nezamestnan|gdp|hdp|popul|obyvatel|mzdy|wage|debt|deficit).*")
                && seenCodes.add("CZ")) {
            matchedCodes.add("CZ");
            matchedTerms.add("czech-context");
        }

        return new DetectedCountries(matchedCodes, matchedTerms);
    }

    private static List<String> stemMatchedCountryCodes(String normalizedQuery) {
        List<String> qStems = CzTextStemmer.stemTokens(normalizedQuery);
        if (qStems.isEmpty()) {
            return List.of();
        }
        Map<String, List<StemEntry>> index = COUNTRY_STEM_INDEX;
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < qStems.size(); i++) {
            String first = qStems.get(i);
            for (StemEntry entry : index.getOrDefault(first, List.of())) {
                int tlen = entry.stems().size();
                if (i + tlen <= qStems.size()) {
                    boolean match = true;
                    for (int j = 0; j < tlen; j++) {
                        if (!qStems.get(i + j).equals(entry.stems().get(j))) {
                            match = false;
                            break;
                        }
                    }
                    if (match && seen.add(entry.code())) {
                        out.add(entry.code());
                    }
                }
            }
        }
        return out;
    }

    private record StemEntry(List<String> stems, String code) {}

    /** Perf fix: called once at class-init (see COUNTRY_STEM_INDEX) instead of once per detectedCountryCodes() call. */
    private static Map<String, List<StemEntry>> buildCountryStemIndex() {
        Map<String, List<StemEntry>> index = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : CatalogCountryAliasRegistry.aliasesByCode().entrySet()) {
            for (String alias : entry.getValue()) {
                String a = alias.strip();
                if (a.isEmpty() || a.contains("*") || a.chars().anyMatch(Character::isDigit)) {
                    continue;
                }
                List<String> stems = CzTextStemmer.stemTokens(a);
                if (stems.isEmpty()) {
                    continue;
                }
                int maxLen = stems.stream().mapToInt(String::length).max().orElse(0);
                if (maxLen < 4) {
                    continue;
                }
                index.computeIfAbsent(stems.get(0), k -> new ArrayList<>()).add(new StemEntry(stems, entry.getKey()));
            }
        }
        return Map.copyOf(index);
    }

    /** Exact regex shape the old per-call matchAlias(...) used to build - see CompiledCountryAlias javadoc. */
    private static Pattern buildAliasPattern(String alias) {
        String a = alias == null ? "" : alias.strip();
        if (a.isEmpty()) {
            return null;
        }
        if (a.endsWith("*") && a.length() > 2) {
            String stem = Pattern.quote(a.substring(0, a.length() - 1));
            return Pattern.compile("(?:^|[^a-z0-9])" + stem + "[a-z0-9]*");
        }
        return Pattern.compile("(?:^|[^a-z0-9])" + Pattern.quote(a) + "(?:[^a-z0-9]|$)");
    }

    private static List<Pattern> compileTermPatterns(List<String> terms) {
        List<Pattern> out = new ArrayList<>();
        for (String term : terms == null ? List.<String>of() : terms) {
            Pattern pattern = buildAliasPattern(term);
            if (pattern != null) {
                out.add(pattern);
            }
        }
        return List.copyOf(out);
    }

    private static boolean matchesPattern(Pattern pattern, String normalizedQuery) {
        if (pattern == null) {
            return false;
        }
        MATCHER_EXECUTION_COUNT.incrementAndGet();
        return pattern.matcher(normalizedQuery).find();
    }

    private static boolean matchesAnyPattern(List<Pattern> patterns, String normalizedQuery) {
        for (Pattern pattern : patterns) {
            if (matchesPattern(pattern, normalizedQuery)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveEuAggregateScope(String normalizedQuery) {
        if (List.of("euro area", "eurozone", "eurozona", "ea20", "ea19", "ea").stream()
                .anyMatch(normalizedQuery::contains)) {
            return "EA";
        }
        if (List.of("evropska unie", "european union", "eu27", "eu 27", "eu-27").stream()
                .anyMatch(normalizedQuery::contains)) {
            return "EU";
        }
        if (List.of("evropa", "evrope", "evrop", "europe", "european").stream()
                .anyMatch(normalizedQuery::contains)) {
            return "EUROPE";
        }
        return "EU";
    }

    private static List<String> dedupUpper(List<String> codes) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String c : codes) {
            String cc = c == null ? "" : c.strip().toUpperCase(Locale.ROOT);
            if (!cc.isEmpty() && seen.add(cc)) {
                out.add(cc);
            }
        }
        return out;
    }

    private static List<String> dedupStrings(List<String> values) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String v : values) {
            String t = v == null ? "" : v.strip();
            if (!t.isEmpty() && seen.add(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Deserialized shape of {@code catalog/geo_scopes.json}. */
    private record GeoScopes(
            Set<String> europeanIso2,
            List<String> euAggregateTerms,
            List<String> globalAggregateTerms,
            Map<String, String> fixedSourceScopes) {

        static GeoScopes empty() {
            return new GeoScopes(Set.of(), List.of(), List.of(), Map.of());
        }
    }

    private static GeoScopes loadGeoScopes() {
        try (InputStream in = CatalogGeoIntent.class.getResourceAsStream("/catalog/geo_scopes.json")) {
            if (in == null) {
                return GeoScopes.empty();
            }
            Map<String, List<String>> raw =
                    MAPPER.readValue(in, new TypeReference<LinkedHashMap<String, List<String>>>() {});
            Set<String> europeanIso2 = Set.copyOf(raw.getOrDefault("european_iso2", List.of()));
            List<String> euAggregateTerms = List.copyOf(raw.getOrDefault("eu_aggregate_terms", List.of()));
            List<String> globalAggregateTerms = List.copyOf(raw.getOrDefault("global_aggregate_terms", List.of()));
            Map<String, String> fixedSourceScopes = new LinkedHashMap<>();
            for (String entry : raw.getOrDefault("fixed_source_scopes", List.of())) {
                String[] parts = entry == null ? new String[0] : entry.split(":", 2);
                if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                    fixedSourceScopes.put(
                            parts[0].strip().toLowerCase(Locale.ROOT),
                            parts[1].strip().toUpperCase(Locale.ROOT));
                }
            }
            return new GeoScopes(
                    europeanIso2,
                    euAggregateTerms,
                    globalAggregateTerms,
                    Map.copyOf(fixedSourceScopes));
        } catch (Exception ex) {
            return GeoScopes.empty();
        }
    }
}
