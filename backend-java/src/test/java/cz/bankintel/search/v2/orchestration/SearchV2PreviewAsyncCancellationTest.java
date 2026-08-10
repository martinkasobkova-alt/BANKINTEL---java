package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.search.CatalogPreviewOrchestrator;
import cz.bankintel.search.CatalogPreviewService;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SemanticDecision;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration-level tests of PR-6's flag-gated path through the real
 * {@link SearchV2PreviewVerifier}, wiring a fake {@link CatalogPreviewService} exactly the way
 * {@code SearchV2PreviewCancellationReproTest} does for the pre-existing (flag-off) behavior.
 *
 * <p>These tests set {@code SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED=true} and prove the NEW
 * mechanism's actual behavior — they do not touch or "fix"
 * {@code SearchV2PreviewCancellationReproTest}, which continues to document the flag-off default
 * behavior (unchanged by this PR, by design — that is the rollback path).
 */
class SearchV2PreviewAsyncCancellationTest {

    private static final long AWAIT_MS = 10_000;

    private SearchV2PreviewVerifier verifier;

    @AfterEach
    void cleanup() {
        System.clearProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_CONCURRENCY");
        System.clearProperty("SEARCH_PREVIEW_TIMEOUT_MS");
        if (verifier != null) {
            verifier.shutdown();
        }
    }

    // ---- Category 1: running (async) request timeout -----------------------------------------

