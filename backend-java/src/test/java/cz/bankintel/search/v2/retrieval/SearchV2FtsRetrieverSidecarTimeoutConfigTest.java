package cz.bankintel.search.v2.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Covers the production default change for the sidecar FTS relaxed-lane timeout
 * ({@code SEARCH_V2_SIDECAR_QUERY_TIMEOUT_MS_BENCHMARK_OVERRIDE} -> {@code
 * SearchV2FtsRetriever.resolveSidecarQueryTimeoutMs}): default 2500ms -> 1500ms, override behavior
 * and fallback safety unchanged.
 */
class SearchV2FtsRetrieverSidecarTimeoutConfigTest {

    @Test
    void noOverrideResolvesToTheNewFifteenHundredMsDefault() {
        assertThat(SearchV2FtsRetriever.resolveSidecarQueryTimeoutMs(null)).isEqualTo(1_500L);
        assertThat(SearchV2FtsRetriever.resolveSidecarQueryTimeoutMs("")).isEqualTo(1_500L);
        assertThat(SearchV2FtsRetriever.resolveSidecarQueryTimeoutMs("   ")).isEqualTo(1_500L);
    }

    @Test
    void explicitOverrideOf500msIsHonored() {
        assertThat(SearchV2FtsRetriever.resolveSidecarQueryTimeoutMs("500")).isEqualTo(500L);
    }

    @Test
    void explicitOverrideOf2500msIsHonored() {
        assertThat(SearchV2FtsRetriever.resolveSidecarQueryTimeoutMs("2500")).isEqualTo(2_500L);
    }

    @Test
    void unparseableOverrideFallsBackSafelyToTheDefault() {
        assertThat(SearchV2FtsRetriever.resolveSidecarQueryTimeoutMs("not-a-number")).isEqualTo(1_500L);
        assertThat(SearchV2FtsRetriever.resolveSidecarQueryTimeoutMs("1500ms")).isEqualTo(1_500L);
    }

    @Test
    void zeroOrNegativeOverrideFallsBackSafelyToTheDefault() {
        assertThat(SearchV2FtsRetriever.resolveSidecarQueryTimeoutMs("0")).isEqualTo(1_500L);
        assertThat(SearchV2FtsRetriever.resolveSidecarQueryTimeoutMs("-100")).isEqualTo(1_500L);
    }
}
