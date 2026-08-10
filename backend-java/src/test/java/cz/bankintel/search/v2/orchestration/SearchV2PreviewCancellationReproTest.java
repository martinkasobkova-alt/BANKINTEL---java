package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.bankintel.search.CatalogPreviewService;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SemanticDecision;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * PR-2 (investigative / regression-only): reproduces, or refutes, the suspected
 * "preview timeout does not cancel the underlying work" behaviour of
 * {@link SearchV2PreviewVerifier}.
 *
 * <p>Hypothesis under test: {@code CompletableFuture#completeOnTimeout(...)} only races a
 * fallback value onto the future the CALLER observes. It does not cancel or interrupt the
 * {@code supplyAsync} task backing that future, so:
 * <ul>
 *   <li>(a) the caller-visible {@code verify(...)}/{@code verifyTopOnly(...)} call returns
 *       around {@code previewTimeoutMs} even while the real preview call is still stuck;</li>
 *   <li>(b) the underlying simulated "slow HTTP call" is never interrupted and keeps running (it
 *       eventually finishes once released), well past the point the caller already got a timeout
 *       result;</li>
 *   <li>(c) because the verifier's {@code ExecutorService} is a small fixed-size pool shared by
 *       ALL preview requests (it is a single field on the {@code @Service} singleton), a slow
 *       candidate occupying the pool's only worker thread blocks a concurrent, otherwise-fast,
 *       candidate's underlying work from ever starting while the pool is occupied — even though
 *       that fast candidate's own caller also receives a prompt timeout response;</li>
 *   <li>(d) sharper than plain "starvation": once the fast candidate's own {@code
 *       previewTimeoutMs} elapses while it is still queued (never dequeued), {@code
 *       CompletableFuture}'s own {@code AsyncSupply.run()} guard ({@code if (d.result == null)})
 *       means its real preview call is <b>silently skipped forever</b>, not merely delayed — freeing
 *       the worker thread later (by releasing the slow candidate) does NOT make the fast candidate's
 *       real work run late. A task that had ALREADY started running before its timeout fired (the
 *       slow candidate here) is unaffected by that guard and keeps running uninterrupted instead,
 *       per (a)/(b) above. Which of these two very different failure modes a given candidate hits
 *       depends entirely on pool timing, not on anything the caller controls.
 * </ul>
 *
 * <p>This test uses a Mockito-mocked {@link CatalogPreviewService} to simulate a slow upstream
 * connector call (e.g. a blocking {@code HttpClient.send(...)} that never checks for interrupt).
 * It does not modify any production class.
 *
 * <p><b>Determinism note:</b> the "slow" preview call is gated by a {@link CountDownLatch} that
 * only the test releases, instead of a fixed {@code Thread.sleep(...)}. This dev machine showed
 * multi-hundred-ms to multi-second scheduling jitter between runs when using fixed sleeps (e.g. a
 * nominal 1500ms sleep was observed completing at both 1526ms and 2391ms in back-to-back runs),
 * which made timestamp-threshold assertions unreliable. Gating on a latch removes the guesswork:
 * the real preview call for "slow" literally cannot complete until the test says so, so there is
 * no race to lose - {@code previewTimeoutMs} (clamped to a 500ms floor) is the only clock running,
 * and every other assertion is state-based ("has this flag been set yet"), not time-based.
 */
class SearchV2PreviewCancellationReproTest {

    private static final long AWAIT_MS = 10_000;

    private SearchV2PreviewVerifier verifier;

    @AfterEach
    void cleanup() {
        System.clearProperty("SEARCH_PREVIEW_CONCURRENCY");
        System.clearProperty("SEARCH_PREVIEW_TIMEOUT_MS");
        if (verifier != null) {
            // Package-private lifecycle hook (normally @PreDestroy) - shuts the internal
            // executor down; harmless test-only cleanup, does not touch production code.
            verifier.shutdown();
        }
    }

