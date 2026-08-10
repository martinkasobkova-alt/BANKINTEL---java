package cz.bankintel.search.v2.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2TelemetryEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void eventSerializesToJsonWithoutThrowingAndPreservesKeyFields() throws Exception {
        SearchV2TelemetryEvent event = SearchV2TelemetryEventBuilder.buildError("req-9", "hdp cesko", "boom happened");

        String json = objectMapper.writeValueAsString(event.toMap());
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("schema_version").asText()).isEqualTo("1");
        assertThat(node.get("request_id").asText()).isEqualTo("req-9");
        assertThat(node.get("normalized_query").asText()).isEqualTo("hdp cesko");
        assertThat(node.get("final_response_path").asText()).isEqualTo("error");
        assertThat(node.get("planner_path").isNull()).isTrue();
        assertThat(node.get("candidates").isArray()).isTrue();
        assertThat(node.get("candidates")).isEmpty();
    }

    @Test
    void toMapNullFieldsSerializeAsJsonNullNotAsMissingOrZero() throws Exception {
        SearchV2TelemetryEvent.TimingTelemetry timings =
                new SearchV2TelemetryEvent.TimingTelemetry(null, null, 42L, null, null, null, null, null, null);
        SearchV2TelemetryEvent event = new SearchV2TelemetryEvent(
                "1",
                1L,
                "req-1",
                "q",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "deterministic_story",
                timings,
                List.of());

        Map<String, Object> map = event.toMap();
        String json = objectMapper.writeValueAsString(map);
        JsonNode node = objectMapper.readTree(json);
        JsonNode timingsNode = node.get("timings");

        assertThat(timingsNode.get("planner_ms").isNull()).isTrue();
        assertThat(timingsNode.get("retrieval_ms").asLong()).isEqualTo(42L);
        assertThat(timingsNode.has("coverage_ms")).isTrue();
        assertThat(timingsNode.get("coverage_ms").isNull()).isTrue();
    }
}
