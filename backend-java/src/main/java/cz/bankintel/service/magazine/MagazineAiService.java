package cz.bankintel.service.magazine;

import com.fasterxml.jackson.databind.JsonNode;
import cz.bankintel.domain.dto.MagazineDtos.AiChatCitation;
import cz.bankintel.domain.dto.MagazineDtos.AiChatResponse;
import cz.bankintel.domain.dto.MagazineDtos.MagazineAiChatRequest;
import cz.bankintel.domain.dto.MagazineDtos.MagazineAiSearchRequest;
import cz.bankintel.domain.dto.MagazineDtos.SearchHit;
import cz.bankintel.domain.dto.MagazineDtos.SearchResponse;
import cz.bankintel.search.openai.OpenAiClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MagazineAiService {

    private static final Pattern OVERVIEW = Pattern.compile(
            "o cem|shrn|souhrn|tema|temata|co resi|co je v cisle|co se pise",
            Pattern.CASE_INSENSITIVE);

    private final MagazineSearchService searchService;
    private final OpenAiClient openAiClient;

    @Value("${OPENAI_API_KEY:}")
    private String openAiApiKey;

    public SearchResponse aiSearch(MagazineAiSearchRequest body) {
        if (!hasOpenAiKey()) {
            return new SearchResponse(body.query(), blankToNull(body.magazineId()), List.of(), openAiDisabledMessage());
        }
        List<SearchHit> hits = searchService.search(
                body.query(), body.magazineId(), body.issueId(), body.limit());
        return new SearchResponse(body.query(), blankToNull(body.magazineId()), hits, null);
    }

    public AiChatResponse aiChat(MagazineAiChatRequest body) {
        List<SearchHit> hits;
        if (isOverviewQuestion(body.query())) {
            hits = searchService.representativeChunks(body.magazineId(), body.issueId(), body.topK());
        } else {
            hits = searchService.search(body.query(), body.magazineId(), body.issueId(), body.topK());
        }
        if (hits.isEmpty()) {
            return new AiChatResponse("K dotazu jsem v archivu nic relevantního nenašla.", List.of(), null);
        }
        if (body.page() != null && body.page() > 0) {
            hits = new ArrayList<>(hits);
            hits.sort(Comparator.comparingInt(h -> h.page() == body.page() ? 0 : 1));
        }
        if (!hasOpenAiKey()) {
            return new AiChatResponse(fallbackAnswer(body.query(), hits), buildCitations(hits), openAiDisabledMessage());
        }
        try {
            String answer = llmAnswer(body, hits);
            return new AiChatResponse(answer, buildCitations(hits), null);
        } catch (Exception e) {
            return new AiChatResponse(fallbackAnswer(body.query(), hits), buildCitations(hits), null);
        }
    }

    private String llmAnswer(MagazineAiChatRequest body, List<SearchHit> hits) throws Exception {
        String system =
                "Jsi analytik finančního archivu. Odpovídáš česky, věcně a konkrétně, vycházíš z dodaného kontextu (citací).";
        String user = buildChatPrompt(body.query(), hits, body.page());
        JsonNode response = openAiClient.chatCompletion(system, user);
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "AI nevrátila odpověď.";
        }
        String content = choices.get(0).path("message").path("content").asText("").trim();
        return content.isEmpty() ? "AI nevrátila odpověď." : content;
    }

    private static String buildChatPrompt(String question, List<SearchHit> hits, Integer currentPage) {
        StringBuilder blocks = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            var issue = h.issue();
            String body = h.textFull() != null && !h.textFull().isBlank() ? h.textFull() : h.snippet();
            blocks.append("[C")
                    .append(i + 1)
                    .append("] časopis=")
                    .append(issue == null ? "" : issue.magazineTitle())
                    .append(" číslo=")
                    .append(issue == null ? "" : issue.issueLabel())
                    .append(" strana=")
                    .append(h.page())
                    .append("\n")
                    .append(body == null ? "" : body)
                    .append("\n\n");
        }
        String joined = blocks.length() == 0 ? "Bez kontextu." : blocks.toString().trim();
        String pageLine = "";
        if (currentPage != null && currentPage > 0) {
            pageLine = "Čtenář má právě otevřenou stranu " + currentPage + ".\n";
        }
        return "Odpověz česky věcně a konkrétně a vycházej z dodaných citací.\n"
                + "Ke každému tvrzení přidej citaci ve formátu [C1], [C2], ...\n\n"
                + pageLine
                + "Dotaz:\n"
                + question
                + "\n\nKontext:\n"
                + joined;
    }

    private static String fallbackAnswer(String question, List<SearchHit> hits) {
        if (hits.isEmpty()) {
            return "V archivu jsem k dotazu nenašla dostatek textu pro odpověď.";
        }
        boolean overview = isOverviewQuestion(question);
        String intro = overview
                ? "Podle načtených pasáží je číslo hlavně o těchto tématech:"
                : "Podle načtených pasáží odpověď vychází takto:";
        List<String> lines = new ArrayList<>();
        lines.add(intro);
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int idx = 0; idx < Math.min(hits.size(), 6); idx++) {
            SearchHit hit = hits.get(idx);
            var issue = hit.issue();
            int page = hit.page();
            String key = (issue == null ? "" : issue.id()) + ":" + page;
            if (!used.add(key)) {
                continue;
            }
            String snippet = hit.snippet() == null ? "" : hit.snippet().replaceAll("\\s+", " ").trim();
            if (snippet.isEmpty()) {
                continue;
            }
            if (snippet.length() > 210) {
                snippet = snippet.substring(0, 207).trim() + "...";
            }
            String label = page > 0 ? "str. " + page : "bez strany";
            String issueLabel = issue == null ? "" : issue.issueLabel();
            if (issueLabel != null && !issueLabel.isBlank()) {
                label = issueLabel + ", " + label;
            }
            lines.add("- " + label + ": " + snippet + " [C" + (idx + 1) + "]");
        }
        if (lines.size() == 1) {
            return "Text je v archivu načtený, ale nepodařilo se z něj sestavit čitelnou odpověď.";
        }
        return String.join("\n", lines);
    }

    private static List<AiChatCitation> buildCitations(List<SearchHit> hits) {
        List<AiChatCitation> citations = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            var issue = h.issue();
            citations.add(new AiChatCitation(
                    "C" + (i + 1),
                    issue == null ? null : issue.id(),
                    issue == null ? null : issue.issueLabel(),
                    issue == null ? null : issue.magazineId(),
                    issue == null ? null : issue.magazineTitle(),
                    h.page(),
                    h.snippet()));
        }
        return citations;
    }

    private boolean hasOpenAiKey() {
        return openAiApiKey != null && !openAiApiKey.isBlank();
    }

    private static String openAiDisabledMessage() {
        return "OPENAI_API_KEY není nastaven — AI odpovědi jsou vypnuté.";
    }

    private static boolean isOverviewQuestion(String query) {
        if (query == null) {
            return false;
        }
        String folded = MagazineSearchService.foldText(query);
        return OVERVIEW.matcher(folded).find();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
