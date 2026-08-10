package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Covers the AI reranker default: "use_ai" controls only the planner (backward compatible - the
 * production frontend hardcodes use_ai=true and must keep getting the same planner behavior). The
 * AI reranker defaults ON regardless of "use_ai", independent of the planner setting - re-enabled
 * 2026-07-31 after the fallback (non-AI) scorer was confirmed live to return topically wrong Top-1
 * results for FX pairs/commodities (e.g. "eurusd", "cena zlata") that it cannot disambiguate at
 * all, despite the aggregate gold-set MRR regression the original A/B test found. Still
 * overridable via an explicit "use_ai_reranker": false, independent of the planner setting.
 */
class SearchV2ServiceAiFlagsTest {

    @Test
    void legacyUseAiTrueEnablesBothThePlannerAndTheReranker() {
        Map<String, Object> payload = Map.of("use_ai", true);
        assertThat(SearchV2Service.resolveUseAiPlanner(payload)).isTrue();
        assertThat(SearchV2Service.resolveUseAiReranker(payload)).isTrue();
    }

    @Test
    void legacyUseAiFalseDisablesThePlannerButTheRerankerStaysOnByDefault() {
        Map<String, Object> payload = Map.of("use_ai", false);
        assertThat(SearchV2Service.resolveUseAiPlanner(payload)).isFalse();
        assertThat(SearchV2Service.resolveUseAiReranker(payload))
                .as("reranker default is independent of the planner flag")
                .isTrue();
    }

    @Test
    void omittingEverythingDefaultsPlannerOnAndRerankerOn() {
        assertThat(SearchV2Service.resolveUseAiPlanner(Map.of())).isTrue();
        assertThat(SearchV2Service.resolveUseAiReranker(Map.of())).isTrue();
    }

    @Test
    void explicitUseAiRerankerTrueStaysOnIndependentlyOfThePlannerSetting() {
        assertThat(SearchV2Service.resolveUseAiReranker(Map.of("use_ai", true, "use_ai_reranker", true))).isTrue();
        assertThat(SearchV2Service.resolveUseAiReranker(Map.of("use_ai", false, "use_ai_reranker", true)))
                .as("reranker opt-in must be independent of the planner flag")
                .isTrue();
    }

    @Test
    void explicitUseAiRerankerFalseOptsOutEvenWhenThePlannerIsOn() {
        assertThat(SearchV2Service.resolveUseAiReranker(Map.of("use_ai", true, "use_ai_reranker", false)))
                .as("must still be possible to opt out of the reranker explicitly")
                .isFalse();
    }

    @Test
    void explicitUseAiPlannerOverridesLegacyUseAi() {
        assertThat(SearchV2Service.resolveUseAiPlanner(Map.of("use_ai", false, "use_ai_planner", true))).isTrue();
        assertThat(SearchV2Service.resolveUseAiPlanner(Map.of("use_ai", true, "use_ai_planner", false))).isFalse();
    }
}
