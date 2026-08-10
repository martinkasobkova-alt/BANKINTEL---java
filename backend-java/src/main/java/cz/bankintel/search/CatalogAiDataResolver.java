package cz.bankintel.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.openai.OpenAiJsonSupport;
import cz.bankintel.search.openai.OpenAiModelTask;
import cz.bankintel.util.BankIntelEnvVars;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * "AI over data" selection layer: an LLM re-ranker over the retrieved candidates.
 *
 * <p>Given the user query and the candidate series titles, the model decides which candidates genuinely
 * measure what the user asked (by meaning, not shared keywords) and orders them best-first. This is what
 * lets the app answer "ask anything &rarr; get the right series" without brittle per-topic scoring rules.
 *
 * <p>Purely additive and fail-safe: without an API key (or on any error / disabled flag) the candidates are
 * returned unchanged, so the deterministic lexical pipeline keeps working.
 */
@Service
@RequiredArgsConstructor
public class CatalogAiDataResolver {

    private static final Logger log = LoggerFactory.getLogger(CatalogAiDataResolver.class);

    /** How many top lexical candidates are shown to the model. */
    private static final int MAX_CANDIDATES = 24;
    private static final int MIN_CANDIDATES = 2;
    /** Search-score bonus given to AI-selected rows so the preview pool verifies them first. */
    private static final int AI_PREVIEW_BONUS = 600;

    private static final String SYSTEM_PROMPT =
            """
            You are an expert economic-data librarian for a Czech banking and macro data app. \
            Given a USER QUERY and a numbered list of candidate data series (source + title), select which \
            candidates genuinely measure what the user asked — judge by MEANING, not shared keywords. \
            Reject a series that is a different indicator even if it shares words: e.g. for an exchange-rate \
            query keep only exchange-rate series and reject GDP / unemployment / deficit; for an inflation \
            query reject exchange rates. Respect the requested country or region when the query names one. \
            Return ONLY a JSON object of the form {"relevant":[<index>, ...]} listing the matching candidate \
            indices ordered best-first (most relevant first). Include only indices that truly match; if none \
            match, return {"relevant":[]}.""";

    private final OpenAiJsonSupport openAiJsonSupport;
    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;

    /** True when the row was chosen by the AI resolver as genuinely relevant to the query. */
    public static boolean isAiRelevant(Map<String, Object> row) {
        return row != null && Boolean.TRUE.equals(row.get("_ai_relevant"));
    }

    /** True when the resolver evaluated this row and rejected it as off-topic. */
    public static boolean isAiRejected(Map<String, Object> row) {
        return row != null && Boolean.FALSE.equals(row.get("_ai_relevant"));
    }

    /**
     * Re-order candidates by LLM-judged relevance. Relevant rows come first (best-first) and are flagged so the
     * preview + final-ranking stages prefer them. Never throws; returns the input unchanged when it cannot help.
     */
    public List<Map<String, Object>> rankCandidates(String query, List<Map<String, Object>> candidates) {
        if (candidates == null || candidates.size() < MIN_CANDIDATES || query == null || query.isBlank()) {
            return candidates;
        }
        if (!openAiClient.isConfigured() || BankIntelEnvVars.isFalsy("DEEP_SEARCH_AI_RESOLVER")) {
            return candidates;
        }

        List<Map<String, Object>> ordered = new ArrayList<>(candidates);
        ordered.sort((a, b) -> Integer.compare(searchScore(b), searchScore(a)));
        int window = Math.min(MAX_CANDIDATES, ordered.size());
        List<Map<String, Object>> head = new ArrayList<>(ordered.subList(0, window));
        List<Map<String, Object>> tail = new ArrayList<>(ordered.subList(window, ordered.size()));

        try {
            List<Integer> relevant = askModel(query, head);
            if (relevant == null) {
                return candidates;
            }
            if (relevant.isEmpty() && hasDeterministicStrongMatch(query, head)) {
                log.info("AI data resolver returned no rows despite deterministic exact matches; keeping lexical order.");
                return candidates;
            }
            return applySelection(head, tail, relevant);
        } catch (Exception ex) {
            log.warn("AI data resolver failed, keeping lexical order: {}", ex.getMessage());
            return candidates;
        }
    }

