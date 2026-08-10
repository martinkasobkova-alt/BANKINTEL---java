package cz.bankintel.search.v2.reranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.openai.OpenAiModelTask;
import cz.bankintel.search.v2.entity.SearchV2ExactEntityScorer;
import cz.bankintel.search.v2.ontology.SearchV2ConceptOntology;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SearchV2SemanticValidatorTest {

    private final SearchV2SemanticValidator validator =
            new SearchV2SemanticValidator(
                    mock(OpenAiClient.class),
                    new ObjectMapper(),
                    new SearchV2ConceptOntology(new ObjectMapper()),
                    new SearchV2ExactEntityScorer(),
                    new SearchV2InstitutionalSectorRegistry(new ObjectMapper()));

    @Test
    void deterministicFallbackMarksContextOnlySeriesAsContext() {
        SearchQueryPlan plan = plan("inflace spanelsko", List.of("inflation", "hicp", "cpi"));

        var result = validator.validate(
                plan,
                List.of(
                        candidate("weights", "HICP - country weights (1996-2025)"),
                        candidate("annual-rate", "HICP - monthly data (annual rate of change)")),
                false);

        assertThat(result.decisions()).extracting("seriesId").containsExactly("weights", "annual-rate");
        assertThat(result.decisions().get(0).resultRole()).isEqualTo("context");
        assertThat(result.decisions().get(1).resultRole()).isEqualTo("primary");
        assertThat(result.decisions().get(1).relevanceScore()).isGreaterThan(result.decisions().get(0).relevanceScore());
    }

    @Test
    void deterministicFallbackRetainsLexicallyUnverifiedCandidatesAsContext() {
        SearchQueryPlan plan = plan("cena zlata", List.of("gold", "price"));

        var result = validator.validate(
                plan,
                List.of(
                        candidate("gold", "Gold"),
                        candidate("oil", "Crude oil, Brent")),
                false);

        assertThat(result.decisions()).extracting("seriesId").containsExactly("gold", "oil");
        assertThat(result.decisions().get(0).decision()).isEqualTo("keep");
        assertThat(result.decisions().get(1).decision()).isEqualTo("keep");
        assertThat(result.decisions().get(1).resultRole()).isEqualTo("context");
        assertThat(result.decisions().get(1).semanticConflicts()).contains("fallback_unverified_candidate");
    }

    @Test
    void deterministicFallbackRewardsFullOriginalQueryCoverage() {
        SearchQueryPlan plan = plan(
                "USD CZK exchange rate",
                List.of("exchange_rate"),
                List.of("exchange rate", "foreign exchange rate"));

        SearchCandidate broad = new SearchCandidate(
                "imf:broad",
                "broad",
                "Nominal effective exchange rate Hungary",
                "",
                "imf",
                "",
                "",
                "",
                "",
                "",
                List.of("exchange_rate"),
                List.of(),
                List.of(),
                "",
                1.0,
                "exchange rate",
                List.of(),
                Map.of());
        SearchCandidate exact = new SearchCandidate(
                "fred:exact",
                "exact",
                "US Dollar exchange rate, USD per Czech koruna",
                "OECD unit CZK",
                "fred",
                "",
                "",
                "",
                "",
                "",
                List.of("exchange_rate"),
                List.of(),
                List.of(),
                "",
                1.0,
                "USD CZK exchange rate",
                List.of(),
                Map.of());

        var result = validator.validate(plan, List.of(broad, exact), false);

        assertThat(result.decisions().get(1).matchedUserNeed()).contains("original_query_full_coverage");
        assertThat(result.decisions().get(1).relevanceScore())
                .isGreaterThan(result.decisions().get(0).relevanceScore());
    }

    @Test
    void deterministicFallbackDoesNotRejectWhenAProfessionalSignalCannotBeProven() {
        SearchQueryPlan plan = plan("roa bank", List.of("roa", "bank", "return on assets"));

        var result = validator.validate(
                plan,
                List.of(
                        candidate("bank-assets", "Bank total assets"),
                        candidate("roa", "Return on assets (ROA) · banks")),
                false);

        assertThat(result.decisions().get(0).decision()).isEqualTo("keep");
        assertThat(result.decisions().get(0).resultRole()).isEqualTo("context");
        assertThat(result.decisions().get(0).semanticConflicts()).contains("fallback_missing_required_signal:roa");
        assertThat(result.decisions().get(1).decision()).isEqualTo("keep");
        assertThat(result.decisions().get(1).matchedUserNeed()).contains("roa");
    }

    @Test
    void deterministicFallbackRejectsOnlyExplicitGeoConflicts() {
        SearchQueryPlan plan = plan("ROA spanelskych bank", List.of("roa", "bank", "return on assets"));

        var result = validator.validate(
                plan,
                List.of(
                        candidate("at-roa", "Return on assets of banks", Map.of("geo", "AT")),
                        candidate("es-roa", "Return on assets of banks", Map.of("geo", "ES")),
                        candidate("selectable-roa", "Return on assets of banks")),
                false);

        assertThat(result.decisions().get(0).decision()).isEqualTo("drop");
        assertThat(result.decisions().get(0).semanticConflicts()).contains("explicit_geo_mismatch:AT");
        assertThat(result.decisions().get(1).decision()).isEqualTo("keep");
        assertThat(result.decisions().get(2).decision()).isEqualTo("keep");
    }

    @Test
    void deterministicFallbackTreatsGlobalDataTableAsDimensionSelectable() {
        SearchQueryPlan plan = plan(
                "zisk bank v Cesku",
                List.of("bank_profitability"),
                List.of("bank profit", "net income", "return on equity"));

        var result = validator.validate(
                plan,
                List.of(candidate(
                        "bank-profit-table",
                        "Net income of banks",
                        "data360",
                        Map.of(
                                "geo", "GLOBAL",
                                "catalog_family", "banking",
                                "measure_type", "net_profit",
                                "economic_object", "bank_profit"))),
                false);

        assertThat(result.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.decision()).isEqualTo("keep");
            assertThat(decision.semanticConflicts()).doesNotContain("explicit_geo_mismatch:GLOBAL");
        });
    }

    @Test
    void llmReceivesDeterministicEvidenceAndItsSemanticDecisionIsNotRewritten() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiClient client = mock(OpenAiClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.modelFor(OpenAiModelTask.RERANKER)).thenReturn("test-model");
        when(client.chatCompletionJson(anyString(), anyString(), eq(OpenAiModelTask.RERANKER)))
                .thenReturn(objectMapper.readTree("""
                        {
                          "decisions": [{
                            "series_id": "roe",
                            "decision": "drop",
                            "relevance_score": 0.4,
                            "confidence": 0.8,
                            "matched_user_need": ["bank profitability"],
                            "semantic_conflicts": ["sibling_subseries"],
                            "reason": "Model chose to reject this sibling series.",
                            "result_role": "reject"
                          }]
                        }
                        """));
        SearchV2SemanticValidator aiValidator = new SearchV2SemanticValidator(
                client,
                objectMapper,
                new SearchV2ConceptOntology(objectMapper),
                new SearchV2ExactEntityScorer(),
                new SearchV2InstitutionalSectorRegistry(objectMapper));
        SearchQueryPlan plan = plan(
                "zisk bank",
                List.of("bank_profitability"),
                List.of("bank profitability", "return on equity"));

        var result = aiValidator.validate(
                plan,
                List.of(candidate(
                        "roe",
                        "Return on equity of banks",
                        Map.of("measure_type", "roe", "institutional_sector", "banks"))),
                true);

        assertThat(result.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.decision()).isEqualTo("drop");
            assertThat(decision.semanticConflicts()).contains("sibling_subseries");
        });
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).chatCompletionJson(anyString(), promptCaptor.capture(), eq(OpenAiModelTask.RERANKER));
        JsonNode evidence = objectMapper.readTree(promptCaptor.getValue())
                .path("candidates")
                .get(0)
                .path("deterministic_evidence");
        assertThat(evidence.path("authority").asText()).isEqualTo("advisory_semantic_evidence");
        assertThat(evidence.path("target_profile").path("catalog_family").asText()).isEqualTo("banking");
        assertThat(evidence.path("target_profile").path("catalog_family").asText())
                .isNotEqualTo("markets_equities");
    }

    @Test
    void llmReceivesHardGeoConflictFromFixedNationalCatalog() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiClient client = mock(OpenAiClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.modelFor(OpenAiModelTask.RERANKER)).thenReturn("test-model");
        when(client.chatCompletionJson(anyString(), anyString(), eq(OpenAiModelTask.RERANKER)))
                .thenReturn(objectMapper.readTree("""
                        {
                          "decisions": [{
                            "series_id": "national-wages",
                            "decision": "drop",
                            "relevance_score": 0.1,
                            "confidence": 0.99,
                            "matched_user_need": [],
                            "semantic_conflicts": ["wrong_geography"],
                            "reason": "Wrong fixed source geography.",
                            "result_role": "reject"
                          }]
                        }
                        """));
        SearchV2SemanticValidator aiValidator = new SearchV2SemanticValidator(
                client,
                objectMapper,
                new SearchV2ConceptOntology(objectMapper),
                new SearchV2ExactEntityScorer(),
                new SearchV2InstitutionalSectorRegistry(objectMapper));
        SearchQueryPlan plan = plan(
                "vyvoj mezd v Rakousku",
                List.of("average_wages"),
                List.of("average wages", "wage growth"));

        aiValidator.validate(
                plan,
                List.of(candidate("national-wages", "Average wages", "csu", Map.of())),
                true);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).chatCompletionJson(anyString(), promptCaptor.capture(), eq(OpenAiModelTask.RERANKER));
        JsonNode geo = objectMapper.readTree(promptCaptor.getValue())
                .path("candidates")
                .get(0)
                .path("deterministic_evidence")
                .path("geo");
        assertThat(geo.path("status").asText()).isEqualTo("source_scope_conflict");
        assertThat(geo.path("hard_conflict").asBoolean()).isTrue();
        assertThat(geo.path("candidate_inferred").asText()).isEqualTo("CZ");
    }

    @Test
    void explicitSectorSurvivesWrongPlannerConceptAndOverridesUnsupportedLlmKeep() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiClient client = mock(OpenAiClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.modelFor(OpenAiModelTask.RERANKER)).thenReturn("test-model");
        when(client.chatCompletionJson(anyString(), anyString(), eq(OpenAiModelTask.RERANKER)))
                .thenReturn(objectMapper.readTree("""
                        {
                          "decisions": [
                            {
                              "series_id": "bank-supervision",
                              "decision": "keep",
                              "relevance_score": 0.9,
                              "confidence": 0.8,
                              "matched_user_need": ["insurance sector"],
                              "semantic_conflicts": [],
                              "reason": "Possibly relevant.",
                              "result_role": "primary"
                            },
                            {
                              "series_id": "insurance-assets",
                              "decision": "keep",
                              "relevance_score": 0.8,
                              "confidence": 0.8,
                              "matched_user_need": ["insurance sector"],
                              "semantic_conflicts": [],
                              "reason": "Relevant.",
                              "result_role": "primary"
                            }
                          ]
                        }
                        """));
        SearchV2SemanticValidator aiValidator = new SearchV2SemanticValidator(
                client,
                objectMapper,
                new SearchV2ConceptOntology(objectMapper),
                new SearchV2ExactEntityScorer(),
                new SearchV2InstitutionalSectorRegistry(objectMapper));
        SearchQueryPlan plan = plan("stav pojistoven na Slovensku", List.of("bank_profitability"));

        var result = aiValidator.validate(
                plan,
                List.of(
                        candidate("bank-supervision", "Significant credit institutions", "ecb2", Map.of(
                                "institutional_sector", "banks")),
                        candidate("insurance-assets", "Insurance corporations total assets", "ecb2", Map.of(
                                "institutional_sector", "insurance"))),
                true);

        assertThat(result.decisions().get(0).decision()).isEqualTo("drop");
        assertThat(result.decisions().get(0).semanticConflicts())
                .contains("institutional_sector_mismatch:insurance:banks", "proven_structured_conflict");
        assertThat(result.decisions().get(1).decision()).isEqualTo("keep");
    }

    @Test
    void deterministicFallbackSeparatesCoreFromHeadlineInflation() {
        SearchQueryPlan plan = plan("jadrova inflace Cesko", List.of("core inflation", "inflation"));

        var result = validator.validate(
                plan,
                List.of(
                        candidate("headline", "HICP overall inflation", Map.of("measure_type", "headline_inflation")),
                        candidate("core", "Core inflation excluding energy and food", Map.of("measure_type", "core_inflation"))),
                false);

        assertThat(result.decisions().get(0).decision()).isEqualTo("drop");
        assertThat(result.decisions().get(0).semanticConflicts()).contains("core_vs_headline_inflation");
        assertThat(result.decisions().get(1).decision()).isEqualTo("keep");
    }

    @Test
    void deterministicFallbackSeparatesRealFromNominalWagesAndGovernmentScope() {
        SearchQueryPlan plan = plan("realne mzdy CR", List.of("real wages", "wages"));

        var result = validator.validate(
                plan,
                List.of(
                        candidate("gov-nominal", "Government sector wages", Map.of(
                                "economic_object", "wages",
                                "nominal_real", "nominal",
                                "institutional_sector", "government")),
                        candidate("real-wages", "Real wages total economy", Map.of(
                                "economic_object", "wages",
                                "nominal_real", "real",
                                "institutional_sector", "total_economy"))),
                false);

        assertThat(result.decisions().get(0).decision()).isEqualTo("drop");
        assertThat(result.decisions().get(0).semanticConflicts()).contains("real_vs_nominal");
        assertThat(result.decisions().get(1).decision()).isEqualTo("keep");
    }

    @Test
    void broadBankProfitQueryKeepsNetProfitRoaAndRoeAsValidProfitabilitySeries() {
        SearchQueryPlan plan = plan(
                "zisk bank v Cesku",
                List.of("bank_profitability"),
                List.of("bank profit", "net income", "return on equity", "return on assets"));

        var result = validator.validate(
                plan,
                List.of(
                        candidate("roe", "Return on equity of banks", Map.of("measure_type", "roe", "institutional_sector", "banks")),
                        candidate("profit", "Net profit of banks", Map.of("measure_type", "net_profit", "institutional_sector", "banks"))),
                false);

        assertThat(result.decisions().get(0).decision()).isEqualTo("keep");
        assertThat(result.decisions().get(0).semanticConflicts()).doesNotContain("net_profit_vs_profitability_ratio");
        assertThat(result.decisions().get(1).decision()).isEqualTo("keep");
    }

    @Test
    void explicitBankProfitabilityRejectsDifferentStructuredInstitutionalSector() {
        SearchQueryPlan plan = plan("ROA bank Cesko", List.of("return_on_assets", "bank_profitability"));

        var result = validator.validate(
                plan,
                List.of(
                        candidate("pension-roa", "Return on assets of pension funds", "data360", Map.of(
                                "measure_type", "roa",
                                "institutional_sector", "pension_funds")),
                        candidate("bank-roa", "Return on assets of banks", "ecb2", Map.of(
                                "measure_type", "roa",
                                "institutional_sector", "banks"))),
                false);

        assertThat(result.decisions().get(0).decision()).isEqualTo("drop");
        assertThat(result.decisions().get(0).semanticConflicts())
                .contains("institutional_sector_mismatch:banks:pension_funds");
        assertThat(result.decisions().get(1).decision()).isEqualTo("keep");
    }

    @Test
    void explicitPensionFundSectorRejectsBankRoaAndKeepsPensionFundRoa() {
        SearchQueryPlan plan = plan("ROA pension funds", List.of("return_on_assets"));

        var result = validator.validate(
                plan,
                List.of(
                        candidate("bank-roa", "Return on assets of banks", "ecb2", Map.of(
                                "measure_type", "roa",
                                "institutional_sector", "banks")),
                        candidate("pension-roa", "Return on assets of pension funds", "data360", Map.of(
                                "measure_type", "roa",
                                "institutional_sector", "pension_funds"))),
                false);

        assertThat(result.decisions().get(0).decision()).isEqualTo("drop");
        assertThat(result.decisions().get(0).semanticConflicts())
                .contains("institutional_sector_mismatch:pension_funds:banks");
        assertThat(result.decisions().get(1).decision()).isEqualTo("keep");
    }

    @Test
    void explicitInstitutionalSectorDoesNotRejectCandidateWithMissingSectorMetadata() {
        SearchQueryPlan plan = plan("ROA insurance companies", List.of("return_on_assets"));

        var result = validator.validate(
                plan,
                List.of(candidate("generic-roa", "Return on assets", Map.of("measure_type", "roa"))),
                false);

        assertThat(result.decisions().getFirst().decision()).isEqualTo("keep");
        assertThat(result.decisions().getFirst().resultRole()).isEqualTo("context");
        assertThat(result.decisions().getFirst().semanticConflicts())
                .contains("missing_explicit_institutional_sector:insurance");
    }

    @Test
    void explicitNetProfitQueryStillDistinguishesProfitabilityRatiosInFallback() {
        SearchQueryPlan plan = plan(
                "cisty zisk bank v Cesku",
                List.of("bank_net_profit"),
                List.of("bank profit", "return on equity"));

        var result = validator.validate(
                plan,
                List.of(
                        candidate("roe", "Return on equity of banks", Map.of("measure_type", "roe", "institutional_sector", "banks")),
                        candidate("profit", "Net profit of banks", Map.of("measure_type", "net_profit", "institutional_sector", "banks"))),
                false);

        assertThat(result.decisions().get(0).decision()).isEqualTo("drop");
        assertThat(result.decisions().get(0).semanticConflicts()).contains("net_profit_vs_profitability_ratio");
        assertThat(result.decisions().get(1).decision()).isEqualTo("keep");
    }

    @Test
    void deterministicFallbackSeparatesPolicyRateFromLendingRate() {
        SearchQueryPlan plan = plan("sazby CNB", List.of("central bank policy rate", "repo rate"));

        var result = validator.validate(
                plan,
                List.of(
                        candidate("loan", "Bank lending rate for loans", Map.of("instrument", "loan", "measure_type", "interest_rate")),
                        candidate("repo", "Central bank two-week repo rate", Map.of("measure_type", "central_bank_policy_rate", "instrument", "interest_rate"))),
                false);

        assertThat(result.decisions().get(0).decision()).isEqualTo("drop");
        assertThat(result.decisions().get(0).semanticConflicts()).contains("policy_rate_vs_retail_lending_rate");
        assertThat(result.decisions().get(1).decision()).isEqualTo("keep");
    }

    @Test
    void deterministicFallbackSeparatesHousePricesGoldPricesAndEquities() {
        SearchQueryPlan housePlan = plan("ceny nemovitosti Slovensko", List.of("house price index", "property price"));
        var house = validator.validate(
                housePlan,
                List.of(
                        candidate("dwellings", "Completed dwellings housing completions", Map.of("measure_type", "count")),
                        candidate("hpi", "House price index", Map.of("measure_type", "house_price_index"))),
                false);
        assertThat(house.decisions().get(0).semanticConflicts()).contains("house_price_vs_housing_quantity");
        assertThat(house.decisions().get(1).decision()).isEqualTo("keep");

        SearchQueryPlan goldPlan = plan("cena zlata", List.of("gold price"));
        var gold = validator.validate(
                goldPlan,
                List.of(
                        candidate("reserves", "Central bank gold reserves", Map.of("economic_object", "central_bank_gold_reserves")),
                        candidate("spot", "Gold spot market price", Map.of(
                                "measure_type", "market_price",
                                "economic_object", "gold",
                                "price_type", "commodity_market_price"))),
                false);
        assertThat(gold.decisions().get(0).semanticConflicts()).contains("market_price_vs_reserve_asset");
        assertThat(gold.decisions().get(1).decision()).isEqualTo("keep");

        SearchQueryPlan stockPlan = plan("akcie CEZ", List.of("stock price", "equity"));
        var stock = validator.validate(
                stockPlan,
                List.of(
                        candidate("macro", "Financial derivatives and balance of payments", Map.of("catalog_family", "macro")),
                        candidate("equity", "CEZ share price", "stocks", Map.of(
                                "instrument", "equity",
                                "catalog_family", "markets_equities"))),
                false);
        assertThat(stock.decisions().get(0).semanticConflicts()).contains("equity_market_price_vs_unrelated_series");
        assertThat(stock.decisions().get(1).decision()).isEqualTo("keep");
    }

    private static SearchCandidate candidate(String id, String title) {
        return candidate(id, title, Map.of());
    }

    private static SearchCandidate candidate(String id, String title, Map<String, Object> raw) {
        return candidate(id, title, "eurostat", raw);
    }

    private static SearchCandidate candidate(String id, String title, String source, Map<String, Object> raw) {
        return new SearchCandidate(
                source + ":" + id,
                id,
                title,
                "",
                source,
                "",
                "",
                "",
                "",
                "",
                stringList(raw.get("concepts")),
                stringList(raw.get("tags")),
                List.of(),
                "",
                1.0,
                "hicp",
                List.of(),
                raw);
    }

    private static List<String> stringList(Object value) {
        if (value instanceof Iterable<?> iterable) {
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        return List.of();
    }

    private static SearchQueryPlan plan(String query, List<String> terms) {
        return plan(query, terms, terms);
    }

    private static SearchQueryPlan plan(String query, List<String> primaryConcepts, List<String> searchTerms) {
        return new SearchQueryPlan(
                query,
                "cs",
                "find_series",
                primaryConcepts,
                List.of(),
                List.of("ES"),
                List.of("eurostat"),
                List.of(),
                List.of(),
                null,
                searchTerms,
                searchTerms,
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "local_fallback",
                null);
    }
}
