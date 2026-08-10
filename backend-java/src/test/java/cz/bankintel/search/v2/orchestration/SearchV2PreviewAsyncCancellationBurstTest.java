package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.bankintel.search.CatalogPreviewOrchestrator;
import cz.bankintel.search.CatalogPreviewService;
import cz.bankintel.search.v2.observability.SearchV2TelemetryWriter;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SemanticDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Validation phase (Fáze 3): repeated burst of preview requests (>=100) with a realistic outcome
 * mix (fast success / timeout / connection-reset-style transport failure). Captures every
 * {@code preview_lifecycle} telemetry payload in-memory via a mocked
 * {@link SearchV2TelemetryWriter} (the same pattern already used elsewhere in this suite) rather
 * than relying on the real writer's background thread/file, which is only started by Spring's
 * {@code @PostConstruct} and is never invoked when the writer is constructed directly in a test.
 */
class SearchV2PreviewAsyncCancellationBurstTest {

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

    @Test
    void hundredRequestBurstWithMixedOutcomesProducesExactlyOneTerminalEventEachAndCorrectLifecycleTelemetry() throws Exception {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "8");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "300");

        int total = 120;
        int timeoutCount = 20; // never-completing transport - must be genuinely cancelled
        int connectionResetCount = 10; // immediate exceptional completion, simulating a reset connection
        int successCount = total - timeoutCount - connectionResetCount;

        ConcurrentLinkedQueue<Map<String, Object>> capturedEvents = new ConcurrentLinkedQueue<>();
        SearchV2TelemetryWriter telemetryWriter = mock(SearchV2TelemetryWriter.class);
        when(telemetryWriter.enabled()).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = invocation.getArgument(0);
            capturedEvents.add(payload);
            return null;
        }).when(telemetryWriter).submitRaw(anyMap());

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.previewAsyncIfSupported(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> payload = invocation.getArgument(0);
            String id = String.valueOf(payload.get("set_id"));
            int index = Integer.parseInt(id.substring(id.lastIndexOf('-') + 1));
            if (index < timeoutCount) {
                CompletableFuture<Object> transport = new CompletableFuture<>(); // never completes
                CompletableFuture<Map<String, Object>> response =
                        transport.handle((v, ex) -> Map.of("ok", false, "preview_state", "error"));
                return Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(transport, response));
            }
            if (index < timeoutCount + connectionResetCount) {
                CompletableFuture<Object> transport = new CompletableFuture<>();
                transport.completeExceptionally(new java.io.IOException("Connection reset by peer"));
                CompletableFuture<Map<String, Object>> response = new CompletableFuture<>();
                response.completeExceptionally(new java.io.IOException("Connection reset by peer"));
                return Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(transport, response));
            }
            return Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(
                    CompletableFuture.completedFuture(new Object()),
                    CompletableFuture.completedFuture(Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1))))));
        });

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService(), telemetryWriter);

        List<SearchResult> batch = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            batch.add(result("burst-" + i, "fred"));
        }

        long startMs = System.currentTimeMillis();
        SearchV2PreviewVerifier.VerificationResult result = verifier.verifyTopOnly(batch, total, List.of());
        long durationMs = System.currentTimeMillis() - startMs;
        // verifyTopOnly() only awaits each arbiter's own publicResult - it does NOT wait for that
        // candidate's independently-scheduled execution timer to ALSO physically fire afterward (for
        // a candidate that already resolved via success/failure, that timer is still pending on a
        // background thread, scheduled for ~300ms after ITS OWN dispatch time). Give every such timer
        // a chance to fire and be safely ignored before asserting on late_completion_ignored below.
        Thread.sleep(500);

        assertThat(result.statuses()).hasSize(total);
        long timedOutStatuses = result.statuses().stream().filter(s -> "timeout".equals(s.get("preview_state"))).count();
        long okStatuses = result.statuses().stream().filter(s -> Boolean.TRUE.equals(s.get("ok"))).count();
        assertThat(timedOutStatuses).as("every never-completing transport must resolve as timeout, not hang forever").isEqualTo(timeoutCount);
        assertThat(okStatuses).as("every fast-success candidate must actually succeed").isEqualTo(successCount);

        Map<String, Long> phaseCounts = new java.util.HashMap<>();
        for (Map<String, Object> event : capturedEvents) {
            String phase = String.valueOf(event.get("phase"));
            phaseCounts.merge(phase, 1L, Long::sum);
        }

        assertThat(phaseCounts.getOrDefault("completed", 0L))
                .as("exactly one terminal 'completed' lifecycle event per attempt, phase counts: %s", phaseCounts)
                .isEqualTo((long) total);
        assertThat(phaseCounts.getOrDefault("cancellation_attempted", 0L))
                .as("every never-completing transport must have a cancellation attempted against it")
                .isGreaterThanOrEqualTo((long) timeoutCount);
        assertThat(phaseCounts.getOrDefault("permit_released", 0L))
                .as("bulkhead is disabled here, but the arbiter's own permit-release bookkeeping still "
                        + "fires (as a no-op release) exactly once per attempt")
                .isEqualTo((long) total);
        assertThat(phaseCounts.getOrDefault("late_completion_ignored", 0L))
                .as("every candidate produces exactly one late/duplicate completion attempt that must "
                        + "be safely ignored: success/connection-reset candidates resolve before their "
                        + "own scheduled execution timer fires (which still fires later regardless, "
                        + "since it is not cancelled just because the arbiter already decided); timeout "
                        + "candidates win via the execution timer, but cancelling their transport makes "
                        + "its dependent .handle(...) stage complete shortly afterward with a synthetic "
                        + "value, which arrives as a late 'succeed' attempt")
                .isEqualTo((long) total);

        System.out.printf(
                "Phase 3 burst: %d requests, %dms total, phase counts=%s%n", total, durationMs, phaseCounts);
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
