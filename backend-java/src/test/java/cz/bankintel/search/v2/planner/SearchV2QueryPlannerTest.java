package cz.bankintel.search.v2.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.openai.OpenAiModelTask;
import cz.bankintel.search.v2.entity.ExactEntityResolver;
import cz.bankintel.search.v2.entity.SearchV2SourceCapabilityRegistry;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.ontology.SearchV2IndustrySectorRegistry;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SourceRoutingDecision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2QueryPlannerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiClient openAiClient = mock(OpenAiClient.class);
    private final SearchV2SourceCapabilityRegistry capabilityRegistry = new SearchV2SourceCapabilityRegistry(objectMapper);
    private final CatalogIndexStore catalogIndexStore = mock(CatalogIndexStore.class);
    private final ExactEntityResolver exactEntityResolver =
            new ExactEntityResolver(objectMapper, capabilityRegistry, catalogIndexStore);
    private final SearchV2ConceptRegistry conceptRegistry = new SearchV2ConceptRegistry(objectMapper);
    private final SearchV2InstitutionalSectorRegistry institutionalSectorRegistry =
            new SearchV2InstitutionalSectorRegistry(objectMapper);
    private final SearchV2MetricIntentRegistry metricIntentRegistry = new SearchV2MetricIntentRegistry(objectMapper);
    private final SearchV2IndustrySectorRegistry industrySectorRegistry = new SearchV2IndustrySectorRegistry(objectMapper);
    private final SearchV2QueryPlanner planner = new SearchV2QueryPlanner(
            openAiClient, exactEntityResolver, conceptRegistry, capabilityRegistry, institutionalSectorRegistry,
            metricIntentRegistry, industrySectorRegistry);

    /**
     * Živě reprodukováno (2026-09-05): appka pro holý dataset kód "naio_10_pyp1620" vždycky
     * zavolala LLM plánovač (protože inferCodeLikeEntity natvrdo vracel probable_entity, nikdy
     * exact_entity) - LLM si jednou vymyslel špatný "10-year yield" výklad, což zahodilo všech
     * 25 reálných kandidátů. Když se kód dá ověřit proti katalogu, appka teď plánovač vůbec
     * nezavolá (přesně jako u kurátorovaných entit typu tickerů) - end-to-end důkaz, ne jen na
     * úrovni ExactEntityResolver samotného.
     */
    @Test
    void catalogVerifiedDatasetCodeBypassesLlmPlannerJustLikeCuratedEntities() {
        when(openAiClient.isConfigured()).thenReturn(true);
        when(catalogIndexStore.lookupRowIndexedOnly("eurostat", "naio_10_pyp1620"))
                .thenReturn(java.util.Optional.of(Map.of("set_id", "naio_10_pyp1620")));

        SearchQueryPlan plan = planner.plan(Map.of(
                "query", "naio_10_pyp1620",
                "use_ai", true));

        assertThat(plan.plannerStatus()).isEqualTo("exact_entity_resolver");
        assertThat(plan.sourceRouting().preferredSources()).containsExactly("eurostat");
        verify(openAiClient, never()).plannerCompletionJson(any(), any(), any());
    }

    @Test
    void localPlanKeepsExplicitSourceAsHardConstraint() {
        when(openAiClient.isConfigured()).thenReturn(false);

        SearchQueryPlan plan = planner.plan(Map.of(
                "query", "data o zisku bank pouze z ARAD",
                "use_ai", false));

        assertThat(plan.explicitSources()).containsExactly("arad");
        assertThat(plan.clarification().required()).isFalse();
    }

    @Test
    void institutionalSectorIsDetectedDeterministicallyOnTheLocalPlanningPath() {
        when(openAiClient.isConfigured()).thenReturn(false);

        SearchQueryPlan plan = planner.plan(Map.of(
                "query", "naklady pojistoven ve spanelsku",
                "use_ai", false));

        assertThat(plan.institutionalSectors())
                .as("sector detection must not depend on the LLM planner being configured")
                .containsExactly("insurance");
    }

    @Test
    void institutionalSectorsIsEmptyNotNullWhenNoSectorIsNamed() {
        when(openAiClient.isConfigured()).thenReturn(false);

        SearchQueryPlan plan = planner.plan(Map.of(
                "query", "HDP Nemecko",
                "use_ai", false));

        assertThat(plan.institutionalSectors()).isEmpty();
    }

    @Test
    void localPlanAsksClarificationForActionLikeProductionRequest() {
        when(openAiClient.isConfigured()).thenReturn(false);

        SearchQueryPlan plan = planner.plan(Map.of(
                "query", "navysit vyrobu v Nemecku",
                "use_ai", false));

        assertThat(plan.intent()).isEqualTo("ambiguous");
        assertThat(plan.clarification().required()).isTrue();
        assertThat(plan.clarification().question()).contains("typ");
        assertThat(plan.geographies()).contains("DE");
    }

    @Test
    void selectedSourcesOverrideQueryAliases() {
        when(openAiClient.isConfigured()).thenReturn(false);

        SearchQueryPlan plan = planner.plan(Map.of(
                "query", "ARAD inflace",
                "sources", List.of("eurostat"),
                "use_ai", false));

        assertThat(plan.explicitSources()).containsExactly("eurostat");
    }

    @Test
    void ecbAliasNormalizesToSearchV2Ecb2Source() {
        when(openAiClient.isConfigured()).thenReturn(false);

        SearchQueryPlan plan = planner.plan(Map.of(
                "query", "ROA bank pouze ECB",
                "use_ai", false));

        assertThat(plan.explicitSources()).containsExactly("ecb2");
    }

    @Test
    void bankProfitabilityRoutesToEurostatThroughCapabilityRegistry() {
        when(openAiClient.isConfigured()).thenReturn(false);

        SearchQueryPlan plan = planner.plan(Map.of(
                "query", "zisk bank slovensko",
                "use_ai", false));

        assertThat(plan.primaryConcepts()).contains("bank_profitability");
        assertThat(plan.geographies()).contains("SK");
        assertThat(plan.sourceRouting().selectedCatalogFamilies())
                .contains("banking", "financial_stability");
        assertThat(plan.sourceRouting().preferredSources()).contains("eurostat");
    }

    @Test
    void bankInterestIncomePhraseIsNotMisclassifiedAsInterestRate() {
        when(openAiClient.isConfigured()).thenReturn(false);

        SearchQueryPlan plan = planner.plan(Map.of(
                "query", "urokove vynosy slovenskych bank",
                "use_ai", false));

        assertThat(plan.primaryConcepts()).containsExactly("net_interest_income");
        assertThat(plan.primaryConcepts()).doesNotContain("interest_rate");
        assertThat(plan.geographies()).contains("SK");
        assertThat(plan.sourceRouting().selectedCatalogFamilies())
                .contains("banking", "financial_stability");
        assertThat(plan.sourceRouting().preferredSources()).contains("ecb2", "eurostat");
    }

    @Test
    void worldBankAndData360HaveDistinctExplicitSourceAliases() {
        when(openAiClient.isConfigured()).thenReturn(false);

        SearchQueryPlan worldBank = planner.plan(Map.of(
                "query", "GDP growth World Bank",
                "use_ai", false));
        SearchQueryPlan data360 = planner.plan(Map.of(
                "query", "GDP growth World Bank Data360",
                "use_ai", false));

        assertThat(worldBank.explicitSources()).containsExactly("worldbank");
        assertThat(data360.explicitSources()).containsExactly("data360");
    }

    @Test
    void stockIntentRoutesToSeparateStocksCatalog() {
        when(openAiClient.isConfigured()).thenReturn(false);

        SearchQueryPlan plan = planner.plan(Map.of(
                "query", "akcie CEZ",
                "use_ai", false));

        assertThat(plan.explicitSources()).containsExactly("stocks");
    }

    @Test
    void highConfidenceExactEntityBypassesBroadPlannerExpansion() {
        when(openAiClient.isConfigured()).thenReturn(true);

        SearchQueryPlan plan = planner.plan(Map.of(
                "query", "nasdaq100",
                "use_ai", true));

        assertThat(plan.plannerStatus()).isEqualTo("exact_entity_resolver");
        assertThat(plan.entityResolution().resolutionType()).isEqualTo("exact_entity");
        assertThat(plan.entityResolution().canonicalName()).isEqualTo("NASDAQ-100");
        assertThat(plan.sourceRouting().preferredSources()).contains("fred");
        assertThat(plan.firstPassSearchTerms()).contains("nasdaq100", "NASDAQ-100", "NDX");
        assertThat(plan.queryVariants())
                .filteredOn(variant -> "related_entity".equals(variant.role()))
                .extracting("text")
                .contains("S&P 500", "VIX");
        assertThat(plan.firstPassSearchTerms()).doesNotContain("S&P 500", "VIX", "US stock market valuation");
    }

    @Test
    void exactEntityFixedGeoSurvivesPlanningWhenUserDidNotSelectAnotherGeo() {
        when(openAiClient.isConfigured()).thenReturn(false);

        SearchQueryPlan fed = planner.plan(Map.of("query", "urokove sazby Fed", "use_ai", false));
        SearchQueryPlan kb = planner.plan(Map.of("query", "akcie Komercni banka", "use_ai", false));
        SearchQueryPlan nasdaq = planner.plan(Map.of("query", "Nasdaq-100", "use_ai", false));

        assertThat(fed.geographies()).containsExactly("US");
        assertThat(fed.sourceRouting().preferredSources()).contains("fred");
        assertThat(kb.geographies()).containsExactly("CZ");
        assertThat(kb.sourceRouting().preferredSources()).contains("stocks");
        assertThat(nasdaq.geographies()).isEmpty();
        assertThat(nasdaq.entityResolution().attributes()).containsEntry("market", "US");
    }

    @Test
    void selectedGeoKeepsPriorityOverEntityFixedGeo() {
        when(openAiClient.isConfigured()).thenReturn(false);

        SearchQueryPlan plan = planner.plan(Map.of(
                "query", "akcie Komercni banka",
                "selected_geo", List.of("SK"),
                "use_ai", false));

        assertThat(plan.geographies()).containsExactly("SK");
    }

    @Test
    void czechGeoAliasesNormalizeToSameCountryCode() {
        when(openAiClient.isConfigured()).thenReturn(false);

        assertThat(planner.plan(Map.of("query", "ROA bank Cesko", "use_ai", false)).geographies()).contains("CZ");
        assertThat(planner.plan(Map.of("query", "ROA bank Czechia", "use_ai", false)).geographies()).contains("CZ");
        assertThat(planner.plan(Map.of("query", "ROA bank Czech Republic", "use_ai", false)).geographies()).contains("CZ");
        assertThat(planner.plan(Map.of("query", "non-performing loans Czech banks", "use_ai", false)).geographies()).contains("CZ");
        assertThat(planner.plan(Map.of("query", "ROA bank CZ", "use_ai", false)).geographies()).contains("CZ");
    }

    @Test
    void euroAreaAliasNormalizesToU2AggregateCode() {
        when(openAiClient.isConfigured()).thenReturn(false);

        SearchQueryPlan plan = planner.plan(Map.of(
                "query", "ROA bank eurozone",
                "use_ai", false));

        assertThat(plan.geographies()).contains("U2");
    }

    @Test
    void phraseConceptGuardPreventsSiblingRateConceptLeakage() throws Exception {
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.modelFor(OpenAiModelTask.PLANNER)).thenReturn("test-planner");
        when(openAiClient.plannerCompletionJson(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new cz.bankintel.search.openai.OpenAiClient.CompletionResult(new ObjectMapper().readTree("""
                        {
                          "normalized_query": "urokova mira nemecko",
                          "language": "cs",
                          "intent": "lookup",
                          "required_concepts": ["interest_rate"],
                          "measure_types": ["rate"],
                          "geographies": ["DE"],
                          "catalog_families": ["macro", "labour", "prices"],
                          "preferred_sources": ["eurostat", "oecd4", "imf", "ecb2"],
                          "excluded_sources": [],
                          "query_variants": [
                            {"text": "urokova mira nemecko", "role": "original_exact", "weight": 1.0},
                            {"text": "interest rate Germany", "role": "professional_synonym", "weight": 0.8},
                            {"text": "unemployment rate Germany", "role": "related_entity", "weight": 0.5},
                            {"text": "inflation rate Germany", "role": "related_entity", "weight": 0.5}
                          ],
                          "clarification_required": false,
                          "clarification_question": null
                        }
                        """), java.util.Map.of("called", true, "success", true)));

        SearchQueryPlan plan = planner.plan(Map.of("query", "urokova mira nemecko", "use_ai", true));

        String terms = String.join(" | ", plan.firstPassSearchTerms()).toLowerCase();
        String variants = plan.queryVariants().toString().toLowerCase();
        assertThat(plan.primaryConcepts()).contains("interest_rate");
        assertThat(plan.supportingConcepts()).doesNotContain("rate");
        assertThat(terms).doesNotContain("unemployment").doesNotContain("inflation");
        assertThat(variants).doesNotContain("unemployment").doesNotContain("inflation");
        assertThat(plan.sourceRouting().selectedCatalogFamilies()).contains("interest_rates");
        assertThat(plan.sourceRouting().preferredSources()).contains("ecb2");
    }

    @Test
    void clarificationOptionsBecomeFirstPassSearchTermsBeforeNarrowPlannerVariants() throws Exception {
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.modelFor(OpenAiModelTask.PLANNER)).thenReturn("test-planner");
        when(openAiClient.plannerCompletionJson(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new cz.bankintel.search.openai.OpenAiClient.CompletionResult(new ObjectMapper().readTree("""
                        {
                          "normalized_query": "stav sektoru v zemi",
                          "intent": "ambiguous",
                          "required_concepts": ["sector condition"],
                          "measure_types": ["level"],
                          "geographies": ["SK"],
                          "geo_memberships": [],
                          "catalog_families": [],
                          "preferred_sources": ["ecb2", "oecd4"],
                          "excluded_sources": [],
                          "query_variants": [
                            {"text": "stav sektoru v zemi", "role": "original_exact"},
                            {"text": "sector count Slovakia", "role": "professional_synonym"}
                          ],
                          "confidence": 0.7,
                          "clarification_required": true,
                          "clarification_question": "Kterou metriku chcete?",
                          "clarification_options": [
                            "sector total assets Slovakia",
                            "sector profitability Slovakia",
                            "sector capital Slovakia"
                          ]
                        }
                        """), java.util.Map.of("called", true, "success", true)));

        SearchQueryPlan plan = planner.plan(Map.of("query", "stav sektoru v zemi", "use_ai", true));

        assertThat(plan.firstPassSearchTerms())
                .startsWith(
                        "stav sektoru v zemi",
                        "sector total assets Slovakia",
                        "sector profitability Slovakia",
                        "sector capital Slovakia");
        assertThat(plan.clarification().required()).isFalse();
    }

    @Test
    void explicitBankInterestIncomePhraseOverridesWrongLlmRateConcept() throws Exception {
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.modelFor(OpenAiModelTask.PLANNER)).thenReturn("test-planner");
        when(openAiClient.plannerCompletionJson(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new cz.bankintel.search.openai.OpenAiClient.CompletionResult(new ObjectMapper().readTree("""
                        {
                          "normalized_query": "urokove vynosy slovenskych bank",
                          "language": "cs",
                          "intent": "lookup",
                          "required_concepts": ["interest_rate"],
                          "measure_types": ["rate"],
                          "geographies": ["SK"],
                          "catalog_families": ["interest_rates"],
                          "preferred_sources": ["ecb2"],
                          "excluded_sources": [],
                          "query_variants": [
                            {"text": "urokove vynosy slovenskych bank", "role": "original_exact", "weight": 1.0},
                            {"text": "bank interest rates Slovakia", "role": "professional_synonym", "weight": 0.8}
                          ],
                          "clarification_required": false,
                          "clarification_question": null
                        }
                        """), java.util.Map.of("called", true, "success", true)));

        SearchQueryPlan plan = planner.plan(Map.of(
                "query", "urokove vynosy slovenskych bank",
                "use_ai", true));

        assertThat(plan.primaryConcepts()).containsExactly("net_interest_income");
        assertThat(plan.primaryConcepts()).doesNotContain("interest_rate");
        assertThat(plan.sourceRouting().selectedCatalogFamilies())
                .contains("banking", "financial_stability");
    }

    @Test
    void structuredSourceEvidenceSurvivesWrongLlmRoutingAndExclusion() {
        SourceRoutingDecision concept = new SourceRoutingDecision(
                List.of("banking"),
                List.of("ecb2", "eurostat"),
                List.of(),
                Map.of("ecb2", "concept capability"));
        SourceRoutingDecision llm = new SourceRoutingDecision(
                List.of("macro"),
                List.of("fred"),
                List.of("ecb2"),
                Map.of("fred", "planner suggestion"));

        SourceRoutingDecision merged = SearchV2QueryPlanner.mergeSourceRouting(
                SourceRoutingDecision.empty(), concept, SourceRoutingDecision.empty(), llm);

        assertThat(merged.selectedCatalogFamilies()).containsExactly("banking", "macro");
        assertThat(merged.preferredSources()).containsExactly("ecb2", "eurostat");
        assertThat(merged.excludedSources()).doesNotContain("ecb2");
        assertThat(merged.sourceSelectionReason()).containsKeys("ecb2", "fred");
    }

    @Test
    void llmRoutingIsUsedWhenStructuredRegistriesHaveNoPreferredSource() {
        SourceRoutingDecision llm = new SourceRoutingDecision(
                List.of("macro"),
                List.of("eurostat", "imf"),
                List.of(),
                Map.of("eurostat", "planner suggestion"));

        SourceRoutingDecision merged = SearchV2QueryPlanner.mergeSourceRouting(
                SourceRoutingDecision.empty(),
                SourceRoutingDecision.empty(),
                SourceRoutingDecision.empty(),
                llm);

        assertThat(merged.preferredSources()).containsExactly("eurostat", "imf");
    }

    @Test
    void localFallbackResolvesDifferentRatePhrasesByLongestConceptPhrase() {
        when(openAiClient.isConfigured()).thenReturn(false);

        SearchQueryPlan interest = planner.plan(Map.of("query", "urokova mira nemecko", "use_ai", false));
        SearchQueryPlan unemployment = planner.plan(Map.of("query", "mira nezamestnanosti nemecko", "use_ai", false));
        SearchQueryPlan inflation = planner.plan(Map.of("query", "mira inflace rakousko", "use_ai", false));

        assertThat(interest.primaryConcepts()).containsExactly("interest_rate");
        assertThat(unemployment.primaryConcepts()).containsExactly("unemployment_rate");
        assertThat(inflation.primaryConcepts()).containsExactly("inflation_rate");
        assertThat(interest.primaryConcepts()).doesNotContain("unemployment_rate");
    }

    @Test
    void openAiClarificationDoesNotOverrideConcreteSearchPlan() throws Exception {
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.modelFor(OpenAiModelTask.PLANNER)).thenReturn("test-planner");
        when(openAiClient.plannerCompletionJson(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new cz.bankintel.search.openai.OpenAiClient.CompletionResult(new ObjectMapper().readTree("""
                        {
                          "language": "cs",
                          "intent": "find_series",
                          "primary_concepts": ["zisk bank", "ziskovost bank"],
                          "exact_search_terms": ["zisk bank", "ROA banky", "ROE banky"],
                          "semantic_search_terms": ["bank profits Czechia"],
                          "geographies": ["CZ"],
                          "clarification": {
                            "required": true,
                            "question": "Chcete ROA nebo ROE?",
                            "reason": "Existuje vice indikatoru."
                          }
                        }
                        """), java.util.Map.of("called", true, "success", true)));

        SearchQueryPlan plan = planner.plan(Map.of("query", "zisk bank v Cesku", "use_ai", true));

        assertThat(plan.clarification().required()).isFalse();
        assertThat(plan.primaryConcepts()).contains("bank_profitability");
    }

    @Test
    void fallbackResolvesEconomicGrowthThroughConceptRegistry() {
        when(openAiClient.isConfigured()).thenReturn(false);

        SearchQueryPlan plan = planner.plan(Map.of("query", "rust nemecke ekonomiky", "use_ai", false));

        assertThat(plan.primaryConcepts()).contains("growth_rate");
        assertThat(plan.geographies()).contains("DE");
    }

    /**
     * AI planner nondeterminism audit (2026-07-31): "ziskovost pojistoven spanelsko" has no matching
     * concept_registry.json entry (the concept ontology only names bank-specific profitability), so
     * conceptRouting never fires and source_routing used to fall through entirely to the LLM's own
     * per-call guess - confirmed live to sometimes come back with empty preferred_sources/
     * selected_catalog_families, which on one out of six repeated identical-query runs caused a
     * verified-result count of 0 instead of the usual 4. metric_intent ("profitability") resolves
     * deterministically here even though the concept ontology has no entry, so it must fill this gap
     * instead of leaving source routing entirely up to a single, possibly-empty LLM response.
     */
    @Test
    void emptyLlmSourceRoutingFallsBackToMetricIntentDerivedRoutingNotToNothing() throws Exception {
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.modelFor(OpenAiModelTask.PLANNER)).thenReturn("test-planner");
        when(openAiClient.plannerCompletionJson(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new cz.bankintel.search.openai.OpenAiClient.CompletionResult(new ObjectMapper().readTree("""
                        {
                          "language": "cs",
                          "intent": "find_series",
                          "primary_concepts": ["bank_profitability"],
                          "exact_search_terms": ["ziskovost pojistoven spanelsko"],
                          "semantic_search_terms": ["insurance profitability Spain"],
                          "geographies": ["ES"],
                          "catalog_families": [],
                          "preferred_sources": [],
                          "excluded_sources": [],
                          "clarification": {"required": false, "question": null, "reason": null}
                        }
                        """), java.util.Map.of("called", true, "success", true)));

        SearchQueryPlan plan = planner.plan(Map.of("query", "ziskovost pojistoven spanelsko", "use_ai", true));

        assertThat(plan.metricIntents()).containsExactly("profitability");
        assertThat(plan.sourceRouting().preferredSources())
                .as("an empty LLM source_routing response must not leave preferred_sources empty when "
                        + "metric_intent resolved deterministically")
                .isNotEmpty()
                .contains("eurostat", "imf", "bis")
                .doesNotContain("stocks", "commodities");
    }

}
