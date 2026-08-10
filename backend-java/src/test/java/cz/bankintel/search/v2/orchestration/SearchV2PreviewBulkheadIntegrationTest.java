package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration-level tests proving PR-8's {@link SearchV2PreviewBulkhead} is wired correctly into
 * the real {@link SearchV2PreviewVerifier} dispatch path (both the PR-6 async-cancellable path and
 * the pre-existing legacy path), not just the bulkhead class in isolation
 * ({@code SearchV2PreviewBulkheadTest} covers that).
 */
class SearchV2PreviewBulkheadIntegrationTest {

    private SearchV2PreviewVerifier verifier;

    @AfterEach
    void cleanup() {
        System.clearProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_BULKHEAD_DEFAULT_LIMIT");
        System.clearProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_FRED");
        System.clearProperty("SEARCH_PREVIEW_CONCURRENCY");
        System.clearProperty("SEARCH_PREVIEW_TIMEOUT_MS");
        if (verifier != null) {
            verifier.shutdown();
        }
    }

    // ---- Regression safety: disabled by default --------------------------------------------------

    @Test
    void bulkheadDisabledByDefaultLeavesMultiCandidateBehaviorUnchanged() {
        // SEARCH_PREVIEW_BULKHEAD_ENABLED intentionally left unset.
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(anyMap())).thenReturn(Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1))));

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());
        SearchV2PreviewVerifier.VerificationResult result = verifier.verifyTopOnly(
                List.of(result("a", "fred"), result("b", "fred"), result("c", "fred"), result("d", "fred")), 4, List.of());

        assertThat(result.accepted()).hasSize(4);
    }

    // ---- Bulkhead saturation produces an explicit outcome, not silent candidate loss -------------

    @Test
    void bulkheadSaturationForOneSourceProducesExplicitBulkheadRejectedWhileADifferentSourceSucceeds() throws Exception {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "500");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.previewAsyncIfSupported(anyMap())).thenReturn(Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(
                CompletableFuture.completedFuture(new Object()),
                CompletableFuture.completedFuture(Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1)))))));

        SearchV2PreviewBulkhead bulkhead = new SearchV2PreviewBulkhead();
        Optional<Runnable> heldFredPermit = bulkhead.tryAdmit("fred", 10);
        assertThat(heldFredPermit).as("test pre-holds the sole fred permit to make saturation deterministic").isPresent();

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService(), null, bulkhead);

        SearchV2PreviewVerifier.VerificationResult result = verifier.verifyTopOnly(
                List.of(result("blocked-fred", "fred"), result("ok-ecb", "ecb2")), 2, List.of());

        assertThat(result.statuses()).hasSize(2);
        Map<String, Object> fredStatus = statusFor(result, "blocked-fred");
        assertThat(fredStatus.get("preview_state"))
                .as("PR-7b: denied bulkhead admission must surface as its own distinct outcome - "
                        + "never a candidate that silently disappears from preview_verification, and never "
                        + "mislabeled as a plain \"timeout\" (see SearchV2PreviewOutcome.BULKHEAD_REJECTED)")
                .isEqualTo("bulkhead_rejected");
        assertThat(statusFor(result, "ok-ecb").get("ok"))
                .as("a different source's own bulkhead quota must be untouched by fred's saturation")
                .isEqualTo(true);
        verify(previewService, never())
                .previewAsyncIfSupported(argThat(payload -> "blocked-fred".equals(payload.get("set_id"))));
    }

    // ---- The permit tracks true in-flight time, not just dispatch time ---------------------------

    @Test
    void permitForTheAsyncPathIsHeldUntilTheRealResponseArrivesNotJustUntilDispatchReturns() throws Exception {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "5000"); // generous - the test controls completion itself
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CompletableFuture<Object> transport = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> response =
                transport.handle((v, ex) -> Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1))));
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.previewAsyncIfSupported(anyMap()))
                .thenReturn(Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(transport, response)));

        SearchV2PreviewBulkhead bulkhead = new SearchV2PreviewBulkhead();
        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService(), null, bulkhead);

        Thread caller = new Thread(() -> verifier.verifyTopOnly(List.of(result("in-flight-fred", "fred")), 1, List.of()));
        caller.start();

        long deadline = System.currentTimeMillis() + 5000;
        while (bulkhead.availablePermits("fred") > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(bulkhead.availablePermits("fred"))
                .as("dispatch has started but the async response has not arrived yet - the permit must still be held")
                .isEqualTo(0);
        assertThat(bulkhead.tryAdmit("fred", 50))
                .as("while the permit is held, a second fred attempt must be denied")
                .isEmpty();

        transport.complete(new Object()); // let the async response resolve
        caller.join(5000);

        deadline = System.currentTimeMillis() + 2000;
        while (bulkhead.availablePermits("fred") < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(bulkhead.availablePermits("fred"))
                .as("the permit must be released once the async response actually completes")
                .isEqualTo(1);
    }

    // ---- PR-7c: deterministic lifecycle - permit acquired just before the deadline never regresses ---

    @Test
    void permitAcquiredJustBeforeTheAdmissionDeadlineAlwaysContinuesAsDispatchedNeverLaterBulkheadRejected() throws Exception {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "600");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.previewAsyncIfSupported(anyMap())).thenReturn(Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(
                CompletableFuture.completedFuture(new Object()),
                CompletableFuture.completedFuture(Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1)))))));

        SearchV2PreviewBulkhead bulkhead = new SearchV2PreviewBulkhead();
        Optional<Runnable> heldPermit = bulkhead.tryAdmit("fred", 10);
        assertThat(heldPermit).isPresent();
        // Free the sole permit well within the 600ms admission budget, but late enough that this is a
        // genuinely close call, not an instant admit - proves the deterministic phase-CAS resolves this
        // correctly regardless of exactly how close to the deadline the permit shows up.
        CompletableFuture.delayedExecutor(400, TimeUnit.MILLISECONDS).execute(() -> heldPermit.get().run());

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService(), null, bulkhead);
        SearchV2PreviewVerifier.VerificationResult result =
                verifier.verifyTopOnly(List.of(result("late-admit-fred", "fred")), 1, List.of());

        assertThat(result.statuses().get(0).get("ok"))
                .as("a permit acquired before the admission deadline must let the candidate continue as "
                        + "dispatched and succeed - never later regress to bulkhead_rejected")
                .isEqualTo(true);
    }

    // ---- Validation phase (Fáze 5): explicit worst-case total duration ≈ admission + execution -----

    @Test
    void worstCaseTotalDurationApproachesAdmissionTimeoutPlusExecutionTimeoutWhenBothAreFullyConsumed() throws Exception {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "500"); // both admission and execution budget
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        // Transport that never completes on its own - once admitted, this candidate will consume the
        // full execution budget too (resolved only by the execution timer, not by a real response).
        CompletableFuture<Object> neverCompletingTransport = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> neverCompletingResponse =
                neverCompletingTransport.handle((v, ex) -> Map.of("ok", false, "preview_state", "error"));
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.previewAsyncIfSupported(anyMap()))
                .thenReturn(Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(neverCompletingTransport, neverCompletingResponse)));

        SearchV2PreviewBulkhead bulkhead = new SearchV2PreviewBulkhead();
        Optional<Runnable> heldPermit = bulkhead.tryAdmit("fred", 10);
        assertThat(heldPermit).isPresent();
        // Release the sole permit almost at the admission deadline, so admission alone consumes
        // nearly the full 500ms budget before this candidate is even allowed to start executing.
        CompletableFuture.delayedExecutor(450, TimeUnit.MILLISECONDS).execute(() -> heldPermit.get().run());

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService(), null, bulkhead);

        long startMs = System.currentTimeMillis();
        SearchV2PreviewVerifier.VerificationResult result =
                verifier.verifyTopOnly(List.of(result("worst-case-fred", "fred")), 1, List.of());
        long totalDurationMs = System.currentTimeMillis() - startMs;

        assertThat(result.statuses().get(0).get("preview_state"))
                .as("admitted just under the wire, then genuinely times out during execution - not bulkhead_rejected")
                .isEqualTo("timeout");
        System.out.printf(
                "Phase 5 worst-case: admission budget=500ms (consumed ~450ms) + execution budget=500ms "
                        + "(consumed ~500ms) -> measured total=%dms (expected ~ up to 2x500ms=1000ms)%n",
                totalDurationMs);
        assertThat(totalDurationMs)
                .as("total duration must be able to approach admission_timeout + execution_timeout (documented, "
                        + "accepted consequence of removing the timing-margin heuristic - see PR-7c report)")
                .isBetween(800L, 1400L);
    }

    // ---- PR-7c: concurrent contention over a small bulkhead never double-counts or crashes -----------

    @Test
    void concurrentContentionOverASmallBulkheadAlwaysProducesConsistentPerCandidateOutcomes() throws Exception {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_FRED", "2");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "600");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "8");

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.previewAsyncIfSupported(anyMap())).thenReturn(Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(
                CompletableFuture.completedFuture(new Object()),
                CompletableFuture.completedFuture(Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1)))))));

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        int candidateCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(candidateCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(candidateCount);
        AtomicInteger verifiedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        AtomicInteger otherCount = new AtomicInteger(0);
        try {
            for (int i = 0; i < candidateCount; i++) {
                String id = "contend-fred-" + i;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        SearchV2PreviewVerifier.VerificationResult result =
                                verifier.verifyTopOnly(List.of(result(id, "fred")), 1, List.of());
                        Map<String, Object> status = result.statuses().get(0);
                        if (Boolean.TRUE.equals(status.get("ok"))) {
                            verifiedCount.incrementAndGet();
                        } else if ("bulkhead_rejected".equals(status.get("preview_state"))) {
                            rejectedCount.incrementAndGet();
                        } else {
                            otherCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(doneGate.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(otherCount.get())
                .as("every candidate must resolve to either a real result or an explicit bulkhead_rejected - never anything ambiguous")
                .isZero();
        assertThat(verifiedCount.get() + rejectedCount.get()).isEqualTo(candidateCount);
        assertThat(verifiedCount.get()).as("at least the bulkhead's own limit of 2 must get through").isGreaterThanOrEqualTo(2);
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
