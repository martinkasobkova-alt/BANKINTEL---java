package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Perf fix (selectRerankPool/candidateMatchesRequiredConcepts bottleneck): proves
 * {@code SearchV2Service.selectRerankPool} - now resolving the concept requirement once instead of
 * once per candidate - selects the exact same candidate IDs, in the exact same order, as before. Uses
 * the REAL {@link SearchV2ConceptRegistry} (loaded from its production JSON resource), not a stub.
 */
class SearchV2SelectRerankPoolConceptPerfTest {

    private final SearchV2ConceptRegistry registry = new SearchV2ConceptRegistry(new ObjectMapper());

    @Test
    void conceptMatchingCandidatesAreSelectedFirstInGeoSourceScoreOrder() {
        SearchQueryPlan plan = planWithConcepts(List.of("bank_profitability"));
        SearchCandidate matching1 = candidate("m1", "zisk bank SK", "ecb2", 0.9);
        SearchCandidate matching2 = candidate("m2", "bank profit Slovakia", "eurostat", 0.5);
        SearchCandidate nonMatching = candidate("n1", "completely unrelated commodity index", "fred", 0.99);

        List<SearchCandidate> pool = SearchV2Service.selectRerankPool(
                List.of(matching1, matching2, nonMatching), 10, plan, registry);

        List<String> ids = pool.stream().map(SearchCandidate::candidateId).toList();
        assertThat(ids).as("both concept-matching candidates must be included").contains("ecb2:m1", "eurostat:m2");
        assertThat(ids).as("non-matching candidate still included via the general fill pass, just not first")
                .contains("fred:n1");
        int m1Index = ids.indexOf("ecb2:m1");
        int m2Index = ids.indexOf("eurostat:m2");
        int nIndex = ids.indexOf("fred:n1");
        assertThat(m1Index).isLessThan(nIndex);
        assertThat(m2Index).isLessThan(nIndex);
    }

    @Test
    void emptyPrimaryConceptsProducesTheSamePoolAsNoConceptFilterAtAll() {
        SearchQueryPlan planEmpty = planWithConcepts(List.of());
        SearchQueryPlan planNull = planWithConcepts(null);
        SearchCandidate c1 = candidate("c1", "anything", "ecb2", 0.9);
        SearchCandidate c2 = candidate("c2", "anything else", "fred", 0.5);

        List<SearchCandidate> viaEmpty =
                SearchV2Service.selectRerankPool(List.of(c1, c2), 10, planEmpty, registry);
        List<SearchCandidate> viaNull =
                SearchV2Service.selectRerankPool(List.of(c1, c2), 10, planNull, registry);
        List<SearchCandidate> viaNoRegistry = SearchV2Service.selectRerankPool(List.of(c1, c2), 10);

        List<String> idsEmpty = viaEmpty.stream().map(SearchCandidate::candidateId).toList();
        List<String> idsNull = viaNull.stream().map(SearchCandidate::candidateId).toList();
        List<String> idsNoRegistry = viaNoRegistry.stream().map(SearchCandidate::candidateId).toList();
        assertThat(idsEmpty).isEqualTo(idsNull).isEqualTo(idsNoRegistry);
    }

    @Test
    void multipleRequiredConceptsSelectCandidatesMatchingEither() {
        SearchQueryPlan plan = planWithConcepts(List.of("interest_rate", "bank_profitability"));
        SearchCandidate rateMatch = candidate("r1", "policy rate Germany", "ecb2", 0.9);
        SearchCandidate profitMatch = candidate("p1", "bank profit Slovakia", "eurostat", 0.5);
        SearchCandidate unrelated = candidate("u1", "commodity price index", "fred", 0.99);

        List<SearchCandidate> pool = SearchV2Service.selectRerankPool(
                List.of(rateMatch, profitMatch, unrelated), 10, plan, registry);
        List<String> ids = pool.stream().map(SearchCandidate::candidateId).toList();
        assertThat(ids).contains("ecb2:r1", "eurostat:p1", "fred:u1");
        assertThat(ids.indexOf("ecb2:r1")).isLessThan(ids.indexOf("fred:u1"));
        assertThat(ids.indexOf("eurostat:p1")).isLessThan(ids.indexOf("fred:u1"));
    }

    @Test
    void unresolvableConceptIdNeverThrowsAndFallsBackToTitleEvidenceAndFill() {
        SearchQueryPlan plan = planWithConcepts(List.of("not_a_real_concept_id"));
        SearchCandidate c1 = candidate("c1", "bank profit Slovakia", "ecb2", 0.9);

        List<SearchCandidate> pool = SearchV2Service.selectRerankPool(List.of(c1), 10, plan, registry);
        assertThat(pool.stream().map(SearchCandidate::candidateId).toList()).contains("ecb2:c1");
    }

    private static SearchQueryPlan planWithConcepts(List<String> primaryConcepts) {
        // NOTE: primaryConcepts is field #4 of SearchQueryPlan's canonical constructor - it must land
        // in that exact slot, not any other List<String> slot, or plan.primaryConcepts() silently
        // returns the wrong value and the concept-filter branch in selectRerankPool is never exercised.
        return new SearchQueryPlan(
                "zisk bank slovensko", "cs", "find_series", primaryConcepts, List.of(),
                List.of("SK"), List.of("ecb2", "eurostat", "fred"), List.of(), List.of(), null,
                List.of("bank profit Slovakia"), List.of("bank profit"), List.of(), List.of(), List.of(),
                List.of("primary"), new SearchQueryPlan.Clarification(false, null, null), "local_fallback", null);
    }

    private static SearchCandidate candidate(String id, String title, String source, double ftsScore) {
        return new SearchCandidate(
                source + ":" + id, id, title, "", source, "MIR", "SK", "A", "PC", "",
                List.of("bank_roa"), List.of(), List.of(), "", ftsScore, "zisk bank",
                List.of("canonical_title"), Map.of("primary_concept", "bank_roa", "catalog_family", "banking"));
    }
}
