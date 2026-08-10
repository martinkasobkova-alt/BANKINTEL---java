package cz.bankintel.search.v2.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.util.BankIntelEnvVars;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Async, bounded-queue JSON-lines writer for {@link SearchV2TelemetryEvent}.
 *
 * <p>Design intent, matching the PR-1 constraints:
 * <ul>
 *   <li>{@link #submit(SearchV2TelemetryEvent)} never blocks the calling (request) thread on disk
 *       I/O — it only serializes the event (in-memory, no I/O) and offers it to a bounded queue.</li>
 *   <li>When the queue is full, the event is dropped and a counter is incremented — the request is
 *       never slowed down or blocked to make room.</li>
 *   <li>A single background thread does the actual (blocking) file write, sequentially.</li>
 *   <li>Every failure mode (serialization error, write error, queue full) is caught internally and
 *       only surfaces via SLF4J warnings/errors and in-memory counters — it can never propagate back
 *       to a caller and break a search request.</li>
 * </ul>
 *
 * <p>A hand-rolled queue+thread was chosen over Logback's {@code AsyncAppender} because this class
 * needs to be exhaustively unit-testable (deterministic overflow behavior, exact drop counting)
 * without depending on Logback's internal, timing-sensitive queue semantics.
 */
@Service
public class SearchV2TelemetryWriter {

    private static final Logger log = LoggerFactory.getLogger(SearchV2TelemetryWriter.class);
    private static final int DEFAULT_QUEUE_CAPACITY = 2000;
    private static final int MIN_QUEUE_CAPACITY = 50;
    private static final int MAX_QUEUE_CAPACITY = 20_000;
    private static final long POLL_TIMEOUT_MS = 500L;
    private static final long SHUTDOWN_DRAIN_TIMEOUT_MS = 2_000L;
    private static final long DROP_LOG_EVERY_N = 500L;

    private final boolean enabled;
    private final Path logFilePath;
    private final BlockingQueue<String> queue;
    private final ObjectMapper objectMapper;

    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong writtenCount = new AtomicLong();
    private final AtomicLong serializationErrorCount = new AtomicLong();
    private final AtomicLong writeErrorCount = new AtomicLong();
    private final AtomicLong finalCacheHitCount = new AtomicLong();

    private volatile Thread writerThread;
    private volatile boolean running;

    @Autowired
    public SearchV2TelemetryWriter(ObjectMapper objectMapper) {
        this(objectMapper, configuredEnabled(), configuredCapacity(), configuredLogPath());
    }

    /** Package-visible constructor for deterministic, Spring-free unit tests. */
    SearchV2TelemetryWriter(ObjectMapper objectMapper, boolean enabled, int queueCapacity, Path logFilePath) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.logFilePath = logFilePath;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Serializes and enqueues the event. Never throws, never blocks on I/O. Safe to call
     * unconditionally, but callers should still check {@link #enabled()} first to skip building
     * the event entirely when telemetry is off.
     */
    public void submit(SearchV2TelemetryEvent event) {
        if (event == null) {
            return;
        }
        submitRaw(event.toMap());
    }

    /**
     * Generic entry point reused by finer-grained telemetry (e.g. per-attempt preview lifecycle
     * events from {@link cz.bankintel.search.v2.orchestration.SearchV2PreviewVerifier}), so they
     * share the exact same bounded-queue/drop/error-counting behavior as {@link SearchV2TelemetryEvent}
     * without needing their own writer.
     */
    public void submitRaw(Map<String, Object> payload) {
        if (!enabled || payload == null) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            serializationErrorCount.incrementAndGet();
            log.warn("search v2 telemetry: failed to serialize event, dropping it: {}", ex.getMessage());
            return;
        }
        boolean offered = queue.offer(json);
        if (!offered) {
            long dropped = droppedCount.incrementAndGet();
            if (dropped == 1 || dropped % DROP_LOG_EVERY_N == 0) {
                log.warn(
                        "search v2 telemetry: queue full (capacity reached), dropping event "
                                + "(dropped_total={})",
                        dropped);
            }
        }
    }

    public void recordFinalCacheHit() {
        if (!enabled) {
            return;
        }
        finalCacheHitCount.incrementAndGet();
    }

    public long droppedCount() {
        return droppedCount.get();
    }

    public long writtenCount() {
        return writtenCount.get();
    }

    public long serializationErrorCount() {
        return serializationErrorCount.get();
    }

    public long writeErrorCount() {
        return writeErrorCount.get();
    }

    public long finalCacheHitCount() {
        return finalCacheHitCount.get();
    }

    public int queuedCount() {
        return queue.size();
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("search v2 telemetry disabled (SEARCH_V2_TELEMETRY_ENABLED not truthy); writer not started");
            return;
        }
        try {
            Path parent = logFilePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException ex) {
            log.error(
                    "search v2 telemetry: could not create directory for {}, telemetry writer disabled for this run: {}",
                    logFilePath,
                    ex.getMessage());
            return;
        }
        running = true;
        writerThread = new Thread(this::runLoop, "search-v2-telemetry-writer");
        writerThread.setDaemon(true);
        writerThread.start();
        log.info("search v2 telemetry writer started, log path={}, queue_capacity={}", logFilePath, queue.remainingCapacity());
    }

    @PreDestroy
    void shutdown() {
        running = false;
        Thread thread = writerThread;
        if (thread == null) {
            return;
        }
        try {
            thread.join(SHUTDOWN_DRAIN_TIMEOUT_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void runLoop() {
        try (BufferedWriter writer = Files.newBufferedWriter(
                logFilePath, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            while (running || !queue.isEmpty()) {
                String line;
                try {
                    line = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (line == null) {
                    continue;
                }
                writeLine(writer, line);
            }
        } catch (IOException ex) {
            log.error("search v2 telemetry: could not open log file {}, writer stopped: {}", logFilePath, ex.getMessage());
        }
    }

    private void writeLine(BufferedWriter writer, String line) {
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
            writtenCount.incrementAndGet();
        } catch (IOException ex) {
            writeErrorCount.incrementAndGet();
            log.error("search v2 telemetry: failed to write event line: {}", ex.getMessage());
        }
    }

    private static boolean configuredEnabled() {
        return BankIntelEnvVars.isTruthy("SEARCH_V2_TELEMETRY_ENABLED");
    }

    private static int configuredCapacity() {
        String raw = BankIntelEnvVars.get("SEARCH_V2_TELEMETRY_QUEUE_CAPACITY");
        int parsed = DEFAULT_QUEUE_CAPACITY;
        if (!raw.isBlank()) {
            try {
                parsed = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                parsed = DEFAULT_QUEUE_CAPACITY;
            }
        }
        return Math.max(MIN_QUEUE_CAPACITY, Math.min(MAX_QUEUE_CAPACITY, parsed));
    }

    private static Path configuredLogPath() {
        String configured = BankIntelEnvVars.get("SEARCH_V2_TELEMETRY_LOG_PATH");
        if (!configured.isBlank()) {
            return Path.of(configured);
        }
        String dataDir = BankIntelEnvVars.get("BANKINTEL_DATA_DIR");
        Path base = dataDir.isBlank() ? Path.of("data") : Path.of(dataDir);
        return base.resolve("search_v2_telemetry").resolve("search-v2-telemetry.jsonl");
    }
}