    private List<Integer> askModel(String query, List<Map<String, Object>> head) throws Exception {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < head.size(); i++) {
            Map<String, Object> row = head.get(i);
            list.append(i).append(": [").append(source(row)).append("] ").append(title(row));
            String path = CatalogMapSupport.str(row.get(CatalogKeys.FULL_PATH));
            if (!path.isBlank()) {
                list.append("  (").append(trim(path, 130)).append(")");
            }
            list.append('\n');
        }
        Map<String, Object> result = openAiJsonSupport.chatJsonObject(
                SYSTEM_PROMPT,
                "USER QUERY: " + query + "\n\nCANDIDATES:\n" + list,
                OpenAiModelTask.CHAT);
        if (!(result.get("relevant") instanceof List<?> items)) {
            return null;
        }
        List<Integer> out = new ArrayList<>();
        for (Object item : items) {
            Integer idx = toIndex(item);
            if (idx != null && idx >= 0 && idx < head.size() && !out.contains(idx)) {
                out.add(idx);
            }
        }
        return out;
    }

    private static boolean hasDeterministicStrongMatch(String query, List<Map<String, Object>> candidates) {
        Map<String, Object> geoIntent = CatalogGeoIntent.detectGeoIntent(query);
        CatalogQueryRelevanceProfile profile = CatalogQueryRelevanceProfile.from(query, geoIntent);
        if (profile.groupCount() == 0) {
            return false;
        }
        for (Map<String, Object> row : candidates == null ? List.<Map<String, Object>>of() : candidates) {
            String haystack = CatalogSemanticRowText.haystack(row);
            CatalogQueryRelevanceProfile.SemanticFit fit =
                    profile.match(CatalogSemanticRowText.title(row), haystack);
            CatalogQueryIntent.IntentScoreAdjustments intentAdj =
                    CatalogQueryIntent.computeIntentScoreAdjustments(haystack, query, geoIntent);
            if (intentAdj.negativePenalty() <= 0 && fit.totalHits() >= profile.groupCount()) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> applySelection(
            List<Map<String, Object>> head, List<Map<String, Object>> tail, List<Integer> relevant) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Integer> chosen = new HashSet<>(relevant);
        int rank = 1;
        for (Integer idx : relevant) {
            Map<String, Object> row = head.get(idx);
            row.put("_ai_relevant", true);
            row.put("_ai_rank", rank);
            row.put(CatalogKeys.SEARCH_SCORE, searchScore(row) + AI_PREVIEW_BONUS + Math.max(0, 40 - rank) * 5);
            out.add(row);
            rank++;
        }
        for (int i = 0; i < head.size(); i++) {
            if (!chosen.contains(i)) {
                Map<String, Object> row = head.get(i);
                row.put("_ai_relevant", false);
                out.add(row);
            }
        }
        out.addAll(tail);
        return out;
    }

    private static int searchScore(Map<String, Object> row) {
        return CatalogMapSupport.toInt(row.get(CatalogKeys.SEARCH_SCORE), 0);
    }

    private static String source(Map<String, Object> row) {
        String s = CatalogMapSupport.str(row.getOrDefault(CatalogKeys.SOURCE_TYPE, row.get(CatalogKeys.CATALOG_ID)));
        return s.isBlank() ? "?" : s;
    }

    private static String title(Map<String, Object> row) {
        String t = CatalogMapSupport.firstNonBlank(row.get(CatalogKeys.TITLE), row.get(CatalogKeys.NAME));
        return trim(t, 160);
    }

    private static Integer toIndex(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(CatalogMapSupport.str(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String trim(String text, int max) {
        String s = text == null ? "" : text.trim();
        return s.length() <= max ? s : s.substring(0, max).trim();
    }
}
