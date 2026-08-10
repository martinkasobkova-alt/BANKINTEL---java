package cz.bankintel.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.config.BankIntelProperties;
import cz.bankintel.search.scoring.CatalogScoringPipeline;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Regression guard for the Czech-declension FTS recall fix, against a small SELF-CONTAINED FTS5
 * table (not the shared production index, which drifts) - {@code catalog_fts} is tokenized with
 * {@code unicode61 remove_diacritics 2}: it removes accents but never stems, so an exact quoted
 * token like "domacnosti" (nominative) cannot lexically match a row titled with "domacnostem"
 * (dative), e.g. ARAD's real "Uvery domacnostem celkem" (household loans total) - confirmed missing
 * from the raw FTS lane (search-relevance audit, 2026-07-31; only the vector lane found it).
 * {@link CatalogIndexStore#buildRawFtsMatch} now widens each token's match clause with an FTS5
 * prefix query on {@link CzTextStemmer}'s stem, so the same query must now surface it via the raw
 * FTS path directly.
 */
class CatalogIndexStoreCzechMorphologyRegressionTest {

    private CatalogIndexStore indexStore;

    @BeforeEach
    void setUp() throws Exception {
        // Deliberately not @TempDir: the SQLite connection pool this test exercises keeps a handle
        // open past the test method's end, and Windows refuses to delete an open file - JUnit's
        // @TempDir would then fail the test on cleanup alone, not on any real assertion. A plain temp
        // directory left behind is a harmless, common tradeoff (same as other CatalogIndexStore tests
        // that point at pre-existing on-disk fixtures with no cleanup at all).
        Path tempDir = Files.createTempDirectory("catalog-fts-cz-morphology-test");
        Path fts = tempDir.resolve("test_catalog_fts.sqlite");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + fts);
                Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE VIRTUAL TABLE catalog_fts USING fts5(
                        source UNINDEXED,
                        set_id UNINDEXED,
                        row_json UNINDEXED,
                        title,
                        full_path,
                        search_blob,
                        territory,
                        tokenize='unicode61 remove_diacritics 2'
                    )
                    """);
            insertRow(conn, "arad", "arad_household_loans_total", "Uvery domacnostem celkem");
            insertRow(conn, "arad", "arad_road_crashes", "Pocet dopravnich nehod");
        }

        BankIntelProperties bankProps = new BankIntelProperties(
                new BankIntelProperties.Jwt("test-secret", 60, 7),
                new BankIntelProperties.Cors(""),
                new BankIntelProperties.Cookie(false, "Lax", ""),
                new BankIntelProperties.Dev(false, false),
                "",
                new BankIntelProperties.Catalog(
                        tempDir.toAbsolutePath().normalize().toString(),
                        fts.toAbsolutePath().normalize().toString(),
                        ""),
                new BankIntelProperties.Chat(""),
                new BankIntelProperties.Storage("", "", ""));

        CatalogSearchProperties searchProps = new CatalogSearchProperties(bankProps);
        ObjectMapper objectMapper = new ObjectMapper();
        CatalogSearchMetadataSidecar sidecar = new CatalogSearchMetadataSidecar(searchProps, objectMapper);
        CatalogSqliteReadPool sqlitePool = new CatalogSqliteReadPool(searchProps);
        CatalogSearchResultCache searchResultCache = new CatalogSearchResultCache();
        CatalogScoringPipeline scoringPipeline = new CatalogScoringPipeline(sidecar);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        indexStore = new CatalogIndexStore(
                searchProps, objectMapper, sidecar, sqlitePool, environment, searchResultCache, scoringPipeline);
    }

    private static void insertRow(Connection conn, String source, String setId, String title) throws Exception {
        String rowJson = "{\"source\":\"" + source + "\",\"set_id\":\"" + setId + "\",\"name\":\"" + title + "\"}";
        try (var ps = conn.prepareStatement(
                "INSERT INTO catalog_fts(source, set_id, row_json, title, full_path, search_blob, territory) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, source);
            ps.setString(2, setId);
            ps.setString(3, rowJson);
            ps.setString(4, title);
            ps.setString(5, title);
            ps.setString(6, title);
            ps.setString(7, "");
            ps.executeUpdate();
        }
    }

    @Test
    void nominativeQueryFindsDativeTitleViaRawFts() {
        List<Map<String, Object>> results = indexStore.searchSourceFtsRaw("arad", "uvery domacnosti", 30);

        assertThat(results)
                .as("'domacnosti' (nominative) must reach a row titled with 'domacnostem' (dative) via "
                        + "the stem-prefix widened FTS clause")
                .anySatisfy(row -> assertThat(row.get("set_id")).isEqualTo("arad_household_loans_total"));
        assertThat(results)
                .as("the stem prefix must not pull in an unrelated row that shares no root")
                .noneSatisfy(row -> assertThat(row.get("set_id")).isEqualTo("arad_road_crashes"));
    }

    @Test
    void dativeQueryFindsNominativeTitleViaRawFts() {
        // Symmetric check: querying with the dative form must also reach a nominative-titled row (if
        // one existed) - exercised here via the same declined-form row matching its own exact title.
        List<Map<String, Object>> results = indexStore.searchSourceFtsRaw("arad", "domacnostem", 30);

        assertThat(results)
                .anySatisfy(row -> assertThat(row.get("set_id")).isEqualTo("arad_household_loans_total"));
    }

    @Test
    void unrelatedQueryDoesNotMatchUnrelatedTitle() {
        List<Map<String, Object>> results = indexStore.searchSourceFtsRaw("arad", "dopravni nehody", 30);

        assertThat(results).anySatisfy(row -> assertThat(row.get("set_id")).isEqualTo("arad_road_crashes"));
        assertThat(results).noneSatisfy(row -> assertThat(row.get("set_id")).isEqualTo("arad_household_loans_total"));
    }

    @Test
    void budgetedPureFtsFindsRowWithoutSidecarMerge() {
        CatalogIndexStore.FtsSearchResult result = indexStore.searchFtsHitsWithinBudget(
                "arad", "uvery domacnosti", 30, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5));

        assertThat(result.budgetExhausted()).isFalse();
        assertThat(result.hits()).anySatisfy(hit ->
                assertThat(hit.setId()).isEqualTo("arad_household_loans_total"));
        assertThat(result.hits()).allSatisfy(hit ->
                assertThat(hit.toMap()).doesNotContainKey("_sidecar_rescue"));
    }

    @Test
    void expiredBudgetDoesNotStartSqliteWork() {
        CatalogIndexStore.FtsSearchResult result = indexStore.searchFtsHitsWithinBudget(
                "arad", "uvery domacnosti", 30, System.nanoTime() - 1);

        assertThat(result.budgetExhausted()).isTrue();
        assertThat(result.hits()).isEmpty();
    }
}
