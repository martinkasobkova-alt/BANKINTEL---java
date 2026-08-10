package cz.bankintel.search.v2.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2PreviewOutcomeTest {

    @Test
    void okTrueIsAlwaysVerifiedRegardlessOfPreviewState() {
        assertThat(SearchV2PreviewOutcome.classify(Map.of("ok", true, "preview_state", "ok")))
                .isEqualTo(SearchV2PreviewOutcome.VERIFIED);
    }

    @Test
    void timeoutStateIsClassifiedAsTimeoutNeverAsTransportFailureOrPossible() {
        String outcome = SearchV2PreviewOutcome.classify(Map.of("ok", false, "preview_state", "timeout"));

        assertThat(outcome).isEqualTo(SearchV2PreviewOutcome.TIMEOUT);
        assertThat(outcome).isNotEqualTo(SearchV2PreviewOutcome.TRANSPORT_FAILURE);
        assertThat(outcome).isNotEqualTo(SearchV2PreviewOutcome.POSSIBLE);
    }

    @Test
    void cancelledStateIsClassifiedAsCancelledNeverAsTransportFailureOrPossible() {
        String outcome = SearchV2PreviewOutcome.classify(Map.of("ok", false, "preview_state", "cancelled"));

        assertThat(outcome).isEqualTo(SearchV2PreviewOutcome.CANCELLED);
        assertThat(outcome).isNotEqualTo(SearchV2PreviewOutcome.TRANSPORT_FAILURE);
        assertThat(outcome).isNotEqualTo(SearchV2PreviewOutcome.TIMEOUT);
    }

    @Test
    void errorStateIsClassifiedAsTransportFailure() {
        assertThat(SearchV2PreviewOutcome.classify(Map.of("ok", false, "preview_state", "error")))
                .isEqualTo(SearchV2PreviewOutcome.TRANSPORT_FAILURE);
    }

    // ---- Fix: sync_failed (PreviewResponseBuilder.buildError's real shape for every HTTP-level ------
    // ---- connector failure - 429/5xx/connection error) must never be misclassified as EMPTY ---------

    @Test
    void syncFailedStateWithZeroRowsIsClassifiedAsTransportFailureNeverEmpty() {
        String outcome = SearchV2PreviewOutcome.classify(Map.of("ok", false, "preview_state", "sync_failed", "rows", 0));

        assertThat(outcome)
                .as("a real HTTP 429/5xx/connection failure (rows=0) must never be confused with confirmed "
                        + "absence of data")
                .isEqualTo(SearchV2PreviewOutcome.TRANSPORT_FAILURE);
        assertThat(SearchV2PreviewOutcome.isRejectedBucket(outcome)).isFalse();
        assertThat(SearchV2PreviewOutcome.isUnverifiedBucket(outcome)).isTrue();
    }

    @Test
    void syncFailedStateWithoutARowsKeyIsClassifiedAsTransportFailure() {
        assertThat(SearchV2PreviewOutcome.classify(Map.of("ok", false, "preview_state", "sync_failed")))
                .isEqualTo(SearchV2PreviewOutcome.TRANSPORT_FAILURE);
    }

    @Test
    void aRealSuccessfulPreviewWithZeroObservationsStillClassifiesAsEmpty() {
        // Regression safety for the sync_failed fix: only an *explicit* technical state may override the
        // rows=0 default classification - a genuinely successful call (preview_state="ok") that simply
        // found no observations must still be EMPTY, exactly as before this fix.
        assertThat(SearchV2PreviewOutcome.classify(Map.of("ok", false, "preview_state", "ok", "rows", 0)))
                .isEqualTo(SearchV2PreviewOutcome.EMPTY);
    }

    @Test
    void internalErrorStateIsClassifiedAsInternalFailureNeverAsTransportFailure() {
        String outcome = SearchV2PreviewOutcome.classify(Map.of("ok", false, "preview_state", "internal_error"));

        assertThat(outcome).isEqualTo(SearchV2PreviewOutcome.INTERNAL_FAILURE);
        assertThat(outcome).isNotEqualTo(SearchV2PreviewOutcome.TRANSPORT_FAILURE);
    }

    @Test
    void circuitOpenStateIsClassifiedAsCircuitOpenNeverAsTransportFailure() {
        String outcome = SearchV2PreviewOutcome.classify(Map.of("ok", false, "preview_state", "circuit_open"));

        assertThat(outcome).isEqualTo(SearchV2PreviewOutcome.CIRCUIT_OPEN);
        assertThat(outcome).isNotEqualTo(SearchV2PreviewOutcome.TRANSPORT_FAILURE);
    }

    @Test
    void bulkheadRejectedStateIsClassifiedAsBulkheadRejectedNeverAsTimeout() {
        String outcome = SearchV2PreviewOutcome.classify(Map.of("ok", false, "preview_state", "bulkhead_rejected"));

        assertThat(outcome).isEqualTo(SearchV2PreviewOutcome.BULKHEAD_REJECTED);
        assertThat(outcome).isNotEqualTo(SearchV2PreviewOutcome.TIMEOUT);
    }

    @Test
    void unsupportedStateIsClassifiedAsUnsupported() {
        assertThat(SearchV2PreviewOutcome.classify(Map.of("ok", false, "preview_state", "unsupported")))
                .isEqualTo(SearchV2PreviewOutcome.UNSUPPORTED);
    }

    @Test
    void structurallyInvalidStateIsClassifiedAsStructurallyInvalid() {
        assertThat(SearchV2PreviewOutcome.classify(Map.of("ok", false, "preview_state", "structurally_invalid")))
                .isEqualTo(SearchV2PreviewOutcome.STRUCTURALLY_INVALID);
    }

    @Test
    void legacyPr7bInvalidStringIsAcceptedOnInputAndMapsToTheNewCanonicalOutcome() {
        assertThat(SearchV2PreviewOutcome.classify(Map.of("ok", false, "preview_state", "invalid")))
                .as("PR-7c renamed INVALID -> STRUCTURALLY_INVALID; the old wire value must still classify "
                        + "correctly (e.g. for already-persisted telemetry), even though nothing produces it anymore")
                .isEqualTo(SearchV2PreviewOutcome.STRUCTURALLY_INVALID);
    }

    @Test
    void notOkWithZeroRowsAndNoTechnicalStateIsClassifiedAsConfirmedEmpty() {
        assertThat(SearchV2PreviewOutcome.classify(Map.of("ok", false, "preview_state", "ok", "rows", 0)))
                .isEqualTo(SearchV2PreviewOutcome.EMPTY);
    }

    @Test
    void blankOrUnknownStateWithoutAZeroRowCountIsClassifiedAsPossibleNotEmptyOrFailed() {
        assertThat(SearchV2PreviewOutcome.classify(Map.of("ok", false, "preview_state", "something_unrecognized")))
                .isEqualTo(SearchV2PreviewOutcome.POSSIBLE);
        assertThat(SearchV2PreviewOutcome.classify(Map.of("ok", false, "preview_state", "")))
                .isEqualTo(SearchV2PreviewOutcome.POSSIBLE);
        assertThat(SearchV2PreviewOutcome.classify(Map.of("ok", false)))
                .as("a status with no preview_state or rows at all must still classify to something "
                        + "explicit, not silently disappear")
                .isEqualTo(SearchV2PreviewOutcome.POSSIBLE);
    }

    @Test
    void nullOrEmptyStatusIsNotChecked() {
        assertThat(SearchV2PreviewOutcome.classify(null)).isEqualTo(SearchV2PreviewOutcome.NOT_CHECKED);
        assertThat(SearchV2PreviewOutcome.classify(Map.of())).isEqualTo(SearchV2PreviewOutcome.NOT_CHECKED);
    }

    @Test
    void allOutcomesArePairwiseDistinct() {
        assertThat(List.of(
                        SearchV2PreviewOutcome.VERIFIED,
                        SearchV2PreviewOutcome.POSSIBLE,
                        SearchV2PreviewOutcome.TIMEOUT,
                        SearchV2PreviewOutcome.CANCELLED,
                        SearchV2PreviewOutcome.TRANSPORT_FAILURE,
                        SearchV2PreviewOutcome.UNSUPPORTED,
                        SearchV2PreviewOutcome.INTERNAL_FAILURE,
                        SearchV2PreviewOutcome.CIRCUIT_OPEN,
                        SearchV2PreviewOutcome.BULKHEAD_REJECTED,
                        SearchV2PreviewOutcome.EMPTY,
                        SearchV2PreviewOutcome.STRUCTURALLY_INVALID,
                        SearchV2PreviewOutcome.NOT_CHECKED))
                .doesNotHaveDuplicates()
                .hasSize(12);
    }

    @Test
    void unverifiedBucketContainsExactlyTheOutcomesThatAreRelevantButNotConfirmed() {
        for (String outcome : List.of(
                SearchV2PreviewOutcome.POSSIBLE,
                SearchV2PreviewOutcome.TIMEOUT,
                SearchV2PreviewOutcome.CANCELLED,
                SearchV2PreviewOutcome.TRANSPORT_FAILURE,
                SearchV2PreviewOutcome.UNSUPPORTED,
                SearchV2PreviewOutcome.INTERNAL_FAILURE,
                SearchV2PreviewOutcome.CIRCUIT_OPEN,
                SearchV2PreviewOutcome.BULKHEAD_REJECTED)) {
            assertThat(SearchV2PreviewOutcome.isUnverifiedBucket(outcome)).as(outcome).isTrue();
        }
        for (String outcome : List.of(
                SearchV2PreviewOutcome.VERIFIED,
                SearchV2PreviewOutcome.EMPTY,
                SearchV2PreviewOutcome.STRUCTURALLY_INVALID,
                SearchV2PreviewOutcome.NOT_CHECKED)) {
            assertThat(SearchV2PreviewOutcome.isUnverifiedBucket(outcome)).as(outcome).isFalse();
        }
    }

    @Test
    void rejectedBucketContainsExactlyEmptyAndStructurallyInvalid() {
        assertThat(SearchV2PreviewOutcome.isRejectedBucket(SearchV2PreviewOutcome.EMPTY)).isTrue();
        assertThat(SearchV2PreviewOutcome.isRejectedBucket(SearchV2PreviewOutcome.STRUCTURALLY_INVALID)).isTrue();
        assertThat(SearchV2PreviewOutcome.isRejectedBucket(SearchV2PreviewOutcome.TIMEOUT)).isFalse();
        assertThat(SearchV2PreviewOutcome.isRejectedBucket(SearchV2PreviewOutcome.POSSIBLE)).isFalse();
        assertThat(SearchV2PreviewOutcome.isRejectedBucket(SearchV2PreviewOutcome.VERIFIED)).isFalse();
    }

    // ---- sync_failed fix: centralized circuit-breaker failure policy, exhaustive over every outcome --

    @Test
    void breakerFailureBucketContainsExactlyTimeoutAndTransportFailure() {
        for (String outcome : List.of(SearchV2PreviewOutcome.TIMEOUT, SearchV2PreviewOutcome.TRANSPORT_FAILURE)) {
            assertThat(SearchV2PreviewOutcome.isBreakerFailure(outcome)).as(outcome).isTrue();
        }
        for (String outcome : List.of(
                SearchV2PreviewOutcome.VERIFIED,
                SearchV2PreviewOutcome.POSSIBLE,
                SearchV2PreviewOutcome.CANCELLED,
                SearchV2PreviewOutcome.UNSUPPORTED,
                SearchV2PreviewOutcome.INTERNAL_FAILURE,
                SearchV2PreviewOutcome.CIRCUIT_OPEN,
                SearchV2PreviewOutcome.BULKHEAD_REJECTED,
                SearchV2PreviewOutcome.EMPTY,
                SearchV2PreviewOutcome.STRUCTURALLY_INVALID,
                SearchV2PreviewOutcome.NOT_CHECKED)) {
            assertThat(SearchV2PreviewOutcome.isBreakerFailure(outcome))
                    .as(outcome + " must never count against a connector's circuit-breaker health")
                    .isFalse();
        }
    }
}
