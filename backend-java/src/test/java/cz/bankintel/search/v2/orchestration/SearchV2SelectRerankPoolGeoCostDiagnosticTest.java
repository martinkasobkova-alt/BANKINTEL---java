package cz.bankintel.search.v2.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.v2.geo.SearchV2GeoCompatibility;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Temporary diagnostic (perf investigation only, not a behavior assertion suite): after the concept-
 * registry optimization, live measurement showed {@code select_rerank_pool_ms} UNCHANGED at 47/72
 * candidates (~950ms/~1530ms, identical to the pre-fix baseline). This proves the dominant cost inside
 * {@code SearchV2Service.selectRerankPool} was never {@code candidateMatchesRequiredConcepts} - this
 * test measures the two real candidates for the actual cost directly, with real production components
 * (no mocks): (1) call counts through the concept registry via a real {@code selectRerankPool} call,
 * and (2) direct wall-clock cost of {@code SearchV2GeoCompatibility.assessCandidateGeo}, which is
 * invoked from inside the (non-caching) {@code Comparator.comparing} key extractor during the
 * concept-match sort.
 */
class SearchV2SelectRerankPoolGeoCostDiagnosticTest {

    private final SearchV2ConceptRegistry registry = new SearchV2ConceptRegistry(new ObjectMapper());

    @Test
    void reportConceptRegistryCallCountsForRealisticCandidateCounts() {
        // Kept deliberately small (not 47/72/136/240 like the live-measured buckets): each candidate's
        // evidence text mentions "Slovakia", so candidateMatchesRequiredConcepts's resolve() call pays
        // the full CatalogGeoIntent.detectGeoIntent() cost (see SearchV2SelectRerankPoolGeoCostDiagnosticTest
        // class javadoc) for every one of them. In a cold/interpreted fresh test-executor JVM fork
        // (no JIT warmup, unlike a live server that's been up for a while) that cost is dramatically
        // higher than in a warmed JVM - at n=240 this test alone took minutes and stalled a full
        // regression run. The O(1)-vs-O(n) call-count claim this test proves does not depend on the
        // absolute candidate count, only on the ratio holding at any n, so a small n is sufficient.
        for (int n : List.of(5, 15)) {
            List<SearchCandidate> candidates = buildCandidates(n, true);
            SearchQueryPlan plan = planWithConcepts(List.of("bank_profitability"));

            long resolveBefore = registry.resolveCallCountForTest();
            long requirementBefore = registry.resolveRequirementCallCountForTest();
            long matchesBefore = registry.candidateMatchesRequiredConceptsCallCountForTest();
            long scanBefore = registry.conceptListScanCountForTest();

            SearchV2Service.selectRerankPool(candidates, 40, plan, registry);

            long resolveCalls = registry.resolveCallCountForTest() - resolveBefore;
            long requirementCalls = registry.resolveRequirementCallCountForTest() - requirementBefore;
            long matchesCalls = registry.candidateMatchesRequiredConceptsCallCountForTest() - matchesBefore;
            long scans = registry.conceptListScanCountForTest() - scanBefore;

            System.out.printf(
                    "[concept-registry-calls] n=%d resolveRequirementCalls=%d (expect 1) "
                            + "candidateMatchesRequiredConceptsCalls=%d (expect ~n=%d) "
                            + "resolveCalls=%d conceptListFullScans=%d%n",
                    n, requirementCalls, matchesCalls, n, resolveCalls, scans);
        }
    }

    @Test
    void reportDirectGeoCompatibilityCostForBlankVsTaggedGeoCandidates() {
        SearchQueryPlan plan = planWithConcepts(List.of("bank_profitability"));
        for (int n : List.of(47, 72, 136, 240)) {
            List<SearchCandidate> blankGeo = buildCandidates(n, false);
            List<SearchCandidate> taggedGeo = buildCandidates(n, true);

            long blankNanos = timeAssessCandidateGeoOnceEach(blankGeo, plan);
            long taggedNanos = timeAssessCandidateGeoOnceEach(taggedGeo, plan);

            // Comparator.comparing(keyExtractor) re-invokes the key extractor on both sides of every
            // comparison during List.sort's TimSort - not a flat n calls. n*ceil(log2(n)) approximates
            // the merge-sort comparison count as an order-of-magnitude, not an exact TimSort count.
            int repeatFactor = Math.max(1, (int) Math.ceil(Math.log(n) / Math.log(2)));
            long blankNanosAtSortScale = timeAssessCandidateGeoRepeated(blankGeo, plan, repeatFactor);

            System.out.printf(
                    "[geo-compat-cost] n=%d perCallBlankGeoUs=%.1f perCallTaggedGeoUs=%.1f "
                            + "totalOnceEachBlankMs=%.2f totalOnceEachTaggedMs=%.2f "
                            + "repeatFactor(~log2 n)=%d totalAtSortScaleBlankMs=%.2f%n",
                    n,
                    (blankNanos / (double) n) / 1000.0,
                    (taggedNanos / (double) n) / 1000.0,
                    blankNanos / 1_000_000.0,
                    taggedNanos / 1_000_000.0,
                    repeatFactor,
                    blankNanosAtSortScale / 1_000_000.0);
        }
    }

