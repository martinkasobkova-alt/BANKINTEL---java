package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.search.CatalogPreviewService;
import cz.bankintel.search.PreviewResponseBuilder;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SearchV2PreviewOutcome;
import cz.bankintel.search.v2.schema.SemanticDecision;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration-level tests proving PR-10's {@link SearchV2PreviewCircuitBreaker} is wired correctly
 * into the real {@link SearchV2PreviewVerifier} dispatch path, not just the breaker class in
 * isolation ({@code SearchV2PreviewCircuitBreakerTest} covers that).
 */
class SearchV2PreviewCircuitBreakerIntegrationTest {

    private SearchV2PreviewVerifier verifier;

    @AfterEach
    void cleanup() {
        System.clearProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED");
        System.clearProperty("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS_FRED");
        System.clearProperty("SEARCH_PREVIEW_CONCURRENCY");
        if (verifier != null) {
            verifier.shutdown();
        }
    }

    // ---- Regression safety: disabled by default --------------------------------------------------

    @Test
    void disabledByDefaultNeverFailsFastEvenAfterRepeatedFailures() {
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(anyMap())).thenReturn(Map.of("preview_state", "error", "rows", List.of(), "message", "boom"));

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());
        for (int i = 0; i < 5; i++) {
            verifier.verifyTopOnly(List.of(result("fred-" + i, "fred")), 1, List.of());
        }

        verify(previewService, times(5)).preview(anyMap());
    }

    // ---- Open breaker fails fast for its own source, leaves other sources unaffected --------------

    @Test
    void openBreakerFailsFastForItsSourceWhileADifferentSourceInTheSameBatchSucceeds() {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> payload = invocation.getArgument(0);
            if ("fred".equals(payload.get("source"))) {
                return Map.of("preview_state", "error", "rows", List.of(), "message", "boom");
            }
            return Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1)));
        });

        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();
        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService(), null, null, breaker);

        verifier.verifyTopOnly(List.of(result("fred-1", "fred")), 1, List.of());
        assertThat(breaker.stateOf("fred")).isEqualTo(SearchV2PreviewCircuitBreaker.State.OPEN);

        SearchV2PreviewVerifier.VerificationResult result2 = verifier.verifyTopOnly(
                List.of(result("fred-2", "fred"), result("ecb-1", "ecb2")), 2, List.of());

        assertThat(String.valueOf(statusFor(result2, "fred-2").get("reason")))
                .as("second fred candidate must fail fast with an explicit circuit-open reason, not a real connector call")
                .contains("circuit_breaker_open");
        assertThat(statusFor(result2, "ecb-1").get("ok"))
                .as("a different source in the same batch must be completely unaffected")
                .isEqualTo(true);
        verify(previewService, times(1))
                .preview(argThat(p -> "fred".equals(p.get("source"))));
    }

    // ---- Recovery: cooldown elapses, a successful half-open trial closes the breaker --------------

    @Test
    void breakerRecoversAfterCooldownViaASuccessfulHalfOpenTrial() throws Exception {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS_FRED", "150");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        AtomicInteger fredCallCount = new AtomicInteger(0);
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> payload = invocation.getArgument(0);
            if ("fred".equals(payload.get("source"))) {
                int call = fredCallCount.incrementAndGet();
                if (call == 1) {
                    return Map.of("preview_state", "error", "rows", List.of(), "message", "boom");
                }
                return Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1)));
            }
            return Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1)));
        });

        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();
        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService(), null, null, breaker);

        verifier.verifyTopOnly(List.of(result("fred-1", "fred")), 1, List.of());
        assertThat(breaker.stateOf("fred")).isEqualTo(SearchV2PreviewCircuitBreaker.State.OPEN);

        Thread.sleep(200); // cooldown elapses

        SearchV2PreviewVerifier.VerificationResult recovered =
                verifier.verifyTopOnly(List.of(result("fred-2", "fred")), 1, List.of());

        assertThat(statusFor(recovered, "fred-2").get("ok"))
                .as("the half-open trial call actually reached the connector and succeeded")
                .isEqualTo(true);
        assertThat(breaker.stateOf("fred")).isEqualTo(SearchV2PreviewCircuitBreaker.State.CLOSED);
        verify(previewService, times(2)).preview(argThat(p -> "fred".equals(p.get("source"))));
    }

    // ---- sync_failed fix: the real PreviewResponseBuilder.buildError shape must drive the breaker ----
    // ---- exactly like the "error"/"timeout" cases above - not just a hand-picked "error" string -------

    @Test
    void aSingleSyncFailedBelowThresholdKeepsTheBreakerClosed() {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "2");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(anyMap())).thenReturn(PreviewResponseBuilder.buildError(
                Map.of("source_type", "fred", "set_id", "gdp"), Map.of("source_type", "fred", "set_id", "gdp"),
                503, Map.of("error", "Service Unavailable")));

        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();
        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService(), null, null, breaker);

        SearchV2PreviewVerifier.VerificationResult result =
                verifier.verifyTopOnly(List.of(result("fred-1", "fred")), 1, List.of());

        assertThat(result.statuses().get(0).get("preview_state")).isEqualTo("sync_failed");
        assertThat(breaker.stateOf("fred"))
                .as("one sync_failed below the configured threshold of 2 must not yet open the breaker")
                .isEqualTo(SearchV2PreviewCircuitBreaker.State.CLOSED);
    }

    @Test
    void repeatedSyncFailedResponsesUpToTheThresholdOpenTheBreaker() {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "2");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(anyMap())).thenReturn(PreviewResponseBuilder.buildError(
                Map.of("source_type", "fred", "set_id", "gdp"), Map.of("source_type", "fred", "set_id", "gdp"),
                503, Map.of("error", "Service Unavailable")));

        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();
        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService(), null, null, breaker);

        verifier.verifyTopOnly(List.of(result("fred-1", "fred")), 1, List.of());
        assertThat(breaker.stateOf("fred")).isEqualTo(SearchV2PreviewCircuitBreaker.State.CLOSED);

        verifier.verifyTopOnly(List.of(result("fred-2", "fred")), 1, List.of());
        assertThat(breaker.stateOf("fred"))
                .as("the second consecutive sync_failed reaches the threshold of 2 and must open the breaker")
                .isEqualTo(SearchV2PreviewCircuitBreaker.State.OPEN);
    }

    @Test
    void afterTheBreakerOpensFromSyncFailedTheNextRequestFailsFastWithoutANewTransportCall() {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(anyMap())).thenReturn(PreviewResponseBuilder.buildError(
                Map.of("source_type", "fred", "set_id", "gdp"), Map.of("source_type", "fred", "set_id", "gdp"),
                503, Map.of("error", "Service Unavailable")));

        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();
        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService(), null, null, breaker);

        verifier.verifyTopOnly(List.of(result("fred-1", "fred")), 1, List.of()); // opens the breaker
        assertThat(breaker.stateOf("fred")).isEqualTo(SearchV2PreviewCircuitBreaker.State.OPEN);

        SearchV2PreviewVerifier.VerificationResult second =
                verifier.verifyTopOnly(List.of(result("fred-2", "fred")), 1, List.of());

        assertThat(second.statuses().get(0).get("preview_state"))
                .as("must fail fast as circuit_open, not a second sync_failed")
                .isEqualTo("circuit_open");
        verify(previewService, times(1))
                .preview(argThat(p -> "fred".equals(p.get("source"))));
    }

    @Test
    void successAfterHalfOpenTrialClosesTheBreakerFollowingSyncFailedFailures() throws Exception {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS_FRED", "150");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        AtomicInteger fredCallCount = new AtomicInteger(0);
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(anyMap())).thenAnswer(invocation -> {
            if (fredCallCount.incrementAndGet() == 1) {
                return PreviewResponseBuilder.buildError(
                        Map.of("source_type", "fred", "set_id", "gdp"), Map.of("source_type", "fred", "set_id", "gdp"),
                        503, Map.of("error", "Service Unavailable"));
            }
            return Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1)));
        });

        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();
        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService(), null, null, breaker);

        verifier.verifyTopOnly(List.of(result("fred-1", "fred")), 1, List.of());
        assertThat(breaker.stateOf("fred")).isEqualTo(SearchV2PreviewCircuitBreaker.State.OPEN);

        Thread.sleep(200); // cooldown elapses

        SearchV2PreviewVerifier.VerificationResult recovered =
                verifier.verifyTopOnly(List.of(result("fred-2", "fred")), 1, List.of());

        assertThat(recovered.statuses().get(0).get("ok")).isEqualTo(true);
        assertThat(breaker.stateOf("fred"))
                .as("a successful half-open trial must close the breaker even though it was opened by sync_failed")
                .isEqualTo(SearchV2PreviewCircuitBreaker.State.CLOSED);
    }

    // ---- HTTP 429 and HTTP 5xx currently produce byte-identical "sync_failed" shapes: both must -----
    // ---- classify as TRANSPORT_FAILURE and count toward the breaker the same way ---------------------

    @Test
    void http429ViaTheRealBuildErrorPathClassifiesAsTransportFailureAndCountsTowardTheBreaker() {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(anyMap())).thenReturn(PreviewResponseBuilder.buildError(
                Map.of("source_type", "fred", "set_id", "gdp"), Map.of("source_type", "fred", "set_id", "gdp"),
                429, Map.of("error", "Too Many Requests")));

        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();
        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService(), null, null, breaker);

        SearchV2PreviewVerifier.VerificationResult result =
                verifier.verifyTopOnly(List.of(result("fred-429", "fred")), 1, List.of());

        Map<String, Object> status = result.statuses().get(0);
        assertThat(status.get("preview_state"))
                .as("429 currently produces the same sync_failed shape as any 5xx - no distinct policy in this PR")
                .isEqualTo("sync_failed");
        assertThat(SearchV2PreviewOutcome.classify(status)).isEqualTo(SearchV2PreviewOutcome.TRANSPORT_FAILURE);
        assertThat(breaker.stateOf("fred"))
                .as("a 429, exactly like a 5xx, must count as a connector failure and can open the breaker")
                .isEqualTo(SearchV2PreviewCircuitBreaker.State.OPEN);
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
