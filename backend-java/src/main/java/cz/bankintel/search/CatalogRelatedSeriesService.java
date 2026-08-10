package cz.bankintel.search;

import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.openai.OpenAiJsonSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogRelatedSeriesService {

    private static final List<String> DEFAULT_SOURCES = CatalogSourceRegistry.RELATED_SERIES_DEFAULT_SOURCES;

    private final CatalogMultiSearchService multiSearchService;
    private final OpenAiClient openAiClient;
    private final OpenAiJsonSupport openAiJsonSupport;

    public Map<String, Object> findRelated(Map<String, Object> body) {
        Map<String, Object> meta = buildMeta(body);
        boolean aiUsed = false;
        String query = buildRelatedQuery(meta);
        if (openAiClient.isConfigured()) {
            try {
                Map<String, Object> aiQuery = openAiJsonSupport.chatJsonObject(
                        """
                        Jsi datový kurátor. Navrhni tematický vyhledávací dotaz pro nalezení příbuzných ekonomických řad.
                        Vrať JSON: {"search_query":"..."} — dotaz v češtině nebo angličtině, max 120 znaků.
                        """,
                        buildAiQueryPrompt(meta));
                String aiSearch = stringOrBlank(aiQuery.get("search_query"));
                if (!aiSearch.isBlank()) {
                    query = aiSearch;
                    aiUsed = true;
                }
            } catch (Exception ex) {
                log.warn("OpenAI related-series query failed: {}", ex.getMessage());
            }
        }
        List<String> sources = normalizeSources(parseSources(body.get("sources")));
        int limit = parseLimit(body.get("limit"));
        Map<String, Object> searchPayload = new LinkedHashMap<>();
        searchPayload.put("query", query);
        searchPayload.put("q", query);
        searchPayload.put("sources", sources);
        searchPayload.put("limit", limit * 3);
        searchPayload.put("limit_per_source", Math.max(3, limit));
        Map<String, Object> search = multiSearchService.multiSearch(searchPayload);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) search.getOrDefault("results", List.of());
        List<Map<String, Object>> shortlist = dedupeAndCap(results, meta, Math.max(limit * 2, 12));
        List<Map<String, Object>> trimmed = shortlist;
        if (openAiClient.isConfigured() && shortlist.size() > limit) {
            try {
                List<Map<String, Object>> reranked = rerankWithAi(meta, query, shortlist, limit);
                if (!reranked.isEmpty()) {
                    trimmed = reranked;
                    aiUsed = true;
                }
            } catch (Exception ex) {
                log.warn("OpenAI related-series rerank failed: {}", ex.getMessage());
            }
        }
        if (trimmed.size() > limit) {
            trimmed = trimmed.subList(0, limit);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        long distinctSourceCount = trimmed.stream()
                .map(CatalogRelatedSeriesService::candidateSource)
                .map(source -> source.toLowerCase(Locale.ROOT))
                .filter(source -> !source.isBlank())
                .distinct()
                .count();
        out.put("ok", true);
        out.put("query", query);
        out.put("source_type", meta.get("source_type"));
        out.put("set_id", meta.get("set_id"));
        out.put("items", trimmed);
        out.put("related", trimmed);
        out.put("results", trimmed);
        out.put("count", trimmed.size());
        out.put("distinct_source_count", distinctSourceCount);
        out.put("message_cs", trimmed.isEmpty() ? "Nenašel jsem dostatečně relevantní související řady." : "");
        out.put("ai_used", aiUsed);
        out.put("elapsed_ms", search.get("elapsed_ms"));
        return out;
    }

    private List<Map<String, Object>> rerankWithAi(
            Map<String, Object> meta, String query, List<Map<String, Object>> candidates, int limit) throws Exception {
        StringBuilder user = new StringBuilder();
        user.append("Referenční řada: ").append(stringOrBlank(meta.get("title"))).append('\n');
        user.append("Dotaz: ").append(query).append('\n');
        user.append("Kandidáti (index|source|set_id|title):\n");
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> row = candidates.get(i);
            user.append(i)
                    .append('|')
                    .append(candidateSource(row))
                    .append('|')
                    .append(stringOrBlank(row.get("set_id")))
                    .append('|')
                    .append(stringOrBlank(row.get("title")))
                    .append('\n');
        }
        user.append("Vyber max ").append(limit).append(" nejrelevantnějších indexů. Vrať JSON: {\"selected_indices\":[0,1,...]}");
        Map<String, Object> parsed = openAiJsonSupport.chatJsonObject(
                "Vybíráš tematicky nejbližší statistické řady. Vracej pouze JSON s polem selected_indices.",
                user.toString());
        Object raw = parsed.get("selected_indices");
        if (!(raw instanceof List<?> indices)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Integer> seen = new java.util.LinkedHashSet<>();
        for (Object idxObj : indices) {
            int idx = idxObj instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(idxObj));
            if (idx < 0 || idx >= candidates.size() || !seen.add(idx)) {
                continue;
            }
            out.add(candidates.get(idx));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private static String buildAiQueryPrompt(Map<String, Object> meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("Název: ").append(stringOrBlank(meta.get("title"))).append('\n');
        append(sb, "Indikátor", meta.get("indicator_name"));
        append(sb, "Zdroj", meta.get("source_type"));
        append(sb, "Země", meta.get("country_label"));
        append(sb, "Set ID", meta.get("set_id"));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, Object value) {
        String s = stringOrBlank(value);
        if (!s.isBlank()) {
            sb.append(label).append(": ").append(s).append('\n');
        }
    }

    private static List<String> normalizeSources(List<String> sources) {
        List<String> out = new ArrayList<>();
        for (String source : sources) {
            String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
            if ("oecd".equals(normalized)) {
                normalized = "oecd4";
            }
            if (!normalized.isBlank() && !out.contains(normalized)) {
                out.add(normalized);
            }
        }
        return out.isEmpty() ? DEFAULT_SOURCES : out;
    }

    private static Map<String, Object> buildMeta(Map<String, Object> body) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (body == null) {
            return meta;
        }
        for (String key :
                List.of(
                        "source_type",
                        "catalog_id",
                        "set_id",
                        "dataset_id",
                        "title",
                        "name",
                        "country_label",
                        "geo_label",
                        "selected_indicator_name",
                        "indicator_name",
                        "limit")) {
            if (body.containsKey(key)) {
                meta.put(key, body.get(key));
            }
        }
        if (!meta.containsKey("source_type") && body.containsKey("catalog_id")) {
            meta.put("source_type", body.get("catalog_id"));
        }
        return meta;
    }

    private static String buildRelatedQuery(Map<String, Object> meta) {
        String title = stringOrBlank(meta.get("selected_indicator_name"));
        if (title.isBlank()) {
            title = stringOrBlank(meta.get("indicator_name"));
        }
        if (title.isBlank()) {
            title = stringOrBlank(meta.get("title"));
        }
        if (title.isBlank()) {
            title = stringOrBlank(meta.get("name"));
        }
        String country = stringOrBlank(meta.get("country_label"));
        if (country.isBlank()) {
            country = stringOrBlank(meta.get("geo_label"));
        }
        if (!country.isBlank() && !title.toLowerCase(Locale.ROOT).contains(country.toLowerCase(Locale.ROOT))) {
            return (title + " " + country).trim();
        }
        return title.isBlank() ? "economic indicator" : title;
    }

    private static List<Map<String, Object>> dedupeAndCap(
            List<Map<String, Object>> results, Map<String, Object> reference, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        String referenceSource = firstNonBlank(reference, "source_type", "catalog_id").toLowerCase(Locale.ROOT);
        String referenceSetId = firstNonBlank(reference, "set_id", "dataset_id").toLowerCase(Locale.ROOT);
        for (Map<String, Object> row : results) {
            String source = candidateSource(row).toLowerCase(Locale.ROOT);
            String setId = firstNonBlank(row, "set_id", "dataset_id").toLowerCase(Locale.ROOT);
            String key = source + "|" + setId;
            boolean isReferenceSeries = !referenceSetId.isBlank()
                    && referenceSetId.equals(setId)
                    && (referenceSource.isBlank() || referenceSource.equals(source));
            if (key.equals("|") || isReferenceSeries || seen.contains(key)) {
                continue;
            }
            seen.add(key);
            out.add(row);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private static String candidateSource(Map<String, Object> row) {
        return firstNonBlank(row, "source", "source_type", "catalog_id", "catalog_label");
    }

    private static String firstNonBlank(Map<String, Object> values, String... keys) {
        if (values == null) {
            return "";
        }
        for (String key : keys) {
            String value = stringOrBlank(values.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static List<String> parseSources(Object raw) {
        if (raw instanceof List<?> list && !list.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                String s = stringOrBlank(item);
                if (!s.isBlank()) {
                    out.add(s);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        return DEFAULT_SOURCES;
    }

    private static int parseLimit(Object raw) {
        if (raw instanceof Number n) {
            return Math.max(1, Math.min(n.intValue(), 12));
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(String.valueOf(raw).trim()), 12));
        } catch (NumberFormatException ex) {
            return 5;
        }
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
