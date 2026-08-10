package cz.bankintel.search.v2.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.v2.entity.ExactEntityResolver;
import cz.bankintel.search.v2.entity.SearchV2SourceCapabilityRegistry;
import cz.bankintel.search.v2.ontology.SearchV2ConceptOntology;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry;
import cz.bankintel.search.v2.planner.SearchV2QueryPlanner;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression guard against the giant-dump query variant reappearing on the actual production
 * planning path (use_ai=false, i.e. the deterministic/local plan used whenever the LLM planner is
 * disabled or unconfigured) - not a hand-built {@link SearchQueryPlan}.
 *
 * <p>Root cause (fixed): {@code SearchV2QueryPlanner.localSearchTerms()} used to call {@code
 * CatalogTextUtils.needlesFromQuery()} (which runs the legacy {@code CatalogSearchSynonyms}
 * banking-group expansion and returns up to 16 individual word tokens), then joined ALL of them
 * into ONE string via {@code String.join(" ", tokens)} and added that as a single search term. That
 * joined string, once downstream in {@link SearchV2QueryExpander#expand}, reproducibly timed out or
 * returned zero candidates against every structured FTS source for every one of the 13/68
 * gold+holdout queries where it fired (A/B measured: 0 lost gold results, +2 gained, latency
 * improved or unchanged on 13/15 measured queries) - it never contributed a single candidate.
 * {@code localSearchTerms()} no longer emits the joined term; the individual tokens are still added
 * separately, which is what actually finds results.
 *
 * <p>This test uses the REAL {@link SearchV2QueryPlanner} (with an unconfigured {@link
 * OpenAiClient} standing in for use_ai=false, which short-circuits to the local/deterministic plan
 * before the client is ever consulted) and the REAL {@link SearchV2QueryExpander} - if a future
 * change to {@code localSearchTerms()} reintroduces the joined giant-dump term, this test must fail.
 */
class SearchV2GiantDumpProductionPathTest {

    private static final String GIANT_DUMP = "bank profit banks banking sector credit institutions "
            + "monetary financial mfi profits earnings net income profitability";

    @Test
    void bankProfitCzechRepublicNeverProducesTheGiantDumpVariantViaTheRealPlannerAndExpander() {
        ObjectMapper objectMapper = new ObjectMapper();
        SearchV2SourceCapabilityRegistry sourceCapabilityRegistry =
                new SearchV2SourceCapabilityRegistry(objectMapper);
        ExactEntityResolver exactEntityResolver =
                new ExactEntityResolver(objectMapper, sourceCapabilityRegistry);
        SearchV2ConceptRegistry conceptRegistry = new SearchV2ConceptRegistry(objectMapper);
        SearchV2InstitutionalSectorRegistry institutionalSectorRegistry =
                new SearchV2InstitutionalSectorRegistry(objectMapper);
        SearchV2MetricIntentRegistry metricIntentRegistry = new SearchV2MetricIntentRegistry(objectMapper);
        OpenAiClient unconfiguredOpenAiClient = mock(OpenAiClient.class);

        SearchV2QueryPlanner planner = new SearchV2QueryPlanner(
                unconfiguredOpenAiClient,
                exactEntityResolver,
                conceptRegistry,
                sourceCapabilityRegistry,
                institutionalSectorRegistry,
                metricIntentRegistry);

        SearchQueryPlan plan = planner.plan(Map.of(
                "q", "bank profit Czech Republic",
                "query", "bank profit Czech Republic",
                "use_ai", false));

        assertThat(plan.primaryConcepts()).contains("bank_profitability");
        assertThat(plan.exactSearchTerms())
                .as("localSearchTerms() must not produce the giant joined-token string as one term")
                .doesNotContain(GIANT_DUMP);

        SearchV2ConceptOntology ontology = new SearchV2ConceptOntology(objectMapper);
        SearchV2InstitutionalSectorRegistry sectorRegistry = new SearchV2InstitutionalSectorRegistry(objectMapper);
        SearchV2QueryExpander expander =
                new SearchV2QueryExpander(ontology, conceptRegistry, sectorRegistry, new SearchV2MetricIntentRegistry(objectMapper));

        List<String> variants = expander.expand(plan);

        assertThat(variants)
                .as("the real expand() output for the real production (use_ai=false) plan must NOT include "
                        + "the giant-dump variant - if this fails, the always-timing-out/zero-candidate "
                        + "variant has come back")
                .doesNotContain(GIANT_DUMP);
    }
}
