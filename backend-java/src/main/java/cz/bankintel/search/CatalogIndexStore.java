package cz.bankintel.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import cz.bankintel.search.model.CatalogHit;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.model.CatalogRawRow;
import cz.bankintel.search.scoring.CatalogScoringPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.sqlite.ProgressHandler;

/**
 * Lokální index katalogových položek — SQLite FTS5 + JSONL fallback z {@code CATALOG_SEARCH_INDEX_DIR}.
 *
 * <p>Používá {@link CatalogSuggestService}, {@link CatalogClassicSearchService} a metadata v preview fallbacku.
 */
@Service
public class CatalogIndexStore {

    private static final Logger log = LoggerFactory.getLogger(CatalogIndexStore.class);
    private static final int JSONL_SCAN_CAP = 500_000;
    /**
     * Above this FTS match cardinality, {@code ORDER BY bm25(catalog_fts)} on the full OR-expanded
     * query can take multiple seconds for {@code fred}/{@code ecb2}; below it bm25 ordering stays
     * sub-second (benchmark: ~127 ms for ~34k matches).
     */
    static final int FTS_BM25_ORDER_THRESHOLD = 45_000;

    /**
     * When a narrowed big-source match stays below this size, pull a deeper bm25 window so canonical
     * entities (e.g. NASDAQ100 at bm25 rank ~315 for {@code "nasdaq"}) are not truncated by the
     * default 120-row cap.
     */
    private static final int FTS_DEEP_FETCH_MATCH_THRESHOLD = 10_000;

    private static final int FTS_DEEP_FETCH_MAX = 500;
    private static final int RAW_FTS_QUERY_TIMEOUT_SECONDS = 15;
    private static final Set<String> RAW_FTS_STOP_WORDS = Set.of(
            "a", "an", "and", "are", "at", "by", "data", "dataset", "from", "in", "jen", "katalog", "o", "only",
            "pouze", "rada", "rady", "search", "serie", "series", "source", "v", "ve", "z", "ze", "zdroj");
    /**
     * Minimum stem length before it is used as an FTS5 prefix ({@code stem*}) instead of the exact
     * quoted token - guards against an overly short/generic stem (e.g. a 3-char floor) matching an
     * unrelated wide swath of the index. {@link CzTextStemmer#czStem(String, int)} already refuses to
     * shorten a word below its own {@code minStem} floor, so this is a second, independent floor
     * specific to how aggressively a *prefix* (broader than an exact stem-equality check) is allowed
     * to fan out.
     */
    private static final int MIN_STEM_PREFIX_LEN = 5;

    private static final String SQL_FTS_COUNT_BOUNDED =
            "SELECT COUNT(*) FROM (SELECT rowid FROM catalog_fts "
                    + "WHERE source = ? AND catalog_fts MATCH ? LIMIT ?)";
    private static final String SQL_FTS_SEARCH_ORDERED =
            "SELECT row_json, bm25(catalog_fts) AS rank FROM catalog_fts WHERE source = ? AND catalog_fts MATCH ? "
                    + "ORDER BY rank LIMIT ?";
    private static final String SQL_FTS_SEARCH_FAST =
            "SELECT row_json, 0 AS rank FROM catalog_fts WHERE source = ? AND catalog_fts MATCH ? LIMIT ?";
    private static final String SQL_FTS_SUGGEST_ORDERED =
            "SELECT source, set_id, title, full_path, bm25(catalog_fts) AS rank "
                    + "FROM catalog_fts WHERE source = ? AND catalog_fts MATCH ? ORDER BY rank LIMIT ?";
    private static final String SQL_FTS_SUGGEST_FAST =
            "SELECT source, set_id, title, full_path, 0 AS rank "
                    + "FROM catalog_fts WHERE source = ? AND catalog_fts MATCH ? LIMIT ?";
    private static final Pattern FTS_QUOTED_TOKEN = Pattern.compile("\"([^\"]+)\"");

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final CatalogSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final CatalogSearchMetadataSidecar metadataSidecar;
    private final CatalogSqliteReadPool sqlitePool;
    private final Environment environment;
    private final CatalogSearchResultCache searchResultCache;
    private final CatalogScoringPipeline scoringPipeline;
    private final Semaphore ftsConcurrencyLimiter;

