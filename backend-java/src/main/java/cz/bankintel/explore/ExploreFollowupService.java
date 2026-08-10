package cz.bankintel.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.openai.OpenAiClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExploreFollowupService {

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;

    public Map<String, Object> followup(Map<String, Object> body) {
        String question = str(body.get("followup_question"));
        if (question.length() < 2) {
            return Map.of("ok", false, "error", "Dotaz musí mít alespoň 2 znaky.", "reason", "question_too_short");
        }
        String sectionTitle = str(body.get("section_title"));
        String sectionText = str(body.get("section_text"));
        String originalQuestion = str(body.get("question"));
        String priorContext = str(body.get("prior_data_context"));
        try {
            String system =
                    """
                    Jsi senior český ekonomický analytik. Uživatel se doptává k hotové sekci manažerské analýzy.
                    Vrať pouze JSON objekt:
                    {"followup_answer":"...","updated_section_text":"","key_points":["..."]}
                    followup_answer: 2-6 vět v češtině.
                    key_points: max 4 stručné body s čísly/fakty z kontextu.
                    updated_section_text: nech prázdné, pokud stačí krátká odpověď.
                    """;
            String userPrompt =
                    "Původní dotaz: "
                            + originalQuestion
                            + "\nSekce: "
                            + sectionTitle
                            + "\nText sekce: "
                            + sectionText
                            + "\nKontext dat: "
                            + priorContext
                            + "\nDoplňující dotaz: "
                            + question;
            JsonNode response = openAiClient.chatCompletion(system, userPrompt);
            String content = response.path("choices").path(0).path("message").path("content").asText("").trim();
            ParsedFollowup parsed = parseFollowup(content);
            if (parsed.answer().isBlank()) {
                throw new IllegalStateException("empty followup answer");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("followup_answer", parsed.answer());
            out.put("updated_section_text", parsed.updatedSectionText());
            out.put("key_points", parsed.keyPoints());
            out.put("supporting_drivers", List.of());
            out.put("ai_used", true);
            return out;
        } catch (Exception ex) {
            return Map.of(
                    "ok", false,
                    "error",
                    "Doplňující odpověď se nepodařila vygenerovat. "
                            + (ex.getMessage() != null ? ex.getMessage() : ""));
        }
    }

    private ParsedFollowup parseFollowup(String content) throws Exception {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        if (trimmed.startsWith("{")) {
            JsonNode node = objectMapper.readTree(trimmed);
            List<String> keyPoints = new ArrayList<>();
            if (node.path("key_points").isArray()) {
                node.path("key_points").forEach(item -> {
                    String point = item.asText("").trim();
                    if (!point.isBlank()) {
                        keyPoints.add(point);
                    }
                });
            }
            return new ParsedFollowup(
                    node.path("followup_answer").asText("").trim(),
                    node.path("updated_section_text").asText("").trim(),
                    keyPoints.stream().limit(4).toList());
        }
        return new ParsedFollowup(trimmed, "", List.of());
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).strip() : "";
    }

    private record ParsedFollowup(String answer, String updatedSectionText, List<String> keyPoints) {}
}
