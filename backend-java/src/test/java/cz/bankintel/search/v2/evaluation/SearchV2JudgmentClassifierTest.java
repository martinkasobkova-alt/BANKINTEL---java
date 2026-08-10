package cz.bankintel.search.v2.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchV2JudgmentClassifierTest {

    @Test
    void explicitSeriesIsNotMixedWithHeuristicLabels() {
        SearchV2GoldQuery query = gold(List.of("FEDFUNDS"), List.of("policy rate"), "", List.of());

        assertThat(SearchV2JudgmentClassifier.classify(query))
                .isEqualTo(SearchV2JudgmentClassifier.JudgmentType.EXPLICIT_GOLD_SERIES);
    }

    @Test
    void sourceConstraintIsRuleBasedWhenNoExactSeriesExists() {
        SearchV2GoldQuery query = gold(List.of(), List.of("exchange rate"), "ecb2", List.of());

        assertThat(SearchV2JudgmentClassifier.classify(query))
                .isEqualTo(SearchV2JudgmentClassifier.JudgmentType.RULE_BASED);
    }

    @Test
    void conceptFamilyWithoutExactSeriesIsHeuristic() {
        SearchV2GoldQuery query = gold(List.of(), List.of("inflation"), "", List.of("consumer prices"));

        assertThat(SearchV2JudgmentClassifier.classify(query))
                .isEqualTo(SearchV2JudgmentClassifier.JudgmentType.HEURISTIC);
    }

    private static SearchV2GoldQuery gold(
            List<String> relevantSeriesIds,
            List<String> expectedConcepts,
            String requiredSource,
            List<String> conceptFamilies) {
        return new SearchV2GoldQuery(
                "q",
                "query",
                "find_series",
                expectedConcepts,
                List.of(),
                requiredSource,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                relevantSeriesIds,
                conceptFamilies,
                List.of(),
                false,
                false,
                List.of(),
                "",
                "",
                List.of());
    }
}
