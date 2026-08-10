package cz.bankintel.search.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ForecastPredictorConfigTest {

    @Test
    void resolvesBankReturnOnEquityAsBankingProfitabilityNotEquityIndex() {
        ForecastPredictorConfig.Domain domain = ForecastPredictorConfig.get()
                .resolveDomain("Return on equity of banks")
                .orElseThrow();

        assertEquals("banking_profitability", domain.key());
    }

    private final ForecastPredictorConfig config = ForecastPredictorConfig.get();

    @Test
    void resolvesPriceInflationDomainForCzechInflationQuery() {
        Optional<ForecastPredictorConfig.Domain> domain = config.resolveDomain("Inflace CR (HICP rocni zmena)");
        assertTrue(domain.isPresent());
        assertEquals("price_inflation", domain.get().key());
    }

    @Test
    void resolvesGdpGrowthDomainForEnglishGdpSeriesName() {
        // Regression: "Real GDP Poland (seasonally adjusted)" must resolve to gdp_growth even
        // though it doesn't contain the word "growth" — narrow match_terms previously required it.
        Optional<ForecastPredictorConfig.Domain> domain = config.resolveDomain("Real GDP Poland (seasonally adjusted)");
        assertTrue(domain.isPresent());
        assertEquals("gdp_growth", domain.get().key());
    }

    @Test
    void resolvesPolicyRateDomainForCnbRepoRate() {
        Optional<ForecastPredictorConfig.Domain> domain = config.resolveDomain("2T repo sazba CNB");
        assertTrue(domain.isPresent());
        assertEquals("policy_rate", domain.get().key());
    }

    @Test
    void returnsEmptyForUnrelatedLabel() {
        Optional<ForecastPredictorConfig.Domain> domain = config.resolveDomain("Počet obyvatel obce Kunratice");
        assertFalse(domain.isPresent());
    }

    @Test
    void everyDomainExposesAtLeastOnePredictor() {
        for (ForecastPredictorConfig.Domain domain : config.domains()) {
            assertFalse(domain.predictors().isEmpty(), "domain " + domain.key() + " has no predictors");
        }
    }

    @Test
    void resolvesWagesDomain() {
        Optional<ForecastPredictorConfig.Domain> domain = config.resolveDomain("Průměrná hrubá mzda ČR");
        assertTrue(domain.isPresent());
        assertEquals("wages", domain.get().key());
    }

    @Test
    void resolvesUnemploymentDomain() {
        Optional<ForecastPredictorConfig.Domain> domain = config.resolveDomain("Obecná míra nezaměstnanosti V4");
        assertTrue(domain.isPresent());
        assertEquals("unemployment", domain.get().key());
    }

    @Test
    void resolvesGeneralCommodityPricesDomainDistinctFromGold() {
        Optional<ForecastPredictorConfig.Domain> gold = config.resolveDomain("Gold price XAU/USD");
        assertTrue(gold.isPresent());
        assertEquals("commodity_gold", gold.get().key());

        Optional<ForecastPredictorConfig.Domain> oil = config.resolveDomain("Brent crude oil commodity price");
        assertTrue(oil.isPresent());
        assertEquals("commodity_prices", oil.get().key());
    }

    @Test
    void everyDomainExposesOntologyMetadataForFeatureDiscovery() {
        for (ForecastPredictorConfig.Domain domain : config.domains()) {
            assertFalse(domain.commonLags().isEmpty(), "domain " + domain.key() + " has no common_lags");
            assertFalse(domain.preferredFrequency().isBlank(), "domain " + domain.key() + " has no preferred_frequency");
            for (ForecastPredictorConfig.Predictor predictor : domain.predictors()) {
                assertFalse(predictor.valueKind().isBlank(), "predictor " + predictor.role() + " in domain " + domain.key() + " has no value_kind");
            }
        }
    }
}
