package cz.bankintel.search.v2.orchestration;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PR-7c: arbitrates the single terminal outcome of one preview verification attempt via an explicit,
 * deterministic lifecycle - {@link Phase#CREATED} → {@link Phase#WAITING_FOR_CAPACITY} →
 * {@link Phase#DISPATCHED} → {@link Phase#TERMINAL} - instead of the PR-7b timing-margin heuristic
 * (racing two independently-scheduled timers and biasing which one wins by a few milliseconds).
 *
 * <p>Every transition - including the five distinct terminal ones - is a single compare-and-set on
 * one {@link #state} reference. This is deliberate: an earlier version of this class tracked "which
 * phase" and "what was decided" as two separate atomics, updated one after the other, which opened a
 * genuine (if narrow) window where a concurrent reader could observe the phase as already TERMINAL
 * while the outcome had not been recorded yet. Collapsing both into one CAS-guarded value removes
 * that window entirely: winning the transition and recording the outcome are the same atomic step,
 * exactly like the original (PR-6) single-{@code decided}-reference design, just extended with the
 * two pre-dispatch phases.
 *
 * <p>The critical property this collapsed state enforces, purely through compare-and-set (never
 * through comparing clock times): a request can only ever be decided {@link State#BULKHEAD_REJECTED}
 * while its state is still {@link State#CREATED}/{@link State#WAITING_FOR_CAPACITY} - the moment
 * {@link #admittedWithPermit}/{@link #admittedWithoutPermit} wins its own CAS into
 * {@link State#DISPATCHED}, {@link #rejectForCapacity} can never win again, no matter how the two
 * calls are scheduled relative to each other. Symmetrically, {@link #executionTimeout} can only ever
 * win while the state is {@link State#DISPATCHED} - a request that never got that far cannot become
 * {@link State#TIMEOUT}. There is no margin anywhere in this class: correctness comes from what state
 * the request is actually, verifiably in, not from which of two clocks fires first.
 *
 * <p>This class also owns the bulkhead permit's release ({@link #releasePermitOnce}), guaranteed to
 * run exactly once per attempt regardless of which terminal outcome wins - see
 * {@link #admittedWithPermit} for why binding the release and winning the state transition must be
 * one atomic step, not two.
 *
 * <p>Not thread-confined: {@link #rejectForCapacity}, {@link #executionTimeout}, {@link #succeed},
 * {@link #fail}, and {@link #cancelIfPending} may all be invoked concurrently from different threads
 * (the admission timer, the execution timer, the transport's own completion callback, the worker
 * thread). Exactly one ever wins the terminal transition; the others are safely ignored (but reported
 * via {@link Sink#lateCompletionIgnored}).
 */
final class PreviewRequestArbiter {

    /** Coarse, telemetry/test-facing view of {@link State} - every {@code State} maps to exactly one of these. */
    enum Phase {
        CREATED,
        WAITING_FOR_CAPACITY,
        DISPATCHED,
        TERMINAL
    }

    enum Outcome {
        SUCCESS,
        TIMEOUT,
        CANCELLED,
        FAILURE,
        BULKHEAD_REJECTED
    }

    /**
     * The single source of truth. {@code CREATED}/{@code WAITING_FOR_CAPACITY}/{@code DISPATCHED} are
     * the three non-terminal states; the five {@code *_DECIDED}-shaped values below them are the only
     * terminal states, one per {@link Outcome} - see {@link #phaseOf} and {@link #outcomeOf} for the
     * (trivial) mapping back to the public {@link Phase}/{@link Outcome} enums.
     */
    private enum State {
        CREATED,
        WAITING_FOR_CAPACITY,
        DISPATCHED,
        BULKHEAD_REJECTED,
        TIMEOUT,
        CANCELLED,
        SUCCESS,
        FAILURE
    }

    /** Abstracts "how to attempt cancellation of whatever underlying transport is bound". */
    @FunctionalInterface
    interface Cancellable {
        /** Best-effort. Must not throw for a merely-already-completed/cancelled transport. */
        void cancel();
    }

    /** Minimal telemetry seam. Every method is a no-op by default so tests/call sites can opt in selectively. */
    interface Sink {
        default void admissionWaitStarted() {}

        default void admissionAcquired() {}

        default void admissionRejected() {}

        default void transportDispatched(String transportType) {}

        default void executionTimedOut() {}

        default void completed(Outcome outcome, Phase phaseAtCompletion, long executionMs) {}

        default void cancellationAttempted() {}

        default void cancellationResult(boolean succeeded) {}

        default void lateCompletionIgnored(Outcome winningOutcome, Outcome lateOutcome) {}

        default void transportFailure(String reason) {}

        default void permitReleased() {}

        Sink NO_OP = new Sink() {};
    }

    private static final Runnable NO_OP_RELEASE = () -> {};

    private final CompletableFuture<Map<String, Object>> publicResult = new CompletableFuture<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.CREATED);
    private final AtomicReference<Cancellable> transport = new AtomicReference<>();
    private final AtomicReference<Runnable> permitRelease = new AtomicReference<>(NO_OP_RELEASE);
    private final AtomicBoolean permitReleased = new AtomicBoolean(false);
    private final Sink sink;
    private final long createdAtNanos = System.nanoTime();

    PreviewRequestArbiter() {
        this(Sink.NO_OP);
    }

    PreviewRequestArbiter(Sink sink) {
        this.sink = sink == null ? Sink.NO_OP : sink;
    }

    CompletableFuture<Map<String, Object>> publicResult() {
        return publicResult;
    }

    Phase phase() {
        return phaseOf(state.get());
    }

    /** {@code null} until a terminal decision has been made. */
    Outcome outcome() {
        return outcomeOf(state.get());
    }

    long elapsedMsSinceCreated() {
        return (System.nanoTime() - createdAtNanos) / 1_000_000L;
    }

    /** CREATED -> WAITING_FOR_CAPACITY. Called once, synchronously, only when the bulkhead is enabled. */
    void beginAdmission() {
        if (state.compareAndSet(State.CREATED, State.WAITING_FOR_CAPACITY)) {
            sink.admissionWaitStarted();
        }
    }

    /**
     * CREATED -> DISPATCHED directly, for when the bulkhead is disabled - there is no admission phase
     * to wait through at all, matching pre-PR-8 behavior exactly.
     */
    boolean admittedWithoutPermit() {
        boolean moved = state.compareAndSet(State.CREATED, State.DISPATCHED);
        if (moved) {
            sink.admissionAcquired();
        }
        return moved;
    }

    /**
     * Attempts to move from CREATED/WAITING_FOR_CAPACITY to DISPATCHED now that a real bulkhead
     * permit has been acquired. Binding {@code permitRelease} and winning the state transition are
     * one atomic step (the binding happens ONLY inside the winning CAS branch) so that there is never
     * a window where {@link #rejectForCapacity} could win the race yet find the real permit already
     * bound (which would either double-release or, worse, silently drop the release) - see the class
     * javadoc.
     *
     * @return {@code true} if the arbiter now owns {@code permitRelease} and will call it exactly
     *     once on its own eventual terminal transition - the caller must NOT release it itself.
     *     {@code false} if this attempt was already decided (typically: the admission timer already
     *     rejected it, or it was cancelled) - the caller must release the permit itself immediately,
     *     since the arbiter never took ownership of it.
     */
    boolean admittedWithPermit(Runnable acquiredPermitRelease) {
        while (true) {
            State current = state.get();
            if (current != State.CREATED && current != State.WAITING_FOR_CAPACITY) {
                return false;
            }
            if (state.compareAndSet(current, State.DISPATCHED)) {
                permitRelease.set(acquiredPermitRelease == null ? NO_OP_RELEASE : acquiredPermitRelease);
                sink.admissionAcquired();
                return true;
            }
        }
    }

    /**
     * Terminal: the admission timer fired while still CREATED/WAITING_FOR_CAPACITY. Structurally
     * impossible once {@link #admittedWithPermit}/{@link #admittedWithoutPermit} has already won -
     * this method's own CAS simply cannot succeed anymore at that point, regardless of timing.
     */
    boolean rejectForCapacity(Map<String, Object> value) {
        while (true) {
            State current = state.get();
            if (current != State.CREATED && current != State.WAITING_FOR_CAPACITY) {
                return false;
            }
            if (state.compareAndSet(current, State.BULKHEAD_REJECTED)) {
                sink.admissionRejected();
                publicResult.complete(value);
                sink.completed(Outcome.BULKHEAD_REJECTED, phaseOf(current), elapsedMsSinceCreated());
                releasePermitOnce();
                return true;
            }
        }
    }

    /**
     * Registers the transport for cancellation and confirms the attempt is still live. Always called
     * after {@code admittedWithPermit}/{@code admittedWithoutPermit} has already moved the state to
     * DISPATCHED, so this itself is not a state transition - it just refuses (and cancels immediately)
     * in the narrow case where a terminal decision (e.g. the execution timeout, or an explicit
     * cancellation) already won in the brief window before this call.
     */
    boolean bindTransport(Cancellable cancellable, String transportType) {
        transport.set(cancellable);
        if (isTerminal(state.get())) {
            attemptCancel(cancellable);
            return false;
        }
        sink.transportDispatched(transportType);
        return true;
    }

    /** Terminal: the execution timer fired while still DISPATCHED. Impossible unless dispatch actually happened. */
    boolean executionTimeout(Map<String, Object> value) {
        if (!state.compareAndSet(State.DISPATCHED, State.TIMEOUT)) {
            // Two distinct reasons this CAS can fail: (a) something else already decided first (a
            // genuine late completion - report it), or (b) this was never dispatched at all (the
            // structural invariant case - nothing "late" to report).
            State current = state.get();
            if (isTerminal(current)) {
                sink.lateCompletionIgnored(outcomeOf(current), Outcome.TIMEOUT);
            }
            return false;
        }
        sink.executionTimedOut();
        Cancellable bound = transport.get();
        if (bound != null) {
            attemptCancel(bound);
        }
        publicResult.complete(value);
        sink.completed(Outcome.TIMEOUT, Phase.DISPATCHED, elapsedMsSinceCreated());
        releasePermitOnce();
        return true;
    }

    void succeed(Map<String, Object> value) {
        terminalTransition(State.SUCCESS, Outcome.SUCCESS, () -> {
            publicResult.complete(value);
        });
    }

    void fail(Map<String, Object> value, String reason) {
        terminalTransition(State.FAILURE, Outcome.FAILURE, () -> {
            sink.transportFailure(reason);
            publicResult.complete(value);
        });
    }

    /** Explicit cancellation, from any non-terminal state (before or after dispatch). */
    void cancelIfPending(Map<String, Object> cancelledValue) {
        terminalTransition(State.CANCELLED, Outcome.CANCELLED, () -> {
            Cancellable bound = transport.get();
            if (bound != null) {
                attemptCancel(bound);
            }
            publicResult.complete(cancelledValue);
        });
    }

    /**
     * Shared CAS loop for the three terminal transitions that may win from any non-terminal state
     * (unlike {@link #rejectForCapacity}, scoped to pre-dispatch only, and {@link #executionTimeout},
     * scoped to DISPATCHED only). {@code onWin} runs only after the state transition has already won,
     * so {@code publicResult.complete(...)} and the outcome recorded by {@link #outcome()} can never
     * be observed out of step with each other.
     */
    private void terminalTransition(State target, Outcome outcome, Runnable onWin) {
        while (true) {
            State current = state.get();
            if (isTerminal(current)) {
                sink.lateCompletionIgnored(outcomeOf(current), outcome);
                return;
            }
            if (state.compareAndSet(current, target)) {
                onWin.run();
                sink.completed(outcome, phaseOf(current), elapsedMsSinceCreated());
                releasePermitOnce();
                return;
            }
        }
    }

    private static boolean isTerminal(State s) {
        return s == State.BULKHEAD_REJECTED
                || s == State.TIMEOUT
                || s == State.CANCELLED
                || s == State.SUCCESS
                || s == State.FAILURE;
    }

    private static Phase phaseOf(State s) {
        return switch (s) {
            case CREATED -> Phase.CREATED;
            case WAITING_FOR_CAPACITY -> Phase.WAITING_FOR_CAPACITY;
            case DISPATCHED -> Phase.DISPATCHED;
            default -> Phase.TERMINAL;
        };
    }

    private static Outcome outcomeOf(State s) {
        return switch (s) {
            case BULKHEAD_REJECTED -> Outcome.BULKHEAD_REJECTED;
            case TIMEOUT -> Outcome.TIMEOUT;
            case CANCELLED -> Outcome.CANCELLED;
            case SUCCESS -> Outcome.SUCCESS;
            case FAILURE -> Outcome.FAILURE;
            default -> null;
        };
    }

    /** Guaranteed exactly-once, regardless of which terminal transition calls it or how many times. */
    private void releasePermitOnce() {
        if (permitReleased.compareAndSet(false, true)) {
            permitRelease.get().run();
            sink.permitReleased();
        }
    }

    private void attemptCancel(Cancellable cancellable) {
        if (cancellable == null) {
            return;
        }
        sink.cancellationAttempted();
        try {
            cancellable.cancel();
            sink.cancellationResult(true);
        } catch (Exception ex) {
            sink.cancellationResult(false);
        }
    }
}
