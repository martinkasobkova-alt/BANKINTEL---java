package cz.bankintel.connector.health;

import cz.bankintel.util.BankIntelEnvVars;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Periodic reachability check of every external data source the connectors depend on.
 *
 * <p>Until this existed, an upstream outage (ČNB/ARAD down, Eurostat rejecting traffic, an expired
 * API key) only surfaced when a user ran a query and got an empty chart. The probe answers one
 * question — "is the upstream endpoint answering us right now?" — and deliberately not "does a real
 * data fetch return correct rows", which would need per-source fixtures and would be far more
 * fragile.
 *
 * <p>Semantics: any HTTP response below 500 counts as {@code UP}. Data APIs commonly answer a bare
 * base URL with 400/404, and that still proves DNS, TLS and the service itself are alive. Only 5xx,
 * timeouts and connection failures are {@code DOWN}.
 */
@Service
public class ConnectorHealthService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorHealthService.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT = "BankIntel-HealthProbe/1.0";

    /**
     * Probe targets. The URLs mirror the base URLs the connectors actually call (see
     * {@code InMemorySourceBuilder} and the per-source support services); {@code file_upload} is
     * local-only and has nothing to probe.
     */
    private static final List<ProbeTarget> TARGETS = List.of(
            new ProbeTarget("arad", "ČNB / ARAD", "https://www.cnb.cz/aradb/api/v1", null),
            new ProbeTarget("csu", "ČSÚ", "https://data.csu.gov.cz/api/katalog/v1", null),
            new ProbeTarget(
                    "eurostat",
                    "Eurostat",
                    "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data",
                    null),
            new ProbeTarget("ecb", "ECB Data Portal", "https://data-api.ecb.europa.eu", null),
            new ProbeTarget("imf", "IMF SDMX 3.0", "https://api.imf.org/external/sdmx/3.0", null),
            new ProbeTarget("bis", "BIS Stats", "https://stats.bis.org/api/v1", null),
            new ProbeTarget("oecd", "OECD SDMX", "https://sdmx.oecd.org/public/rest", null),
            new ProbeTarget(
                    "world_bank_data360", "World Bank Data360", "https://data360api.worldbank.org", null),
            new ProbeTarget("fred", "FRED (St. Louis Fed)", "https://api.stlouisfed.org/fred", "FRED_API_KEY"),
            new ProbeTarget(
                    "worldbank_pink_sheet",
                    "World Bank Pink Sheet",
                    "https://thedocs.worldbank.org",
                    null),
            new ProbeTarget(
                    "tradingeconomics",
                    "TradingEconomics",
                    "https://api.tradingeconomics.com",
                    "TRADING_ECONOMICS_API_KEY"));

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final List<ProbeTarget> targets;
    private final Map<String, ProbeResult> lastResults = new ConcurrentHashMap<>();
    private volatile Instant lastRunAt;

    public ConnectorHealthService() {
        this(TARGETS);
    }

    /** Test seam: lets a hermetic test point the probe at a local server instead of the internet. */
    ConnectorHealthService(List<ProbeTarget> targets) {
        this.targets = List.copyOf(targets);
    }

    /** Runs every probe concurrently. Returns the same shape as {@link #snapshot()}. */
    public Map<String, Object> probeAll() {
        // One short-lived pool per run: probes are rare (hourly) and this keeps no idle threads
        // hanging around between runs.
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(targets.size(), 6));
        try {
            List<CompletableFuture<ProbeResult>> futures = new ArrayList<>();
            for (ProbeTarget target : targets) {
                futures.add(CompletableFuture.supplyAsync(() -> probe(target), pool));
            }
            for (CompletableFuture<ProbeResult> future : futures) {
                ProbeResult result = future.join();
                lastResults.put(result.sourceType(), result);
            }
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        lastRunAt = Instant.now();

        List<String> down = lastResults.values().stream()
                .filter(r -> r.status() == Status.DOWN)
                .map(ProbeResult::sourceType)
                .sorted()
                .toList();
        if (down.isEmpty()) {
            log.info("connector health probe: all {} sources reachable", targets.size());
        } else {
            // WARN so it lands in whatever log alerting exists, rather than only in the endpoint.
            log.warn("connector health probe: {} of {} sources unreachable: {}", down.size(), targets.size(), down);
        }
        return snapshot();
    }

    /** Last known state without touching the network. */
    public Map<String, Object> snapshot() {
        List<ProbeResult> results = new ArrayList<>(lastResults.values());
        results.sort(Comparator.comparing(ProbeResult::sourceType));

        long up = results.stream().filter(r -> r.status() == Status.UP).count();
        long down = results.stream().filter(r -> r.status() == Status.DOWN).count();
        long misconfigured = results.stream().filter(r -> r.status() == Status.MISCONFIGURED).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", overallStatus(results, down));
        out.put("checked_at", lastRunAt == null ? null : lastRunAt.toString());
        out.put("total", targets.size());
        out.put("up", up);
        out.put("down", down);
        out.put("misconfigured", misconfigured);
        out.put("sources", results.stream().map(ProbeResult::toMap).toList());
        return out;
    }

    private static String overallStatus(List<ProbeResult> results, long down) {
        if (results.isEmpty()) {
            return "unknown";
        }
        if (down == 0) {
            return "ok";
        }
        return down == results.size() ? "down" : "degraded";
    }

    private ProbeResult probe(ProbeTarget target) {
        Instant checkedAt = Instant.now();
        // A missing key is a configuration fault, not an upstream outage — keep them apart so an
        // unset key never reads as "the source is down".
        if (target.requiredEnvKey() != null && BankIntelEnvVars.get(target.requiredEnvKey()).isBlank()) {
            return new ProbeResult(
                    target.sourceType(),
                    target.label(),
                    Status.MISCONFIGURED,
                    null,
                    null,
                    checkedAt,
                    target.requiredEnvKey() + " is not configured");
        }
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(target.url()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            long latencyMs = elapsedMs(started);
            boolean reachable = response.statusCode() < 500;
            return new ProbeResult(
                    target.sourceType(),
                    target.label(),
                    reachable ? Status.UP : Status.DOWN,
                    response.statusCode(),
                    latencyMs,
                    checkedAt,
                    reachable ? null : "upstream returned HTTP " + response.statusCode());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ProbeResult(
                    target.sourceType(),
                    target.label(),
                    Status.DOWN,
                    null,
                    elapsedMs(started),
                    checkedAt,
                    "probe interrupted");
        } catch (Exception ex) {
            return new ProbeResult(
                    target.sourceType(),
                    target.label(),
                    Status.DOWN,
                    null,
                    elapsedMs(started),
                    checkedAt,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    public enum Status {
        UP,
        DOWN,
        MISCONFIGURED
    }

    public record ProbeTarget(String sourceType, String label, String url, String requiredEnvKey) {}

    public record ProbeResult(
            String sourceType,
            String label,
            Status status,
            Integer httpStatus,
            Long latencyMs,
            Instant checkedAt,
            String detail) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("source_type", sourceType);
            map.put("label", label);
            map.put("status", status.name().toLowerCase());
            map.put("http_status", httpStatus);
            map.put("latency_ms", latencyMs);
            map.put("checked_at", checkedAt == null ? null : checkedAt.toString());
            map.put("detail", detail);
            return map;
        }
    }
}