    @Test
    void timeoutCompletesCallerFutureButUnderlyingWorkKeepsRunningUninterrupted() throws Exception {
        long previewTimeoutMs = 500; // clamp floor in SearchV2PreviewVerifier; cannot go lower
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", String.valueOf(previewTimeoutMs));

        CountDownLatch releaseGate = new CountDownLatch(1);
        AtomicBoolean workStarted = new AtomicBoolean(false);
        AtomicBoolean workFinished = new AtomicBoolean(false);
        AtomicBoolean workWasInterrupted = new AtomicBoolean(false);

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(any())).thenAnswer(invocation -> {
            workStarted.set(true);
            try {
                // Blocks indefinitely until the test explicitly releases it - the real preview
                // call literally cannot win a race against previewTimeoutMs, so there is nothing
                // timing-dependent left to assert about part (a).
                releaseGate.await();
            } catch (InterruptedException ie) {
                workWasInterrupted.set(true);
                Thread.currentThread().interrupt();
                throw ie;
            }
            workFinished.set(true);
            return okPreview();
        });

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        SearchV2PreviewVerifier.VerificationResult result =
                verifier.verifyTopOnly(List.of(result("slow-series")), 1, List.of());

        // (a) the caller gets a timeout answer while the real call is still blocked on the gate -
        // this is guaranteed by construction (the gate is never released before this point), not
        // by timing luck.
        assertThat(result.statuses()).hasSize(1);
        Map<String, Object> status = result.statuses().get(0);
        assertThat(status.get("preview_state")).isEqualTo("timeout");
        assertThat(status.get("reason")).isEqualTo("preview_timeout_ms=" + previewTimeoutMs);
        assertThat(result.accepted()).isEmpty();
        assertThat(workStarted.get()).as("underlying task did start running on the pool").isTrue();
        assertThat(workFinished.get()).as("underlying task cannot have finished - gate is still closed").isFalse();

