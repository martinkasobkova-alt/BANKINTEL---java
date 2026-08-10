package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Direct unit tests of the PR-9 tiered timeout policy, independent of the verifier/executor. */
class SearchV2PreviewTimeoutPolicyTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_TIMEOUT_FAST_MS");
        System.clearProperty("SEARCH_PREVIEW_TIMEOUT_NORMAL_MS");
        System.clearProperty("SEARCH_PREVIEW_TIMEOUT_SLOW_MS");
    }

    // ---- Regression safety: disabled by default --------------------------------------------------

    @Test
    void disabledByDefaultReturnsTheGlobalTimeoutVerbatimForEverySource() {
        assertThat(SearchV2PreviewTimeoutPolicy.resolveMs("fred", 8000)).isEqualTo(8000);
        assertThat(SearchV2PreviewTimeoutPolicy.resolveMs("arad", 8000))
                .as("even a SLOW-tier source must use the plain global value while disabled")
                .isEqualTo(8000);
        assertThat(SearchV2PreviewTimeoutPolicy.resolveMs("arad", 123))
                .as("disabled path is a pure passthrough of whatever global value the caller supplies")
                .isEqualTo(123);
    }

    // ---- Tier assignment, derived from each connector's own documented HTTP timeout --------------

    @Test
    void fastTierCoversFredAndEcb() {
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor("fred")).isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.FAST);
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor("ecb2")).isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.FAST);
    }

    @Test
    void normalTierCoversImfData360OecdAndBis() {
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor("imf")).isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.NORMAL);
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor("data360")).isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.NORMAL);
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor("oecd4")).isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.NORMAL);
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor("bis")).isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.NORMAL);
    }

    @Test
    void slowTierCoversEurostatAradAndCsu() {
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor("eurostat")).isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.SLOW);
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor("arad")).isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.SLOW);
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor("csu")).isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.SLOW);
    }

    @Test
    void unknownOrLocalOnlySourcesDefaultToNormal() {
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor("worldbank")).isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.NORMAL);
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor("commodities")).isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.NORMAL);
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor("stocks")).isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.NORMAL);
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor("totally-unknown-source"))
                .isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.NORMAL);
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor(null)).isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.NORMAL);
    }

    @Test
    void sourceMatchingIsCaseAndWhitespaceInsensitive() {
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor(" FRED ")).isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.FAST);
        assertThat(SearchV2PreviewTimeoutPolicy.tierFor("Arad")).isEqualTo(SearchV2PreviewTimeoutPolicy.Tier.SLOW);
    }

    // ---- Enabled: every tier defaults to the same 8000ms as before, until explicitly configured ---

    @Test
    void enabledWithNoOverridesStillDefaultsEveryTierToTheOldGlobalDefault() {
        System.setProperty("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED", "true");
        assertThat(SearchV2PreviewTimeoutPolicy.resolveMs("fred", 999)).isEqualTo(8000);
        assertThat(SearchV2PreviewTimeoutPolicy.resolveMs("imf", 999)).isEqualTo(8000);
        assertThat(SearchV2PreviewTimeoutPolicy.resolveMs("arad", 999)).isEqualTo(8000);
    }

    // ---- Enabled: explicit per-tier configuration is honored -----------------------------------

    @Test
    void enabledWithExplicitPerTierValuesAppliesTheCorrectValuePerSource() {
        System.setProperty("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_FAST_MS", "3000");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_NORMAL_MS", "12000");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_SLOW_MS", "25000");

        assertThat(SearchV2PreviewTimeoutPolicy.resolveMs("fred", 1)).isEqualTo(3000);
        assertThat(SearchV2PreviewTimeoutPolicy.resolveMs("ecb2", 1)).isEqualTo(3000);
        assertThat(SearchV2PreviewTimeoutPolicy.resolveMs("bis", 1)).isEqualTo(12000);
        assertThat(SearchV2PreviewTimeoutPolicy.resolveMs("arad", 1)).isEqualTo(25000);
        assertThat(SearchV2PreviewTimeoutPolicy.resolveMs("eurostat", 1)).isEqualTo(25000);
    }

    // ---- Clamp is preserved even under tiering ---------------------------------------------------

    @Test
    void perTierValuesAreClampedToTheSameExistingBoundsAsTheGlobalTimeout() {
        System.setProperty("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_SLOW_MS", "600000"); // e.g. an operator tries ARAD's full 10 min
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_FAST_MS", "10"); // below the floor

        assertThat(SearchV2PreviewTimeoutPolicy.resolveMs("arad", 1))
                .as("must not exceed the existing 30s ceiling, even for the SLOW tier")
                .isEqualTo(30_000);
        assertThat(SearchV2PreviewTimeoutPolicy.resolveMs("fred", 1))
                .as("must not go below the existing 500ms floor")
                .isEqualTo(500);
    }

    // ---- Validation phase (Fáze 6, scenario 7): documented rollout risk, not fixed here -----------

    /**
     * KNOWN ROLLOUT RISK (documented, not fixed per the validation-phase instructions): each tier's
     * value is clamped independently to the existing [500, 30000] bounds, but there is NO cross-tier
     * monotonicity check. An operator can configure FAST_MS &gt; NORMAL_MS &gt; SLOW_MS (an inverted,
     * nonsensical tier ordering) and the policy will silently honor it - FAST-tier sources would then
     * get a LARGER timeout budget than SLOW-tier sources, defeating the whole point of tiering. This
     * test documents and reproduces the current (accepted, unvalidated) behavior; it does not assert
     * that this is desirable, and no validation was added to fix it in this validation pass.
     */
    @Test
    void perTierMonotonicityIsNotValidatedInvertedTierOrderIsSilentlyAccepted() {
        System.setProperty("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_FAST_MS", "20000"); // FAST > NORMAL > SLOW - inverted, nonsensical
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_NORMAL_MS", "10000");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_SLOW_MS", "1000");

        long fastResolved = SearchV2PreviewTimeoutPolicy.resolveMs("fred", 1); // FAST tier
        long slowResolved = SearchV2PreviewTimeoutPolicy.resolveMs("arad", 1); // SLOW tier

        assertThat(fastResolved)
                .as("ROLLOUT RISK: no monotonicity validation exists - an inverted FAST > SLOW "
                        + "configuration is silently accepted rather than rejected/clamped/warned about")
                .isGreaterThan(slowResolved);
    }
}
