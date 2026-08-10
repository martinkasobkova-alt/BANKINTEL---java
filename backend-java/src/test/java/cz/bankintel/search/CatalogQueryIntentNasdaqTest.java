package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogQueryIntentNasdaqTest {

    @Test
    void nasdaqCenaDoesNotActivatePriceInflationWithoutExplicitInflation() {
        CatalogQueryIntent.QueryIntent intent = CatalogQueryIntent.classifyQueryIntent("nasdaq cena");
        assertTrue(
                !intent.activeGroups().contains("price_inflation"),
                "equity query should not keep CPI price_inflation: " + intent.activeGroups());
    }

    @Test
    void nasdaq100ScoresHigherThanObscurePriceIndexWithNasdaqCenaQuery() {
        String query = "nasdaq cena";
        List<String> needles = CatalogTextUtils.needlesFromQuery(query);
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);

        int nasdaq100 = CatalogCompositeScorer.scoreWithLikelySources(
                "fred",
                query,
                Map.of(
                        "set_id", "NASDAQ100",
                        "name", "NASDAQ-100",
                        "search_blob", "NASDAQ-100 NASDAQ 100 Index"),
                needles,
                10,
                CatalogTextUtils.titleMatchScore(CatalogTextUtils.foldAscii("NASDAQ-100"), needles),
                List.of("fred"),
                geo,
                CatalogGeoIntent.requestedGeoCodes(geo));

        int obscure = CatalogCompositeScorer.scoreWithLikelySources(
                "fred",
                query,
                Map.of(
                        "set_id", "NASDAQIFEDLV",
                        "name", "Nasdaq IFED US Large-Cap Low Volatility Index Price Return",
                        "search_blob", "Nasdaq IFED US Large-Cap Low Volatility Index Price Return"),
                needles,
                10,
                CatalogTextUtils.titleMatchScore(
                        CatalogTextUtils.foldAscii(
                                "Nasdaq IFED US Large-Cap Low Volatility Index Price Return"),
                        needles),
                List.of("fred"),
                geo,
                CatalogGeoIntent.requestedGeoCodes(geo));

        assertTrue(nasdaq100 > obscure, "nasdaq100=" + nasdaq100 + " obscure=" + obscure);
    }
}
