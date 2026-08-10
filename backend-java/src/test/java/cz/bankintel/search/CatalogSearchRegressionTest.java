package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.bankintel.search.model.CatalogKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit-level golden-query regression guard — synonyms, geo intent, likely sources, and scoring
 * helpers without a full FTS database.
 */
class CatalogSearchRegressionTest {

    @ParameterizedTest
    @CsvSource({
        "zisk bank, profit, arad",
        "ROE bank, roe, ecb2",
        "ROA bank, roa, ecb2",
        "inflace Česko, inflac, csu",
        "HDP Ukrajiny, hdp, imf",
        "cena ropy, oil, fred",
        "nezaměstnanost Slovensko, nezamestnan, eurostat"
    })
    void goldenQueriesExpandNeedlesAndLikelySources(String query, String needleFragment, String expectedSource) {
        List<String> needles = CatalogTextUtils.needlesFromQuery(query);
        String needlesJoined = String.join(" ", needles).toLowerCase(Locale.ROOT);
        assertTrue(
                needlesJoined.contains(needleFragment.toLowerCase(Locale.ROOT)),
                "needles for '" + query + "': " + needles);

        List<String> likely = CatalogLikelySources.inferLikelyCatalogSources(
                query, CatalogSearchSynonyms.expandSearchQueries(query));
        assertTrue(likely.contains(expectedSource), "likely sources for '" + query + "': " + likely);

        List<String> expanded = CatalogSearchSynonyms.expandSearchQueries(query);
        assertFalse(expanded.isEmpty(), "expandSearchQueries empty for: " + query);
    }

    @Test
    void ziskBankScoresBankingTitleHigherWithLikelySources() {
        String query = "zisk bank";
        List<String> needles = CatalogTextUtils.needlesFromQuery(query);
        List<String> likely = CatalogLikelySources.inferLikelyCatalogSources(query);

        int aradScore = CatalogCompositeScorer.scoreWithLikelySources(
                "arad",
                query,
                Map.of("name", "Bank profitability (ROE)"),
                needles,
                40,
                CatalogTextUtils.titleMatchScore(
                        CatalogSearchSynonyms.foldCs("Bank profitability (ROE)"), needles),
                likely);
        int randomScore = CatalogCompositeScorer.scoreWithLikelySources(
                "fred",
                query,
                Map.of("name", "Unrelated retail sales"),
                needles,
                5,
                CatalogTextUtils.titleMatchScore(
                        CatalogSearchSynonyms.foldCs("Unrelated retail sales"), needles),
                likely);

        assertTrue(aradScore > randomScore, "arad=" + aradScore + " fred=" + randomScore);
    }

    @Test
    void inflaceCeskoDetectsCzechGeo() {
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent("inflace Česko");
        assertTrue("country".equals(geo.get("type")) || "CZ".equals(geo.get("country_code")));
    }

