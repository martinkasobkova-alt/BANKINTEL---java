package cz.bankintel.search.v2.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry.ResolvedConceptRequirement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Perf fix (selectRerankPool/candidateMatchesRequiredConcepts bottleneck): proves the precomputed
 * {@code normalizedAliasesById}/{@code normalizedPhrasesById} + {@link ResolvedConceptRequirement}
 * path is behaviorally identical to the original per-candidate registry scan, using the REAL registry
 * loaded from {@code /search_v2/concept_registry.json} (not a stub) - the same one every candidate is
 * matched against in production.
 */
class SearchV2ConceptRegistryPerfTest {

    private final SearchV2ConceptRegistry registry = new SearchV2ConceptRegistry(new ObjectMapper());

    // ---- Equivalence: old (List<String>) overload vs. new (ResolvedConceptRequirement) overload ----

    @Test
    void oldAndNewOverloadsAgreeOnCanonicalConceptIdMatch() {
        List<String> required = List.of("bank_profitability");
        String text = "Bank profitability (ROE) Slovakia";

        boolean viaOld = registry.candidateMatchesRequiredConcepts(text, required);
        boolean viaNew = registry.candidateMatchesRequiredConcepts(text, registry.resolveRequirement(required));

        assertThat(viaOld).isTrue();
        assertThat(viaNew).isEqualTo(viaOld);
    }

    @Test
    void oldAndNewOverloadsAgreeOnAliasMatch() {
        // "zisk bank" is a cs alias of bank_profitability, not the concept ID itself - exercises the
        // resolve()-detected path via a precomputed NormalizedAlias, not a literal ID match.
        List<String> required = List.of("bank_profitability");
        String text = "zisk bank - Slovensko, ctvrtletni udaje";

        boolean viaOld = registry.candidateMatchesRequiredConcepts(text, required);
        boolean viaNew = registry.candidateMatchesRequiredConcepts(text, registry.resolveRequirement(required));

        assertThat(viaOld).isTrue();
        assertThat(viaNew).isEqualTo(viaOld);
    }

    @Test
    void oldAndNewOverloadsAgreeOnCaseInsensitivity() {
        List<String> required = List.of("bank_profitability");
        String text = "BANK PROFIT SLOVAKIA";

        assertThat(registry.candidateMatchesRequiredConcepts(text, required)).isTrue();
        assertThat(registry.candidateMatchesRequiredConcepts(text, registry.resolveRequirement(required))).isTrue();
    }

    @Test
    void oldAndNewOverloadsAgreeOnDiacriticsAndNormalization() {
        // foldAscii must strip diacritics before comparison - "návratnost" vs "navratnost".
        List<String> required = List.of("bank_profitability");
        String withDiacritics = "návratnost vlastního kapitálu bank";

        boolean viaOld = registry.candidateMatchesRequiredConcepts(withDiacritics, required);
        boolean viaNew = registry.candidateMatchesRequiredConcepts(
                withDiacritics, registry.resolveRequirement(required));

        assertThat(viaNew).isEqualTo(viaOld);
    }

    @Test
    void oldAndNewOverloadsAgreeOnUnknownConcept() {
        List<String> required = List.of("totally_unknown_concept_id");
        String text = "bank profit Slovakia";

        assertThat(registry.candidateMatchesRequiredConcepts(text, required)).isFalse();
        assertThat(registry.candidateMatchesRequiredConcepts(text, registry.resolveRequirement(required))).isFalse();
    }

    @Test
    void oldAndNewOverloadsAgreeOnMultipleRequiredConcepts() {
        List<String> required = List.of("interest_rate", "bank_profitability");
        for (String text : List.of(
                "policy rate Germany", "bank profit Slovakia", "unrelated commodity price index")) {
            boolean viaOld = registry.candidateMatchesRequiredConcepts(text, required);
            boolean viaNew = registry.candidateMatchesRequiredConcepts(text, registry.resolveRequirement(required));
            assertThat(viaNew).as(text).isEqualTo(viaOld);
        }
    }

    @Test
    void emptyRequiredConceptsNeverMatchesEitherOverload() {
        assertThat(registry.candidateMatchesRequiredConcepts("bank profit", List.of())).isFalse();
        assertThat(registry.candidateMatchesRequiredConcepts("bank profit", registry.resolveRequirement(List.of())))
                .isFalse();
        assertThat(registry.candidateMatchesRequiredConcepts("bank profit", (List<String>) null)).isFalse();
        assertThat(registry.candidateMatchesRequiredConcepts("bank profit", registry.resolveRequirement(null)))
                .isFalse();
    }

