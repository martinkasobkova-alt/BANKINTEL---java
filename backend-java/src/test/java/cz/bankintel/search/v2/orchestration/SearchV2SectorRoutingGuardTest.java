package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.ExactEntityResolution;
import cz.bankintel.search.v2.schema.SourceRoutingDecision;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers the architectural fix for "zisk pojistoven ve spanelsku" returning zero results: the
 * planner has no insurance_profitability concept, so it routed via bank_profitability (the only
 * profitability concept in the registry), sending retrieval to bank-only sources. The semantic
 * validator correctly rejected the wrong-sector candidates that came back, but nothing right-sector
 * was ever fetched.
 *
 * <p>These tests use the REAL {@link SearchV2ConceptRegistry} and {@link
 * SearchV2InstitutionalSectorRegistry} (both load their JSON resources from an {@link
 * ObjectMapper}, no mocking) so a future edit to either registry's data is exercised exactly as
 * production sees it.
 */
class SearchV2SectorRoutingGuardTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SearchV2ConceptRegistry conceptRegistry = new SearchV2ConceptRegistry(objectMapper);
    private final SearchV2InstitutionalSectorRegistry institutionalSectorRegistry =
            new SearchV2InstitutionalSectorRegistry(objectMapper);

    private SearchQueryPlan planWith(List<String> primaryConcepts, List<String> institutionalSectors) {
        return new SearchQueryPlan(
                "test query",
                "cs",
                "find_series",
                primaryConcepts,
                List.of(),
                List.of("ES"),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of("test query"),
                List.of("test query"),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "openai",
                null,
                cz.bankintel.search.v2.schema.ExactEntityResolution.openTopic("test"),
                cz.bankintel.search.v2.schema.SourceRoutingDecision.empty(),
                List.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                institutionalSectors);
    }

    private SearchV2SectorRoutingGuard.Assessment assess(SearchQueryPlan plan) {
        return SearchV2SectorRoutingGuard.assess(plan, conceptRegistry, institutionalSectorRegistry);
    }

    @Test
    void bankConceptMatchingExplicitBankSectorDoesNotTriggerFanOut() {
        // "ziskovost bank Spanelsko" - the working control case: concept and explicit sector agree.
        SearchV2SectorRoutingGuard.Assessment assessment =
                assess(planWith(List.of("bank_profitability"), List.of("banks")));

        assertThat(assessment.conceptKnown()).isTrue();
        assertThat(assessment.impliedSector()).isEqualTo("banks");
        assertThat(assessment.conceptSectorConflict()).isFalse();
        assertThat(assessment.sectorUnaddressed()).isFalse();
        assertThat(assessment.fanOutTriggered())
                .as("routing already correctly targets the sector the query names - no widening needed")
                .isFalse();
    }

    @Test
    void exactBankingEntityAddressesBankSectorWithoutConceptRegistryMatch() {
        SearchQueryPlan base = planWith(List.of("Return on equity"), List.of("banks"));
        SearchQueryPlan plan = new SearchQueryPlan(
                base.originalQuery(), base.language(), base.intent(), base.primaryConcepts(),
                base.supportingConcepts(), base.geographies(), base.explicitSources(),
                base.frequencyPreferences(), base.unitPreferences(), base.timeScope(),
                base.exactSearchTerms(), base.semanticSearchTerms(), base.translatedSearchTerms(),
                base.relatedSearchTerms(), base.excludedMeanings(), base.desiredResultRoles(),
                base.clarification(), base.plannerStatus(), base.model(),
                new ExactEntityResolution("exact_entity", 0.95, "financial_ratio", "Return on equity",
                        List.of("ROE"), List.of("return on equity"), "banking", List.of("ecb2"),
                        List.of("ROE"), List.of(), false, "test", Map.of("measure_type", "roe")),
                new SourceRoutingDecision(List.of("banking"), List.of("ecb2"), List.of(), Map.of()),
                base.queryVariants(), base.llmPlannerTrace(), base.fallbackTrace(), base.institutionalSectors());

        SearchV2SectorRoutingGuard.Assessment assessment = assess(plan);

        assertThat(assessment.impliedSector()).isEqualTo("banks");
        assertThat(assessment.sectorUnaddressed()).isFalse();
        assertThat(assessment.fanOutTriggered()).isFalse();
    }

    @Test
    void bankConceptConflictingWithExplicitInsuranceSectorTriggersFanOut() {
        // "ziskovost pojistoven Spanelsko" - the reported bug: planner falls back to
        // bank_profitability (the only profitability concept that exists) for an insurance query.
        SearchV2SectorRoutingGuard.Assessment assessment =
                assess(planWith(List.of("bank_profitability"), List.of("insurance")));

        assertThat(assessment.conceptKnown()).isTrue();
        assertThat(assessment.impliedSector()).isEqualTo("banks");
        assertThat(assessment.detectedSector()).isEqualTo("insurance");
        assertThat(assessment.conceptSectorConflict())
                .as("concept implies banks but the query explicitly names insurance")
                .isTrue();
        assertThat(assessment.fanOutTriggered()).isTrue();
    }

    @Test
    void bankConceptConflictingWithExplicitPensionFundsSectorTriggersFanOut() {
        // "ziskovost penzijnich fondu" - same failure mode, different sector.
        SearchV2SectorRoutingGuard.Assessment assessment =
                assess(planWith(List.of("bank_profitability"), List.of("pension_funds")));

        assertThat(assessment.conceptSectorConflict()).isTrue();
        assertThat(assessment.fanOutTriggered()).isTrue();
    }

    @Test
    void unregisteredLiteralTextConceptWithExplicitSectorTriggersFanOut() {
        // "naklady pojistoven" / "aktiva pojistoven" - live-observed planner behavior: when nothing
        // fits, it sometimes echoes the raw query text back as primary_concepts instead of a real id.
        // Fan-out here fires via sectorUnaddressed (an explicit sector IS named), not conceptUnknown
        // alone.
        SearchV2SectorRoutingGuard.Assessment assessment =
                assess(planWith(List.of("naklady pojistoven spanelsko"), List.of("insurance")));

        assertThat(assessment.conceptKnown()).isFalse();
        assertThat(assessment.conceptUnknown()).isTrue();
        assertThat(assessment.conceptRegistryStatus()).isEqualTo("free_metric_intent");
        assertThat(assessment.sectorUnaddressed()).isTrue();
        assertThat(assessment.fanOutTriggered()).isTrue();
    }

    @Test
    void unregisteredLiteralTextConceptWithNoExplicitSectorDoesNotTriggerFanOut() {
        // "HDP Nemecko" - live-observed planner nondeterminism: the same macro query sometimes gets a
        // real concept id (gdp) and sometimes gets its own raw text echoed back as primary_concepts.
        // With no institutional sector at stake, that flakiness alone must not force all 11 sources
        // open - it must fall through to the existing lexical/vector/default fallback unchanged.
        SearchV2SectorRoutingGuard.Assessment assessment =
                assess(planWith(List.of("hdp nemecko"), List.of()));

        assertThat(assessment.conceptUnknown()).isTrue();
        assertThat(assessment.detectedSector()).isBlank();
        assertThat(assessment.sectorUnaddressed()).isFalse();
        assertThat(assessment.conceptSectorConflict()).isFalse();
        assertThat(assessment.fanOutTriggered())
                .as("no explicit sector named - unknown-concept flakiness alone is not a fan-out trigger")
                .isFalse();
    }

    @Test
    void emptyConceptListIsAValidStateAndDoesNotAloneTriggerFanOut() {
        SearchV2SectorRoutingGuard.Assessment assessment = assess(planWith(List.of(), List.of()));

        assertThat(assessment.conceptKnown()).isFalse();
        assertThat(assessment.conceptUnknown())
                .as("an empty concept list is a valid state, not 'unknown'")
                .isFalse();
        assertThat(assessment.conceptRegistryStatus()).isEqualTo("empty");
        assertThat(assessment.fanOutTriggered()).isFalse();
    }

    @Test
    void knownNonSectorConceptWithExplicitSectorLeavesSectorUnaddressedAndTriggersFanOut() {
        // "zadluzeni domacnosti" via a generic concept (e.g. lending_rate/gdp) that implies no
        // sector at all - routing has no signal that a household-sector query was asked.
        SearchV2SectorRoutingGuard.Assessment assessment =
                assess(planWith(List.of("lending_rate"), List.of("households")));

        assertThat(assessment.conceptKnown()).isTrue();
        assertThat(assessment.impliedSector()).isBlank();
        assertThat(assessment.sectorUnaddressed()).isTrue();
        assertThat(assessment.fanOutTriggered()).isTrue();
    }

    @Test
    void nonSectorConceptWithNoExplicitSectorDoesNotTriggerFanOut() {
        // "produktivita stavebnictvi" / "inflace Cesko" / "HDP Nemecko" - macro/industry queries with
        // no institutional sector at stake at all must keep their existing narrow, fast routing.
        SearchV2SectorRoutingGuard.Assessment assessment =
                assess(planWith(List.of("industrial_production"), List.of()));

        assertThat(assessment.detectedSector()).isBlank();
        assertThat(assessment.conceptSectorConflict()).isFalse();
        assertThat(assessment.sectorUnaddressed()).isFalse();
        assertThat(assessment.fanOutTriggered())
                .as("no sector at stake for this query - concept-driven routing is trusted as-is")
                .isFalse();
    }
}
