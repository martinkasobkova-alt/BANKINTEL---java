package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.schema.ExactEntityResolution;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SourceRoutingDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2RerankPoolSelectionTest {

    @Test
    void reservesLlmPoolSpaceForStrongTitleAndGeoEvidence() {
        List<SearchCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < 70; index++) {
            candidates.add(candidate(
                    "generic-" + index,
                    "Index finančních institucí " + index,
                    "net interest income SK",
                    "SK"));
        }
        SearchCandidate exact = candidate(
                "ecb-net-interest-income-sk",
                "Net interest income [full sample] · SK · Domestic banking groups",
                "net interest income SK",
                "SK");
        candidates.add(exact);

        List<SearchCandidate> pool = SearchV2Service.selectRerankPool(candidates, 60);

        assertThat(pool).hasSize(60).contains(exact);
        assertThat(pool.getFirst()).isEqualTo(exact);
    }

    @Test
    void reservesPoolSpaceForRegistryConceptEvidenceBeyondTheLexicalCutoff() {
        List<SearchCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < 90; index++) {
            candidates.add(candidate(
                    "generic-" + index,
                    "Financial institutions index " + index,
                    "financial institutions",
                    "SK"));
        }
        SearchCandidate misleading = candidate(
                "fred-non-interest-income",
                "Bank's Non-Interest Income to Total Income for Slovakia",
                "interest income",
                "SK",
                "fred");
        SearchCandidate exact = candidate(
                "ecb-interest-income-sk",
                "Interest income · SK · Domestic banking groups and stand-alone banks · A",
                "interest income",
                "SK");
        candidates.add(misleading);
        candidates.add(exact);

        SearchQueryPlan plan = new SearchQueryPlan(
                "urokove vynosy slovenskych bank",
                "cs",
                "find_series",
                List.of("net_interest_income"),
                List.of(),
                List.of("SK"),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of("interest income"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "openai",
                "test",
                ExactEntityResolution.openTopic("test"),
                new SourceRoutingDecision(List.of("banking"), List.of("ecb2", "fred"), List.of(), Map.of()),
                List.of());

        List<SearchCandidate> pool = SearchV2Service.selectRerankPool(
                candidates, 60, plan, new SearchV2ConceptRegistry(new ObjectMapper()));

        assertThat(pool).hasSize(60).contains(exact);
        assertThat(pool.indexOf(exact)).isLessThan(pool.indexOf(misleading));
    }

    @Test
    void sourceRoutingMakesRequestOrderIrrelevant() {
        SearchQueryPlan plan = planWithPreferredSources(List.of("ecb2", "eurostat", "bis"));
        List<String> alphabetical = List.of("arad", "bis", "csu", "ecb2", "eurostat", "fred", "imf", "oecd4");
        List<String> reversed = List.of("oecd4", "imf", "fred", "eurostat", "ecb2", "csu", "bis", "arad");

        assertThat(SearchV2Service.prioritizeAllowedSources(alphabetical, plan))
                .containsExactlyElementsOf(SearchV2Service.prioritizeAllowedSources(reversed, plan))
                .startsWith("ecb2", "eurostat", "bis");
    }

    @Test
    void ambiguousPlanReservesSpaceAcrossSourcesAndMeaningBranches() {
        List<SearchCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            candidates.add(candidate(
                    "eurostat-" + index,
                    "Generic insurance indicator " + index,
                    "insurance sector count Slovakia",
                    "SK",
                    "eurostat"));
        }
        SearchCandidate assets = candidate(
                "ecb-insurance-assets",
                "Insurance corporations total assets · SK",
                "insurance company assets Slovakia",
                "SK",
                "ecb2");
        SearchCandidate premiums = candidate(
                "oecd-insurance-premiums",
                "Insurance premiums · Slovakia",
                "insurance premiums Slovakia",
                "SK",
                "oecd4");
        candidates.add(assets);
        candidates.add(premiums);

        SearchQueryPlan plan = new SearchQueryPlan(
                "stav pojistoven na slovensku",
                "cs",
                "ambiguous",
                List.of("insurance sector"),
                List.of(),
                List.of("SK"),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of("insurance company assets Slovakia", "insurance premiums Slovakia"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(true, "Který ukazatel?", "Several measurable meanings."),
                "openai",
                "test",
                ExactEntityResolution.openTopic("test"),
                new SourceRoutingDecision(List.of(), List.of("eurostat", "ecb2", "oecd4"), List.of(), Map.of()),
                List.of());

        List<SearchCandidate> pool = SearchV2Service.selectRerankPool(
                candidates, 12, plan, new SearchV2ConceptRegistry(new ObjectMapper()));

        assertThat(pool).contains(assets, premiums);
    }

    private static SearchQueryPlan planWithPreferredSources(List<String> preferredSources) {
        return new SearchQueryPlan(
                "obecny dotaz",
                "cs",
                "find_series",
                List.of("net_interest_income"),
                List.of(),
                List.of("SK"),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of("interest income"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "openai",
                "test",
                ExactEntityResolution.openTopic("test"),
                new SourceRoutingDecision(List.of("banking"), preferredSources, List.of(), Map.of()),
                List.of());
    }

    private static SearchCandidate candidate(String id, String title, String matchedQuery, String geo) {
        return candidate(id, title, matchedQuery, geo, "ecb2");
    }

    private static SearchCandidate candidate(String id, String title, String matchedQuery, String geo, String source) {
        return new SearchCandidate(
                id,
                id,
                title,
                "",
                source,
                "CBD",
                geo,
                "A",
                "EUR",
                "",
                List.of(),
                List.of(),
                List.of(),
                "2025",
                0.0,
                matchedQuery,
                List.of("canonical_title"),
                Map.of());
    }
}
