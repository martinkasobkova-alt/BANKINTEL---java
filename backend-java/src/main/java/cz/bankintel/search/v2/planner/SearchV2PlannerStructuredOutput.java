package cz.bankintel.search.v2.planner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SearchV2PlannerStructuredOutput {

    private SearchV2PlannerStructuredOutput() {}

    static Map<String, Object> schema() {
        Map<String, Object> root = object();
        root.put("required", List.of(
                "normalized_query",
                "intent",
                "required_concepts",
                "measure_types",
                "geographies",
                "geo_memberships",
                "catalog_families",
                "preferred_sources",
                "excluded_sources",
                "query_variants",
                "confidence",
                "clarification_required",
                "clarification_question",
                "clarification_options"));
        root.put("properties", Map.ofEntries(
                Map.entry("normalized_query", string()),
                Map.entry("intent", stringEnum(List.of(
                        "lookup",
                        "compare",
                        "trend",
                        "forecast",
                        "relationship",
                        "exact_entity",
                        "ambiguous"))),
                Map.entry("required_concepts", stringArray()),
                Map.entry("measure_types", stringArray()),
                Map.entry("geographies", stringArray()),
                Map.entry("geo_memberships", stringArray()),
                Map.entry("catalog_families", stringArray()),
                Map.entry("preferred_sources", stringArray()),
                Map.entry("excluded_sources", stringArray()),
                Map.entry("query_variants", queryVariantArray()),
                Map.entry("confidence", Map.of("type", "number")),
                Map.entry("clarification_required", Map.of("type", "boolean")),
                Map.entry("clarification_question", Map.of("type", List.of("string", "null"))),
                Map.entry("clarification_options", stringArray())));
        return root;
    }

    private static Map<String, Object> queryVariantArray() {
        Map<String, Object> item = object();
        item.put("required", List.of("text", "role"));
        item.put("properties", Map.of(
                "text", string(),
                "role", stringEnum(List.of(
                        "original_exact",
                        "canonical_name",
                        "exact_alias",
                        "professional_synonym",
                        "broader_concept",
                        "related_entity"))));
        return Map.of("type", "array", "items", item);
    }

    private static Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        out.put("additionalProperties", false);
        return out;
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> stringArray() {
        return Map.of("type", "array", "items", string());
    }

    private static Map<String, Object> stringEnum(List<String> values) {
        return Map.of("type", "string", "enum", values);
    }
}
