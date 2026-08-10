package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * PR-7c: unit tests of the deterministic lifecycle mechanism, independent of HTTP, the bulkhead, or
 * any executor pool. These are the fast, deterministic counterparts of the (slower,
 * thread-timing-based) integration tests in {@code SearchV2PreviewBulkheadIntegrationTest} and
 * {@code SearchV2PreviewCapacityOutcomeInteractionTest}.
 */
class PreviewRequestArbiterTest {

    private static final Map<String, Object> SUCCESS_VALUE = Map.of("ok", true, "rows", 3);
    private static final Map<String, Object> TIMEOUT_VALUE = Map.of("ok", false, "preview_state", "timeout");
    private static final Map<String, Object> FAILURE_VALUE = Map.of("ok", false, "preview_state", "error");
    private static final Map<String, Object> CANCELLED_VALUE = Map.of("ok", false, "preview_state", "cancelled");
    private static final Map<String, Object> BULKHEAD_REJECTED_VALUE = Map.of("ok", false, "preview_state", "bulkhead_rejected");

    // ---- Category 1: execution timeout of an actually-dispatched request ----------------------

    @Test
    void dispatchedRequestExecutionTimeoutCompletesPublicResultAndCancelsTheBoundTransport() {
        RecordingSink sink = new RecordingSink();
        PreviewRequestArbiter arbiter = new PreviewRequestArbiter(sink);
        assertThat(arbiter.admittedWithoutPermit()).isTrue();
        AtomicBoolean cancelCalled = new AtomicBoolean(false);
        boolean shouldRun = arbiter.bindTransport(() -> cancelCalled.set(true), "async_http");
        assertThat(shouldRun).isTrue();

        boolean won = arbiter.executionTimeout(TIMEOUT_VALUE);

        assertThat(won).isTrue();
        assertThat(arbiter.outcome()).isEqualTo(PreviewRequestArbiter.Outcome.TIMEOUT);
        assertThat(arbiter.phase()).isEqualTo(PreviewRequestArbiter.Phase.TERMINAL);
        assertThat(arbiter.publicResult()).isCompletedWithValue(TIMEOUT_VALUE);
        assertThat(cancelCalled).isTrue();
        assertThat(sink.cancellationAttempted.get()).isEqualTo(1);
        assertThat(sink.cancellationSucceeded.get()).isEqualTo(1);
        assertThat(sink.permitReleasedCount.get()).as("execution timeout must release the held permit exactly once").isEqualTo(1);
    }

    @Test
    void lateSuccessAfterExecutionTimeoutNeverOverwritesTheAlreadyReturnedTimeoutValue() {
        PreviewRequestArbiter arbiter = new PreviewRequestArbiter(RecordingSink.NO_OP_RECORDER);
        arbiter.admittedWithoutPermit();
        arbiter.bindTransport(() -> {}, "async_http");

        arbiter.executionTimeout(TIMEOUT_VALUE);
        arbiter.succeed(SUCCESS_VALUE); // arrives "late" - underlying work kept running past the timeout

        assertThat(arbiter.outcome()).isEqualTo(PreviewRequestArbiter.Outcome.TIMEOUT);
        assertThat(arbiter.publicResult()).isCompletedWithValue(TIMEOUT_VALUE);
    }

    // ---- Category 2: rejected for capacity before dispatch ever happens ------------------------

