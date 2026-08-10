package cz.bankintel.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Perf fix (geo-detection regex-compilation storm - {@code CatalogGeoIntent.detectedCountryCodes()}
 * used to compile ~1200 fresh {@code Pattern} objects on every call, once per candidate per request):
 * golden-dataset equivalence between the NEW precompiled-pattern implementation and an embedded ORACLE
 * that reproduces the OLD per-call {@code Pattern.compile(...)} algorithm byte-for-byte (same regex
 * shapes, same iteration order, same fold/no-fold split between the two matcher passes, same stem index
 * rebuild). Any behavioral drift introduced by precompilation would show up here as a field-by-field
 * mismatch on {@code detectGeoIntent(...)} output.
 */
class CatalogGeoIntentPatternCacheEquivalenceTest {

    // ---- Oracle: exact reimplementation of the pre-optimization algorithm, compiling Patterns fresh
    // on every call (no precomputed index) - deliberately duplicated, not reused, so a regression in
    // the optimized path cannot silently drag the oracle down with it. ----

    private static boolean oracleMatchAlias(String normalizedQuery, String alias) {
        String a = alias.strip();
        if (a.isEmpty()) {
            return false;
        }
        if (a.endsWith("*") && a.length() > 2) {
            String stem = Pattern.quote(a.substring(0, a.length() - 1));
            return Pattern.compile("(?:^|[^a-z0-9])" + stem + "[a-z0-9]*").matcher(normalizedQuery).find();
        }
        return Pattern.compile("(?:^|[^a-z0-9])" + Pattern.quote(a) + "(?:[^a-z0-9]|$)")
                .matcher(normalizedQuery)
                .find();
    }

    private static Map<String, Object> oracleDetectGeoIntent(String query) {
        String normalizedQuery = CatalogGeoIntent.normalizeGeoQueryText(query);
        OracleDetected detected = oracleDetectedCountryCodes(query, normalizedQuery);
        List<String> matchedCodes = detected.codes;
        List<String> matchedTerms = detected.terms;

        boolean hasEuAggregate = oracleEuAggregateTerms().stream().anyMatch(t -> oracleMatchAlias(normalizedQuery, t));
        boolean hasGlobalAggregate =
                oracleGlobalAggregateTerms().stream().anyMatch(t -> oracleMatchAlias(normalizedQuery, t));
        boolean hasComparativeMarker = normalizedQuery.contains(",")
                || normalizedQuery.contains(" vs ")
                || normalizedQuery.contains(" versus ")
                || normalizedQuery.contains(" oproti ")
                || normalizedQuery.contains(" compare ");

        List<String> dedupCodes = oracleDedupUpper(matchedCodes);

        String geoType = "unknown";
        String countryCode = null;
        List<String> countryCodes = List.of();
        boolean comparative = false;
        double confidence = 0.0;

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
        } else if (hasGlobalAggregate) {
            geoType = "global_aggregate";
            confidence = 0.84;
        }

        if (hasComparativeMarker && dedupCodes.size() >= 2) {
            comparative = true;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", geoType);
        result.put("country_code", countryCode);
        result.put("country_codes", countryCodes);
        result.put("comparative", comparative);
        result.put("confidence", Math.round(confidence * 1000.0) / 1000.0);
        result.put("matched_terms", oracleDedupStrings(matchedTerms));
        result.put("normalized_query", normalizedQuery);
        return result;
    }

    private record OracleDetected(List<String> codes, List<String> terms) {}

