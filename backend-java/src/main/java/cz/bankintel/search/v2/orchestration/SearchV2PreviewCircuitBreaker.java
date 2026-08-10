package cz.bankintel.search.v2.orchestration;

import cz.bankintel.search.v2.observability.SearchV2TelemetryWriter;
import cz.bankintel.util.BankIntelEnvVars;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * PR-10: per-connector circuit breaker for preview verification dispatch.
 *
 * <p>One independent breaker per source (never a single global breaker, and never shared state
 * between two different sources - each gets its own {@link Breaker} instance, mirroring
 * {@link SearchV2PreviewBulkhead}'s per-source isolation). Three states:
 *
 * <ul>
 *   <li>{@link State#CLOSED} - normal operation; requests pass through and their outcome is
 *       recorded via {@link #recordSuccess} / {@link #recordFailure}.
 *   <li>{@link State#OPEN} - the source has failed too many times in a row; requests are rejected
 *       immediately ({@link #allowRequest} returns {@code false}, no connector call is attempted at
 *       all) until its configured cooldown period has elapsed.
 *   <li>{@link State#HALF_OPEN} - once the cooldown elapses, exactly one trial request is allowed
 *       through per source at a time; every other concurrent caller is still rejected until that
 *       trial resolves. A successful trial closes the breaker (resets the failure count); a failed
 *       trial reopens it and restarts the cooldown timer.
 * </ul>
 *
 * <p>"Cooldown" (the fourth state named in the PR-10 plan) is modeled as OPEN's own configurable
 * exit timer rather than a fourth enum value - this is the standard circuit breaker shape (Hystrix,
 * resilience4j, Polly all model it this way): there is no observably distinct state between "open,
 * still waiting" and "open, cooldown elapsed, about to try again" other than the timer itself, which
 * {@link #allowRequest} already evaluates on every call.
 *
 * <p>Disabled by default via {@code SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED}; when disabled,
 * {@link #allowRequest} always returns {@code true} and {@link #recordSuccess}/{@link #recordFailure}
 * are no-ops, so behavior is byte-for-byte identical to before this PR.
 *
 * <p>Never applies to {@code file_upload} (the {@code FileUploadConnector}'s source type) - that
 * connector reads a locally-uploaded file from disk, never makes a network call, and is not even
 * reachable as a {@code SearchCandidate.source()} in this pipeline (it is not a member of
 * {@code SearchV2QueryPlanner.ALL_SEARCH_V2_SOURCES}) - but the exclusion below is enforced
 * explicitly rather than relying on that structural fact alone.
 */
@Service
public class SearchV2PreviewCircuitBreaker {

    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private static final String EXCLUDED_SOURCE = "file_upload";
    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final long DEFAULT_COOLDOWN_MS = 30_000;

    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();
    private final SearchV2TelemetryWriter telemetryWriter;

    /** Legacy no-arg constructor for direct-construction tests; telemetry is a no-op through this path. */
    public SearchV2PreviewCircuitBreaker() {
        this(null);
    }

    @Autowired
    public SearchV2PreviewCircuitBreaker(SearchV2TelemetryWriter telemetryWriter) {
        this.telemetryWriter = telemetryWriter;
    }

    static boolean enabled() {
        return BankIntelEnvVars.isTruthy("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED");
    }

    /** Whether a request for {@code source} may proceed right now. Never throws. */
    boolean allowRequest(String source) {
        String key = normalize(source);
        if (!enabled() || isExcluded(key)) {
            return true;
        }
        Breaker breaker = breakerFor(key);
        State current = breaker.state.get();

        if (current == State.CLOSED) {
            return true;
        }

        if (current == State.OPEN) {
            long elapsedMs = System.currentTimeMillis() - breaker.openedAtMs.get();
            if (elapsedMs < cooldownMsFor(key)) {
                emit(key, "rejected_open", current, Map.of("elapsed_ms", elapsedMs));
                return false;
            }
            if (breaker.state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                breaker.halfOpenTrialClaimed.set(true);
                emit(key, "half_open", State.HALF_OPEN, Map.of());
                return true;
            }
            current = breaker.state.get(); // lost the CAS race - someone else already flipped it
        }

        if (current == State.HALF_OPEN) {
            boolean claimed = breaker.halfOpenTrialClaimed.compareAndSet(false, true);
            if (!claimed) {
                emit(key, "rejected_half_open_busy", current, Map.of());
            }
            return claimed;
        }

        return true;
    }

    /** Records a successful outcome. Closes the breaker (resets the failure count) if it was open or half-open. */
    void recordSuccess(String source) {
        String key = normalize(source);
        if (!enabled() || isExcluded(key)) {
            return;
        }
        Breaker breaker = breakerFor(key);
        State previous = breaker.state.getAndSet(State.CLOSED);
        breaker.consecutiveFailures.set(0);
        breaker.halfOpenTrialClaimed.set(false);
        if (previous != State.CLOSED) {
            emit(key, "closed", State.CLOSED, Map.of("previous_state", previous.name()));
        }
    }

    /**
     * Records a failed outcome. A failed half-open trial reopens immediately and restarts the
     * cooldown timer; otherwise a closed breaker opens once {@code failureThresholdFor(source)}
     * consecutive failures accumulate.
     */
    void recordFailure(String source) {
        String key = normalize(source);
        if (!enabled() || isExcluded(key)) {
            return;
        }
        Breaker breaker = breakerFor(key);
        if (breaker.state.get() == State.HALF_OPEN) {
            breaker.state.set(State.OPEN);
            breaker.openedAtMs.set(System.currentTimeMillis());
            breaker.halfOpenTrialClaimed.set(false);
            emit(key, "reopened_after_failed_trial", State.OPEN, Map.of());
            return;
        }
        int failures = breaker.consecutiveFailures.incrementAndGet();
        if (failures >= failureThresholdFor(key) && breaker.state.compareAndSet(State.CLOSED, State.OPEN)) {
            breaker.openedAtMs.set(System.currentTimeMillis());
            emit(key, "opened", State.OPEN, Map.of("consecutive_failures", failures));
        }
    }

    State stateOf(String source) {
        Breaker breaker = breakers.get(normalize(source));
        return breaker == null ? State.CLOSED : breaker.state.get();
    }

    private Breaker breakerFor(String source) {
        return breakers.computeIfAbsent(source, ignored -> new Breaker());
    }

    private static boolean isExcluded(String normalizedSource) {
        return EXCLUDED_SOURCE.equals(normalizedSource);
    }

    private static String normalize(String source) {
        return source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
    }

    private static int failureThresholdFor(String source) {
        int fromEnv = parseIntEnv("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_" + source.toUpperCase(Locale.ROOT), -1);
        if (fromEnv > 0) {
            return fromEnv;
        }
        return Math.max(1, parseIntEnv("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD", DEFAULT_FAILURE_THRESHOLD));
    }

    private static long cooldownMsFor(String source) {
        long fromEnv = parseLongEnv("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS_" + source.toUpperCase(Locale.ROOT), -1);
        if (fromEnv > 0) {
            return fromEnv;
        }
        return Math.max(0, parseLongEnv("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS", DEFAULT_COOLDOWN_MS));
    }

    private void emit(String source, String phase, State state, Map<String, Object> extra) {
        if (telemetryWriter == null || !telemetryWriter.enabled()) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schema_version", "1");
        event.put("timestamp_ms", System.currentTimeMillis());
        event.put("event_type", "preview_circuit_breaker");
        event.put("phase", phase);
        event.put("source", source);
        event.put("state", state.name());
        event.putAll(extra);
        telemetryWriter.submitRaw(event);
    }

    private static int parseIntEnv(String name, int fallback) {
        String raw = BankIntelEnvVars.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long parseLongEnv(String name, long fallback) {
        String raw = BankIntelEnvVars.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static final class Breaker {
        final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        final AtomicLong openedAtMs = new AtomicLong(0);
        final AtomicBoolean halfOpenTrialClaimed = new AtomicBoolean(false);
    }
}
