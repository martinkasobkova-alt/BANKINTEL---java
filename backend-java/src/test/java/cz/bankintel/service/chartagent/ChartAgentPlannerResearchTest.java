package cz.bankintel.service.chartagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.openai.OpenAiJsonSupport;
import cz.bankintel.search.openai.OpenAiModelTask;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ChartAgentPlannerResearchTest {

    @Test
    @SuppressWarnings("unchecked")
    void fallbackRecognizesEventAnnotationWithoutReplacingOrdinaryAnalytics() {
        Map<String, Object> research = ChartAgentPlanner.planWithRegex(
                "Vyznač do grafu období krizí podle internetu", Map.of("series", List.of(Map.of())));
        Map<String, Object> analytics = ChartAgentPlanner.planWithRegex(
                "Spočítej korelaci mezi řadami", Map.of("series", List.of(Map.of(), Map.of())));

        assertThat((List<String>) research.get("operations")).contains("web_event_annotations");
        assertThat((List<String>) analytics.get("operations"))
                .contains("correlation_matrix")
                .doesNotContain("web_event_annotations", "discover_external_data");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallbackKeepsAnnotationRemovalOutOfCatalogSearch() {
        Map<String, Object> result = ChartAgentPlanner.planWithRegex(
                "oddelej ty vlady", Map.of("series", List.of(Map.of())));

        assertThat((List<String>) result.get("operations")).containsExactly("clear_period_annotations");
        assertThat((List<String>) result.get("chart_action_intents")).containsExactly("clear_period_annotations");
    }

    @Test
    void llmPlannerReceivesPriorActionsAndActiveAnnotationsForEllipticalFollowup() throws Exception {
        OpenAiJsonSupport openAi = mock(OpenAiJsonSupport.class);
        ChartAgentPlanner planner = new ChartAgentPlanner(openAi, new ObjectMapper());
        ReflectionTestUtils.setField(planner, "openAiApiKey", "test-key");
        ArgumentCaptor<String> contextJson = ArgumentCaptor.forClass(String.class);
        when(openAi.chatJsonObject(anyString(), contextJson.capture(), eq(OpenAiModelTask.PLANNER)))
                .thenReturn(Map.of(
                        "intent", "web_event_annotations",
                        "operations", List.of("web_event_annotations"),
                        "chart_action_intents", List.of("annotate_period"),
                        "resolved_question", "Dohledat a vyznačit změny českých vlád od roku 2017 do konce grafu.",
                        "needs_confirmation", false,
                        "followup_question_cz", "",
                        "warnings", List.of()));

        Map<String, Object> result = planner.planChartQuestion(
                "Vyznač to i od 2017 dál.",
                Map.of(
                        "series", List.of(Map.of("label", "Return on equity of banks")),
                        "active_annotations", List.of(Map.of(
                                "label", "Petr Nečas", "from", "2010", "to", "2013"))),
                List.of(
                        Map.of("role", "user", "content", "Vyznač, kdy se měnila česká vláda."),
                        Map.of("role", "assistant", "content", "Změny vlád jsem vyznačil do roku 2014.")),
                List.of(Map.of(
                        "question", "Vyznač, kdy se měnila česká vláda.",
                        "chart_actions", List.of(Map.of(
                                "type", "annotate_period", "label", "Petr Nečas", "from", "2010", "to", "2013")))));

        assertThat(result.get("resolved_question"))
                .isEqualTo("Dohledat a vyznačit změny českých vlád od roku 2017 do konce grafu.");
        assertThat(contextJson.getValue())
                .contains("Vyznač, kdy se měnila česká vláda.", "Petr Nečas", "active_annotations");
    }
}