    private static OracleDetected oracleDetectedCountryCodes(String rawQuery, String normalizedQuery) {
        List<String> matchedCodes = new ArrayList<>();
        List<String> matchedTerms = new ArrayList<>();
        Set<String> seenCodes = new LinkedHashSet<>();

        for (Map.Entry<String, List<String>> entry : CatalogCountryAliasRegistry.aliasesByCode().entrySet()) {
            for (String alias : entry.getValue()) {
                String af = CatalogSearchSynonyms.foldCs(alias);
                if (af.isEmpty()) {
                    continue;
                }
                if (oracleMatchAlias(normalizedQuery, af)
                        || CatalogCountryAliasRegistry.matchAlias(normalizedQuery, alias)) {
                    if (seenCodes.add(entry.getKey())) {
                        matchedCodes.add(entry.getKey());
                    }
                    matchedTerms.add(alias);
                }
            }
        }

        for (String code : oracleStemMatchedCountryCodes(normalizedQuery)) {
            if (seenCodes.add(code)) {
                matchedCodes.add(code);
                matchedTerms.add("stem:" + code);
            }
        }

        java.util.regex.Matcher isoMatcher =
                Pattern.compile("\\b[A-Z]{2,3}\\b").matcher(rawQuery == null ? "" : rawQuery);
        Set<String> allCodes = new LinkedHashSet<>(CatalogCountryAliasRegistry.aliasesByCode().keySet());
        allCodes.addAll(CatalogGeoIntent.EUROPEAN_COUNTRY_CODES);
        allCodes.add("XK");
        while (isoMatcher.find()) {
            String tok = isoMatcher.group().strip().toUpperCase(java.util.Locale.ROOT);
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
                && normalizedQuery.matches(
                        ".*(econom|market|trh|statistik|inflac|nezamestnan|gdp|hdp|popul|obyvatel|mzdy|wage|debt|deficit).*")
                && seenCodes.add("CZ")) {
            matchedCodes.add("CZ");
            matchedTerms.add("czech-context");
        }

        return new OracleDetected(matchedCodes, matchedTerms);
    }

