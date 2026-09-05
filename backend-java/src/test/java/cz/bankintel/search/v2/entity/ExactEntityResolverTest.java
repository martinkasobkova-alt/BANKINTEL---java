package cz.bankintel.search.v2.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.v2.entity.ExactEntityResolver.ResolutionResult;
import cz.bankintel.search.v2.schema.SearchQueryVariant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExactEntityResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SearchV2SourceCapabilityRegistry capabilityRegistry = new SearchV2SourceCapabilityRegistry(objectMapper);
    private final CatalogIndexStore catalogIndexStore = mock(CatalogIndexStore.class);
    private final ExactEntityResolver resolver =
            new ExactEntityResolver(objectMapper, capabilityRegistry, catalogIndexStore);

    @Test
    void resolvesNasdaq100AsMarketIndexWithoutBroadExpansion() {
        ResolutionResult result = resolver.resolve("nasdaq100");

        assertThat(result.entityResolution().resolutionType()).isEqualTo("exact_entity");
        assertThat(result.entityResolution().entityType()).isEqualTo("market_index");
        assertThat(result.entityResolution().canonicalName()).isEqualTo("NASDAQ-100");
        assertThat(result.entityResolution().allowBroadExpansion()).isFalse();
        assertThat(result.sourceRouting().selectedCatalogFamilies()).containsExactly("markets_indices");
        assertThat(result.sourceRouting().preferredSources()).contains("fred");
        assertThat(result.queryVariants()).extracting("role")
                .contains("original_exact", "canonical_name", "symbol", "exact_alias", "related_entity");
    }

    @Test
    void preservesTickerFxCommodityAndRatioEntities() {
        assertThat(resolver.resolve("AAPL").entityResolution().entityType()).isEqualTo("equity");
        assertThat(resolver.resolve("EUR/USD").entityResolution().entityType()).isEqualTo("fx_pair");
        assertThat(resolver.resolve("USDJPY").entityResolution().entityType()).isEqualTo("fx_pair");
        assertThat(resolver.resolve("Brent").entityResolution().entityType()).isEqualTo("commodity");
        assertThat(resolver.resolve("ROA bank").entityResolution().entityType()).isEqualTo("financial_ratio");
        assertThat(resolver.resolve("2T repo sazba").entityResolution().entityType()).isEqualTo("interest_rate");
    }

    @Test
    void resolvesFixedGeoEntitiesWithoutTurningMarketIndexesIntoCountryFilters() {
        ResolutionResult fed = resolver.resolve("urokove sazby Fed");
        ResolutionResult kb = resolver.resolve("akcie Komercni banka");
        ResolutionResult nasdaq = resolver.resolve("Nasdaq-100");

        assertThat(fed.entityResolution().canonicalName()).isEqualTo("Federal funds effective rate");
        assertThat(fed.entityResolution().attributes()).containsEntry("fixed_geo", "US");
        assertThat(fed.sourceRouting().preferredSources()).contains("fred");

        assertThat(kb.entityResolution().canonicalName()).isEqualTo("Komercni banka");
        assertThat(kb.entityResolution().attributes()).containsEntry("fixed_geo", "CZ");
        assertThat(kb.sourceRouting().preferredSources()).contains("stocks");

        assertThat(nasdaq.entityResolution().canonicalName()).isEqualTo("NASDAQ-100");
        assertThat(nasdaq.entityResolution().attributes()).doesNotContainKey("fixed_geo");
        assertThat(nasdaq.entityResolution().attributes()).containsEntry("market", "US");
    }

    @Test
    void infersRequestedReturnTypeForMarketIndexQueries() {
        ResolutionResult result = resolver.resolve("Nasdaq-100 total return");

        assertThat(result.entityResolution().canonicalName()).isEqualTo("NASDAQ-100");
        assertThat(result.entityResolution().attributes()).containsEntry("requested_return_type", "total_return");
    }

    @ParameterizedTest
    @CsvSource({
        "S&P 500, market_index, S&P 500, fred",
        "Nasdaq-100, market_index, NASDAQ-100, fred",
        "Dow Jones, market_index, Dow Jones Industrial Average, fred",
        "DAX, market_index, DAX, fred",
        "VIX, market_index, VIX, fred",
        "CEZ.PR, equity, CEZ, stocks",
        "AAPL, equity, Apple, stocks",
        "MSFT, equity, Microsoft, stocks",
        "EUR/USD, fx_pair, EUR/USD, ecb2",
        "CZK/EUR, fx_pair, CZK/EUR, ecb2",
        "USDJPY, fx_pair, USD/JPY, ecb2",
        "ROA bank, financial_ratio, Return on assets, ecb2",
        "ROE bank, financial_ratio, Return on equity, ecb2",
        "HICP Spain, economic_indicator, HICP, eurostat",
        "CPI Spain, economic_indicator, CPI, eurostat",
        "GDP Germany, economic_indicator, GDP, eurostat",
        "2T repo sazba, interest_rate, 2T repo sazba, arad",
        "Brent, commodity, Brent crude oil, fred",
        "gold price, commodity, Gold, fred",
        "natural gas price, commodity, Natural gas, fred"
    })
    void resolvesGeneralExactEntityFamilies(String query, String type, String canonical, String preferredSource) {
        ResolutionResult result = resolver.resolve(query);

        assertThat(result.entityResolution().resolutionType()).isIn("exact_entity", "probable_entity");
        assertThat(result.entityResolution().entityType()).isEqualTo(type);
        assertThat(result.entityResolution().canonicalName()).isEqualTo(canonical);
        assertThat(result.sourceRouting().preferredSources()).contains(preferredSource);
        assertThat(result.queryVariants())
                .filteredOn(SearchQueryVariant::firstPassExactRole)
                .extracting("role")
                .allMatch(role -> List.of("original_exact", "canonical_name", "exact_alias", "symbol", "translated_exact").contains(role));
    }

    @Test
    void siblingEntitiesRemainRelatedNotExactAliases() {
        ResolutionResult result = resolver.resolve("Nasdaq-100 index");

        List<String> firstPass = result.queryVariants().stream()
                .filter(variant -> variant.firstPassExactRole())
                .map(variant -> variant.text())
                .toList();

        assertThat(firstPass).doesNotContain("S&P 500", "VIX", "Dow Jones");
        assertThat(result.queryVariants())
                .filteredOn(variant -> "related_entity".equals(variant.role()))
                .extracting("text")
                .contains("S&P 500", "VIX");
    }

    @Test
    void unresolvedTopicFallsBackToOpenTopic() {
        assertThat(resolver.resolve("inflace spanelsko").entityResolution().resolutionType()).isEqualTo("open_topic");
    }

    /**
     * Živě zjištěno (2026-09-05): appka pro "naio_10_pyp1620" natvrdo vracela probable_entity/0.78
     * - LLM plánovač se pak vždycky zavolal a jednou si vymyslel "10-year yield", což zahodilo
     * všech 25 reálných kandidátů. Když se kód dá živě ověřit proti katalogu (tady zamockovanému),
     * appka to teď pozná jako exact_entity a LLM plánovač se vůbec nezavolá (viz
     * SearchV2QueryPlannerTest).
     */
    @Test
    void catalogVerifiedCodeBecomesExactEntityWithVerifiedSource() {
        when(catalogIndexStore.lookupRowIndexedOnly("eurostat", "naio_10_pyp1620"))
                .thenReturn(Optional.of(Map.of("set_id", "naio_10_pyp1620")));

        ResolutionResult result = resolver.resolve("naio_10_pyp1620");

        assertThat(result.entityResolution().resolutionType()).isEqualTo("exact_entity");
        assertThat(result.entityResolution().confidence()).isEqualTo(0.95);
        assertThat(result.entityResolution().allowBroadExpansion()).isFalse();
        assertThat(result.entityResolution().highConfidenceExact()).isTrue();
        assertThat(result.sourceRouting().preferredSources()).containsExactly("eurostat");
    }

    /** Same code shape, but nothing in the (mocked) catalog matches it - must fall through to
     * today's unchanged, conservative behavior, not fail or guess. */
    @Test
    void codeShapedButUnknownStringStaysProbableEntityUnchanged() {
        ResolutionResult result = resolver.resolve("naio_10_pyp1620");

        assertThat(result.entityResolution().resolutionType()).isEqualTo("probable_entity");
        assertThat(result.entityResolution().confidence()).isEqualTo(0.78);
        assertThat(result.entityResolution().allowBroadExpansion()).isTrue();
        assertThat(result.entityResolution().highConfidenceExact()).isFalse();
    }

    /**
     * The SERIES_CODE regex is tested against the query with ALL whitespace stripped, so an
     * ordinary sentence like "unemployment rate 2024" compacts to "unemploymentrate2024" and
     * matches the same shape a real dataset code would. Catalog verification must never even be
     * attempted for a query typed as more than one token - narrowing/skip-the-LLM behavior for a
     * real sentence would be a regression, not a fix, even if some source coincidentally has a
     * matching id.
     */
    @Test
    void multiWordQueryNeverTriggersCatalogVerificationEvenIfCodeShapedAfterStrippingWhitespace() {
        when(catalogIndexStore.lookupRowIndexedOnly(anyString(), anyString()))
                .thenReturn(Optional.of(Map.of("set_id", "unemploymentrate2024")));

        ResolutionResult result = resolver.resolve("unemployment rate 2024");

        assertThat(result.entityResolution().resolutionType()).isEqualTo("probable_entity");
        verify(catalogIndexStore, never()).lookupRowIndexedOnly(anyString(), anyString());
    }
}
