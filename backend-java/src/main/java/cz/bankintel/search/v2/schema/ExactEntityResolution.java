package cz.bankintel.search.v2.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ExactEntityResolution(
        String resolutionType,
        double confidence,
        String entityType,
        String canonicalName,
        List<String> symbols,
        List<String> aliases,
        String catalogFamily,
        List<String> preferredSources,
        List<String> exactTerms,
        List<String> relatedEntities,
        boolean allowBroadExpansion,
        String reason,
        Map<String, Object> attributes) {

    public static ExactEntityResolution openTopic(String reason) {
        return new ExactEntityResolution(
                "open_topic",
                0.0,
                "",
                "",
                List.of(),
                List.of(),
                "",
                List.of(),
                List.of(),
                List.of(),
                true,
                reason == null ? "" : reason,
                Map.of());
    }

    public boolean highConfidenceExact() {
        return "exact_entity".equals(resolutionType) && confidence >= 0.85 && !allowBroadExpansion;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resolution_type", resolutionType);
        out.put("confidence", confidence);
        out.put("entity_type", entityType);
        out.put("canonical_name", canonicalName);
        out.put("symbols", symbols);
        out.put("aliases", aliases);
        out.put("catalog_family", catalogFamily);
        out.put("preferred_sources", preferredSources);
        out.put("exact_terms", exactTerms);
        out.put("related_entities", relatedEntities);
        out.put("allow_broad_expansion", allowBroadExpansion);
        out.put("reason", reason);
        out.put("attributes", attributes == null ? Map.of() : attributes);
        return out;
    }
}
