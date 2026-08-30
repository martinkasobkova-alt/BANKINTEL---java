package cz.bankintel.search.v2.orchestration;

import cz.bankintel.search.CatalogPreviewOrchestrator;
import cz.bankintel.search.CatalogPreviewService;
import cz.bankintel.search.CatalogSourceRegistry;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.v2.observability.SearchV2TelemetryWriter;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SearchV2PreviewOutcome;
import cz.bankintel.util.BankIntelEnvVars;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SearchV2PreviewVerifier {

    private static final int DEFAULT_VERIFY_LIMIT = 8;
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Set<String> DATASET_IDENTIFIER_SOURCES = Set.of("eurostat");
    private static final Set<String> NON_FILTERING_GEO_SENTINELS =
            Set.of("GLOBAL", "WORLD", "SVET", "SVĚT");

    private final CatalogPreviewService previewService;
    private final SearchV2CacheService cacheService;
    private final SearchV2TelemetryWriter telemetryWriter;
    private final SearchV2PreviewBulkhead bulkhead;
    private final SearchV2PreviewCircuitBreaker circuitBreaker;
    private final int previewParallelism;
    private final long previewTimeoutMs;
    private final ExecutorService executor;

    /** Legacy 2-arg constructor, preserved so existing direct-construction tests keep compiling unchanged; telemetry, bulkhead and circuit breaker are no-ops through this path. */
    public SearchV2PreviewVerifier(CatalogPreviewService previewService, SearchV2CacheService cacheService) {
        this(previewService, cacheService, null, null);
    }

    /** Preserved for tests constructed before PR-8; bulkhead and circuit breaker are no-ops (always admit) through this path. */
    public SearchV2PreviewVerifier(
            CatalogPreviewService previewService, SearchV2CacheService cacheService, SearchV2TelemetryWriter telemetryWriter) {
        this(previewService, cacheService, telemetryWriter, null);
    }

    /** Preserved for tests constructed before PR-10; circuit breaker is a no-op (always allows) through this path. */
    public SearchV2PreviewVerifier(
            CatalogPreviewService previewService,
            SearchV2CacheService cacheService,
            SearchV2TelemetryWriter telemetryWriter,
            SearchV2PreviewBulkhead bulkhead) {
        this(previewService, cacheService, telemetryWriter, bulkhead, null);
    }

    @Autowired
    public SearchV2PreviewVerifier(
            CatalogPreviewService previewService,
            SearchV2CacheService cacheService,
            SearchV2TelemetryWriter telemetryWriter,
            SearchV2PreviewBulkhead bulkhead,
            SearchV2PreviewCircuitBreaker circuitBreaker) {
        this.previewService = previewService;
        this.cacheService = cacheService;
        this.telemetryWriter = telemetryWriter;
        this.bulkhead = bulkhead == null ? new SearchV2PreviewBulkhead() : bulkhead;
        this.circuitBreaker = circuitBreaker == null ? new SearchV2PreviewCircuitBreaker() : circuitBreaker;
        this.previewParallelism = configuredPreviewParallelism();
        this.previewTimeoutMs = configuredPreviewTimeoutMs();
        this.executor = Executors.newFixedThreadPool(previewParallelism);
    }

    public VerificationResult verify(List<SearchResult> results, int maxResults) {
        return verify(results, maxResults, List.of());
    }

    public VerificationResult verify(List<SearchResult> results, int maxResults, List<String> requestedGeographies) {
        return verify(results, maxResults, requestedGeographies, true);
    }

    public VerificationResult verifyTopOnly(List<SearchResult> results, int maxResults, List<String> requestedGeographies) {
        return verify(results, maxResults, requestedGeographies, false);
    }

    private VerificationResult verify(
            List<SearchResult> results,
            int maxResults,
            List<String> requestedGeographies,
            boolean scanForReplacements) {
        long start = System.currentTimeMillis();
        List<SearchResult> accepted = new ArrayList<>();
        List<Map<String, Object>> statuses = new ArrayList<>();
        int limit = maxResults <= 0 ? DEFAULT_VERIFY_LIMIT : maxResults;
        int scanLimit = scanForReplacements ? Math.max(DEFAULT_VERIFY_LIMIT, limit * 4) : limit;
        List<SearchResult> checked = results == null ? List.of() : results.stream().limit(scanLimit).toList();
        Map<String, CompletableFuture<Map<String, Object>>> requestFutures = new LinkedHashMap<>();
        for (int offset = 0; offset < checked.size() && accepted.size() < limit; offset += previewParallelism) {
            List<SearchResult> batch = checked.subList(offset, Math.min(offset + previewParallelism, checked.size()));
            List<CompletableFuture<Map<String, Object>>> futures = batch.stream()
                    .map(result -> requestFutures.computeIfAbsent(
                            verificationKey(result.candidate(), requestedGeographies),
                            ignored -> dispatchVerification(result.candidate(), requestedGeographies)))
                    .toList();
            for (int i = 0; i < batch.size(); i++) {
                Map<String, Object> status = futures.get(i).join();
                statuses.add(status);
                if (accepted.size() < limit && Boolean.TRUE.equals(status.get("ok"))) {
                    accepted.add(batch.get(i));
                }
            }
        }
        return new VerificationResult(accepted, statuses, System.currentTimeMillis() - start, statuses.size(), requestFutures.size());
    }

    private Map<String, Object> verifyOne(SearchCandidate candidate, List<String> requestedGeographies) {
        return cacheService.getOrCompute(
                verificationKey(candidate, requestedGeographies), CACHE_TTL, () -> doVerify(candidate, requestedGeographies));
    }

    private Map<String, Object> doVerify(SearchCandidate candidate, List<String> requestedGeographies) {
        long start = System.currentTimeMillis();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("source", candidate.source());
        status.put("series_id", candidate.seriesId());
        status.put("transport_type", "worker_thread");
        try {
            Map<String, Object> payload = previewPayload(candidate, requestedGeographies);
            Map<String, Object> response = previewService.preview(payload);
            String state = CatalogMapSupport.firstNonBlank(response.get("preview_state"), response.get("sync_state"));
            int rows = rowCount(response.get("rows"));
            boolean ok = rows > 0 && !"error".equalsIgnoreCase(state) && !"unsupported".equalsIgnoreCase(state);
            status.put("ok", ok);
            status.put("rows", rows);
            status.put("preview_state", state);
            status.put("preview_request_payload", reusablePreviewRequest(payload));
            if (!ok) {
                status.put("reason", CatalogMapSupport.firstNonBlank(response.get("message"), response.get("error")));
            } else {
                status.put("preview_payload", new LinkedHashMap<>(response));
                Map<String, Object> queryParams = firstMap(
                        response.get("query_params"),
                        response.get("filters_used"),
                        payload.get("query_params"));
                if (!queryParams.isEmpty()) {
                    status.put("query_params", queryParams);
                }
            }
        } catch (Exception ex) {
            status.put("ok", false);
            // PR-7 fix: this branch previously never set preview_state, leaving a technical exception
            // unclassifiable downstream (SearchV2PreviewOutcome.classify would fall through to
            // POSSIBLE instead of FAILED). Purely additive - does not change `ok`/`reason`/control flow.
            status.put("preview_state", "error");
            status.put("reason", ex.getMessage());
        }
        status.put("latency_ms", System.currentTimeMillis() - start);
        return status;
    }

    /**
     * Chooses the dispatch mechanism for one candidate. When
     * {@code SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED} is off, behavior is byte-for-byte
     * identical to before this PR (same {@code supplyAsync(...).completeOnTimeout(...)} composition,
     * with its documented limitations). When on, dispatch goes through
     * {@link #dispatchCancellableAsync}, which never uses that composition at all — see
     * {@code docs/archive/search-v2-preview-cancellation-investigation.md}. The two mechanisms never run for
     * the same request: this is a single top-level branch, not a race between two implementations.
     *
     * <p>PR-10: {@link SearchV2PreviewCircuitBreaker#allowRequest} gates admission here, above both
     * mechanisms, so a source with an open breaker fails fast for either dispatch path without
     * consuming a bulkhead permit or a worker slot. The breaker only ever observes outcomes through
     * {@link #recordBreakerOutcome} on the future this method returns - it never needs to know which
     * mechanism (legacy or PR-6 cancellable) actually produced that outcome.
     *
     * <p>PR-7b: a structurally invalid candidate (missing source or series id) is rejected before
     * even the circuit breaker check - it is not a signal about any connector's health, so it must
     * never count toward, or be blocked by, either the breaker or the bulkhead.
     */
    private CompletableFuture<Map<String, Object>> dispatchVerification(
            SearchCandidate candidate, List<String> requestedGeographies) {
        if (isStructurallyInvalid(candidate)) {
            return CompletableFuture.completedFuture(structurallyInvalidStatus(candidate));
        }
        String source = candidate.source();
        if (!circuitBreaker.allowRequest(source)) {
            return CompletableFuture.completedFuture(circuitOpenStatus(candidate));
        }
        CompletableFuture<Map<String, Object>> future = asyncCancellationEnabled()
                ? dispatchCancellableAsync(candidate, requestedGeographies)
                : dispatchLegacy(candidate, requestedGeographies);
        return future.whenComplete((status, ex) -> recordBreakerOutcome(source, status));
    }

    private CompletableFuture<Map<String, Object>> dispatchLegacy(
            SearchCandidate candidate, List<String> requestedGeographies) {
        long timeoutMs = SearchV2PreviewTimeoutPolicy.resolveMs(candidate.source(), previewTimeoutMs);
        return CompletableFuture
                .supplyAsync(() -> verifyOne(candidate, requestedGeographies), executor)
                .completeOnTimeout(timeoutStatus(candidate, timeoutMs), timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Cache hits are recorded as successes too - only ever-{@code ok=true} statuses get cached, so
     * this is never misleading. Delegates classification to the canonical
     * {@link SearchV2PreviewOutcome#classify}, so this method (and both dispatch paths that funnel
     * through it) automatically stay correct as that classification evolves. Only {@code TIMEOUT} and
     * {@code TRANSPORT_FAILURE} - a genuine timeout of a dispatched request, or a transport/HTTP-level
     * failure (including the {@code sync_failed} shape produced by {@code PreviewResponseBuilder} for
     * every real 429/5xx/connection error) - ever count against a source's health. Capacity outcomes
     * ({@code CIRCUIT_OPEN}, {@code BULKHEAD_REJECTED}), pre-transport {@code INTERNAL_FAILURE},
     * {@code CANCELLED}, {@code UNSUPPORTED}, {@code STRUCTURALLY_INVALID}, {@code NOT_CHECKED}, and a
     * confirmed-{@code EMPTY} result are all neutral, since none of them reflect whether the connector
     * itself is actually unhealthy.
     */
    private void recordBreakerOutcome(String source, Map<String, Object> status) {
        if (status == null) {
            return;
        }
        String outcome = SearchV2PreviewOutcome.classify(status);
        if (SearchV2PreviewOutcome.VERIFIED.equals(outcome)) {
            circuitBreaker.recordSuccess(source);
            return;
        }
        if (SearchV2PreviewOutcome.isBreakerFailure(outcome)) {
            circuitBreaker.recordFailure(source);
        }
        // Everything else (CIRCUIT_OPEN/BULKHEAD_REJECTED/INTERNAL_FAILURE/CANCELLED/UNSUPPORTED/
        // STRUCTURALLY_INVALID/EMPTY/POSSIBLE/NOT_CHECKED) is neutral - see isBreakerFailure's javadoc.
    }

    private static boolean isStructurallyInvalid(SearchCandidate candidate) {
        return candidate == null
                || candidate.source() == null || candidate.source().isBlank()
                || candidate.seriesId() == null || candidate.seriesId().isBlank();
    }

    /** PR-7c: canonical name/state for {@code SearchV2PreviewOutcome.STRUCTURALLY_INVALID} - see its javadoc for the rename rationale. */
    private static Map<String, Object> structurallyInvalidStatus(SearchCandidate candidate) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("source", candidate == null ? "" : candidate.source());
        status.put("series_id", candidate == null ? "" : candidate.seriesId());
        status.put("ok", false);
        status.put("preview_state", "structurally_invalid");
        status.put("reason", "candidate is missing source or series_id");
        return status;
    }

    private static Map<String, Object> circuitOpenStatus(SearchCandidate candidate) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("source", candidate.source());
        status.put("series_id", candidate.seriesId());
        status.put("ok", false);
        status.put("preview_state", "circuit_open");
        status.put("reason", "circuit_breaker_open:" + candidate.source());
        return status;
    }

    private static Map<String, Object> bulkheadRejectedStatus(SearchCandidate candidate) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("source", candidate.source());
        status.put("series_id", candidate.seriesId());
        status.put("ok", false);
        status.put("preview_state", "bulkhead_rejected");
        status.put("reason", "bulkhead_saturated:" + candidate.source());
        return status;
    }

    /**
     * The PR-6/PR-7c mechanism. Exactly one outcome ever completes the returned future - success,
     * timeout, cancelled, failure, or bulkhead-rejected - decided entirely by which
     * {@link PreviewRequestArbiter} phase transition wins its own compare-and-set. There is never a
     * race between two clocks: {@link PreviewRequestArbiter#rejectForCapacity} can only ever win while
     * the phase is still CREATED/WAITING_FOR_CAPACITY, and {@link PreviewRequestArbiter#executionTimeout}
     * can only ever win while the phase is DISPATCHED - see that class's javadoc.
     *
     * <p>Two independently scheduled timers exist, each scoped to exactly one phase:
     * <ul>
     *   <li>The <b>admission timer</b> (only when {@link SearchV2PreviewBulkhead#enabled()}) - bounds
     *       how long this attempt may sit in CREATED/WAITING_FOR_CAPACITY (queued on {@code executor},
     *       or blocked waiting for a bulkhead permit) before it is resolved as
     *       {@code bulkhead_rejected}. Its budget is the same {@code timeoutMs} used everywhere else
     *       (PR-9's resolved, possibly-tiered per-source timeout) - no new externally configurable
     *       value is introduced; this is "the smallest safe mechanism based on existing configuration".
     *   <li>The <b>execution timer</b> - bounds how long this attempt may spend DISPATCHED (a bulkhead
     *       permit held, real transport starting or already running) before it is resolved as
     *       {@code timeout}. Scheduled fresh, starting from zero, the instant the phase actually
     *       becomes DISPATCHED (right after a permit is acquired, or immediately when the bulkhead is
     *       disabled) - never from attempt-creation time, so a slow admission wait never eats into the
     *       operation's own execution budget, and vice versa.
     * </ul>
     *
     * <p>When {@link SearchV2PreviewBulkhead#enabled()} is {@code false}, there is no admission phase
     * at all: {@link PreviewRequestArbiter#admittedWithoutPermit()} moves CREATED straight to
     * DISPATCHED synchronously, right here, before {@code executor.execute(...)} even queues the real
     * work - byte-for-byte the pre-PR-8 behavior (one timer, scheduled from creation, protecting
     * against a saturated {@code executor} exactly as before).
     */
    private CompletableFuture<Map<String, Object>> dispatchCancellableAsync(
            SearchCandidate candidate, List<String> requestedGeographies) {
        String key = verificationKey(candidate, requestedGeographies);
        Object cached = cacheService.get(key).orElse(null);
        if (cached instanceof Map<?, ?> cachedMap) {
            return CompletableFuture.completedFuture(CatalogMapSupport.castMap(cachedMap));
        }

        PreviewRequestArbiter arbiter = new PreviewRequestArbiter(telemetrySink());
        Map<String, Object> payload = previewPayload(candidate, requestedGeographies);
        long timeoutMs = SearchV2PreviewTimeoutPolicy.resolveMs(candidate.source(), previewTimeoutMs);
        emitTieredTimeoutTelemetry(candidate.source(), timeoutMs);

        boolean bulkheadEnabled = SearchV2PreviewBulkhead.enabled();
        if (bulkheadEnabled) {
            arbiter.beginAdmission();
            // Independent of `executor`: a saturated preview pool, or a saturated bulkhead, must
            // never be able to delay this decision beyond its own admission budget.
            CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS)
                    .execute(() -> arbiter.rejectForCapacity(bulkheadRejectedStatus(candidate)));
        } else {
            arbiter.admittedWithoutPermit();
            scheduleExecutionTimeout(arbiter, candidate, timeoutMs);
        }

        executor.execute(() -> runDispatchedAttempt(
                candidate, requestedGeographies, key, arbiter, payload, timeoutMs, bulkheadEnabled));
        return arbiter.publicResult();
    }

    private void scheduleExecutionTimeout(PreviewRequestArbiter arbiter, SearchCandidate candidate, long timeoutMs) {
        CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS)
                .execute(() -> arbiter.executionTimeout(timeoutStatus(candidate, timeoutMs)));
    }

    private void runDispatchedAttempt(
            SearchCandidate candidate,
            List<String> requestedGeographies,
            String cacheKey,
            PreviewRequestArbiter arbiter,
            Map<String, Object> payload,
            long timeoutMs,
            boolean bulkheadEnabled) {
        if (arbiter.phase() == PreviewRequestArbiter.Phase.TERMINAL) {
            return; // Already decided (e.g. rejected for capacity) while this attempt was queued on `executor`.
        }
        if (bulkheadEnabled) {
            Optional<Runnable> admission = bulkhead.tryAdmit(candidate.source(), timeoutMs);
            if (admission.isEmpty()) {
                // Nothing was acquired, so there is nothing to release. The independently scheduled
                // admission timer (rejectForCapacity) owns this outcome - possibly already resolved it.
                return;
            }
            boolean admitted = arbiter.admittedWithPermit(admission.get());
            if (!admitted) {
                // Lost the race after all (the admission timer won moments earlier) - the arbiter never
                // took ownership of this permit, so it must be released directly, right here.
                admission.get().run();
                return;
            }
            scheduleExecutionTimeout(arbiter, candidate, timeoutMs);
        }
        // Phase is now DISPATCHED and the execution timer is running - proceed to the real work.
        Optional<CatalogPreviewOrchestrator.AsyncPreviewHandle> asyncHandle;
        try {
            asyncHandle = previewService.previewAsyncIfSupported(payload);
        } catch (Exception ex) {
            // PR-7b: this happens before any transport is attempted (connector resolution/lookup) -
            // internal_failure, distinct from a transport-level error.
            arbiter.fail(failureStatus(candidate, rootMessage(ex), "internal_error", null), rootMessage(ex));
            return;
        }
        if (asyncHandle.isPresent()) {
            runAsyncCancellableAttempt(candidate, payload, cacheKey, arbiter, asyncHandle.get());
        } else {
            runWorkerThreadFallbackAttempt(candidate, requestedGeographies, cacheKey, arbiter);
        }
    }

    /** Path for connectors that implement {@link cz.bankintel.connector.AsyncCancellableFetch}. */
    private void runAsyncCancellableAttempt(
            SearchCandidate candidate,
            Map<String, Object> payload,
            String cacheKey,
            PreviewRequestArbiter arbiter,
            CatalogPreviewOrchestrator.AsyncPreviewHandle handle) {
        long start = System.currentTimeMillis();
        boolean shouldRun = arbiter.bindTransport(() -> handle.transportFuture().cancel(true), "async_http");
        if (!shouldRun) {
            return; // Already decided (execution timeout fired first); transport already cancelled.
        }
        handle.responseFuture().whenComplete((response, ex) -> {
            if (ex != null) {
                arbiter.fail(failureStatus(candidate, rootMessage(ex), "error", "async_http"), rootMessage(ex));
                return;
            }
            Map<String, Object> status = toVerificationStatus(candidate, payload, response, start);
            if (Boolean.TRUE.equals(status.get("ok"))) {
                cacheService.put(cacheKey, CACHE_TTL, status);
            }
            arbiter.succeed(status);
        });
    }

    /**
     * Fallback path for connectors NOT converted to {@link cz.bankintel.connector.AsyncCancellableFetch}
     * (see {@code docs/archive/search-v2-preview-cancellation-investigation.md} section 9 for which and why).
     * Runs the existing blocking {@link #doVerify} call, but as a raw {@link Runnable} submitted
     * directly to {@code executor} — NOT via {@code CompletableFuture.supplyAsync(...)} — specifically
     * so that {@code Thread.interrupt()} (this class's {@code Cancellable} for this path) actually
     * interrupts the worker thread. {@code CompletableFuture.cancel()} on a {@code supplyAsync} result
     * does not do this (confirmed in the PR-2 investigation); interrupting the worker thread directly
     * does, because {@code HttpClient.send(...)}'s blocking implementation is built on the
     * interruptible {@code Future.get()}. This frees the {@code SEARCH_PREVIEW_CONCURRENCY}-bounded
     * worker slot promptly — the practically important fix for pool exhaustion — but, unlike the
     * async-HTTP path, does not guarantee the underlying TCP exchange is aborted at the same instant;
     * that stronger guarantee requires converting the connector to {@code sendAsync}.
     */
    private void runWorkerThreadFallbackAttempt(
            SearchCandidate candidate, List<String> requestedGeographies, String cacheKey, PreviewRequestArbiter arbiter) {
        Thread workerThread = Thread.currentThread();
        boolean shouldRun = arbiter.bindTransport(workerThread::interrupt, "worker_thread");
        if (!shouldRun) {
            return;
        }
        try {
            Map<String, Object> status = doVerify(candidate, requestedGeographies);
            if (Boolean.TRUE.equals(status.get("ok"))) {
                cacheService.put(cacheKey, CACHE_TTL, status);
            }
            arbiter.succeed(status);
        } catch (Exception ex) {
            // PR-7b: doVerify already catches everything reasonable internally and never throws in
            // practice - anything escaping here is unusual enough to treat as internal, not transport.
            arbiter.fail(failureStatus(candidate, ex.getMessage(), "internal_error", "worker_thread"), ex.getMessage());
        } finally {
            Thread.interrupted(); // clear any interrupt flag before this pooled thread is reused
        }
    }

    /** Mirrors {@link #doVerify}'s status shape exactly, so downstream code (SearchV2Service etc.) sees no difference. */
    private Map<String, Object> toVerificationStatus(
            SearchCandidate candidate, Map<String, Object> payload, Map<String, Object> response, long startedAtMs) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("source", candidate.source());
        status.put("series_id", candidate.seriesId());
        status.put("transport_type", "async_http");
        String state = CatalogMapSupport.firstNonBlank(response.get("preview_state"), response.get("sync_state"));
        int rows = rowCount(response.get("rows"));
        boolean ok = rows > 0 && !"error".equalsIgnoreCase(state) && !"unsupported".equalsIgnoreCase(state);
        status.put("ok", ok);
        status.put("rows", rows);
        status.put("preview_state", state);
        status.put("preview_request_payload", reusablePreviewRequest(payload));
        if (!ok) {
            status.put("reason", CatalogMapSupport.firstNonBlank(response.get("message"), response.get("error")));
        } else {
            status.put("preview_payload", new LinkedHashMap<>(response));
            Map<String, Object> queryParams =
                    firstMap(response.get("query_params"), response.get("filters_used"), payload.get("query_params"));
            if (!queryParams.isEmpty()) {
                status.put("query_params", queryParams);
            }
        }
        status.put("latency_ms", System.currentTimeMillis() - startedAtMs);
        return status;
    }

    /**
     * @param previewState e.g. {@code "error"} (transport-level, after an attempt was made) or
     *     {@code "internal_error"} (before any transport was attempted) - see
     *     {@code SearchV2PreviewOutcome} for how each maps to a distinct outcome.
     * @param transportType {@code "async_http"}/{@code "worker_thread"}, or {@code null} if no
     *     transport was ever attempted for this failure.
     */
    private static Map<String, Object> failureStatus(
            SearchCandidate candidate, String reason, String previewState, String transportType) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("source", candidate.source());
        status.put("series_id", candidate.seriesId());
        status.put("ok", false);
        status.put("preview_state", previewState);
        status.put("reason", reason);
        if (transportType != null) {
            status.put("transport_type", transportType);
        }
        return status;
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? "unknown error" : String.valueOf(current.getMessage());
    }

    /** PR-9: only emitted when tiering is actually enabled - a no-op otherwise, like the rest of telemetry. */
    private void emitTieredTimeoutTelemetry(String source, long resolvedTimeoutMs) {
        if (telemetryWriter == null || !telemetryWriter.enabled() || !SearchV2PreviewTimeoutPolicy.enabled()) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schema_version", "1");
        event.put("timestamp_ms", System.currentTimeMillis());
        event.put("event_type", "preview_timeout_tier");
        event.put("source", source);
        event.put("tier", SearchV2PreviewTimeoutPolicy.tierFor(source).name());
        event.put("resolved_timeout_ms", resolvedTimeoutMs);
        telemetryWriter.submitRaw(event);
    }

    /**
     * PR-7c: one event per lifecycle transition, named to match the exact field names requested for
     * this hardening pass - admission_wait_started/admission_acquired/admission_rejected/
     * transport_dispatched/execution_timeout/permit_released, plus a final "completed" event carrying
     * both the outcome and the phase the request was in immediately before that terminal transition.
     * Every emission goes through {@code telemetryWriter.submitRaw}, which enqueues onto the existing
     * bounded queue drained by PR-1's single background writer thread - never a synchronous write.
     */
    private PreviewRequestArbiter.Sink telemetrySink() {
        if (telemetryWriter == null || !telemetryWriter.enabled()) {
            return PreviewRequestArbiter.Sink.NO_OP;
        }
        return new PreviewRequestArbiter.Sink() {
            @Override
            public void admissionWaitStarted() {
                emit("admission_wait_started", Map.of());
            }

            @Override
            public void admissionAcquired() {
                emit("admission_acquired", Map.of());
            }

            @Override
            public void admissionRejected() {
                emit("admission_rejected", Map.of());
            }

            @Override
            public void transportDispatched(String transportType) {
                emit("transport_dispatched", Map.of("transport_type", transportType));
            }

            @Override
            public void executionTimedOut() {
                emit("execution_timeout", Map.of());
            }

            @Override
            public void completed(PreviewRequestArbiter.Outcome outcome, PreviewRequestArbiter.Phase phaseAtCompletion, long executionMs) {
                emit(
                        "completed",
                        Map.of(
                                "outcome", outcome.name(),
                                "lifecycle_phase_at_completion", phaseAtCompletion.name(),
                                "execution_ms", executionMs));
            }

            @Override
            public void cancellationAttempted() {
                emit("cancellation_attempted", Map.of());
            }

            @Override
            public void cancellationResult(boolean succeeded) {
                emit("cancellation_result", Map.of("succeeded", succeeded));
            }

            @Override
            public void lateCompletionIgnored(PreviewRequestArbiter.Outcome winningOutcome, PreviewRequestArbiter.Outcome lateOutcome) {
                emit(
                        "late_completion_ignored",
                        Map.of("winning_outcome", winningOutcome.name(), "late_outcome", lateOutcome.name()));
            }

            @Override
            public void transportFailure(String reason) {
                emit(
                        "transport_failure",
                        Map.of("reason", reason == null ? "" : reason.length() > 200 ? reason.substring(0, 200) : reason));
            }

            @Override
            public void permitReleased() {
                emit("permit_released", Map.of());
            }

            private void emit(String phaseLabel, Map<String, Object> details) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("schema_version", "2");
                event.put("timestamp_ms", System.currentTimeMillis());
                event.put("event_type", "preview_lifecycle");
                event.put("phase", phaseLabel);
                event.putAll(details);
                telemetryWriter.submitRaw(event);
            }
        };
    }

    private static boolean asyncCancellationEnabled() {
        return BankIntelEnvVars.isTruthy("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED");
    }

    private static Map<String, Object> timeoutStatus(SearchCandidate candidate, long previewTimeoutMs) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("source", candidate.source());
        status.put("series_id", candidate.seriesId());
        status.put("ok", false);
        status.put("preview_state", "timeout");
        status.put("reason", "preview_timeout_ms=" + previewTimeoutMs);
        status.put("latency_ms", previewTimeoutMs);
        return status;
    }

    /**
     * Cache/dedup key for {@code requestFutures.computeIfAbsent} - deliberately independent of {@link
     * #previewIdentifier}, which is a display/payload concern (what {@code set_id}/{@code id} gets sent
     * to the downstream preview endpoint) and must keep its existing dataset-vs-series behavior
     * unchanged. This key answers a narrower question: may two candidates safely share one in-flight
     * preview request and its resulting status? {@code previewIdentifier} alone answered that too
     * coarsely for sources in {@link #DATASET_IDENTIFIER_SOURCES} - e.g. several distinct Eurostat
     * COICOP sub-series (different {@code seriesId}, same {@code dataset}, both empty {@code
     * query_params}) collapsed onto the same key and silently shared one candidate's verification
     * status. {@code seriesId} is therefore always part of the identity below, in addition to {@code
     * dataset}, {@code frequency} and a canonical (key-sorted, null/absent-vs-empty-distinguishing)
     * projection of {@code query_params} - so two candidates only ever share a key when they would in
     * fact issue the same preview request. See the preview-verifier key-collision diagnosis (2026-07-30).
     */
    private static String verificationKey(SearchCandidate candidate, List<String> requestedGeographies) {
        String geoKey = String.join(",", normalizedGeographies(candidate, requestedGeographies));
        return "preview:" + candidate.source() + ":" + canonicalPreviewRequestIdentity(candidate) + ":geo=" + geoKey;
    }

    private static String canonicalPreviewRequestIdentity(SearchCandidate candidate) {
        return "dataset=" + canonicalPart(candidate.dataset())
                + "|series=" + canonicalPart(candidate.seriesId())
                + "|freq=" + canonicalPart(candidate.frequency())
                + "|params=" + canonicalQueryParamsIdentity(candidate);
    }

    private static String canonicalPart(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Distinguishes three states so a genuinely-unfiltered request never collides with an
     * explicitly-empty-filter one: no {@code query_params} key at all (or a non-Map value, mirroring
     * {@link #firstMap}'s defensive handling) canonicalizes to {@code "absent"}; an explicitly empty
     * map to {@code "{}"}; a non-empty map to a key-sorted, deterministically-serialized projection
     * (see {@link #canonicalizeQueryParamValue}) so insertion-order differences between two
     * semantically-identical maps never produce different keys.
     */
    private static String canonicalQueryParamsIdentity(SearchCandidate candidate) {
        if (candidate.raw() == null || !candidate.raw().containsKey("query_params")) {
            return "absent";
        }
        Object value = candidate.raw().get("query_params");
        if (!(value instanceof Map<?, ?> map)) {
            return "absent";
        }
        return map.isEmpty() ? "{}" : canonicalizeQueryParamValue(map);
    }

    private static String canonicalizeQueryParamValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            java.util.Map<String, Object> sorted = new java.util.TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(entry.getKey()).append('=').append(canonicalizeQueryParamValue(entry.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(canonicalizeQueryParamValue(item));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(value);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static Map<String, Object> previewPayload(SearchCandidate candidate, List<String> requestedGeographies) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (candidate.raw() != null) {
            payload.putAll(candidate.raw());
        }
        payload.put("source_type", previewSource(candidate.source()));
        payload.put("source", candidate.source());
        String previewIdentifier = previewIdentifier(candidate);
        payload.put("set_id", previewIdentifier);
        payload.put("id", previewIdentifier);
        payload.put("name", candidate.title());
        payload.put("user_query", candidate.matchedQuery());
        List<String> geographies = normalizedGeographies(candidate, requestedGeographies);
        if (!geographies.isEmpty()) {
            payload.putIfAbsent("country", geographies.getFirst());
            Map<String, Object> geoIntent = new LinkedHashMap<>();
            geoIntent.put("requested_geo_codes", geographies);
            payload.putIfAbsent("geo_intent", geoIntent);
        }
        return payload;
    }

    private static String previewIdentifier(SearchCandidate candidate) {
        if (candidate == null) {
            return "";
        }
        String source = CatalogSourceRegistry.normalizeSearchSource(candidate.source());
        Map<String, Object> queryParams = candidate.raw() == null
                ? Map.of()
                : firstMap(candidate.raw().get("query_params"));
        if (DATASET_IDENTIFIER_SOURCES.contains(source)
                && queryParams.isEmpty()
                && candidate.dataset() != null
                && !candidate.dataset().isBlank()) {
            return candidate.dataset();
        }
        return candidate.seriesId();
    }

    private static Map<String, Object> reusablePreviewRequest(Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of(
                "source_type",
                "source",
                "set_id",
                "id",
                "name",
                "country",
                "query_params",
                "geo_intent",
                "user_query")) {
            if (payload.containsKey(key)) {
                out.put(key, payload.get(key));
            }
        }
        return out;
    }

    @SafeVarargs
    private static Map<String, Object> firstMap(Object... values) {
        for (Object value : values) {
            if (value instanceof Map<?, ?> map && !map.isEmpty()) {
                return CatalogMapSupport.castMap(map);
            }
        }
        return Map.of();
    }

    private static List<String> normalizedGeographies(SearchCandidate candidate, List<String> requestedGeographies) {
        List<String> out = new ArrayList<>();
        addGeo(out, candidate.geo());
        if (out.isEmpty()) {
            for (String geo : requestedGeographies == null ? List.<String>of() : requestedGeographies) {
                addGeo(out, geo);
            }
        }
        return out.stream().distinct().toList();
    }

    private static void addGeo(List<String> out, String geo) {
        if (geo == null || geo.isBlank()) {
            return;
        }
        String normalized = geo.trim().toUpperCase(Locale.ROOT);
        if (!NON_FILTERING_GEO_SENTINELS.contains(normalized)) {
            out.add(normalized);
        }
    }

    private static String previewSource(String source) {
        String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
        return switch (normalized) {
            case "data360" -> "world_bank_data360";
            case "commodities" -> "worldbank_pink_sheet";
            case "stocks" -> "yahoo_finance";
            case "oecd4" -> "oecd";
            default -> normalized;
        };
    }

    private static int rowCount(Object rows) {
        if (rows instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    private static int configuredPreviewParallelism() {
        return Math.max(1, Math.min(parseIntEnv("SEARCH_PREVIEW_CONCURRENCY", 3), 8));
    }

    private static long configuredPreviewTimeoutMs() {
        return Math.max(500, Math.min(parseLongEnv("SEARCH_PREVIEW_TIMEOUT_MS", 8000), 30_000));
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

    public record VerificationResult(
            List<SearchResult> accepted,
            List<Map<String, Object>> statuses,
            long latencyMs,
            int checkedCount,
            int uniqueRequestCount) {
        public VerificationResult(List<SearchResult> accepted, List<Map<String, Object>> statuses, long latencyMs) {
            this(accepted, statuses, latencyMs, statuses == null ? 0 : statuses.size(), statuses == null ? 0 : statuses.size());
        }
    }
}