    @Test
    void asyncHttpTransportFutureIsGenuinelyCancelledOnTimeout() {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "500");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        // Never completes on its own - stands in for a hung real HTTP exchange.
        CompletableFuture<Object> transportFuture = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> responseFuture = transportFuture.handle(
                (value, ex) -> Map.of("ok", false, "preview_state", "error", "rows", List.of()));
        when(previewService.previewAsyncIfSupported(anyMap()))
                .thenReturn(Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(transportFuture, responseFuture)));

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        SearchV2PreviewVerifier.VerificationResult result =
                verifier.verifyTopOnly(List.of(result("slow-async")), 1, List.of());

        assertThat(result.statuses()).hasSize(1);
        assertThat(result.statuses().get(0).get("preview_state")).isEqualTo("timeout");
        assertThat(transportFuture.isCancelled())
                .as("the real transport future — not a wrapper — must be cancelled")
                .isTrue();
    }

    // ---- Category 2: queued (fallback) request that IS given a chance to run -----------------

    @Test
    void fallbackRequestQueuedBehindABusyPoolStillRunsOnceFreedBeforeItsOwnTimeout() throws Exception {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "1");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "5000"); // generous: longer than the slow hold below

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.previewAsyncIfSupported(anyMap())).thenReturn(Optional.empty()); // force fallback path

        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);
        AtomicBoolean fastRan = new AtomicBoolean(false);
        when(previewService.preview(any())).thenAnswer(invocation -> {
            Map<String, Object> payload = invocation.getArgument(0);
            String id = String.valueOf(payload.get("set_id"));
            if ("slow-fb".equals(id)) {
                slowStarted.countDown();
                releaseGate.await();
                return Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1)));
            }
            fastRan.set(true);
            return Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1)));
        });

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        Thread slowCaller = new Thread(() -> verifier.verifyTopOnly(List.of(result("slow-fb")), 1, List.of()));
        slowCaller.start();
        assertThat(slowStarted.await(AWAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)).isTrue();

        Thread fastCaller = new Thread(() -> verifier.verifyTopOnly(List.of(result("fast-fb")), 1, List.of()));
        fastCaller.start();
        Thread.sleep(200); // let the fast task genuinely sit queued behind the busy sole worker

        releaseGate.countDown();
        slowCaller.join(AWAIT_MS);
        fastCaller.join(AWAIT_MS);

        assertThat(slowCaller.isAlive()).isFalse();
        assertThat(fastCaller.isAlive()).isFalse();
        assertThat(fastRan)
                .as("a request that was merely queued (not yet timed out) must actually run once the "
                        + "worker frees up — the raw-Runnable dispatch has no AsyncSupply-style guard "
                        + "that silently skips it")
                .isTrue();
    }

    // ---- Concrete measurement: how much sooner is the worker freed after timeout? -------------

    @Test
    void fallbackWorkerIsFreedWithinMillisecondsOfInterruptInsteadOfWaitingForTheConnectorsOwnTimeout() throws Exception {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "1");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "500");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.previewAsyncIfSupported(anyMap())).thenReturn(Optional.empty()); // fallback path
        long connectorOwnTimeoutMs = 60_000; // stand-in for e.g. ARAD's real 10-minute connector timeout
        java.util.concurrent.atomic.AtomicLong workerFreedAtMs = new java.util.concurrent.atomic.AtomicLong(-1);
        long dispatchedAtMs = System.currentTimeMillis();
        when(previewService.preview(any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(connectorOwnTimeoutMs); // a blocking call that only a real interrupt escapes early
            } catch (InterruptedException ie) {
                workerFreedAtMs.set(System.currentTimeMillis());
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted");
            }
            return Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1)));
        });

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());
        verifier.verifyTopOnly(List.of(result("slow-interruptible")), 1, List.of());

        // verifyTopOnly() returns as soon as the arbiter decides TIMEOUT (~500ms); the interrupt it
        // triggers takes a few extra ms to actually unwind the sleeping worker thread, so poll briefly.
        long deadline = System.currentTimeMillis() + 5_000;
        while (workerFreedAtMs.get() < 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        long freedAfterMs = workerFreedAtMs.get() - dispatchedAtMs;
        System.out.printf("SearchV2PreviewAsyncCancellationTest measured: worker freed %d ms after dispatch "
                + "(SearchV2 preview timeout=500ms, connector's own timeout=%d ms)%n", freedAfterMs, connectorOwnTimeoutMs);
        assertThat(workerFreedAtMs.get())
                .as("the worker must have been freed via interrupt, not by waiting out the connector's own timeout")
                .isGreaterThan(0);
        assertThat(freedAfterMs)
                .as("worker was freed %d ms after dispatch (SearchV2 preview timeout was 500 ms; the "
                        + "connector's own configured timeout — e.g. ARAD's real 10 minutes — was %d ms, "
                        + "never reached)", freedAfterMs, connectorOwnTimeoutMs)
                .isLessThan(connectorOwnTimeoutMs);
    }

    // ---- Category 7: batch isolation -----------------------------------------------------------

    @Test
    void oneTimedOutCandidateDoesNotPreventOtherCandidatesInTheSameBatchFromSucceeding() {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "500");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.previewAsyncIfSupported(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> payload = invocation.getArgument(0);
            String id = String.valueOf(payload.get("set_id"));
            if ("slow-batch".equals(id)) {
                CompletableFuture<Object> transport = new CompletableFuture<>(); // never completes
                CompletableFuture<Map<String, Object>> response =
                        transport.handle((v, ex) -> Map.of("ok", false, "preview_state", "error"));
                return Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(transport, response));
            }
            CompletableFuture<Object> transport = CompletableFuture.completedFuture(new Object());
            CompletableFuture<Map<String, Object>> response =
                    CompletableFuture.completedFuture(Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1))));
            return Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(transport, response));
        });

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        SearchV2PreviewVerifier.VerificationResult result = verifier.verifyTopOnly(
                List.of(result("slow-batch"), result("fast-batch-1"), result("fast-batch-2")), 3, List.of());

        assertThat(result.statuses()).hasSize(3);
        Map<String, Object> slowStatus = statusFor(result, "slow-batch");
        assertThat(slowStatus.get("preview_state")).isEqualTo("timeout");
        assertThat(statusFor(result, "fast-batch-1").get("ok")).isEqualTo(true);
        assertThat(statusFor(result, "fast-batch-2").get("ok")).isEqualTo(true);
        assertThat(result.accepted()).hasSize(2);
    }

    // ---- Regression safety: flag off never touches the new path --------------------------------

    @Test
    void flagOffNeverCallsTheAsyncPreviewPath() {
        // SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED intentionally left unset/false.
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "2000");
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(any())).thenReturn(Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1))));

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());
        verifier.verifyTopOnly(List.of(result("flag-off")), 1, List.of());

        verify(previewService, never()).previewAsyncIfSupported(anyMap());
    }

    private static Map<String, Object> statusFor(SearchV2PreviewVerifier.VerificationResult result, String seriesId) {
        return result.statuses().stream()
                .filter(status -> seriesId.equals(status.get("series_id")))
                .findFirst()
                .orElseThrow();
    }

    private static SearchResult result(String id) {
        return new SearchResult(candidate(id), decision(id), 0);
    }

    private static SearchCandidate candidate(String id) {
        return new SearchCandidate(
                "fred:" + id, id, "Title " + id, "", "fred", "", "", "", "", "",
                List.of(), List.of(), List.of(), "", 1, "q", List.of(), Map.of());
    }

    private static SemanticDecision decision(String id) {
        return new SemanticDecision(id, "keep", 0.9, 0.9, List.of(), List.of(), "ok", "primary");
    }
}
