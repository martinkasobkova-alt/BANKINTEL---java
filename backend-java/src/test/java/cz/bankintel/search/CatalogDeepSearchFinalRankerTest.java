package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.bankintel.search.model.CatalogKeys;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogDeepSearchFinalRankerTest {

    @Test
    void ranksTopicFitAboveLivePreviewOnlyRows() {
        String query = "zisk bank slovensko";
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        List<Map<String, Object>> verified = List.of(
                row("eurostat", "tipsbd40", "Return on equity of banks", 143, "verified"),
                row("eurostat", "tipsbd30", "Tier-1 capital ratio banking sector", 143, "verified"),
                row(
                        "eurostat",
                        "tipsbd20",
                        "Consolidated banking leverage, domestic and foreign entities",
                        140,
                        "verified"),
                row(
                        "fred",
                        "BABANAICS11NSAUS",
                        "Business Applications: Agriculture in the United States",
                        0,
                        "verified"),
                row(
                        "eurostat",
                        "ilc_li06",
                        "At-risk-of-poverty rate by poverty threshold and household work intensity",
                        135,
                        "verified"));
        List<Map<String, Object>> possible = List.of(
                row(
                        "ecb2",
                        "AME/A.EA20.1.0.0.0.ZUTN",
                        "Euro area unemployment rate - total",
                        0,
                        "candidate"));

        CatalogDeepSearchFinalRanker.RankedBuckets ranked =
                CatalogDeepSearchFinalRanker.rank(query, geo, verified, possible);

        assertEquals("tipsbd40", ranked.verified().getFirst().get(CatalogKeys.SET_ID));
        assertEquals(1, ranked.verified().getFirst().get("final_rank"));
        Map<String, Object> poverty = ranked.verified().stream()
                .filter(row -> "ilc_li06".equals(row.get(CatalogKeys.SET_ID)))
                .findFirst()
                .orElseThrow();
        Map<String, Object> bankingLeverage = ranked.verified().stream()
                .filter(row -> "tipsbd20".equals(row.get(CatalogKeys.SET_ID)))
                .findFirst()
                .orElseThrow();
        Map<String, Object> fredBusinessApplications = ranked.verified().stream()
                .filter(row -> "BABANAICS11NSAUS".equals(row.get(CatalogKeys.SET_ID)))
                .findFirst()
                .orElseThrow();
        assertFalse(Boolean.TRUE.equals(poverty.get("topic_match")));
        assertEquals("mismatch", poverty.get("semantic_match_level"));
        assertTrue((Integer) poverty.get("final_rank") > (Integer) ranked.verified().getFirst().get("final_rank"));
        assertEquals("mismatch", fredBusinessApplications.get("semantic_match_level"));
        assertTrue(
                (Integer) fredBusinessApplications.get("final_rank") > (Integer) bankingLeverage.get("final_rank"),
                fredBusinessApplications + " vs " + bankingLeverage);
    }

    @Test
    void livePreviewMismatchIsNotSemanticallyActionable() {
        String query = "roa bank";
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        List<Map<String, Object>> verified = List.of(row(
                "fred",
                "NASDAQNQDMEU3020",
                "Nasdaq Developed Markets: Europe Financial Services Index",
                41,
                "verified"));
        List<Map<String, Object>> possible = List.of(row(
                "ecb2",
                "CBD2/A.U2.W0.11._Z._Z.A.A.I2004._Z._Z._Z._Z._Z._Z.PC",
                "Return on assets (ROA) · U2 · Domestic banking groups and stand-alone banks · A (%)",
                1112,
                "candidate"));

        CatalogDeepSearchFinalRanker.RankedBuckets ranked =
                CatalogDeepSearchFinalRanker.rank(query, geo, verified, possible);

        Map<String, Object> fred = findBySetId(ranked.verified(), "NASDAQNQDMEU3020");
        Map<String, Object> ecb = findBySetId(
                ranked.possible(), "CBD2/A.U2.W0.11._Z._Z.A.A.I2004._Z._Z._Z._Z._Z._Z.PC");
        assertFalse(CatalogDeepSearchFinalRanker.isSemanticallyActionable(fred), fred.toString());
        assertTrue(CatalogDeepSearchFinalRanker.isSemanticallyActionable(ecb), ecb.toString());
        assertTrue((Integer) ecb.get("final_rank") < (Integer) fred.get("final_rank"), ecb + " vs " + fred);
    }

    @Test
    void ranksAvailableCommodityPriceRowsAboveUnavailableCandidateRows() {
        String query = "cena ropy";
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        List<Map<String, Object>> verified = List.of(row(
                "imf",
                "GX161.G2M_S13_POGDP_PT",
                "Vyrobci ropy - Expenditure, General government, Percent of GDP",
                520,
                "verified"));
        List<Map<String, Object>> possible = List.of(
                row("fred", "ACOILBRENTEU", "Crude Oil Prices: Brent - Europe", 10, "candidate"),
                rowWithUnit("commodities", "CRUDE_OIL_BRENT", "Crude oil, Brent", "($/bbl)", 10, "verified"),
                row(
                        "ecb2",
                        "STBS/M.I10.N.IMPX.2B0600.4D0.N.IX",
                        "Import price index (extra euro area) - Extraction of crude petroleum and natural gas",
                        1161,
                        "candidate"),
                row(
                        "imf",
                        "IMF|IMF.RES|WEO|9.0.0|G001.POLVOIL",
                        "World - Olive oil, Unit prices, US dollars per metric tonne",
                        1161,
                        "candidate"),
                row(
                        "oecd4",
                        "economic_outlook_ltb/CZE/SHARE_OIL/_/A",
                        "Share of oil in total primary energy supply, economic scenarios",
                        953,
                        "candidate"));

        CatalogDeepSearchFinalRanker.RankedBuckets ranked =
                CatalogDeepSearchFinalRanker.rank(query, geo, verified, possible);

        Map<String, Object> fred = findBySetId(ranked.possible(), "ACOILBRENTEU");
        Map<String, Object> pinkSheet = findBySetId(ranked.possible(), "CRUDE_OIL_BRENT");
        Map<String, Object> imf = findBySetId(ranked.verified(), "GX161.G2M_S13_POGDP_PT");
        Map<String, Object> ecb = findBySetId(ranked.possible(), "STBS/M.I10.N.IMPX.2B0600.4D0.N.IX");
        Map<String, Object> oliveOil = findBySetId(ranked.possible(), "IMF|IMF.RES|WEO|9.0.0|G001.POLVOIL");
        Map<String, Object> scenario = findBySetId(ranked.possible(), "economic_outlook_ltb/CZE/SHARE_OIL/_/A");

        assertEquals("exact", fred.get("semantic_match_level"));
        assertTrue(Boolean.TRUE.equals(fred.get("metric_match")), fred.toString());
        assertEquals("exact", pinkSheet.get("semantic_match_level"));
        assertTrue(Boolean.TRUE.equals(pinkSheet.get("metric_match")), pinkSheet.toString());
        assertEquals("partial", imf.get("semantic_match_level"));
        assertFalse(Boolean.TRUE.equals(imf.get("metric_match")), imf.toString());
        assertFalse("exact".equals(scenario.get("semantic_match_level")), scenario.toString());
        assertFalse(Boolean.TRUE.equals(scenario.get("metric_match")), scenario.toString());
        assertEquals("partial", oliveOil.get("semantic_match_level"));
        assertTrue((Integer) fred.get("final_rank") < (Integer) imf.get("final_rank"));
        assertTrue((Integer) pinkSheet.get("final_rank") < (Integer) imf.get("final_rank"));
        assertTrue((Integer) fred.get("final_rank") < (Integer) scenario.get("final_rank"));
        assertTrue((Integer) fred.get("final_rank") < (Integer) ecb.get("final_rank"));
        assertTrue((Integer) pinkSheet.get("final_rank") < (Integer) ecb.get("final_rank"));
        assertTrue((Integer) fred.get("final_rank") < (Integer) oliveOil.get("final_rank"));
    }

    @Test
    void verifiedPreviewOutranksExactButUnavailableCandidates() {
        String query = "cena ropy";
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        List<Map<String, Object>> verified = List.of(
                rowWithUnit("commodities", "CRUDE_OIL_BRENT", "Crude oil, Brent", "($/bbl)", 15, "verified"));
        List<Map<String, Object>> possible = List.of(
                row("fred", "ACOILBRENTEU", "Crude Oil Prices: Brent - Europe", 900, "candidate"),
                row("oecd4", "economic_outlook_117/OECD/WPBRENT/_/A", "Crude oil price, FOB, USD per barrel, spot Brent (A) · OECD", 900, "candidate"));

        CatalogDeepSearchFinalRanker.RankedBuckets ranked =
                CatalogDeepSearchFinalRanker.rank(query, geo, verified, possible);

        Map<String, Object> brent = findBySetId(ranked.verified(), "CRUDE_OIL_BRENT");
        Map<String, Object> fred = findBySetId(ranked.possible(), "ACOILBRENTEU");
        Map<String, Object> oecd = findBySetId(ranked.possible(), "economic_outlook_117/OECD/WPBRENT/_/A");
        assertTrue((Integer) brent.get("final_rank") < (Integer) fred.get("final_rank"), brent + " vs " + fred);
        assertTrue((Integer) brent.get("final_rank") < (Integer) oecd.get("final_rank"), brent + " vs " + oecd);
    }

    @Test
    void commodityQueryRequiresBothPriceMetricAndCommodityDomainForActionableVerifiedRows() {
        String query = "cena ropy";
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        List<Map<String, Object>> verified = List.of(
                rowWithUnit("commodities", "CRUDE_OIL_BRENT", "Crude oil, Brent", "($/bbl)", 300, "verified"),
                row("bis", "BIS|WS_CPP|Q.AR", "Argentina - Commercial property prices", 300, "verified"));

        CatalogDeepSearchFinalRanker.RankedBuckets ranked =
                CatalogDeepSearchFinalRanker.rank(query, geo, verified, List.of());

        Map<String, Object> brent = findBySetId(ranked.verified(), "CRUDE_OIL_BRENT");
        Map<String, Object> propertyPrices = findBySetId(ranked.verified(), "BIS|WS_CPP|Q.AR");
        assertTrue(CatalogDeepSearchFinalRanker.isSemanticallyActionable(brent), brent.toString());
        assertFalse(CatalogDeepSearchFinalRanker.isSemanticallyActionable(propertyPrices), propertyPrices.toString());
        assertFalse(Boolean.TRUE.equals(propertyPrices.get("domain_match")), propertyPrices.toString());
    }

    @Test
    void inflationQueryDemotesCpiDeflatedExchangeRates() {
        String query = "inflace spanelsko";
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        List<Map<String, Object>> verified = List.of(
                row("eurostat", "prc_hicp_midx", "HICP - monthly data (index) (1996-2025)", 320, "verified"),
                row(
                        "ecb2",
                        "EXR/A.E01.AUD.ERC0.A",
                        "Real effective exch. rate CPI deflated - Australian dollar",
                        320,
                        "verified"));

        CatalogDeepSearchFinalRanker.RankedBuckets ranked =
                CatalogDeepSearchFinalRanker.rank(query, geo, verified, List.of());

        Map<String, Object> hicp = findBySetId(ranked.verified(), "prc_hicp_midx");
        Map<String, Object> exr = findBySetId(ranked.verified(), "EXR/A.E01.AUD.ERC0.A");
        assertTrue((Integer) hicp.get("final_rank") < (Integer) exr.get("final_rank"), hicp + " vs " + exr);
        assertTrue(Boolean.TRUE.equals(exr.get("negative_intent_match")), exr.toString());
        assertFalse(CatalogDeepSearchFinalRanker.isSemanticallyActionable(exr), exr.toString());
    }

    @Test
    void genericInflationRanksHeadlineIndexAboveUnrequestedSpecializedVariants() {
        String query = "inflace spanelsko";
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        List<Map<String, Object>> verified = List.of(
                row("eurostat", "prc_hicp_apc", "HICP - administered prices (composition) (2001-2025)", 278, "verified"),
                row("eurostat", "prc_hicp_midx", "HICP - monthly data (index) (1996-2025)", 278, "verified"));

        CatalogDeepSearchFinalRanker.RankedBuckets ranked =
                CatalogDeepSearchFinalRanker.rank(query, geo, verified, List.of());

        Map<String, Object> headline = findBySetId(ranked.verified(), "prc_hicp_midx");
        Map<String, Object> specialized = findBySetId(ranked.verified(), "prc_hicp_apc");
        assertTrue(
                (Integer) headline.get("final_rank") < (Integer) specialized.get("final_rank"),
                headline + " vs " + specialized);
    }

    @Test
    void wildcardCountryAliasDoesNotDemoteVerifiedGeoPreview() {
        String query = "inflace madarsko";
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        List<Map<String, Object>> verified = List.of(row(
                "eurostat",
                "prc_hicp_manr",
                "HICP - monthly data (annual rate of change) (1997-2025)",
                123,
                "verified"));

        CatalogDeepSearchFinalRanker.RankedBuckets ranked =
                CatalogDeepSearchFinalRanker.rank(query, geo, verified, List.of());

        Map<String, Object> hicp = findBySetId(ranked.verified(), "prc_hicp_manr");
        assertEquals("exact", hicp.get("semantic_match_level"), hicp.toString());
        assertTrue(Boolean.TRUE.equals(hicp.get("metric_match")), hicp.toString());
        assertTrue(CatalogDeepSearchFinalRanker.isSemanticallyActionable(hicp), hicp.toString());
    }

    @Test
    void eurusdRanksExchangeRateAboveUsdDenominatedNonFxRows() {
        String query = "eurusd";
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        List<Map<String, Object>> verified = List.of(row(
                "imf",
                "IMF|IMF.RES|EQ|2.0.0|CZE.X_USD.0.A",
                "Czechia - Exports, US dollar",
                900,
                "verified"));
        List<Map<String, Object>> possible = List.of(row(
                "fred",
                "CCUSMA02EZA618N",
                "Currency Conversions: US Dollar Exchange Rate: Average of Daily Rates: National Currency: USD for Euro Area",
                120,
                "candidate"));

        CatalogDeepSearchFinalRanker.RankedBuckets ranked =
                CatalogDeepSearchFinalRanker.rank(query, geo, verified, possible);

        Map<String, Object> exportUsd = findBySetId(ranked.verified(), "IMF|IMF.RES|EQ|2.0.0|CZE.X_USD.0.A");
        Map<String, Object> fx = findBySetId(ranked.possible(), "CCUSMA02EZA618N");
        assertTrue((Integer) fx.get("final_rank") < (Integer) exportUsd.get("final_rank"), fx + " vs " + exportUsd);
        assertFalse(CatalogDeepSearchFinalRanker.isSemanticallyActionable(exportUsd), exportUsd.toString());
    }

    private static Map<String, Object> findBySetId(List<Map<String, Object>> rows, String setId) {
        return rows.stream()
                .filter(row -> setId.equals(row.get(CatalogKeys.SET_ID)))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> row(String source, String setId, String title, int score, String status) {
        boolean verified = "verified".equals(status);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(CatalogKeys.CATALOG_ID, source);
        row.put(CatalogKeys.SOURCE_TYPE, source);
        row.put(CatalogKeys.SET_ID, setId);
        row.put("title", title);
        row.put(CatalogKeys.SEARCH_SCORE, score);
        row.put(CatalogKeys.STATUS, status);
        row.put(CatalogKeys.PREVIEW_STATUS, status);
        row.put(CatalogKeys.PREVIEW_AVAILABLE, verified);
        row.put("preview_row_count", verified ? 10 : 0);
        return row;
    }

    private static Map<String, Object> rowWithUnit(
            String source, String setId, String title, String unit, int score, String status) {
        boolean verified = "verified".equals(status);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(CatalogKeys.CATALOG_ID, source);
        row.put(CatalogKeys.SOURCE_TYPE, source);
        row.put(CatalogKeys.SET_ID, setId);
        row.put("title", title);
        row.put("unit", unit);
        row.put(CatalogKeys.ROW, Map.of("unit", unit));
        row.put(CatalogKeys.SEARCH_SCORE, score);
        row.put(CatalogKeys.STATUS, status);
        row.put(CatalogKeys.PREVIEW_STATUS, status);
        row.put(CatalogKeys.PREVIEW_AVAILABLE, verified);
        row.put("preview_row_count", verified ? 10 : 0);
        return row;
    }
}
