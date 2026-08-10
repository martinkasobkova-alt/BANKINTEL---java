package cz.bankintel.search.v2.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SourceRoutingDecision(
        List<String> selectedCatalogFamilies,
        List<String> preferredSources,
        List<String> excludedSources,
        Map<String, String> sourceSelectionReason) {

    public static SourceRoutingDecision empty() {
        return new SourceRoutingDecision(List.of(), List.of(), List.of(), Map.of());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("selected_catalog_families", selectedCatalogFamilies);
        out.put("preferred_sources", preferredSources);
        out.put("excluded_sources", excludedSources);
        out.put("source_selection_reason", sourceSelectionReason);
        return out;
    }
}
