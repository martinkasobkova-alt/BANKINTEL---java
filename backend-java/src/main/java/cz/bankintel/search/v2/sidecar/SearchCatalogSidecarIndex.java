package cz.bankintel.search.v2.sidecar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cz.bankintel.search.CatalogSearchProperties;
import cz.bankintel.search.CatalogSourceRegistry;
import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.CzTextStemmer;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.util.BankIntelEnvVars;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SearchCatalogSidecarIndex {

    public static final String MODE_LEGACY = "legacy";
    public static final String MODE_SIDECAR = "sidecar";
    private static final Logger log = LoggerFactory.getLogger(SearchCatalogSidecarIndex.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int INSERT_BATCH_SIZE = 1_000;
    private static final int SQLITE_SEARCH_TIMEOUT_SECONDS = 1;
    private static final int MAX_SOURCE_RECALL_QUOTA = 5;
    private static final int MIN_STRICT_RESULTS_BEFORE_SKIPPING_RELAXED = 6;
    private static final double STRICT_RECALL_SHARE = 0.75;
    private static final Set<String> QUERY_STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "by", "do", "for", "from", "in", "is", "je", "jsou",
            "k", "ke", "na", "o", "od", "of", "on", "pro", "s", "se", "the", "to", "u", "v", "ve", "z", "ze");

    private final CatalogSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final SearchCatalogSidecarBuilder builder;
    private final Set<String> indexedSources = ConcurrentHashMap.newKeySet();
    private final AtomicLong contentRevision = new AtomicLong();
    private final AtomicReference<CoverageSnapshot> coverageSnapshot = new AtomicReference<>();
    private final AtomicBoolean coverageRefreshRunning = new AtomicBoolean();
    private final ExecutorService coverageExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("search-sidecar-coverage-", 0).factory());

    // FTS timeout validation experiment (benchmark-only, read-and-cleared same-thread - see
    // SearchV2FtsRetriever#retrieveSidecar): captures strict/relaxed lane outcome for the query this
    // thread just ran through searchHybridSqlite, including when the relaxed lane throws (e.g. its own
    // SQLITE_SEARCH_TIMEOUT_SECONDS aborts it) - diagnostics-only, never changes what is returned or
    // thrown to the caller.
    private static final ThreadLocal<Map<String, Object>> LAST_HYBRID_DIAGNOSTICS = new ThreadLocal<>();

    public static Map<String, Object> drainLastHybridDiagnostics() {
        Map<String, Object> out = LAST_HYBRID_DIAGNOSTICS.get();
        LAST_HYBRID_DIAGNOSTICS.remove();
        return out == null ? Map.of() : out;
    }

    public SearchCatalogSidecarIndex(
            CatalogSearchProperties properties, ObjectMapper objectMapper, SearchCatalogSidecarBuilder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.builder = builder;
    }

    public String configuredMode(Map<String, Object> request) {
        String override = CatalogMapSupport.firstNonBlank(
                request == null ? Map.of() : request,
                "search_catalog_index",
                "catalog_index",
                "SEARCH_CATALOG_INDEX");
        String raw = override.isBlank() ? BankIntelEnvVars.get("SEARCH_CATALOG_INDEX") : override;
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return MODE_SIDECAR.equals(value) ? MODE_SIDECAR : MODE_LEGACY;
    }

    public boolean sidecarEnabled(String mode) {
        return MODE_SIDECAR.equalsIgnoreCase(mode);
    }

    /** Changes whenever a successful rebuild can make cached search results stale. */
    public long contentRevision() {
        return contentRevision.get();
    }

    public List<Map<String, Object>> search(String source, String queryRaw, int limit) {
        return search(List.of(source), queryRaw, limit);
    }

    public List<Map<String, Object>> search(List<String> sources, String queryRaw, int limit) {
        return searchInternal(sources, queryRaw, limit, true, new ConcurrentHashMap<>());
    }

    /**
     * Searches a shared source set in one FTS pass. Caller-side candidate merging is responsible for source
     * diversity, so this path intentionally avoids issuing another query for every source.
     */
    public List<Map<String, Object>> searchGlobal(List<String> sources, String queryRaw, int limit) {
        return searchGlobal(sources, queryRaw, limit, new ConcurrentHashMap<>());
    }

    /**
     * Perf fix (duplicate FTS query elimination - see FtsQueryCacheKey): {@code dedupeCache} lets a
     * caller share one cache across MULTIPLE searchGlobal calls - e.g. one per query-expansion variant
     * within the same request - so two variants whose final FTS MATCH expression is byte-identical
     * after normalization/token-capping execute the underlying SQL only once. Keyed on the actual MATCH
     * expression, not the input query text, so it catches duplicates the caller cannot detect from the
     * variant text alone (e.g. two differently-worded variants that both truncate to the same 8
     * tokens). The caller owns the cache's lifetime: pass a fresh map per logical request/retrieval
     * call, never a shared/static one - unbounded growth risk, and index content can change between
     * requests.
     */
    public List<Map<String, Object>> searchGlobal(
            List<String> sources,
            String queryRaw,
            int limit,
            Map<FtsQueryCacheKey, List<Map<String, Object>>> dedupeCache) {
        return searchInternal(sources, queryRaw, limit, false, dedupeCache);
    }

    /**
     * Cache key for the FTS dedup above - equal only when the actual SQL MATCH expression, the source
     * set, and the row limit are all identical. Two query-expansion variants with different raw text
     * can produce an equal key once {@code queryTokens()}/{@code buildMatch}/{@code buildRelaxedMatch}
     * normalize and cap them - that is precisely the case this record is meant to catch.
     */
    public record FtsQueryCacheKey(List<String> sources, String matchExpr, int limit) {}

    private static final class FtsQueryExecutionException extends RuntimeException {
        private final Exception original;

        FtsQueryExecutionException(Exception original) {
            super(original);
            this.original = original;
        }

        Exception original() {
            return original;
        }
    }

    // Test-only: counts actual SQL executions (i.e. dedupeCache misses), to prove duplicate MATCH
    // expressions within one cache no longer run the query twice.
    private static final AtomicLong FTS_QUERY_EXECUTION_COUNT = new AtomicLong();

    public static long ftsQueryExecutionCountForTest() {
        return FTS_QUERY_EXECUTION_COUNT.get();
    }

    private List<Map<String, Object>> searchInternal(
            List<String> sources,
            String queryRaw,
            int limit,
            boolean reservePerSource,
            Map<FtsQueryCacheKey, List<Map<String, Object>>> dedupeCache) {
        List<String> normalizedSources = (sources == null ? List.<String>of() : sources).stream()
                .map(CatalogSourceRegistry::normalizeSearchSource)
                .filter(source -> !source.isBlank())
                .distinct()
                .toList();
        if (normalizedSources.isEmpty() || queryRaw == null || queryRaw.trim().length() < 2) {
            return List.of();
        }
        int lim = Math.max(1, Math.min(limit, 500));
        try {
            for (String source : normalizedSources) {
                ensureSourceIndexed(source);
            }
            return reservePerSource
                    ? searchBalancedSqlite(normalizedSources, queryRaw, lim, dedupeCache)
                    : searchHybridSqlite(normalizedSources, queryRaw, lim, dedupeCache);
        } catch (Exception ex) {
            log.warn("search_v2 sidecar sqlite search failed sources={}: {}", normalizedSources, ex.getMessage());
            return List.of();
        }
    }

    public synchronized Map<String, Object> rebuild(List<String> requestedSources) {
        long start = System.currentTimeMillis();
        boolean fullRebuild = requestedSources == null || requestedSources.isEmpty();
        List<String> sources = fullRebuild
                ? availableSources()
                : requestedSources.stream()
                        .map(CatalogSourceRegistry::normalizeSearchSource)
                        .filter(s -> !s.isBlank())
                        .distinct()
                        .toList();
        List<Map<String, Object>> sourceStats = new ArrayList<>();
        int docs = 0;
        int changedDocs = 0;
        try {
            Files.createDirectories(properties.sidecarDir());
            try (Connection conn = openConnection()) {
                initializeSchema(conn);
                for (String source : sources) {
                    BuildStats stats = buildSource(conn, source);
                    docs += stats.documentCount();
                    changedDocs += stats.changedCount() + stats.deletedCount();
                    sourceStats.add(stats.toMap());
                    indexedSources.add(source);
                }
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA optimize");
                }
            }
            if (changedDocs > 0) {
                contentRevision.incrementAndGet();
            }
        } catch (Exception ex) {
            return Map.of(
                    "ok", false,
                    "status", "error",
                    "error", ex.getMessage(),
                    "duration_ms", System.currentTimeMillis() - start);
        }
        return Map.of(
                "ok", true,
                "status", "ok",
                "index_mode", MODE_SIDECAR,
                "sidecar_dir", properties.sidecarDir().toString(),
                "fts_db", properties.sidecarFtsDbPath().toString(),
                "rebuild_mode", "incremental",
                "sources", sourceStats,
                "document_count", docs,
                "changed_document_count", changedDocs,
                "duration_ms", System.currentTimeMillis() - start);
    }

    public Map<String, Object> coverage() {
        CoverageSnapshot snapshot = coverageSnapshot.get();
        long revision = contentRevision.get();
        boolean stale = snapshot == null || snapshot.contentRevision() != revision;
        if (stale) {
            refreshCoverageAsync(revision);
        }

        if (snapshot == null) {
            return Map.of(
                    "status", "building",
                    "refresh_running", true,
                    "content_revision", revision,
                    "sidecar_dir", properties.sidecarDir().toString(),
                    "fts_db", properties.sidecarFtsDbPath().toString(),
                    "sources", List.of(),
                    "document_count", 0);
        }

        Map<String, Object> out = new LinkedHashMap<>(snapshot.payload());
        out.put("status", stale ? "stale" : "ready");
        out.put("refresh_running", stale && coverageRefreshRunning.get());
        out.put("content_revision", snapshot.contentRevision());
        out.put("snapshot_created_at", snapshot.createdAt().toString());
        out.put("snapshot_compute_ms", snapshot.computeMs());
        return out;
    }

    private void refreshCoverageAsync(long requestedRevision) {
        if (!coverageRefreshRunning.compareAndSet(false, true)) {
            return;
        }
        coverageExecutor.submit(() -> {
            long started = System.currentTimeMillis();
            try {
                Map<String, Object> payload = computeCoverage();
                coverageSnapshot.set(new CoverageSnapshot(
                        requestedRevision,
                        Instant.now(),
                        System.currentTimeMillis() - started,
                        Map.copyOf(payload)));
            } catch (Exception ex) {
                log.warn("search_v2 sidecar coverage snapshot refresh failed: {}", ex.getMessage());
            } finally {
                coverageRefreshRunning.set(false);
                if (contentRevision.get() != requestedRevision) {
                    refreshCoverageAsync(contentRevision.get());
                }
            }
        });
    }

    private Map<String, Object> computeCoverage() {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (String source : availableSources()) {
            try {
                sources.add(sourceCoverage(source));
            } catch (Exception ex) {
                sources.add(Map.of("source", source, "ok", false, "error", ex.getMessage()));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sidecar_dir", properties.sidecarDir().toString());
        out.put("fts_db", properties.sidecarFtsDbPath().toString());
        out.put("sources", sources);
        out.put("document_count", sources.stream().mapToInt(s -> CatalogMapSupport.toInt(s.get("rows"), 0)).sum());
        return out;
    }

    public void forEachDocument(Consumer<SearchCatalogSidecarDocument> consumer) throws Exception {
        if (consumer == null || !Files.isRegularFile(properties.sidecarFtsDbPath())) {
            return;
        }
        try (Connection conn = openConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT doc_json FROM sidecar_doc ORDER BY source, series_id, dataset");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                consumer.accept(objectMapper.readValue(
                        rs.getString("doc_json"), SearchCatalogSidecarDocument.class));
            }
        }
    }

    public Map<SearchCatalogSidecarDocumentKey, SearchCatalogSidecarDocument> documents(
            List<SearchCatalogSidecarDocumentKey> keys) {
        if (keys == null || keys.isEmpty() || !Files.isRegularFile(properties.sidecarFtsDbPath())) {
            return Map.of();
        }
        Map<SearchCatalogSidecarDocumentKey, SearchCatalogSidecarDocument> out = new LinkedHashMap<>();
        String sql = "SELECT doc_json FROM sidecar_doc WHERE source = ? AND series_id = ? AND dataset = ?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (SearchCatalogSidecarDocumentKey key : keys) {
                ps.setString(1, key.source());
                ps.setString(2, key.seriesId());
                ps.setString(3, key.dataset());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        out.put(key, objectMapper.readValue(
                                rs.getString("doc_json"), SearchCatalogSidecarDocument.class));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("search_v2 sidecar vector document lookup failed: {}", ex.getMessage());
        }
        return out;
    }

    public synchronized Map<String, Object> optimize() {
        long start = System.currentTimeMillis();
        try (Connection conn = openConnection()) {
            initializeSchema(conn);
            optimizeIndex(conn);
            return Map.of(
                    "ok", true,
                    "status", "ok",
                    "duration_ms", System.currentTimeMillis() - start);
        } catch (Exception ex) {
            return Map.of(
                    "ok", false,
                    "status", "error",
                    "error", ex.getMessage(),
                    "duration_ms", System.currentTimeMillis() - start);
        }
    }

    private synchronized void ensureSourceIndexed(String source) throws Exception {
        if (indexedSources.contains(source) && Files.isRegularFile(properties.sidecarFtsDbPath())) {
            return;
        }
        Files.createDirectories(properties.sidecarDir());
        try (Connection conn = openConnection()) {
            initializeSchema(conn);
            if (sourceIndexed(conn, source)) {
                indexedSources.add(source);
                return;
            }
            BuildStats stats = buildSource(conn, source);
            indexedSources.add(source);
            log.info("search_v2 sidecar source indexed source={} docs={}", source, stats.documentCount());
        }
    }

    private BuildStats buildSource(Connection conn, String source) throws Exception {
        long start = System.currentTimeMillis();
        String signature = sourceSignature(source);
        SourceState previous = sourceState(conn, source);
        if (sourceIndexed(conn, source) && previous != null && signature.equals(previous.signature())) {
            return BuildStats.skipped(source, previous, System.currentTimeMillis() - start);
        }

        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS temp.sidecar_seen_doc");
            st.execute("CREATE TEMP TABLE sidecar_seen_doc(series_id TEXT NOT NULL, dataset TEXT NOT NULL, PRIMARY KEY(series_id, dataset)) WITHOUT ROWID");
        }
        conn.setAutoCommit(false);
        int[] scanned = {0};
        int[] changed = {0};
        int[] unchanged = {0};
        int[] current = {0};
        int[] historical = {0};
        try (PreparedStatement seenPs = conn.prepareStatement(
                        "INSERT OR IGNORE INTO sidecar_seen_doc(series_id, dataset) VALUES(?,?)");
                PreparedStatement existingPs = conn.prepareStatement("""
                        SELECT doc_json, content_hash, fts_rowid FROM sidecar_doc
                        WHERE source=? AND series_id=? AND dataset=?
                        """);
                PreparedStatement docPs = conn.prepareStatement("""
                        INSERT INTO sidecar_doc(source, series_id, dataset, doc_json, metadata_quality, content_hash, fts_rowid)
                        VALUES(?,?,?,?,?,?,?)
                        ON CONFLICT(source, series_id, dataset) DO UPDATE SET
                            doc_json=excluded.doc_json,
                            metadata_quality=excluded.metadata_quality,
                            content_hash=excluded.content_hash,
                            fts_rowid=excluded.fts_rowid
                        WHERE sidecar_doc.content_hash <> excluded.content_hash
                        """);
                PreparedStatement deleteFtsPs = conn.prepareStatement("DELETE FROM sidecar_fts WHERE rowid=?");
                PreparedStatement ftsPs = conn.prepareStatement(
                        "INSERT INTO sidecar_fts(rowid, source, series_id, dataset, canonical_title, primary_concept, aliases, original_title, description, category, geo, source_label) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            forEachSourceDocument(source, doc -> {
                scanned[0]++;
                if ("historical".equals(doc.lifecycleStatus())) {
                    historical[0]++;
                } else {
                    current[0]++;
                }
                seenPs.setString(1, doc.seriesId());
                seenPs.setString(2, doc.dataset());
                seenPs.addBatch();

                String docJson = objectMapper.writeValueAsString(doc);
                String contentHash = stableContentHash(doc);
                ExistingDocument existing = existingDocument(existingPs, doc);
                if (existing != null && contentHash.equals(existing.contentHash())) {
                    unchanged[0]++;
                    if (scanned[0] % INSERT_BATCH_SIZE == 0) {
                        seenPs.executeBatch();
                        seenPs.clearBatch();
                    }
                    return;
                }
                boolean ftsChanged = existing == null || !ftsContentHash(doc).equals(existing.ftsContentHash());
                long ftsRowId = ftsChanged
                        ? stableFtsRowId(doc.source(), doc.seriesId(), doc.dataset())
                        : existing.ftsRowId();
                docPs.setString(1, doc.source());
                docPs.setString(2, doc.seriesId());
                docPs.setString(3, doc.dataset());
                docPs.setString(4, docJson);
                docPs.setDouble(5, doc.metadataQualityScore());
                docPs.setString(6, contentHash);
                docPs.setLong(7, ftsRowId);
                docPs.executeUpdate();
                changed[0]++;
                if (ftsChanged) {
                    if (existing != null && existing.ftsRowId() > 0) {
                        deleteFtsPs.setLong(1, existing.ftsRowId());
                        deleteFtsPs.executeUpdate();
                    }
                    queueFtsDocument(ftsPs, doc, ftsRowId);
                }
                if (scanned[0] % INSERT_BATCH_SIZE == 0) {
                    seenPs.executeBatch();
                    ftsPs.executeBatch();
                    seenPs.clearBatch();
                    ftsPs.clearBatch();
                }
            });
            seenPs.executeBatch();
            ftsPs.executeBatch();

            int deleted = deleteStaleDocuments(conn, source);
            upsertSourceState(conn, source, signature, scanned[0], current[0], historical[0]);
            conn.commit();
            return new BuildStats(
                    source, scanned[0], changed[0], unchanged[0], deleted, current[0], historical[0], false,
                    System.currentTimeMillis() - start);
        } catch (Exception ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS temp.sidecar_seen_doc");
            }
        }
    }

    private void queueFtsDocument(PreparedStatement ftsPs, SearchCatalogSidecarDocument doc, long ftsRowId) throws Exception {
        ftsPs.setLong(1, ftsRowId);
        ftsPs.setString(2, doc.source());
        ftsPs.setString(3, doc.seriesId());
        ftsPs.setString(4, doc.dataset());
        ftsPs.setString(5, firstNonBlank(doc.canonicalTitleCs(), doc.canonicalTitleEn()));
        ftsPs.setString(6, doc.primaryConcept());
        ftsPs.setString(7, String.join(" ", concat(doc.aliasesCs(), doc.aliasesEn(), doc.abbreviations())));
        ftsPs.setString(8, doc.originalTitle());
        ftsPs.setString(9, firstNonBlank(
                doc.canonicalDescriptionCs(), doc.canonicalDescriptionEn(), doc.originalDescription()));
        ftsPs.setString(10, String.join(" ", doc.concepts()) + " " + doc.dataset());
        ftsPs.setString(11, doc.geo());
        ftsPs.setString(12, doc.source());
        ftsPs.addBatch();
    }

    private String stableContentHash(SearchCatalogSidecarDocument doc) throws Exception {
        ObjectNode node = objectMapper.valueToTree(doc);
        node.remove("updated_at");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(node));
        return HexFormat.of().formatHex(digest);
    }

    private ExistingDocument existingDocument(PreparedStatement ps, SearchCatalogSidecarDocument doc) throws Exception {
        ps.setString(1, doc.source());
        ps.setString(2, doc.seriesId());
        ps.setString(3, doc.dataset());
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            SearchCatalogSidecarDocument old = objectMapper.readValue(
                    rs.getString("doc_json"), SearchCatalogSidecarDocument.class);
            return new ExistingDocument(
                    rs.getString("content_hash"), rs.getLong("fts_rowid"), ftsContentHash(old));
        }
    }

    private String ftsContentHash(SearchCatalogSidecarDocument doc) throws Exception {
        String content = String.join("\u001f",
                doc.source(),
                doc.seriesId(),
                doc.dataset(),
                firstNonBlank(doc.canonicalTitleCs(), doc.canonicalTitleEn()),
                doc.primaryConcept(),
                String.join(" ", concat(doc.aliasesCs(), doc.aliasesEn(), doc.abbreviations())),
                doc.originalTitle(),
                firstNonBlank(doc.canonicalDescriptionCs(), doc.canonicalDescriptionEn(), doc.originalDescription()),
                String.join(" ", doc.concepts()) + " " + doc.dataset(),
                doc.geo(),
                doc.source());
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static long stableFtsRowId(String source, String seriesId, String dataset) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((source + "\u001f" + seriesId + "\u001f" + dataset).getBytes(StandardCharsets.UTF_8));
        long value = ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE;
        return value == 0 ? 1 : value;
    }

    private String sourceSignature(String source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        updateSignature(digest, properties.jsonlPath(source));
        updateSignature(digest, properties.metadataPath(source));
        digest.update(builder.enrichmentVersion().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateSignature(MessageDigest digest, Path path) throws Exception {
        String value = path.toAbsolutePath().normalize()
                + "|" + (Files.isRegularFile(path) ? Files.size(path) : -1)
                + "|" + (Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : -1);
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private SourceState sourceState(Connection conn, String source) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT input_signature, document_count, current_count, historical_count
                FROM sidecar_source_state WHERE source=?
                """)) {
            ps.setString(1, source);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new SourceState(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4))
                        : null;
            }
        }
    }

    private static void upsertSourceState(
            Connection conn, String source, String signature, int documents, int current, int historical)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO sidecar_source_state(
                    source, input_signature, document_count, current_count, historical_count, last_success_at)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(source) DO UPDATE SET
                    input_signature=excluded.input_signature,
                    document_count=excluded.document_count,
                    current_count=excluded.current_count,
                    historical_count=excluded.historical_count,
                    last_success_at=excluded.last_success_at
                """)) {
            ps.setString(1, source);
            ps.setString(2, signature);
            ps.setInt(3, documents);
            ps.setInt(4, current);
            ps.setInt(5, historical);
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    private static int deleteStaleDocuments(Connection conn, String source) throws Exception {
        List<Long> staleRowIds = new ArrayList<>();
        try (PreparedStatement select = conn.prepareStatement("""
                SELECT d.fts_rowid FROM sidecar_doc d
                WHERE d.source=? AND NOT EXISTS (
                    SELECT 1 FROM sidecar_seen_doc s
                    WHERE s.series_id=d.series_id AND s.dataset=d.dataset)
                """)) {
            select.setString(1, source);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    staleRowIds.add(rs.getLong(1));
                }
            }
        }
        try (PreparedStatement deleteFts = conn.prepareStatement("DELETE FROM sidecar_fts WHERE rowid=?")) {
            for (long rowId : staleRowIds) {
                if (rowId > 0) {
                    deleteFts.setLong(1, rowId);
                    deleteFts.addBatch();
                }
            }
            deleteFts.executeBatch();
        }
        try (PreparedStatement deleteDocs = conn.prepareStatement("""
                DELETE FROM sidecar_doc
                WHERE source=? AND NOT EXISTS (
                    SELECT 1 FROM sidecar_seen_doc s
                    WHERE s.series_id=sidecar_doc.series_id AND s.dataset=sidecar_doc.dataset)
                """)) {
            deleteDocs.setString(1, source);
            deleteDocs.executeUpdate();
        }
        return staleRowIds.size();
    }

    private static void optimizeIndex(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO sidecar_fts(sidecar_fts) VALUES('optimize')");
            st.execute("PRAGMA optimize");
        }
    }

    private List<Map<String, Object>> searchSqlite(
            List<String> sources,
            String queryRaw,
            int limit,
            RetrievalLane lane,
            Map<FtsQueryCacheKey, List<Map<String, Object>>> dedupeCache) throws Exception {
        String matchExpr = lane == RetrievalLane.STRICT ? buildMatch(queryRaw) : buildRelaxedMatch(queryRaw);
        if (matchExpr.isBlank()) {
            return List.of();
        }
        // Perf fix (duplicate FTS query elimination): keyed on the FINAL match expression, sources, and
        // limit - NOT on queryRaw/lane/variant text - so two query-expansion variants that normalize to
        // the same MATCH expression execute the underlying SQL exactly once. computeIfAbsent guarantees
        // this holds even when two variants race concurrently for the same key (one blocks on the
        // other's in-flight computation instead of both running the expensive query independently).
        FtsQueryCacheKey cacheKey = new FtsQueryCacheKey(sources, matchExpr, limit);
        try {
            return dedupeCache.computeIfAbsent(cacheKey, key -> {
                try {
                    return executeFtsQuery(sources, queryRaw, matchExpr, limit, lane);
                } catch (Exception ex) {
                    throw new FtsQueryExecutionException(ex);
                }
            });
        } catch (FtsQueryExecutionException wrapped) {
            throw wrapped.original();
        }
    }

    // Package-private (not private): overridden by SearchCatalogSidecarIndexDedupeAndFallbackTest to
    // deterministically inject a RELAXED-lane failure (real query timeouts are multi-second and flaky
    // to assert on) without touching production behavior - see that test for the override.
    List<Map<String, Object>> executeFtsQuery(
            List<String> sources, String queryRaw, String matchExpr, int limit, RetrievalLane lane)
            throws Exception {
        FTS_QUERY_EXECUTION_COUNT.incrementAndGet();
        List<ScoredDoc> docs = new ArrayList<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(sources.size(), "?"));
        String sql = """
                SELECT d.doc_json,
                       bm25(sidecar_fts, 0.1, 0.1, 0.5, 8.0, 7.0, 5.0, 3.0, 1.5, 1.0, 1.0, 0.5) AS rank
                FROM sidecar_fts
                JOIN sidecar_doc d ON d.source = sidecar_fts.source AND d.series_id = sidecar_fts.series_id AND d.dataset = sidecar_fts.dataset
                WHERE sidecar_fts.source IN (%s) AND sidecar_fts MATCH ?
                ORDER BY rank
                LIMIT ?
                """.formatted(placeholders);
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(SQLITE_SEARCH_TIMEOUT_SECONDS);
            int parameter = 1;
            for (String source : sources) {
                ps.setString(parameter++, source);
            }
            ps.setString(parameter++, matchExpr);
            ps.setInt(parameter, Math.min(Math.max(limit * 2, 40), 500));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SearchCatalogSidecarDocument doc =
                            objectMapper.readValue(rs.getString("doc_json"), SearchCatalogSidecarDocument.class);
                    double bm25 = rs.getDouble("rank");
                    docs.add(scoreDoc(doc, queryRaw, -bm25));
                }
            }
        }
        return docs.stream()
                .sorted(Comparator.comparingDouble(ScoredDoc::score).reversed())
                .limit(limit)
                .map(hit -> withRetrievalLane(
                        hit.doc().toSearchRow(hit.score(), queryRaw, hit.matchedFields()), lane))
                .toList();
    }

    /**
     * Combines a precise conjunctive lane with a bounded recall lane. The latter is deliberately small: its
     * purpose is to expose plausible partial matches to the semantic reranker, not to replace exact retrieval.
     */
    private List<Map<String, Object>> searchHybridSqlite(
            List<String> sources,
            String queryRaw,
            int limit,
            Map<FtsQueryCacheKey, List<Map<String, Object>>> dedupeCache) throws Exception {
        int strictLimit = Math.max(1, (int) Math.ceil(limit * STRICT_RECALL_SHARE));
        int relaxedLimit = Math.max(0, limit - strictLimit);
        long strictStart = System.currentTimeMillis();
        List<Map<String, Object>> strict =
                searchSqlite(sources, queryRaw, strictLimit, RetrievalLane.STRICT, dedupeCache);
        long strictLatencyMs = System.currentTimeMillis() - strictStart;
        int sufficientStrictResults = Math.min(strictLimit, MIN_STRICT_RESULTS_BEFORE_SKIPPING_RELAXED);
        if (relaxedLimit == 0
                || queryTokens(queryRaw).size() < 2
                || strict.size() >= sufficientStrictResults) {
            LAST_HYBRID_DIAGNOSTICS.set(hybridDiagnostics(
                    strict.size(), strictLatencyMs, false, false, false, 0, 0));
            return strict;
        }
        long relaxedStart = System.currentTimeMillis();
        List<Map<String, Object>> relaxed;
        try {
            relaxed = searchSqlite(sources, queryRaw, relaxedLimit, RetrievalLane.RELAXED, dedupeCache);
        } catch (Exception ex) {
            long relaxedLatencyMs = System.currentTimeMillis() - relaxedStart;
            LAST_HYBRID_DIAGNOSTICS.set(hybridDiagnostics(
                    strict.size(), strictLatencyMs, true, false, true, relaxedLatencyMs, 0));
            // Perf fix (Varianta E - relaxed-failure fallback): a failing/timing-out RELAXED lane used
            // to propagate out of this whole method, and searchInternal's outer catch turned the ENTIRE
            // hybrid search - including these already-computed STRICT results - into an empty list. Now
            // it falls back to STRICT's own results instead of discarding them. Only reached when
            // RELAXED itself throws; when RELAXED completes normally, the merge below is unchanged.
            log.warn(
                    "search_v2 sidecar relaxed lane failed/timed out (strict_result_count={}, "
                            + "relaxed_latency_ms={}), falling back to strict results: {}",
                    strict.size(), relaxedLatencyMs, ex.getMessage());
            return strict;
        }
        long relaxedLatencyMs = System.currentTimeMillis() - relaxedStart;
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> row : strict) {
            merged.putIfAbsent(searchIdentity(row), row);
        }
        for (Map<String, Object> row : relaxed) {
            merged.putIfAbsent(searchIdentity(row), row);
        }
        LAST_HYBRID_DIAGNOSTICS.set(hybridDiagnostics(
                strict.size(), strictLatencyMs, true, true, false, relaxedLatencyMs, relaxed.size()));
        return merged.values().stream().limit(limit).toList();
    }

    private static Map<String, Object> hybridDiagnostics(
            int strictResultCount,
            long strictLatencyMs,
            boolean relaxedStarted,
            boolean relaxedCompleted,
            boolean relaxedTimedOut,
            long relaxedLatencyMs,
            int relaxedResultCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strict_result_count", strictResultCount);
        out.put("strict_latency_ms", strictLatencyMs);
        out.put("relaxed_started", relaxedStarted);
        out.put("relaxed_completed", relaxedCompleted);
        out.put("relaxed_timed_out", relaxedTimedOut);
        out.put("relaxed_latency_ms", relaxedLatencyMs);
        out.put("relaxed_result_count", relaxedResultCount);
        return out;
    }

    /**
     * Preserves the strongest global matches while reserving a small recall lane for every selected source.
     * Without this lane, very large catalogs can fill the shared FTS window before a smaller catalog gets
     * a chance to contribute any candidate at all.
     */
    private List<Map<String, Object>> searchBalancedSqlite(
            List<String> sources,
            String queryRaw,
            int limit,
            Map<FtsQueryCacheKey, List<Map<String, Object>>> dedupeCache) throws Exception {
        List<Map<String, Object>> global = searchHybridSqlite(sources, queryRaw, limit, dedupeCache);
        if (sources.size() <= 1 || limit <= 1) {
            return global;
        }

        int sourceQuota = Math.max(1, Math.min(MAX_SOURCE_RECALL_QUOTA, limit / sources.size()));
        Map<String, List<Map<String, Object>>> reservedBySource = new LinkedHashMap<>();
        for (String source : sources) {
            List<Map<String, Object>> alreadyPresent = global.stream()
                    .filter(row -> source.equals(CatalogSourceRegistry.normalizeSearchSource(
                            String.valueOf(row.getOrDefault("source", "")))))
                    .limit(sourceQuota)
                    .toList();
            if (alreadyPresent.size() >= sourceQuota) {
                reservedBySource.put(source, alreadyPresent);
                continue;
            }
            reservedBySource.put(source, searchHybridSqlite(List.of(source), queryRaw, sourceQuota, dedupeCache));
        }

        Map<String, Map<String, Object>> balanced = new LinkedHashMap<>();
        for (String source : sources) {
            for (Map<String, Object> row : reservedBySource.getOrDefault(source, List.of())) {
                balanced.putIfAbsent(searchIdentity(row), row);
            }
        }
        for (Map<String, Object> row : global) {
            balanced.putIfAbsent(searchIdentity(row), row);
            if (balanced.size() >= limit) {
                break;
            }
        }
        return balanced.values().stream().limit(limit).toList();
    }

    private static String searchIdentity(Map<String, Object> row) {
        return CatalogSourceRegistry.normalizeSearchSource(String.valueOf(row.getOrDefault("source", "")))
                + ":"
                + String.valueOf(row.getOrDefault("series_id", "")).trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> withRetrievalLane(Map<String, Object> row, RetrievalLane lane) {
        Map<String, Object> annotated = new LinkedHashMap<>(row);
        annotated.put("_retrieval_lane", lane.name().toLowerCase(Locale.ROOT));
        return annotated;
    }

    private ScoredDoc scoreDoc(SearchCatalogSidecarDocument doc, String queryRaw, double baseScore) {
        List<String> queryTokens = queryTokens(queryRaw);
        List<String> matchedFields = new ArrayList<>();
        double score = baseScore;
        score += fieldScore("canonical_title", firstNonBlank(doc.canonicalTitleCs(), doc.canonicalTitleEn()), queryRaw, queryTokens, 20, matchedFields);
        score += fieldScore("primary_concept", doc.primaryConcept(), queryRaw, queryTokens, 18, matchedFields);
        score += fieldScore("structured_metadata", String.join(" ", doc.concepts()), queryRaw, queryTokens, 10, matchedFields);
        score += fieldScore("aliases", String.join(" ", concat(doc.aliasesCs(), doc.aliasesEn(), doc.abbreviations())), queryRaw, queryTokens, 12, matchedFields);
        score += fieldScore("original_title", doc.originalTitle(), queryRaw, queryTokens, 8, matchedFields);
        score += fieldScore("description", firstNonBlank(doc.canonicalDescriptionCs(), doc.canonicalDescriptionEn(), doc.originalDescription()), queryRaw, queryTokens, 2, matchedFields);
        score += fieldScore("dataset", doc.dataset(), queryRaw, queryTokens, 2, matchedFields);
        score += fieldScore("geo", doc.geo(), queryRaw, queryTokens, 2, matchedFields);
        score += fieldScore("source", doc.source(), queryRaw, queryTokens, 1, matchedFields);
        score += doc.metadataQualityScore();
        String normalizedQuery = CatalogTextUtils.normalizeTokenBoundaries(queryRaw);
        boolean historicalQuery = normalizedQuery.contains("histor") || normalizedQuery.contains("archiv");
        if ("historical".equals(doc.lifecycleStatus())) {
            score += historicalQuery ? 0.75 : -0.75;
        } else {
            score += historicalQuery ? 0.0 : 0.75 * doc.lifecycleConfidence();
        }
        return new ScoredDoc(doc, score, matchedFields.stream().distinct().toList());
    }

    private static double fieldScore(
            String field,
            String rawValue,
            String queryRaw,
            List<String> queryTokens,
            double weight,
            List<String> matchedFields) {
        String value = CatalogTextUtils.normalizeTokenBoundaries(rawValue);
        if (value.isBlank()) {
            return 0.0;
        }
        String query = CatalogTextUtils.normalizeTokenBoundaries(queryRaw);
        double score = 0.0;
        if (!query.isBlank() && (" " + value + " ").contains(" " + query + " ")) {
            score += weight * 3.0;
            matchedFields.add(field + ":phrase");
        }
        for (String token : queryTokens) {
            if ((" " + value + " ").contains(" " + token + " ")) {
                score += weight;
                matchedFields.add(field);
            }
        }
        return score;
    }

    private int forEachSourceDocument(String source, DocumentConsumer consumer) throws Exception {
        Path metadataPath = properties.metadataPath(source);
        Path rawPath = properties.jsonlPath(source);
        Map<String, Map<String, Object>> metadataBySeries = readMetadataBySeries(source, metadataPath);
        Set<String> emitted = new HashSet<>();
        int count = 0;

        if (Files.isRegularFile(rawPath)) {
            try (BufferedReader reader = Files.newBufferedReader(rawPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    Map<String, Object> raw = normalizeSource(objectMapper.readValue(line, MAP_TYPE), source);
                    String rawSeriesId = builder.rawSeriesId(raw);
                    Map<String, Object> metadata = metadataBySeries.remove(rawSeriesId);
                    SearchCatalogSidecarDocument doc = metadata == null
                            ? builder.buildRaw(raw)
                            : builder.build(overlay(raw, metadata, source));
                    String identity = documentIdentity(doc);
                    if (!identity.isBlank() && emitted.add(identity)) {
                        consumer.accept(doc);
                        count++;
                    }
                }
            }
        }

        for (Map<String, Object> metadata : metadataBySeries.values()) {
            SearchCatalogSidecarDocument doc = builder.build(normalizeSource(metadata, source));
            if (!standaloneMetadataDocument(source, doc.seriesId())) {
                continue;
            }
            String identity = documentIdentity(doc);
            if (!identity.isBlank() && emitted.add(identity)) {
                consumer.accept(doc);
                count++;
            }
        }
        return count;
    }

    private static boolean standaloneMetadataDocument(String source, String seriesId) {
        if (!"ecb2".equals(source)) {
            return true;
        }
        String normalized = seriesId == null ? "" : seriesId.trim();
        return normalized.contains("/") || normalized.regionMatches(true, 0, "ecb:", 0, 4);
    }

    private Map<String, Map<String, Object>> readMetadataBySeries(String source, Path metadataPath)
            throws Exception {
        Map<String, Map<String, Object>> metadataBySeries = new LinkedHashMap<>();
        if (!Files.isRegularFile(metadataPath)) {
            return metadataBySeries;
        }
        try (BufferedReader reader = Files.newBufferedReader(metadataPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    Map<String, Object> metadata = normalizeSource(objectMapper.readValue(line, MAP_TYPE), source);
                    SearchCatalogSidecarDocument doc = builder.build(metadata);
                    if (!doc.seriesId().isBlank()) {
                        metadataBySeries.put(doc.seriesId(), metadata);
                    }
                }
            }
        }
        return metadataBySeries;
    }

    static Map<String, Object> overlay(
            Map<String, Object> raw, Map<String, Object> metadata, String source) {
        Map<String, Object> merged = new LinkedHashMap<>(raw == null ? Map.of() : raw);
        if (metadata != null) {
            metadata.forEach((key, value) -> {
                if (hasValue(value)) {
                    merged.put(key, value);
                }
            });
        }
        merged.put("source", source);
        return merged;
    }

    private static Map<String, Object> normalizeSource(Map<String, Object> row, String source) {
        Map<String, Object> normalized = new LinkedHashMap<>(row == null ? Map.of() : row);
        normalized.put("source", source);
        return normalized;
    }

    private static boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof java.util.Collection<?> values) {
            return !values.isEmpty();
        }
        if (value instanceof Map<?, ?> values) {
            return !values.isEmpty();
        }
        return true;
    }

    private static String documentIdentity(SearchCatalogSidecarDocument doc) {
        if (doc == null || doc.source().isBlank() || doc.seriesId().isBlank()) {
            return "";
        }
        return doc.source() + "|" + doc.seriesId() + "|" + doc.dataset();
    }

    private Map<String, Object> sourceCoverage(String source) throws Exception {
        CoverageAccumulator coverage = new CoverageAccumulator(source);
        if (Files.isRegularFile(properties.sidecarFtsDbPath())) {
            try (Connection conn = openConnection();
                    PreparedStatement ps = conn.prepareStatement(
                            "SELECT doc_json FROM sidecar_doc WHERE source = ?")) {
                ps.setString(1, source);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        coverage.add(objectMapper.readValue(
                                rs.getString("doc_json"), SearchCatalogSidecarDocument.class));
                    }
                }
            }
        }
        if (coverage.rows == 0) {
            forEachSourceDocument(source, coverage::add);
        }
        return coverage.toMap();
    }

    private final class CoverageAccumulator {
        private final String source;
        private int rows;
        private int withoutDescription;
        private int withoutGeo;
        private int withoutUnit;
        private int withoutFrequency;
        private int withoutPrimaryConcept;
        private int shortTitles;
        private int codeOnlyTitles;
        private int currentRows;
        private int historicalRows;
        private int unknownLifecycleRows;
        private int inferredLifecycleRows;
        private double qualityTotal;

        private CoverageAccumulator(String source) {
            this.source = source;
        }

        private void add(SearchCatalogSidecarDocument doc) {
            rows++;
            String description = firstNonBlank(
                    doc.canonicalDescriptionCs(), doc.canonicalDescriptionEn(), doc.originalDescription());
            String title = firstNonBlank(doc.canonicalTitleCs(), doc.canonicalTitleEn(), doc.originalTitle());
            withoutDescription += description.isBlank() ? 1 : 0;
            withoutGeo += doc.geo().isBlank() ? 1 : 0;
            withoutUnit += doc.unit().isBlank() ? 1 : 0;
            withoutFrequency += doc.frequency().isBlank() ? 1 : 0;
            withoutPrimaryConcept += doc.primaryConcept().isBlank() ? 1 : 0;
            shortTitles += title.length() < 8 ? 1 : 0;
            codeOnlyTitles += looksCodeOnly(title) ? 1 : 0;
            if ("historical".equals(doc.lifecycleStatus())) {
                historicalRows++;
            } else if ("current".equals(doc.lifecycleStatus())) {
                currentRows++;
            } else {
                unknownLifecycleRows++;
            }
            inferredLifecycleRows += doc.lifecycleConfidence() < 0.75 ? 1 : 0;
            qualityTotal += doc.metadataQualityScore();
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", source);
            out.put("rows", rows);
            out.put("without_description", withoutDescription);
            out.put("without_geo", withoutGeo);
            out.put("without_unit", withoutUnit);
            out.put("without_frequency", withoutFrequency);
            out.put("without_primary_concept", withoutPrimaryConcept);
            out.put("short_titles", shortTitles);
            out.put("code_only_titles", codeOnlyTitles);
            out.put("current_rows", currentRows);
            out.put("historical_rows", historicalRows);
            out.put("unknown_lifecycle_rows", unknownLifecycleRows);
            out.put("inferred_lifecycle_rows", inferredLifecycleRows);
            out.put("avg_quality", rows == 0 ? 0.0 : qualityTotal / rows);
            return out;
        }
    }

    private static boolean looksCodeOnly(String title) {
        String t = title == null ? "" : title.trim();
        return !t.isBlank() && t.matches("[A-Z0-9_.\\-/]{3,}");
    }

    private boolean sourceIndexed(Connection conn, String source) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM sidecar_doc WHERE source = ? LIMIT 1")) {
            ps.setString(1, source);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void initializeSchema(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            // WAL is a database-level setting. Configure it only on the serialized
            // writer path; doing this for every parallel read can acquire locks and
            // make otherwise fast FTS queries time out.
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS sidecar_doc(
                        source TEXT NOT NULL,
                        series_id TEXT NOT NULL,
                        dataset TEXT NOT NULL,
                        doc_json TEXT NOT NULL,
                        metadata_quality REAL NOT NULL DEFAULT 0,
                        content_hash TEXT NOT NULL DEFAULT '',
                        fts_rowid INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(source, series_id, dataset)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS sidecar_source_state(
                        source TEXT PRIMARY KEY,
                        input_signature TEXT NOT NULL,
                        document_count INTEGER NOT NULL DEFAULT 0,
                        current_count INTEGER NOT NULL DEFAULT 0,
                        historical_count INTEGER NOT NULL DEFAULT 0,
                        last_success_at TEXT NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS sidecar_fts USING fts5(
                        source UNINDEXED,
                        series_id UNINDEXED,
                        dataset UNINDEXED,
                        canonical_title,
                        primary_concept,
                        aliases,
                        original_title,
                        description,
                        category,
                        geo,
                        source_label,
                        tokenize='unicode61 remove_diacritics 2'
                    )
                    """);
        }
        ensureColumn(conn, "sidecar_doc", "content_hash", "TEXT NOT NULL DEFAULT ''");
        ensureColumn(conn, "sidecar_doc", "fts_rowid", "INTEGER NOT NULL DEFAULT 0");
    }

    private static void ensureColumn(Connection conn, String table, String column, String definition) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private Connection openConnection() throws Exception {
        Files.createDirectories(properties.sidecarFtsDbPath().getParent());
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + properties.sidecarFtsDbPath().toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return conn;
    }

    private List<String> availableSources() {
        Set<String> sources = new LinkedHashSet<>();
        collectJsonlSources(properties.indexDir(), sources);
        collectJsonlSources(properties.metadataDir(), sources);
        return sources.stream().sorted().toList();
    }

    private static void collectJsonlSources(Path directory, Set<String> sources) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".jsonl"))
                    .map(name -> name.substring(0, name.length() - ".jsonl".length()))
                    .map(CatalogSourceRegistry::normalizeSearchSource)
                    .filter(source -> !source.isBlank())
                    .forEach(sources::add);
        } catch (Exception ex) {
            log.warn("search_v2 source scan failed dir={}: {}", directory, ex.getMessage());
        }
    }

    static String buildMatch(String queryRaw) {
        List<String> tokens = queryTokens(queryRaw);
        if (tokens.isEmpty()) {
            return "";
        }
        return tokens.stream()
                .limit(8)
                .map(SearchCatalogSidecarIndex::exactOrStemWidenedClause)
                .reduce((a, b) -> a + " AND " + b)
                .orElse("");
    }

    /**
     * Exact quoted token, widened with an FTS5 prefix query on {@link CzTextStemmer}'s stem whenever
     * it strips a long-enough Czech case suffix - e.g. a query for "domacnosti" (nominative) also
     * reaches a document titled with "domacnostem" (dative), which the exact quoted phrase alone
     * would miss (this index is tokenized {@code unicode61 remove_diacritics 2}: accents are folded,
     * nothing is stemmed). No hardcoded word-form list - {@code CzTextStemmer} is a general rule-based
     * suffix stripper. No widening (falls through to the exact clause only) for short tokens, ticker
     * symbols, dataset codes, or words with no case suffix to strip - see {@code ftsPrefixStem}'s
     * javadoc for the exact floor.
     */
    private static String exactOrStemWidenedClause(String token) {
        String exact = "\"" + token.replace("\"", " ") + "\"";
        String stem = CzTextStemmer.ftsPrefixStem(token);
        return stem.isEmpty() ? exact : "(" + stem + "* OR " + exact + ")";
    }

    static String buildRelaxedMatch(String queryRaw) {
        List<String> tokens = queryTokens(queryRaw);
        if (tokens.size() < 2) {
            return "";
        }
        String alternatives = tokens.stream()
                .limit(8)
                .map(SearchCatalogSidecarIndex::relaxedTokenClause)
                .reduce((a, b) -> a + " OR " + b)
                .orElse("");
        return alternatives.isBlank()
                ? ""
                : "{canonical_title primary_concept aliases original_title category} : (" + alternatives + ")";
    }

    /**
     * Same declension-widening as {@link #exactOrStemWidenedClause}, but for the relaxed lane's own
     * prefix convention: a stemmed prefix widens further back to the shared root (e.g. "domacnost*"
     * catches "domacnostem") than the previous raw-token prefix ("domacnosti*", which - since FTS5
     * prefix-matches the literal characters given - never reached back far enough to match a
     * different grammatical case sharing only the root).
     */
    private static String relaxedTokenClause(String token) {
        String stem = CzTextStemmer.ftsPrefixStem(token);
        if (!stem.isEmpty()) {
            return stem + "*";
        }
        return "\"" + token.replace("\"", " ") + "\"" + (token.length() >= 4 ? "*" : "");
    }

    private static List<String> queryTokens(String queryRaw) {
        String normalized = CatalogTextUtils.normalizeTokenBoundaries(queryRaw);
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2 && !QUERY_STOP_WORDS.contains(token)) {
                out.add(token);
            }
        }
        return List.copyOf(out);
    }

    // Package-private (not private): SearchCatalogSidecarIndexDedupeAndFallbackTest overrides
    // executeFtsQuery() below to deterministically inject a RELAXED-lane failure, without relying on
    // real query timing - it needs to reference this type in that override's signature.
    enum RetrievalLane {
        STRICT,
        RELAXED
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> out = new ArrayList<>();
        if (lists == null) {
            return out;
        }
        for (List<String> list : lists) {
            if (list != null) {
                out.addAll(list);
            }
        }
        return out;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    @PreDestroy
    void close() {
        coverageExecutor.shutdownNow();
        indexedSources.clear();
    }

    private record CoverageSnapshot(
            long contentRevision,
            Instant createdAt,
            long computeMs,
            Map<String, Object> payload) {}

    private record ScoredDoc(SearchCatalogSidecarDocument doc, double score, List<String> matchedFields) {}

    @FunctionalInterface
    private interface DocumentConsumer {
        void accept(SearchCatalogSidecarDocument doc) throws Exception;
    }

    private record SourceState(String signature, int documentCount, int currentCount, int historicalCount) {}

    private record ExistingDocument(String contentHash, long ftsRowId, String ftsContentHash) {}

    private record BuildStats(
            String source,
            int documentCount,
            int changedCount,
            int unchangedCount,
            int deletedCount,
            int currentCount,
            int historicalCount,
            boolean skipped,
            long durationMs) {

        static BuildStats skipped(String source, SourceState state, long durationMs) {
            return new BuildStats(
                    source, state.documentCount(), 0, state.documentCount(), 0,
                    state.currentCount(), state.historicalCount(), true, durationMs);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", source);
            out.put("document_count", documentCount);
            out.put("changed_count", changedCount);
            out.put("unchanged_count", unchangedCount);
            out.put("deleted_count", deletedCount);
            out.put("current_count", currentCount);
            out.put("historical_count", historicalCount);
            out.put("skipped_unchanged_source", skipped);
            out.put("rebuild_mode", "incremental");
            out.put("duration_ms", durationMs);
            out.put("updated_at", Instant.now().toString());
            return out;
        }
    }
}