    public CatalogIndexStore(
            CatalogSearchProperties properties,
            ObjectMapper objectMapper,
            CatalogSearchMetadataSidecar metadataSidecar,
            CatalogSqliteReadPool sqlitePool,
            Environment environment,
            CatalogSearchResultCache searchResultCache,
            CatalogScoringPipeline scoringPipeline) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metadataSidecar = metadataSidecar;
        this.sqlitePool = sqlitePool;
        this.environment = environment;
        this.searchResultCache = searchResultCache;
        this.scoringPipeline = scoringPipeline;
        int maxConcurrentFtsQueries = configuredFtsConcurrency(environment);
        this.ftsConcurrencyLimiter = maxConcurrentFtsQueries > 0
                ? new Semaphore(maxConcurrentFtsQueries, true)
                : null;
    }

    public boolean ftsDbAvailable() {
        return Files.isRegularFile(properties.ftsDbPath());
    }

    public String ftsDbPathLabel() {
        return properties.ftsDbPath().toAbsolutePath().normalize().toString();
    }

    public Optional<Map<String, Object>> loadMeta(String source) {
        try {
            if (!Files.isRegularFile(properties.metaPath(source))) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(properties.metaPath(source).toFile(), MAP_TYPE));
        } catch (Exception ex) {
            log.debug("meta read failed source={}: {}", source, ex.getMessage());
            return Optional.empty();
        }
    }

    public List<Map<String, Object>> ftsSuggest(String queryRaw, int limit, List<String> sources) {
        if (ftsDbAvailable()) {
            return ftsSuggestSqlite(queryRaw, limit, sources);
        }
        return jsonlSuggest(queryRaw, limit, sources);
    }

    /** Typed search hits — preferred internal API. */
    public List<CatalogHit> searchHits(String source, String queryRaw, int limit) {
        return searchSourceInternal(source, queryRaw, limit).stream().map(CatalogHit::fromMap).toList();
    }

    public List<CatalogHit> sidecarSearchHits(String source, String queryRaw, int limit) {
        String src = CatalogSourceRegistry.normalizeSearchSource(source);
        if (src.isBlank() || queryRaw == null || queryRaw.trim().length() < 2) {
            return List.of();
        }
        return scoreRows(src, queryRaw, sidecarMetadataRows(src, queryRaw), limit).stream()
                .map(CatalogHit::fromMap)
                .toList();
    }

    /** Resolves equivalent query variants with a single pass over immutable sidecar metadata. */
    public List<CatalogHit> sidecarSearchHits(String source, List<String> queryVariants, int limitPerVariant) {
        String src = CatalogSourceRegistry.normalizeSearchSource(source);
        List<String> variants = queryVariants == null
                ? List.of()
                : queryVariants.stream()
                        .filter(value -> value != null && value.trim().length() >= 2)
                        .map(String::trim)
                        .distinct()
                        .toList();
        if (src.isBlank() || variants.isEmpty()) {
            return List.of();
        }
        int totalLimit = Math.max(1, Math.min(500, Math.max(1, limitPerVariant) * variants.size()));
        String combinedQuery = String.join(" ", variants);
        return scoreRows(
                        src,
                        combinedQuery,
                        sidecarMetadataRows(src, variants, totalLimit),
                        totalLimit)
                .stream()
                .map(CatalogHit::fromMap)
                .toList();
    }

    /**
     * Pure FTS retrieval for a deep-search term. It deliberately does not merge the metadata sidecar;
     * the lane already retrieves that immutable source once for all equivalent terms.
     */
    public FtsSearchResult searchFtsHitsWithinBudget(
            String source, String queryRaw, int limit, long deadlineNanos) {
        long started = System.nanoTime();
        long queueWaitMs = 0L;
        String src = CatalogSourceRegistry.normalizeSearchSource(source);
        if (src.isBlank() || queryRaw == null || queryRaw.trim().length() < 2) {
            return new FtsSearchResult(List.of(), false, false, elapsedMillis(started), queueWaitMs);
        }
        if (deadlineExpired(deadlineNanos)) {
            return new FtsSearchResult(List.of(), true, false, elapsedMillis(started), queueWaitMs);
        }
        int lim = Math.max(1, Math.min(limit, 100));
        if (!ftsDbAvailable()) {
            List<CatalogHit> hits = jsonlSearch(src, queryRaw, lim).stream().map(CatalogHit::fromMap).toList();
            return new FtsSearchResult(
                    hits, deadlineExpired(deadlineNanos), false, elapsedMillis(started), queueWaitMs);
        }
        if (!ftsResultCacheBypassedForColdPathProfile()) {
            Optional<List<Map<String, Object>>> cached =
                    searchResultCache.get("fts-only-v1", src, queryRaw, lim);
            if (cached.isPresent()) {
                return new FtsSearchResult(
                        cached.get().stream().map(CatalogHit::fromMap).toList(),
                        false,
                        true,
                        elapsedMillis(started),
                        queueWaitMs);
            }
        }
        boolean permitAcquired = false;
        long queueStarted = System.nanoTime();
        try {
            permitAcquired = acquireFtsPermit(deadlineNanos);
            queueWaitMs = elapsedMillis(queueStarted);
            if (!permitAcquired) {
                return new FtsSearchResult(List.of(), true, false, elapsedMillis(started), queueWaitMs);
            }
            SqliteFtsOutcome outcome = ftsSearchSqliteWithinBudget(src, queryRaw, lim, deadlineNanos);
            if (!outcome.budgetExhausted() && !ftsResultCacheBypassedForColdPathProfile()) {
                searchResultCache.put("fts-only-v1", src, queryRaw, lim, outcome.rows());
            }
            return new FtsSearchResult(
                    outcome.rows().stream().map(CatalogHit::fromMap).toList(),
                    outcome.budgetExhausted(),
                    false,
                    elapsedMillis(started),
                    queueWaitMs);
        } finally {
            if (permitAcquired && ftsConcurrencyLimiter != null) {
                ftsConcurrencyLimiter.release();
            }
        }
    }

    public record FtsSearchResult(
            List<CatalogHit> hits,
            boolean budgetExhausted,
            boolean cacheHit,
            long durationMs,
            long queueWaitMs) {}

    private boolean acquireFtsPermit(long deadlineNanos) {
        if (ftsConcurrencyLimiter == null) {
            return true;
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return false;
        }
        try {
            return ftsConcurrencyLimiter.tryAcquire(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static int configuredFtsConcurrency(Environment environment) {
        String configured = environment.getProperty("SEARCH_FTS_MAX_CONCURRENT_QUERIES", "0");
        if (configured == null || configured.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** Legacy JSON-shaped API for controllers and external callers. */
    public List<Map<String, Object>> searchSource(String source, String queryRaw, int limit) {
        return searchSourceInternal(source, queryRaw, limit);
    }

    /**
     * Search V2 raw retrieval adapter: SQLite/JSONL candidate fetch with no V1 sidecar rescue and
     * no V1 scoring pipeline. Semantic keep/drop and final ranking are owned by Search V2.
     */
    public List<Map<String, Object>> searchSourceFtsRaw(String source, String queryRaw, int limit) {
        String src = CatalogSourceRegistry.normalizeSearchSource(source);
        if (src.isBlank() || queryRaw == null || queryRaw.trim().length() < 2) {
            return List.of();
        }
        int lim = Math.max(1, Math.min(limit, 500));
        if (ftsDbAvailable()) {
            return ftsSearchSqliteRaw(src, queryRaw, lim);
        }
        List<String> needles = CatalogTextUtils.needlesFromQuery(queryRaw.trim());
        return jsonlScan(src, needles, queryRaw, lim, false);
    }

    public String catalogVersion() {
        try {
            if (Files.isRegularFile(properties.ftsDbPath())) {
                return "sqlite:"
                        + Files.size(properties.ftsDbPath())
                        + ":"
                        + Files.getLastModifiedTime(properties.ftsDbPath()).toMillis();
            }
        } catch (Exception ignored) {
            // best effort version label
        }
        return "jsonl:" + properties.indexDir().toAbsolutePath().normalize();
    }

    private List<Map<String, Object>> searchSourceInternal(String source, String queryRaw, int limit) {
        String src = CatalogSourceRegistry.normalizeSearchSource(source);
        if (queryRaw == null || queryRaw.trim().length() < 2) {
            return List.of();
        }
        if (!ftsDbAvailable()) {
            if (isProdProfile()) {
                log.error(
                        "FTS database unavailable in production (path={}); refusing silent JSONL fallback for source={}",
                        properties.ftsDbPath(),
                        src);
                throw new IllegalStateException(
                        "Catalog FTS index is unavailable in production. Configure CLASSIC_CATALOG_FTS_DB or CATALOG_SEARCH_INDEX_DIR.");
            }
            log.warn("FTS database unavailable (path={}); falling back to JSONL scan for source={}", properties.ftsDbPath(), src);
        }
        List<Map<String, Object>> rows;
        if (ftsDbAvailable()) {
            rows = ftsResultCacheBypassedForColdPathProfile()
                    ? ftsSearchSqlite(src, queryRaw, limit)
                    : searchResultCache.getOrCompute(
                            src, queryRaw, limit, () -> ftsSearchSqlite(src, queryRaw, limit));
        } else {
            rows = jsonlSearch(src, queryRaw, limit);
        }
        return mergeSidecarRows(src, queryRaw, rows, limit);
    }

    /**
     * The benchmark profile must measure every query from an empty retrieval cache without clearing
     * the process-wide cache used by other requests. Production and normal local profiles keep the
     * existing LRU behavior unchanged.
     */
    public boolean ftsResultCacheBypassedForColdPathProfile() {
        return environment.acceptsProfiles(Profiles.of("cold-path-profile"));
    }

    private boolean isProdProfile() {
        return environment.acceptsProfiles(Profiles.of("prod"));
    }

    private static String rowIdentityKey(String source, Map<String, Object> row) {
        if ("arad".equals(source)) {
            return AradSeriesIdentity.fromRow(row).toLowerCase(Locale.ROOT);
        }
        return String.valueOf(row.getOrDefault("set_id", row.getOrDefault("id", "")))
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String displaySetId(String source, Map<String, Object> row) {
        if ("arad".equals(source)) {
            String composite = AradSeriesIdentity.fromRow(row);
            if (!composite.isBlank()) {
                return composite;
            }
        }
        return String.valueOf(row.getOrDefault("set_id", row.getOrDefault("id", ""))).trim();
    }

    public List<Map<String, Object>> lookupRowsBySetIds(String source, List<String> setIds, int limit) {
        String src = CatalogSourceRegistry.normalizeSearchSource(source);
        if (src.isBlank() || setIds == null || setIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = setIds.stream()
                .map(id -> id == null ? "" : id.trim())
                .filter(id -> !id.isBlank())
                .distinct()
                .limit(Math.max(1, limit))
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        if (ftsDbAvailable()) {
            return lookupRowsBySetIdsSqlite(src, ids, limit);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            if (out.size() >= limit) {
                break;
            }
            lookupJsonl(src, id).ifPresent(out::add);
        }
        return out;
    }

    private List<Map<String, Object>> mergeSidecarRows(
            String source, String queryRaw, List<Map<String, Object>> rows, int limit) {
        if (!metadataSidecar.enabledForClassic()) {
            return rerankResults(source, queryRaw, rows, limit);
        }
        List<Map<String, Object>> extraRows = sidecarRescueRows(source, queryRaw, rows, limit);
        if (extraRows.isEmpty()) {
            return rerankResults(source, queryRaw, rows, limit);
        }
        List<Map<String, Object>> merged = new ArrayList<>(rows);
        merged.addAll(extraRows);
        return rerankResults(source, queryRaw, merged, limit);
    }

    private List<Map<String, Object>> sidecarRescueRows(
            String source, String queryRaw, List<Map<String, Object>> existingRows, int limit) {
        if (!metadataSidecar.enabledForClassic()) {
            return List.of();
        }
        List<String> sidecarIds = metadataSidecar.sidecarRetrievalSetIds(source, queryRaw);
        if (sidecarIds.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rescued = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : existingRows) {
            seen.add(rowIdentityKey(source, row));
        }
        List<String> lookupIds;
        if ("arad".equals(source)) {
            java.util.Set<String> setIds = new java.util.LinkedHashSet<>();
            for (String sid : sidecarIds) {
                String[] parsed = AradSeriesIdentity.parse(sid);
                if (parsed != null) {
                    setIds.add(parsed[0]);
                } else {
                    setIds.add(sid);
                }
            }
            lookupIds = new ArrayList<>(setIds);
        } else {
            lookupIds = sidecarIds;
        }
        List<Map<String, Object>> extra =
                lookupRowsBySetIds(source, lookupIds, Math.max(limit * 3, sidecarIds.size()));
        if ("arad".equals(source) && !extra.isEmpty()) {
            java.util.Set<String> wanted = new java.util.LinkedHashSet<>();
            for (String sid : sidecarIds) {
                wanted.add(sid.toLowerCase(Locale.ROOT));
            }
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> rawRow : extra) {
                String composite = AradSeriesIdentity.fromRow(rawRow).toLowerCase(Locale.ROOT);
                if (wanted.contains(composite)) {
                    filtered.add(rawRow);
                }
            }
            extra = filtered;
        }
        for (Map<String, Object> rawRow : extra) {
            String key = rowIdentityKey(source, rawRow);
            if (key.isBlank() || seen.contains(key)) {
                continue;
            }
            seen.add(key);
            rawRow.put("_sidecar_rescue", true);
            rescued.add(rawRow);
        }
        for (String sid : sidecarIds) {
            String key = sidecarIdentityKey(source, sid);
            if (key.isBlank() || seen.contains(key)) {
                continue;
            }
            Map<String, Object> meta = metadataSidecar.getSearchMetadata(source, sid, sid);
            if (meta == null) {
                continue;
            }
            Map<String, Object> synthetic = syntheticRowFromMetadata(source, sid, meta);
            if (synthetic.isEmpty()) {
                continue;
            }
            seen.add(key);
            synthetic.put("_sidecar_rescue", true);
            rescued.add(synthetic);
        }
        return rescued;
    }

    private List<Map<String, Object>> sidecarMetadataRows(String source, String queryRaw) {
        return sidecarMetadataRows(source, List.of(queryRaw), SIDECAR_SINGLE_QUERY_LIMIT);
    }

    private static final int SIDECAR_SINGLE_QUERY_LIMIT = 40;

    private List<Map<String, Object>> sidecarMetadataRows(
            String source, List<String> queryVariants, int maxResults) {
        if (!metadataSidecar.enabledForClassic()) {
            return List.of();
        }
        List<String> sidecarIds = metadataSidecar.sidecarRetrievalSetIds(source, queryVariants, maxResults);
        if (sidecarIds.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String sid : sidecarIds) {
            String key = sidecarIdentityKey(source, sid);
            if (key.isBlank() || !seen.add(key)) {
                continue;
            }
            Map<String, Object> meta = metadataSidecar.getSearchMetadata(source, sid, sid);
            if (meta == null) {
                continue;
            }
            Map<String, Object> synthetic = syntheticRowFromMetadata(source, sid, meta);
            if (synthetic.isEmpty()) {
                continue;
            }
            synthetic.put("_sidecar_rescue", true);
            synthetic.put("_sidecar_fast_metadata", true);
            rows.add(synthetic);
        }
        return rows;
    }

    private static String sidecarIdentityKey(String source, String seriesId) {
        if ("arad".equals(source)) {
            String[] parsed = AradSeriesIdentity.parse(seriesId);
            if (parsed != null) {
                return AradSeriesIdentity.compose(parsed[0], parsed[1]).toLowerCase(Locale.ROOT);
            }
        }
        return seriesId == null ? "" : seriesId.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> syntheticRowFromMetadata(
            String source, String seriesId, Map<String, Object> metadata) {
        Map<String, Object> row = new LinkedHashMap<>();
        String title = str(metadata.get("human_label_cs"));
        if (title.isBlank()) {
            title = str(metadata.get("human_label_en"));
        }
        if (title.isBlank()) {
            title = str(metadata.get("title_original"));
        }
        if (title.isBlank()) {
            return Map.of();
        }
        row.put("set_id", seriesId);
        if ("arad".equals(source)) {
            String[] parsed = AradSeriesIdentity.parse(seriesId);
            if (parsed != null) {
                row.put("set_id", parsed[0]);
                row.put("indicator_id", parsed[1]);
            }
        }
        row.put("name", title);
        row.put("title", title);
        row.put("indicator_name", title);
        row.put("source_type", source);
        row.put("catalog_id", source);
        row.put("catalog_label", CatalogSourceRegistry.label(source));
        // Eurostat enrichment aliases keep the real API dataset in dataset_id; without it preview
        // fetches the synthetic series_id and gets HTTP 404 / empty.
        copyMetadataText(row, metadata, "dataset_id");
        copyMetadataText(row, metadata, "dataset");
        if (!str(row.get("dataset")).isBlank() && str(row.get("dataset_id")).isBlank()) {
            row.put("dataset_id", row.get("dataset"));
        }
        if (!str(row.get("dataset_id")).isBlank() && str(row.get("dataset")).isBlank()) {
            row.put("dataset", row.get("dataset_id"));
        }
        copyMetadataText(row, metadata, "title_original");
        copyMetadataText(row, metadata, "human_label_cs");
        copyMetadataText(row, metadata, "human_label_en");
        row.put("_sidecar_metadata_only", true);
        return row;
    }

    private static void copyMetadataText(Map<String, Object> row, Map<String, Object> metadata, String key) {
        String value = str(metadata.get(key));
        if (!value.isBlank()) {
            row.put(key, value);
        }
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private List<Map<String, Object>> rerankResults(
            String source, String queryRaw, List<Map<String, Object>> rows, int limit) {
        return scoreRows(source, queryRaw, rows, limit);
    }

    private List<Map<String, Object>> lookupRowsBySetIdsSqlite(String source, List<String> setIds, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        String sqlSingle = "SELECT row_json FROM catalog_rows_lookup WHERE source = ? AND set_id = ? LIMIT 1";
        String sqlMulti = "SELECT row_json FROM catalog_rows_lookup WHERE source = ? AND set_id = ? LIMIT ?";
        try (PooledConnection pooled = borrowPooled()) {
            Connection conn = pooled.connection();
            for (String rawId : setIds) {
                if (out.size() >= limit) {
                    break;
                }
                String setId = rawId == null ? "" : rawId.trim();
                if (setId.isBlank()) {
                    continue;
                }
                String[] aradParts = "arad".equals(source) ? AradSeriesIdentity.parse(setId) : null;
                if (aradParts != null) {
                    String lookupSetId = aradParts[0];
                    String wantedIndicator = aradParts[1].toLowerCase(Locale.ROOT);
                    String key = AradSeriesIdentity.compose(lookupSetId, aradParts[1]).toLowerCase(Locale.ROOT);
                    if (seen.contains(key)) {
                        continue;
                    }
                    try (PreparedStatement ps = conn.prepareStatement(sqlMulti)) {
                        ps.setString(1, source);
                        ps.setString(2, lookupSetId);
                        ps.setInt(3, Math.max(20, limit));
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                Map<String, Object> parsed = parseRowJson(rs.getString("row_json"));
                                if (parsed == null) {
                                    continue;
                                }
                                String indicator = String.valueOf(parsed.getOrDefault("indicator_id", ""))
                                        .trim()
                                        .toLowerCase(Locale.ROOT);
                                if (!wantedIndicator.equals(indicator)) {
                                    continue;
                                }
                                seen.add(key);
                                out.add(parsed);
                                break;
                            }
                        }
                    }
                    continue;
                }
                String key = setId.toLowerCase(Locale.ROOT);
                if (seen.contains(key)) {
                    continue;
                }
                boolean exactFound = false;
                try (PreparedStatement ps = conn.prepareStatement(sqlSingle)) {
                    ps.setString(1, source);
                    ps.setString(2, setId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            Map<String, Object> parsed = parseRowJson(rs.getString("row_json"));
                            if (parsed != null) {
                                seen.add(rowIdentityKey(source, parsed));
                                out.add(parsed);
                                exactFound = true;
                            }
                        }
                    }
                }
                // Prefix expansion is only a rescue when the exact set_id is missing. Running it after
                // every short exact hit (nama_10_gdp/%, tipsbd40/%, …) floods the shared limit and can
                // drop later Manager intent seeds from the same lookup batch.
                if (!exactFound && !setId.contains("/") && setId.length() <= 12 && out.size() < limit) {
                    String sqlPrefix =
                            "SELECT row_json FROM catalog_rows_lookup WHERE source = ? AND set_id LIKE ? LIMIT ?";
                    try (PreparedStatement ps = conn.prepareStatement(sqlPrefix)) {
                        ps.setString(1, source);
                        ps.setString(2, setId + "/%");
                        ps.setInt(3, Math.max(5, (limit - out.size()) / 2));
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next() && out.size() < limit) {
                                Map<String, Object> parsed = parseRowJson(rs.getString("row_json"));
                                if (parsed == null) {
                                    continue;
                                }
                                String rowKey = rowIdentityKey(source, parsed);
                                if (rowKey.isBlank() || seen.contains(rowKey)) {
                                    continue;
                                }
                                seen.add(rowKey);
                                out.add(parsed);
                            }
                        }
                    }
                }
                if ("bis".equals(source) && setId.endsWith("||DATAFLOW") && out.size() < limit) {
                    String flow = setId.substring(0, setId.length() - "||DATAFLOW".length());
                    if (!flow.isBlank()) {
                        String sqlBisPrefix =
                                "SELECT row_json FROM catalog_rows_lookup WHERE source = ? AND set_id LIKE ? LIMIT ?";
                        try (PreparedStatement ps = conn.prepareStatement(sqlBisPrefix)) {
                            ps.setString(1, source);
                            ps.setString(2, "BIS|" + flow + "|%");
                            ps.setInt(3, Math.max(12, limit - out.size()));
                            try (ResultSet rs = ps.executeQuery()) {
                                while (rs.next() && out.size() < limit) {
                                    Map<String, Object> parsed = parseRowJson(rs.getString("row_json"));
                                    if (parsed == null) {
                                        continue;
                                    }
                                    String rowKey = rowIdentityKey(source, parsed);
                                    if (rowKey.isBlank() || seen.contains(rowKey)) {
                                        continue;
                                    }
                                    seen.add(rowKey);
                                    out.add(parsed);
                                }
                            }
                        }
                    }
                }
            }
        } catch (SQLException ex) {
            log.warn("lookupRowsBySetIds sqlite source={}: {}", source, ex.getMessage());
        }
        return out;
    }

    public Optional<Map<String, Object>> lookupRow(String source, String setId) {
        String src = CatalogSourceRegistry.normalizeSearchSource(source);
        String sid = setId == null ? "" : setId.trim();
        if (src.isBlank() || sid.isBlank()) {
            return Optional.empty();
        }
        if (ftsDbAvailable()) {
            Optional<Map<String, Object>> hit = lookupSqlite(src, sid);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return lookupJsonl(src, sid);
    }

    private List<Map<String, Object>> ftsSuggestSqlite(String queryRaw, int limit, List<String> sources) {
        int lim = Math.max(1, Math.min(limit, 50));
        int perSource = Math.max(12, Math.min(lim * 3, 40));
        // Synonym-aware typeahead: expand abbreviations/synonyms (HDP → GDP / "hrubý domácí
        // produkt", inflace → CPI/HICP, …) so the "Srovnat s…" compare box and the header
        // typeahead find the right series from a short abbreviation — the same expansion the full
        // search already uses (CatalogTextUtils.buildFtsMatch). Without this "HDP" only matched the
        // literal token "hdp" and never surfaced GDP series.
        String matchExpr =
                CatalogTextUtils.buildFtsSuggestMatch(queryRaw, CatalogSearchSynonyms.expandSearchQueries(queryRaw));
        if ("\"\"".equals(matchExpr)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try (PooledConnection pooled = borrowPooled()) {
            Connection conn = pooled.connection();
            for (String source : sources) {
                // Autocomplete needs bounded latency more than a global bm25 sort. Counting and
                // sorting very large FRED/ECB match sets used to block every keystroke for seconds;
                // the final search still performs the full ranked retrieval.
                FtsQueryPlan plan = CatalogSourceRegistry.BIG_FTS_SOURCES.contains(source)
                        ? new FtsQueryPlan(matchExpr, false)
                        : resolveFtsQueryPlan(conn, source, matchExpr);
                String sql = plan.ordered() ? SQL_FTS_SUGGEST_ORDERED : SQL_FTS_SUGGEST_FAST;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, source);
                    ps.setString(2, plan.matchExpr());
                    ps.setInt(3, perSource);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("source", rs.getString("source"));
                            row.put("set_id", rs.getString("set_id"));
                            row.put("title", rs.getString("title"));
                            row.put("full_path", rs.getString("full_path"));
                            row.put("_fts_rank", rs.getDouble("rank"));
                            out.add(row);
                        }
                    }
                } catch (SQLException ex) {
                    log.warn("fts_suggest sqlite source={}: {}", source, ex.getMessage());
                }
            }
        } catch (SQLException ex) {
            log.warn("fts_suggest connection failed: {}", ex.getMessage());
        }
        return out;
    }

    private List<Map<String, Object>> ftsSearchSqlite(String source, String queryRaw, int limit) {
        int lim = Math.max(1, Math.min(limit, 100));
        List<String> needles = CatalogTextUtils.needlesFromQuery(queryRaw.trim());
        String matchExpr = CatalogTextUtils.buildFtsMatch(needles, queryRaw);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PooledConnection pooled = borrowPooled()) {
            Connection conn = pooled.connection();
            FtsQueryPlan plan = resolveFtsQueryPlan(conn, source, matchExpr);
            int fetch = computeFtsFetchLimit(conn, source, lim, plan);
            String sql = plan.ordered() ? SQL_FTS_SEARCH_ORDERED : SQL_FTS_SEARCH_FAST;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, source);
                ps.setString(2, plan.matchExpr());
                ps.setInt(3, fetch);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> parsed = parseRowJson(rs.getString("row_json"));
                        if (parsed != null) {
                            parsed.put("_fts_rank", rs.getDouble("rank"));
                            rows.add(parsed);
                        }
                    }
                }
            }
            rows = mergeEntityRescueRows(conn, source, queryRaw, needles, rows, fetch);
            if (rows.isEmpty() && !"\"\"".equals(matchExpr)) {
                rows.addAll(lookupLikeFallback(conn, source, queryRaw, lim));
            }
        } catch (SQLException ex) {
            log.warn("fts_search sqlite source={}: {}", source, ex.getMessage());
        }
        return scoreRows(source, queryRaw, rows, lim);
    }

    private SqliteFtsOutcome ftsSearchSqliteWithinBudget(
            String source, String queryRaw, int limit, long deadlineNanos) {
        int lim = Math.max(1, Math.min(limit, 100));
        List<String> needles = CatalogTextUtils.needlesFromQuery(queryRaw.trim());
        String matchExpr = CatalogTextUtils.buildFtsMatch(needles, queryRaw);
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean budgetExhausted = deadlineExpired(deadlineNanos);
        if (budgetExhausted) {
            return new SqliteFtsOutcome(List.of(), true);
        }
        try (PooledConnection pooled = borrowPooled()) {
            Connection conn = pooled.connection();
            boolean progressHandlerInstalled = false;
            try {
                ProgressHandler.setHandler(conn, 10_000, new ProgressHandler() {
                    @Override
                    protected int progress() {
                        return deadlineExpired(deadlineNanos) ? 1 : 0;
                    }
                });
                progressHandlerInstalled = true;

                FtsQueryPlan plan = resolveFtsQueryPlan(conn, source, matchExpr);
                int fetch = computeFtsFetchLimit(conn, source, lim, plan);
                String sql = plan.ordered() ? SQL_FTS_SEARCH_ORDERED : SQL_FTS_SEARCH_FAST;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, source);
                    ps.setString(2, plan.matchExpr());
                    ps.setInt(3, fetch);
                    applyQueryTimeout(ps, remainingDeadlineSeconds(deadlineNanos));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Map<String, Object> parsed = parseRowJson(rs.getString("row_json"));
                            if (parsed != null) {
                                parsed.put("_fts_rank", rs.getDouble("rank"));
                                rows.add(parsed);
                            }
                        }
                    }
                }
                rows = mergeEntityRescueRows(conn, source, queryRaw, needles, rows, fetch);
                if (rows.isEmpty() && !"\"\"".equals(matchExpr) && !deadlineExpired(deadlineNanos)) {
                    rows.addAll(lookupLikeFallback(conn, source, queryRaw, lim));
                }
                budgetExhausted = deadlineExpired(deadlineNanos);
            } catch (SQLException ex) {
                budgetExhausted = deadlineExpired(deadlineNanos) || sqliteInterrupted(ex);
                if (!budgetExhausted) {
                    log.warn("budgeted fts_search sqlite source={}: {}", source, ex.getMessage());
                }
            } finally {
                if (progressHandlerInstalled) {
                    try {
                        ProgressHandler.clearHandler(conn);
                    } catch (SQLException ex) {
                        log.debug("sqlite progress handler cleanup source={}: {}", source, ex.getMessage());
                    }
                }
            }
        } catch (SQLException ex) {
            budgetExhausted = deadlineExpired(deadlineNanos) || sqliteInterrupted(ex);
            if (!budgetExhausted) {
                log.warn("budgeted fts_search connection source={}: {}", source, ex.getMessage());
            }
        }
        return new SqliteFtsOutcome(scoreRows(source, queryRaw, rows, lim), budgetExhausted);
    }

    private List<Map<String, Object>> ftsSearchSqliteRaw(String source, String queryRaw, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        String matchExpr = buildRawFtsMatch(queryRaw, true);
        if ("\"\"".equals(matchExpr)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PooledConnection pooled = borrowPooled()) {
            Connection conn = pooled.connection();
            rows.addAll(executeRawFts(conn, source, queryRaw, matchExpr, lim, true));
            if (rows.isEmpty() && matchExpr.contains(" AND ")) {
                String looseMatchExpr = buildRawFtsMatch(queryRaw, false);
                if (!looseMatchExpr.equals(matchExpr) && !"\"\"".equals(looseMatchExpr)) {
                    rows.addAll(executeRawFts(conn, source, queryRaw, looseMatchExpr, lim, false));
                }
            }
            if (rows.isEmpty()) {
                rows.addAll(lookupLikeFallback(conn, source, queryRaw, lim));
                for (Map<String, Object> row : rows) {
                    row.put("_matched_query", queryRaw);
                    row.put("_like_fallback", true);
                }
            }
        } catch (SQLException ex) {
            log.warn("fts_raw_search sqlite source={}: {}", source, ex.getMessage());
        }
        return rows;
    }

    private List<Map<String, Object>> executeRawFts(
            Connection conn, String source, String queryRaw, String matchExpr, int limit, boolean ordered)
            throws SQLException {
        int fetch = Math.min(Math.max(limit, 20), 250);
        String sql = ordered ? SQL_FTS_SEARCH_ORDERED : SQL_FTS_SEARCH_FAST;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, source);
            ps.setString(2, matchExpr);
            ps.setInt(3, fetch);
            applyQueryTimeout(ps, RAW_FTS_QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && rows.size() < limit) {
                    Map<String, Object> parsed = parseRowJson(rs.getString("row_json"));
                    if (parsed != null) {
                        parsed.put("_fts_rank", rs.getDouble("rank"));
                        parsed.put("_matched_query", queryRaw);
                        parsed.put("_fts_match_expr", matchExpr);
                        rows.add(parsed);
                    }
                }
            }
        }
        return rows;
    }

    private static String buildRawFtsMatch(String queryRaw, boolean requireAllTokens) {
        String topic = queryRaw == null ? "" : queryRaw.trim();
        String normalized = CatalogTextUtils.normalizeTokenBoundaries(topic);
        if (normalized.isBlank()) {
            return "\"\"";
        }
        List<String> tokens = new ArrayList<>();
        for (String raw : normalized.split("\\s+")) {
            String token = CatalogTextUtils.ftsEscapeToken(raw);
            if (token.length() < 2 || RAW_FTS_STOP_WORDS.contains(token)) {
                continue;
            }
            if (!tokens.contains(token)) {
                tokens.add(token);
            }
            if (tokens.size() >= 5) {
                break;
            }
        }
        if (tokens.isEmpty()) {
            return "\"\"";
        }
        List<String> clauses = new ArrayList<>();
        for (String token : tokens) {
            clauses.add(ftsTokenClause(token));
        }
        return String.join(requireAllTokens ? " AND " : " OR ", clauses);
    }

    /**
     * A single token's match clause - exact quoted phrase, widened with an FTS5 prefix query
     * ({@code stem*}) whenever {@link CzTextStemmer} strips a long-enough Czech case suffix.
     *
     * <p>Root cause this addresses: {@code catalog_fts} is tokenized with {@code unicode61
     * remove_diacritics 2} (see {@code SearchCatalogSidecarIndex}'s FTS5 table DDL for the same
     * tokenizer choice) - it removes accents but never stems, so an exact quoted token like
     * {@code "domacnosti"} cannot match an indexed row whose title uses a different grammatical case
     * ({@code "domacnostem"}, dative). A bareword prefix query on the STEM (not the raw token) matches
     * both, and any other case sharing the same root, without touching how content is indexed and
     * without a hardcoded list of inflected word forms - {@code CzTextStemmer} is a general rule-based
     * suffix stripper, not a per-word table.
     *
     * <p>Only widens when the stem actually removed something AND stayed at least {@link
     * #MIN_STEM_PREFIX_LEN} characters (a short prefix like a 3-char stem floor would fan out to an
     * unrelated wide swath of the index). Otherwise falls through to the exact quoted token exactly as
     * before - no behavior change for short tokens, tickers, dataset codes, or already-uninflected
     * words.
     */
    private static String ftsTokenClause(String token) {
        String exact = ftsQuotedToken(token);
        String stem = CzTextStemmer.ftsPrefixStem(token, MIN_STEM_PREFIX_LEN);
        if (stem.isEmpty()) {
            return exact;
        }
        return "(" + CatalogTextUtils.ftsEscapeToken(stem) + "* OR " + exact + ")";
    }

    private static void applyQueryTimeout(PreparedStatement ps, int seconds) {
        try {
            ps.setQueryTimeout(seconds);
        } catch (SQLException ignored) {
            // SQLite JDBC timeout support is best-effort across platforms.
        }
    }

    private static int remainingDeadlineSeconds(long deadlineNanos) {
        long remaining = Math.max(1L, deadlineNanos - System.nanoTime());
        return Math.max(1, (int) Math.min(60L, (remaining + 999_999_999L) / 1_000_000_000L));
    }

    private static boolean deadlineExpired(long deadlineNanos) {
        return Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos;
    }

    private static boolean sqliteInterrupted(SQLException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("sqlite_interrupt") || message.contains("interrupted");
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private int computeFtsFetchLimit(Connection conn, String source, int lim, FtsQueryPlan plan)
            throws SQLException {
        int defaultFetch = Math.min(Math.max(lim * 4, lim + 5), 120);
        if (!CatalogSourceRegistry.BIG_FTS_SOURCES.contains(source) || !plan.ordered()) {
            return defaultFetch;
        }
        int matchCount = countFtsMatches(conn, source, plan.matchExpr());
        if (matchCount <= FTS_DEEP_FETCH_MATCH_THRESHOLD) {
            return Math.min(Math.max(defaultFetch, lim * 10), Math.min(matchCount, FTS_DEEP_FETCH_MAX));
        }
        if (matchCount <= FTS_BM25_ORDER_THRESHOLD) {
            return Math.min(Math.max(defaultFetch, 250), 350);
        }
        return defaultFetch;
    }

    /**
     * Direct rescue for well-known FRED equity indices when the query clearly names NASDAQ but the
     * bm25 window would still miss canonical {@code NASDAQ100}/{@code NASDAQCOM} rows.
     */
    private List<Map<String, Object>> mergeEntityRescueRows(
            Connection conn,
            String source,
            String queryRaw,
            List<String> needles,
            List<Map<String, Object>> rows,
            int fetchLimit) throws SQLException {
        if (!"fred".equals(source)) {
            return rows;
        }
        String folded = CatalogTextUtils.foldAscii(queryRaw == null ? "" : queryRaw);
        if (!CatalogTextUtils.containsTokenOrPhrase(folded, "nasdaq")) {
            return rows;
        }
        List<String> rescueIds = new ArrayList<>();
        rescueIds.add("NASDAQ100");
        rescueIds.add("NASDAQCOM");
        if (folded.contains("100")) {
            rescueIds.add(0, "NASDAQ100");
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            seen.add(rowIdentityKey(source, row));
        }
        List<Map<String, Object>> merged = new ArrayList<>(rows);
        for (String setId : rescueIds) {
            if (seen.size() >= fetchLimit + rescueIds.size()) {
                break;
            }
            Optional<Map<String, Object>> hit = lookupSqliteOnConnection(conn, source, setId);
            if (hit.isEmpty()) {
                continue;
            }
            String key = rowIdentityKey(source, hit.get());
            if (key.isBlank() || seen.contains(key)) {
                continue;
            }
            Map<String, Object> rescued = new LinkedHashMap<>(hit.get());
            rescued.put("_entity_rescue", true);
            rescued.put("_fts_rank", 0.0);
            seen.add(key);
            merged.add(rescued);
        }
        return merged;
    }

    /**
     * Chooses between bm25-ordered and unordered FTS retrieval for large catalogs. Non-big sources
     * always use bm25 ordering; big sources ({@link CatalogSourceRegistry#BIG_FTS_SOURCES}) count
     * the match set first and only fall back to the legacy fast path when ordering would be too
     * expensive, optionally narrowing to the rarest quoted token from the OR-expanded match expr.
     */
    static FtsQueryPlan resolveFtsQueryPlan(Connection conn, String source, String matchExpr) throws SQLException {
        if (!CatalogSourceRegistry.BIG_FTS_SOURCES.contains(source)) {
            return new FtsQueryPlan(matchExpr, true);
        }
        if ("\"\"".equals(matchExpr)) {
            return new FtsQueryPlan(matchExpr, true);
        }
        int matchCount = countFtsMatches(conn, source, matchExpr);
        if (matchCount <= FTS_BM25_ORDER_THRESHOLD) {
            String anchored = anchoredMatchExpr(conn, source, matchExpr);
            if (!anchored.equals(matchExpr)) {
                int anchoredCount = countFtsMatches(conn, source, anchored);
                if (anchoredCount > 0
                        && anchoredCount < matchCount
                        && anchoredCount <= FTS_DEEP_FETCH_MATCH_THRESHOLD) {
                    return new FtsQueryPlan(anchored, true);
                }
            }
            return new FtsQueryPlan(matchExpr, true);
        }
        String anchored = anchoredMatchExpr(conn, source, matchExpr);
        if (!anchored.equals(matchExpr)) {
            int anchoredCount = countFtsMatches(conn, source, anchored);
            if (anchoredCount <= FTS_BM25_ORDER_THRESHOLD) {
                return new FtsQueryPlan(anchored, true);
            }
            return new FtsQueryPlan(anchored, false);
        }
        return new FtsQueryPlan(matchExpr, false);
    }

    static int countFtsMatches(Connection conn, String source, String matchExpr) throws SQLException {
        // Query planning only needs to distinguish an exact small cardinality from "larger than the
        // bm25 threshold". Counting the complete match set made broad terms scan millions of FTS rows
        // even though every value above the threshold leads to the same plan. The bounded subquery
        // preserves that decision while allowing SQLite to stop as soon as the answer is known.
        try (PreparedStatement ps = conn.prepareStatement(SQL_FTS_COUNT_BOUNDED)) {
            ps.setString(1, source);
            ps.setString(2, matchExpr);
            ps.setInt(3, FTS_BM25_ORDER_THRESHOLD + 1);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * When a broad OR match explodes (e.g. {@code "nasdaq" OR "cena" OR "price"}), retry with the
     * rarest quoted token so bm25 ordering stays tractable and major entities are not drowned out.
     */
    static String anchoredMatchExpr(Connection conn, String source, String matchExpr) throws SQLException {
        List<String> tokens = parseQuotedFtsTokens(matchExpr);
        if (tokens.isEmpty()) {
            return matchExpr;
        }
        String bestToken = null;
        int bestCount = Integer.MAX_VALUE;
        int bestLen = -1;
        for (String token : tokens) {
            String single = ftsQuotedToken(token);
            int count = countFtsMatches(conn, source, single);
            if (count <= 0) {
                continue;
            }
            int len = token.length();
            if (count < bestCount || (count == bestCount && len > bestLen)) {
                bestCount = count;
                bestLen = len;
                bestToken = token;
            }
        }
        if (bestToken == null) {
            return matchExpr;
        }
        return ftsQuotedToken(bestToken);
    }

    static List<String> parseQuotedFtsTokens(String matchExpr) {
        if (matchExpr == null || matchExpr.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher matcher = FTS_QUOTED_TOKEN.matcher(matchExpr);
        while (matcher.find()) {
            String token = matcher.group(1).trim();
            if (!token.isBlank()) {
                out.add(token);
            }
        }
        return out;
    }

    private static String ftsQuotedToken(String token) {
        return "\"" + CatalogTextUtils.ftsEscapeToken(token) + "\"";
    }

    record FtsQueryPlan(String matchExpr, boolean ordered) {}

    private record SqliteFtsOutcome(List<Map<String, Object>> rows, boolean budgetExhausted) {}

    private List<Map<String, Object>> lookupLikeFallback(
            Connection conn, String source, String queryRaw, int limit) throws SQLException {
        String folded = CatalogTextUtils.foldAscii(queryRaw);
        String like = "%" + folded.substring(0, Math.min(40, folded.length())) + "%";
        String sql =
                "SELECT row_json, 0 AS rank FROM catalog_rows_lookup WHERE source = ? AND row_json LIKE ? LIMIT ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, source);
            ps.setString(2, like);
            ps.setInt(3, limit + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> parsed = parseRowJson(rs.getString("row_json"));
                    if (parsed != null) {
                        rows.add(parsed);
                    }
                }
            }
        }
        return rows;
    }

    private Optional<Map<String, Object>> lookupSqlite(String source, String setId) {
        try (PooledConnection pooled = borrowPooled()) {
            return lookupSqliteOnConnection(pooled.connection(), source, setId);
        } catch (SQLException ex) {
            log.warn("lookup sqlite source={} set_id={}: {}", source, setId, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Map<String, Object>> lookupSqliteOnConnection(Connection conn, String source, String setId)
            throws SQLException {
        String sql = "SELECT row_json FROM catalog_rows_lookup WHERE source = ? AND set_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, source);
            ps.setString(2, setId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(parseRowJson(rs.getString("row_json")));
                }
            }
        }
        return Optional.empty();
    }

    private List<Map<String, Object>> jsonlSuggest(String queryRaw, int limit, List<String> sources) {
        List<String> needles = CatalogTextUtils.needlesFromQuery(queryRaw.trim());
        int lim = Math.max(1, Math.min(limit, 50));
        int perSource = Math.max(12, Math.min(lim * 3, 40));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String source : sources) {
            out.addAll(jsonlScan(source, needles, queryRaw, perSource, true));
        }
        return out;
    }

    private List<Map<String, Object>> jsonlSearch(String source, String queryRaw, int limit) {
        List<String> needles = CatalogTextUtils.needlesFromQuery(queryRaw.trim());
        return scoreRows(source, queryRaw, jsonlScan(source, needles, queryRaw, limit + 5, false), limit);
    }

    private Optional<Map<String, Object>> lookupJsonl(String source, String setId) {
        var path = properties.jsonlPath(source);
        if (!Files.isRegularFile(path)) {
            if ("oecd".equals(source)) {
                path = properties.jsonlPath("oecd4");
            }
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
        }
        String target = setId.toLowerCase(Locale.ROOT);
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> row = objectMapper.readValue(line, MAP_TYPE);
                String sid = String.valueOf(row.getOrDefault("set_id", row.getOrDefault("id", ""))).trim();
                if (!sid.isBlank() && sid.equalsIgnoreCase(target)) {
                    return Optional.of(row);
                }
            }
        } catch (Exception ex) {
            log.warn("lookup jsonl source={} set_id={}: {}", source, setId, ex.getMessage());
        }
        return Optional.empty();
    }

    private List<Map<String, Object>> jsonlScan(
            String source, List<String> needles, String queryRaw, int limit, boolean slim) {
        var path = properties.jsonlPath(source);
        if (!Files.isRegularFile(path)) {
            if ("oecd".equals(source)) {
                path = properties.jsonlPath("oecd4");
                source = "oecd4";
            }
            if (!Files.isRegularFile(path)) {
                return List.of();
            }
        }
        List<Map<String, Object>> hits = new ArrayList<>();
        int scanned = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null && hits.size() < limit && scanned < JSONL_SCAN_CAP) {
                scanned++;
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> row = objectMapper.readValue(line, MAP_TYPE);
                String sid = String.valueOf(row.getOrDefault("set_id", row.getOrDefault("id", ""))).trim();
                if (sid.isBlank()) {
                    continue;
                }
                String title = CatalogTextUtils.rowTitle(row);
                String titleFold = CatalogTextUtils.foldAscii(title);
                String blob = CatalogTextUtils.foldAscii(
                        String.valueOf(row.getOrDefault("search_blob", row.getOrDefault("_search_blob", ""))));
                int score = CatalogTextUtils.titleMatchScore(titleFold, needles);
                if (score <= 0) {
                    boolean blobHit = needles.stream()
                            .anyMatch(n -> !n.isBlank() && CatalogTextUtils.containsTokenOrPhrase(blob, n));
                    if (!blobHit && !CatalogTextUtils.foldAscii(queryRaw).isBlank()) {
                        String qf = CatalogTextUtils.foldAscii(queryRaw);
                        if (CatalogTextUtils.containsTokenOrPhrase(titleFold, qf)
                                || CatalogTextUtils.containsTokenOrPhrase(blob, qf)) {
                            score = 5;
                        }
                    } else if (blobHit) {
                        score = 5;
                    }
                }
                if (score <= 0) {
                    continue;
                }
                if (slim) {
                    Map<String, Object> slimRow = new LinkedHashMap<>();
                    slimRow.put("source", source);
                    slimRow.put("set_id", sid);
                    slimRow.put("title", title);
                    slimRow.put(
                            "full_path",
                            String.valueOf(row.getOrDefault("full_path", row.getOrDefault("tree_path", ""))).trim());
                    slimRow.put("_match", score);
                    slimRow.put("_fts_rank", 0.0);
                    hits.add(slimRow);
                } else {
                    row.put("_match", score);
                    row.put("_fts_rank", 0.0);
                    hits.add(row);
                }
            }
        } catch (Exception ex) {
            log.warn("jsonl scan source={}: {}", source, ex.getMessage());
        }
        hits.sort(Comparator.comparingInt((Map<String, Object> r) -> -(int) r.getOrDefault("_match", 0))
                .thenComparingDouble(r -> (double) r.getOrDefault("_fts_rank", 0.0)));
        if (hits.size() > limit) {
            return new ArrayList<>(hits.subList(0, limit));
        }
        return hits;
    }

    private List<Map<String, Object>> scoreRows(
            String source, String queryRaw, List<Map<String, Object>> rows, int limit) {
        return scoringPipeline.scoreAndRankAsMaps(source, queryRaw, rows, limit);
    }

    private Map<String, Object> parseRowJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (Exception ex) {
            return null;
        }
    }

    private PooledConnection borrowPooled() throws SQLException {
        return new PooledConnection(openReadOnlyConnection());
    }

    private Connection openReadOnlyConnection() throws SQLException {
        return sqlitePool.borrow();
    }

    private void releaseConnection(Connection conn) {
        sqlitePool.release(conn);
    }

    private final class PooledConnection implements AutoCloseable {
        private final Connection connection;

        private PooledConnection(Connection connection) {
            this.connection = connection;
        }

        private Connection connection() {
            return connection;
        }

        @Override
        public void close() {
            releaseConnection(connection);
        }
    }
}
