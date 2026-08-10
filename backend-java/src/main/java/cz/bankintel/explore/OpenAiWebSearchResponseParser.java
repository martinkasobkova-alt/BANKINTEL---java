package cz.bankintel.explore;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared parse of OpenAI Responses API payloads that include web_search output. */
public final class OpenAiWebSearchResponseParser {

    private OpenAiWebSearchResponseParser() {}

    public record Parsed(String responseId, String text, List<SourceRef> sources) {}

    public record SourceRef(String url, String title) {}

    public static Parsed parse(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return new Parsed("", "", List.of());
        }
        StringBuilder text = new StringBuilder();
        LinkedHashMap<String, SourceRef> sources = new LinkedHashMap<>();
        for (JsonNode output : root.path("output")) {
            if ("message".equals(output.path("type").asText(""))) {
                for (JsonNode content : output.path("content")) {
                    if ("output_text".equals(content.path("type").asText(""))) {
                        if (!text.isEmpty()) {
                            text.append('\n');
                        }
                        text.append(content.path("text").asText(""));
                        for (JsonNode annotation : content.path("annotations")) {
                            addSource(sources, annotation.path("url").asText(""), annotation.path("title").asText(""));
                        }
                    }
                }
            }
            if ("web_search_call".equals(output.path("type").asText(""))) {
                for (JsonNode source : output.path("action").path("sources")) {
                    addSource(sources, source.path("url").asText(""), source.path("title").asText(""));
                }
            }
        }
        return new Parsed(root.path("id").asText(""), text.toString().trim(), List.copyOf(sources.values()));
    }

    public static List<Map<String, Object>> sourceUrlMaps(List<SourceRef> refs, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (refs == null) {
            return out;
        }
        int cap = Math.max(0, limit);
        for (SourceRef ref : refs) {
            if (out.size() >= cap) {
                break;
            }
            if (ref == null || ref.url() == null || ref.url().isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("url", ref.url());
            if (ref.title() != null && !ref.title().isBlank()) {
                row.put("title", ref.title());
            }
            out.add(row);
        }
        return out;
    }

    private static void addSource(Map<String, SourceRef> out, String rawUrl, String title) {
        String url = safeUrl(rawUrl);
        if (!url.isBlank()) {
            out.putIfAbsent(url, new SourceRef(url, truncate(title == null ? "" : title.trim(), 240)));
        }
    }

    private static String safeUrl(String raw) {
        try {
            URI uri = URI.create(raw == null ? "" : raw.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return ("https".equals(scheme) || "http".equals(scheme)) && uri.getHost() != null
                    ? uri.toString()
                    : "";
        } catch (Exception ex) {
            return "";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