    @Test
    void rejectionForCapacityBeforeDispatchIsExplicitAndTearsDownAnyLateTransport() {
        RecordingSink sink = new RecordingSink();
        PreviewRequestArbiter arbiter = new PreviewRequestArbiter(sink);
        arbiter.beginAdmission();

        boolean won = arbiter.rejectForCapacity(BULKHEAD_REJECTED_VALUE);
        assertThat(won).isTrue();
        assertThat(arbiter.outcome()).isEqualTo(PreviewRequestArbiter.Outcome.BULKHEAD_REJECTED);
        assertThat(arbiter.publicResult()).isCompletedWithValue(BULKHEAD_REJECTED_VALUE);

        // The worker eventually gets its turn and tries to acquire/bind anyway.
        AtomicBoolean cancelCalled = new AtomicBoolean(false);
        boolean shouldRun = arbiter.bindTransport(() -> cancelCalled.set(true), "worker_thread");

        assertThat(shouldRun)
                .as("a request already rejected for capacity must be explicitly told not to run - "
                        + "never silently skipped without a trace")
                .isFalse();
        assertThat(cancelCalled).as("even a late-bound transport must still be torn down, not left dangling").isTrue();
        assertThat(arbiter.publicResult()).isCompletedWithValue(BULKHEAD_REJECTED_VALUE);
    }

    @Test
    void rejectForCapacityCanNeverWinOnceDispatchHasAlreadyHappened() {
        PreviewRequestArbiter arbiter = new PreviewRequestArbiter();
        arbiter.beginAdmission();
        assertThat(arbiter.admittedWithPermit(() -> {})).isTrue();
        assertThat(arbiter.phase()).isEqualTo(PreviewRequestArbiter.Phase.DISPATCHED);

        boolean won = arbiter.rejectForCapacity(BULKHEAD_REJECTED_VALUE);

        assertThat(won)
                .as("structural invariant: BULKHEAD_REJECTED must be impossible once dispatch already won")
                .isFalse();
        assertThat(arbiter.outcome()).isNull(); // still undecided - dispatched but not yet terminal
        assertThat(arbiter.phase()).isEqualTo(PreviewRequestArbiter.Phase.DISPATCHED);
    }

    @Test
    void executionTimeoutCanNeverWinWithoutHavingBeenDispatched() {
        PreviewRequestArbiter arbiter = new PreviewRequestArbiter();
        arbiter.beginAdmission();
        assertThat(arbiter.rejectForCapacity(BULKHEAD_REJECTED_VALUE)).isTrue();

        boolean won = arbiter.executionTimeout(TIMEOUT_VALUE);

        assertThat(won).as("structural invariant: TIMEOUT must be impossible unless the transport was actually dispatched").isFalse();
        assertThat(arbiter.outcome()).isEqualTo(PreviewRequestArbiter.Outcome.BULKHEAD_REJECTED);
        assertThat(arbiter.publicResult()).isCompletedWithValue(BULKHEAD_REJECTED_VALUE);
    }

    // ---- Category 6 (user's bulkhead-lifecycle list): permit acquired just before rejection ------

    @Test
    void permitAcquiredJustBeforeCapacityRejectionAlwaysContinuesAsDispatchedNeverLaterBulkheadRejected() {
        PreviewRequestArbiter arbiter = new PreviewRequestArbiter();
        arbiter.beginAdmission();

        assertThat(arbiter.admittedWithPermit(() -> {}))
                .as("permit acquired first - this must win regardless of how close the deadline was")
                .isTrue();
        boolean lateRejection = arbiter.rejectForCapacity(BULKHEAD_REJECTED_VALUE);

        assertThat(lateRejection).isFalse();
        assertThat(arbiter.phase()).isEqualTo(PreviewRequestArbiter.Phase.DISPATCHED);
        assertThat(arbiter.outcome())
                .as("must never retroactively become BULKHEAD_REJECTED after continuing as dispatched")
                .isNull();
    }

    // ---- Category 3: success before execution timeout -------------------------------------------

