package cz.bankintel.search.v2.reranking;

import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SearchV2BatchReranker {

    private final SearchV2SemanticValidator semanticValidator;

    public SearchV2SemanticValidator.ValidationResult rerank(SearchQueryPlan plan, List<SearchCandidate> candidates) {
        return semanticValidator.validate(plan, candidates);
    }

    public SearchV2SemanticValidator.ValidationResult rerank(
            SearchQueryPlan plan, List<SearchCandidate> candidates, boolean useAi) {
        return semanticValidator.validate(plan, candidates, useAi);
    }

    public SearchV2SemanticValidator.ValidationResult unavailableFallback(
            List<SearchCandidate> candidates, String reason) {
        return semanticValidator.unavailableFallback(candidates, reason);
    }

    public SearchV2SemanticValidator.ValidationResult unavailableFallback(
            SearchQueryPlan plan, List<SearchCandidate> candidates, String reason) {
        return semanticValidator.unavailableFallback(plan, candidates, reason);
    }
}
