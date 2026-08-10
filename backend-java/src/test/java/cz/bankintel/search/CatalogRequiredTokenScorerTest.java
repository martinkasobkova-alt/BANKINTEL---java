package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogRequiredTokenScorerTest {

    @Test
    void nezamestnanostHitsEnglishUnemploymentTitle() {
        String hay = CatalogTextUtils.foldAscii("Unemployment rate - total - % of labour force");
        assertTrue(
                CatalogRequiredTokenScorer.tokenHit(hay, "nezamestnanost", null),
                "CZ token must bridge to EN unemployment title");
    }

    @Test
    void shortAcronymRequiresTokenBoundary() {
        String falsePositiveHay = CatalogTextUtils.foldAscii("Croatia broad age abroad banking data");
        assertFalse(
                CatalogRequiredTokenScorer.tokenHit(falsePositiveHay, "roa", null),
                "ROA must not match inside Croatia/broad/abroad");

        String realRoaHay = CatalogTextUtils.foldAscii("Return on assets (ROA) banking sector");
        assertTrue(CatalogRequiredTokenScorer.tokenHit(realRoaHay, "roa", null));
    }

    @Test
    void requiredTokenBonusScalesWithRatioSquared() {
        int full = CatalogRequiredTokenScorer.requiredTokenBonus(2, 2, 2.0);
        int partial = CatalogRequiredTokenScorer.requiredTokenBonus(2, 1, 1.0);
        assertTrue(full > partial, "full=" + full + " partial=" + partial);
        assertTrue(full >= 480 + 90);
    }

    @Test
    void wildcardCountryAliasTokenMatchesResolvedGeoCode() {
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent("inflace madarsko");
        String hay = CatalogTextUtils.foldAscii("HICP annual rate of change Hungary HU");

        assertTrue(
                CatalogRequiredTokenScorer.tokenHit(hay, "madarsko", geo),
                "Wildcard geo aliases such as madarsk* must be resolved as country tokens.");
    }
}
