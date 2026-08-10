package cz.bankintel.search;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Small read-only SQLite connection pool for FTS index — avoids opening a new JDBC connection per query.
 * Ref: classic_catalog_fts_index.py persistent conn pattern.
 */
@Component
public class CatalogSqliteReadPool {

    private static final Logger log = LoggerFactory.getLogger(CatalogSqliteReadPool.class);
    // Deep-search fans a lane search out to every configured catalog source concurrently
    // (virtual thread per source); a small pool caused most of those lookups to open a
    // fresh JDBC connection to the (multi-GB) FTS file instead of reusing a pooled one,
    // which showed up as multi-second lane latency under concurrent load. Sized generously
    // above the realistic source-lane fan-out so idle read-only connections can be reused.
    private static final int POOL_SIZE = 32;

    private final CatalogSearchProperties properties;
    private final BlockingQueue<Connection> pool = new ArrayBlockingQueue<>(POOL_SIZE);
    private volatile String jdbcUrl;

    public CatalogSqliteReadPool(CatalogSearchProperties properties) {
        this.properties = properties;
    }

    public Connection borrow() throws SQLException {
        if (!properties.ftsDbPath().toFile().exists()) {
            throw new SQLException("FTS database not found: " + properties.ftsDbPath());
        }
        ensureUrl();
        Connection conn = pool.poll();
        if (conn != null) {
            try {
                if (conn.isValid(2)) {
                    return conn;
                }
                closeQuietly(conn);
            } catch (SQLException ex) {
                closeQuietly(conn);
            }
        }
        return openNew();
    }

    public void release(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            if (conn.isClosed() || !conn.isValid(1)) {
                closeQuietly(conn);
                return;
            }
            if (!pool.offer(conn)) {
                closeQuietly(conn);
            }
        } catch (SQLException ex) {
            closeQuietly(conn);
        }
    }

    private Connection openNew() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void ensureUrl() {
        if (jdbcUrl == null) {
            synchronized (this) {
                if (jdbcUrl == null) {
                    jdbcUrl = "jdbc:sqlite:file:" + properties.ftsDbPath().toAbsolutePath() + "?mode=ro";
                }
            }
        }
    }

    private static void closeQuietly(Connection conn) {
        try {
            conn.close();
        } catch (SQLException ex) {
            log.trace("sqlite pool close: {}", ex.getMessage());
        }
    }
}
