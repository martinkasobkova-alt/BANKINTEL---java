package cz.bankintel.search.v2.sidecar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogSearchProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Perf fix (q16 "zisk bank slovensko" structural diagnosis): two independent, approved-in-isolation
 * changes.
 *
 * <p>Varianta B - two query-expansion variants whose text differs but whose FINAL FTS {@code MATCH}
 * expression is byte-identical after {@code queryTokens()}/{@code buildMatch}/{@code
 * buildRelaxedMatch}'s 8-token cap (exactly what happened for q16's giant synonym-dump variant with
 * and without a trailing " SK") must execute the underlying SQL only once per request, via a
 * caller-supplied dedupe cache keyed on (sources, matchExpr, limit) - never on the raw variant text.
 *
 * <p>Varianta E - when STRICT succeeds but RELAXED subsequently throws (its own
 * {@code SQLITE_SEARCH_TIMEOUT_SECONDS} firing, or any other failure), the already-computed STRICT
 * results must be returned instead of the whole hybrid search collapsing to an empty list. Real
 * SQLite timeouts are multi-second and flaky to assert on deterministically, so these tests inject
 * the failure via a package-private test seam ({@code executeFtsQuery}) instead.
 */
class SearchCatalogSidecarIndexDedupeAndFallbackTest {

    @TempDir
    Path tempDir;

    // The exact real-world pair diagnosed for q16 "zisk bank slovensko": the giant synonym-dump
    // variant with and without a trailing geo suffix. Both must truncate to the identical first 8
    // tokens (zisk, bank, banks, banking, sector, credit, institutions, monetary).
    private static final String Q16_GIANT_DUMP = "zisk bank banks banking sector credit institutions "
            + "monetary financial mfi profit profits earnings net income profitability";
    private static final String Q16_GIANT_DUMP_WITH_GEO = Q16_GIANT_DUMP + " SK";

    // ---- Varianta B: dedup by final MATCH expression ----------------------------------------------

    @Test
    void q16GiantDumpVariantsTruncateToTheIdenticalMatchExpressionRegardlessOfTrailingGeoSuffix() {
        assertThat(SearchCatalogSidecarIndex.buildMatch(Q16_GIANT_DUMP))
                .isEqualTo(SearchCatalogSidecarIndex.buildMatch(Q16_GIANT_DUMP_WITH_GEO));
        assertThat(SearchCatalogSidecarIndex.buildRelaxedMatch(Q16_GIANT_DUMP))
                .isEqualTo(SearchCatalogSidecarIndex.buildRelaxedMatch(Q16_GIANT_DUMP_WITH_GEO));
        // "monetary" now widens to (monetar* OR "monetary") - CzTextStemmer strips its trailing "y" the
        // same as it would a Czech nominative-singular "-y" case ending (see the Czech-morphology FTS
        // recall fix); the other 7 tokens have no suffix the stemmer recognizes, so they are unchanged.
        assertThat(SearchCatalogSidecarIndex.buildMatch(Q16_GIANT_DUMP))
                .isEqualTo("\"zisk\" AND \"bank\" AND \"banks\" AND \"banking\" AND \"sector\" AND "
                        + "\"credit\" AND \"institutions\" AND (monetar* OR \"monetary\")");
    }