    @Test
    void hdpUkrajinyDetectsUkraine() {
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent("HDP Ukrajiny");
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) geo.get("country_codes");
        assertTrue(codes != null && codes.contains("UA"), "geo: " + geo);
    }

    @Test
    void nezamestnanostSlovenskoDetectsSlovakia() {
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent("nezaměstnanost Slovensko");
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) geo.get("country_codes");
        assertTrue(codes != null && codes.contains("SK"), "geo: " + geo);
    }

    @Test
    void inflaceCeskoFtsMatchUsesWordTokensNotExactPhrase() {
        String match = CatalogTextUtils.buildFtsMatch(CatalogTextUtils.needlesFromQuery("inflace Cesko"), "inflace Cesko");
        String lower = match.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("\"inflace\"") || lower.contains("\"inflation\""), match);
        assertFalse(lower.contains("\"inflace cesko\""), "must not require exact phrase: " + match);
    }

    @Test
    void nezamestnanostSlovenskoFtsMatchUsesTopicTokens() {
        String match = CatalogTextUtils.buildFtsMatch(
                CatalogTextUtils.needlesFromQuery("nezaměstnanost Slovensko"), "nezaměstnanost Slovensko");
        String lower = match.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("nezamestnan") || lower.contains("unemployment"), match);
        assertFalse(lower.contains("slovensko") || lower.contains("slovakia"), "geo token must not be in FTS: " + match);
    }

    @Test
    void hdpNemeckoTopicStripsGeoFromFts() {
        String topic = CatalogGeoIntent.topicQueryWithoutGeo("HDP Nemecko");
        assertFalse(topic.toLowerCase(Locale.ROOT).contains("nemeck"), "topic=" + topic);
        String match = CatalogTextUtils.buildFtsMatch(CatalogTextUtils.needlesFromQuery("HDP Nemecko"), "HDP Nemecko");
        assertTrue(match.toLowerCase(Locale.ROOT).contains("hdp") || match.toLowerCase(Locale.ROOT).contains("gdp"), match);
    }

    @Test
    void variantDedupCollapsesDuplicateTitles() {
        List<Map<String, Object>> rows = List.of(
                Map.of("source_type", "ecb2", "name", "Credit standards · Loan supply · Austria", "_search_score", 40),
                Map.of("source_type", "ecb2", "name", "Credit standards · Loan supply · Austria", "_search_score", 35),
                Map.of("source_type", "ecb2", "name", "Credit standards · Loan supply · Belgium", "_search_score", 30));
        List<Map<String, Object>> deduped = CatalogSearchVariantDedup.consolidateDisplayRows(new ArrayList<>(rows));
        assertTrue(deduped.size() <= 2, "deduped=" + deduped);
    }

    @Test
    void roeBankFtsMatchIncludesSynonyms() {
        String match = CatalogTextUtils.buildFtsMatch(CatalogTextUtils.needlesFromQuery("ROE bank"), "ROE bank");
        String lower = match.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("roe") || lower.contains("return"), match);
    }

    @Test
    void roaBankScoresReturnOnAssetsWithoutSubstringNoise() {
        List<String> needles = CatalogTextUtils.needlesFromQuery("ROA bank");
        int real = CatalogTextUtils.titleMatchScore(
                CatalogTextUtils.foldAscii("Return on assets (ROA) - domestic banking groups"),
                needles);
        int falsePositive = CatalogTextUtils.titleMatchScore(
                CatalogTextUtils.foldAscii("Croatia - broad age groups - abroad mobility"),
                needles);

        assertTrue(real > 0, "real=" + real + " needles=" + needles);
        assertTrue(real > falsePositive, "real=" + real + " falsePositive=" + falsePositive);
        assertFalse(CatalogSearchSynonyms.detectBankingExpansionGroups("Croatia bank").contains("banking_profitability"));
    }

    @Test
    void inflacePromotesStrongCatalogMatchWhenPreviewFails() {
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent("inflace");
        List<Map<String, Object>> possible = new ArrayList<>(List.of(Map.of(
                CatalogKeys.TITLE,
                "Consumer price index - inflation",
                CatalogKeys.SEARCH_SCORE,
                42,
                CatalogKeys.PREVIEW_STATUS,
                "unverified",
                CatalogKeys.SOURCE_TYPE,
                "fred",
                CatalogKeys.SET_ID,
                "CPIAUCSL")));
        List<Map<String, Object>> verified = new ArrayList<>();
        CatalogDeepSearchPromotion.promoteCatalogMatches("inflace", geo, possible, verified);
        assertFalse(verified.isEmpty(), "verified=" + verified);
        assertTrue(
                "catalog_match".equals(verified.get(0).get(CatalogKeys.RESULT_TIER)),
                verified.toString());
        assertTrue(
                "candidate".equals(verified.get(0).get(CatalogKeys.STATUS)),
                verified.toString());
    }

    @Test
    void hdpUkrajinyHardRejectsCzechSourceRowsWithoutCountryCode() {
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent("HDP Ukrajiny");
        Map<String, Object> aradRow = Map.of(
                CatalogKeys.SOURCE_TYPE, "arad",
                CatalogKeys.TITLE, "Hrubý domácí produkt",
                CatalogKeys.SET_ID, "NM001");
        CatalogGeoIntent.GeoRowAdjustment adj = CatalogGeoIntent.rowCountryGeoAdjustment(aradRow, geo);
        assertTrue(adj.hardReject(), adj.toString());
    }

    @Test
    void cenaRopyExpandsOilSynonyms() {
        List<String> expanded = CatalogSearchSynonyms.expandSearchQueries("cena ropy");
        String joined = String.join(" ", expanded).toLowerCase(Locale.ROOT);
        assertTrue(joined.contains("oil") || joined.contains("brent") || joined.contains("crude"), joined);
    }

    @Test
    void geoTokenDoesNotFlagCreditResearchHealthcare() {
        assertFalse(CatalogGeoIntent.looksLikeGeoToken("credit"));
        assertFalse(CatalogGeoIntent.looksLikeGeoToken("research"));
        assertFalse(CatalogGeoIntent.looksLikeGeoToken("healthcare"));
        assertTrue(CatalogGeoIntent.looksLikeGeoToken("cr"));
        assertTrue(CatalogGeoIntent.looksLikeGeoToken("ea"));
    }

    @Test
    void creditStandardsTopicNotStrippedByGeo() {
        List<String> needles = CatalogTextUtils.needlesFromQuery("credit standards");
        String joined = String.join(" ", needles).toLowerCase(Locale.ROOT);
        assertTrue(joined.contains("credit"), "needles=" + needles);
    }
}
