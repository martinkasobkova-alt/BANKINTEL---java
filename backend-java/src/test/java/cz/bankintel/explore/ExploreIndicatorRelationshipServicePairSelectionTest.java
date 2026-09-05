package cz.bankintel.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.openai.OpenAiModelTask;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Živě zjištěno: výběr páru řad ke korelaci byl 100% na LLM, bez pravidla, bez determinismu -
 * čtyři shodné běhy stejného dotazu vrátily 4 různé sady. Kořenová příčina: volání šlo přes
 * {@code OpenAiModelTask.CHAT} (teplota 0,2, bez seedu) - stejný mechanismus, co byl už dřív
 * opravený pro plánovač/reranker ({@code OpenAiClient.java}), jen se sem nikdy nedostal.
 */
class ExploreIndicatorRelationshipServicePairSelectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, Object> item(String setId, String title, String category) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("set_id", setId);
        out.put("title", title);
        out.put("source_type", "eurostat");
        out.put("manager_category", category);
        out.put("observations", List.of(
                Map.of("period", "2024-01", "value", 1.0),
                Map.of("period", "2024-02", "value", 2.0),
                Map.of("period", "2024-03", "value", 3.0)));
        return out;
    }

    @Test
    void proposeRelationshipsForcesDeterministicSamplingOnTheChatTask() throws Exception {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.chatCompletionJson(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(MAPPER.readTree("{\"relationships\":[]}"));
        ExploreIndicatorRelationshipService service = new ExploreIndicatorRelationshipService(openAiClient);

        service.analyze(
                List.of(item("a", "HDP", "macro_indicators"), item("b", "Tržby", "sector_indicators")),
                "otázka",
                "sektor");

        verify(openAiClient).chatCompletionJson(any(), any(), eq(OpenAiModelTask.CHAT), eq(true));
    }

    @Test
    void promptIncludesEachCandidatesManagerCategoryForEconomicallySensiblePairing() throws Exception {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.chatCompletionJson(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(MAPPER.readTree("{\"relationships\":[]}"));
        ExploreIndicatorRelationshipService service = new ExploreIndicatorRelationshipService(openAiClient);

        service.analyze(
                List.of(item("a", "HDP", "macro_indicators"), item("b", "Tržby", "sector_indicators")),
                "otázka",
                "sektor");

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(openAiClient).chatCompletionJson(any(), userPrompt.capture(), any(), eq(true));
        assertThat(userPrompt.getValue()).contains("kategorie=macro_indicators").contains("kategorie=sector_indicators");
    }
}
