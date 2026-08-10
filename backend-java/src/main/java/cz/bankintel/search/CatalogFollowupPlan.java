package cz.bankintel.search;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validated execution contract produced by the LLM follow-up planner.
 *
 * <p>This class deliberately contains no natural-language rules. It validates only bounded runtime
 * capabilities and registered source identifiers; semantic routing remains the planner's job.
 */
public record CatalogFollowupPlan(
        String relation,
        String operation,
        String action,
        boolean preserveConcept,
        String searchQuery,
        SourceConstraints sourceConstraints,
        List<String> seriesRefIds,
        double confidence,
        String reasonCz,
        String routingSource) {

    private static final Set<String> RELATIONS = Set.of("same_topic", "new_topic");
    private static final Set<String> OPERATIONS = Set.of("answer", "search", "compare", "compute", "compose", "research");
    private static final Map<String, Set<String>> ACTIONS_BY_OPERATION = Map.of(
            "answer", Set.of("chat_over_results", "explain_indicator"),
            "search", Set.of("refine_search", "find_alternatives", "new_search"),
            "compare", Set.of("compare_selected"),
            "compute", Set.of("compute_ratio", "compute_difference", "compute_percent_change"),
            "compose", Set.of("compose_multi_chart", "add_to_chart"),
            "research", Set.of("annotate_web_events", "discover_external_series"));
    private static final Set<String> SOURCE_MODES = Set.of("keep", "include", "exclude", "alternatives");
    private static final Set<String> SEARCH_SOURCES = Set.copyOf(CatalogSourceRegistry.FOLLOWUP_DEFAULT_SOURCE_POOL);

    public CatalogFollowupPlan {
        seriesRefIds = seriesRefIds == null ? List.of() : List.copyOf(seriesRefIds);
    }

    /** Compatibility constructor for plans that do not target concrete series. */
    public CatalogFollowupPlan(
            String relation,
            String operation,
            String action,
            boolean preserveConcept,
            String searchQuery,
            SourceConstraints sourceConstraints,
            double confidence,
            String reasonCz,
            String routingSource) {
        this(relation, operation, action, preserveConcept, searchQuery, sourceConstraints, List.of(),
                confidence, reasonCz, routingSource);
    }

    public static CatalogFollowupPlan fromLlmJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String relation = normalized(node.path("relation").asText(""));
        String operation = normalized(node.path("operation").asText(""));
        String action = normalized(node.path("action").asText(""));
        String searchQuery = node.path("search_query").asText("").trim();
        if (!node.path("preserve_concept").isBoolean()) {
            return null;
        }
        SourceConstraints constraints = SourceConstraints.fromJson(node.path("source_constraints"));
        List<String> seriesRefIds = seriesRefIds(node.path("series_ref_ids"));
        if (seriesRefIds == null || !isValid(relation, operation, action, searchQuery, constraints, seriesRefIds)) {
            return null;
        }
        return new CatalogFollowupPlan(
                relation,
                operation,
                action,
                node.path("preserve_concept").asBoolean(),
                searchQuery,
                constraints,
                seriesRefIds,
                boundedConfidence(node.path("confidence").asDouble(0.0)),
                node.path("reason_cz").asText("").trim(),
                "llm");
    }

    @SuppressWarnings("unchecked")
    public static CatalogFollowupPlan fromPayload(Object raw) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) rawMap;
        String relation = normalized(map.get("relation"));
        String operation = normalized(map.get("operation"));
        String action = normalized(map.get("action"));
        String searchQuery = stringValue(map.get("search_query"));
        if (!(map.get("preserve_concept") instanceof Boolean preserveConcept)) {
            return null;
        }
        SourceConstraints constraints = SourceConstraints.fromPayload(map.get("source_constraints"));
        List<String> seriesRefIds = seriesRefIds(map.get("series_ref_ids"));
        if (seriesRefIds == null || !isValid(relation, operation, action, searchQuery, constraints, seriesRefIds)) {
            return null;
        }
        double confidence = map.get("confidence") instanceof Number number ? number.doubleValue() : 0.0;
        return new CatalogFollowupPlan(
                relation,
                operation,
                action,
                preserveConcept,
                searchQuery,
                constraints,
                seriesRefIds,
                boundedConfidence(confidence),
                stringValue(map.get("reason_cz")),
                stringValue(map.get("routing_source")));
    }

    public String compatibilityIntent() {
        if ("new_topic".equals(relation)) {
            return "new_search";
        }
        if ("search".equals(operation)) {
            return "refine_search";
        }
        return "continue";
    }

    public boolean executesSearch() {
        return "search".equals(operation) && !"new_topic".equals(relation);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("relation", relation);
        out.put("operation", operation);
        out.put("action", action);
        out.put("preserve_concept", preserveConcept);
        out.put("search_query", searchQuery);
        out.put("source_constraints", sourceConstraints.toMap());
        out.put("series_ref_ids", seriesRefIds);
        out.put("confidence", confidence);
        out.put("reason_cz", reasonCz);
        out.put("routing_source", routingSource);
        return out;
    }

    private static boolean isValid(
            String relation,
            String operation,
            String action,
            String searchQuery,
            SourceConstraints constraints,
            List<String> seriesRefIds) {
        if (!RELATIONS.contains(relation)
                || !OPERATIONS.contains(operation)
                || !ACTIONS_BY_OPERATION.getOrDefault(operation, Set.of()).contains(action)
                || constraints == null) {
            return false;
        }
        if (("search".equals(operation) || "research".equals(operation)) && searchQuery.isBlank()) {
            return false;
        }
        int requiredRefs = switch (action) {
            case "explain_indicator", "add_to_chart" -> 1;
            case "compare_selected", "compute_ratio", "compute_difference", "compute_percent_change",
                    "compose_multi_chart" -> 2;
            default -> 0;
        };
        if (seriesRefIds.size() < requiredRefs) {
            return false;
        }
        return "new_topic".equals(relation) == "new_search".equals(action);
    }

    private static List<String> seriesRefIds(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (value.isBlank() || value.length() > 180) {
                return null;
            }
            if (!out.contains(value)) {
                out.add(value);
            }
        }
        return out.size() <= 8 ? out : null;
    }

    private static List<String> seriesRefIds(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String value = stringValue(item);
            if (value.isBlank() || value.length() > 180) {
                return null;
            }
            if (!out.contains(value)) {
                out.add(value);
            }
        }
        return out.size() <= 8 ? out : null;
    }

    private static String normalized(Object value) {
        return stringValue(value).toLowerCase(Locale.ROOT);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double boundedConfidence(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record SourceConstraints(String mode, List<String> include, List<String> exclude) {

        public SourceConstraints {
            include = include == null ? List.of() : List.copyOf(include);
            exclude = exclude == null ? List.of() : List.copyOf(exclude);
        }

        private static SourceConstraints fromJson(JsonNode node) {
            if (node == null || !node.isObject()) {
                return null;
            }
            String mode = normalized(node.path("mode").asText(""));
            List<String> include = registeredSources(node.path("include"));
            List<String> exclude = registeredSources(node.path("exclude"));
            if (include == null || exclude == null || !validMode(mode, include, exclude)) {
                return null;
            }
            return new SourceConstraints(mode, include, exclude);
        }

        @SuppressWarnings("unchecked")
        private static SourceConstraints fromPayload(Object raw) {
            if (!(raw instanceof Map<?, ?> rawMap)) {
                return null;
            }
            Map<String, Object> map = (Map<String, Object>) rawMap;
            String mode = normalized(map.get("mode"));
            List<String> include = registeredSources(map.get("include"));
            List<String> exclude = registeredSources(map.get("exclude"));
            if (include == null || exclude == null || !validMode(mode, include, exclude)) {
                return null;
            }
            return new SourceConstraints(mode, include, exclude);
        }

        private static boolean validMode(String mode, List<String> include, List<String> exclude) {
            if (!SOURCE_MODES.contains(mode)) {
                return false;
            }
            if ("include".equals(mode)) {
                return !include.isEmpty();
            }
            if ("exclude".equals(mode)) {
                return !exclude.isEmpty();
            }
            return true;
        }

        private static List<String> registeredSources(JsonNode node) {
            if (node == null || !node.isArray()) {
                return null;
            }
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                String source = registeredSource(item.asText(""));
                if (source == null) {
                    return null;
                }
                if (!source.isBlank() && !values.contains(source)) {
                    values.add(source);
                }
            }
            return values;
        }

        private static List<String> registeredSources(Object raw) {
            if (!(raw instanceof List<?> list)) {
                return null;
            }
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                String source = registeredSource(stringValue(item));
                if (source == null) {
                    return null;
                }
                if (!source.isBlank() && !values.contains(source)) {
                    values.add(source);
                }
            }
            return values;
        }

        private static String registeredSource(String raw) {
            String source = CatalogSourceRegistry.normalizeSearchSource(raw);
            if (source.isBlank()) {
                return "";
            }
            return SEARCH_SOURCES.contains(source) ? source : null;
        }

        public Map<String, Object> toMap() {
            return Map.of("mode", mode, "include", include, "exclude", exclude);
        }
    }
}
