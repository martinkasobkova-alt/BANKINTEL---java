package cz.bankintel.search.v2.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchV2TelemetryWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void disabledWriterNeverQueuesOrCountsAnything() {
        SearchV2TelemetryWriter writer =
                new SearchV2TelemetryWriter(objectMapper, false, 10, tempDir.resolve("disabled.jsonl"));

        writer.submit(minimalEvent());
        writer.recordFinalCacheHit();

        assertThat(writer.enabled()).isFalse();
        assertThat(writer.queuedCount()).isZero();
        assertThat(writer.droppedCount()).isZero();
        assertThat(writer.finalCacheHitCount()).isZero();
    }

    @Test
    void boundedQueueDropsExcessEventsAndCountsThemInsteadOfBlocking() {
        // Capacity 2, writer thread never started: submit() must never block the calling thread even
        // though nothing drains the queue.
        SearchV2TelemetryWriter writer =
                new SearchV2TelemetryWriter(objectMapper, true, 2, tempDir.resolve("overflow.jsonl"));

        for (int i = 0; i < 5; i++) {
            writer.submit(minimalEvent());
        }

        assertThat(writer.queuedCount()).isEqualTo(2);
        assertThat(writer.droppedCount()).isEqualTo(3);
        assertThat(writer.writtenCount()).isZero();
    }

    @Test
    void serializationFailureIsCountedAndNeverThrowsToTheCaller() throws JsonProcessingException {
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        when(brokenMapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("boom"));
        SearchV2TelemetryWriter writer =
                new SearchV2TelemetryWriter(brokenMapper, true, 10, tempDir.resolve("broken.jsonl"));

        writer.submit(minimalEvent());

        assertThat(writer.serializationErrorCount()).isEqualTo(1);
        assertThat(writer.queuedCount()).isZero();
        assertThat(writer.droppedCount()).isZero();
    }

    @Test
    void startedWriterDrainsQueueToFileAsJsonLines() throws IOException {
        Path logFile = tempDir.resolve("written.jsonl");
        SearchV2TelemetryWriter writer = new SearchV2TelemetryWriter(objectMapper, true, 100, logFile);
        writer.start();
        try {
            writer.submit(minimalEvent());
            writer.submit(minimalEvent());

            awaitWrittenCount(writer, 2);
        } finally {
            writer.shutdown();
        }

        List<String> lines = Files.readAllLines(logFile);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"schema_version\":\"1\"").contains("\"request_id\":\"req-1\"");
    }

    @Test
    void enabledWriterCountsFinalCacheHits() {
        SearchV2TelemetryWriter writer =
                new SearchV2TelemetryWriter(objectMapper, true, 10, tempDir.resolve("cache-hits.jsonl"));

        writer.recordFinalCacheHit();
        writer.recordFinalCacheHit();

        assertThat(writer.finalCacheHitCount()).isEqualTo(2);
    }

    private static SearchV2TelemetryEvent minimalEvent() {
        return SearchV2TelemetryEventBuilder.buildError("req-1", "test query", "boom");
    }

    /** Polls the background writer thread's progress instead of depending on a fixed sleep. */
    private static void awaitWrittenCount(SearchV2TelemetryWriter writer, long expected) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (writer.writtenCount() >= expected) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for telemetry writer", ex);
            }
        }
        throw new AssertionError(
                "telemetry writer did not reach writtenCount=" + expected + " within 5s (was " + writer.writtenCount() + ")");
    }
}
