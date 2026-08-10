package cz.bankintel.search.v2.reranking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Covers PR-1's prompt versioning requirement for the reranker system prompt. */
class SearchV2SemanticValidatorPromptVersionTest {

    @Test
    void versionConstantIsDeclaredAndNonBlank() {
        assertThat(SearchV2SemanticValidator.RERANKER_PROMPT_VERSION).isNotBlank();
    }

    @Test
    void contentHashIsStableAcrossRepeatedCalls() {
        String first = SearchV2SemanticValidator.promptContentHash();
        String second = SearchV2SemanticValidator.promptContentHash();

        assertThat(first).isNotBlank().hasSize(64);
        assertThat(first).isEqualTo(second);
    }
}
