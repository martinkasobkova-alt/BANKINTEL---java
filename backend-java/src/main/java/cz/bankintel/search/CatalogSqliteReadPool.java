package cz.bankintel.search;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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

    // Generace souboru indexu. Rebuild ({@link ClassicCatalogFtsIndexBuilder}) soubor vymění pod
    // rukama, takže spojení otevřená před výměnou už ukazují na starý (odlinkovaný) soubor.
    // isValid() je na nich pořád true — čtou dál, jen stará data — proto se nepoznají jinak než
    // podle generace, ve které byla vypůjčena.
    private final AtomicLong generation = new AtomicLong();
    private final Map<Connection, Long> issuedGeneration = new ConcurrentHashMap<>();

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
                    return track(conn);
                }
                closeQuietly(conn);
            } catch (SQLException ex) {
                closeQuietly(conn);
            }
        }
        return track(openNew());
    }

    public void release(Connection conn) {
        if (conn == null) {
            return;
        }
        Long borrowedIn = issuedGeneration.remove(conn);
        if (borrowedIn == null || borrowedIn != generation.get()) {
            // Vypůjčeno před výměnou souboru indexu — zpátky do poolu nesmí, četlo by starý soubor.
            closeQuietly(conn);
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

    /**
     * Zavře nakešovaná spojení a zneplatní ta právě vypůjčená — volá se těsně před atomickou
     * výměnou souboru indexu (Python ekvivalent {@code close_fts_connection()}).
     *
     * <p>Bez toho drží pool až {@value #POOL_SIZE} otevřených handlů na starý soubor. Na Windows
     * kvůli tomu selže samotný přesun, na Linuxu projde, ale spojení dál čtou odlinkovaný inode —
     * hledání by vracelo předrebuildová data až do restartu aplikace.
     *
     * @return kolik nakešovaných spojení se zavřelo
     */
    public int drain() {
        generation.incrementAndGet();
        int closed = 0;
        Connection conn;
        while ((conn = pool.poll()) != null) {
            issuedGeneration.remove(conn);
            closeQuietly(conn);
            closed++;
        }
        return closed;
    }

    private Connection track(Connection conn) {
        issuedGeneration.put(conn, generation.get());
        return conn;
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
