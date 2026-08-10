package cz.bankintel.search.v2.planner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Covers PR-1's prompt versioning requirement: a human-readable version constant plus a stable
 * content hash of the prompt actually loaded, without touching planner_prompt.md itself.
 */
class SearchV2QueryPlannerPromptVersionTest {

    @Test
    void versionConstantIsDeclaredAndNonBlank() {
        assertThat(SearchV2QueryPlanner.PLANNER_PROMPT_VERSION).isNotBlank();
    }

    @Test
    void contentHashIsStableAcrossRepeatedCalls() {
        String first = SearchV2QueryPlanner.promptContentHash();
        String second = SearchV2QueryPlanner.promptContentHash();

        assertThat(first).isNotBlank().hasSize(64); // SHA-256 hex digest length
        assertThat(first).isEqualTo(second);
    }

    @Test
    void contentHashChangesWhenContentChanges() {
        assertThat(SearchV2QueryPlanner.sha256Hex("a")).isNotEqualTo(SearchV2QueryPlanner.sha256Hex("b"));
        assertThat(SearchV2QueryPlanner.sha256Hex("same")).isEqualTo(SearchV2QueryPlanner.sha256Hex("same"));
    }
}
