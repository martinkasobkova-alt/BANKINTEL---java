package cz.bankintel.search.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OpenAiJsonSupport {

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;

    public Map<String, Object> chatJsonObject(String systemPrompt, String userPrompt) {
        return chatJsonObject(systemPrompt, userPrompt, OpenAiModelTask.CHAT);
    }

    public Map<String, Object> chatJsonObject(String systemPrompt, String userPrompt, OpenAiModelTask task) {
        try {
            JsonNode response = openAiClient.chatCompletionJson(systemPrompt, userPrompt, task);
            return objectMapper.convertValue(response, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("OpenAI JSON chat failed: " + ex.getMessage(), ex);
        }
    }

    public static String extractContent(JsonNode response) {
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException("OpenAI returned empty content");
        }
        String text = content.asText().trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start >= 0 && end > start) {
                text = text.substring(start + 1, end).trim();
            }
        }
        return text;
    }
}