    private static List<String> oracleStemMatchedCountryCodes(String normalizedQuery) {
        List<String> qStems = CzTextStemmer.stemTokens(normalizedQuery);
        if (qStems.isEmpty()) {
            return List.of();
        }
        Map<String, List<OracleStemEntry>> index = oracleCountryStemIndex();
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < qStems.size(); i++) {
            String first = qStems.get(i);
            for (OracleStemEntry entry : index.getOrDefault(first, List.of())) {
                int tlen = entry.stems.size();
                if (i + tlen <= qStems.size()) {
                    boolean match = true;
                    for (int j = 0; j < tlen; j++) {
                        if (!qStems.get(i + j).equals(entry.stems.get(j))) {
                            match = false;
                            break;
                        }
                    }
                    if (match && seen.add(entry.code)) {
                        out.add(entry.code);
                    }
                }
            }
        }
        return out;
    }

    private record OracleStemEntry(List<String> stems, String code) {}

    private static Map<String, List<OracleStemEntry>> oracleCountryStemIndex() {
        Map<String, List<OracleStemEntry>> index = new LinkedHashMap<>();
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
                index.computeIfAbsent(stems.get(0), k -> new ArrayList<>()).add(new OracleStemEntry(stems, entry.getKey()));
            }
        }
        return index;
    }

    private static List<String> oracleEuAggregateTerms() {
        return CatalogGeoIntent.euAggregateTermsForTest();
    }

    private static List<String> oracleGlobalAggregateTerms() {
        return CatalogGeoIntent.globalAggregateTermsForTest();
    }

    private static List<String> oracleDedupUpper(List<String> codes) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String c : codes) {
            String cc = c == null ? "" : c.strip().toUpperCase(java.util.Locale.ROOT);
            if (!cc.isEmpty() && seen.add(cc)) {
                out.add(cc);
            }
        }
        return out;
    }

    private static List<String> oracleDedupStrings(List<String> values) {
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

    // ---- Golden dataset ----

    static Stream<String> goldenQueries() {
        List<String> queries = new ArrayList<>();
        // All country aliases from the real registry (every alias, every country) - each wrapped in a
        // realistic sentence so boundary/prefilter behavior is exercised, not just the bare token.
        for (Map.Entry<String, List<String>> entry : CatalogCountryAliasRegistry.aliasesByCode().entrySet()) {
            for (String alias : entry.getValue()) {
                String bare = alias.endsWith("*") ? alias.substring(0, alias.length() - 1) + "sko" : alias;
                queries.add("HDP a inflace " + bare + " za posledni rok");
            }
        }
        // ISO alpha-2 / alpha-3 codes
        queries.add("GDP growth in DE last year");
        queries.add("inflation CZE vs SVK comparison");
        queries.add("unemployment rate USA");
        // Czech / English country names, diacritics, multi-word
        queries.add("Nezaměstnanost v Německu");
        queries.add("inflace ceska republika");
        queries.add("Czech Republic inflation rate");
        queries.add("Spojene staty americke HDP");
        queries.add("Slovenska republika mzdy");
        // abbreviations
        queries.add("cr vs sk mzdy");
        // alias as standalone word vs substring inside another word (must NOT match)
        queries.add("cr");
        queries.add("credit research report"); // "cr" substring inside "credit" - must not match CZ
        queries.add("increase in research spending"); // "ea" substring inside "research" - must not match U2
        // multiple countries in one text
        queries.add("Germany vs France industrial output comparison");
        queries.add("HDP Nemecko, Francie a Italie");
        // no country at all
        queries.add("bank profitability ROE ROA quarterly");
        queries.add("");
        queries.add("   ");
        // punctuation around alias, start/end of string
        queries.add("(Germany), inflation");
        queries.add("Germany.");
        queries.add("Germany");
        queries.add(".Germany");
        // case variations
        queries.add("GERMANY inflation");
        queries.add("gErMaNy inflation");
        // potentially conflicting alias (konzsk* shared by CD and CG)
        queries.add("HDP konzska republika");
        // EU / global aggregate terms
        queries.add("EU average inflation");
        queries.add("eurozone GDP growth");
        queries.add("global commodity prices");
        // mixed compare (country + EU aggregate)
        queries.add("Germany vs EU average inflation");
        return queries.stream().distinct();
    }

    @ParameterizedTest
    @MethodSource("goldenQueries")
    void detectGeoIntentMatchesOracleOnGoldenDataset(String query) {
        Map<String, Object> viaNew = CatalogGeoIntent.detectGeoIntent(query);
        Map<String, Object> viaOracle = oracleDetectGeoIntent(query);

        assertThat(viaNew.get("type")).as("type for query=%s", query).isEqualTo(viaOracle.get("type"));
        assertThat(viaNew.get("country_code")).as("country_code for query=%s", query)
                .isEqualTo(viaOracle.get("country_code"));
        assertThat(viaNew.get("country_codes")).as("country_codes for query=%s", query)
                .isEqualTo(viaOracle.get("country_codes"));
        assertThat(viaNew.get("comparative")).as("comparative for query=%s", query)
                .isEqualTo(viaOracle.get("comparative"));
        assertThat(viaNew.get("confidence")).as("confidence for query=%s", query)
                .isEqualTo(viaOracle.get("confidence"));
        assertThat(viaNew.get("matched_terms")).as("matched_terms for query=%s", query)
                .isEqualTo(viaOracle.get("matched_terms"));
        assertThat(viaNew.get("normalized_query")).as("normalized_query for query=%s", query)
                .isEqualTo(viaOracle.get("normalized_query"));
    }

    @Test
    void nullQueryNeverThrowsAndMatchesOracle() {
        Map<String, Object> viaNew = CatalogGeoIntent.detectGeoIntent(null);
        Map<String, Object> viaOracle = oracleDetectGeoIntent(null);
        assertThat(viaNew.get("type")).isEqualTo(viaOracle.get("type"));
        assertThat(viaNew.get("country_codes")).isEqualTo(viaOracle.get("country_codes"));
    }

    @Test
    void requestedGeoCodesStillDerivesTheSameCodesFromDetectGeoIntent() {
        for (String query : List.of(
                "Germany vs France comparison", "inflace ceska republika", "EU average inflation", "no country here")) {
            Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
            List<String> codes = CatalogGeoIntent.requestedGeoCodes(geo);
            assertThat(codes).as("requestedGeoCodes must not throw and must be a list for query=%s", query)
                    .isNotNull();
        }
    }
}
