package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.search.CatalogPreviewOrchestrator;
import cz.bankintel.search.CatalogPreviewService;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SearchV2PreviewOutcome;
import cz.bankintel.search.v2.schema.SemanticDecision;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * PR-7b: cross-cutting tests proving PR-6 (async cancellation), PR-8 (bulkhead), PR-9 (tiered
 * timeout) and PR-10 (circuit breaker) interact correctly with the granular outcome model - each
 * capacity-management outcome (bulkhead_rejected, circuit_open) is distinct from a genuine
 * connector-level failure, and only a real dispatched request's own timeout/failure ever affects a
 * source's circuit breaker health.
 */
class SearchV2PreviewCapacityOutcomeInteractionTest {

    private SearchV2PreviewVerifier verifier;

    @AfterEach
    void cleanup() {
        System.clearProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_FRED");
        System.clearProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED");
        System.clearProperty("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_TIMEOUT_FAST_MS");
        System.clearProperty("SEARCH_PREVIEW_TIMEOUT_NORMAL_MS");
        System.clearProperty("SEARCH_PREVIEW_TIMEOUT_SLOW_MS");
        System.clearProperty("SEARCH_PREVIEW_TIMEOUT_MS");
        System.clearProperty("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS_FRED");
        System.clearProperty("SEARCH_PREVIEW_CONCURRENCY");
        if (verifier != null) {
            verifier.shutdown();
        }
    }

