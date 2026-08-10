package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.bankintel.util.BankIntelDataPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class CatalogIndexStoreFtsQueryPlanTest {

    @Test
    void parseQuotedFtsTokensSplitsOrExpression() {
        List<String> tokens = CatalogIndexStore.parseQuotedFtsTokens("\"nasdaq\" OR \"cena\" OR \"price\"");
        assertTrue(tokens.contains("nasdaq"));
        assertTrue(tokens.contains("cena"));
        assertTrue(tokens.contains("price"));
    }

    @Test
    void resolveFtsQueryPlanUsesBm25ForNasdaqCenaOnFred() throws Exception {
        Path fts = resolveFtsDb();
        Assumptions.assumeTrue(Files.isRegularFile(fts), "FTS DB missing: " + fts);

        String query = "nasdaq cena";
        String matchExpr =
                CatalogTextUtils.buildFtsMatch(CatalogTextUtils.needlesFromQuery(query), query);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + fts.toAbsolutePath())) {
            CatalogIndexStore.FtsQueryPlan plan =
                    CatalogIndexStore.resolveFtsQueryPlan(conn, "fred", matchExpr);
            assertTrue(plan.ordered(), "broad nasdaq+cena match should stay bm25-ordered on fred");
            assertEquals(matchExpr, plan.matchExpr());
        }
    }

    @Test
    void anchoredMatchExprPicksRarestTokenForBroadOrMatch() throws Exception {
        Path fts = resolveFtsDb();
        Assumptions.assumeTrue(Files.isRegularFile(fts), "FTS DB missing: " + fts);

        String matchExpr = "\"nasdaq\" OR \"cena\" OR \"price\"";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + fts.toAbsolutePath())) {
            String anchored = CatalogIndexStore.anchoredMatchExpr(conn, "fred", matchExpr);
            assertEquals("\"nasdaq\"", anchored, "cena has 0 fred matches; nasdaq is the rarest active token");
        }
    }

    @Test
    void nonBigSourceAlwaysUsesBm25Ordering() throws Exception {
        Path fts = resolveFtsDb();
        Assumptions.assumeTrue(Files.isRegularFile(fts), "FTS DB missing: " + fts);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + fts.toAbsolutePath())) {
            CatalogIndexStore.FtsQueryPlan plan =
                    CatalogIndexStore.resolveFtsQueryPlan(conn, "eurostat", "\"gdp\" OR \"hdp\"");
            assertTrue(plan.ordered());
        }
    }

    @Test
    void degenerateIndexQueryFallsBackWhenAboveThreshold() throws Exception {
        Path fts = resolveFtsDb();
        Assumptions.assumeTrue(Files.isRegularFile(fts), "FTS DB missing: " + fts);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + fts.toAbsolutePath())) {
            String matchExpr = "\"index\"";
            int count = CatalogIndexStore.countFtsMatches(conn, "fred", matchExpr);
            Assumptions.assumeTrue(
                    count > CatalogIndexStore.FTS_BM25_ORDER_THRESHOLD,
                    "index cardinality below threshold in this mirror: " + count);

            CatalogIndexStore.FtsQueryPlan plan =
                    CatalogIndexStore.resolveFtsQueryPlan(conn, "fred", matchExpr);
            assertFalse(plan.ordered(), "degenerate broad token should use fast fallback");
        }
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
