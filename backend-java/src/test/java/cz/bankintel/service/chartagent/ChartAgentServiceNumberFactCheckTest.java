package cz.bankintel.service.chartagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.bankintel.service.research.WebResearchService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ověřuje, že ChartAgentService opravdu volá kontrolu čísel a promítá ji do warnings. */
class ChartAgentServiceNumberFactCheckTest {

    private static Map<String, Object> contractWithTwoPoints() {
        return Map.of(
                "title", "Test",
                "series", List.of(Map.of("id", "main", "label", "Test")),
                "data", List.of(
                        Map.of("series_id", "main", "period", "2024", "value_raw", 100.0),
                        Map.of("series_id", "main", "period", "2025", "value_raw", 110.0)));
    }

    private static ChartAgentService serviceWithLlmAnswer(
            ChartAgentEconomistService economist, ChartAgentPlanner planner, String llmAnswer) {
        when(planner.planChartQuestion(anyString(), anyMap(), anyList(), anyList()))
                .thenReturn(Map.of("operations", List.of("summary"), "warnings", List.of()));
        when(economist.economistAnswer(
                        anyString(), anyMap(), anyList(), anyList(), anyMap(), anyString(), anyList(), anyMap()))
                .thenReturn(llmAnswer);
        return new ChartAgentService(economist, planner, mock(WebResearchService.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void neoverenaCisloVOdpovediModeluVyvolaVarovani() {
        ChartAgentEconomistService economist = mock(ChartAgentEconomistService.class);
        ChartAgentPlanner planner = mock(ChartAgentPlanner.class);
        ChartAgentService service = serviceWithLlmAnswer(
                economist, planner, "Hodnota vzrostla na 127,5, což je významný nárůst.");

        Map<String, Object> response = service.analyzeChartQuestion(
                Map.of("question", "Jak se vyvíjí řada?", "chart_contract", contractWithTwoPoints()));

        List<String> warnings = (List<String>) response.get("warnings");
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("127,5"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cislaOdpovidajiciDatumVarovaniNevyvolaji() {
        ChartAgentEconomistService economist = mock(ChartAgentEconomistService.class);
        ChartAgentPlanner planner = mock(ChartAgentPlanner.class);
        ChartAgentService service = serviceWithLlmAnswer(
                economist, planner, "Hodnota vzrostla ze 100 na 110, meziročně o 10 %.");

        Map<String, Object> response = service.analyzeChartQuestion(
                Map.of("question", "Jak se vyvíjí řada?", "chart_contract", contractWithTwoPoints()));

        List<String> warnings = (List<String>) response.get("warnings");
        assertThat(warnings).noneMatch(w -> w.contains("nepodařilo dohledat"));
    }
}
