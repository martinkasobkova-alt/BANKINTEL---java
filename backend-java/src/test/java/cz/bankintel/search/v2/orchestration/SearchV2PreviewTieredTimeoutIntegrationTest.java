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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration-level tests proving PR-9's {@link SearchV2PreviewTimeoutPolicy} is wired correctly
 * into the real {@link SearchV2PreviewVerifier} dispatch path, not just the policy class in
 * isolation ({@code SearchV2PreviewTimeoutPolicyTest} covers that).
 */
class SearchV2PreviewTieredTimeoutIntegrationTest {

    private SearchV2PreviewVerifier verifier;

    @AfterEach
    void cleanup() {
        System.clearProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_TIMEOUT_FAST_MS");
        System.clearProperty("SEARCH_PREVIEW_TIMEOUT_SLOW_MS");
        System.clearProperty("SEARCH_PREVIEW_TIMEOUT_MS");
        System.clearProperty("SEARCH_PREVIEW_CONCURRENCY");
        if (verifier != null) {
            verifier.shutdown();
        }
    }

    // ---- Regression safety: disabled by default --------------------------------------------------

    @Test
    void disabledByDefaultUsesTheSameGlobalTimeoutForEverySourceEvenArad() {
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "5000");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.preview(anyMap())).thenReturn(Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1))));

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());
        SearchV2PreviewVerifier.VerificationResult result =
                verifier.verifyTopOnly(List.of(result("arad-1", "arad"), result("fred-1", "fred")), 2, List.of());

        assertThat(result.accepted()).hasSize(2);
    }

    // ---- SLOW tier gives real breathing room a tiny global default would have denied --------------

    @Test
    void slowTierGivesAnAradCandidateEnoughTimeThatATinyGlobalDefaultWouldHaveDenied() throws Exception {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "150"); // tiny global default
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_SLOW_MS", "3000"); // generous SLOW tier
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CompletableFuture<Object> transport = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> response = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(400, TimeUnit.MILLISECONDS).execute(() -> {
            transport.complete(new Object());
            response.complete(Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1))));
        });
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.previewAsyncIfSupported(anyMap()))
                .thenReturn(Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(transport, response)));

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());
        SearchV2PreviewVerifier.VerificationResult result =
                verifier.verifyTopOnly(List.of(result("slow-arad", "arad")), 1, List.of());

        assertThat(result.statuses().get(0).get("ok"))
                .as("400ms real latency must succeed under the SLOW tier's 3000ms budget, even though "
                        + "the tiny 150ms global default (used pre-PR-9 for every source) would have timed it out")
                .isEqualTo(true);
    }

    // ---- FAST tier can be stricter than a generous global default ---------------------------------

    @Test
    void fastTierCanBeStricterThanAGenerousGlobalDefaultForFred() {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "5000"); // generous global default
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_FAST_MS", "150"); // tight FAST tier
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CompletableFuture<Object> transport = new CompletableFuture<>(); // never completes within the test
        CompletableFuture<Map<String, Object>> response =
                transport.handle((v, ex) -> Map.of("ok", false, "preview_state", "error"));
        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.previewAsyncIfSupported(anyMap()))
                .thenReturn(Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(transport, response)));

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());
        SearchV2PreviewVerifier.VerificationResult result =
                verifier.verifyTopOnly(List.of(result("slow-fred", "fred")), 1, List.of());

        assertThat(result.statuses().get(0).get("preview_state"))
                .as("FAST tier's own 150ms budget must govern fred, not the generous 5000ms global default")
                .isEqualTo("timeout");
    }

    // ---- Mixed-tier batch: each candidate's own tier applies independently ------------------------

    @Test
    void mixedTierBatchAppliesEachCandidatesOwnTierIndependently() {
        System.setProperty("SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_MS", "8000");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_FAST_MS", "150");
        System.setProperty("SEARCH_PREVIEW_TIMEOUT_SLOW_MS", "3000");
        System.setProperty("SEARCH_PREVIEW_CONCURRENCY", "3");

        CompletableFuture<Object> aradTransport = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> aradResponse = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(400, TimeUnit.MILLISECONDS).execute(() -> {
            aradTransport.complete(new Object());
            aradResponse.complete(Map.of("preview_state", "ok", "rows", List.of(Map.of("value", 1))));
        });
        CompletableFuture<Object> fredTransport = new CompletableFuture<>(); // never completes
        CompletableFuture<Map<String, Object>> fredResponse =
                fredTransport.handle((v, ex) -> Map.of("ok", false, "preview_state", "error"));

        CatalogPreviewService previewService = mock(CatalogPreviewService.class);
        when(previewService.previewAsyncIfSupported(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> payload = invocation.getArgument(0);
            if ("arad".equals(payload.get("source"))) {
                return Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(aradTransport, aradResponse));
            }
            return Optional.of(new CatalogPreviewOrchestrator.AsyncPreviewHandle(fredTransport, fredResponse));
        });

        verifier = new SearchV2PreviewVerifier(previewService, new SearchV2CacheService());
        SearchV2PreviewVerifier.VerificationResult result = verifier.verifyTopOnly(
                List.of(result("mixed-arad", "arad"), result("mixed-fred", "fred")), 2, List.of());

        assertThat(statusFor(result, "mixed-arad").get("ok"))
                .as("ARAD's own SLOW-tier 3000ms budget covers its 400ms real latency")
                .isEqualTo(true);
        assertThat(statusFor(result, "mixed-fred").get("preview_state"))
                .as("FRED's own FAST-tier 150ms budget times out independently of ARAD in the same batch")
                .isEqualTo("timeout");
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
