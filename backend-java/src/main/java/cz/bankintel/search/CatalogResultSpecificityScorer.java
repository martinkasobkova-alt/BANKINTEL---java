package cz.bankintel.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** Data-driven adjustment for generic queries versus specialized catalog variants. */
final class CatalogResultSpecificityScorer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SpecificityRules RULES = loadRules();

    private CatalogResultSpecificityScorer() {}

    static int adjustment(String query, Map<String, Object> row) {
        String q = CatalogTextUtils.normalizeTokenBoundaries(query);
        String hay = CatalogTextUtils.normalizeTokenBoundaries(CatalogSemanticRowText.haystack(row));
        if (q.isBlank() || hay.isBlank()) {
            return 0;
        }
        int adjustment = 0;
        boolean unrequestedRefinement = false;
        for (SpecificityRule rule : RULES.unrequestedRefinements()) {
            if (!hasAny(hay, rule.terms())) {
                continue;
            }
            if (hasAny(q, rule.queryTerms())) {
                continue;
            }
            adjustment -= Math.max(0, rule.penalty());
            unrequestedRefinement = true;
        }
        if (!unrequestedRefinement) {
            for (PreferredTerm rule : RULES.preferredTerms()) {
                if (hasAny(hay, rule.terms())) {
                    adjustment += Math.max(0, rule.bonus());
                }
            }
        }
        return adjustment;
    }

    private static boolean hasAny(String normalizedText, List<String> terms) {
        for (String term : terms == null ? List.<String>of() : terms) {
            if (CatalogTextUtils.containsWholeTokenOrPhrase(normalizedText, term)) {
                return true;
            }
        }
        return false;
    }

    private static SpecificityRules loadRules() {
        try (InputStream in =
                CatalogResultSpecificityScorer.class.getResourceAsStream("/catalog/result_specificity_rules.json")) {
            if (in == null) {
                return SpecificityRules.empty();
            }
            Map<String, List<Map<String, Object>>> raw =
                    MAPPER.readValue(in, new TypeReference<Map<String, List<Map<String, Object>>>>() {});
            return new SpecificityRules(
                    readPreferred(raw.get("preferred_terms")),
                    readRefinements(raw.get("unrequested_refinements")));
        } catch (Exception ex) {
            return SpecificityRules.empty();
        }
    }

    private static List<PreferredTerm> readPreferred(List<Map<String, Object>> raw) {
        return (raw == null ? List.<Map<String, Object>>of() : raw)
                .stream()
                        .map(item -> new PreferredTerm(readStrings(item.get("terms")), intValue(item.get("bonus"))))
                        .toList();
    }

    private static List<SpecificityRule> readRefinements(List<Map<String, Object>> raw) {
        return (raw == null ? List.<Map<String, Object>>of() : raw)
                .stream()
                        .map(item -> new SpecificityRule(
                                readStrings(item.get("terms")),
                                readStrings(item.get("query_terms")),
                                intValue(item.get("penalty"))))
                        .toList();
    }

    private static List<String> readStrings(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(value -> value == null ? "" : String.valueOf(value).trim())
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static int intValue(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception ex) {
            return 0;
        }
    }

    private record SpecificityRules(List<PreferredTerm> preferredTerms, List<SpecificityRule> unrequestedRefinements) {
        static SpecificityRules empty() {
            return new SpecificityRules(List.of(), List.of());
        }
    }

    private record PreferredTerm(List<String> terms, int bonus) {}

    private record SpecificityRule(List<String> terms, List<String> queryTerms, int penalty) {}
}
