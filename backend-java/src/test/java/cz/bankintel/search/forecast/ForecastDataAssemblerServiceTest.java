package cz.bankintel.search.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.CatalogPreviewOrchestrator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ForecastDataAssemblerServiceTest {

    @Mock private CatalogPreviewOrchestrator previewOrchestrator;
    @Mock private CatalogIndexStore indexStore;

    private ForecastDataAssemblerService assembler;

    @BeforeEach
    void setUp() {
        assembler = new ForecastDataAssemblerService(previewOrchestrator, indexStore);
        when(indexStore.lookupRow(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
    }

    @Test
    void buildsRequestWithNormalizedTargetAndCandidateExogenousSeries() {
        when(previewOrchestrator.fetchRecords(argThatMatchesSetId("prc_hicp_manr")))
                .thenReturn(List.of(
                        Map.of("date", "2024-01", "value", 2.3),
                        Map.of("date", "2024-02", "value", 2.1)));
        when(previewOrchestrator.fetchRecords(argThatMatchesSetId("teimf040")))
                .thenReturn(List.of(
                        Map.of("date", "2024-01", "value", 5.75),
                        Map.of("date", "2024-02", "value", 5.75),
                        Map.of("date", "2024-03", "value", 5.5),
                        Map.of("date", "2024-04", "value", 5.5),
                        Map.of("date", "2024-05", "value", 5.25),
                        Map.of("date", "2024-06", "value", 5.0)));

        ForecastPlannerService.PredictorCandidate candidate = new ForecastPlannerService.PredictorCandidate(
                "policy_rate", "úrokové sazby", "eurostat", "teimf040", "3-month-interest rate", 10, "rate", 1.0);

        Map<String, Object> request = assembler.buildRequest(
                "eurostat",
                "prc_hicp_manr",
                "Inflace CR",
                "CZ",
                Map.of(),
                Map.of("geo", "CZ"),
                "",
                List.of(),
                List.of(candidate),
                List.of("3M", "12M"),
                6_000);

        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) request.get("target");
        assertEquals("eurostat:prc_hicp_manr", target.get("series_id"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidateExog = (List<Map<String, Object>>) request.get("candidate_exog");
        assertEquals(1, candidateExog.size());
        assertEquals("eurostat:teimf040", candidateExog.get(0).get("series_id"));
        assertEquals("policy_rate", candidateExog.get(0).get("role"));
        assertEquals("policy_rate", candidateExog.get(0).get("concept"));
        assertEquals("rate", candidateExog.get(0).get("value_kind"));
        assertEquals(1.0, candidateExog.get(0).get("discovery_score"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> histExog = (List<Map<String, Object>>) request.get("hist_exog");
        assertTrue(histExog.isEmpty(), "hist_exog is decided by the Java model engine, not pre-filled by the assembler");
    }

    @Test
    void dropsCandidateWithTooFewUsableObservationsFromThePool() {
        when(previewOrchestrator.fetchRecords(argThatMatchesSetId("prc_hicp_manr")))
                .thenReturn(List.of(Map.of("date", "2024-01", "value", 2.3)));
        // Only 2 usable rows — below the assembler's own sanity floor of 6 (a fetch/parse
        // problem, not a relevance decision — that's left to the Java model engine).
        when(previewOrchestrator.fetchRecords(argThatMatchesSetId("shortseries")))
                .thenReturn(List.of(Map.of("date", "2024-01", "value", 1.0), Map.of("date", "2024-02", "value", 1.1)));

        ForecastPlannerService.PredictorCandidate candidate = new ForecastPlannerService.PredictorCandidate(
                "wages", "mzdy", "eurostat", "shortseries", "Short series", 5, "index_level", 0.6);

        Map<String, Object> request = assembler.buildRequest(
                "eurostat",
                "prc_hicp_manr",
                "Inflace CR",
                "CZ",
                Map.of(),
                Map.of(),
                "",
                List.of(),
                List.of(candidate),
                List.of(),
                6_000);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidateExog = (List<Map<String, Object>>) request.get("candidate_exog");
        assertTrue(candidateExog.isEmpty(), "series with < 6 usable observations must be dropped before reaching the model engine");
    }

    @Test
    void fetchesMultipleCandidatesForDifferentConceptsConcurrently() {
        when(previewOrchestrator.fetchRecords(argThatMatchesSetId("prc_hicp_manr")))
                .thenReturn(List.of(Map.of("date", "2024-01", "value", 2.3), Map.of("date", "2024-02", "value", 2.1)));
        when(previewOrchestrator.fetchRecords(argThatMatchesSetId("rate_a")))
                .thenReturn(sixMonthlyRows());
        when(previewOrchestrator.fetchRecords(argThatMatchesSetId("wage_a")))
                .thenReturn(sixMonthlyRows());

        List<ForecastPlannerService.PredictorCandidate> candidates = List.of(
                new ForecastPlannerService.PredictorCandidate("policy_rate", "sazby", "eurostat", "rate_a", "Rate A", 10, "rate", 1.0),
                new ForecastPlannerService.PredictorCandidate("wages", "mzdy", "eurostat", "wage_a", "Wage A", 8, "index_level", 0.6));

        Map<String, Object> request = assembler.buildRequest(
                "eurostat",
                "prc_hicp_manr",
                "Inflace CR",
                "CZ",
                Map.of(),
                Map.of(),
                "",
                List.of(),
                candidates,
                List.of(),
                6_000);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidateExog = (List<Map<String, Object>>) request.get("candidate_exog");
        assertEquals(2, candidateExog.size());
    }

    @Test
    void fetchesTargetThroughAnalysisRecordMode() {
        when(previewOrchestrator.fetchRecords(argThatMatchesSetId("DCOILBRENTEU")))
                .thenReturn(sixMonthlyRows());

        assembler.buildRequest(
                "fred",
                "DCOILBRENTEU",
                "Brent",
                "US",
                Map.of(),
                Map.of(),
                "",
                List.of(),
                List.of(),
                List.of("3M"),
                6_000);

        verify(previewOrchestrator).fetchRecords(argThatMatchesSetIdAndAnalysisMode("DCOILBRENTEU"));
    }

    @Test
    void neverDropsExplicitTargetDimensionsWhenTheSelectedSeriesIsEmpty() {
        Map<String, Object> strictFilters = Map.of("geo", "SE", "type", "mortgage");
        when(previewOrchestrator.fetchRecords(argThatMatchesFilters("MIR", strictFilters)))
                .thenReturn(List.of());

        Map<String, Object> request = assembler.buildRequest(
                "ecb",
                "MIR",
                "Mortgage rate",
                "SE",
                Map.of(),
                strictFilters,
                "SE.MORTGAGE",
                List.of("SE.MORTGAGE"),
                List.of(),
                List.of("1Y"),
                6_000);

        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) request.get("target");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> observations = (List<Map<String, Object>>) target.get("observations");
        assertTrue(observations.isEmpty());
        verify(previewOrchestrator, times(1)).fetchRecords(argThatMatchesSetId("MIR"));
    }

    private static List<Map<String, Object>> sixMonthlyRows() {
        return List.of(
                Map.of("date", "2024-01", "value", 1.0),
                Map.of("date", "2024-02", "value", 1.1),
                Map.of("date", "2024-03", "value", 1.2),
                Map.of("date", "2024-04", "value", 1.3),
                Map.of("date", "2024-05", "value", 1.4),
                Map.of("date", "2024-06", "value", 1.5));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argThatMatchesSetId(String setId) {
        return org.mockito.ArgumentMatchers.argThat(m -> m != null && setId.equals(m.get("set_id")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argThatMatchesSetIdAndAnalysisMode(String setId) {
        return org.mockito.ArgumentMatchers.argThat(m -> {
            if (m == null || !setId.equals(m.get("set_id")) || !(m.get("query_params") instanceof Map<?, ?> qp)) {
                return false;
            }
            return "analysis".equals(qp.get("record_mode")) && Integer.valueOf(100_000).equals(qp.get("record_limit"));
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argThatMatchesFilters(String setId, Map<String, Object> filters) {
        return org.mockito.ArgumentMatchers.argThat(m -> m != null
                && setId.equals(m.get("set_id"))
                && filters.equals(m.get("dimension_filters")));
    }
}
