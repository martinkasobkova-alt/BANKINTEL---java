package cz.bankintel.search.v2.coverage;

import cz.bankintel.search.v2.geo.SearchV2GeoCompatibility;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SearchResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SearchV2CoverageChecker {

    public record CoverageResult(String status, List<String> missingAspects, boolean retryRecommended, String reason) {
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", status);
            out.put("missing_aspects", missingAspects);
            out.put("retry_recommended", retryRecommended);
            out.put("reason", reason);
            return out;
        }
    }

    public CoverageResult check(SearchQueryPlan plan, List<SearchResult> results, String semanticStatus) {
        if (results.isEmpty() && plan.clarification() != null && plan.clarification().required()) {
            return new CoverageResult("insufficient", List.of("clarification_required"), false, plan.clarification().reason());
        }
        List<String> missing = new ArrayList<>();
        boolean hasPrimary = results.stream().anyMatch(r -> "primary".equals(r.role()) && r.decision().relevanceScore() >= 0.55);
        if (!hasPrimary) {
            missing.add("strong_primary_series");
        }
        List<String> concreteGeographies = plan.geographies().stream()
                .filter(SearchV2CoverageChecker::isConcreteGeography)
                .toList();
        if (!concreteGeographies.isEmpty()
                && results.stream().noneMatch(result -> SearchV2GeoCompatibility.candidateMatchesRequestedGeo(
                        result.candidate(), concreteGeographies, plan))) {
            missing.add("requested_geography");
        }
        if ("unavailable".equals(semanticStatus)) {
            missing.add("semantic_validation");
        }
        if (results.isEmpty()) {
            return new CoverageResult("insufficient", missing, true, "No candidate survived final selection.");
        }
        if (!missing.isEmpty()) {
            return new CoverageResult("partial", missing, missing.contains("strong_primary_series"), "Some requested aspects are weak or missing.");
        }
        return new CoverageResult("complete", List.of(), false, "Primary result coverage is sufficient.");
    }

    private static boolean isConcreteGeography(String geography) {
        if (geography == null || geography.isBlank()) {
            return false;
        }
        String normalized = geography.trim().toUpperCase(Locale.ROOT);
        return !List.of("GLOBAL", "WORLD", "SVET", "SVĚT").contains(normalized);
    }
}