    @Test
    void bulkheadDenialNeverIncrementsTheCircuitBreakerFailureCounterForThatSource() {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "2");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "300");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.previewAsyncIfSupported(anyMap())).thenReturn(Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(
                CompletableFuture.completedFuture(new Object()),
                CompletableFuture.completedFuture(Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1)))))));

        SearchV2PreviewBulkhead bulkhead = new SearchV2PreviewBulkhead();
        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();
        Optional<Runnable> heldPermit = bulkhead.tryAdmit("fred", 10);
        assertThat(heldPermit).as("test pre-holds the sole fred permit").isPresent();

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService(), null, bulkhead, breaker);

        for (int i = 0; i < 3; i++) {
            SearchV2PreviewVerifier.VerificationResult result =
                    verifier.verifyTopOnly(List.of(result("blocked-fred-" + i, "fred")), 1, List.of());
            assertThat(result.statuses().get(0).get("preview_state")).isEqualTo("bulkhead_rejected");
        }

        assertThat(breaker.stateOf("fred"))
                .as("bulkhead denial (never even attempted) must never count against a connector's health, "
                        + "even after repeated denials well past the configured failure threshold of 2")
                .isEqualTo(SearchV2PreviewCircuitBreaker.State.CLOSED);
    }

    @Test
    void circuitOpenIsADistinctOutcomeFromTransportFailureOnceTheBreakerTrips() {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(anyMap())).thenReturn(Map.of("preview_state", "error", "rows", List.of(), "message", "boom"));

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        SearchV2PreviewVerifier.VerificationResult first = verifier.verifyTopOnly(List.of(result("fred-1", "fred")), 1, List.of());
        assertThat(first.statuses().get(0).get("preview_state"))
                .as("the real connector call failed - this attempt is a genuine transport_failure")
                .isEqualTo("error");

        SearchV2PreviewVerifier.VerificationResult second = verifier.verifyTopOnly(List.of(result("fred-2", "fred")), 1, List.of());
        assertThat(second.statuses().get(0).get("preview_state"))
                .as("once tripped, the next request must be circuit_open - never another transport_failure, "
                        + "since no real connector call is even attempted this time")
                .isEqualTo("circuit_open");
    }

    // ---- Validation phase (Fáze 7, scenario 16): STRUCTURALLY_INVALID never affects the breaker ---

    @Test
    void structurallyInvalidCandidateNeverAffectsTheCircuitBreakerRegardlessOfRepetition() {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();
        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService(), null, null, breaker);

        // A candidate missing series_id - structurally invalid, rejected before even the breaker check.
        SearchCandidate malformed = new SearchCandidate(
                "fred:", "", "Malformed", "", "fred", "", "", "", "", "",
                List.of(), List.of(), List.of(), "", 1, "q", List.of(), Map.of());
        for (int i = 0; i < 5; i++) {
            SearchV2PreviewVerifier.VerificationResult result = verifier.verifyTopOnly(
                    List.of(new SearchResult(malformed, decision("malformed"), 0)), 1, List.of());
            assertThat(result.statuses().get(0).get("preview_state")).isEqualTo("structurally_invalid");
        }

        assertThat(breaker.stateOf("fred"))
                .as("repeated structurally-invalid candidates must never count as connector failures")
                .isEqualTo(SearchV2PreviewCircuitBreaker.State.CLOSED);
        verify(previewService, never()).preview(anyMap());
    }

    @Test
    void requestNeverDispatchedDueToCircuitBeingOpenDoesNotFurtherAffectBreakerState() {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS_FRED", "60000");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(anyMap())).thenReturn(Map.of("preview_state", "error", "rows", List.of(), "message", "boom"));

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());
        verifier.verifyTopOnly(List.of(result("fred-1", "fred")), 1, List.of()); // trips the breaker open

        for (int i = 0; i < 5; i++) {
            SearchV2PreviewVerifier.VerificationResult rejected =
                    verifier.verifyTopOnly(List.of(result("fred-rejected-" + i, "fred")), 1, List.of());
            assertThat(rejected.statuses().get(0).get("preview_state")).isEqualTo("circuit_open");
        }
        // Still well within the (60s) cooldown - none of the 5 fail-fast rejections above should have
        // reset or otherwise perturbed the open state into anything other than plain OPEN/circuit_open.
    }

    @Test
    void aGenuineTimeoutOfAnActuallyDispatchedRequestCountsAsAFailureAndCanTripTheBreaker() {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "300");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        CompletableFuture<Object> transport = new CompletableFuture<>(); // dispatched, never resolves -> genuine timeout
        CompletableFuture<Map<String, Object>> response = transport.handle((v, ex) -> Map.of("ok", false, "preview_state", "error"));
        when(previewService.previewAsyncIfSupported(anyMap()))
                .thenReturn(Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(transport, response)));

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        SearchV2PreviewVerifier.VerificationResult first = verifier.verifyTopOnly(List.of(result("fred-t1", "fred")), 1, List.of());
        assertThat(first.statuses().get(0).get("preview_state")).isEqualTo("timeout");

        SearchV2PreviewVerifier.VerificationResult second = verifier.verifyTopOnly(List.of(result("fred-t2", "fred")), 1, List.of());
        assertThat(second.statuses().get(0).get("preview_state"))
                .as("a genuine timeout of a request that actually ran must be able to trip the breaker")
                .isEqualTo("circuit_open");
    }

    // ---- Smoke test: PR-6 through PR-10 flags all enabled together, across distinct outcome types ---

    @Test
    void allFourFeatureFlagsWorkTogetherAcrossVerifiedTimeoutAndTransportFailureOutcomes() {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "400");
        // Pin every tier to the same small value so enabling PR-9's tiering alongside this test's other
        // flags does not silently fall back to its own 8000ms per-tier default and slow the test down.
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_FAST_MS", "400");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_NORMAL_MS", "400");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_SLOW_MS", "400");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "4");

        CompletableFuture<Object> timeoutTransport = new CompletableFuture<>(); // never completes
        CompletableFuture<Map<String, Object>> timeoutResponse =
                timeoutTransport.handle((v, ex) -> Map.of("ok", false, "preview_state", "error"));

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.previewAsyncIfSupported(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> payload = invocation.getArgument(0);
            String source = String.valueOf(payload.get("source"));
            return switch (source) {
                case "ecb2" -> Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(
                        timeoutTransport, timeoutResponse));
                case "arad" -> Optional.<CatalogPreviewOrchestrator.AsyncPreviewHandle>empty(); // forces fallback path -> preview()
                default -> Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(
                        CompletableFuture.completedFuture(new Object()),
                        CompletableFuture.completedFuture(Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1))))));
            };
        });
        when(previewService.preview(anyMap())).thenReturn(Map.of("preview_state", "error", "rows", List.of(), "message", "boom"));

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        SearchV2PreviewVerifier.VerificationResult result = verifier.verifyTopOnly(
                List.of(result("verified-fred", "fred"), result("timeout-ecb", "ecb2"), result("failed-arad", "arad")),
                3,
                List.of());

        Map<String, Object> fredStatus = statusFor(result, "verified-fred");
        Map<String, Object> ecbStatus = statusFor(result, "timeout-ecb");
        Map<String, Object> aradStatus = statusFor(result, "failed-arad");

        assertThat(SearchV2PreviewOutcome.classify(fredStatus)).isEqualTo(SearchV2PreviewOutcome.VERIFIED);
        assertThat(SearchV2PreviewOutcome.classify(ecbStatus)).isEqualTo(SearchV2PreviewOutcome.TIMEOUT);
        assertThat(SearchV2PreviewOutcome.classify(aradStatus)).isEqualTo(SearchV2PreviewOutcome.TRANSPORT_FAILURE);
    }

    private static Map<String, Object> statusFor(SearchV2PreviewVerifier.VerificationResult result, String seriesId) {
        return result.statuses().stream()
                .filter(status -> seriesId.equals(status.get("series_id")))
                .findFirst()
                .orElseThrow();
    }

    private static SearchResult result(String id, String source) {
        return new SearchResult(candidate(id, source), decision(id), 0);
    }

    private static SearchCandidate candidate(String id, String source) {
        return new SearchCandidate(
                source + ":" + id, id, "Title " + id, "", source, "", "", "", "", "",
                List.of(), List.of(), List.of(), "", 1, "q", List.of(), Map.of());
    }

    private static SemanticDecision decision(String id) {
        return new SemanticDecision(id, "keep", 0.9, 0.9, List.of(), List.of(), "ok", "primary");
    }
}
