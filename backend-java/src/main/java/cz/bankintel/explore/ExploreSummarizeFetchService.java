package cz.bankintel.explore;

import cz.bankintel.connector.ConnectorFactory;
import cz.bankintel.explore.ExploreDtos.ExploreSummarizeSeriesItem;
import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.CatalogPreviewOrchestrator;
import cz.bankintel.search.CatalogSourceRegistry;
import cz.bankintel.explore.manager.ManagerSeriesCacheReader;
import cz.bankintel.explore.manager.fetch.ManagerFetchRegistry;
import cz.bankintel.explore.manager.fetch.ManagerMirrorFetchSupport;
import cz.bankintel.search.CatalogTextUtils;
import jakarta.annotation.PreDestroy;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Fetch řad pro Manager Explorer summarize — FTS hydratace + katalogový preview fetch. */
@Service
@RequiredArgsConstructor
public class ExploreSummarizeFetchService {

    private static final Logger log = LoggerFactory.getLogger(ExploreSummarizeFetchService.class);
    private static final int DEFAULT_MAX_SERIES = 14;
    private static final int CHART_POINT_LIMIT = 120;
    // Bounded, not unlimited: each fetchOne() can hit an external connector (ARAD/ECB/FRED/...),
    // so fanning out all 14 at once would open 14 concurrent outbound HTTP calls per summarize
    // job. 6 matches the concurrency already used for preview verification in
    // CatalogDeepSearchPreviewService (DEFAULT_PREVIEW_CONCURRENCY) against the same connector layer.
    private static final int FETCH_CONCURRENCY = 6;
    private static final long DEFAULT_FETCH_ITEM_TIMEOUT_SEC = 25;

    private final CatalogIndexStore indexStore;
    private final CatalogPreviewOrchestrator previewOrchestrator;
    private final ConnectorFactory connectorFactory;
    private final ManagerSeriesCacheReader managerSeriesCacheReader;
    private final ManagerFetchRegistry managerFetchRegistry;
    private final ExecutorService fetchExecutor = Executors.newFixedThreadPool(FETCH_CONCURRENCY);
    // Not final: tests shrink this to exercise the timeout path without a real 25s wait. Production
    // code never changes it from the default.
    private long fetchItemTimeoutSec = DEFAULT_FETCH_ITEM_TIMEOUT_SEC;

    /** Test-only seam - see {@link #fetchItemTimeoutSec}. */
    void setFetchItemTimeoutSecForTest(long seconds) {
        this.fetchItemTimeoutSec = seconds;
    }

