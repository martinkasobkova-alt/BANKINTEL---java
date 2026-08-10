package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogCompositeScorerTest {

    @Test
    void additiveModelBeatsWeakBlendForZiskBank() {
        String query = "zisk bank";
        List<String> needles = CatalogTextUtils.needlesFromQuery(query);
        List<String> likely = CatalogLikelySources.inferLikelyCatalogSources(query);
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);

        Map<String, Object> profitRow = Map.of("name", "Bank net profit ROE return on equity banking sector");
        Map<String, Object> assetsRow = Map.of("name", "Bank total assets long-term assets banking sector");

        int additiveProfit = CatalogCompositeScorer.scoreWithLikelySources(
                "arad", query, profitRow, needles, 40, 100, likely, geo, CatalogGeoIntent.requestedGeoCodes(geo));
        int additiveAssets = CatalogCompositeScorer.scoreWithLikelySources(
                "arad", query, assetsRow, needles, 40, 100, likely, geo, CatalogGeoIntent.requestedGeoCodes(geo));

        CatalogCompositeScorer.ScoreBreakdown weakBlendProfit =
                CatalogCompositeScorer.scoreBreakdown("arad", query, profitRow, needles, 40, 100);
        CatalogCompositeScorer.ScoreBreakdown weakBlendAssets =
                CatalogCompositeScorer.scoreBreakdown("arad", query, assetsRow, needles, 40, 100);

        assertTrue(additiveProfit > additiveAssets, "additive profit=" + additiveProfit + " assets=" + additiveAssets);
        assertTrue(
                additiveProfit > weakBlendProfit.compositeScore(),
                "additive should dominate legacy blend: additive=" + additiveProfit + " blend=" + weakBlendProfit.compositeScore());
        assertTrue(
                additiveProfit - additiveAssets > weakBlendProfit.compositeScore() - weakBlendAssets.compositeScore(),
                "additive separation should exceed weak blend separation");
    }

    @Test
    void inflationQueryScoresHicpRowHigherThanUnrelated() {
        String query = "inflace HICP";
        List<String> needles = CatalogTextUtils.needlesFromQuery(query);
        List<String> likely = CatalogLikelySources.inferLikelyCatalogSources(query);
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);

        Map<String, Object> hicpRow = Map.of("name", "Harmonised Index of Consumer Prices HICP inflation rate");
        Map<String, Object> unrelated = Map.of("name", "Bank total assets long-term assets");

        int hicpScore = CatalogCompositeScorer.scoreWithLikelySources(
                "eurostat", query, hicpRow, needles, 40, 100, likely, geo, CatalogGeoIntent.requestedGeoCodes(geo));
        int otherScore = CatalogCompositeScorer.scoreWithLikelySources(
                "eurostat", query, unrelated, needles, 40, 100, likely, geo, CatalogGeoIntent.requestedGeoCodes(geo));

        assertTrue(hicpScore > otherScore, "hicp=" + hicpScore + " other=" + otherScore);
    }

    @Test
    void countryMismatchPenalizesForeignRowRegardlessOfDataset() {
        // Generic replacement for the old prc_hicp/teicp code-literal test (kolo 6 cleanup):
        // the same dataset (une_rt_m-shaped row) must rank higher for the requested country
        // purely from its resolved geo dimension, not from any dataset-code whitelist.
        String query = "nezamestnanost Cesko";
        List<String> needles = CatalogTextUtils.needlesFromQuery(query);
        List<String> likely = CatalogLikelySources.inferLikelyCatalogSources(query);
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        List<String> geoCodes = CatalogGeoIntent.requestedGeoCodes(geo);

        Map<String, Object> czRow = Map.of("set_id", "une_rt_m", "name", "Unemployment by sex and age", "geo", "CZ");
        Map<String, Object> deRow = Map.of("set_id", "une_rt_m", "name", "Unemployment by sex and age", "geo", "DE");

        int czScore = CatalogCompositeScorer.scoreWithLikelySources(
                "eurostat", query, czRow, needles, 40, 100, likely, geo, geoCodes);
        int deScore = CatalogCompositeScorer.scoreWithLikelySources(
                "eurostat", query, deRow, needles, 40, 100, likely, geo, geoCodes);

        assertTrue(czScore > deScore, "cz=" + czScore + " de=" + deScore);
    }

    @Test
    void iso3PathTokenGeneralizesCountryMismatchForAnyCountry() {
        // Regression for Zjištění A (kolo 6): the old code only recognized a 7-country ISO3
        // whitelist (MEX/AUS/USA/JPN/CHN/BRA/IND) embedded in OECD-style paths. The generic
        // ISO2<->ISO3 registry must recognize ANY country pair, e.g. SVK vs MEX/AUS for a SK query.
        String query = "nezamestnanost Slovensko";
        List<String> needles = CatalogTextUtils.needlesFromQuery(query);
        List<String> likely = CatalogLikelySources.inferLikelyCatalogSources(query);
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        List<String> geoCodes = CatalogGeoIntent.requestedGeoCodes(geo);

        Map<String, Object> skRow = Map.of("set_id", "OECD/SVK/LFS/UNE_RATE", "name", "Unemployment rate");
        Map<String, Object> mexRow = Map.of("set_id", "OECD/MEX/LFS/UNE_RATE", "name", "Unemployment rate");
        Map<String, Object> ausRow = Map.of("set_id", "OECD/AUS/LFS/UNE_RATE", "name", "Unemployment rate");

        int skScore = CatalogCompositeScorer.scoreWithLikelySources(
                "oecd4", query, skRow, needles, 40, 100, likely, geo, geoCodes);
        int mexScore = CatalogCompositeScorer.scoreWithLikelySources(
                "oecd4", query, mexRow, needles, 40, 100, likely, geo, geoCodes);
        int ausScore = CatalogCompositeScorer.scoreWithLikelySources(
                "oecd4", query, ausRow, needles, 40, 100, likely, geo, geoCodes);

        assertTrue(skScore > mexScore, "sk=" + skScore + " mex=" + mexScore);
        assertTrue(skScore > ausScore, "sk=" + skScore + " aus=" + ausScore);
    }

    @Test
    void iso3PathTokenGeneralizesForArbitraryCountryPairOutsideOldWhitelist() {
        // Poland/Canada were never in the removed 7-country whitelist — proves the fix is
        // truly general, not just a bigger whitelist.
        String query = "HDP Polsko";
        List<String> needles = CatalogTextUtils.needlesFromQuery(query);
        List<String> likely = CatalogLikelySources.inferLikelyCatalogSources(query);
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        List<String> geoCodes = CatalogGeoIntent.requestedGeoCodes(geo);

        Map<String, Object> plRow = Map.of("set_id", "OECD/POL/SNA/GDP", "name", "Gross domestic product");
        Map<String, Object> caRow = Map.of("set_id", "OECD/CAN/SNA/GDP", "name", "Gross domestic product");

        int plScore = CatalogCompositeScorer.scoreWithLikelySources(
                "oecd4", query, plRow, needles, 40, 100, likely, geo, geoCodes);
        int caScore = CatalogCompositeScorer.scoreWithLikelySources(
                "oecd4", query, caRow, needles, 40, 100, likely, geo, geoCodes);

        assertTrue(plScore > caScore, "pl=" + plScore + " ca=" + caScore);
    }

    @Test
    void inflationQueryPenalizesEcbExchangeRateSetId() {
        String query = "inflace Cesko";
        List<String> needles = CatalogTextUtils.needlesFromQuery(query);
        List<String> likely = CatalogLikelySources.inferLikelyCatalogSources(query);
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        List<String> geoCodes = CatalogGeoIntent.requestedGeoCodes(geo);

        Map<String, Object> exr = Map.of(
                "set_id", "EXR/A.E01.HRK.NRC0.A",
                "name", "Real harmonised competitiveness indicator CPI deflated");
        Map<String, Object> hicp = Map.of("set_id", "prc_hicp_manr", "name", "HICP annual rate of change");

        int exrScore = CatalogCompositeScorer.scoreWithLikelySources(
                "ecb2", query, exr, needles, 40, 100, likely, geo, geoCodes);
        int hicpScore = CatalogCompositeScorer.scoreWithLikelySources(
                "eurostat", query, hicp, needles, 40, 100, likely, geo, geoCodes);

        assertTrue(hicpScore > exrScore, "hicp=" + hicpScore + " exr=" + exrScore);
    }

    @Test
    void aradSourceGetsBonusForCzechBankQuery() {
        String query = "zisk českých bank";
        List<String> needles = CatalogTextUtils.needlesFromQuery(query);
        List<String> likely = CatalogLikelySources.inferLikelyCatalogSources(query);
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        Map<String, Object> row = Map.of("name", "Bankovní sektor zisk ROE");

        int arad = CatalogCompositeScorer.scoreWithLikelySources(
                "arad", query, row, needles, 40, 100, likely, geo, CatalogGeoIntent.requestedGeoCodes(geo));
        int ecb = CatalogCompositeScorer.scoreWithLikelySources(
                "ecb2", query, row, needles, 40, 100, likely, geo, CatalogGeoIntent.requestedGeoCodes(geo));

        assertTrue(arad >= ecb, "arad=" + arad + " ecb=" + ecb);
    }
}
