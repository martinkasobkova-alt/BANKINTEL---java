package cz.bankintel.search.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import cz.bankintel.search.CatalogMultiSearchService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the predictor-relevance sanity filter in {@link ForecastPlannerService}: the domain ->
 * predictor map decides WHICH economic concepts to look for, but the catalog's full-text search
 * can still hand back an unrelated series for a given concept (e.g. an insurance-claims dataset
 * for a "wages" query) — the planner must not accept that as if it were economically relevant.
 */
@ExtendWith(MockitoExtension.class)
class ForecastPlannerServiceTest {

    @Mock private CatalogMultiSearchService multiSearchService;

    private ForecastPlannerService planner;

    @BeforeEach
    void setUp() {
        planner = new ForecastPlannerService(multiSearchService);
    }

    @Test
    void rejectsIrrelevantHitAndFallsBackToRelevantOne() {
        // First candidate returned by the search is a clearly unrelated title (no keyword overlap
        // with the "wages" search query); the second one is a genuine wage series.
        when(multiSearchService.multiSearch(any())).thenReturn(Map.of(
                "results",
                List.of(
                        Map.of("source_type", "arad", "set_id", "1067:XYZ", "title", "Pojišťovny - Počet nahlášených pojistných událostí"),
                        Map.of("source_type", "eurostat", "set_id", "enpe_wg_m", "title", "Average nominal monthly wages and salaries"))));

        ForecastPlannerService.PlanResult plan = planner.plan("Inflace CR (HICP rocni zmena)", "eurostat", "prc_hicp_manr", "CZ");

        assertTrue(plan.domainKey().isPresent());
        assertEquals("price_inflation", plan.domainKey().get());
        boolean anyIrrelevantPicked = plan.candidates().stream().anyMatch(c -> c.setId().equals("1067:XYZ"));
        assertFalse(anyIrrelevantPicked, "planner must not accept a keyword-irrelevant search hit as a predictor");
        boolean relevantWagesPicked = plan.candidates().stream()
                .anyMatch(c -> "wages".equals(c.role()) && c.setId().equals("enpe_wg_m"));
        assertTrue(relevantWagesPicked, "planner should still find the genuinely relevant wages series");
    }

    @Test
    void neverPicksTheTargetSeriesItselfAsItsOwnPredictor() {
        when(multiSearchService.multiSearch(any())).thenReturn(Map.of(
                "results",
                List.of(
                        Map.of("source_type", "eurostat", "set_id", "prc_hicp_manr", "title", "Inflace CR (HICP rocni zmena)"),
                        Map.of("source_type", "eurostat", "set_id", "teimf040", "title", "3-month-interest rate"))));

        ForecastPlannerService.PlanResult plan = planner.plan("Inflace CR (HICP rocni zmena)", "eurostat", "prc_hicp_manr", "CZ");

        boolean selfPicked = plan.candidates().stream().anyMatch(c -> c.setId().equals("prc_hicp_manr"));
        assertFalse(selfPicked);
    }

    @Test
    void returnsEmptyPlanWhenNoDomainMatches() {
        ForecastPlannerService.PlanResult plan = planner.plan("Počet obyvatel obce Kunratice", "csu", "some_set", "CZ");
        assertFalse(plan.domainKey().isPresent());
        assertTrue(plan.candidates().isEmpty());
    }

    @Test
    void returnsUpToTwoRankedCandidatesPerConceptWithDiscoveryScore() {
        // Two distinct, relevant wage series for the same "wages" concept — the planner should
        // surface both (top-N = 2), not just the first one, and rank the geo-matching one first
        // with a higher discoveryScore.
        when(multiSearchService.multiSearch(any())).thenReturn(Map.of(
                "results",
                List.of(
                        Map.of("source_type", "fred", "set_id", "wage_generic", "title", "Average wages earnings index"),
                        Map.of("source_type", "eurostat", "set_id", "enpe_wg_m", "title", "Average nominal monthly wages and salaries CZ"))));

        ForecastPlannerService.PlanResult plan = planner.plan("Inflace CR (HICP rocni zmena)", "eurostat", "prc_hicp_manr", "CZ");

        long wageCandidates = plan.candidates().stream().filter(c -> "wages".equals(c.role())).count();
        assertEquals(2, wageCandidates, "should return up to top-N=2 candidates for the wages concept");
        ForecastPlannerService.PredictorCandidate best = plan.candidates().stream()
                .filter(c -> "wages".equals(c.role()))
                .max((a, b) -> Double.compare(a.discoveryScore(), b.discoveryScore()))
                .orElseThrow();
        assertEquals("enpe_wg_m", best.setId(), "geo-matching hit should rank first / score highest");
        assertTrue(best.discoveryScore() > 0.0 && best.discoveryScore() <= 1.0);
    }
}
