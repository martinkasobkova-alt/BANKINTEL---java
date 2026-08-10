package cz.bankintel.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Contextual follow-up query composition with data-driven source and topic lexicons. */
public final class CatalogFollowupQuerySupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final FollowupQueryLexicon LEXICON = loadFollowupQueryLexicon();
    private static final Pattern ROA_WORD =
            Pattern.compile("(?<![a-z0-9])roa(?![a-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROE_WORD =
            Pattern.compile("(?<![a-z0-9])roe(?![a-z0-9])", Pattern.CASE_INSENSITIVE);

    static final List<String> FOLLOWUP_DEFAULT_SOURCE_POOL = CatalogSourceRegistry.FOLLOWUP_DEFAULT_SOURCE_POOL;

    private CatalogFollowupQuerySupport() {}

    public static String buildContextualFollowupQuery(String message, Map<String, Object> context) {
        String msg = normalizeWhitespace(message);
        if (msg.isBlank()) {
            return "";
        }
        Map<String, Object> ctx = context != null ? context : Map.of();
        if (messageLooksLikeNewTopic(msg, ctx)) {
            return truncate(msg, 700);
        }
        if (isConversationalSourceRequest(msg)) {
            String focus = firstNonBlank(
                    str(ctx.get("current_focus_query")),
                    str(ctx.get("root_query")),
                    str(ctx.get("last_search_query")));
            return focus.isBlank() ? truncate(msg, 700) : truncate(focus, 700);
        }
        if (!requestedSourcesFromMessage(msg).isEmpty()
                && (!topicMarkers(msg).isEmpty() || !explicitMetricFocus(msg).isBlank() || looksIndicatorOnlyRefine(msg))) {
            return truncate(msg, 700);
        }

        String rootQuery = firstNonBlank(str(ctx.get("root_query")), str(ctx.get("last_query")));
        String currentFocus = firstNonBlank(
                str(ctx.get("current_focus_query")), str(ctx.get("last_search_query")), rootQuery);
        String lastSearch = firstNonBlank(str(ctx.get("last_search_query")), str(ctx.get("last_query")));
        String metricFocus = firstNonBlank(explicitMetricFocus(msg), contextMetricFocus(ctx));
        String topicAnchor = filterTopicAnchorForMetric(str(ctx.get("topic_anchor")), metricFocus);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> foundSummary =
                ctx.get("found_series_summary") instanceof List<?> list
                        ? (List<Map<String, Object>>) list
                        : List.of();

        List<String> parts = new ArrayList<>();
        appendUniquePhrase(parts, msg);

        boolean mustAddContext = msg.split("\\s+").length <= 4
                || looksIndicatorOnlyRefine(msg)
                || looksGenericRefinePrompt(msg);
        if (!currentFocus.isBlank() && (mustAddContext || !containsPhrase(String.join(" ", parts), currentFocus))) {
            appendUniquePhrase(parts, currentFocus);
        }
        if (!rootQuery.isBlank() && (mustAddContext || !containsPhrase(String.join(" ", parts), rootQuery))) {
            appendUniquePhrase(parts, rootQuery);
        }
        if (!topicAnchor.isBlank() && !containsPhrase(String.join(" ", parts), topicAnchor)) {
            appendUniquePhrase(parts, topicAnchor, 160);
        }
        if (!lastSearch.isBlank()
                && !lastSearch.equals(rootQuery)
                && !lastSearch.equals(currentFocus)
                && mustAddContext) {
            appendUniquePhrase(parts, lastSearch, 180);
        }

        int titleBudget = String.join(" ", parts).length() < 430 ? 3 : 1;
        int titleCount = 0;
        for (Map<String, Object> row : foundSummary) {
            if (titleCount >= titleBudget) {
                break;
            }
            String title = contextTitleForFollowupQuery(str(row.get("title")), metricFocus);
            if (!title.isBlank()) {
                appendUniquePhrase(parts, title, 130);
                titleCount++;
            }
        }

        String out = String.join("; ", parts);
        if (out.length() <= 700) {
            return out;
        }
        int cut = out.lastIndexOf(' ', 697);
        if (cut < 400) {
            cut = 697;
        }
        return out.substring(0, cut).replaceAll("[ ;,]+$", "") + "...";
    }

    public static List<String> requestedSourcesFromMessage(String message) {
        return sourceRequest(message).includedSources();
    }

    public static SourceRequest sourceRequest(String message) {
        String q = fold(message);
        if (q.isBlank()) {
            return SourceRequest.empty();
        }
        List<String> matched = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : LEXICON.sourceAliases().entrySet()) {
            if (anyTermMatches(q, entry.getValue()) && seen.add(entry.getKey())) {
                matched.add(entry.getKey());
            }
        }
        boolean alternativesRequested = anyTermMatches(q, LEXICON.sourceAlternativePhrases());
        boolean exclusionRequested = anyTermMatches(q, LEXICON.sourceExclusionTerms());
        if (exclusionRequested) {
            return new SourceRequest(List.of(), List.copyOf(matched), alternativesRequested || !matched.isEmpty());
        }
        return new SourceRequest(List.copyOf(matched), List.of(), alternativesRequested);
    }

    public static boolean isSearchRefinementRequest(String message) {
        SourceRequest request = sourceRequest(message);
        return request.alternativesRequested()
                || !request.excludedSources().isEmpty()
                || (!request.includedSources().isEmpty() && isConversationalSourceRequest(message))
                || looksGenericRefinePrompt(message);
    }

    public static String topicAnchorFromFoundSummary(List<Map<String, Object>> foundSummary) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (Map<String, Object> row : foundSummary) {
            refs.add(Map.of(
                    "title", str(row.get("title")),
                    "set_id", str(row.get("set_id"))));
        }
        return topicAnchorFromSeriesRefs(refs);
    }

    private static String topicAnchorFromSeriesRefs(List<Map<String, Object>> seriesRefs) {
        String text = seriesRefs.stream()
                .map(r -> fold(r.get("title")))
                .reduce("", (a, b) -> a + " " + b)
                .trim();
        if (text.isBlank()) {
            return "";
        }
        List<String> tags = new ArrayList<>();
        for (TopicAnchorRule rule : LEXICON.topicAnchorRules()) {
            if (anyTermMatches(text, rule.terms())) {
                tags.addAll(rule.tags());
            }
        }
        return String.join(" ", uniquePreserveOrder(tags));
    }

    private static List<String> uniquePreserveOrder(List<String> items) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String item : items) {
            if (seen.add(item)) {
                out.add(item);
            }
        }
        return out;
    }

    private static boolean isConversationalSourceRequest(String message) {
        String q = fold(message);
        SourceRequest request = sourceRequest(message);
        if (request.includedSources().isEmpty()
                && request.excludedSources().isEmpty()
                && !request.alternativesRequested()) {
            return false;
        }
        return request.alternativesRequested()
                || LEXICON.conversationalSourcePhrases().stream()
                .anyMatch(phrase -> q.startsWith(fold(phrase)) || q.contains(fold(phrase)));
    }

    private static boolean messageLooksLikeNewTopic(String message, Map<String, Object> context) {
        if (message.isBlank()) {
            return false;
        }
        String q = fold(message);
        if (anyTermMatches(q, LEXICON.newTopicSuppressTerms())) {
            return false;
        }
        String base = firstNonBlank(
                str(context.get("root_query")),
                str(context.get("current_focus_query")),
                str(context.get("last_search_query")),
                str(context.get("topic_anchor")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary =
                context.get("found_series_summary") instanceof List<?> list
                        ? (List<Map<String, Object>>) list
                        : List.of();
        String summaryTitles = summary.stream()
                .map(r -> str(r.get("title")))
                .reduce("", (a, b) -> a + " " + b);
        Set<String> baseTopics = topicMarkers(base + " " + summaryTitles);
        Set<String> msgTopics = topicMarkers(message);
        return !msgTopics.isEmpty()
                && !baseTopics.isEmpty()
                && disjoint(msgTopics, baseTopics)
                && message.split("\\s+").length >= 2;
    }

    private static boolean disjoint(Set<String> a, Set<String> b) {
        for (String item : a) {
            if (b.contains(item)) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> topicMarkers(String text) {
        String q = fold(text);
        Set<String> out = new LinkedHashSet<>();
        for (TopicMarker marker : LEXICON.topicMarkers()) {
            if (anyTermMatches(q, marker.terms())) {
                out.add(marker.id());
            }
        }
        return out;
    }

    private static String explicitMetricFocus(String text) {
        String q = fold(text);
        if (q.isBlank()) {
            return "";
        }
        boolean hasRoa = metricFocusMatches(q, "roa");
        boolean hasRoe = metricFocusMatches(q, "roe");
        if (hasRoa && !hasRoe) {
            return "roa";
        }
        if (hasRoe && !hasRoa) {
            return "roe";
        }
        return "";
    }

    private static boolean metricFocusMatches(String foldedText, String metric) {
        List<String> aliases = LEXICON.metricFocusAliases().getOrDefault(metric, List.of(metric));
        if (anyTermMatches(foldedText, aliases)) {
            return true;
        }
        if ("roa".equals(metric)) {
            return ROA_WORD.matcher(foldedText).find() || foldedText.contains("return on assets");
        }
        if ("roe".equals(metric)) {
            return ROE_WORD.matcher(foldedText).find() || foldedText.contains("return on equity");
        }
        return false;
    }

    private static String contextMetricFocus(Map<String, Object> context) {
        String stored = fold(context.get("metric_focus"));
        if (stored.isBlank()) {
            stored = fold(context.get("current_metric_focus"));
        }
        if ("roa".equals(stored) || "roe".equals(stored)) {
            return stored;
        }
        for (String key : List.of("current_focus_query", "last_search_query", "last_query")) {
            String focus = explicitMetricFocus(str(context.get(key)));
            if (!focus.isBlank()) {
                return focus;
            }
        }
        return "";
    }

    private static String filterTopicAnchorForMetric(String anchor, String metricFocus) {
        if (metricFocus.isBlank()) {
            return anchor.trim();
        }
        List<String> out = new ArrayList<>();
        for (String token : anchor.split("\\s+")) {
            String up = token.toUpperCase(Locale.ROOT).replaceAll("[,;]", "");
            if ("EVROPA".equals(up) || "EU".equals(up)) {
                continue;
            }
            if ("roa".equals(metricFocus) && "ROE".equals(up)) {
                continue;
            }
            if ("roe".equals(metricFocus) && "ROA".equals(up)) {
                continue;
            }
            out.add(token);
        }
        return String.join(" ", out).trim();
    }

    private static String contextTitleForFollowupQuery(String title, String metricFocus) {
        String s = normalizeWhitespace(title);
        if (s.isBlank() || metricConflictsFocus(s, metricFocus)) {
            return "";
        }
        s = s.replaceAll("(?i)\\bforeign\\s*\\(\\s*eu\\s+and\\s+non-eu\\s*\\)", "foreign");
        s = s.replaceAll("(?i)\\beu\\s+and\\s+non-eu\\b", "");
        return normalizeWhitespace(s);
    }

    private static boolean metricConflictsFocus(String text, String metricFocus) {
        if ("roa".equals(metricFocus)) {
            return textHasMetric(text, "roe") && !textHasMetric(text, "roa");
        }
        if ("roe".equals(metricFocus)) {
            return textHasMetric(text, "roa") && !textHasMetric(text, "roe");
        }
        return false;
    }

    private static boolean textHasMetric(String text, String metric) {
        String q = fold(text);
        return metricFocusMatches(q, metric);
    }

    private static boolean looksIndicatorOnlyRefine(String message) {
        String q = fold(message);
        if (q.isBlank() || q.split("\\s+").length > 4) {
            return false;
        }
        return anyTermMatches(q, LEXICON.indicatorOnlyTerms());
    }

    private static boolean looksGenericRefinePrompt(String message) {
        return anyTermMatches(fold(message), LEXICON.genericRefinePhrases());
    }

    private static boolean anyTermMatches(String foldedText, List<String> rawTerms) {
        if (foldedText.isBlank() || rawTerms.isEmpty()) {
            return false;
        }
        for (String rawTerm : rawTerms) {
            if (termMatches(foldedText, rawTerm)) {
                return true;
            }
        }
        return false;
    }

    private static boolean termMatches(String foldedText, String rawTerm) {
        String term = fold(rawTerm).trim();
        if (term.isBlank()) {
            return false;
        }
        if (term.contains(" ")) {
            return foldedText.contains(term);
        }
        if (term.length() <= 4) {
            return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(term) + "(?![a-z0-9])")
                    .matcher(foldedText)
                    .find();
        }
        return foldedText.contains(term);
    }

    private static boolean containsPhrase(String haystack, String needle) {
        String h = normalizeWhitespace(haystack).toLowerCase(Locale.ROOT);
        String n = normalizeWhitespace(needle).toLowerCase(Locale.ROOT);
        return !n.isBlank() && h.contains(n);
    }

    private static void appendUniquePhrase(List<String> parts, String phrase) {
        appendUniquePhrase(parts, phrase, 220);
    }

    private static void appendUniquePhrase(List<String> parts, String phrase, int maxLen) {
        String p = truncate(normalizeWhitespace(phrase), maxLen);
        if (p.isBlank()) {
            return;
        }
        String joined = String.join(" ", parts);
        if (containsPhrase(joined, p)) {
            return;
        }
        for (String existing : parts) {
            if (existing.length() >= 8 && containsPhrase(p, existing)) {
                return;
            }
        }
        parts.add(p);
    }

    private static FollowupQueryLexicon loadFollowupQueryLexicon() {
        try (InputStream in =
                CatalogFollowupQuerySupport.class.getResourceAsStream("/catalog/followup_query_lexicon.json")) {
            if (in == null) {
                return FollowupQueryLexicon.empty();
            }
            JsonNode root = MAPPER.readTree(in);
            return new FollowupQueryLexicon(
                    readStringListMap(root.path("source_aliases")),
                    readTopicMarkers(root.path("topic_markers")),
                    readTopicAnchorRules(root.path("topic_anchor_tags")),
                    readStringList(root.path("conversational_source_phrases")),
                    readStringList(root.path("source_alternative_phrases")),
                    readStringList(root.path("source_exclusion_terms")),
                    readStringList(root.path("new_topic_suppress_terms")),
                    readStringList(root.path("indicator_only_terms")),
                    readStringList(root.path("generic_refine_phrases")),
                    readStringListMap(root.path("metric_focus_aliases")));
        } catch (Exception ex) {
            return FollowupQueryLexicon.empty();
        }
    }

    private static List<TopicMarker> readTopicMarkers(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<TopicMarker> out = new ArrayList<>();
        for (JsonNode item : node) {
            String id = item.path("id").asText("").trim();
            List<String> terms = readStringList(item.path("terms"));
            if (!id.isBlank() && !terms.isEmpty()) {
                out.add(new TopicMarker(id, terms));
            }
        }
        return List.copyOf(out);
    }

    private static List<TopicAnchorRule> readTopicAnchorRules(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<TopicAnchorRule> out = new ArrayList<>();
        for (JsonNode item : node) {
            List<String> tags = readStringList(item.path("tags"));
            List<String> terms = readStringList(item.path("terms"));
            if (!tags.isEmpty() && !terms.isEmpty()) {
                out.add(new TopicAnchorRule(tags, terms));
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, List<String>> readStringListMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            List<String> values = readStringList(entry.getValue());
            if (!values.isEmpty()) {
                out.put(entry.getKey(), values);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    private static String normalizeWhitespace(String text) {
        return str(text).replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen).trim();
    }

    private static String fold(Object value) {
        return CatalogTextUtils.foldAscii(str(value));
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

    private record FollowupQueryLexicon(
            Map<String, List<String>> sourceAliases,
            List<TopicMarker> topicMarkers,
            List<TopicAnchorRule> topicAnchorRules,
            List<String> conversationalSourcePhrases,
            List<String> sourceAlternativePhrases,
            List<String> sourceExclusionTerms,
            List<String> newTopicSuppressTerms,
            List<String> indicatorOnlyTerms,
            List<String> genericRefinePhrases,
            Map<String, List<String>> metricFocusAliases) {
        static FollowupQueryLexicon empty() {
            return new FollowupQueryLexicon(
                    Map.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of());
        }
    }

    public record SourceRequest(
            List<String> includedSources, List<String> excludedSources, boolean alternativesRequested) {
        static SourceRequest empty() {
            return new SourceRequest(List.of(), List.of(), false);
        }
    }

    private record TopicMarker(String id, List<String> terms) {}

    private record TopicAnchorRule(List<String> tags, List<String> terms) {}
}
