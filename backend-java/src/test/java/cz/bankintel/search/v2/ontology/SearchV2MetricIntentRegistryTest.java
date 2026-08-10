package cz.bankintel.search.v2.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SearchV2MetricIntentRegistryTest {

    private final SearchV2MetricIntentRegistry registry = new SearchV2MetricIntentRegistry(new ObjectMapper());

    @Test
    void resolvesDebtClusterFromCzechIndebtednessWord() {
        assertThat(registry.resolve("zadluzeni domacnosti")).isEqualTo("debt");
    }

    @Test
    void resolvesDebtClusterFromEnglishLoansWord() {
        assertThat(registry.resolve("household loans outstanding")).isEqualTo("debt");
    }

    @Test
    void resolvesProfitabilityClusterFromCzechZiskovost() {
        assertThat(registry.resolve("ziskovost pojistoven spanelsko")).isEqualTo("profitability");
    }

    @Test
    void unrecognizedMetricResolvesToBlankNotAnException() {
        assertThat(registry.resolve("elektrifikace vesnic")).isBlank();
    }

    @Test
    void aliasesForResolvedMetricExpandsToTheWholeClusterIncludingWordsNotInTheOriginalQuery() {
        var aliases = registry.aliasesForResolvedMetric("zadluzeni domacnosti");

        assertThat(aliases)
                .as("must include synonyms the query never used, so a candidate titled 'uvery' or "
                        + "'dluh' can still be recognized as the same metric")
                .contains("uvery", "dluh", "loans", "liabilities");
    }

    @Test
    void aliasesForUnrecognizedMetricIsEmpty() {
        assertThat(registry.aliasesForResolvedMetric("elektrifikace vesnic")).isEmpty();
    }
}
