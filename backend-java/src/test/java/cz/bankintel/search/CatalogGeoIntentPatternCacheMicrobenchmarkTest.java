package cz.bankintel.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Perf fix (geo-detection regex-compilation storm): in-JVM before/after microbenchmark. "Before" is
 * the embedded oracle from {@link CatalogGeoIntentPatternCacheEquivalenceTest} (proven behaviorally
 * identical across 654 golden cases) reimplementing the exact pre-optimization algorithm - compiling
 * fresh Patterns on every call, no precomputed index. "After" is the real, current
 * {@code CatalogGeoIntent.detectGeoIntent}. Running both in the same JVM session (same warmup, same
 * JIT state) gives a fair A/B without needing a VCS revert (this repo has no git history to diff against).
 */
class CatalogGeoIntentPatternCacheMicrobenchmarkTest {

    private static final int WARM_ITERATIONS = 150;
    private static final int MEASURED_ITERATIONS = 150;

    private static final String SHORT_NO_GEO = "bank profitability ROE ROA quarterly";
    private static final String SHORT_WITH_GEO = "bank profitability Slovakia";
    private static final String LONG_NO_GEO =
            "Bank profitability ROE ROA dataset quarterly banking sector indicator comparison table "
                    + "national supervision metrics annual report consolidated financial statement summary "
                    + "regulatory capital adequacy ratio non performing loan coverage liquidity buffer";
    private static final String LONG_WITH_GEO =
            "Bank profitability ROE ROA Slovakia SK dataset quarterly banking sector indicator comparison "
                    + "table national bank supervision metrics annual report consolidated financial statement "
                    + "summary regulatory capital adequacy ratio Slovak Republic non performing loan coverage";
    private static final String MULTI_COUNTRY =
            "Germany vs France vs Czech Republic vs Slovakia economic comparison GDP inflation unemployment";

    @Test
    void benchmarkDetectGeoIntentBeforeVsAfter() {
        record Category(String name, String text) {}
        List<Category> categories = List.of(
                new Category("short_no_geo", SHORT_NO_GEO),
                new Category("short_with_geo", SHORT_WITH_GEO),
                new Category("long_no_geo", LONG_NO_GEO),
                new Category("long_with_geo", LONG_WITH_GEO),
                new Category("multi_country", MULTI_COUNTRY));

        System.out.println("[geo-microbench] category | before(oracle) median/p90/min/max us | after(new) median/p90/min/max us | speedup");
        for (Category category : categories) {
            for (int i = 0; i < WARM_ITERATIONS; i++) {
                oracleDetectGeoIntent(category.text());
                CatalogGeoIntent.detectGeoIntent(category.text());
            }
            long[] beforeNanos = new long[MEASURED_ITERATIONS];
            for (int i = 0; i < MEASURED_ITERATIONS; i++) {
                long start = System.nanoTime();
                oracleDetectGeoIntent(category.text());
                beforeNanos[i] = System.nanoTime() - start;
            }
            long[] afterNanos = new long[MEASURED_ITERATIONS];
            for (int i = 0; i < MEASURED_ITERATIONS; i++) {
                long start = System.nanoTime();
                CatalogGeoIntent.detectGeoIntent(category.text());
                afterNanos[i] = System.nanoTime() - start;
            }
            printStats(category.name(), beforeNanos, afterNanos);
        }
    }

    private static void printStats(String label, long[] beforeNanos, long[] afterNanos) {
        long[] beforeSorted = beforeNanos.clone();
        long[] afterSorted = afterNanos.clone();
        java.util.Arrays.sort(beforeSorted);
        java.util.Arrays.sort(afterSorted);
        double beforeMedianUs = beforeSorted[beforeSorted.length / 2] / 1000.0;
        double beforeP90Us = beforeSorted[(int) (beforeSorted.length * 0.9)] / 1000.0;
        double beforeMinUs = beforeSorted[0] / 1000.0;
        double beforeMaxUs = beforeSorted[beforeSorted.length - 1] / 1000.0;
        double afterMedianUs = afterSorted[afterSorted.length / 2] / 1000.0;
        double afterP90Us = afterSorted[(int) (afterSorted.length * 0.9)] / 1000.0;
        double afterMinUs = afterSorted[0] / 1000.0;
        double afterMaxUs = afterSorted[afterSorted.length - 1] / 1000.0;
        double speedup = beforeMedianUs / Math.max(0.001, afterMedianUs);
        System.out.printf(
                "[geo-microbench] %s | before %.1f/%.1f/%.1f/%.1f us | after %.1f/%.1f/%.1f/%.1f us | speedup=%.1fx%n",
                label, beforeMedianUs, beforeP90Us, beforeMinUs, beforeMaxUs,
                afterMedianUs, afterP90Us, afterMinUs, afterMaxUs, speedup);
    }

    // ---- Oracle (duplicated deliberately from CatalogGeoIntentPatternCacheEquivalenceTest - see that
    // class's javadoc for why it is not shared/reused) ----

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
        List<String> matchedCodes = new ArrayList<>();
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
                }
            }
        }

        for (String code : oracleStemMatchedCountryCodes(normalizedQuery)) {
            seenCodes.add(code);
        }

        boolean hasEuAggregate = CatalogGeoIntent.euAggregateTermsForTest().stream()
                .anyMatch(t -> oracleMatchAlias(normalizedQuery, t));
        boolean hasGlobalAggregate = CatalogGeoIntent.globalAggregateTermsForTest().stream()
                .anyMatch(t -> oracleMatchAlias(normalizedQuery, t));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("country_codes", List.copyOf(matchedCodes));
        result.put("has_eu_aggregate", hasEuAggregate);
        result.put("has_global_aggregate", hasGlobalAggregate);
        return result;
    }

    private static List<String> oracleStemMatchedCountryCodes(String normalizedQuery) {
        List<String> qStems = CzTextStemmer.stemTokens(normalizedQuery);
        if (qStems.isEmpty()) {
            return List.of();
        }
        Map<String, List<Map.Entry<List<String>, String>>> index = new LinkedHashMap<>();
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
                index.computeIfAbsent(stems.get(0), k -> new ArrayList<>())
                        .add(Map.entry(stems, entry.getKey()));
            }
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < qStems.size(); i++) {
            for (Map.Entry<List<String>, String> e : index.getOrDefault(qStems.get(i), List.of())) {
                List<String> stems = e.getKey();
                if (i + stems.size() <= qStems.size()) {
                    boolean match = true;
                    for (int j = 0; j < stems.size(); j++) {
                        if (!qStems.get(i + j).equals(stems.get(j))) {
                            match = false;
                            break;
                        }
                    }
                    if (match && seen.add(e.getValue())) {
                        out.add(e.getValue());
                    }
                }
            }
        }
        return out;
    }
}