    @Test
    void successBeforeExecutionTimeoutWinsAndCancellationIsNeverAttempted() {
        RecordingSink sink = new RecordingSink();
        PreviewRequestArbiter arbiter = new PreviewRequestArbiter(sink);
        arbiter.admittedWithoutPermit();
        AtomicBoolean cancelCalled = new AtomicBoolean(false);
        arbiter.bindTransport(() -> cancelCalled.set(true), "async_http");

        arbiter.succeed(SUCCESS_VALUE);
        arbiter.executionTimeout(TIMEOUT_VALUE); // timeout action still fires later, e.g. scheduled regardless

        assertThat(arbiter.outcome()).isEqualTo(PreviewRequestArbiter.Outcome.SUCCESS);
        assertThat(arbiter.publicResult()).isCompletedWithValue(SUCCESS_VALUE);
        assertThat(cancelCalled).as("cancellation must never be attempted once success already won").isFalse();
        assertThat(sink.cancellationAttempted.get()).isZero();
        assertThat(sink.lateCompletionIgnored.get()).isEqualTo(1);
        assertThat(sink.permitReleasedCount.get()).isEqualTo(1);
    }

    // ---- Category 4: race success vs execution timeout -------------------------------------------

    @Test
    void raceBetweenSuccessAndExecutionTimeoutAlwaysProducesExactlyOneWinnerNeverBoth() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            int iterations = 500;
            for (int i = 0; i < iterations; i++) {
                RecordingSink sink = new RecordingSink();
                PreviewRequestArbiter arbiter = new PreviewRequestArbiter(sink);
                arbiter.admittedWithoutPermit();
                arbiter.bindTransport(() -> {}, "async_http");

                CountDownLatch startGate = new CountDownLatch(1);
                CountDownLatch doneGate = new CountDownLatch(2);
                pool.submit(() -> {
                    await(startGate);
                    arbiter.succeed(SUCCESS_VALUE);
                    doneGate.countDown();
                });
                pool.submit(() -> {
                    await(startGate);
                    arbiter.executionTimeout(TIMEOUT_VALUE);
                    doneGate.countDown();
                });
                startGate.countDown();
                assertThat(doneGate.await(5, TimeUnit.SECONDS)).as("iteration %d did not finish in time", i).isTrue();

                assertThat(arbiter.outcome()).isNotNull();
                assertThat(arbiter.publicResult()).isDone();
                Object value = arbiter.publicResult().get();
                assertThat(value).isIn(SUCCESS_VALUE, TIMEOUT_VALUE);
                assertThat(sink.completedCount.get()).as("iteration %d", i).isEqualTo(1);
                assertThat(sink.lateCompletionIgnored.get()).as("iteration %d", i).isEqualTo(1);
                assertThat(sink.permitReleasedCount.get()).as("permit released exactly once, iteration %d", i).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- Validation phase: cancellation vs success race (previously only tested sequentially) -----

    @Test
    void raceBetweenCancellationAndSuccessAlwaysProducesExactlyOneWinnerNeverBoth() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            int iterations = 500;
            for (int i = 0; i < iterations; i++) {
                RecordingSink sink = new RecordingSink();
                PreviewRequestArbiter arbiter = new PreviewRequestArbiter(sink);
                arbiter.admittedWithoutPermit();
                arbiter.bindTransport(() -> {}, "async_http");

                CountDownLatch startGate = new CountDownLatch(1);
                CountDownLatch doneGate = new CountDownLatch(2);
                pool.submit(() -> {
                    await(startGate);
                    arbiter.succeed(SUCCESS_VALUE);
                    doneGate.countDown();
                });
                pool.submit(() -> {
                    await(startGate);
                    arbiter.cancelIfPending(CANCELLED_VALUE);
                    doneGate.countDown();
                });
                startGate.countDown();
                assertThat(doneGate.await(5, TimeUnit.SECONDS)).as("iteration %d did not finish in time", i).isTrue();

                assertThat(arbiter.outcome()).isNotNull();
                Object value = arbiter.publicResult().get();
                assertThat(value).isIn(SUCCESS_VALUE, CANCELLED_VALUE);
                assertThat(sink.completedCount.get()).as("iteration %d", i).isEqualTo(1);
                assertThat(sink.lateCompletionIgnored.get()).as("iteration %d", i).isEqualTo(1);
                assertThat(sink.permitReleasedCount.get()).as("permit released exactly once, iteration %d", i).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- Category 7 (user's bulkhead-lifecycle list): admission race, 500 iterations -------------

    @Test
    void admissionRaceBetweenPermitAcquisitionAndCapacityRejectionAlwaysProducesExactlyOneConsistentOutcome() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            int iterations = 500;
            int admittedWins = 0;
            int rejectedWins = 0;
            for (int i = 0; i < iterations; i++) {
                PreviewRequestArbiter arbiter = new PreviewRequestArbiter();
                arbiter.beginAdmission();
                AtomicInteger releaseCount = new AtomicInteger(0);

                CountDownLatch startGate = new CountDownLatch(1);
                CountDownLatch doneGate = new CountDownLatch(2);
                AtomicBoolean admittedResult = new AtomicBoolean(false);
                AtomicBoolean rejectedResult = new AtomicBoolean(false);
                pool.submit(() -> {
                    await(startGate);
                    admittedResult.set(arbiter.admittedWithPermit(releaseCount::incrementAndGet));
                    doneGate.countDown();
                });
                pool.submit(() -> {
                    await(startGate);
                    rejectedResult.set(arbiter.rejectForCapacity(BULKHEAD_REJECTED_VALUE));
                    doneGate.countDown();
                });
                startGate.countDown();
                assertThat(doneGate.await(5, TimeUnit.SECONDS)).as("iteration %d did not finish in time", i).isTrue();

                assertThat(admittedResult.get() ^ rejectedResult.get())
                        .as("iteration %d: exactly one of admitted/rejected must win, never both, never neither", i)
                        .isTrue();
                if (admittedResult.get()) {
                    admittedWins++;
                    assertThat(arbiter.phase()).as("iteration %d", i).isEqualTo(PreviewRequestArbiter.Phase.DISPATCHED);
                    // Permit was bound to the (still non-terminal) arbiter - not yet released, since
                    // nothing terminal has happened yet for this specific race scenario.
                    assertThat(releaseCount.get()).as("iteration %d", i).isEqualTo(0);
                } else {
                    rejectedWins++;
                    assertThat(arbiter.outcome()).as("iteration %d", i).isEqualTo(PreviewRequestArbiter.Outcome.BULKHEAD_REJECTED);
                }
            }
            assertThat(admittedWins + rejectedWins).isEqualTo(iterations);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- Category 5: connector failure -----------------------------------------------------------

    @Test
    void connectorFailureProducesAFailureOutcomeDistinctFromTimeoutAndFreesTheAttempt() {
        RecordingSink sink = new RecordingSink();
        PreviewRequestArbiter arbiter = new PreviewRequestArbiter(sink);
        arbiter.admittedWithoutPermit();
        arbiter.bindTransport(() -> {}, "async_http");

        arbiter.fail(FAILURE_VALUE, "connection refused");

        assertThat(arbiter.outcome()).isEqualTo(PreviewRequestArbiter.Outcome.FAILURE);
        assertThat(arbiter.publicResult()).isCompletedWithValue(FAILURE_VALUE);
        assertThat(sink.transportFailureReason.get()).isEqualTo("connection refused");
        assertThat(sink.permitReleasedCount.get()).isEqualTo(1);
    }

    // ---- Category 6 (test list): cancellation propagation ----------------------------------------

    @Test
    void cancelIfPendingTearsDownAnUndecidedDispatchedAttempt() {
        RecordingSink sink = new RecordingSink();
        PreviewRequestArbiter arbiter = new PreviewRequestArbiter(sink);
        arbiter.admittedWithoutPermit();
        AtomicBoolean cancelCalled = new AtomicBoolean(false);
        arbiter.bindTransport(() -> cancelCalled.set(true), "async_http");

        arbiter.cancelIfPending(CANCELLED_VALUE);

        assertThat(arbiter.outcome()).isEqualTo(PreviewRequestArbiter.Outcome.CANCELLED);
        assertThat(arbiter.publicResult()).isCompletedWithValue(CANCELLED_VALUE);
        assertThat(cancelCalled).isTrue();
        assertThat(sink.permitReleasedCount.get()).isEqualTo(1);
    }

    @Test
    void cancelIfPendingAlsoTearsDownAnUndecidedPreDispatchAttempt() {
        PreviewRequestArbiter arbiter = new PreviewRequestArbiter();
        arbiter.beginAdmission();

        arbiter.cancelIfPending(CANCELLED_VALUE);

        assertThat(arbiter.outcome()).isEqualTo(PreviewRequestArbiter.Outcome.CANCELLED);
        assertThat(arbiter.rejectForCapacity(BULKHEAD_REJECTED_VALUE))
                .as("once cancelled, rejectForCapacity must not be able to win either")
                .isFalse();
    }

    @Test
    void cancelIfPendingIsANoOpOnceAlreadyDecided() {
        PreviewRequestArbiter arbiter = new PreviewRequestArbiter(RecordingSink.NO_OP_RECORDER);
        arbiter.admittedWithoutPermit();
        AtomicBoolean cancelCalled = new AtomicBoolean(false);
        arbiter.bindTransport(() -> cancelCalled.set(true), "async_http");
        arbiter.succeed(SUCCESS_VALUE);

        arbiter.cancelIfPending(CANCELLED_VALUE);

        assertThat(arbiter.outcome()).isEqualTo(PreviewRequestArbiter.Outcome.SUCCESS);
        assertThat(arbiter.publicResult()).isCompletedWithValue(SUCCESS_VALUE);
        assertThat(cancelCalled).as("a decided attempt must not be cancelled after the fact").isFalse();
    }

    // ---- Permit release exactly-once, across every terminal path ---------------------------------

    @Test
    void permitReleaseNeverFiresWhenRejectedForCapacitySinceNoRealPermitWasEverBound() {
        AtomicInteger releaseCount = new AtomicInteger(0);
        PreviewRequestArbiter arbiter = new PreviewRequestArbiter();
        arbiter.beginAdmission();
        // Note: admittedWithPermit(releaseCount::incrementAndGet) is deliberately never called - this
        // simulates the bulkhead's own wait never returning a permit at all before the deadline.

        arbiter.rejectForCapacity(BULKHEAD_REJECTED_VALUE);

        assertThat(releaseCount.get()).as("nothing was ever bound - there is nothing to release").isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Thread-safe recording {@link PreviewRequestArbiter.Sink} for assertions. */
    private static final class RecordingSink implements PreviewRequestArbiter.Sink {
        static final PreviewRequestArbiter.Sink NO_OP_RECORDER = new RecordingSink();

        final AtomicInteger cancellationAttempted = new AtomicInteger();
        final AtomicInteger cancellationSucceeded = new AtomicInteger();
        final AtomicInteger completedCount = new AtomicInteger();
        final AtomicInteger lateCompletionIgnored = new AtomicInteger();
        final AtomicInteger permitReleasedCount = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<String> transportFailureReason = new java.util.concurrent.atomic.AtomicReference<>();

        @Override
        public void completed(PreviewRequestArbiter.Outcome outcome, PreviewRequestArbiter.Phase phaseAtCompletion, long executionMs) {
            completedCount.incrementAndGet();
        }

        @Override
        public void cancellationAttempted() {
            cancellationAttempted.incrementAndGet();
        }

        @Override
        public void cancellationResult(boolean succeeded) {
            if (succeeded) {
                cancellationSucceeded.incrementAndGet();
            }
        }

        @Override
        public void lateCompletionIgnored(PreviewRequestArbiter.Outcome winningOutcome, PreviewRequestArbiter.Outcome lateOutcome) {
            lateCompletionIgnored.incrementAndGet();
        }

        @Override
        public void transportFailure(String reason) {
            transportFailureReason.set(reason);
        }

        @Override
        public void permitReleased() {
            permitReleasedCount.incrementAndGet();
        }
    }
}
