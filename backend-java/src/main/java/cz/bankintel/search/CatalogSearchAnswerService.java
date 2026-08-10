package cz.bankintel.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.openai.OpenAiJsonSupport;
import cz.bankintel.search.openai.OpenAiModelTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CatalogSearchAnswerService {

    private static final Logger log = LoggerFactory.getLogger(CatalogSearchAnswerService.class);

    private final OpenAiJsonSupport openAiJsonSupport;
    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;

    private static final String STORY_SYSTEM_PROMPT =
            """
            You are a data-savvy economist writing for a Czech banking/macro data app. From FACTS (a user query, \
            the verified data series found for it, and related indicators found in the catalogue), write a short \
            DATA STORY in Czech that helps the user understand the topic THROUGH the data.
            Return a single JSON object: {"headline_cz":"...","story_cz":"...","drivers":[{"factor_cz":"...","series_cz":"exact title from FACTS or null"}]}
            Rules for story_cz (3-6 plain Czech sentences, no markdown):
            - First state what the VERIFIED series show for the query (the concrete data the user asked for).
            - Then explain the KEY DRIVERS / context — what influences this indicator — and whenever a related \
            indicator in FACTS matches a driver, name that series so the story is anchored in real data.
            - For forward-looking questions (e.g. "what will electricity prices be"), say which drivers to watch and \
            what the available series suggest, WITHOUT inventing numeric forecasts.
            Grounding: use only the catalog_label/title/set_id present in FACTS. Never invent institutions, series or \
            values. If the data is thin or a driver has no matching series, say so honestly.
            drivers: 2-4 key factors; series_cz = a matching related-indicator title from FACTS when one exists, else null.""";

    /** Backward-compatible plain-text answer (the story narrative). */
    public String composeAnswer(String query, List<Map<String, Object>> verified, List<Map<String, Object>> candidates) {
        return str(composeStory(query, verified, candidates).get("answer_cz"));
    }

    /**
     * PR-7c: the strictly non-interpretive notice for the {@code unverified_only} response status -
     * no verified result exists, only candidates whose preview outcome is
     * {@code SearchV2PreviewOutcome.isUnverifiedBucket(...)} (timeout, transport failure, circuit
     * open, bulkhead rejected, etc.). This method NEVER calls the LLM, regardless of
     * {@code use_ai_story} - see the class-level rationale in {@code SearchV2Service}: an LLM asked to
     * "explain what the data shows" has nothing verified to ground itself in, and a title alone is
     * enough for it to hallucinate a trend/value/confirmation claim about data nobody has actually
     * seen. Only four kinds of statement are possible here: (1) potentially relevant series were
     * found, (2) their current content could not be verified, (3) a safely-aggregated category of why
     * (never a raw preview value or per-candidate detail), (4) the result is unconfirmed. No numeric
     * value, no trend word (rostla/klesala/zhoršuje/zlepšuje), no per-series interpretation ever
     * appears - the deterministic template below is exhaustively enumerable, unlike free LLM text.
     */
    public Map<String, Object> composeUnverifiedNotice(String query, List<Map<String, Object>> unverifiedCandidates) {
        List<Map<String, Object>> safe = unverifiedCandidates == null ? List.of() : unverifiedCandidates;
        String reasonPhrase = aggregateReasonPhrase(safe);
        List<String> titles = new ArrayList<>();
        for (Map<String, Object> row : safe.stream().limit(4).toList()) {
            String title = firstNonBlank(str(row.get("title")), str(row.get("name")));
            if (!title.isBlank()) {
                titles.add(truncate(title, 90));
            }
        }
        String seriesNoun = safe.size() == 1 ? "potenciálně relevantní datovou řadu" : "potenciálně relevantní datové řady";
        StringBuilder sb = new StringBuilder();
        sb.append("K dotazu „").append(truncate(query, 120)).append("“ jsem našel ").append(seriesNoun);
        if (!titles.isEmpty()) {
            sb.append(" (").append(String.join("; ", titles)).append(')');
        }
        sb.append(", jejich aktuální obsah se ale nepodařilo ověřit ").append(reasonPhrase)
                .append(". Výsledek není potvrzený.");
        return story("Ověření se nezdařilo", sb.toString(), List.of(), summarizeRows(safe, 6));
    }

    /**
     * Aggregates the unverified candidates' {@code preview_outcome} values into one safely-generic
     * Czech phrase - never per-candidate detail, never a raw {@code preview_reason} string (which may
     * contain a raw HTTP/exception message), only a coarse category. Priority order matters when
     * several outcomes are mixed: timeout is named first since it is the most actionable/common case.
     */
    private static String aggregateReasonPhrase(List<Map<String, Object>> unverifiedCandidates) {
        boolean anyTimeout = false;
        boolean anyCapacity = false;
        boolean anyTransportOrInternal = false;
        boolean anyCancelled = false;
        for (Map<String, Object> row : unverifiedCandidates) {
            String outcome = str(row.get("preview_outcome")).toLowerCase(Locale.ROOT);
            switch (outcome) {
                case "timeout" -> anyTimeout = true;
                case "circuit_open", "bulkhead_rejected" -> anyCapacity = true;
                case "transport_failure", "internal_failure" -> anyTransportOrInternal = true;
                case "cancelled" -> anyCancelled = true;
                default -> { /* possible/unsupported - no specific category to add */ }
            }
        }
        if (anyTimeout) {
            return "kvůli timeoutu zdroje";
        }
        if (anyCapacity) {
            return "kvůli dočasné nedostupnosti zdroje";
        }
        if (anyTransportOrInternal) {
            return "kvůli technické chybě při ověřování";
        }
        if (anyCancelled) {
            return "kvůli přerušení ověření";
        }
        return "protože ověření nebylo možné dokončit";
    }

    /**
     * Build a grounded "data story": a headline, a narrative that explains the query through the found data and its
     * drivers, and the driver breakdown. Falls back to a deterministic story without an API key.
     */
    public Map<String, Object> composeStory(
            String query, List<Map<String, Object>> verified, List<Map<String, Object>> candidates) {
        return composeStory(query, verified, candidates, true);
    }

    /** Build the story without silently re-enabling AI when the caller explicitly disabled it. */
    public Map<String, Object> composeStory(
            String query,
            List<Map<String, Object>> verified,
            List<Map<String, Object>> candidates,
            boolean useAi) {
        List<Map<String, Object>> primary = !verified.isEmpty() ? verified : candidates;
        if (primary.isEmpty()) {
            return story(
                    "Shrnutí hledání",
                    "V indexu nebyla nalezena použitelná shoda — zkuste jiný pojem, synonymum nebo užší instituci.",
                    List.of(),
                    List.of());
        }
        if (useAi && openAiClient.isConfigured()) {
            try {
                Map<String, Object> llm = llmStory(query, verified, candidates);
                if (llm != null && !str(llm.get("answer_cz")).isBlank()) {
                    return llm;
                }
            } catch (Exception ex) {
                log.warn("catalog data-story LLM failed: {}", ex.getMessage());
            }
        }
        return deterministicStory(query, verified, candidates);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> llmStory(
            String query, List<Map<String, Object>> verified, List<Map<String, Object>> candidates) throws Exception {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("user_query", query);
        facts.put("verified", summarizeRows(verified, 8));
        facts.put("related_indicators", summarizeRows(candidates, 12));
        Map<String, Object> result = openAiJsonSupport.chatJsonObject(
                STORY_SYSTEM_PROMPT, "FACTS JSON:\n" + objectMapper.writeValueAsString(facts), OpenAiModelTask.CHAT);
        String narrative = firstNonBlank(str(result.get("story_cz")), str(result.get("answer_cz")));
        if (narrative.isBlank()) {
            return null;
        }
        List<Map<String, Object>> drivers = result.get("drivers") instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list
                : List.of();
        return story(
                firstNonBlank(str(result.get("headline_cz")), "Příběh dat"),
                narrative.length() > 1400 ? narrative.substring(0, 1400).trim() : narrative,
                drivers,
                summarizeRows(verified.isEmpty() ? candidates : verified, 6));
    }

    private static Map<String, Object> deterministicStory(
            String query, List<Map<String, Object>> verified, List<Map<String, Object>> candidates) {
        List<Map<String, Object>> primary = !verified.isEmpty() ? verified : candidates;
        Map<String, Object> v0 = primary.getFirst();
        String label = firstNonBlank(
                str(v0.get("catalog_label")), CatalogSourceRegistry.label(str(v0.get("catalog_id"))), str(v0.get("catalog_id")));
        String title = firstNonBlank(str(v0.get("title")), str(v0.get("name")), "—");
        StringBuilder sb = new StringBuilder();
        sb.append("K dotazu „").append(truncate(query, 120)).append("“ ");
        sb.append(verified.isEmpty() ? "index našel kandidátní řady, ověření náhledem je ale nepotvrdilo. Nejbližší je "
                : "jsou ověřená data. Hlavní řada: ");
        sb.append(label).append(" — „").append(truncate(title, 140)).append("“.");
        List<String> others = new ArrayList<>();
        for (Map<String, Object> row : primary.stream().skip(1).limit(4).toList()) {
            String t = firstNonBlank(str(row.get("title")), str(row.get("name")));
            if (!t.isBlank()) {
                others.add(truncate(t, 90));
            }
        }
        if (!others.isEmpty()) {
            sb.append(" Související ukazatele k sestavení kontextu: ").append(String.join("; ", others)).append(".");
        }
        return story("Přehled dat k dotazu", sb.toString().trim(), List.of(), summarizeRows(primary, 6));
    }

    private static Map<String, Object> story(
            String headline, String narrative, List<Map<String, Object>> drivers, List<Map<String, Object>> seriesUsed) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headline_cz", headline);
        out.put("answer_cz", narrative);
        out.put("drivers", drivers);
        out.put("series_used", seriesUsed);
        return out;
    }

    private static List<Map<String, Object>> summarizeRows(List<Map<String, Object>> rows, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows.stream().limit(Math.max(0, limit)).toList()) {
            Map<String, Object> brief = new LinkedHashMap<>();
            String catalogId = str(row.get("catalog_id"));
            if (catalogId.isBlank()) {
                catalogId = str(row.get("source_type"));
            }
            brief.put("catalog_id", catalogId.toLowerCase(Locale.ROOT));
            brief.put(
                    "catalog_label",
                    firstNonBlank(
                            str(row.get("catalog_label")),
                            CatalogSourceRegistry.label(catalogId),
                            catalogId));
            brief.put("set_id", str(row.get("set_id")));
            brief.put("title", firstNonBlank(str(row.get("title")), str(row.get("name"))));
            brief.put("full_path", str(row.get("full_path")));
            out.add(brief);
        }
        return out;
    }

    private static String truncate(String text, int maxLen) {
        String s = str(text);
        return s.length() <= maxLen ? s : s.substring(0, maxLen).trim();
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }
}
