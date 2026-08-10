package cz.bankintel.search.v2.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SemanticDecision(
        String seriesId,
        String decision,
        double relevanceScore,
        double confidence,
        List<String> matchedUserNeed,
        List<String> semanticConflicts,
        String reason,
        String resultRole) {

    public boolean keepLike() {
        return "keep".equalsIgnoreCase(decision) || "supporting".equalsIgnoreCase(decision);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series_id", seriesId);
        out.put("decision", decision);
        out.put("relevance_score", relevanceScore);
        out.put("confidence", confidence);
        out.put("matched_user_need", matchedUserNeed);
        out.put("semantic_conflicts", semanticConflicts);
        out.put("reason", reason);
        out.put("result_role", resultRole);
        return out;
    }

    public static SemanticDecision fallbackKeep(SearchCandidate candidate, int rank) {
        double score = Math.max(0.2, 0.75 - rank * 0.03);
        return new SemanticDecision(
                candidate.seriesId(),
                "keep",
                score,
                0.35,
                List.of("fts_candidate"),
                List.of("semantic_rerank_unavailable"),
                "LLM semantic validation is unavailable; returned as raw FTS fallback.",
                "primary");
    }
}
