package cz.bankintel.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CatalogFollowupPlanTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsRegisteredStructuredSearchPlan() throws Exception {
        CatalogFollowupPlan plan = CatalogFollowupPlan.fromLlmJson(objectMapper.readTree(
                """
                {
                  "relation":"same_topic",
                  "operation":"search",
                  "action":"find_alternatives",
                  "preserve_concept":true,
                  "search_query":"bank profitability",
                  "source_constraints":{"mode":"alternatives","include":["eurostat"],"exclude":["ecb"]},
                  "confidence":0.97,
                  "reason_cz":"Jiny zdroj pro stejny koncept."
                }
                """));

        assertThat(plan).isNotNull();
        assertThat(plan.compatibilityIntent()).isEqualTo("refine_search");
        assertThat(plan.sourceConstraints().exclude()).containsExactly("ecb2");
    }

    @Test
    void rejectsUnknownSourceInsteadOfGuessing() throws Exception {
        CatalogFollowupPlan plan = CatalogFollowupPlan.fromLlmJson(objectMapper.readTree(
                """
                {
                  "relation":"same_topic",
                  "operation":"search",
                  "action":"refine_search",
                  "preserve_concept":true,
                  "search_query":"bank profitability",
                  "source_constraints":{"mode":"include","include":["unknown_catalog"],"exclude":[]},
                  "confidence":0.8,
                  "reason_cz":""
                }
                """));

        assertThat(plan).isNull();
    }

    @Test
    void rejectsSemanticActionOperationMismatch() throws Exception {
        CatalogFollowupPlan plan = CatalogFollowupPlan.fromLlmJson(objectMapper.readTree(
                """
                {
                  "relation":"same_topic",
                  "operation":"answer",
                  "action":"find_alternatives",
                  "preserve_concept":true,
                  "search_query":"",
                  "source_constraints":{"mode":"keep","include":[],"exclude":[]},
                  "confidence":0.8,
                  "reason_cz":""
                }
                """));

        assertThat(plan).isNull();
    }

    @Test
    void composePlanRequiresTwoExactSeriesReferences() throws Exception {
        CatalogFollowupPlan invalid = CatalogFollowupPlan.fromLlmJson(objectMapper.readTree(
                """
                {
                  "relation":"same_topic",
                  "operation":"compose",
                  "action":"compose_multi_chart",
                  "preserve_concept":true,
                  "search_query":"",
                  "source_constraints":{"mode":"keep","include":[],"exclude":[]},
                  "series_ref_ids":["inflation"],
                  "confidence":0.9,
                  "reason_cz":""
                }
                """));

        CatalogFollowupPlan valid = CatalogFollowupPlan.fromLlmJson(objectMapper.readTree(
                """
                {
                  "relation":"same_topic",
                  "operation":"compose",
                  "action":"compose_multi_chart",
                  "preserve_concept":true,
                  "search_query":"",
                  "source_constraints":{"mode":"keep","include":[],"exclude":[]},
                  "series_ref_ids":["inflation","gold"],
                  "confidence":0.9,
                  "reason_cz":""
                }
                """));

        assertThat(invalid).isNull();
        assertThat(valid).isNotNull();
        assertThat(valid.seriesRefIds()).containsExactly("inflation", "gold");
    }

    @Test
    void acceptsGeneralWebEventResearchWithoutSeriesHardcoding() throws Exception {
        CatalogFollowupPlan plan = CatalogFollowupPlan.fromLlmJson(objectMapper.readTree(
                """
                {
                  "relation":"same_topic",
                  "operation":"research",
                  "action":"annotate_web_events",
                  "preserve_concept":true,
                  "search_query":"historical crises affecting the visible chart",
                  "source_constraints":{"mode":"keep","include":[],"exclude":[]},
                  "series_ref_ids":[],
                  "confidence":0.94,
                  "reason_cz":"Dohledani udalosti pro anotace grafu."
                }
                """));

        assertThat(plan).isNotNull();
        assertThat(plan.compatibilityIntent()).isEqualTo("continue");
        assertThat(plan.action()).isEqualTo("annotate_web_events");
    }

    @Test
    void rejectsResearchPlanWithoutSelfContainedQuestion() throws Exception {
        CatalogFollowupPlan plan = CatalogFollowupPlan.fromLlmJson(objectMapper.readTree(
                """
                {
                  "relation":"same_topic",
                  "operation":"research",
                  "action":"discover_external_series",
                  "preserve_concept":true,
                  "search_query":"",
                  "source_constraints":{"mode":"keep","include":[],"exclude":[]},
                  "series_ref_ids":[],
                  "confidence":0.8,
                  "reason_cz":""
                }
                """));

        assertThat(plan).isNull();
    }
}
