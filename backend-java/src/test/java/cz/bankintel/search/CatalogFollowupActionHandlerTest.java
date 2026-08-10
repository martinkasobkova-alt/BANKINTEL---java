package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.service.calculations.ComputedIndicatorRunner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogFollowupActionHandlerTest {

    @Mock
    private ComputedIndicatorRunner computedRunner;

    private CatalogFollowupActionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CatalogFollowupActionHandler(computedRunner);
    }

    @Test
    void explainIndicatorWithoutRefs() {
        var result = handler.handle("explain_indicator", "vysvetli", List.of(), "u1");
        assertTrue(result.assistantAnswer().contains("vyberte"));
    }

    @Test
    void explainIndicatorWithInflationTitle() {
        var result = handler.handle(
                "explain_indicator",
                "vysvetli",
                List.of(Map.of("title", "HICP inflation rate", "set_id", "x", "source_type", "eurostat")),
                "u1");
        assertTrue(result.assistantAnswer().toLowerCase().contains("cen"));
    }

    @Test
    void computeRatioNeedsTwoSeries() {
        var result = handler.handle(
                "compute_ratio", "pomer", List.of(Map.of("set_id", "a", "source_type", "fred")), "u1");
        assertTrue(result.assistantAnswer().contains("dvě"));
    }

    @Test
    void computeRatioRunsEngine() {
        when(computedRunner.run(any(), anyString()))
                .thenReturn(new ComputedIndicatorRunner.RunResult(
                        List.of(Map.of("period", "2020", "value", 1.5)), List.of(), Map.of()));
        var result = handler.handle(
                "compute_ratio",
                "pomer",
                List.of(
                        Map.of("set_id", "a", "source_type", "fred", "title", "A"),
                        Map.of("set_id", "b", "source_type", "fred", "title", "B")),
                "u1");
        assertTrue(result.assistantAnswer().contains("1.5"));
        assertFalse(result.computationResult().isEmpty());
    }

    @Test
    void computeDifferenceRunsEngine() {
        when(computedRunner.run(any(), anyString()))
                .thenReturn(new ComputedIndicatorRunner.RunResult(
                        List.of(Map.of("period", "2020", "value", -2.0)), List.of(), Map.of()));
        var result = handler.handle(
                "compute_difference",
                "rozdil",
                List.of(
                        Map.of("set_id", "a", "source_type", "fred"),
                        Map.of("set_id", "b", "source_type", "fred")),
                "u1");
        assertTrue(result.assistantAnswer().contains("-2.0"));
    }

    @Test
    void composeMultiNeedsSeries() {
        when(computedRunner.run(any(), anyString()))
                .thenReturn(new ComputedIndicatorRunner.RunResult(
                        List.of(Map.of("period", "2020", "s1", 1.0)), List.of(), Map.of()));
        var result = handler.handle(
                "compose_multi_chart",
                "graf",
                List.of(Map.of("set_id", "a", "source_type", "fred", "title", "A")),
                "u1");
        assertTrue(result.assistantAnswer().contains("multi-graf"));
        assertTrue(result.computationResult().get("chart_payload") instanceof Map<?, ?>);
        Map<?, ?> chartPayload = (Map<?, ?>) result.computationResult().get("chart_payload");
        assertTrue(Boolean.TRUE.equals(chartPayload.get("multi_series")));
        assertTrue(chartPayload.get("rows") instanceof List<?>);
        assertTrue(chartPayload.get("series") instanceof List<?>);
    }

    @Test
    void composePreservesPreviewParametersRequiredBySourceLoader() {
        when(computedRunner.run(any(), anyString()))
                .thenReturn(new ComputedIndicatorRunner.RunResult(
                        List.of(Map.of("period", "2020", "s1", 1.0, "s2", 2.0)), List.of(), Map.of()));
        handler.handle(
                "add_to_chart",
                "add gold",
                List.of(
                        Map.of(
                                "set_id", "inflation",
                                "source_type", "imf",
                                "title", "Inflation",
                                "query_params", Map.of("country", "US")),
                        Map.of(
                                "set_id", "gold",
                                "source_type", "fred",
                                "title", "Gold",
                                "query_params", Map.of("series_id", "GOLDAMGBD228NLBM"))),
                "u1");

        ArgumentCaptor<cz.bankintel.domain.entity.ComputedIndicatorEntity> captor =
                ArgumentCaptor.forClass(cz.bankintel.domain.entity.ComputedIndicatorEntity.class);
        verify(computedRunner).run(captor.capture(), anyString());
        assertTrue(captor.getValue().getSeries().stream()
                .allMatch(row -> row.containsKey("query_params")));
    }
}
