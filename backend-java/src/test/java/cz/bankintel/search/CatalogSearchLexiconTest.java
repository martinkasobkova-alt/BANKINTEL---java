package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.bankintel.search.model.CatalogKeys;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CatalogSearchLexiconTest {

    @ParameterizedTest
    @CsvSource({
        "cena ropy, crude",
        "cena plynu, gas",
        "cena zlata, gold",
        "ropa brent, oil"
    })
    void commodityEnglishTermsFromStems(String query, String expectedFragment) {
        List<String> terms = CatalogSearchLexicon.commodityEnglishTerms(query);
        assertTrue(
                terms.stream().anyMatch(t -> t.toLowerCase().contains(expectedFragment)),
                query + " -> " + terms);
    }

    @Test
    void commodityTitleBonusPrefersCrudeOilOverGenericPrice() {
        int oil = CatalogSearchLexicon.commodityTitleBonus(
                "cena ropy", CatalogTextUtils.foldAscii("Crude oil, Brent, USD/barrel"));
        int noise = CatalogSearchLexicon.commodityTitleBonus(
                "cena ropy", CatalogTextUtils.foldAscii("Consumer price index, Czech Republic"));
        assertTrue(oil > noise, "oil=" + oil + " noise=" + noise);
    }

    @Test
    void genericTokenDeWeightApplies() {
        assertTrue(CatalogSearchLexicon.isGenericToken("price"));
        assertTrue(CatalogSearchLexicon.isGenericToken("index"));
        assertFalse(CatalogSearchLexicon.isGenericToken("unemployment"));
    }

    @Test
    void priceAndOilRelatedSurfacesSupportSemanticSlots() {
        assertTrue(CatalogSearchLexicon.relatedSurfaces("cena").contains("price"));
        assertTrue(CatalogSearchLexicon.relatedSurfaces("ropy").contains("crude oil"));
    }

    @Test
    void indexProbeTermsIncludeEnglishExpansion() {
        List<String> probe = CatalogSearchLexicon.buildIndexProbeTerms("inflace Cesko", 6);
        assertTrue(probe.stream().anyMatch(t -> t.toLowerCase().contains("inflation") || t.contains("hicp")));
    }
}