    @Test
    void reportResolveCostForTextWithAndWithoutGeographyMention() {
        // Isolates SearchV2ConceptRegistry#resolve()'s cost specifically, with REALISTIC evidence text
        // (mirrors candidateConceptEvidence's concatenation of title+description+dataset+concepts+raw
        // fields) - the earlier live measurement showed ~20ms/candidate inside the concept-filter
        // block even AFTER the registry-scan fix, which is inconsistent with the registry's own
        // precomputed alias/phrase scan (proven cheap in reportDirectGeoCompatibilityCostForBlankVsTaggedGeoCandidates
        // and the call-count test above). Hypothesis: the cost is in resolve()'s call to
        // withoutDetectedGeography() -> CatalogGeoIntent.detectGeoIntent(), which recompiles ~1200
        // regex Patterns (2 per alias x 619 aliases) EVERY call when the text mentions a country.
        String textWithGeoMention = "Bank profitability ROE ROA Slovakia SK dataset quarterly banking sector "
                + "indicator comparison table national bank supervision";
        String textWithoutGeoMention = "Bank profitability ROE ROA dataset quarterly banking sector "
                + "indicator comparison table supervision metrics";

        // JIT warmup - both branches, same iteration count, before the timed run.
        for (int i = 0; i < 200; i++) {
            registry.resolve(textWithGeoMention);
            registry.resolve(textWithoutGeoMention);
        }

        int n = 200;
        long withGeoStart = System.nanoTime();
        for (int i = 0; i < n; i++) {
            registry.resolve(textWithGeoMention);
        }
        long withGeoNanos = System.nanoTime() - withGeoStart;

        long withoutGeoStart = System.nanoTime();
        for (int i = 0; i < n; i++) {
            registry.resolve(textWithoutGeoMention);
        }
        long withoutGeoNanos = System.nanoTime() - withoutGeoStart;

        System.out.printf(
                "[resolve-geo-detection-cost] n=%d perCallWithGeoMentionUs=%.1f perCallWithoutGeoMentionUs=%.1f "
                        + "ratio=%.1fx%n",
                n,
                (withGeoNanos / (double) n) / 1000.0,
                (withoutGeoNanos / (double) n) / 1000.0,
                withGeoNanos / (double) Math.max(1, withoutGeoNanos));
    }

    private static long timeAssessCandidateGeoOnceEach(List<SearchCandidate> candidates, SearchQueryPlan plan) {
        long start = System.nanoTime();
        for (SearchCandidate candidate : candidates) {
            SearchV2GeoCompatibility.assessCandidateGeo(candidate, plan.geographies(), plan);
        }
        return System.nanoTime() - start;
    }

    private static long timeAssessCandidateGeoRepeated(
            List<SearchCandidate> candidates, SearchQueryPlan plan, int repeatFactor) {
        long start = System.nanoTime();
        for (int r = 0; r < repeatFactor; r++) {
            for (SearchCandidate candidate : candidates) {
                SearchV2GeoCompatibility.assessCandidateGeo(candidate, plan.geographies(), plan);
            }
        }
        return System.nanoTime() - start;
    }

    private static List<SearchCandidate> buildCandidates(int n, boolean taggedGeo) {
        List<SearchCandidate> out = new ArrayList<>();
        String[] sources = {"ecb2", "eurostat", "fred", "data360", "bis"};
        for (int i = 0; i < n; i++) {
            String source = sources[i % sources.length];
            String geo = taggedGeo ? "SK" : "";
            out.add(new SearchCandidate(
                    source + ":c" + i, "c" + i, "bank profit Slovakia dataset " + i, "", source, "MIR",
                    geo, "A", "PC", "", List.of("bank_roa"), List.of(), List.of(), "", 0.5 + (i % 10) * 0.01,
                    "zisk bank", List.of("canonical_title"),
                    Map.of("primary_concept", "bank_roa", "catalog_family", "banking")));
        }
        return out;
    }

    private static SearchQueryPlan planWithConcepts(List<String> primaryConcepts) {
        // NOTE: primaryConcepts is field #4 of SearchQueryPlan's canonical constructor - it must be
        // passed in that exact slot, not any other List<String> slot, or plan.primaryConcepts() will
        // silently return the wrong value and the concept-filter branch in selectRerankPool will never
        // be exercised (confirmed live: an earlier version of this helper had this bug).
        return new SearchQueryPlan(
                "zisk bank slovensko", "cs", "find_series", primaryConcepts, List.of(),
                List.of("SK"), List.of("ecb2", "eurostat", "fred"), List.of(), List.of(), null,
                List.of("bank profit Slovakia"), List.of("bank profit"), List.of(), List.of(), List.of(),
                List.of("primary"), new SearchQueryPlan.Clarification(false, null, null), "local_fallback", null);
    }
}
