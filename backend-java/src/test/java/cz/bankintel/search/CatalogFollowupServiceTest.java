package cz.bankintel.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.v2.orchestration.SearchV2FeatureFlags;
import cz.bankintel.search.v2.orchestration.SearchV2Service;
import cz.bankintel.service.research.WebResearchService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CatalogFollowupServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiClient openAiClient = mock(OpenAiClient.class);
    private final SearchV2Service searchV2Service = mock(SearchV2Service.class);
    private final SearchV2FeatureFlags searchV2FeatureFlags = mock(SearchV2FeatureFlags.class);
    private final CatalogFollowupService service = new CatalogFollowupService(
            mock(CatalogDeepSearchService.class),
            searchV2Service,
            searchV2FeatureFlags,
            mock(CatalogFollowupActionHandler.class),
            mock(WebResearchService.class),
            openAiClient,
            objectMapper);

    @Test
    void recoversExpiredConversationFromCurrentSearchResults() throws Exception {
        when(openAiClient.chatCompletion(anyString(), anyString()))
                .thenReturn(objectMapper.readTree(
                        "{\"choices\":[{\"message\":{\"content\":\"ROA porovnava ziskovost bank.\"}}]}"));

        Map<String, Object> result = service.followup(null, Map.of(
                "conversation_id", "expired-conversation",
                "message", "Porovnej vybrane rady.",
                "selected_series_refs", List.of(series("ecb2", "CBD2/A.AT.ROA", "ROA Rakousko")),
                "recovery_context", Map.of(
                        "root_query", "zisk bank",
                        "sources", List.of("ecb2"),
                        "found_summary", List.of(series("ecb2", "CBD2/A.AT.ROA", "ROA Rakousko")))));

        assertThat(result).containsEntry("ok", true).containsEntry("conversation_recovered", true);
        assertThat(conversationId(result)).isNotBlank().isNotEqualTo("expired-conversation");
    }

    @Test
    void returnsStructuredRetryableErrorWhenRecoveryContextIsMissing() {
        Map<String, Object> result = service.followup(null, Map.of(
                "conversation_id", "expired-conversation",
                "message", "Porovnej vybrane rady."));

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("error_code", "conversation_not_found")
                .containsEntry("retryable", true);
    }

    @Test
    void validLlmPlanIsNeverReinterpretedByTextRules() throws Exception {
        when(openAiClient.chatCompletion(anyString(), anyString()))
                .thenReturn(objectMapper.readTree(
                        "{\"choices\":[{\"message\":{\"content\":\"{"
                                + "\\\"relation\\\":\\\"same_topic\\\","
                                + "\\\"operation\\\":\\\"answer\\\","
                                + "\\\"action\\\":\\\"chat_over_results\\\","
                                + "\\\"preserve_concept\\\":true,"
                                + "\\\"search_query\\\":\\\"\\\","
                                + "\\\"source_constraints\\\":{\\\"mode\\\":\\\"keep\\\","
                                + "\\\"include\\\":[],\\\"exclude\\\":[]},"
                                + "\\\"confidence\\\":0.92}\"}}]}"));

        Map<String, Object> result = service.resultsIntent(Map.of(
                "message", "no ale co jiny zdroj mimo ecb?",
                "found_summary", List.of(series("ecb2", "CBD2/A.AT.ROA", "ROA Rakousko"))));

        assertThat(result)
                .containsEntry("intent", "continue")
                .containsEntry("action_hint", "chat_over_results")
                .containsEntry("source", "llm");
    }

    @Test
    @SuppressWarnings("unchecked")
    void llmCanPlanAlternativeSourceSearchWithoutDeterministicRewrite() throws Exception {
        when(openAiClient.chatCompletion(anyString(), anyString()))
                .thenReturn(objectMapper.readTree(
                        "{\"choices\":[{\"message\":{\"content\":\"{"
                                + "\\\"relation\\\":\\\"same_topic\\\","
                                + "\\\"operation\\\":\\\"search\\\","
                                + "\\\"action\\\":\\\"find_alternatives\\\","
                                + "\\\"preserve_concept\\\":true,"
                                + "\\\"search_query\\\":\\\"zisk bank\\\","
                                + "\\\"source_constraints\\\":{\\\"mode\\\":\\\"alternatives\\\","
                                + "\\\"include\\\":[],\\\"exclude\\\":[\\\"ecb2\\\"]},"
                                + "\\\"confidence\\\":0.98}\"}}]}"));

        Map<String, Object> result = service.resultsIntent(Map.of(
                "message", "no ale co jiny zdroj mimo ecb?",
                "root_query", "zisk bank",
                "current_sources", List.of("ecb2"),
                "found_summary", List.of(series("ecb2", "CBD2/A.AT.ROA", "ROA Rakousko"))));

        assertThat(result)
                .containsEntry("intent", "refine_search")
                .containsEntry("action_hint", "find_alternatives")
                .containsEntry("source", "llm");
        Map<String, Object> plan = (Map<String, Object>) result.get("followup_plan");
        Map<String, Object> constraints = (Map<String, Object>) plan.get("source_constraints");
        assertThat(constraints.get("exclude")).isEqualTo(List.of("ecb2"));
    }

    @Test
    void unavailableLlmUsesExplicitlyMarkedRegistryFallback() {
        when(openAiClient.chatCompletion(anyString(), anyString())).thenThrow(new IllegalStateException("offline"));

        Map<String, Object> result = service.resultsIntent(Map.of(
                "message", "no ale co jiny zdroj mimo ecb?"));

        assertThat(result)
                .containsEntry("intent", "refine_search")
                .containsEntry("source", "registry_fallback")
                .containsKey("followup_plan");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void alternativeSearchUsesOtherCatalogsAndRetainsOriginalTopic() {
        when(searchV2FeatureFlags.useV2(anyMap())).thenReturn(true);
        when(searchV2Service.search(anyMap())).thenReturn(Map.of(
                "verified", List.of(series("eurostat", "tipsbd40", "Return on equity of banks"))));

        Map<String, Object> result = service.followup(null, Map.of(
                "conversation_id", "expired-conversation",
                "message", "no ale co jiny zdroj mimo ecb?",
                "followup_plan", alternativePlan(),
                "selected_series_refs", List.of(series("ecb2", "CBD2/A.AT.ROA", "ROA Rakousko")),
                "recovery_context", Map.of(
                        "root_query", "zisk bank",
                        "sources", List.of("ecb2"),
                        "found_summary", List.of(series("ecb2", "CBD2/A.AT.ROA", "ROA Rakousko")))));

        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        verify(searchV2Service).search(payload.capture());
        assertThat(payload.getValue().get("q")).isEqualTo("zisk bank");
        List<String> searchedSources = ((List<?>) payload.getValue().get("sources"))
                .stream()
                .map(String::valueOf)
                .toList();
        assertThat(searchedSources)
                .contains("eurostat")
                .doesNotContain("ecb2");
        assertThat(result).containsKey("deep_search_result");
    }

    private static Map<String, Object> series(String source, String setId, String title) {
        return Map.of("source_type", source, "catalog_id", source, "set_id", setId, "title", title);
    }

    private static Map<String, Object> alternativePlan() {
        return Map.of(
                "relation", "same_topic",
                "operation", "search",
                "action", "find_alternatives",
                "preserve_concept", true,
                "search_query", "zisk bank",
                "source_constraints", Map.of(
                        "mode", "alternatives", "include", List.of(), "exclude", List.of("ecb2")),
                "confidence", 0.98,
                "reason_cz", "alternative source",
                "routing_source", "llm");
    }

    @SuppressWarnings("unchecked")
    private static String conversationId(Map<String, Object> result) {
        return String.valueOf(((Map<String, Object>) result.get("conversation")).get("id"));
    }
}
