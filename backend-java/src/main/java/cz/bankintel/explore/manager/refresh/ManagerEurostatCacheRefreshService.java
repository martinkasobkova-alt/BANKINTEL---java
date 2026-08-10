package cz.bankintel.explore.manager.refresh;

import cz.bankintel.connector.AsyncCancellableFetch.AsyncFetchHandle;
import cz.bankintel.connector.ConnectorFetchResult;
import cz.bankintel.connector.EurostatConnector;
import cz.bankintel.explore.manager.refresh.ManagerEurostatRefreshTargetBuilder.RefreshTarget;
import cz.bankintel.sources.eurostat.EurostatRateLimiter;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a native-Java Eurostat refresh of {@code manager_series_cache}: curated bundle
 * rows ({@link ManagerSegmentBundleLoader}) -> per-country targets ({@link
 * ManagerEurostatRefreshTargetBuilder}) -> bounded-concurrency live fetch (this class) -> cache
 * documents ({@link ManagerSeriesCacheDocBuilder}) -> Mongo upsert ({@link
 * ManagerSeriesCacheWriter}).
 *
 * <p>Fetch concurrency/timeout/retry mirrors the legacy Python script's own numbers for parity
 * (concurrency 6, 45s per-target timeout, 1 retry) and the bounded-wave shape already proven in
 * {@code ExploreSummarizeFetchService.fetchBatch} - a wave's per-item futures are harvested
 * strictly in order, one item's failure/timeout never aborts the rest of the batch.
 */
@Service
@RequiredArgsConstructor
public class ManagerEurostatCacheRefreshService {

    private static final Logger log = LoggerFactory.getLogger(ManagerEurostatCacheRefreshService.class);

    private static final String EUROSTAT_BASE_URL =
            "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data";
    private static final int FETCH_CONCURRENCY = 6;
    private static final long FETCH_ITEM_TIMEOUT_SEC = 45;
    private static final long RATE_LIMITER_ACQUIRE_TIMEOUT_SEC = 15;
    private static final int MAX_ATTEMPTS = 2; // 1 initial + 1 retry, matching the Python script's --retries default.

    /** {@code wave_status} thresholds, identical to the Python script's
     * {@code _evaluate_wave_status} (minus the "one source's failure share" check, which is
     * meaningless for a single-source-only job - every target here IS Eurostat). */
    private static final double BAD_RATIO_STOP_THRESHOLD = 0.20;
    private static final double STALE_RATIO_STOP_THRESHOLD = 0.35;

    private final ManagerSegmentBundleLoader bundleLoader;
    private final ManagerEurostatRefreshTargetBuilder targetBuilder;
    private final ManagerSeriesCacheWriter writer;
    private final EurostatConnector eurostatConnector;
    private final EurostatRateLimiter rateLimiter;
    private final ExecutorService fetchExecutor = Executors.newFixedThreadPool(FETCH_CONCURRENCY);

    @PreDestroy
    void shutdownFetchExecutor() {
        fetchExecutor.shutdown();
    }

    public record RefreshReport(
            int targets, int loaded, int unavailable, int staleSuspicious, int written, String waveStatus, List<String> stopReasons) {}

    /**
     * @param segmentIds which segment bundles to refresh; empty/null means every segment.
     * @param geos the requested target countries (e.g. Phase 1's {IT,DE,CZ} or {@link
     *     cz.bankintel.explore.EuMembership#ISO2_CODES} for a full run) - actually fetched geos
     *     are narrowed per-row to {@code geos ∩ row.geo_coverage}, never blindly all of them.
     * @param dryRun when true, fetches and classifies everything but skips the Mongo write
     *     entirely - used for Phase 1 correctness verification before touching any collection.
     * @param collectionName target Mongo collection - a shadow collection for Phase 1 live-write
     *     verification, the real {@code manager_series_cache} from Phase 2 onward.
     */
    public RefreshReport refresh(Collection<String> segmentIds, Set<String> geos, boolean dryRun, String collectionName) {
        List<Map<String, Object>> rows = bundleLoader.eurostatRowsForSegments(segmentIds);
        List<RefreshTarget> targets = targetBuilder.buildTargets(rows, geos);
        if (targets.isEmpty()) {
            log.warn("eurostat cache refresh found 0 targets for segments={} geos={}", segmentIds, geos);
            return new RefreshReport(0, 0, 0, 0, 0, "PASS", List.of());
        }
        Instant now = Instant.now();
        List<Document> docs = fetchAllWaves(targets, now);

        int loaded = 0;
        int unavailable = 0;
        int staleSuspicious = 0;
        for (Document doc : docs) {
            String freshness = doc.getString("freshness");
            if ("unavailable".equals(freshness)) {
                unavailable++;
            } else {
                loaded++;
                if ("stale_suspicious".equals(freshness)) {
                    staleSuspicious++;
                }
            }
        }

        List<String> stopReasons = new ArrayList<>();
        double badRatio = (double) unavailable / targets.size();
        double staleRatio = (double) staleSuspicious / targets.size();
        if (badRatio > BAD_RATIO_STOP_THRESHOLD) {
            stopReasons.add(String.format("failed+unavailable %.1f%% > 20%%", badRatio * 100));
        }
        if (staleRatio > STALE_RATIO_STOP_THRESHOLD) {
            stopReasons.add(String.format("stale_suspicious %.1f%% > 35%%", staleRatio * 100));
        }
        String waveStatus = stopReasons.isEmpty() ? "PASS" : "STOPPED";

        int written = dryRun ? 0 : writer.upsertBatch(collectionName, docs);
        log.info(
                "eurostat cache refresh done: targets={} loaded={} unavailable={} stale_suspicious={} written={} dryRun={} waveStatus={}",
                targets.size(),
                loaded,
                unavailable,
                staleSuspicious,
                written,
                dryRun,
                waveStatus);
        return new RefreshReport(targets.size(), loaded, unavailable, staleSuspicious, written, waveStatus, stopReasons);
    }

