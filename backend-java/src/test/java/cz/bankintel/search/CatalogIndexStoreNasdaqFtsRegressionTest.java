package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.config.BankIntelProperties;
import cz.bankintel.search.scoring.CatalogScoringPipeline;
import cz.bankintel.util.BankIntelDataPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Regression guard for BIG_FTS_SOURCES retrieval: {@code nasdaq cena} must surface canonical FRED
 * NASDAQ indices, not only random OMX Baltic price-index long-tail rows.
 */
class CatalogIndexStoreNasdaqFtsRegressionTest {

    private CatalogIndexStore indexStore;

    @BeforeEach
    void setUp() {
        Path fts = resolveFtsDb();
        Assumptions.assumeTrue(Files.isRegularFile(fts), "FTS DB missing: " + fts);

        Path indexDir = fts.getParent();
        Path metaDir = BankIntelDataPaths.catalogSearchMetadataDir();
        String meta = Files.isDirectory(metaDir) ? metaDir.toAbsolutePath().normalize().toString() : "";

        BankIntelProperties bankProps = new BankIntelProperties(
                new BankIntelProperties.Jwt("test-secret", 60, 7),
                new BankIntelProperties.Cors(""),
                new BankIntelProperties.Cookie(false, "Lax", ""),
                new BankIntelProperties.Dev(false, false),
                "",
                new BankIntelProperties.Catalog(
                        indexDir.toAbsolutePath().normalize().toString(),
                        fts.toAbsolutePath().normalize().toString(),
                        meta),
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
                searchProps,
                objectMapper,
                sidecar,
                sqlitePool,
                environment,
                searchResultCache,
                scoringPipeline);
    }

    @Test
    void nasdaqCenaFredSearchIncludesCanonicalNasdaqIndex() {
        Assumptions.assumeTrue(indexStore.ftsDbAvailable(), "FTS DB not configured for test context");

        List<Map<String, Object>> results = indexStore.searchSource("fred", "nasdaq cena", 30);
        boolean hasCanonical = results.stream()
                .map(row -> String.valueOf(row.getOrDefault("set_id", "")).toUpperCase(Locale.ROOT))
                .anyMatch(id -> "NASDAQ100".equals(id) || "NASDAQCOM".equals(id));

        assertTrue(
                hasCanonical,
                "expected NASDAQ100 or NASDAQCOM in fred search results for 'nasdaq cena', got: "
                        + results.stream()
                                .limit(8)
                                .map(r -> r.get("set_id") + ":" + r.get("name"))
                                .toList());
    }

    private static Path resolveFtsDb() {
        Path sibling = Path.of("C:/Bankoapp-main/Bankoapp-main/backend/data/catalog_search_indexes")
                .resolve("classic_catalog_search.sqlite");
        if (Files.isRegularFile(sibling)) {
            return sibling.toAbsolutePath().normalize();
        }
        return BankIntelDataPaths.catalogSearchIndexDir()
                .resolve("classic_catalog_search.sqlite")
                .toAbsolutePath()
                .normalize();
    }
}
