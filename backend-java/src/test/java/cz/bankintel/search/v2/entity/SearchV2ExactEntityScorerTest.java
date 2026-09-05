package cz.bankintel.search.v2.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.v2.entity.ExactEntityResolver.ResolutionResult;
import cz.bankintel.search.v2.schema.ExactEntityResolution;
import cz.bankintel.search.v2.schema.SearchCandidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2ExactEntityScorerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SearchV2SourceCapabilityRegistry capabilityRegistry = new SearchV2SourceCapabilityRegistry(objectMapper);
    private final ExactEntityResolver resolver =
            new ExactEntityResolver(objectMapper, capabilityRegistry, mock(CatalogIndexStore.class));
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

    /**
     * Živě zjištěno: appka pro obyčejné dataset kódy (bez katalogového ověření) natvrdo vrací
     * probable_entity - exactScore() proto pro ně vždycky vrátí 0.0, i když candidate.dataset()
     * doslova odpovídá tomu, co se vyřešilo. literalIdentifierMatches je nová, nezávislá metoda
     * BEZ týhle brány - dokazuje přesné oddělení: exactScore zůstává 0.0 (beze změny), ale
     * literalIdentifierMatches přesto pozná shodu.
     */
    @Test
    void literalIdentifierMatchesFiresRegardlessOfResolutionType() {
        ResolutionResult resolved = resolver.resolve("naio_10_pyp1620");
        assertThat(resolved.entityResolution().resolutionType()).isEqualTo("probable_entity");
        SearchCandidate exact = candidate("naio_10_pyp1620", "Obchodni a dopravni marze", "naio_10_pyp1620", Map.of());
        SearchCandidate unrelated = candidate("nama_10_gdp", "GDP", "nama_10_gdp", Map.of());

        assertThat(scorer.exactScore(resolved.entityResolution(), exact)).isEqualTo(0.0);
        assertThat(scorer.literalIdentifierMatches(resolved.entityResolution(), exact)).isTrue();
        assertThat(scorer.literalIdentifierMatches(resolved.entityResolution(), unrelated)).isFalse();
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