    private List<Document> fetchAllWaves(List<RefreshTarget> targets, Instant now) {
        List<Document> docs = new ArrayList<>(targets.size());
        int index = 0;
        while (index < targets.size()) {
            int waveEnd = Math.min(targets.size(), index + FETCH_CONCURRENCY);
            List<RefreshTarget> wave = targets.subList(index, waveEnd);
            List<CompletableFuture<Document>> futures = wave.stream()
                    .map(target -> CompletableFuture.supplyAsync(() -> fetchOneWithRetry(target, now), fetchExecutor))
                    .toList();
            for (int i = 0; i < wave.size(); i++) {
                docs.add(awaitDoc(futures.get(i), wave.get(i), now));
            }
            index = waveEnd;
        }
        return docs;
    }

    private Document awaitDoc(CompletableFuture<Document> future, RefreshTarget target, Instant now) {
        try {
            // Generous outer bound: fetchOneWithRetry already bounds itself (rate-limiter wait +
            // per-attempt fetch timeout, x2 attempts) to well under this - this is only a defensive
            // backstop so one pathological target can never hang an entire wave indefinitely.
            return future.get(FETCH_ITEM_TIMEOUT_SEC * MAX_ATTEMPTS + 30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            future.cancel(true);
            log.warn(
                    "eurostat cache refresh target timed out at the outer bound: series_id={} geo={}: {}",
                    target.seriesId(),
                    target.geo(),
                    ex.getMessage());
            return ManagerSeriesCacheDocBuilder.buildUnavailableDoc(target, "outer_timeout", now);
        }
    }

    private Document fetchOneWithRetry(RefreshTarget target, Instant now) {
        String lastFailureReason = "fetch_failed";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                List<Map<String, Object>> observations = fetchObservations(target);
                if (observations.isEmpty()) {
                    lastFailureReason = "no_observations";
                    continue;
                }
                String latestPeriod = str(observations.get(observations.size() - 1).get("period"));
                if (ManagerSeriesCacheDocBuilder.isStaleByHardYearGate(latestPeriod, now)) {
                    return ManagerSeriesCacheDocBuilder.buildUnavailableDoc(
                            target, "stale_data (poslední hodnota " + latestPeriod + ")", now);
                }
                Document doc = ManagerSeriesCacheDocBuilder.buildLoadedDoc(target, observations, now);
                if (doc != null) {
                    return doc;
                }
                lastFailureReason = "no_observations";
            } catch (Exception ex) {
                lastFailureReason = "fetch_error: " + ex.getMessage();
                log.debug(
                        "eurostat cache refresh attempt {}/{} failed for series_id={} geo={}: {}",
                        attempt,
                        MAX_ATTEMPTS,
                        target.seriesId(),
                        target.geo(),
                        ex.getMessage());
            }
        }
        return ManagerSeriesCacheDocBuilder.buildUnavailableDoc(target, lastFailureReason, now);
    }

    private List<Map<String, Object>> fetchObservations(RefreshTarget target) throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("base_url", EUROSTAT_BASE_URL);
        source.put("endpoint", "/" + target.datasetId());
        source.put("query_params", target.queryParams());
        source.put("headers", Map.of("Accept", "application/json"));

        boolean acquired = false;
        try {
            acquired = rateLimiter.tryAcquire(RATE_LIMITER_ACQUIRE_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!acquired) {
                return List.of();
            }
            AsyncFetchHandle handle = eurostatConnector.fetchAsync(source);
            ConnectorFetchResult result;
            try {
                result = handle.resultFuture().get(FETCH_ITEM_TIMEOUT_SEC, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                handle.transportFuture().cancel(true);
                return List.of();
            }
            if (!result.isSuccess()) {
                return List.of();
            }
            List<Map<String, Object>> rows = eurostatConnector.parse(result.raw(), source);
            return normalizeObservations(rows);
        } finally {
            if (acquired) {
                rateLimiter.release();
            }
        }
    }

    private static List<Map<String, Object>> normalizeObservations(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            Object value = row.get("value");
            if (value == null) {
                value = row.get("obs_value");
            }
            Double num = toDouble(value);
            if (num == null) {
                continue;
            }
            String period = firstNonBlank(row.get("date"), row.get("period"), row.get("time"), row.get("TIME_PERIOD"));
            if (period.isBlank()) {
                continue;
            }
            Map<String, Object> obs = new LinkedHashMap<>();
            obs.put("period", period);
            obs.put("date", period);
            obs.put("value", num);
            out.add(obs);
        }
        out.sort(Comparator.comparing(o -> str(o.get("period"))));
        return out;
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim().replace(",", "."));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = str(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
