package cz.bankintel.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogSourceYieldTelemetryTest {

    @Test
    void keepsIndependentRollingWindowsForSourceAndQueryShape() {
        CatalogSourceYieldTelemetry telemetry = new CatalogSourceYieldTelemetry();

        telemetry.observe("metric_geo", List.of(status("source-a", 100, 4, 1, false)));
        Map<String, Map<String, Object>> second =
                telemetry.observe("metric_geo", List.of(status("source-a", 300, 0, 0, true)));
        Map<String, Map<String, Object>> otherShape =
                telemetry.observe("open_topic", List.of(status("source-a", 50, 2, 1, false)));

        assertThat(second.get("source-a"))
                .containsEntry("sample_count", 2)
                .containsEntry("latency_p50_ms", 100L)
                .containsEntry("latency_p95_ms", 300L)
                .containsEntry("empty_rate", 0.5)
                .containsEntry("mean_candidate_yield", 2.0)
                .containsEntry("mean_verified_yield", 0.5)
                .containsEntry("decision_mode", "observe_only");
        assertThat(otherShape.get("source-a"))
                .containsEntry("sample_count", 1)
                .containsEntry("latency_p50_ms", 50L);
    }

    private static Map<String, Object> status(
            String source, long durationMs, int candidates, int verified, boolean empty) {
        return Map.of(
                "source", source,
                "active_work_ms", durationMs,
                "candidate_count", candidates,
                "verified_yield", verified,
                "empty_result", empty,
                "terminal_status", empty ? "empty" : "completed",
                "local_index_call_count", 1,
                "sidecar_seed_count", candidates);
    }
}
