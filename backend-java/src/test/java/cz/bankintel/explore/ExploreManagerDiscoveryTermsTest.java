package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExploreManagerDiscoveryTermsTest {

    @Test
    void alwaysIncludesCoreMacroProbes() {
        // GDP/HDP scaffold must apply to every Manager query — never only to one country/sector case.
        for (String query : List.of(
                "obecny dotaz",
                "vyroba v rakousku",
                "prodej polovodicu ve slovinsku",
                "inflace ve francii",
                "chci investovat do vyroby automobilu v nemecku")) {
            List<String> probes = ExploreManagerDiscoveryTerms.probeTermsFor(query);
            assertTrue(probes.contains("Gross domestic product"), query);
            assertTrue(probes.contains("nama_10_gdp"), query);
            assertTrue(probes.contains("GDP"), query);
            assertTrue(probes.contains("inflation"), query);
            assertTrue(probes.contains("unemployment"), query);
            assertTrue(probes.contains("interest rate"), query);
            assertTrue(probes.contains("policy rate"), query);
            assertTrue(probes.contains("exchange rate"), query);
            assertTrue(probes.contains("industrial production"), query);
            assertTrue(probes.contains("manufacturing"), query);
        }
        assertEquals(
                List.of("nama_10_gdp", "prc_hicp_manr", "une_rt_m", "irt_h_ddmr_m"),
                ExploreManagerDiscoveryTerms.coreMacroSeedsForSource("eurostat"));
        assertEquals(
                List.of("ECBMRRFR", "DEXUSEU"), ExploreManagerDiscoveryTerms.coreMacroSeedsForSource("fred"));
        assertEquals(
                List.of("EXR/M.USD.EUR.SP00.A"),
                ExploreManagerDiscoveryTerms.coreMacroSeedsForSource("ecb2"));
    }

    @Test
    void addsGeoQualifiedProductionSurfacesForAnyDetectedCountry() {
        List<String> austria =
                ExploreManagerDiscoveryTerms.probeTermsFor("chci investovat do vyroby pocitacu v rakousku");
        assertTrue(austria.contains("production manufacturing"));
        assertTrue(austria.contains("investment goods"));
        assertTrue(austria.contains("gross fixed capital formation"));
        assertTrue(austria.contains("Austria manufacturing"));
        assertTrue(austria.contains("Production Manufacturing Austria"));

        List<String> germany =
                ExploreManagerDiscoveryTerms.probeTermsFor("chci investovat do vyroby v nemecku");
        assertTrue(germany.contains("Germany manufacturing"));
        assertTrue(germany.contains("Production Manufacturing Germany"));
        assertFalse(germany.contains("Austria manufacturing"));
    }

    @Test
    void doesNotHardcodeAustriaWhenCountryMissing() {
        List<String> probes = ExploreManagerDiscoveryTerms.probeTermsFor("chci investovat do vyroby pocitacu");
        assertFalse(probes.contains("Austria manufacturing"));
        assertTrue(probes.contains("production manufacturing"));
    }

    @Test
    void macroScaffoldRecognizesCoreEconomicSeries() {
        assertTrue(ExploreManagerDiscoveryTerms.isMacroScaffoldRow(
                Map.of("title", "Unemployment rate", "set_id", "IMF|...|LUR.A")));
        assertTrue(ExploreManagerDiscoveryTerms.isMacroScaffoldRow(
                Map.of("title", "HICP - annual rate of change", "set_id", "teicp290")));
        assertTrue(ExploreManagerDiscoveryTerms.isMacroScaffoldRow(
                Map.of("title", "Production in industry - monthly data", "set_id", "sts_inpr_m")));
        assertTrue(ExploreManagerDiscoveryTerms.isMacroScaffoldRow(
                Map.of(
                        "title",
                        "Gross domestic product (GDP) and main components",
                        "set_id",
                        "nama_10_gdp")));
        assertTrue(ExploreManagerDiscoveryTerms.isCoreGdpDatasetRow(
                Map.of("set_id", "nama_10_gdp", "title", "Gross domestic product (GDP)")));
        assertTrue(ExploreManagerDiscoveryTerms.isCoreMacroSeedRow(
                Map.of("set_id", "ECBMRRFR", "title", "ECB Main Refinancing Operations Rate")));
        assertTrue(ExploreManagerDiscoveryTerms.isMacroScaffoldRow(
                Map.of("set_id", "ECBMRRFR", "title", "ECB Main Refinancing Operations Rate")));
        assertTrue(ExploreManagerDiscoveryTerms.isCoreMacroSeedRow(
                Map.of("set_id", "EXR/M.USD.EUR.SP00.A", "title", "US dollar/Euro")));
        assertTrue(ExploreManagerDiscoveryTerms.isMacroScaffoldRow(
                Map.of("set_id", "EXR/M.USD.EUR.SP00.A", "title", "US dollar/Euro")));
        assertTrue(ExploreManagerDiscoveryTerms.isCoreMacroSeedRow(
                Map.of("set_id", "DEXUSEU", "title", "U.S. Dollars to One Euro")));
        assertFalse(ExploreManagerDiscoveryTerms.isMacroScaffoldRow(Map.of(
                "title",
                "Real effective exch. rate GDP deflators deflated — Australian dollar",
                "set_id",
                "EXR/A.E01.AUD.ERD0.A")));
        assertTrue(ExploreManagerDiscoveryTerms.isCoreMacroSeedRow(
                Map.of("set_id", "prc_hicp_manr", "title", "HICP - monthly data (annual rate of change)")));
        assertTrue(ExploreManagerDiscoveryTerms.isCoreMacroSeedRow(
                Map.of("set_id", "une_rt_m", "title", "Unemployment by sex and age - monthly data")));
        assertFalse(ExploreManagerDiscoveryTerms.isMacroScaffoldRow(
                Map.of("title", "egss output gdp share", "set_id", "eurostat_egss_output_gdp_share")));
        assertTrue(ExploreManagerDiscoveryTerms.isGdpShareProxyRow(
                Map.of("title", "egss output gdp share", "set_id", "eurostat_egss_output_gdp_share")));
        assertFalse(ExploreManagerDiscoveryTerms.isMacroScaffoldRow(
                Map.of("title", "Trade - Export, Intermediate goods", "set_id", "RTD/M.S0.S.T_XINT.E")));
        assertFalse(ExploreManagerDiscoveryTerms.isMacroScaffoldRow(Map.of(
                "title",
                "Real effective exch. rate GDP deflators deflated — Australian dollar",
                "set_id",
                "EXR/A.E01.AUD.ERD0.A")));
    }

    @Test
    void profitabilityQueryInjectsRoeRoaProbes() {
        List<String> intent =
                ExploreManagerDiscoveryTerms.intentProbeTermsFor("ziskovost bank na Slovensku");
        assertTrue(intent.contains("return on equity"));
        assertTrue(intent.contains("return on assets"));
        assertTrue(intent.contains("ROE"));
        assertTrue(intent.contains("ROA"));
        assertTrue(ExploreManagerDiscoveryTerms.detectedIntentIds("ziskovost bank na Slovensku")
                .contains("profitability"));
    }

    @Test
    void automotiveProductionQueryPrioritizesCarSpecificProbesOverGenericSynonyms() {
        List<String> intent = ExploreManagerDiscoveryTerms.intentProbeTermsFor(
                "Jaký je vývoj automobilového průmyslu na Slovensku?");
        int carIndex = intent.indexOf("NACE C29");
        int genericIndex = intent.indexOf("industrial production");
        assertTrue(carIndex >= 0, () -> "missing NACE C29 probe: " + intent);
        assertTrue(carIndex < ExploreManagerDiscoveryTerms.MANAGER_INTENT_LANE_RESERVE, () -> "car probe not within lane reserve budget: " + intent);
        assertTrue(genericIndex < 0 || carIndex < genericIndex, () -> "car probe not prioritized ahead of generic synonym: " + intent);
    }

    @Test
    void nonAutomotiveProductionQueryKeepsGenericSynonymsFirst() {
        List<String> intent = ExploreManagerDiscoveryTerms.intentProbeTermsFor("jak se vyviji vyroba v nemecku");
        int carIndex = intent.indexOf("NACE C29");
        int genericIndex = intent.indexOf("industrial production");
        assertTrue(genericIndex >= 0, () -> "missing generic production probe: " + intent);
        assertTrue(carIndex < 0 || genericIndex < carIndex, () -> "generic synonym should stay ahead of car probe for non-automotive query: " + intent);
    }

    @Test
    void tradeQueryInjectsTradeProbes() {
        List<String> intent =
                ExploreManagerDiscoveryTerms.intentProbeTermsFor("obchod mezi Nizozemskem a Danskem");
        assertTrue(intent.contains("exports"));
        assertTrue(intent.contains("imports"));
        assertTrue(intent.contains("trade balance"));
        assertTrue(ExploreManagerDiscoveryTerms.detectedIntentIds("obchod mezi Nizozemskem a Danskem")
                .contains("trade"));
    }

    @Test
    void topicKeepRecognizesRoeSeriesForProfitabilityQuery() {
        Map<String, Object> roe = Map.of(
                "title", "Return on equity of banks",
                "set_id", "CBD2/ROE");
        assertTrue(ExploreManagerDiscoveryTerms.isTopicIntentRow("ziskovost bank na Slovensku", roe));
        assertFalse(ExploreManagerDiscoveryTerms.isTopicIntentRow(
                "prodej polovodicu ve slovinsku", roe));
    }

    @Test
    void semiconductorQueryStillHasMacroScaffoldProbes() {
        List<String> probes =
                ExploreManagerDiscoveryTerms.probeTermsFor("prodej polovodicu ve slovinsku");
        assertTrue(probes.contains("Gross domestic product"));
        assertTrue(probes.contains("inflation"));
        assertTrue(probes.contains("nama_10_gdp"));
    }

    @Test
    void productionQueryExcludesDwellingsAndEgssNoise() {
        String q = "chci investovat do vyroby v rakousku";
        assertTrue(ExploreManagerDiscoveryTerms.detectedIntentIds(q).contains("production"));
        assertTrue(ExploreManagerDiscoveryTerms.isIntentExcludedRow(
                q,
                Map.of("title", "Gross fixed capital formation - dwellings", "set_id", "nama_10_an6")));
        assertTrue(ExploreManagerDiscoveryTerms.isIntentExcludedRow(
                q,
                Map.of("title", "EGSS output biological gdp share", "set_id", "eurostat_egss_output_gdp_share")));
        assertFalse(ExploreManagerDiscoveryTerms.isIntentExcludedRow(
                q,
                Map.of("title", "Production in industry - manufacturing", "set_id", "sts_inpr_m")));
        assertFalse(ExploreManagerDiscoveryTerms.isIntentExcludedRow(
                q,
                Map.of("title", "Gross domestic product (GDP)", "set_id", "nama_10_gdp")));
    }

    @Test
    void profitabilityQueryStillKeepsRoeNotHousePrices() {
        String q = "ziskovost bank na Slovensku";
        assertTrue(ExploreManagerDiscoveryTerms.isTopicIntentRow(
                q, Map.of("title", "Return on equity of banks", "set_id", "CBD2/ROE")));
        assertTrue(ExploreManagerDiscoveryTerms.isIntentExcludedRow(
                q, Map.of("title", "House price index", "set_id", "prc_hpi")));
    }

    @Test
    void productionQuerySeedsIndustrialProductionSetIds() {
        String q = "vyroba v rakousku";
        assertTrue(ExploreManagerDiscoveryTerms.intentPreviewSeedsForSource(q, "eurostat")
                .containsAll(List.of("sts_inpr_m", "sts_inpp_m", "sts_intv_m")));
        assertTrue(ExploreManagerDiscoveryTerms.intentPreviewSeedsForSource(q, "fred")
                .containsAll(List.of("IPMAN", "INDPRO")));
        List<String> pinned = ExploreManagerDiscoveryTerms.managerPinnedSeedsForSource(q, "eurostat");
        assertTrue(pinned.contains("nama_10_gdp"));
        assertTrue(pinned.contains("sts_inpr_m"));
    }

    @Test
    void profitabilityQuerySeedsBankReturnSetIds() {
        assertEquals(
                List.of("tipsbd40"),
                ExploreManagerDiscoveryTerms.intentPreviewSeedsForSource(
                        "ziskovost bank na Slovensku", "eurostat"));
        assertTrue(ExploreManagerDiscoveryTerms.matchesIntentPreviewSeed(
                "ziskovost bank na Slovensku",
                Map.of(
                        "source_type",
                        "eurostat",
                        "set_id",
                        "tipsbd40",
                        "title",
                        "Return on equity of banks")));
    }

    @Test
    void retailQuerySeedsTurnoverSetIds() {
        assertTrue(ExploreManagerDiscoveryTerms.intentPreviewSeedsForSource(
                        "retail sales ve Francii", "eurostat")
                .containsAll(List.of("sts_trtu_m", "sts_trtu_a")));
        assertTrue(ExploreManagerDiscoveryTerms.detectedIntentIds("retail sales ve Francii")
                .contains("retail"));
    }
}
