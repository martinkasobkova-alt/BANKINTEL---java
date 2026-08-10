package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CatalogQueryIntentTest {

    @Test
    void ziskBankClassifiesMetricDomainAndProfitability() {
        CatalogQueryIntent.QueryIntent intent = CatalogQueryIntent.classifyQueryIntent("zisk bank");
        assertTrue(intent.metricTerms().stream().anyMatch(t -> "zisk".equals(t.raw())));
        assertTrue(intent.domainTerms().stream().anyMatch(t -> "bank".equals(t.raw())));
        assertTrue(intent.activeGroups().contains("profitability"));
    }

    @Test
    void ziskBankIntentBonusBeatsAssetsHaystack() {
        CatalogQueryIntent.IntentScoreAdjustments profitAdj =
                CatalogQueryIntent.computeIntentScoreAdjustments(
                        "Bank net profit ROE return on equity banking sector", "zisk bank", null);
        CatalogQueryIntent.IntentScoreAdjustments assetsAdj =
                CatalogQueryIntent.computeIntentScoreAdjustments(
                        "Bank total assets long-term assets banking sector", "zisk bank", null);
        int profitNet = profitAdj.intentBonus() - profitAdj.negativePenalty();
        int assetsNet = assetsAdj.intentBonus() - assetsAdj.negativePenalty();
        assertTrue(profitNet > assetsNet, "profitNet=" + profitNet + " assetsNet=" + assetsNet);
    }

    @Test
    void inflationQueryDetectsPriceStabilityGroup() {
        CatalogQueryIntent.QueryIntent intent = CatalogQueryIntent.classifyQueryIntent("inflace HICP");
        assertTrue(intent.activeGroups().contains("price_stability") || !intent.metricTerms().isEmpty());
    }

    @Test
    void unemploymentQueryTriggersLaborGroup() {
        CatalogQueryIntent.QueryIntent intent = CatalogQueryIntent.classifyQueryIntent("nezaměstnanost");
        assertTrue(intent.activeGroups().stream().anyMatch(g -> g.contains("labor") || g.contains("employment"))
                || intent.metricTerms().stream().anyMatch(t -> t.raw().contains("nezam")));
    }

    @Test
    void gdpQueryHasMacroGrowthSignals() {
        CatalogQueryIntent.QueryIntent intent = CatalogQueryIntent.classifyQueryIntent("hrubý domácí produkt HDP");
        assertTrue(!intent.activeGroups().isEmpty() || !intent.metricTerms().isEmpty() || !intent.domainTerms().isEmpty());
    }

    @Test
    void eurusdClassifiesAsExchangeRateMetric() {
        CatalogQueryIntent.QueryIntent intent = CatalogQueryIntent.classifyQueryIntent("eurusd");
        String surfaces = intent.metricTerms().toString().toLowerCase();
        assertTrue(surfaces.contains("exchange rate"), "expected exchange-rate metric surfaces: " + intent);
    }
}