        // (b) release the gate and confirm the underlying task resumes and completes normally -
        // i.e. completeOnTimeout never interrupted/cancelled it while it sat blocked past the
        // preview timeout.
        releaseGate.countDown();
        assertThat(awaitTrue(workFinished::get, AWAIT_MS))
                .as("underlying task should complete once released, proving it was never cancelled")
                .isTrue();
        assertThat(workWasInterrupted.get())
                .as("the worker thread must never have been interrupted while blocked past the timeout")
                .isFalse();
    }

    @Test
    void singleWorkerPoolStarvesConcurrentFastRequestBehindSlowOne() throws Exception {
        long previewTimeoutMs = 500; // clamp floor in SearchV2PreviewVerifier; cannot go lower
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "1");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", String.valueOf(previewTimeoutMs));

        CountDownLatch slowGate = new CountDownLatch(1);
        AtomicBoolean slowStarted = new AtomicBoolean(false);
        AtomicBoolean slowFinished = new AtomicBoolean(false);
        AtomicBoolean fastStarted = new AtomicBoolean(false);
        AtomicBoolean fastFinished = new AtomicBoolean(false);

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(any())).thenAnswer(invocation -> {
            Map<String, Object> payload = invocation.getArgument(0);
            String id = String.valueOf(payload.get("set_id"));
            if ("slow1".equals(id)) {
                slowStarted.set(true);
                slowGate.await(); // held open by the test until it has proven starvation
                slowFinished.set(true);
            } else {
                // "fast" candidate: if its supplier body ever actually runs, it finishes instantly -
                // no artificial sleep needed, which keeps this assertion purely state-based.
                fastStarted.set(true);
                fastFinished.set(true);
            }
            return okPreview();
        });

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        AtomicReference<Map<String, Object>> slowStatusHolder = new AtomicReference<>();
        AtomicReference<Map<String, Object>> fastStatusHolder = new AtomicReference<>();
        Thread slowCaller = new Thread(
                () -> slowStatusHolder.set(
                        verifier.verifyTopOnly(List.of(result("slow1")), 1, List.of()).statuses().get(0)),
                "repro-slow-caller");
        Thread fastCaller = new Thread(
                () -> fastStatusHolder.set(
                        verifier.verifyTopOnly(List.of(result("fast1")), 1, List.of()).statuses().get(0)),
                "repro-fast-caller");

        slowCaller.start();
        // Wait for the slow task to actually claim the pool's sole worker thread (state-based,
        // not a fixed sleep) before submitting the fast one.
        assertThat(awaitTrue(slowStarted::get, AWAIT_MS))
                .as("slow candidate's underlying task should start running on the sole worker thread")
                .isTrue();

        fastCaller.start();

        slowCaller.join(AWAIT_MS);
        fastCaller.join(AWAIT_MS);
        assertThat(slowCaller.isAlive()).isFalse();
        assertThat(fastCaller.isAlive()).isFalse();

        // Both callers get a "timeout" verdict, not "ok".
        assertThat(slowStatusHolder.get().get("preview_state"))
                .as("slow candidate must be reported as timed out (its real call is still gated)")
                .isEqualTo("timeout");
        assertThat(fastStatusHolder.get().get("preview_state"))
                .as("fast candidate also times out even though its own real work is instantaneous")
                .isEqualTo("timeout");

        // (c) pool starvation, direct proof: the fast candidate's supplier body never even started
        // running while the sole worker thread was occupied by the (still-gated) slow candidate -
        // even though both of their CALLERS already received a response.
        assertThat(fastStarted.get())
                .as("fast candidate's underlying task must NOT have started yet - the pool's only"
                        + " worker thread is still occupied by the slow candidate")
                .isFalse();

        // Release the slow task and confirm its own underlying work completes normally.
        slowGate.countDown();
        assertThat(awaitTrue(slowFinished::get, AWAIT_MS))
                .as("slow candidate's underlying task should complete once released")
                .isTrue();

        // (d) THE SHARPER FINDING: this is NOT plain "starvation" (delayed-but-eventually-served).
        // Give the freed worker thread ample time to pick up the fast task from the queue, then
        // assert it NEVER runs at all - not "runs late".
        //
        // Root cause (JDK-level, not this codebase): CompletableFuture.supplyAsync(...)'s queued
        // unit of work is an AsyncSupply whose run() method starts with "if (d.result == null)"
        // before invoking the supplier. Once completeOnTimeout races the dependent future to
        // completion (which it already did here, at previewTimeoutMs, while this task was still
        // sitting in the queue - never dequeued), that guard is false by the time a worker thread
        // finally reaches it, so run() returns without ever calling the supplier. The real preview
        // call for a candidate that timed out while still queued is silently skipped forever - not
        // delayed. A candidate that had ALREADY started running when the timeout fired (the slow
        // candidate in this test, and in test 1 above) is unaffected by this guard and keeps
        // running uninterrupted to completion instead.
        Thread.sleep(1_000); // ample time for the freed worker to reach the fast task, if it ever would
        assertThat(fastStarted.get())
                .as("the fast candidate's real preview call is silently SKIPPED, not merely delayed: "
                        + "CompletableFuture's own AsyncSupply.run() no-ops once completeOnTimeout has "
                        + "already completed the future for a task that never started executing")
                .isFalse();
        assertThat(fastFinished.get()).isFalse();
    }

    /** Polls {@code condition} until it is true or {@code maxWaitMs} elapses. */
    private static boolean awaitTrue(BooleanSupplier condition, long maxWaitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    private static Map<String, Object> okPreview() {
        return Map.of(
                "preview_state", "ok",
                "query_params", Map.of("geo", "DE"),
                "rows", List.of(Map.of("value", 1)));
    }

    private static SearchResult result(String id) {
        return new SearchResult(candidate(id), decision(id), 0);
    }

    private static SearchCandidate candidate(String id) {
        return new SearchCandidate(
                "fred:" + id,
                id,
                "Title " + id,
                "",
                "fred",
                "",
                "",
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                "",
                1,
                "q",
                List.of(),
                Map.of());
    }

    private static SemanticDecision decision(String id) {
        return new SemanticDecision(id, "keep", 0.9, 0.9, List.of(), List.of(), "ok", "primary");
    }
}
