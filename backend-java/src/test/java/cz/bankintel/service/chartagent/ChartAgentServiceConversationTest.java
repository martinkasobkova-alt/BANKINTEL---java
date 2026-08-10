package cz.bankintel.service.chartagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.service.research.WebResearchService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChartAgentServiceConversationTest {

    @Test
    @SuppressWarnings("unchecked")
    void resolvedFollowupIsUsedForWebResearchAndActiveAnnotationsReachResearchContext() {
        ChartAgentEconomistService economist = mock(ChartAgentEconomistService.class);
        ChartAgentPlanner planner = mock(ChartAgentPlanner.class);
        WebResearchService research = mock(WebResearchService.class);
        ChartAgentService service = new ChartAgentService(economist, planner, research);
        String resolved = "Dohledat a vyznačit změny českých vlád od roku 2017 do roku 2025.";

        when(planner.planChartQuestion(anyString(), anyMap(), anyList(), anyList())).thenReturn(Map.of(
                "operations", List.of("web_event_annotations"),
                "resolved_question", resolved,
                "warnings", List.of()));
        when(research.researchGraphEvents(eq(resolved), anyMap())).thenReturn(Map.of(
                "ok", true,
                "answer_cz", "Hotovo.",
                "chart_actions", List.of()));

        Map<String, Object> response = service.analyzeChartQuestion(Map.of(
                "question", "Vyznač to i od 2017 dál.",
                "chart_contract", Map.of(
                        "title", "Return on equity of banks",
                        "series", List.of(Map.of("id", "main", "label", "Return on equity of banks")),
                        "data", List.of(Map.of(
                                "series_id", "main", "period", "2025", "value_raw", 16.1)),
                        "active_annotations", List.of(Map.of(
                                "label", "Petr Nečas", "from", "2010", "to", "2013"))),
                "conversation_history", List.of(Map.of(
                        "role", "user", "content", "Vyznač změny českých vlád.")),
                "conversation_state", List.of(Map.of(
                        "question", "Vyznač změny českých vlád.",
                        "chart_actions", List.of(Map.of(
                                "type", "annotate_period", "label", "Petr Nečas", "from", "2010", "to", "2013"))))));

        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(research).researchGraphEvents(eq(resolved), context.capture());
        assertThat((List<Map<String, Object>>) context.getValue().get("active_annotations"))
                .extracting(item -> item.get("label"))
                .containsExactly("Petr Nečas");
        assertThat(response.get("answer_cz")).isEqualTo("Hotovo.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sourcedAnnotationExecutesEvenWhenPlannerReturnedAdvisoryWarnings() {
        ChartAgentEconomistService economist = mock(ChartAgentEconomistService.class);
        ChartAgentPlanner planner = mock(ChartAgentPlanner.class);
        WebResearchService research = mock(WebResearchService.class);
        ChartAgentService service = new ChartAgentService(economist, planner, research);

        when(planner.planChartQuestion(anyString(), anyMap(), anyList(), anyList())).thenReturn(Map.of(
                "operations", List.of("web_event_annotations"),
                "resolved_question", "Vyznačit doložené události.",
                "warnings", List.of("Klasifikace může mít více variant.")));
        when(research.researchGraphEvents(anyString(), anyMap())).thenReturn(Map.of(
                "ok", true,
                "answer_cz", "Připraveno.",
                "chart_actions", List.of(Map.of(
                        "type", "annotate_period",
                        "label", "Událost",
                        "from", "2020",
                        "to", "2021",
                        "source_urls", List.of("https://example.test/source")))));

        Map<String, Object> response = service.analyzeChartQuestion(Map.of(
                "question", "Prostě to přidej.",
                "chart_contract", Map.of(
                        "title", "Test",
                        "series", List.of(Map.of("id", "main", "label", "Test")),
                        "data", List.of(Map.of("series_id", "main", "period", "2021", "value_raw", 1)))));

        assertThat(response.get("execution_ready")).isEqualTo(true);
        assertThat((List<String>) response.get("warnings")).isEmpty();
        assertThat((List<Map<String, Object>>) response.get("chart_actions")).hasSize(1);
    }
}
