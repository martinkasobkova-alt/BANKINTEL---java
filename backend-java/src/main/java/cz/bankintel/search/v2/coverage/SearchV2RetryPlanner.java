package cz.bankintel.search.v2.coverage;

import cz.bankintel.search.v2.schema.SearchQueryPlan;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SearchV2RetryPlanner {

    public List<String> retryTerms(SearchQueryPlan plan, SearchV2CoverageChecker.CoverageResult coverage) {
        if (coverage == null || !coverage.retryRecommended()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String concept : plan.primaryConcepts()) {
            add(terms, concept);
        }
        for (String term : plan.semanticSearchTerms()) {
            add(terms, term);
        }
        for (String term : plan.translatedSearchTerms()) {
            add(terms, term);
        }
        if (!plan.geographies().isEmpty()) {
            List<String> geoTerms = new ArrayList<>();
            for (String term : terms) {
                for (String geo : plan.geographies()) {
                    add(geoTerms, term + " " + geo);
                }
            }
            terms.addAll(geoTerms);
        }
        return terms.stream().distinct().limit(6).toList();
    }

    private static void add(List<String> terms, String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() >= 2 && !terms.contains(clean)) {
            terms.add(clean);
        }
    }
}
