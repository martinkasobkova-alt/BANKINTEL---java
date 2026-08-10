package cz.bankintel.explore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExploreTraceEnvelopeTest {

    @Test
    void exposesTheSameCompleteTerminalContractForRestAndSse() {
        Map<String, Object> body = Map.of(
                "ok", true,
                "cache_hit", true,
                "serving_time_ms", 12,
                "cached_compute_time_ms", 34,
                "total_candidates", 21,
                "performance_profile", Map.of(
                        "sources_requested", List.of("ecb2", "eurostat"),
                        "preview_count", 7,
                        "verified_count", 2));

        Map<String, Object> trace = ExploreTraceEnvelope.enrich(
                body, "request", "run", 1, Map.of("ecb2", "ok", "eurostat", "empty"), "completed");

        assertThat(trace).containsEntry("request_id", "request")
                .containsEntry("discovery_run_id", "run")
                .containsEntry("full_discovery_run_count", 1)
                .containsEntry("cache_hit", true)
                .containsEntry("fallback_reason", null)
                .containsEntry("serving_time_ms", 12L)
                .containsEntry("cached_compute_time_ms", 34L)
                .containsEntry("source_routing", List.of("ecb2", "eurostat"))
                .containsEntry("candidate_count", 21L)
                .containsEntry("preview_count", 7L)
                .containsEntry("validator_outcome", "verified")
                .containsEntry("terminal_status", "completed");
    }

    @Test
    void fallbackAndEmptyValidationRemainExplicit() {
        Map<String, Object> trace = ExploreTraceEnvelope.forRest(Map.of(
                "ok", true,
                "total_candidates", 0,
                "discovery_fallback_reason", "catalog_discovery_empty",
                "performance_profile", Map.of(
                        "fallback_preview_count", 0,
                        "fallback_validator_outcome", "not_run")));

        assertThat(trace).containsEntry("full_discovery_run_count", 1)
                .containsEntry("fallback_reason", "catalog_discovery_empty")
                .containsEntry("candidate_count", 0L)
                .containsEntry("preview_count", 0L)
                .containsEntry("validator_outcome", "not_run");
    }

    @Test
    void derivesRoutingAndPreviewCountFromTheExistingPerformanceProfile() {
        Map<String, Object> trace = ExploreTraceEnvelope.forRest(Map.of(
                "ok", true,
                "total_candidates", 4,
                "performance_profile", Map.of(
                        "sources_requested", 2,
                        "source_statuses", List.of(Map.of("source", "ecb2"), Map.of("source", "eurostat")),
                        "preview_initial", Map.of("items", 3),
                        "verified_count", 1)));

        assertThat(trace).containsEntry("source_routing", List.of("ecb2", "eurostat"))
                .containsEntry("preview_count", 3L)
                .containsEntry("validator_outcome", "verified");
    }
}