    @PreDestroy
    void shutdownFetchExecutor() {
        fetchExecutor.shutdown();
        try {
            if (!fetchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                fetchExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fetchExecutor.shutdownNow();
        }
    }

    public BatchResult fetchBatch(List<ExploreSummarizeSeriesItem> items, String country) {
        return fetchBatch(items, country, DEFAULT_MAX_SERIES);
    }

    /**
     * Fetches each selected series' observations, in bounded-concurrency waves of {@link
     * #FETCH_CONCURRENCY}, instead of one at a time. A wave's results are harvested (and appended
     * to {@code loaded}/{@code failed}) strictly in the original item order, so callers see the
     * exact same ordering as the old sequential loop - only the wall-clock cost of a wave drops
     * from sum-of-fetches to slowest-fetch-in-the-wave. Waves stop once {@code loaded} reaches
     * {@code cap}, preserving the original "stop once we have enough successful series" rule (at
     * wave granularity rather than per-item - the last wave may fetch a few more items than
     * strictly necessary if early ones in it succeed, which is the deliberate, minor trade-off for
     * real parallelism instead of a fully adaptive one-at-a-time check).
     */
    public BatchResult fetchBatch(List<ExploreSummarizeSeriesItem> items, String country, int maxSeries) {
        List<Map<String, Object>> loaded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        int cap = Math.max(1, Math.min(maxSeries, DEFAULT_MAX_SERIES));
        if (items == null || items.isEmpty()) {
            return new BatchResult(loaded, failed, summary(0, 0, 0, indexStore.ftsDbAvailable()));
        }
        int index = 0;
        while (index < items.size() && loaded.size() < cap) {
            int waveEnd = Math.min(items.size(), index + FETCH_CONCURRENCY);
            List<ExploreSummarizeSeriesItem> wave = items.subList(index, waveEnd);
            List<CompletableFuture<Optional<Map<String, Object>>>> futures = wave.stream()
                    .map(item -> CompletableFuture.supplyAsync(() -> fetchOneSafely(item, country), fetchExecutor))
                    .toList();
            for (int i = 0; i < wave.size(); i++) {
                ExploreSummarizeSeriesItem item = wave.get(i);
                Optional<Map<String, Object>> fetched = awaitFetch(futures.get(i), item);
                if (fetched.isPresent()) {
                    loaded.add(fetched.get());
                } else {
                    Map<String, Object> fail = new LinkedHashMap<>();
                    fail.put("source_type", item.sourceType());
                    fail.put("set_id", item.setId());
                    fail.put("title", item.title());
                    fail.put("reason", "fetch_failed_or_insufficient_points");
                    failed.add(fail);
                }
            }
            index = waveEnd;
        }
        return new BatchResult(loaded, failed, summary(items.size(), loaded.size(), failed.size(), indexStore.ftsDbAvailable()));
    }

    /** One series' failure (thrown exception, or a timeout) must never abort the rest of the batch. */
    private Optional<Map<String, Object>> fetchOneSafely(ExploreSummarizeSeriesItem item, String country) {
        try {
            return fetchOne(refFromItem(item), country);
        } catch (Exception ex) {
            log.warn(
                    "summarize fetch threw unexpectedly source_type={} set_id={}: {}",
                    item.sourceType(),
                    item.setId(),
                    ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Map<String, Object>> awaitFetch(
            CompletableFuture<Optional<Map<String, Object>>> future, ExploreSummarizeSeriesItem item) {
        try {
            return future.get(fetchItemTimeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            log.warn(
                    "summarize fetch timed out after {}s source_type={} set_id={}",
                    fetchItemTimeoutSec,
                    item.sourceType(),
                    item.setId());
            return Optional.empty();
        } catch (Exception ex) {
            log.warn(
                    "summarize fetch failed source_type={} set_id={}: {}", item.sourceType(), item.setId(), ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Map<String, Object>> fetchOne(Map<String, Object> ref, String country) {
        Map<String, Object> hydrated = hydrateFromIndex(ref);
        String sourceType = str(hydrated.get("source_type"));
        String setId = str(hydrated.get("set_id"));
        if (sourceType.isBlank() || setId.isBlank()) {
            return Optional.empty();
        }
        String contextGeo = firstNonBlank(country, resolveRefGeo(hydrated));
        Optional<List<Map<String, Object>>> cacheObs = managerSeriesCacheReader.readObservations(hydrated, contextGeo);
        if (cacheObs.isPresent() && cacheObs.get().size() >= 2) {
            return buildLoadedItem(hydrated, sourceType, setId, cacheObs.get(), "manager_series_cache", contextGeo);
        }
        // Specialized domain fetchers (ACEA/EBA/EIOPA/ENTSOE/GIE/FM/oecd4) — ref *_manager_fetch.py
        Optional<List<Map<String, Object>>> mirrorObs = managerFetchRegistry.tryFetch(hydrated, contextGeo);
        if (mirrorObs.isPresent()) {
            List<Map<String, Object>> rows = mirrorObs.get();
            if (ManagerMirrorFetchSupport.isMirrorUnavailable(rows)) {
                return buildMirrorUnavailableItem(hydrated, sourceType, setId, rows.get(0));
            }
            if (rows.size() >= 2) {
                return buildLoadedItem(hydrated, sourceType, setId, rows, "manager_mirror_fetch", contextGeo);
            }
        }
        String connectorType = connectorSourceType(sourceType);
        if (!connectorFactory.isSupported(connectorType)) {
            log.debug("summarize fetch unsupported source_type={} set_id={}", sourceType, setId);
            return Optional.empty();
        }
        Map<String, Object> payload = buildPreviewPayload(hydrated, connectorType, country);
        try {
            List<Map<String, Object>> rows = previewOrchestrator.fetchRecords(payload);
            List<Map<String, Object>> observations = normalizeObservations(rows);
            if (observations.size() < 2) {
                return Optional.empty();
            }
            if (isStaleSeries(observations)) {
                log.debug("summarize fetch skipped stale series {}/{}", sourceType, setId);
                return Optional.empty();
            }
            return buildLoadedItem(
                    hydrated,
                    sourceType,
                    setId,
                    observations,
                    indexStore.ftsDbAvailable() ? "fts_plus_connector" : "jsonl_plus_connector",
                    contextGeo);
        } catch (Exception ex) {
            log.warn("summarize fetch failed {}/{}: {}", sourceType, setId, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Map<String, Object>> buildLoadedItem(
            Map<String, Object> hydrated,
            String sourceType,
            String setId,
            List<Map<String, Object>> observations,
            String fetchSource,
            String contextGeo) {
        Map<String, Object> metrics = computeMetrics(observations);
        List<Map<String, Object>> chartPoints = chartPoints(observations);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", firstNonBlank(hydrated.get("title"), hydrated.get("indicator_name"), setId));
        out.put("source_type", sourceType);
        out.put("set_id", setId);
        out.put("dataset_id", setId);
        out.put("query_params", hydrated.getOrDefault("query_params", Map.of()));
        out.put("summarize_fetch_source", fetchSource);
        copyRefMetadata(hydrated, out);
        // The country actually used to fetch/filter this series (e.g. a multi-country Eurostat
        // panel dataset resolved to just Germany/Italy) never lived in query_params - it was
        // applied out-of-band via buildPreviewPayload's own "country" key - so without this, the
        // "open interactive chart" re-fetch (ExploreInteractiveSeriesDetail ->
        // buildExploreInteractiveCatalogContext, which reads primary_country_code/geo from this
        // same row) had nothing to go on and silently defaulted to the shared catalog preview's
        // own fallback country instead of the geo this analysis actually used.
        if (!str(contextGeo).isBlank() && str(hydrated.get("primary_country_code")).isBlank()) {
            out.put("primary_country_code", contextGeo);
        }
        out.put("observations", observations);
        out.put("chart_points", chartPoints);
        out.put("metrics", metrics);
        out.put("latest_value", metrics.get("latest_value"));
        out.put("latest_observation_date", metrics.get("latest_observation_date"));
        out.put("trend_direction", metrics.get("trend_direction"));
        out.put("data_context_line", buildDataContextLine(out, metrics));
        out.put("status", "loaded");
        return Optional.of(out);
    }

    private Optional<Map<String, Object>> buildMirrorUnavailableItem(
            Map<String, Object> hydrated,
            String sourceType,
            String setId,
            Map<String, Object> mirrorStatus) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", firstNonBlank(hydrated.get("title"), hydrated.get("indicator_name"), setId));
        out.put("source_type", sourceType);
        out.put("set_id", setId);
        out.put("summarize_fetch_source", "source_unavailable");
        out.put("status", "source_unavailable");
        out.put("mirror_status", mirrorStatus.get("_mirror_status"));
        out.put("mirror_path", mirrorStatus.get("_mirror_path"));
        out.put("reason", mirrorStatus.getOrDefault("reason_cs", "Doménový mirror není k dispozici."));
        out.put("observations", List.of());
        out.put("chart_points", List.of());
        copyRefMetadata(hydrated, out);
        return Optional.of(out);
    }

    public static String buildAiDataContext(List<Map<String, Object>> loaded) {
        if (loaded == null || loaded.isEmpty()) {
            return "(žádná numerická data se nepodařilo načíst)";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> item : loaded) {
            sb.append("- ").append(item.getOrDefault("data_context_line", item.get("title"))).append("\n");
        }
        return sb.toString().trim();
    }

    public static Map<String, Object> buildChartPayload(List<Map<String, Object>> loaded) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> series = new ArrayList<>();
        for (Map<String, Object> row : loaded) {
            if (!(row.get("chart_points") instanceof List<?> points) || points.size() < 2) {
                continue;
            }
            Map<String, Object> chart = new LinkedHashMap<>();
            chart.put("name", row.get("title"));
            chart.put("data", points);
            chart.put("source", row.get("source_type"));
            chart.put("source_type", row.get("source_type"));
            chart.put("set_id", row.get("set_id"));
            chart.put("series_id", firstNonBlank(row.get("series_id"), row.get("set_id")));
            for (String key : List.of(
                    "dataset_id", "unit", "freq", "frequency", "summarize_role", "context_scope",
                    "chart_section_id", "segment_id", "linked_sector_id", "primary_country_code",
                    "context_country", "query_params")) {
                if (row.containsKey(key) && row.get(key) != null) {
                    chart.put(key, row.get(key));
                }
            }
            series.add(chart);
        }
        out.put("series", series);
        return out;
    }

    /**
     * ETAPA 7: the frontend's "used data series" list (`ExplorePage.jsx` - {@code seriesUsed}
     * memo) expects a flat list of title strings; it was never populated for either summarize
     * flow, so the "Zobrazit použité datové řady" details block was permanently empty.
     */
    public static List<String> buildSeriesUsed(List<Map<String, Object>> loaded) {
        return loaded.stream().map(row -> str(row.get("title"))).filter(title -> !title.isBlank()).toList();
    }

    /**
     * ETAPA 7: {@code series_coverage} must be an ARRAY of per-series rows - the frontend
     * (exploreSeriesCoverage.js, ExplorePage.jsx "Všechny řady ve zpracování" section) has always
     * expected {@code Array.isArray(series_coverage)} rows with title/series_id/status/reason,
     * but both summarize flows only ever sent a {loaded, failed, requested} COUNT object, so the
     * array checks were always false and the section never rendered (see the MANAGER_EXPLORER_AUDIT
     * docs in docs/archive/, P0 - the original crash there is separately already fixed by the ErrorBoundary +
     * Array.isArray guards; this fixes the underlying always-empty DATA, not just the crash).
     */
    public static List<Map<String, Object>> buildSeriesCoverage(
            List<Map<String, Object>> loaded, List<Map<String, Object>> failed) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : loaded) {
            Map<String, Object> cov = new LinkedHashMap<>();
            cov.put("title", row.get("title"));
            cov.put("source_type", row.get("source_type"));
            cov.put("series_id", firstNonBlank(row.get("series_id"), row.get("set_id")));
            cov.put("set_id", row.get("set_id"));
            cov.put("dataset_id", firstNonBlank(row.get("dataset_id"), row.get("set_id")));
            cov.put("query_params", row.getOrDefault("query_params", Map.of()));
            cov.put("fact", row.get("data_context_line"));
            cov.put("status", "loaded");
            out.add(cov);
        }
        for (Map<String, Object> row : failed) {
            Map<String, Object> cov = new LinkedHashMap<>();
            cov.put("title", row.get("title"));
            cov.put("source_type", row.get("source_type"));
            cov.put("series_id", firstNonBlank(row.get("series_id"), row.get("set_id")));
            cov.put("set_id", row.get("set_id"));
            cov.put("dataset_id", row.get("set_id"));
            cov.put("status", "failed");
            cov.put(
                    "reason",
                    "fetch_failed_or_insufficient_points".equals(str(row.get("reason")))
                            ? "Data se nepodařilo načíst nebo neobsahují dost bodů."
                            : str(row.get("reason")));
            out.add(cov);
        }
        return out;
    }

    private Map<String, Object> hydrateFromIndex(Map<String, Object> ref) {
        Map<String, Object> out = new LinkedHashMap<>(ref);
        String ftsSource = CatalogSourceRegistry.normalizeSearchSource(str(ref.get("source_type")));
        String setId = str(ref.get("set_id"));
        if (ftsSource.isBlank() || setId.isBlank()) {
            return out;
        }
        Optional<Map<String, Object>> indexed = indexStore.lookupRow(ftsSource, setId);
        if (indexed.isEmpty() && "oecd4".equals(ftsSource)) {
            indexed = indexStore.lookupRow("oecd", setId);
        }
        indexed.ifPresent(row -> mergeIndexRow(out, row));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void mergeIndexRow(Map<String, Object> target, Map<String, Object> row) {
        if (str(target.get("title")).isBlank()) {
            target.put("title", CatalogTextUtils.rowTitle(row));
        }
        if (str(target.get("set_id")).isBlank()) {
            target.put("set_id", str(row.get("set_id")));
        }
        Map<String, Object> qp = new LinkedHashMap<>();
        Object existing = target.get("query_params");
        if (existing instanceof Map<?, ?> existingMap) {
            qp.putAll((Map<String, Object>) existingMap);
        }
        Object rowQp = row.get("query_params");
        if (!(rowQp instanceof Map<?, ?>)) {
            rowQp = row.get("filters_used");
        }
        if (rowQp instanceof Map<?, ?> rowMap) {
            for (Map.Entry<?, ?> entry : rowMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    qp.putIfAbsent(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        if (!qp.isEmpty()) {
            target.put("query_params", qp);
        }
        target.put("_fts_index_hit", true);
    }

    private static Map<String, Object> buildPreviewPayload(
            Map<String, Object> ref, String connectorType, String country) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_type", connectorType);
        payload.put("set_id", str(ref.get("set_id")));
        payload.put("name", firstNonBlank(ref.get("title"), ref.get("indicator_name"), ref.get("set_id")));
        payload.put("query_params", ref.getOrDefault("query_params", Map.of()));
        if (!str(country).isBlank()) {
            payload.put("country", country);
        }
        return payload;
    }

    private static Map<String, Object> refFromItem(ExploreSummarizeSeriesItem item) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("source_type", item.sourceType());
        ref.put("set_id", item.setId());
        ref.put("title", item.title());
        ref.put("query_params", item.queryParams() != null ? item.queryParams() : Map.of());
        ref.put("series_id", item.seriesId());
        ref.put("from_related_segment", item.fromRelatedSegment());
        ref.put("indicator_role", item.indicatorRole());
        ref.put("manager_category", item.managerCategory());
        ref.put("segment_id", item.segmentId());
        ref.put("linked_sector_id", item.linkedSectorId());
        ref.put("primary_sector_id", item.primarySectorId());
        ref.put("context_country", item.contextCountry());
        ref.put("summarize_role", inferSummarizeRole(item));
        return ref;
    }

    private static void copyRefMetadata(Map<String, Object> ref, Map<String, Object> target) {
        for (String key :
                List.of(
                        "from_related_segment",
                        "indicator_role",
                        "manager_category",
                        "segment_id",
                        "linked_sector_id",
                        "primary_sector_id",
                        "context_country",
                        "series_id",
                        "unit",
                        "freq",
                        "frequency",
                        "chart_section_id",
                        "primary_country_code",
                        "summarize_role",
                        "context_scope")) {
            if (ref.containsKey(key) && ref.get(key) != null) {
                target.put(key, ref.get(key));
            }
        }
    }

    private static String inferSummarizeRole(ExploreSummarizeSeriesItem item) {
        String role = str(item.indicatorRole());
        if (!role.isBlank()) {
            return role.toLowerCase(Locale.ROOT);
        }
        String category = str(item.managerCategory()).toLowerCase(Locale.ROOT);
        if (category.contains("macro")) {
            return "macro";
        }
        if (category.contains("commod")) {
            return "commodity";
        }
        if (category.contains("financial") || category.contains("market")) {
            return "financial_markets";
        }
        if (Boolean.TRUE.equals(item.fromRelatedSegment())) {
            return "sector";
        }
        return "sector";
    }

    private static String resolveRefGeo(Map<String, Object> ref) {
        Object qpObj = ref.get("query_params");
        if (qpObj instanceof Map<?, ?> qp) {
            for (String key : List.of("geo", "country", "ref_area")) {
                String val = str(qp.get(key));
                if (!val.isBlank()) {
                    return val;
                }
            }
        }
        return firstNonBlank(ref.get("context_country"), ref.get("country"));
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
            obs.put("date", period);
            obs.put("period", period);
            obs.put("value", num);
            out.add(obs);
        }
        out.sort(Comparator.comparing(o -> str(o.get("period"))));
        return out;
    }

    private static List<Map<String, Object>> chartPoints(List<Map<String, Object>> observations) {
        int from = Math.max(0, observations.size() - CHART_POINT_LIMIT);
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map<String, Object> obs : observations.subList(from, observations.size())) {
            points.add(Map.of(
                    "x", str(obs.get("period")),
                    "y", obs.get("value")));
        }
        return points;
    }

    private static Map<String, Object> computeMetrics(List<Map<String, Object>> observations) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        Map<String, Object> last = observations.getLast();
        Map<String, Object> prev = observations.size() > 1 ? observations.get(observations.size() - 2) : last;
        Double latest = toDouble(last.get("value"));
        Double previous = toDouble(prev.get("value"));
        metrics.put("latest_value", latest);
        metrics.put("latest_observation_date", str(last.get("period")));
        String trend = "flat";
        if (latest != null && previous != null) {
            if (latest > previous) {
                trend = "up";
            } else if (latest < previous) {
                trend = "down";
            }
        }
        metrics.put("trend_direction", trend);
        if (latest != null && previous != null && previous != 0.0) {
            metrics.put("period_over_period_change", (latest - previous) / Math.abs(previous));
        }
        if (observations.size() >= 13) {
            Double yearAgo = toDouble(observations.get(observations.size() - 13).get("value"));
            if (latest != null && yearAgo != null && yearAgo != 0.0) {
                metrics.put("year_on_year_change", (latest - yearAgo) / Math.abs(yearAgo));
            }
        }
        return metrics;
    }

    private static String buildDataContextLine(Map<String, Object> item, Map<String, Object> metrics) {
        String title = str(item.get("title"));
        String src = str(item.get("source_type"));
        String setId = str(item.get("set_id"));
        String lastDate = str(metrics.get("latest_observation_date"));
        Object latest = metrics.get("latest_value");
        String trend = trendLabelCz(str(metrics.get("trend_direction")));
        Object yoy = metrics.get("year_on_year_change");
        String yoyText = yoy instanceof Number n ? String.format(Locale.ROOT, "%.1f %%", n.doubleValue() * 100) : "n/a";
        return title + " [" + src + "/" + setId + "]: poslední hodnota " + latest + " (" + lastDate + "), trend "
                + trend + ", YoY " + yoyText;
    }

    private static boolean isStaleSeries(List<Map<String, Object>> observations) {
        String lastPeriod = str(observations.getLast().get("period"));
        int year = parseYear(lastPeriod);
        if (year <= 0) {
            return false;
        }
        return year < Year.now().getValue() - 2;
    }

    private static int parseYear(String period) {
        if (period == null || period.isBlank()) {
            return -1;
        }
        String digits = period.replaceAll("[^0-9]", " ");
        for (String part : digits.trim().split("\\s+")) {
            if (part.length() == 4) {
                try {
                    return Integer.parseInt(part);
                } catch (NumberFormatException ignored) {
                    // continue
                }
            }
        }
        return -1;
    }

    private static String connectorSourceType(String raw) {
        String normalized = CatalogSourceRegistry.normalizeSearchSource(raw);
        return switch (normalized) {
            case "oecd4" -> "oecd";
            case "worldbank", "data360" -> "world_bank_data360";
            default -> ConnectorFactory.normalizeSourceType(normalized);
        };
    }

    /**
     * ETAPA 8 cleanup: {@code planned}/{@code loaded}/{@code failed}/{@code charts} are the exact
     * keys the frontend's "Pokrytí dat" banner ({@code ExplorePage.jsx}) reads - it previously read
     * {@code series_requested}/{@code series_loaded}/{@code series_failed} (this map's old key
     * names) plus a {@code charts} key that never existed, so every summarize response rendered
     * "0 z 0 řad ve zpracování" regardless of how many series actually loaded.
     */
    private static Map<String, Object> summary(int requested, int loaded, int failed, boolean ftsAvailable) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", loaded > 0 ? "partial_or_ok" : "no_data");
        out.put("planned", requested);
        out.put("loaded", loaded);
        out.put("failed", failed);
        out.put("charts", loaded);
        out.put("fts_db_available", ftsAvailable);
        out.put("fetch_backend", ftsAvailable ? "sqlite_fts_plus_connector" : "jsonl_plus_connector");
        return out;
    }

    private static String trendLabelCz(String trend) {
        return switch (trend) {
            case "up" -> "rostoucí";
            case "down" -> "klesající";
            default -> "stabilní";
        };
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

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
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

    public record BatchResult(
            List<Map<String, Object>> loaded, List<Map<String, Object>> failed, Map<String, Object> summary) {}
}
