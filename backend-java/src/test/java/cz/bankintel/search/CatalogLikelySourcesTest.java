package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogLikelySourcesTest {

    @Test
    void eurusdIncludesEcb2() {
        List<String> sources = CatalogLikelySources.inferLikelyCatalogSources("eurusd");
        assertTrue(sources.contains("ecb2"), "expected ecb2 for FX query, got: " + sources);
    }

    @Test
    void ruleMatchedSourcesOmitDefaultFallbackOrder() {
        List<String> unknown = CatalogLikelySources.inferRuleMatchedCatalogSources("naprosto neurcity dotaz");
        assertTrue(unknown.isEmpty(), "rule-matched sources must not leak default source order: " + unknown);

        List<String> fx = CatalogLikelySources.inferRuleMatchedCatalogSources("eurusd");
        assertTrue(fx.contains("ecb2"), "expected ecb2 for FX rule: " + fx);
        assertTrue(fx.contains("fred"), "expected fred for FX rule: " + fx);
    }

    @Test
    void ziskBankPrefersBankingSources() {
        List<String> sources = CatalogLikelySources.inferLikelyCatalogSources("zisk bank");
        assertTrue(sources.contains("arad"), "expected arad: " + sources);
        assertTrue(sources.contains("ecb2"), "expected ecb2: " + sources);
    }

    @Test
    void roaBankUsesBankingRegexRuleBeforeDefaultSources() {
        List<String> sources = CatalogLikelySources.inferLikelyCatalogSources("roa bank");
        int ecb = sources.indexOf("ecb2");
        int csu = sources.indexOf("csu");
        assertTrue(ecb >= 0, "expected ecb2: " + sources);
        assertTrue(csu < 0 || ecb < csu, "ROA must route to banking sources before default CSU: " + sources);
    }

    @Test
    void sourceBoostWeightFavorsEarlierSources() {
        List<String> likely = List.of("arad", "ecb2", "eurostat");
        double arad = CatalogLikelySources.sourceBoostWeight("arad", likely);
        double ecb2 = CatalogLikelySources.sourceBoostWeight("ecb2", likely);
        assertTrue(arad > ecb2);
        assertTrue(arad >= 0.05);
    }

    @Test
    void inflaceCeskoPrefersCsuAndArad() {
        List<String> sources = CatalogLikelySources.inferLikelyCatalogSources("inflace Česko");
        assertTrue(sources.contains("csu") || sources.contains("arad"), "expected CZ inflation sources: " + sources);
    }
}
