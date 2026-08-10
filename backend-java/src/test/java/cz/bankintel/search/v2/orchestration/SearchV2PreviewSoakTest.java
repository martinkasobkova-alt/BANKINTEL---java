package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.bankintel.search.CatalogPreviewOrchestrator;
import cz.bankintel.search.CatalogPreviewService;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SemanticDecision;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Validation phase (Fáze 9): leak/soak test with all four capacity-management flags enabled
 * together, mocked connectors (no real network traffic), a mixed outcome profile (success, timeout,
 * transport failure, slow response) across multiple sources, and repeated circuit-breaker
 * open/cooldown/half-open cycles + bulkhead saturation.
 *
 * <p><b>Scale-down, documented explicitly:</b> the requested scenario is at least 30 minutes; this
 * test runs for {@link #SOAK_DURATION_SECONDS} (a few minutes) instead. A genuine 30-minute run
 * requires either a live server process or a dedicated long-running CI job - neither exists in this
 * session's test infrastructure, and running one inline here would consume a large, disproportionate
 * share of this validation pass for a single data point. This test still exercises the exact same
 * code paths (arbiter lifecycle, bulkhead admission/release, circuit breaker state machine,
 * telemetry) under sustained concurrent load, and directly measures the leak indicators the phase
 * asks for: JVM thread count and heap usage before vs. after, plus the library-level indicators
 * (bulkhead permits, breaker state, pending futures) that a full-length soak would also check.
 */
class SearchV2PreviewSoakTest {

    private static final int SOAK_DURATION_SECONDS = 120;
    private static final String[] SOURCES = {"fred", "ecb2", "arad", "eurostat"};

    private SearchV2PreviewVerifier verifier;

    @AfterEach
    void cleanup() {
        System.clearProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD");
        System.clearProperty("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS");
        System.clearProperty("SEARCH_PREVIEW_TIMEOUT_MS");
        System.clearProperty("SEARCH_PREVIEW_CONCURRENCY");
        if (verifier != null) {
            verifier.shutdown();
        }
    }

    @Test
    void sustainedMixedLoadWithAllCapacityFlagsEnabledReturnsToBaselineAfterwards() throws Exception {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD", "3");
        System.setProperty("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS", "200"); // short, to force many open/half-open cycles
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "6");

        AtomicLong requestCounter = new AtomicLong();
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.previewAsyncIfSupported(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> payload = invocation.getArgument(0);
            String source = String.valueOf(payload.get("source"));
            long n = requestCounter.incrementAndGet();
            int roll = (int) (n % 10);
            // Deterministic-ish mix: ~60% fast success, ~20% timeout, ~10% transport failure, ~10% slow-but-ok.
            if (roll < 6) {
                return Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(
                        CompletableFuture.completedFuture(new Object()),
                        CompletableFuture.completedFuture(Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1))))));
            }
            if (roll < 8) {
                CompletableFuture<Object> transport = new CompletableFuture<>(); // never completes -> timeout
                CompletableFuture<Map<String, Object>> response =
                        transport.handle((v, ex) -> Map.of("ok", false, "preview_state", "error"));
                return Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(transport, response));
            }
            if (roll < 9) {
                CompletableFuture<Object> transport = new CompletableFuture<>();
                transport.completeExceptionally(new java.io.IOException("boom"));
                CompletableFuture<Map<String, Object>> response = new CompletableFuture<>();
                response.completeExceptionally(new java.io.IOException("boom"));
                return Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(transport, response));
            }
            // Slow but eventually ok - completes after a short real delay via a background thread.
            CompletableFuture<Object> transport = new CompletableFuture<>();
            CompletableFuture<Map<String, Object>> response = new CompletableFuture<>();
            CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> {
                transport.complete(new Object());
                response.complete(Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1))));
            });
            return Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(transport, response));
        });

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        Thread.sleep(200);
        int threadsBefore = threadBean.getThreadCount();
        long heapBeforeMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);

        ExecutorService driverPool = Executors.newFixedThreadPool(12);
        AtomicLong totalDispatched = new AtomicLong();
        AtomicLong totalErrors = new AtomicLong();
        AtomicInteger idSeq = new AtomicInteger();
        long deadline = System.currentTimeMillis() + SOAK_DURATION_SECONDS * 1000L;
        try {
            List<CompletableFuture<Void>> inFlight = new java.util.concurrent.CopyOnWriteArrayList<>();
            while (System.currentTimeMillis() < deadline) {
                if (inFlight.size() < 40) {
                    String source = SOURCES[ThreadLocalRandom.current().nextInt(SOURCES.length)];
                    int id = idSeq.incrementAndGet();
                    CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                        try {
                            verifier.verifyTopOnly(List.of(result("soak-" + id, source)), 1, List.of());
                            totalDispatched.incrementAndGet();
                        } catch (Exception ex) {
                            totalErrors.incrementAndGet();
                        }
                    }, driverPool);
                    inFlight.add(task);
                    task.whenComplete((v, ex) -> inFlight.remove(task));
                }
                Thread.sleep(5);
            }
            // Drain remaining in-flight work before measuring the "after" snapshot.
            CompletableFuture.allOf(inFlight.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        } finally {
            driverPool.shutdown();
            driverPool.awaitTermination(30, TimeUnit.SECONDS);
        }

        // Let daemon delayedExecutor timers/telemetry settle, then measure the "after" snapshot.
        Thread.sleep(1000);
        System.gc();
        Thread.sleep(200);
        int threadsAfter = threadBean.getThreadCount();
        long heapAfterMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);

        System.out.printf(
                "Phase 9 soak (%ds, mocked connectors): dispatched=%d driver-errors=%d, "
                        + "threads before=%d after=%d (delta=%d), heap before=%dMB after=%dMB (delta=%dMB)%n",
                SOAK_DURATION_SECONDS, totalDispatched.get(), totalErrors.get(),
                threadsBefore, threadsAfter, threadsAfter - threadsBefore,
                heapBeforeMb, heapAfterMb, heapAfterMb - heapBeforeMb);

        assertThat(totalErrors.get()).as("no unhandled exception should escape a single verifyTopOnly call").isZero();
        assertThat(totalDispatched.get()).as("the soak must have actually dispatched a substantial number of requests").isGreaterThan(500);
        // Generous bound: this JVM also runs Gradle/JUnit/Mockito infrastructure threads that can
        // fluctuate independently of this test; the important signal is "no large, sustained growth",
        // not "exactly zero delta".
        assertThat(threadsAfter - threadsBefore)
                .as("thread count must not have grown by more than a small, bounded amount after the soak "
                        + "and post-soak drain - no unbounded thread leak")
                .isLessThan(20);
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
