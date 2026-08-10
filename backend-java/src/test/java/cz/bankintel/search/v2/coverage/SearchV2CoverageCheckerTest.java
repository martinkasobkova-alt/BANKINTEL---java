package cz.bankintel.search.v2.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SemanticDecision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2CoverageCheckerTest {

    private final SearchV2CoverageChecker checker = new SearchV2CoverageChecker();

    @Test
    void suggestedClarificationDoesNotHideAnOtherwiseValidResult() {
        SearchQueryPlan plan = plan(
                List.of("SK"),
                new SearchQueryPlan.Clarification(
                        true,
                        "Which insurance indicator do you want?",
                        "The topic contains multiple usable metrics."));
        SearchResult result = result(candidate("ecb2", "SK", "ICPF"));

        SearchV2CoverageChecker.CoverageResult coverage = checker.check(plan, List.of(result), "validated");

        assertThat(coverage.status()).isEqualTo("complete");
        assertThat(coverage.missingAspects()).isEmpty();
    }

    @Test
    void globalScopeDoesNotRequireAConcreteCandidateGeoCode() {
        var coverage = checker.check(plan(List.of("GLOBAL")), List.of(result(candidate("fred", "", "GDP"))), "validated");

        assertThat(coverage.status()).isEqualTo("complete");
        assertThat(coverage.missingAspects()).doesNotContain("requested_geography");
    }

    @Test
    void selectableDatasetGeoCountsAsCoverageWithoutAFixedTitleGeo() {
        var coverage = checker.check(
                plan(List.of("SK")),
                List.of(result(candidate("eurostat", "", "prc_hpi_q"))),
                "validated");

        assertThat(coverage.status()).isEqualTo("complete");
        assertThat(coverage.missingAspects()).doesNotContain("requested_geography");
    }

    private static SearchQueryPlan plan(List<String> geographies) {
        return plan(geographies, new SearchQueryPlan.Clarification(false, null, null));
    }

    private static SearchQueryPlan plan(
            List<String> geographies, SearchQueryPlan.Clarification clarification) {
        return new SearchQueryPlan(
                "test query",
                "en",
                "find_series",
                List.of("test_concept"),
                List.of(),
                geographies,
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of("test query"),
                List.of("test concept"),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                clarification,
                "openai",
                "test-model");
    }

    private static SearchCandidate candidate(String source, String geo, String dataset) {
        return new SearchCandidate(
                source + ":series",
                "series",
                "Test series",
                "",
                source,
                dataset,
                geo,
                "A",
                "PC",
                "",
                List.of("test_concept"),
                List.of(),
                List.of(),
                "",
                1.0,
                "test query",
                List.of(),
                Map.of("catalog_family", "macro"));
    }

    private static SearchResult result(SearchCandidate candidate) {
        SemanticDecision decision = new SemanticDecision(
                candidate.seriesId(),
                "keep",
                0.9,
                0.9,
                List.of("test_concept"),
                List.of(),
                "semantic match",
                "primary");
        return new SearchResult(candidate, decision, 1);
    }
}
