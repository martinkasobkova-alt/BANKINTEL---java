package cz.bankintel.service.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.openai.OpenAiClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebResearchServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiClient openAiClient = mock(OpenAiClient.class);
    private final WebResearchService service = new WebResearchService(openAiClient, objectMapper);

    @BeforeEach
    void setUp() {
        service.loadTrustRegistry();
    }

    @Test
    @SuppressWarnings("unchecked")
    void emitsOnlyCitedEventAnnotations() {
        when(openAiClient.webSearch(anyString(), anyString())).thenReturn(response(
                "resp_test",
                """
                {"answer_cz":"Dolozene udalosti.","events":[{"label":"Financni krize","start_period":"2008","end_period":"2009","display_mode":"band","color":"red","summary_cz":"Propad aktivity.","source_urls":["https://www.imf.org/example"]},{"label":"Bez zdroje","start_period":"2011","end_period":"2012","source_urls":["https://invalid.example/x"]}]}
                """,
                List.of(Map.of("url", "https://www.imf.org/example/#chronology", "title", "IMF chronology"))));

        Map<String, Object> result = service.researchGraphEvents(
                "Vyznač krize", Map.of("start_period", "2000", "end_period", "2025"));

        List<Map<String, Object>> actions = (List<Map<String, Object>>) result.get("chart_actions");
        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst())
                .containsEntry("type", "annotate_period")
                .containsEntry("from", "2008")
                .containsEntry("to", "2009")
                .containsEntry("display_mode", "band")
                .containsEntry("color", "red");
        assertThat(result.get("answer_cz")).isEqualTo("Dohledal jsem jednu ověřenou anotaci pro graf.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void incompleteTimelineIsRepairedBeforeItCanReachTheChart() {
        when(openAiClient.webSearch(anyString(), anyString())).thenReturn(
                response(
                        "resp_partial",
                        """
                        {"answer_cz":"Partial.","events":[
                          {"label":"First","start_period":"2007","end_period":"2009","display_mode":"band","color":"blue","layer_id":"governments","replace_layer":true,"source_urls":["https://www.psp.cz/timeline"]},
                          {"label":"Last","start_period":"2021","end_period":"2025","display_mode":"band","color":"blue","layer_id":"governments","replace_layer":true,"source_urls":["https://www.psp.cz/timeline"]}
                        ]}
                        """,
                        List.of(Map.of("url", "https://www.psp.cz/timeline", "title", "Timeline"))),
                response(
                        "resp_repaired",
                        """
                        {"answer_cz":"Complete.","events":[
                          {"label":"A","start_period":"2000","end_period":"2009","display_mode":"band","color":"red","layer_id":"governments","replace_layer":true,"source_urls":["https://www.psp.cz/timeline"]},
                          {"label":"B","start_period":"2010","end_period":"2019","display_mode":"band","color":"gray","layer_id":"governments","replace_layer":true,"source_urls":["https://www.psp.cz/timeline"]},
                          {"label":"C","start_period":"2020","end_period":"2025","display_mode":"band","color":"blue","layer_id":"governments","replace_layer":true,"source_urls":["https://www.psp.cz/timeline"]}
                        ]}
                        """,
                        List.of(Map.of("url", "https://www.psp.cz/timeline", "title", "Timeline"))));

        Map<String, Object> result = service.researchGraphEvents(
                "Add the complete government timeline",
                Map.of("start_period", "2000", "end_period", "2025"));

        List<Map<String, Object>> actions = (List<Map<String, Object>>) result.get("chart_actions");
        assertThat(actions).extracting(action -> action.get("label")).containsExactly("A", "B", "C");
        assertThat(result.get("model_output_id")).isEqualTo("resp_repaired");
    }

    @Test
    @SuppressWarnings("unchecked")
    void externalDatasetDiscoveryDropsNonOfficialDomains() {
        when(openAiClient.webSearch(anyString(), anyString())).thenReturn(response(
                "resp_data",
                """
                {"datasets":[{"title":"Eurostat data","publisher":"Eurostat","url":"https://ec.europa.eu/eurostat/data/database","format":"SDMX"},{"title":"Unknown mirror","publisher":"Unknown","url":"https://example.com/data.csv","format":"CSV"}]}
                """,
                List.of(
                        Map.of("url", "https://ec.europa.eu/eurostat/data/database", "title", "Eurostat"),
                        Map.of("url", "https://example.com/data.csv", "title", "Mirror"))));

        Map<String, Object> result = service.discoverExternalDatasets("missing data", Map.of());

        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("external_items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("publisher", "Eurostat");
    }

    @Test
    @SuppressWarnings("unchecked")
    void researchSectorContextKeepsOfficialAndPressFindingsButDropsUnregisteredDomains() {
        when(openAiClient.webSearch(anyString(), anyString())).thenReturn(response(
                "resp_sector",
                """
                {"answer_cz":"Nalezeno.","findings":[
                  {"label_cz":"Podíl Prahy na HDP","value_text":"25 %","period":"2023","summary_cz":"Praha tvoří čtvrtinu HDP.","source_urls":["https://ec.europa.eu/eurostat/example"]},
                  {"label_cz":"Komentář tisku","value_text":"růst","period":"2023","summary_cz":"Patria komentář.","source_urls":["https://patria.cz/example"]},
                  {"label_cz":"Nedůvěryhodný zdroj","value_text":"něco","period":"2023","summary_cz":"blog","source_urls":["https://random-blog.example/post"]}
                ]}
                """,
                List.of(
                        Map.of("url", "https://ec.europa.eu/eurostat/example", "title", "Eurostat"),
                        Map.of("url", "https://patria.cz/example", "title", "Patria"),
                        Map.of("url", "https://random-blog.example/post", "title", "Blog"))));

        Map<String, Object> result = service.researchSectorContext(
                "Jakým významem se Praha podílí na HDP Česka?", Map.of("sector", "", "country", "CZ"));

        List<Map<String, Object>> findings = (List<Map<String, Object>>) result.get("findings");
        assertThat(findings).hasSize(2);
        assertThat(findings).extracting(f -> f.get("source_tier")).containsExactly("official", "press");
        assertThat(result.get("answer_cz")).isEqualTo("Nalezeno.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void researchSectorContextReportsHonestEmptyAnswerWhenNothingIsCited() {
        when(openAiClient.webSearch(anyString(), anyString())).thenReturn(
                response("resp_empty", "{\"findings\":[]}", List.of()));

        Map<String, Object> result = service.researchSectorContext("nesouvisející dotaz", Map.of());

        assertThat((List<Map<String, Object>>) result.get("findings")).isEmpty();
        assertThat(result.get("answer_cz"))
                .isEqualTo("Na webu jsem nenašel dostatečně doložené informace k této otázce.");
    }

    private com.fasterxml.jackson.databind.JsonNode response(
            String id, String outputText, List<Map<String, String>> citations) {
        List<Map<String, Object>> annotations = citations.stream()
                .map(citation -> Map.<String, Object>of(
                        "type", "url_citation",
                        "url", citation.get("url"),
                        "title", citation.get("title")))
                .toList();
        Map<String, Object> content = Map.of(
                "type", "output_text",
                "text", outputText,
                "annotations", annotations);
        return objectMapper.valueToTree(Map.of(
                "id", id,
                "output", List.of(Map.of("type", "message", "content", List.of(content)))));
    }
}
