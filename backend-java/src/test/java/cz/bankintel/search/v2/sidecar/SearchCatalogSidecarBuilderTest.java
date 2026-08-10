package cz.bankintel.search.v2.sidecar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchCatalogSidecarBuilderTest {

    private SearchCatalogSidecarBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SearchCatalogSidecarBuilder(new ObjectMapper());
        builder.loadTaxonomy();
    }

    @Test
    void goldMeaningsAreNotInterchangeable() {
        SearchCatalogSidecarDocument spot = doc("fred", "GOLDAMGBD228NLBM", "Gold price, London Bullion Market, USD");
        SearchCatalogSidecarDocument reserves = doc("imf", "RES_GOLD", "Central bank gold reserves, official reserve assets");
        SearchCatalogSidecarDocument ppi = doc("fred", "PCU339911339911", "Jewelry producer price index gold");
        SearchCatalogSidecarDocument production = doc("data360", "GOLD_MINING_OUTPUT", "Gold production and mining output");

        assertEquals("commodity_spot_price", spot.primaryConcept());
        assertEquals("central_bank_gold_reserves", reserves.primaryConcept());
        assertEquals("gold_producer_price_index", ppi.primaryConcept());
        assertEquals("gold_production", production.primaryConcept());
        assertNotEquals(spot.primaryConcept(), reserves.primaryConcept());
        assertNotEquals(spot.primaryConcept(), ppi.primaryConcept());
        assertNotEquals(spot.primaryConcept(), production.primaryConcept());
    }

    @Test
    void gdpVariantsAreCanonicalizedSeparately() {
        assertEquals("nominal_gdp", doc("eurostat", "gdp_nom", "Nominal GDP current prices").primaryConcept());
        assertEquals("real_gdp", doc("eurostat", "gdp_real", "Real GDP constant prices").primaryConcept());
        assertEquals("gdp_growth", doc("imf", "gdp_growth", "Real GDP growth rate").primaryConcept());
        assertEquals("gdp_per_capita", doc("data360", "gdp_pc", "GDP per capita").primaryConcept());
        assertEquals("gdp_deflator", doc("fred", "gdp_def", "GDP deflator").primaryConcept());
    }

    @Test
    void wagesBankingAndHousingFamiliesStaySpecific() {
        assertEquals("average_wages", doc("csu", "wage_avg", "Average wages total economy").primaryConcept());
        assertEquals("real_wages", doc("csu", "real_wage", "Real wages inflation adjusted").primaryConcept());
        assertEquals("government_sector_wages", doc("oecd4", "gov_wage", "Government sector wages").primaryConcept());
        assertEquals("compensation_of_employees", doc("eurostat", "comp_emp", "Compensation of employees").primaryConcept());
        assertEquals("wage_index", doc("csu", "wage_index", "Wage index").primaryConcept());

        assertEquals("bank_net_profit", doc("eurostat", "bank_profit", "Bank net profit earnings").primaryConcept());
        assertEquals("net_interest_income", doc("ecb2", "nii", "Net interest income of banks").primaryConcept());
        assertEquals("return_on_assets", doc("ecb2", "roa", "Return on assets of banks").primaryConcept());
        assertEquals("bank_assets", doc("bis", "assets", "Total assets of banks").primaryConcept());
        assertEquals("bank_capital_ratio", doc("eurostat", "tier1", "Tier 1 capital ratio").primaryConcept());

        assertEquals("house_price_index", doc("csu", "hpi", "House price index residential property prices").primaryConcept());
        assertEquals("rents", doc("csu", "rent", "Rental prices housing rent").primaryConcept());
        assertEquals("completed_dwellings", doc("csu", "dwellings", "Completed dwellings housing completions").primaryConcept());
        assertEquals("mortgage_volume", doc("ecb2", "mortgage", "Mortgage loans housing loans").primaryConcept());
        assertEquals("construction_production", doc("csu", "construction", "Construction production output").primaryConcept());
    }

    @Test
    void contextSensitiveConceptsDoNotOverMatchByLooseWords() {
        SearchCatalogSidecarDocument ictCore = doc("data360", "ICT_CORE", "Core revenue of information technology sector");
        SearchCatalogSidecarDocument bankRoe = doc("eurostat", "roe", "Return on equity of banks");
        SearchCatalogSidecarDocument mortgageRate = doc("arad", "MORTGAGE_RATE", "New mortgage loan interest rates");
        SearchCatalogSidecarDocument housingPurchase =
                doc("csu", "OBYDLI", "Index cen nakupu obydli a ceny nemovitosti");

        assertNotEquals("core_inflation", ictCore.primaryConcept());
        assertNotEquals("core_inflation", ictCore.measureType());
        assertEquals("return_on_equity", bankRoe.primaryConcept());
        assertNotEquals("equity", bankRoe.instrument());
        assertNotEquals("central_bank_policy_rate", mortgageRate.primaryConcept());
        assertNotEquals("central_bank_policy_rate", mortgageRate.measureType());
        assertEquals("house_price_index", housingPurchase.primaryConcept());
    }

    @Test
    void repairsCommonMojibakeInCanonicalTitles() {
        SearchCatalogSidecarDocument doc = builder.build(Map.of(
                "source", "csu",
                "series_id", "CRU01",
                "title_original", "Kapacity hromadn\u00c3\u00bdch ubytovac\u00c3\u00adch za\u00c5\u02c7\u00c3\u00adzen\u00c3\u00ad"));

        assertTrue(doc.originalTitle().contains("hromadnych") || doc.originalTitle().contains("hromadn\u00fdch"));
    }

    @Test
    void aradPolicyRatesPreferOfficialIndicatorTitleOverGeneratedDebtLabel() {
        SearchCatalogSidecarDocument doc = builder.build(Map.of(
                "source", "arad",
                "series_id", "1119:SFTP02M11",
                "dataset_id", "1119",
                "title_original", "Diskontn\u00ed sazba:M\u011bs\u00ed\u010dn\u00ed, \u00darokov\u00e9 sazby \u010cNB, ke konci m\u011bs\u00edce",
                "human_label_cs", "v\u00fdnosy dluhopis\u016f",
                "search_keywords_cs", java.util.List.of("v\u00fdnosy dluhopis\u016f", "\u00farokov\u00e9 sazby")));

        assertEquals("Diskontn\u00ed sazba", doc.canonicalTitleCs());
        assertEquals("central_bank_policy_rate", doc.primaryConcept());
        assertEquals("central_bank_policy_rate", doc.measureType());
        assertThat(doc.aliasesCs()).doesNotContain("v\u00fdnosy dluhopis\u016f");
    }

    @Test
    void profitabilityRatiosDoNotKeepGenericBankProfitAliases() {
        SearchCatalogSidecarDocument doc = builder.build(Map.of(
                "source", "arad",
                "series_id", "ROE_BANKS_PILOT",
                "dataset_id", "DS_ROE",
                "title_original", "Return on equity - monetary financial institutions",
                "human_label_cs", "Rentabilita bank (ROE)",
                "search_keywords_cs", java.util.List.of("zisk bank", "rentabilita bank"),
                "search_keywords_en", java.util.List.of("bank profit", "bank roe")));

        assertEquals("return_on_equity", doc.primaryConcept());
        assertEquals("roe", doc.measureType());
        assertThat(doc.aliasesCs()).doesNotContain("zisk bank");
        assertThat(doc.aliasesEn()).doesNotContain("bank profit");
    }

    @Test
    void staleAradRoaLabelCannotRelabelAnUnrelatedRatio() {
        SearchCatalogSidecarDocument doc = builder.build(Map.of(
                "source", "arad",
                "series_id", "1039:DDZSKQ05",
                "title_original", "Druzstevni zalozny - Struktura kapitalu:Ctvrtletni, Nerozdeleny zisk",
                "human_label_cs", "rentabilita aktiv (ROA)",
                "search_keywords_cs", java.util.List.of("roa", "rentabilita aktiv")));

        assertThat(doc.canonicalTitleCs()).contains("Struktura kapitalu");
        assertThat(doc.measureType()).as(doc.toString()).isNotEqualTo("roa");
        assertThat(doc.aliasesCs()).doesNotContain("roa", "rentabilita aktiv", "rentabilita aktiv (ROA)");
    }

    @Test
    void generatedCommodityAliasCannotRelabelMineralDepletionAsSpotPrice() {
        SearchCatalogSidecarDocument doc = builder.build(Map.of(
                "source", "data360",
                "set_id", "WB_WDI|MINERAL_DEPLETION",
                "title_original", "Adjusted savings, mineral depletion (current US$)",
                "description", "Depletion covers copper, gold and other mineral resources at current prices.",
                "search_keywords_en", java.util.List.of(
                        "mineral depletion costs", "commodity spot price", "spot price"),
                "generated_by", "llm_v1"));

        assertThat(doc.primaryConcept()).isNotEqualTo("commodity_spot_price");
        assertThat(doc.aliasesEn()).contains("mineral depletion costs");
        assertThat(doc.aliasesEn()).doesNotContain("commodity spot price", "spot price");
    }

    @Test
    void exchangeRateConceptIsDerivedFromCurrencyMetadataForEverySource() {
        SearchCatalogSidecarDocument doc = builder.build(Map.of(
                "source", "fred",
                "series_id", "GENERIC_FX_SERIES",
                "title_original", "US Dollar Exchange Rate: USD per National Currency",
                "description", "Currency conversion rate at end of period"));

        assertThat(doc.primaryConcept()).isEqualTo("exchange_rate");
        assertThat(doc.catalogFamily()).isEqualTo("fx");
        assertThat(doc.aliasesEn()).contains("exchange rate", "fx rate");
    }

    @Test
    void rawCatalogRowsUseTheSameTaxonomyEnrichment() {
        SearchCatalogSidecarDocument doc = builder.buildRaw(Map.of(
                "source", "fred",
                "set_id", "RAW_FX_SERIES",
                "name", "US Dollar Exchange Rate: USD per National Currency",
                "description", "Currency conversion rate at end of period"));

        assertThat(doc.primaryConcept()).isEqualTo("exchange_rate");
        assertThat(doc.catalogFamily()).isEqualTo("fx");
    }

    @Test
    void currentEcbCbd2RowsRecoverOfficialItemMeaningFromTheSdmxCode() {
        SearchCatalogSidecarDocument doc = builder.buildRaw(Map.of(
                "source", "ecb2",
                "set_id", "CBD2/Q.SK.W0.67._Z._Z.A.F.P2110._X.ALL.CP._Z.T._T.EUR",
                "ecb_flow", "CBD2",
                "ecb_series_key", "Q.SK.W0.67._Z._Z.A.F.P2110._X.ALL.CP._Z.T._T.EUR",
                "name", "Accumulated for the current period · All currencies · Euro",
                "territory", "SK"));

        assertThat(doc.canonicalTitleEn()).as(doc.raw().toString()).startsWith("Net interest income");
        assertThat(doc.primaryConcept()).isEqualTo("net_interest_income");
        assertThat(doc.aliasesEn()).contains("Net interest income");
        assertThat(doc.dataset()).isEqualTo("CBD2");
        assertThat(doc.originalDescription()).contains("Current ECB CBD2 framework");
        assertThat(doc.lifecycleStatus()).isEqualTo("unknown");
        assertThat(doc.lifecycleConfidence()).isZero();
    }

    @Test
    void legacyEcbCbdRowsAreMarkedAsDiscontinuedWithoutQuerySpecificLogic() {
        SearchCatalogSidecarDocument doc = builder.buildRaw(Map.of(
                "source", "ecb2",
                "set_id", "CBD/H.SK.67.A.21100.X.4.Z5.0000.Z01.E",
                "ecb_flow", "CBD",
                "name", "Net interest income [full sample]"));

        assertThat(doc.dataset()).isEqualTo("CBD");
        assertThat(doc.originalDescription()).contains("discontinued in 2014");
        assertThat(doc.lifecycleStatus()).isEqualTo("historical");
        assertThat(doc.lifecycleConfidence()).isEqualTo(1.0);
    }

    @Test
    void abbreviationAloneCannotManufactureACommoditySpotPrice() {
        SearchCatalogSidecarDocument doc = builder.buildRaw(Map.of(
                "source", "fred",
                "set_id", "HISTORICAL_WHOLESALE_COPPER",
                "name", "Wholesale Price of Copper for Berlin",
                "description", "Historical prices were converted from paper to gold marks."));

        assertThat(doc.primaryConcept()).isNotEqualTo("commodity_spot_price");
    }

    @Test
    void sourceDefaultClassifiesTheCommodityPriceCatalogWithoutTitleHardcodes() {
        SearchCatalogSidecarDocument doc = builder.buildRaw(Map.of(
                "source", "commodities",
                "set_id", "GENERIC_COMMODITY",
                "name", "Copper"));

        assertThat(doc.primaryConcept()).isEqualTo("commodity_spot_price");
        assertThat(doc.catalogFamily()).isEqualTo("commodities");
    }

    @Test
    void aradProfitOverAverageAssetsStillDerivesRoaFromTheOfficialFormula() {
        SearchCatalogSidecarDocument doc = builder.build(Map.of(
                "source", "arad",
                "series_id", "1018:DBAPUQ103",
                "title_original", "Banky - Pomerove ukazatele:Ctvrtletni, Zisk po zdaneni / prumerna aktiva",
                "human_label_cs", "rentabilita aktiv (ROA)",
                "search_keywords_cs", java.util.List.of("roa", "rentabilita aktiv")));

        assertThat(doc.measureType()).isEqualTo("roa");
        assertThat(doc.primaryConcept()).isEqualTo("return_on_assets");
        assertThat(doc.aliasesCs()).contains("roa", "rentabilita aktiv");
    }

    @Test
    void profitabilityRatiosDeriveInstitutionalSectorFromSeriesEvidence() {
        SearchCatalogSidecarDocument doc = builder.build(Map.of(
                "source", "data360",
                "series_id", "PENSION_FUNDS_ROA",
                "dataset_id", "OTHER_FINANCIAL_CORPORATIONS",
                "title_original", "Other financial corporations, Pension Funds - Return on assets (ROA)",
                "human_label_en", "Return on assets of banks",
                "search_keywords_en", java.util.List.of("return on assets", "bank roa")));

        assertEquals("return_on_assets", doc.primaryConcept());
        assertEquals("roa", doc.measureType());
        assertEquals("pension_funds", doc.institutionalSector());
        assertThat(doc.aliasesEn()).doesNotContain("return on assets of banks", "bank roa");
        assertThat(doc.catalogFamily()).isNotEqualTo("banking");
    }

    @Test
    void financialEarningsAndDepositTakersAreNotMisreadAsEmployeeWages() {
        SearchCatalogSidecarDocument doc = builder.build(Map.of(
                "source", "data360",
                "series_id", "FSI_BANK_ROA",
                "dataset_id", "IMF_FSI",
                "title_original", "Core Set, Deposit Takers, Earnings and Profitability, Return on assets"));

        assertEquals("return_on_assets", doc.primaryConcept());
        assertEquals("banks", doc.institutionalSector());
        assertThat(doc.economicObject()).isNotEqualTo("wages");
    }

    @Test
    void insuranceCorporationRoaUsesTheSpecificInstitutionalSector() {
        SearchCatalogSidecarDocument doc = builder.build(Map.of(
                "source", "data360",
                "series_id", "FSI_INSURANCE_ROA",
                "dataset_id", "IMF_FSI",
                "title_original", "Other Financial Corporations, Insurance Corporations, Return on assets"));

        assertEquals("insurance", doc.institutionalSector());
        assertEquals("assets", doc.economicObject());
    }

    @Test
    void ecbInstitutionalSectorComesFromSpecificSeriesEvidence() {
        SearchCatalogSidecarDocument insurance = builder.build(Map.of(
                "source", "ecb2",
                "series_id", "SSI/A.SK.1251.T10.1.U6.Z01.E",
                "title_original", "Insurance corporations · SK · Total assets",
                "description", "ECB structural financial indicators"));
        SearchCatalogSidecarDocument banks = builder.build(Map.of(
                "source", "ecb2",
                "series_id", "SUP/Q.SK.W0._Z.E6000._T.SII._Z.ALL.LE.E.C",
                "title_original", "Significant credit institutions · SK",
                "description", "ECB supervisory banking statistics"));

        assertEquals("insurance", insurance.institutionalSector());
        assertEquals("banks", banks.institutionalSector());
    }

    @Test
    void rawCatalogBuildPreservesSpecificEcbInstitutionalSectorEvidence() {
        SearchCatalogSidecarDocument insurance = builder.buildRaw(Map.of(
                "source", "ecb2",
                "series_id", "SSI/A.SK.1251.T10.1.U6.Z01.E",
                "name", "Insurance corporations · All currencies combined · Outstanding amounts · SK · Total assets",
                "description", "ECB Strukturální ukazatele bank — Slovensko.",
                "ecb_flow", "SSI",
                "ecb_flow_label", "Banking structural financial indicators",
                "ecb_series_explanation", "REF SECTOR: Insurance corporations"));
        SearchCatalogSidecarDocument banks = builder.buildRaw(Map.of(
                "source", "ecb2",
                "series_id", "SUP/Q.SK.W0._Z.E6000._T.SII._Z.ALL.LE.E.C",
                "name", "E6000 · SK · Significant institutions · Q",
                "ecb_flow", "SUP",
                "ecb_flow_label", "Supervisory banking statistics"));

        assertEquals("insurance", insurance.institutionalSector());
        assertEquals("banks", banks.institutionalSector());
    }

    @Test
    void ecbReporterSectorIsNotConfusedWithCounterpartySector() {
        SearchCatalogSidecarDocument bank = builder.buildRaw(Map.of(
                "source", "ecb2",
                "series_id", "BSI/Q.SK.N.A.A5A.A.1.U2.2221.Z01.E",
                "name", "Equity · SK · Insurance corporations (S.128)",
                "ecb_series_explanation",
                        "ADJUSTMENT: Neither seasonally nor working day adjusted"
                                + " · BS REP SECTOR: MFIs excluding ESCB"
                                + " · BS ITEM: Equity"
                                + " · BS COUNT SECTOR: Insurance corporations (S.128)"));

        assertEquals("banks", bank.institutionalSector());
    }

    @Test
    void aradRawIndicatorsUseCompositeIdentity() {
        SearchCatalogSidecarDocument first = builder.build(Map.of(
                "source", "arad",
                "set_id", "1123",
                "indicator_id", "CURRENT_ACCOUNT",
                "indicator_name", "Platebni bilance - bezny ucet"));
        SearchCatalogSidecarDocument second = builder.build(Map.of(
                "source", "arad",
                "set_id", "1123",
                "indicator_id", "CAPITAL_ACCOUNT",
                "indicator_name", "Platebni bilance - kapitalovy ucet"));

        assertEquals("1123:CURRENT_ACCOUNT", first.seriesId());
        assertEquals("1123:CAPITAL_ACCOUNT", second.seriesId());
        assertNotEquals(first.seriesId(), second.seriesId());
    }

    @Test
    void metadataOverlayPreservesRawPreviewFields() {
        Map<String, Object> merged = SearchCatalogSidecarIndex.overlay(
                Map.of(
                        "source", "fred",
                        "set_id", "CPIAUCSL",
                        "name", "Consumer Price Index",
                        "query_params", Map.of("fred_series_id", "CPIAUCSL")),
                Map.of(
                        "source", "fred",
                        "series_id", "CPIAUCSL",
                        "human_label_cs", "Index spotrebitelskych cen",
                        "search_keywords_cs", java.util.List.of("inflace")),
                "fred");

        assertEquals(Map.of("fred_series_id", "CPIAUCSL"), merged.get("query_params"));
        assertEquals("Index spotrebitelskych cen", merged.get("human_label_cs"));
        assertEquals("CPIAUCSL", merged.get("set_id"));
    }

    @Test
    void ftsMatchRequiresAllMeaningfulTokensAndDropsStopWords() {
        assertEquals("\"return\" AND \"assets\"", SearchCatalogSidecarIndex.buildMatch("Return on assets"));
        // "urokovy" widens to (urokov* OR "urokovy") - CzTextStemmer strips its Czech adjectival "-y"
        // case ending (6-char stem clears the widening floor); the other three tokens' stems are
        // either unchanged or too short to trust as a prefix (see the Czech-morphology FTS recall fix).
        assertEquals("\"cisty\" AND (urokov* OR \"urokovy\") AND \"vynos\" AND \"bank\"",
                SearchCatalogSidecarIndex.buildMatch("cisty urokovy vynos bank"));
    }

    private SearchCatalogSidecarDocument doc(String source, String seriesId, String title) {
        return builder.build(Map.of(
                "source", source,
                "series_id", seriesId,
                "dataset_id", seriesId,
                "title_original", title,
                "description_en", title));
    }
}