    @Test
    void q16GiantDumpVariantPairExecutesTheUnderlyingSqlOnlyOnceWithASharedDedupeCache() throws Exception {
        SearchCatalogSidecarIndex index = buildIndexWithDocs("ecb2",
                // Deliberately unrelated content so STRICT's 8-token AND finds 0 rows for either
                // variant, forcing RELAXED - exactly the condition that made q16 spend ~6s per variant.
                "{\"source\":\"ecb2\",\"set_id\":\"unrelated_series_1\",\"title\":\"Completely unrelated commodity index\"}\n"
                        + "{\"source\":\"ecb2\",\"set_id\":\"unrelated_series_2\",\"title\":\"Another unrelated dataset\"}\n");

        Map<SearchCatalogSidecarIndex.FtsQueryCacheKey, List<Map<String, Object>>> dedupeCache =
                new ConcurrentHashMap<>();
        long before = SearchCatalogSidecarIndex.ftsQueryExecutionCountForTest();

        List<Map<String, Object>> first =
                index.searchGlobal(List.of("ecb2"), Q16_GIANT_DUMP, 100, dedupeCache);
        List<Map<String, Object>> second =
                index.searchGlobal(List.of("ecb2"), Q16_GIANT_DUMP_WITH_GEO, 100, dedupeCache);

        long executionsForThisPair = SearchCatalogSidecarIndex.ftsQueryExecutionCountForTest() - before;
        // Exactly 2: one STRICT execution + one RELAXED execution, shared by BOTH variants via the
        // dedupe cache - NOT 4 (2 variants x 2 lanes), which is what ran before this fix.
        assertThat(executionsForThisPair)
                .as("STRICT + RELAXED should each execute once, reused by both variants")
                .isEqualTo(2);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentLimitsAreNeverIncorrectlyServedFromEachOthersCacheEntry() throws Exception {
        SearchCatalogSidecarIndex index = buildIndexWithDocs("ecb2",
                "{\"source\":\"ecb2\",\"set_id\":\"wage_series\",\"title\":\"Average wages growth\"}\n");

        Map<SearchCatalogSidecarIndex.FtsQueryCacheKey, List<Map<String, Object>>> dedupeCache =
                new ConcurrentHashMap<>();
        long before = SearchCatalogSidecarIndex.ftsQueryExecutionCountForTest();

        index.searchGlobal(List.of("ecb2"), "wage growth", 10, dedupeCache);
        index.searchGlobal(List.of("ecb2"), "wage growth", 20, dedupeCache); // different limit
        long executions = SearchCatalogSidecarIndex.ftsQueryExecutionCountForTest() - before;

        assertThat(executions)
                .as("a different limit must not be served from a cache entry built for another limit")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void freshDedupeCachePerCallNeverLeaksResultsAcrossIndependentCalls() throws Exception {
        SearchCatalogSidecarIndex index = buildIndexWithDocs("ecb2",
                "{\"source\":\"ecb2\",\"set_id\":\"wage_series\",\"title\":\"Average wages growth\"}\n");

        long before = SearchCatalogSidecarIndex.ftsQueryExecutionCountForTest();
        index.searchGlobal(List.of("ecb2"), "wage growth", 10, new ConcurrentHashMap<>());
        index.searchGlobal(List.of("ecb2"), "wage growth", 10, new ConcurrentHashMap<>());
        long executions = SearchCatalogSidecarIndex.ftsQueryExecutionCountForTest() - before;

        assertThat(executions)
                .as("two separate (fresh-cache) calls must not dedupe against each other")
                .isGreaterThanOrEqualTo(2);
    }

    // ---- Varianta E: STRICT fallback when RELAXED fails/times out --------------------------------

    @Test
    void strictSuccessAndRelaxedTimeoutFallsBackToStrictResultsInsteadOfDiscardingThem() throws Exception {
        FailingRelaxedLaneIndex index = buildFailingRelaxedIndex(
                "{\"source\":\"ecb2\",\"set_id\":\"policy_rate_series\",\"title\":\"Policy interest rate decision\"}\n",
                () -> new SQLTimeoutException("simulated SQLITE_SEARCH_TIMEOUT_SECONDS"));

        List<Map<String, Object>> results = index.search("ecb2", "policy interest rate decision", 8);

        assertThat(results)
                .as("relaxed timing out must not discard the strict hit already found")
                .anyMatch(row -> "policy_rate_series".equals(row.get("series_id")));
        assertThat(index.relaxedAttempts.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void strictSuccessAndRelaxedExceptionFallsBackToStrictResultsInsteadOfDiscardingThem() throws Exception {
        FailingRelaxedLaneIndex index = buildFailingRelaxedIndex(
                "{\"source\":\"ecb2\",\"set_id\":\"policy_rate_series\",\"title\":\"Policy interest rate decision\"}\n",
                () -> new java.sql.SQLException("simulated fts5 syntax error"));

        List<Map<String, Object>> results = index.search("ecb2", "policy interest rate decision", 8);

        assertThat(results)
                .as("relaxed throwing any other SQLException must also fall back to strict results")
                .anyMatch(row -> "policy_rate_series".equals(row.get("series_id")));
    }

    @Test
    void strictEmptyAndRelaxedTimeoutReturnsEmptyListWithoutThrowing() throws Exception {
        FailingRelaxedLaneIndex index = buildFailingRelaxedIndex(
                "{\"source\":\"ecb2\",\"set_id\":\"unrelated\",\"title\":\"Something else entirely unrelated\"}\n",
                () -> new SQLTimeoutException("simulated SQLITE_SEARCH_TIMEOUT_SECONDS"));

        List<Map<String, Object>> results = index.search("ecb2", "zisk bank profit rate decision", 8);

        assertThat(results).isEmpty();
        assertThat(index.relaxedAttempts.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void strictEmptyAndRelaxedSuccessBehavesExactlyAsBefore() throws Exception {
        SearchCatalogSidecarIndex index = buildIndexWithDocs("eurostat",
                "{\"source\":\"eurostat\",\"set_id\":\"salary_series\","
                        + "\"title\":\"Average salary per employee\","
                        + "\"description\":\"Average wages in the total economy\"}\n");

        List<Map<String, Object>> results = index.search("eurostat", "wage growth", 8);

        assertThat(results)
                .anyMatch(row -> "salary_series".equals(row.get("series_id"))
                        && "relaxed".equals(row.get("_retrieval_lane")));
    }

    @Test
    void strictSuccessAndRelaxedSuccessMergesBothLanesExactlyAsBefore() throws Exception {
        SearchCatalogSidecarIndex index = buildIndexWithDocs("eurostat",
                "{\"source\":\"eurostat\",\"set_id\":\"wage_growth_series\",\"title\":\"Wage growth rate\"}\n"
                        + "{\"source\":\"eurostat\",\"set_id\":\"salary_series\","
                        + "\"title\":\"Average salary per employee\","
                        + "\"description\":\"Average wages in the total economy\"}\n");

        List<Map<String, Object>> results = index.search("eurostat", "wage growth", 8);

        assertThat(results).anyMatch(row -> "wage_growth_series".equals(row.get("series_id"))
                && "strict".equals(row.get("_retrieval_lane")));
        assertThat(results).anyMatch(row -> "salary_series".equals(row.get("series_id"))
                && "relaxed".equals(row.get("_retrieval_lane")));
    }

    // ---- fixtures -----------------------------------------------------------------------------

    /** Subclass overriding the package-private executeFtsQuery seam to fail only for RELAXED. */
    private static final class FailingRelaxedLaneIndex extends SearchCatalogSidecarIndex {
        private final java.util.function.Supplier<Exception> relaxedFailure;
        private final AtomicInteger relaxedAttempts = new AtomicInteger();

        FailingRelaxedLaneIndex(
                CatalogSearchProperties properties,
                ObjectMapper objectMapper,
                SearchCatalogSidecarBuilder builder,
                java.util.function.Supplier<Exception> relaxedFailure) {
            super(properties, objectMapper, builder);
            this.relaxedFailure = relaxedFailure;
        }

        @Override
        List<Map<String, Object>> executeFtsQuery(
                List<String> sources, String queryRaw, String matchExpr, int limit, RetrievalLane lane)
                throws Exception {
            if (lane == RetrievalLane.RELAXED) {
                relaxedAttempts.incrementAndGet();
                throw relaxedFailure.get();
            }
            return super.executeFtsQuery(sources, queryRaw, matchExpr, limit, lane);
        }
    }

    private FailingRelaxedLaneIndex buildFailingRelaxedIndex(
            String jsonlContent, java.util.function.Supplier<Exception> relaxedFailure) throws Exception {
        Fixture fixture = writeFixture("ecb2", jsonlContent);
        ObjectMapper objectMapper = new ObjectMapper();
        SearchCatalogSidecarBuilder builder = new SearchCatalogSidecarBuilder(objectMapper);
        builder.loadTaxonomy();
        FailingRelaxedLaneIndex index =
                new FailingRelaxedLaneIndex(fixture.properties(), objectMapper, builder, relaxedFailure);
        assertThat(index.rebuild(List.of("ecb2"))).containsEntry("ok", true);
        return index;
    }

    private SearchCatalogSidecarIndex buildIndexWithDocs(String source, String jsonlContent) throws Exception {
        Fixture fixture = writeFixture(source, jsonlContent);
        ObjectMapper objectMapper = new ObjectMapper();
        SearchCatalogSidecarBuilder builder = new SearchCatalogSidecarBuilder(objectMapper);
        builder.loadTaxonomy();
        SearchCatalogSidecarIndex index = new SearchCatalogSidecarIndex(fixture.properties(), objectMapper, builder);
        assertThat(index.rebuild(List.of(source))).containsEntry("ok", true);
        return index;
    }

    private record Fixture(CatalogSearchProperties properties) {}

    private Fixture writeFixture(String source, String jsonlContent) throws Exception {
        String testId = "t" + System.nanoTime();
        Path indexDir = Files.createDirectories(tempDir.resolve("dedupe-index-" + testId));
        Path metadataDir = Files.createDirectories(tempDir.resolve("dedupe-metadata-" + testId));
        Path sidecarDir = Files.createDirectories(tempDir.resolve("dedupe-sidecar-" + testId));
        Path sourceFile = indexDir.resolve(source + ".jsonl");
        Files.writeString(sourceFile, jsonlContent);

        CatalogSearchProperties properties = mock(CatalogSearchProperties.class);
        when(properties.indexDir()).thenReturn(indexDir);
        when(properties.metadataDir()).thenReturn(metadataDir);
        when(properties.sidecarDir()).thenReturn(sidecarDir);
        when(properties.sidecarFtsDbPath()).thenReturn(sidecarDir.resolve("sidecar.sqlite"));
        when(properties.jsonlPath(source)).thenReturn(sourceFile);
        when(properties.metadataPath(source)).thenReturn(metadataDir.resolve(source + ".jsonl"));
        return new Fixture(properties);
    }
}
