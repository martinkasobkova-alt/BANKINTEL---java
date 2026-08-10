package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.SearchPlan;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.v2.planner.SearchV2QueryPlanner;
import cz.bankintel.search.v2.schema.ExactEntityResolution;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SearchQueryVariant;
import cz.bankintel.search.v2.schema.SourceRoutingDecision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogQueryPlannerAiToggleTest {

    @Mock
    private OpenAiClient openAiClient;

    @Mock
    private SearchV2QueryPlanner searchV2QueryPlanner;

    private CatalogQueryPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new CatalogQueryPlanner(openAiClient, searchV2QueryPlanner);
    }

    @Test
    void useAiFalseForcesLocalPlanWithoutTouchingOpenAi() {
        SearchPlan plan = planner.planTyped("nezamestnanost Slovensko", List.of("eurostat", "fred"), false);

        assertEquals("local", plan.planner());
        assertTrue(plan.sources().contains("eurostat"), "expected local planner to keep relevant requested sources");
        Map<String, Object> map = plan.toMap();
        assertTrue(map.get(CatalogKeys.SEMANTIC_PROFILE) instanceof Map<?, ?>);
        assertEquals("metric_geo", map.get(CatalogKeys.QUERY_SHAPE));
        verifyNoInteractions(openAiClient);
        verifyNoInteractions(searchV2QueryPlanner);
    }

    @Test
    void useAiTrueUsesOpenAiWhenConfigured() throws Exception {
        when(searchV2QueryPlanner.plan(anyMap()))
                .thenReturn(v2Plan(
                        "nezamestnanost Slovensko",
                        List.of("unemployment_rate"),
                        List.of("SK"),
                        List.of("eurostat"),
                        List.of("unemployment rate", "nezamestnanost slovensko")));

        SearchPlan plan = planner.planTyped("nezamestnanost Slovensko", List.of("eurostat", "fred"), true);

        assertEquals("openai", plan.planner());
        assertTrue(plan.sources().contains("eurostat"));
        assertTrue(plan.searchTerms().contains("unemployment rate"), "terms=" + plan.searchTerms());
        verify(searchV2QueryPlanner).plan(anyMap());
        verify(openAiClient, never()).isConfigured();
    }

    @Test
    void openAiPlanKeepsLocalBankingRecallTermsAheadOfModelTerms() throws Exception {
        when(searchV2QueryPlanner.plan(anyMap()))
                .thenReturn(v2Plan(
                        "roa bank",
                        List.of("bank_profitability"),
                        List.of(),
                        List.of("ecb2", "bis", "fred", "oecd4"),
                        List.of("return on assets (roa) bank", "bank profitability return on assets", "bank return on assets ratio")));

        SearchPlan plan = planner.planTyped("roa bank", List.of("ecb2", "bis", "fred", "oecd4"), true);

        List<String> firstTerms = plan.searchTerms().subList(0, Math.min(5, plan.searchTerms().size()));
        String firstTermsFolded = CatalogTextUtils.foldAscii(String.join(" ", firstTerms));
        String allTermsFolded = CatalogTextUtils.foldAscii(String.join(" ", plan.searchTerms()));
        assertTrue(
                firstTermsFolded.contains("bank"),
                "banking recall terms must survive before the FTS cap: " + plan.searchTerms());
        assertTrue(allTermsFolded.contains("return on assets"), "model terms should still be preserved: " + plan.searchTerms());
    }

    @Test
    void openAiSourceRoutingKeepsHeuristicCatalogsInsideRequestedScope() throws Exception {
        when(searchV2QueryPlanner.plan(anyMap()))
                .thenReturn(v2Plan(
                        "roa bank",
                        List.of("bank_profitability"),
                        List.of(),
                        List.of("ecb2", "fred"),
                        List.of("bank return on assets ratio")));

        SearchPlan plan = planner.planTyped(
                "roa bank", List.of("arad", "csu", "ecb2", "eurostat", "bis", "fred", "oecd4"), true);

        assertTrue(plan.sources().contains("ecb2"), "heuristic ECB source must not be lost: " + plan.sources());
    }

    @Test
    void openAiSourceRoutingPromotesStrongFxSourcesAboveBadModelSources() throws Exception {
        when(searchV2QueryPlanner.plan(anyMap()))
                .thenReturn(v2Plan(
                        "eurusd",
                        List.of("exchange_rate"),
                        List.of(),
                        List.of("ecb2", "fred"),
                        List.of("eurusd", "EUR/USD exchange rate")));

        SearchPlan plan = planner.planTyped(
                "eurusd",
                List.of("arad", "csu", "eurostat", "ecb2", "fred", "imf", "data360", "bis", "oecd4"),
                true);

        List<String> firstThree = plan.sources().subList(0, Math.min(3, plan.sources().size()));
        assertTrue(firstThree.contains("ecb2"), "FX route must keep ECB near the top: " + plan.sources());
        assertTrue(firstThree.contains("fred"), "FX route must keep FRED near the top: " + plan.sources());
    }

    @Test
    void explicitSourceFromQueryPlanIsAHardLegacyRoute() throws Exception {
        SearchQueryPlan routed = v2Plan(
                "bank ROE Slovakia ECB",
                List.of("bank_profitability"),
                List.of("SK"),
                List.of("ecb2", "eurostat", "imf"),
                List.of("bank return on equity Slovakia"));
        routed = new SearchQueryPlan(
                routed.originalQuery(), routed.language(), routed.intent(), routed.primaryConcepts(),
                routed.supportingConcepts(), routed.geographies(), List.of("ecb2"),
                routed.frequencyPreferences(), routed.unitPreferences(), routed.timeScope(),
                routed.exactSearchTerms(), routed.semanticSearchTerms(), routed.translatedSearchTerms(),
                routed.relatedSearchTerms(), routed.excludedMeanings(), routed.desiredResultRoles(),
                routed.clarification(), routed.plannerStatus(), routed.model(),
                routed.entityResolution(), routed.sourceRouting(), routed.queryVariants());
        when(searchV2QueryPlanner.plan(anyMap())).thenReturn(routed);

        SearchPlan plan = planner.planTyped(
                "bank ROE Slovakia ECB",
                List.of("ecb2", "eurostat", "imf", "fred"),
                true);

        assertEquals(List.of("ecb2"), plan.sources());
    }

    @Test
    void missingOpenAiConfigurationFallsBackToLocalPlan() {
        when(searchV2QueryPlanner.plan(anyMap())).thenThrow(new IllegalStateException("planner unavailable"));

        SearchPlan plan = planner.planTyped("HDP Nemecko", List.of("eurostat", "oecd4"), true);

        assertEquals("local", plan.planner());
        verify(openAiClient, never()).isConfigured();
    }

    private static SearchQueryPlan v2Plan(
            String query, List<String> concepts, List<String> geos, List<String> sources, List<String> terms) {
        return new SearchQueryPlan(
                query,
                "cs",
                "find_series",
                concepts,
                List.of(),
                geos,
                List.of(),
                List.of(),
                List.of(),
                null,
                terms,
                terms,
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "openai",
                "test-planner",
                ExactEntityResolution.openTopic("test"),
                new SourceRoutingDecision(List.of("macro"), sources, List.of(), Map.of()),
                terms.stream().map(term -> new SearchQueryVariant(term, "professional_synonym", 0.8)).toList());
    }
}