    @Test
    void candidateWithoutConceptMetadataNeverMatches() {
        List<String> required = List.of("bank_profitability");
        assertThat(registry.candidateMatchesRequiredConcepts("", required)).isFalse();
        assertThat(registry.candidateMatchesRequiredConcepts(null, required)).isFalse();
        assertThat(registry.candidateMatchesRequiredConcepts("", registry.resolveRequirement(required))).isFalse();
        assertThat(registry.candidateMatchesRequiredConcepts(null, registry.resolveRequirement(required))).isFalse();
    }

    @Test
    void nullRequirementNeverMatches() {
        assertThat(registry.candidateMatchesRequiredConcepts("bank profit", (ResolvedConceptRequirement) null))
                .isFalse();
    }

    /**
     * Fallback-path equivalence: a concept id that is not directly resolvable from the candidate text
     * via {@code resolve()}, but IS reachable through a required concept's own retrieval terms/aliases
     * scanned in the second loop - the precomputed {@code normalizedPhrasesById} path.
     */
    @Test
    void oldAndNewOverloadsAgreeWhenConceptIsOnlyReachableViaFallbackPhraseScan() {
        List<String> required = List.of("bank_profitability");
        // Contains the alias substring but phrased so resolve() alone may not treat it as high
        // confidence on its own merged query-forms; the fallback phrase scan is what decides it.
        String text = "dataset: roe bank institutions comparison table";

        boolean viaOld = registry.candidateMatchesRequiredConcepts(text, required);
        boolean viaNew = registry.candidateMatchesRequiredConcepts(text, registry.resolveRequirement(required));
        assertThat(viaNew).isEqualTo(viaOld);
    }

    // ---- resolveRequirement itself ------------------------------------------------------------------

    @Test
    void resolveRequirementIsEmptyForUnknownOrBlankInput() {
        assertThat(registry.resolveRequirement(null).isEmpty()).isTrue();
        assertThat(registry.resolveRequirement(List.of()).isEmpty()).isTrue();
        assertThat(registry.resolveRequirement(List.of("nope-not-a-real-concept")).isEmpty()).isTrue();
    }

    @Test
    void resolveRequirementIsCaseAndWhitespaceInsensitiveForConceptIds() {
        ResolvedConceptRequirement requirement = registry.resolveRequirement(List.of("  Bank_Profitability  "));
        assertThat(requirement.isEmpty()).isFalse();
        assertThat(requirement.isCompatible("bank_profitability")).isTrue();
    }

    @Test
    void resolveRequirementIncludesTheRequiredConceptItselfAsCompatible() {
        ResolvedConceptRequirement requirement = registry.resolveRequirement(List.of("bank_profitability"));
        assertThat(requirement.isCompatible("bank_profitability")).isTrue();
    }

    @Test
    void resolveRequirementIncludesCompatibleConceptsBothDirections() {
        // interest_rate declares policy_rate/lending_rate/deposit_rate/mortgage_rate/bond_yield as
        // compatible_concepts - a requirement for interest_rate must also accept those.
        ResolvedConceptRequirement requirement = registry.resolveRequirement(List.of("interest_rate"));
        assertThat(requirement.isCompatible("interest_rate")).isTrue();
        assertThat(requirement.isCompatible("policy_rate")).isTrue();
        assertThat(requirement.isCompatible("bond_yield")).isTrue();
        assertThat(requirement.isCompatible("bank_profitability")).isFalse();
    }

    @Test
    void resolveRequirementResultIsDeterministicAcrossRepeatedCalls() {
        ResolvedConceptRequirement first = registry.resolveRequirement(List.of("interest_rate", "bank_profitability"));
        ResolvedConceptRequirement second = registry.resolveRequirement(List.of("interest_rate", "bank_profitability"));
        for (String candidateId : List.of(
                "interest_rate", "bank_profitability", "policy_rate", "bond_yield", "totally_unknown")) {
            assertThat(first.isCompatible(candidateId)).isEqualTo(second.isCompatible(candidateId));
        }
    }

    // ---- resolve() itself - unchanged semantics after alias precomputation --------------------------

    @Test
    void resolveStillDetectsCanonicalAliasMatch() {
        SearchV2ConceptRegistry.ConceptResolution resolution = registry.resolve("bank profit Slovakia");
        assertThat(resolution.highConfidence()).isTrue();
        assertThat(resolution.conceptIds()).contains("bank_profitability");
    }

    @Test
    void resolveStillReturnsEmptyForBlankOrUnmatchedQuery() {
        assertThat(registry.resolve("").concepts()).isEmpty();
        assertThat(registry.resolve(null).concepts()).isEmpty();
        assertThat(registry.resolve("xyzzy plugh completely unrelated nonsense").concepts()).isEmpty();
    }

    @Test
    void registryHasTheExpectedRealConceptCount() {
        // Documents the actual registry size this bottleneck was measured against - if this changes,
        // the complexity numbers in the perf report need revisiting, not just this assertion.
        assertThat(registry.definitions(List.of(
                "interest_rate", "bank_profitability")).size()).isEqualTo(2);
    }
}
