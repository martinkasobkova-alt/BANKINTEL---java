package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.openai.OpenAiModelTask;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExploreSectionedSynthesisPoliticalTest {

    @Mock
    private OpenAiClient openAiClient;

    @Mock
    private ExploreSectionBucketService bucketService;

    private ExploreSectionedSynthesisService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ExploreSectionedSynthesisService(openAiClient, bucketService);
    }

    @Test
    void politicalSituation_runsWithoutSeriesAndKeepsSources() throws Exception {
        when(openAiClient.isConfigured()).thenReturn(true);
        when(bucketService.bucketItemsBySection(any(), anyString())).thenReturn(Map.of());
        when(bucketService.reportSectionItems(any(), anyString(), anyString())).thenReturn(List.of());

        ObjectNode analysisJson = mapper.createObjectNode();
        analysisJson.put("analysis", "- Signál A z dat\n- Signál B z dat\n- Signál C z dat\n- Shrnutí sekce");
        when(openAiClient.chatCompletionJson(anyString(), anyString(), eq(OpenAiModelTask.CHAT)))
                .thenReturn(analysisJson);

        ObjectNode finalResp = mapper.createObjectNode();
        ArrayNode choices = finalResp.putArray("choices");
        ObjectNode choice = choices.addObject();
        ObjectNode message = choice.putObject("message");
        message.put("content", "- Verdikt 1\n- Verdikt 2\n- Verdikt 3\n- Verdikt 4");
        when(openAiClient.chatCompletion(anyString(), anyString(), eq(OpenAiModelTask.CHAT)))
                .thenReturn(finalResp);

        ObjectNode webRoot = mapper.createObjectNode();
        webRoot.put("id", "resp_pol");
        ArrayNode output = webRoot.putArray("output");
        ObjectNode msg = output.addObject();
        msg.put("type", "message");
        ArrayNode content = msg.putArray("content");
        ObjectNode textNode = content.addObject();
        textNode.put("type", "output_text");
        textNode.put(
                "text",
                "- Stabilní koalice\n- Volby za 18 měsíců\n- Pro polovodiče: smíšené\n- Verdikt: smíšené");
        ArrayNode annotations = textNode.putArray("annotations");
        ObjectNode ann = annotations.addObject();
        ann.put("url", "https://example.com/si-politics");
        ann.put("title", "SI politics");
        when(openAiClient.webSearch(anyString(), anyString())).thenReturn(webRoot);

        ExploreSectionedSynthesisService.SynthesisResult result =
                service.synthesize(new ExploreSectionedSynthesisService.SynthesisRequest(
                        "prodej polovodičů ve Slovinsku", "semiconductors", "SI", List.of()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections =
                (List<Map<String, Object>>) result.payload().get("analysis_sections");
        Map<String, Object> political = sections.stream()
                .filter(s -> "political_situation".equals(s.get("id")))
                .findFirst()
                .orElseThrow();

        assertEquals("Politická situace", political.get("title"));
        assertTrue(String.valueOf(political.get("text")).contains("Verdikt"));
        assertTrue(ExploreSectionedSynthesisService.looksLikeBulletText(String.valueOf(political.get("text"))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) political.get("source_urls");
        assertFalse(sources == null || sources.isEmpty());
        assertEquals("https://example.com/si-politics", sources.get(0).get("url"));

        @SuppressWarnings("unchecked")
        List<String> skipped = (List<String>) result.detailSynthesisMetadata().get("skipped_sections");
        assertFalse(skipped.contains("political_situation"));

        String answer = String.valueOf(result.payload().get("assistant_answer_cz"));
        assertTrue(ExploreSectionedSynthesisService.looksLikeBulletText(answer));
    }
}
