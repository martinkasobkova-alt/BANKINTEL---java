package cz.bankintel.search.v2.orchestration;

import cz.bankintel.search.v2.observability.SearchV2TelemetryWriter;
import cz.bankintel.util.BankIntelEnvVars;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * PR-8: per-source admission control (the bulkhead pattern) for preview verification dispatch.
 *
 * <p>Before PR-8, the only concurrency control in {@link SearchV2PreviewVerifier} was one shared
 * {@code SEARCH_PREVIEW_CONCURRENCY}-sized worker pool, used identically for every source. A single
 * slow or oversubscribed source (e.g. many candidates from the same connector landing in one batch)
 * could occupy an unbounded share of that shared capacity, starving candidates from other sources in
 * the same batch.
 *
 * <p>This class adds a second, orthogonal admission gate: one {@link Semaphore} per source, each with
 * its own configurable permit count. A source can never have more than its own configured number of
 * preview attempts in flight at once, regardless of what other sources are doing. It deliberately does
 * NOT change the underlying worker pool size, timeouts, retry behavior, or circuit breaking — those are
 * separate PRs in the Search V2.1 plan.
 *
 * <p>Disabled by default via {@code SEARCH_PREVIEW_BULKHEAD_ENABLED}; when disabled, {@link #tryAdmit}
 * always grants admission immediately with a no-op release, so behavior is byte-for-byte identical to
 * before this PR.
 */
@Service
public class SearchV2PreviewBulkhead {

    private static final int DEFAULT_PERMITS = 3;
    private static final Runnable NO_OP_RELEASE = () -> {};

    private final Map<String, Semaphore> semaphores = new ConcurrentHashMap<>();
    private final SearchV2TelemetryWriter telemetryWriter;

    /** Legacy no-arg constructor for direct-construction tests; telemetry is a no-op through this path. */
    public SearchV2PreviewBulkhead() {
        this(null);
    }

    @Autowired
    public SearchV2PreviewBulkhead(SearchV2TelemetryWriter telemetryWriter) {
        this.telemetryWriter = telemetryWriter;
    }

    static boolean enabled() {
        return BankIntelEnvVars.isTruthy("SEARCH_PREVIEW_BULKHEAD_ENABLED");
    }

    /**
     * Attempts to admit one preview attempt for {@code source}, waiting up to {@code timeoutMs} for a
     * free permit (the same budget already used as the overall preview timeout - this reuses that
     * existing value rather than introducing a new timeout concept). Returns a release callback if
     * admitted; empty if the bulkhead stayed saturated for the whole wait - in that case the caller
     * must do no real work at all and simply return, since the independently scheduled
     * {@link PreviewRequestArbiter} timeout will complete the result around the same time.
     *
     * <p>The returned release callback must be invoked exactly once per successful admission,
     * regardless of how the attempt ultimately ends (success, failure, timeout, or cancellation).
     */
    Optional<Runnable> tryAdmit(String source, long timeoutMs) {
        if (!enabled()) {
            return Optional.of(NO_OP_RELEASE);
        }
        String key = normalize(source);
        Semaphore semaphore = semaphores.computeIfAbsent(key, SearchV2PreviewBulkhead::newSemaphoreFor);
        long waitStart = System.currentTimeMillis();
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        long waitMs = System.currentTimeMillis() - waitStart;
        if (!acquired) {
            emit("denied", key, waitMs, semaphore);
            return Optional.empty();
        }
        emit("admitted", key, waitMs, semaphore);
        return Optional.of(() -> {
            semaphore.release();
            emit("released", key, 0, semaphore);
        });
    }

    int configuredLimit(String source) {
        return limitFor(normalize(source));
    }

    int availablePermits(String source) {
        Semaphore semaphore = semaphores.get(normalize(source));
        return semaphore == null ? limitFor(normalize(source)) : semaphore.availablePermits();
    }

    private static Semaphore newSemaphoreFor(String source) {
        return new Semaphore(limitFor(source), true);
    }

    private static int limitFor(String source) {
        int fromEnv = parseIntEnv("SEARCH_PREVIEW_BULKHEAD_LIMIT_" + source.toUpperCase(Locale.ROOT), -1);
        if (fromEnv > 0) {
            return fromEnv;
        }
        return Math.max(1, parseIntEnv("SEARCH_PREVIEW_BULKHEAD_DEFAULT_LIMIT", DEFAULT_PERMITS));
    }

    private static String normalize(String source) {
        return source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
    }

    private void emit(String phase, String source, long waitMs, Semaphore semaphore) {
        if (telemetryWriter == null || !telemetryWriter.enabled()) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schema_version", "1");
        event.put("timestamp_ms", System.currentTimeMillis());
        event.put("event_type", "preview_bulkhead");
        event.put("phase", phase);
        event.put("source", source);
        event.put("wait_ms", waitMs);
        event.put("available_permits", semaphore.availablePermits());
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
}
