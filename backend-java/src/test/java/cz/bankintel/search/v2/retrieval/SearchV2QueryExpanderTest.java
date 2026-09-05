package cz.bankintel.search.v2.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.v2.entity.ExactEntityResolver;
import cz.bankintel.search.v2.entity.ExactEntityResolver.ResolutionResult;
import cz.bankintel.search.v2.entity.SearchV2SourceCapabilityRegistry;
import cz.bankintel.search.v2.ontology.SearchV2ConceptOntology;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2QueryExpanderTest {

    private final SearchV2QueryExpander expander =
            new SearchV2QueryExpander(
                    new SearchV2ConceptOntology(new ObjectMapper()),
                    new SearchV2ConceptRegistry(new ObjectMapper()),
                    new SearchV2InstitutionalSectorRegistry(new ObjectMapper()),
                    new SearchV2MetricIntentRegistry(new ObjectMapper()));
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExactEntityResolver resolver =
            new ExactEntityResolver(
                    objectMapper, new SearchV2SourceCapabilityRegistry(objectMapper), mock(CatalogIndexStore.class));

    @Test
    void expandsCompactCurrencyPairIntoSearchableFxTerms() {
        List<String> variants = expander.expand(plan("eurusd", List.of()));

        assertThat(variants).contains("eur usd", "exchange rate eur usd");
        assertThat(variants).doesNotContain("eurusd");
    }

    @Test
    void keepsTopicTermAndAddsRequestedGeoAliasVariants() {
        List<String> variants = expander.expand(plan("HDP Polska", List.of("PL")));

        assertThat(variants).contains("HDP", "HDP PL", "HDP poland");
        assertThat(variants).contains("HDP polsko");
    }

    @Test
    void alwaysAddsCanonicalGeoCodeBeforeHumanAliases() {
        List<String> variants = expander.expand(planWithExactTerms(
                "urokove vynosy slovenských bank",
                List.of("SK"),
                List.of("urokove vynosy slovenských bank", "net interest income"),
                List.of("net_interest_income")));

        assertThat(variants).contains("urokove vynosy bank SK");
        assertThat(variants).contains("net interest income SK", "interest income SK", "urokove vynosy bank slovakia");
    }

    @Test
    void addsOntologyBackedEconomicConceptVariants() {
        assertThat(expander.expand(plan("rust nemecke ekonomiky", List.of("DE"))))
                .contains("real GDP growth", "GDP growth");
        assertThat(expander.expand(plan("prumyslova vyroba Nemecko", List.of("DE"))))
                .contains("industrial production", "production in industry");
        assertThat(expander.expand(plan("vyroba automobilu Polsko", List.of("PL"))))
                .contains("automotive production", "motor vehicle production");
        assertThat(expander.expand(plan("ceny nemovitosti Slovensko", List.of("SK"))))
                .contains("house price index", "residential property prices");
    }

    @Test
    void preservesLaterProfessionalPlannerPhrasesWithinBoundedQueryBudget() {
        List<String> variants = expander.expand(planWithExactTerms(
                "najdi stav sektoru v zemi",
                List.of("SK"),
                List.of(
                        "najdi stav sektoru v zemi",
                        "sektor celkova aktiva na Slovensku",
                        "sektor financni stabilita na Slovensku",
                        "sektor prijmy na Slovensku",
                        "sektor pocet subjektu na Slovensku",
                        "sektor investice na Slovensku",
                        "sector total assets in Slovakia",
                        "sector balance sheet in Slovakia"),
                List.of()));

        assertThat(variants)
                .hasSizeLessThanOrEqualTo(8)
                .contains("sector total assets", "sector balance sheet");
    }

    @Test
    void expandsInstitutionalSectorAliasesFromExternalRegistry() {
        List<String> variants = expander.expand(planWithExactTerms(
                "insurance companies total assets in Slovakia",
                List.of("SK"),
                List.of("insurance companies total assets in Slovakia"),
                List.of()));

        assertThat(variants)
                .contains(
                        "insurance corporations total assets SK",
                        "insurance corporations total assets in slovakia");
    }

    @Test
    void preservesExplicitSectorAndGeoWhenPlannerChoosesAnOverlyNarrowMeasure() {
        List<String> variants = expander.expand(planWithExactTerms(
                "najdi mi to stav pojistoven na slovensku",
                List.of("SK"),
                List.of(
                        "Number of insurance companies in Slovakia",
                        "Insurance premium income in Slovakia",
                        "Household insurance expenditure in Slovakia"),
                List.of("insurance_premium_income")));

        assertThat(variants)
                .hasSizeLessThanOrEqualTo(8)
                .contains("insurance corporations SK");
    }

    @Test
    void exactEntityFirstPassSkipsRelatedAndBroaderVariants() {
        ResolutionResult resolved = resolver.resolve("nasdaq100");

        List<String> variants = expander.expand(exactPlan("nasdaq100", resolved));

        assertThat(variants).contains("nasdaq100", "NASDAQ-100", "NDX");
        assertThat(variants).doesNotContain("S&P 500", "VIX", "stock market index", "equity market index");
    }

    @Test
    void exactEntityPrioritizesCanonicalGeoVariants() {
        ResolutionResult resolved = resolver.resolve("ROA ceskych bank");

        List<String> variants = expander.expand(exactPlan("ROA ceskych bank", List.of("CZ"), resolved));

        assertThat(variants).startsWith("ROA bank CZ", "Return on assets CZ");
        assertThat(variants).contains("ROA bank czechia", "ROA bank", "Return on assets");
        assertThat(variants).doesNotContain("ROA ceskych bank");
    }

    @Test
    void deterministicSearchTermsAreIdenticalAcrossRepeatedCallsWithDifferentLlmContent() {
        // Simulates two "LLM calls" for the identical user query returning different exact_search_terms
        // /primary_concepts (the confirmed root cause of candidate-pool instability) - the deterministic
        // term set must be byte-for-byte identical regardless, since it never reads those fields.
        SearchQueryPlan callA = planWithExactTerms(
                "ziskovost penzijnich fondu",
                List.of(),
                List.of("ziskovost penzijnich fondu", "pension fund profitability"),
                List.of("bank_profitability"));
        SearchQueryPlan callB = planWithExactTerms(
                "ziskovost penzijnich fondu",
                List.of(),
                List.of("return on equity pension funds", "profitability ratio"),
                List.of("return_on_equity"));

        assertThat(expander.deterministicSearchTerms(callA)).isEqualTo(expander.deterministicSearchTerms(callB));
    }

    @Test
    void deterministicPrefixPrecedesDifferentLlmPlannerPhrases() {
        SearchQueryPlan callA = planWithExactTerms(
                "ziskovost penzijnich fondu",
                List.of(),
                List.of("pension fund return on equity", "retirement assets performance"),
                List.of());
        SearchQueryPlan callB = planWithExactTerms(
                "ziskovost penzijnich fondu",
                List.of(),
                List.of("pension scheme profit ratio", "fund investment results"),
                List.of());

        List<String> stable = expander.deterministicSearchTerms(callA);
        List<String> expandedA = expander.expand(callA);
        List<String> expandedB = expander.expand(callB);

        assertThat(expandedA.subList(0, stable.size())).containsExactlyElementsOf(stable);
        assertThat(expandedB.subList(0, stable.size())).containsExactlyElementsOf(stable);
    }

    @Test
    void deterministicSearchTermsIncludeMetricAndSectorRegistryEvidenceFromRawQueryAlone() {
        List<String> terms = expander.deterministicSearchTerms(
                planWithExactTerms(
                        "ziskovost pojistoven",
                        List.of(),
                        List.of("something the llm made up"),
                        List.of()));

        assertThat(terms).contains("ziskovost pojistoven");
        assertThat(terms.stream().anyMatch(t -> t.toLowerCase().contains("insurance") || t.toLowerCase().contains("pojist")))
                .as("sector evidence must come from the raw query, not the fabricated LLM term")
                .isTrue();
    }

    @Test
    void llmTermNamingADifferentRegisteredMetricThanThePlanIsRejected() {
        // Plan's own deterministic metric intent is "debt" (from the raw query itself); an LLM term
        // that names "cost" instead (a different, specifically registered metric) must be dropped.
        SearchQueryPlan plan = planWithMetricIntent(
                "zadluzeni domacnosti", List.of("debt"), List.of("naklady domacnosti na bydleni", "zadluzeni domacnosti"));

        List<String> variants = expander.expand(plan);

        assertThat(variants).noneMatch(v -> v.toLowerCase().contains("naklady domacnosti na bydleni"));
    }

    @Test
    void llmTermWithNoRegisteredMetricIsNeverRejectedByTheConflictFilter() {
        // "free_metric_intent" (unregistered term) must never be treated as a conflict - only a term
        // that resolves to a DIFFERENT, specifically registered metric is rejected.
        SearchQueryPlan plan = planWithMetricIntent(
                "zadluzeni domacnosti", List.of("debt"), List.of("household debt statistics overview", "zadluzeni domacnosti"));

        List<String> variants = expander.expand(plan);

        assertThat(variants).anyMatch(v -> v.toLowerCase().contains("household debt statistics overview"));
    }

    private static SearchQueryPlan planWithMetricIntent(
            String query, List<String> metricIntents, List<String> exactTerms) {
        return new SearchQueryPlan(
                query,
                "cs",
                "find_series",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                exactTerms,
                List.of(query),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "openai",
                null,
                cz.bankintel.search.v2.schema.ExactEntityResolution.openTopic("test"),
                cz.bankintel.search.v2.schema.SourceRoutingDecision.empty(),
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                metricIntents,
                List.of());
    }

    private static SearchQueryPlan plan(String query, List<String> geographies) {
        return planWithExactTerms(query, geographies, List.of(query), List.of());
    }

    private static SearchQueryPlan planWithExactTerms(
            String query, List<String> geographies, List<String> exactTerms, List<String> primaryConcepts) {
        return new SearchQueryPlan(
                query,
                "cs",
                "find_series",
                primaryConcepts,
                List.of(),
                geographies,
                List.of(),
                List.of(),
                List.of(),
                null,
                exactTerms,
                List.of(query),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "local_fallback",
                null);
    }

    private static SearchQueryPlan exactPlan(String query, ResolutionResult resolved) {
        return exactPlan(query, List.of(), resolved);
    }

    private static SearchQueryPlan exactPlan(String query, List<String> geographies, ResolutionResult resolved) {
        return new SearchQueryPlan(
                query,
                "en",
                "find_series",
                List.of(resolved.entityResolution().canonicalName()),
                List.of(),
                geographies,
                List.of(),
                List.of(),
                List.of(),
                null,
                resolved.queryVariants().stream().filter(v -> v.firstPassExactRole()).map(v -> v.text()).toList(),
                List.of(resolved.entityResolution().canonicalName()),
                List.of(),
                resolved.entityResolution().relatedEntities(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "exact_entity_resolver",
                null,
                resolved.entityResolution(),
                resolved.sourceRouting(),
                resolved.queryVariants());
    }
}
