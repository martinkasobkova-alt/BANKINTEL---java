package cz.bankintel.search.v2.schema;

import java.util.LinkedHashMap;
import java.util.Map;

public record SearchResult(SearchCandidate candidate, SemanticDecision decision, int rank) {

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>(candidate.toMap());
        out.put("rank", rank);
        // Stable API alias consumed by the catalog UI and legacy deep-search clients.
        out.put("final_rank", rank);
        out.put("role", role());
        out.put("result_role", role());
        out.put("relevance_score", decision.relevanceScore());
        out.put("confidence", decision.confidence());
        out.put("why_selected", decision.reason());
        out.put("semantic_decision", decision.toMap());
        out.put("preview_status", "candidate");
        out.put("status", "candidate");
        return out;
    }

    public String role() {
        String role = decision.resultRole();
        if (role == null || role.isBlank() || "reject".equalsIgnoreCase(role)) {
            return "supporting".equalsIgnoreCase(decision.decision()) ? "supporting" : "primary";
        }
        return role;
    }
}
