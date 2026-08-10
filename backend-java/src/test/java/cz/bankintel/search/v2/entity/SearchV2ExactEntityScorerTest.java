package cz.bankintel.search.v2.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.v2.entity.ExactEntityResolver.ResolutionResult;
import cz.bankintel.search.v2.schema.SearchCandidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2ExactEntityScorerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SearchV2SourceCapabilityRegistry capabilityRegistry = new SearchV2SourceCapabilityRegistry(objectMapper);
    private final ExactEntityResolver resolver = new ExactEntityResolver(objectMapper, capabilityRegistry);
    private final SearchV2ExactEntityScorer scorer = new SearchV2ExactEntityScorer();

    @Test
    void exactResultProducesStrongEvidenceWithoutOverridingSemanticDecision() {
        ResolutionResult resolved = resolver.resolve("nasdaq100");
        SearchCandidate exact = candidate("NASDAQ100", "NASDAQ-100", "NASDAQ100", Map.of(
                "canonical_title_en", "NASDAQ-100",
                "catalog_family", "markets_indices"));
        SearchCandidate sibling = candidate("SP500", "S&P 500", "SP500", Map.of(
                "canonical_title_en", "S&P 500",
                "catalog_family", "markets_indices"));

        assertThat(scorer.exactScore(resolved.entityResolution(), exact)).isGreaterThanOrEqualTo(0.82);
        assertThat(scorer.exactScore(resolved.entityResolution(), sibling)).isLessThan(0.50);
    }

    @Test
    void siblingMarketIndexDoesNotReceiveExactScoreForNasdaq() {
        ResolutionResult resolved = resolver.resolve("nasdaq100");
        SearchCandidate sibling = candidate("SP500", "S&P 500", "SP500", Map.of("canonical_title_en", "S&P 500"));

        assertThat(scorer.exactScore(resolved.entityResolution(), sibling)).isLessThan(0.50);
    }

    @Test
    void exactTickerIdentifierBeatsDerivativeAliasMatch() {
        ResolutionResult resolved = resolver.resolve("AAPL");
        SearchCandidate direct = candidate("AAPL", "Apple Inc.", "AAPL", Map.of("canonical_title_en", "Apple Inc."));
        SearchCandidate derivative = candidate("AAPL19.BK", "AAPL19_DR AAPL#YUANTA", "AAPL19.BK", Map.of(
                "canonical_title_en", "AAPL19 depositary receipt"));

        assertThat(scorer.exactScore(resolved.entityResolution(), direct))
                .isGreaterThan(scorer.exactScore(resolved.entityResolution(), derivative));
        assertThat(scorer.exactScore(resolved.entityResolution(), direct)).isEqualTo(1.0);
    }

    @Test
    void substringFalsePositiveProducesWeakExactEntityEvidence() {
        ResolutionResult resolved = resolver.resolve("DAX");
        SearchCandidate falsePositive = candidate("NASDAQCX50PI", "Index Copenhagen", "NASDAQCX50PI", Map.of(
                "canonical_title_en", "Copenhagen industry index",
                "original_title", "NASDAQ Copenhagen industry index"));

        assertThat(scorer.exactScore(resolved.entityResolution(), falsePositive)).isLessThan(0.50);
    }

    @Test
    void requestedTotalReturnScoresAbovePriceIndexForNamedMarketIndex() {
        ResolutionResult resolved = resolver.resolve("Nasdaq-100 total return");
        SearchCandidate price = candidate("NASDAQ100", "NASDAQ-100", "NASDAQ100", Map.of(
                "canonical_title_en", "NASDAQ-100"));
        SearchCandidate totalReturn = candidate("NASDAQXNDX", "NASDAQ-100 Total Return Index", "NASDAQXNDX", Map.of(
                "canonical_title_en", "NASDAQ-100 Total Return Index",
                "return_type", "total_return"));
        SearchCandidate hedgedEsg = candidate("NASDAQNDXG12MH", "Nasdaq-100 ESG Total Return CAD", "NASDAQNDXG12MH", Map.of(
                "canonical_title_en", "Nasdaq-100 Environmental, Social and Governance Total Return Currency Hedged Canadian Dollar Index"));

        assertThat(scorer.exactScore(resolved.entityResolution(), totalReturn))
                .isGreaterThan(scorer.exactScore(resolved.entityResolution(), price));
        assertThat(scorer.exactScore(resolved.entityResolution(), totalReturn))
                .isGreaterThan(scorer.exactScore(resolved.entityResolution(), hedgedEsg));
        assertThat(scorer.exactScore(resolved.entityResolution(), price)).isLessThan(0.82);
    }

    @Test
    void commodityMarketPriceDoesNotProtectEquityOrIndustryIndex() {
        ResolutionResult resolved = resolver.resolve("natural gas price");
        SearchCandidate price = candidate("PNGASEUUSDM", "Global price of Natural gas, EU", "PNGASEUUSDM", Map.of(
                "canonical_title_en", "Global price of Natural gas, EU",
                "catalog_family", "commodities"));
        SearchCandidate equityIndex = candidate("NASDAQFUM", "Natural gas industry index", "NASDAQFUM", Map.of(
                "canonical_title_en", "Natural gas industry index",
                "catalog_family", "markets_indices"));

        assertThat(scorer.exactScore(resolved.entityResolution(), price)).isGreaterThan(0.82);
        assertThat(scorer.exactScore(resolved.entityResolution(), equityIndex)).isLessThan(0.50);
    }

    private static SearchCandidate candidate(String id, String title, String dataset, Map<String, Object> raw) {
        return new SearchCandidate(
                "fred:" + id.toLowerCase(),
                id,
                title,
                "",
                "fred",
                dataset,
                "",
                "D",
                "Index",
                "",
                List.of("equity"),
                List.of("equity"),
                List.of(),
                "",
                -10.0,
                "nasdaq100",
                List.of(),
                raw);
    }
}
