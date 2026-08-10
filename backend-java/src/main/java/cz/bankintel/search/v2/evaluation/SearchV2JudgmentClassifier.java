package cz.bankintel.search.v2.evaluation;

/**
 * Classifies eval-label provenance so heuristic/provisional labels are never reported as human
 * gold. This does not influence Search V2 ranking.
 */
public final class SearchV2JudgmentClassifier {

    private SearchV2JudgmentClassifier() {}

    public enum JudgmentType {
        HUMAN_JUDGED("human_judged"),
        EXPLICIT_GOLD_SERIES("explicit_gold_series"),
        RULE_BASED("rule_based"),
        HEURISTIC("heuristic"),
        LLM_JUDGED("LLM_judged"),
        PROVISIONAL("provisional");

        private final String wireName;

        JudgmentType(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public static JudgmentType classify(SearchV2GoldQuery query) {
        if (query == null) {
            return JudgmentType.PROVISIONAL;
        }
        if (query.relevantSeries() != null && !query.relevantSeries().isEmpty()) {
            return JudgmentType.EXPLICIT_GOLD_SERIES;
        }
        if (query.expectsClarification()
                || notBlank(query.requiredSource())
                || (query.forbiddenSources() != null && !query.forbiddenSources().isEmpty())) {
            return JudgmentType.RULE_BASED;
        }
        if ((query.relevantConceptFamilies() != null && !query.relevantConceptFamilies().isEmpty())
                || (query.expectedConceptSignals() != null && !query.expectedConceptSignals().isEmpty())) {
            return JudgmentType.HEURISTIC;
        }
        return JudgmentType.PROVISIONAL;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
