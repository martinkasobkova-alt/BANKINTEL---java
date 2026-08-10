package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.CatalogMapSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogDeepSearchPromotionTest {

    @Test
    void rejectsUsFredRowForSlovakiaQuery() {
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent("nezamestnanost Slovensko");
        Map<String, Object> usRow = Map.of(
                CatalogKeys.TITLE,
                "Noncyclical Rate of Unemployment",
                CatalogKeys.SEARCH_SCORE,
                303,
                CatalogKeys.PREVIEW_STATUS,
                "unverified",
                CatalogKeys.SOURCE_TYPE,
                "fred",
                "territory",
                "USA / FRED",
                CatalogKeys.SET_ID,
                "NROU");
        assertFalse(CatalogDeepSearchPromotion.qualifiesForCatalogMatch(
                usRow, "nezamestnanost Slovensko", CatalogTextUtils.needlesFromQuery("nezamestnanost Slovensko"),
                CatalogGeoIntent.requestedGeoCodes(geo), true,
                CatalogSearchLexicon.primaryTopicTokens("nezamestnanost Slovensko", geo)));
    }

    @Test
    void rejectsGenericPriceMatchForOilQuery() {
        Map<String, Object> geo = Map.of();
        Map<String, Object> noise = Map.of(
                CatalogKeys.TITLE,
                "Crude steelmaking capacity",
                CatalogKeys.SEARCH_SCORE,
                80,
                CatalogKeys.PREVIEW_STATUS,
                "unverified",
                CatalogKeys.SOURCE_TYPE,
                "oecd4");
        assertFalse(CatalogDeepSearchPromotion.qualifiesForCatalogMatch(
                noise,
                "cena ropy",
                CatalogTextUtils.needlesFromQuery("cena ropy"),
                List.of(),
                false,
                CatalogSearchLexicon.primaryTopicTokens("cena ropy", geo)));
    }

    @Test
    void promotesCrudeOilOverNoiseWhenPreviewEmpty() {
        Map<String, Object> geo = Map.of();
        List<Map<String, Object>> possible = new ArrayList<>();
        possible.add(Map.of(
                CatalogKeys.TITLE, "Consumer price index",
                CatalogKeys.SEARCH_SCORE, 90,
                CatalogKeys.PREVIEW_STATUS, "unverified",
                CatalogKeys.SOURCE_TYPE, "oecd4",
                CatalogKeys.SET_ID, "cpi1"));
        possible.add(Map.of(
                CatalogKeys.TITLE, "Crude oil, Brent, USD/barrel",
                CatalogKeys.SEARCH_SCORE, 38,
                CatalogKeys.PREVIEW_STATUS, "unverified",
                CatalogKeys.SOURCE_TYPE, "commodities",
                CatalogKeys.SET_ID, "POILBRE"));
        List<Map<String, Object>> verified = new ArrayList<>();
        CatalogDeepSearchPromotion.promoteCatalogMatches("cena ropy", geo, possible, verified, 1);
        assertEquals(1, verified.size());
        assertEquals("candidate", verified.getFirst().get(CatalogKeys.STATUS));
        assertEquals("catalog_match", verified.getFirst().get(CatalogKeys.RESULT_TIER));
        assertTrue(CatalogMapSupport.str(verified.getFirst().get(CatalogKeys.TITLE)).toLowerCase().contains("brent"));
    }
}
