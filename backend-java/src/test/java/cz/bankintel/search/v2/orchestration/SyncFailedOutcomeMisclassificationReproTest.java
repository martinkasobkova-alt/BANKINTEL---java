package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.bankintel.search.CatalogPreviewService;
import cz.bankintel.search.PreviewResponseBuilder;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SearchV2PreviewOutcome;
import cz.bankintel.search.v2.schema.SemanticDecision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * REGRESSION test for a production finding from the Fáze 7 (circuit breaker) validation pass,
 * fixed by adding a {@code sync_failed} case to {@link SearchV2PreviewOutcome#classify} and
 * centralizing {@code SearchV2PreviewVerifier#recordBreakerOutcome} on that same classification.
 *
 * <p><b>Original bug:</b> {@code CatalogPreviewOrchestrator.preview(...)}/
 * {@code previewAsyncIfSupported(...)} route EVERY connector-level HTTP failure (429, 5xx,
 * connection errors - anything where {@code ConnectorFetchResult.isSuccess()} is false) through
 * {@code PreviewResponseBuilder.buildError(...)}, which sets {@code preview_state = "sync_failed"}
 * and {@code rows = []}. Before the fix, {@code SearchV2PreviewOutcome.classify(...)} did not
 * recognize {@code "sync_failed"} as a technical-failure state, fell through to the default branch,
 * saw {@code rows == 0}, and classified the candidate as {@code EMPTY} (rejected bucket - "confirmed,
 * this series has no data") instead of {@code TRANSPORT_FAILURE} (unverified bucket - "relevant, but
 * we could not verify it"). It also meant {@code recordBreakerOutcome} never counted this failure
 * mode, so the circuit breaker could never open from real HTTP 429/5xx/connection failures routed
 * through this path.
 *
 * <p>This test now asserts the FIXED behavior: a real {@code sync_failed} response classifies as
 * {@code TRANSPORT_FAILURE} (never {@code EMPTY}), and repeated failures through the full verifier
 * trip the circuit breaker exactly like any other transport failure.
 */
class SyncFailedOutcomeMisclassificationReproTest {

    @Test
    void aRealHttp5xxOrConnectionFailureViaPreviewResponseBuilderClassifiesAsTransportFailureNeverEmpty() {
        // Exactly what CatalogPreviewOrchestrator produces for a non-2xx/connection-failed fetch -
        // not a hand-picked "error" string, but the REAL shape from production code.
        Map<String, Object> realErrorResponse = PreviewResponseBuilder.buildError(
                Map.of("source_type", "ecb2", "set_id", "de-rate", "name", "DE interest rate"),
                Map.of("source_type", "ecb2", "set_id", "de-rate"),
                503,
                Map.of("error", "Service Unavailable"));

        assertThat(realErrorResponse.get("preview_state")).isEqualTo("sync_failed");

        // Reconstructed exactly as SearchV2PreviewVerifier.doVerify/toVerificationStatus builds it.
        int rows = ((List<?>) realErrorResponse.get("rows")).size();
        boolean ok = rows > 0
                && !"error".equalsIgnoreCase(String.valueOf(realErrorResponse.get("preview_state")))
                && !"unsupported".equalsIgnoreCase(String.valueOf(realErrorResponse.get("preview_state")));
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("source", "ecb2");
        status.put("series_id", "de-rate");
        status.put("ok", ok);
        status.put("rows", rows);
        status.put("preview_state", realErrorResponse.get("preview_state"));

        String classified = SearchV2PreviewOutcome.classify(status);

        assertThat(classified)
                .as("FIXED: a genuine HTTP 503 (sync_failed, rows=0) must classify as TRANSPORT_FAILURE "
                        + "(unverified - could not confirm), never as EMPTY (confirmed no data)")
                .isEqualTo(SearchV2PreviewOutcome.TRANSPORT_FAILURE);
        assertThat(SearchV2PreviewOutcome.isUnverifiedBucket(classified)).isTrue();
        assertThat(SearchV2PreviewOutcome.isRejectedBucket(classified)).isFalse();
    }

    @Test
    void aRealHttp5xxFailureThroughTheFullVerifierTripsTheCircuitBreaker() {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_ECB2", "1");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");
        try {
            CatalogPreviewService previewService = mock(CatalogPreviewService.class);
            when(previewService.preview(anyMap())).thenReturn(PreviewResponseBuilder.buildError(
                    Map.of("source_type", "ecb2", "set_id", "de-rate", "name", "DE interest rate"),
                    Map.of("source_type", "ecb2", "set_id", "de-rate"),
                    503,
                    Map.of("error", "Service Unavailable")));

            SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();
            SearchV2PreviewVerifier verifier =
                    new SearchV2PreviewVerifier(previewService, new SearchV2CacheService(), null, null, breaker);
            try {
                SearchV2PreviewVerifier.VerificationResult first =
                        verifier.verifyTopOnly(List.of(result("de-rate-1")), 1, List.of());
                SearchV2PreviewVerifier.VerificationResult second =
                        verifier.verifyTopOnly(List.of(result("de-rate-2")), 1, List.of());

                assertThat(first.statuses().get(0).get("preview_state")).isEqualTo("sync_failed");
                assertThat(second.statuses().get(0).get("preview_state"))
                        .as("FIXED: after a real 503 (threshold=1), the breaker must trip and the second "
                                + "request must fail fast as circuit_open instead of being dispatched again")
                        .isEqualTo("circuit_open");
                assertThat(breaker.stateOf("ecb2")).isEqualTo(SearchV2PreviewCircuitBreaker.State.OPEN);
            } finally {
                verifier.shutdown();
            }
        } finally {
            System.clearProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED");
            System.clearProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_ECB2");
            System.clearProperty("SEARCH_PREVIEW_CONCURRENCY");
        }
    }

    private static SearchResult result(String id) {
        return new SearchResult(candidate(id), decision(id), 0);
    }

    private static SearchCandidate candidate(String id) {
        return new SearchCandidate(
                "ecb2:" + id, id, "Title " + id, "", "ecb2", "", "", "", "", "",
                List.of(), List.of(), List.of(), "", 1, "q", List.of(), Map.of());
    }

    private static SemanticDecision decision(String id) {
        return new SemanticDecision(id, "keep", 0.9, 0.9, List.of(), List.of(), "ok", "primary");
    }
}
