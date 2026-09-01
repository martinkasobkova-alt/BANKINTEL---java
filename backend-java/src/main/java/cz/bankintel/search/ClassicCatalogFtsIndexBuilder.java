package cz.bankintel.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.util.BankIntelEnvVars;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Staví classic FTS index ({@code classic_catalog_search.sqlite}) z per-source JSONL.
 *
 * <p>Port {@code services/classic_catalog_fts_index.py:build_fts_database} z referenčního Python
 * repa. Dokud tenhle port nebyl, jediný způsob, jak index přestavět, byl shell-out na Python
 * ({@code BankIntelMaintenanceService}), což znamenalo mít na produkčním hostu Python i celé
 * referenční repo. Runtime čtení indexu už v Javě bylo ({@link CatalogIndexStore}), chyběl jen build.
 *
 * <p><b>Proč se staví do {@code .tmp.sqlite} a až pak přejmenuje.</b> Stejně jako v Pythonu:
 * kdyby build psal do ostrého souboru a spadl, zůstane po něm rozbitý index a hledání je do
 * ručního zásahu mrtvé. Cena je dvojnásobek místa po dobu buildu (~8,9 GB navíc) — viz
 * {@code docs/FTS_AND_SIDECAR.md §7}, kde je spočítaná disková špička.
 *
 * <p><b>Znama odchylka od Pythonu.</b> {@code row_json} se serializuje Jacksonem
 * ({@code {"a":1}}), kdežto Python {@code json.dumps} vkládá mezery ({@code {"a": 1}}). Sloupec
 * je ve FTS {@code UNINDEXED} — nikdy se nehledá, jen se čte a parsuje zpátky — takže výsledky
 * hledání to neovlivní; liší se jen bajty na disku. Parita se proto ověřuje nad rozparsovaným
 * JSONem, ne nad řetězcem.
 *
 * <p><b>Co tenhle port zatím neumí.</b> Python zná volitelné kurátorování podle aktuálnosti
 * ({@code CATALOG_FTS_PRUNE_STALE_BEFORE} → {@code catalog_freshness.is_stale}). To se sem
 * neportovalo, protože je to opt-in a nikde se nepoužívá. Aby ale nikdy nevznikl tiše jiný index,
 * než by postavil Python, {@link #build(List)} s nastavenou proměnnou <b>odmítne běžet</b>
 * místo aby ji ignoroval.
 */
@Service
public class ClassicCatalogFtsIndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(ClassicCatalogFtsIndexBuilder.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** Ref {@code classic_catalog_fts_index.py:FTS_PILOT_SOURCES}. */
    public static final List<String> PILOT_SOURCES =
            List.of("arad", "bis", "csu", "data360", "ecb2", "eurostat", "fred", "imf", "oecd4");

    /** Ref Python {@code if len(batch) >= 500}. */
    private static final int BATCH_SIZE = 500;

    // Ořezy délek přesně podle Pythonu — jinak by se u dlouhých titulů lišil obsah indexu.
    private static final int MAX_TITLE = 500;
    private static final int MAX_FULL_PATH = 2000;
    private static final int MAX_SEARCH_BLOB = 6000;
    private static final int MAX_SEARCH_BLOB_WITH_DIMENSIONS = 6200;
    private static final int MAX_TERRITORY = 200;

    private final CatalogSearchProperties properties;
    private final CatalogSqliteReadPool readPool;
    private final ObjectMapper objectMapper;

    public ClassicCatalogFtsIndexBuilder(
            CatalogSearchProperties properties, CatalogSqliteReadPool readPool, ObjectMapper objectMapper) {
        this.properties = properties;
        this.readPool = readPool;
        this.objectMapper = objectMapper;
    }

    /** Postaví index pro všechny pilotní zdroje. */
    public Map<String, Object> build() {
        return build(PILOT_SOURCES);
    }

    /**
     * Postaví index pro dané zdroje a atomicky ho vymění za stávající.
     *
     * @return souhrn ve tvaru, jaký vrací Python {@code build_fts_database} — {@code path},
     *     {@code total_rows} a {@code sources} s per-zdroj {@code status}/{@code rows}
     */
    public Map<String, Object> build(List<String> sources) {
        String pruneStaleBefore = BankIntelEnvVars.get("CATALOG_FTS_PRUNE_STALE_BEFORE").trim();
        if (!pruneStaleBefore.isEmpty() && !"0".equals(pruneStaleBefore)) {
            throw new IllegalStateException(
                    "CATALOG_FTS_PRUNE_STALE_BEFORE=" + pruneStaleBefore + " není v Java buildu podporováno "
                            + "(kurátorování podle aktuálnosti se neportovalo). Build by tiše vytvořil jiný index "
                            + "než Python. Buď proměnnou odnastavte, nebo index stavte referenčním Python skriptem.");
        }

        List<String> srcs = sources == null || sources.isEmpty()
                ? PILOT_SOURCES
                : sources.stream().filter(s -> s != null && !s.isBlank())
                        .map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();

        Path target = properties.ftsDbPath();
        Path tmp = tmpPathFor(target);
        Map<String, Long> curatedCounts = existingRowCounts(target);
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> perSource = new LinkedHashMap<>();
        summary.put("path", target.toString());
        summary.put("sources", perSource);

        try {
            Files.createDirectories(target.getParent());
            Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            throw new IllegalStateException("nelze připravit " + tmp + ": " + ex.getMessage(), ex);
        }

        long totalRows = 0;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tmp.toAbsolutePath())) {
            createSchema(conn);
            conn.setAutoCommit(false);
            for (String source : srcs) {
                Map<String, Object> result = indexSource(conn, source);
                perSource.put(source, result);
                totalRows += ((Number) result.getOrDefault("rows", 0)).longValue();
            }
            conn.commit();
            conn.setAutoCommit(true);
            createContentIndex(conn);
            checkpointWal(conn);
        } catch (SQLException ex) {
            throw new IllegalStateException("build FTS indexu selhal: " + ex.getMessage(), ex);
        }

        assertCurationNotDestroyed(curatedCounts, perSource, tmp);
        swapIntoPlace(tmp, target);
        summary.put("total_rows", totalRows);
        log.info("classic FTS index built path={} total_rows={} sources={}", target, totalRows, perSource.keySet());
        return summary;
    }

    /**
     * Kolik řádků má na zdroj STÁVAJÍCÍ index — podklad pro {@link #assertCurationNotDestroyed}.
     *
     * <p>Čte se {@code catalog_fts_meta}, ne {@code COUNT(*)}: metatabulka je maličká, kdežto
     * počítání přes {@code catalog_fts} je u ecb2/fred sken statisíců řádků s velkými bloby.
     */
    private Map<String, Long> existingRowCounts(Path target) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(target)) {
            return out;
        }
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:file:" + target.toAbsolutePath() + "?mode=ro");
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT source, row_count FROM catalog_fts_meta")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getLong(2));
            }
        } catch (SQLException ex) {
            log.warn("nelze přečíst catalog_fts_meta ze stávajícího indexu ({}): {}", target, ex.getMessage());
        }
        return out;
    }

    /**
     * Pojistka proti tichému zahození kurátorování.
     *
     * <p>Ostrý index není jen výstup buildu — po něm ještě jedou nástroje, které řežou a obohacují
     * řádky <b>přímo v indexu</b> ({@code prune_fred_local_series.py}, {@code prune_ecb_stale_series.py},
     * {@code enrich_fts_dimensions_inplace.py} …). Naměřeno 2026-09-01: JSONL má 844 759 FRED řad,
     * index jen 261 602 — tedy 583 157 prořezaných. Rebuild čte JSONL, takže by je všechny vrátil
     * a hledání by se zaneslo mrtvými řadami.
     *
     * <p>Platí to stejně pro původní Python skript; ten ale žádnou pojistku nemá a udělá to mlčky.
     * Tady se build radši zastaví (index se nevymění, hotový tmp zůstane k inspekci), dokud někdo
     * vědomě nenastaví {@code CATALOG_FTS_ALLOW_CURATION_RESET=1}.
     */
    /**
     * Odkud už přírůstek řádků neznamená „upstream přidal pár řad", ale „vrátilo se kurátorování".
     *
     * <p>Práh je +5 % a zároveň aspoň +100 řádků. Naměřené reálné případy ho překračují o řády
     * (fred +223 %, imf +42 %, data360 +33 %, ecb2 +28 %, eurostat +44 %), kdežto běžný přírůstek
     * několika nových řad projde bez otravování. Kdyby se hlídala jakákoli změna, flag
     * {@code CATALOG_FTS_ALLOW_CURATION_RESET} by musel být nastavený pořád — a tím by pojistka
     * přestala k čemukoli být.
     */
    private static long curationLostThreshold(long curated) {
        return Math.max(curated + 100, Math.round(curated * 1.05));
    }

    private void assertCurationNotDestroyed(
            Map<String, Long> curatedCounts, Map<String, Object> perSource, Path tmp) {
        if (curatedCounts.isEmpty() || BankIntelEnvVars.isTruthy("CATALOG_FTS_ALLOW_CURATION_RESET")) {
            return;
        }
        List<String> wouldGrow = new ArrayList<>();
        for (Map.Entry<String, Object> entry : perSource.entrySet()) {
            Long curated = curatedCounts.get(entry.getKey());
            if (curated == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            long rebuilt = ((Number) ((Map<String, Object>) entry.getValue()).getOrDefault("rows", 0)).longValue();
            if (rebuilt > curationLostThreshold(curated)) {
                wouldGrow.add(entry.getKey() + ": " + curated + " → " + rebuilt + " (+" + (rebuilt - curated) + ")");
            }
        }
        if (wouldGrow.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                "Rebuild by vrátil řádky, které byly ze stávajícího indexu vyřezány kurátorováním: "
                        + String.join(", ", wouldGrow) + ". Index se proto NEVYMĚNIL (hotový build zůstal jako "
                        + tmp + "). Prune/enrich nástroje pracují přímo nad indexem, takže je rebuild z JSONL "
                        + "zahodí. Buď po rebuildu kurátorování zopakujte, nebo — když to je záměr — nastavte "
                        + "CATALOG_FTS_ALLOW_CURATION_RESET=1.");
    }

    /** {@code classic_catalog_search.sqlite} → {@code classic_catalog_search.tmp.sqlite}. */
    private static Path tmpPathFor(Path target) {
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        return target.resolveSibling(stem + ".tmp.sqlite");
    }

    private void createSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE catalog_fts_meta (source TEXT PRIMARY KEY, row_count INTEGER, "
                    + "built_at TEXT, jsonl_path TEXT)");
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
            // Doprovodná B-tree tabulka pro přesné/prefixové lookupy podle set_id. FTS5 virtuální
            // tabulka neumí indexovat WHERE set_id IN (...) — bez tohohle je každý takový dotaz
            // full scan (~1,2 s na 600k řádků s velkými bloby).
            st.execute("CREATE TABLE catalog_rows_lookup (source TEXT NOT NULL, set_id TEXT NOT NULL, "
                    + "row_json TEXT NOT NULL)");
            st.execute("CREATE INDEX idx_catalog_rows_lookup ON catalog_rows_lookup(source, set_id)");
        }
    }

    private Map<String, Object> indexSource(Connection conn, String source) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        Path jsonl = properties.jsonlPath(source);
        if (!Files.isRegularFile(jsonl)) {
            result.put("status", "missing_jsonl");
            result.put("rows", 0);
            return result;
        }

        String insertFts = "INSERT INTO catalog_fts(source, set_id, row_json, title, full_path, search_blob, "
                + "territory) VALUES (?, ?, ?, ?, ?, ?, ?)";
        String insertLookup = "INSERT INTO catalog_rows_lookup(source, set_id, row_json) VALUES (?, ?, ?)";

        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8);
                PreparedStatement ftsPs = conn.prepareStatement(insertFts);
                PreparedStatement lookupPs = conn.prepareStatement(insertLookup)) {
            String line;
            int pending = 0;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Map<String, Object> row;
                try {
                    row = objectMapper.readValue(trimmed, MAP_TYPE);
                } catch (Exception ex) {
                    // Python: json.JSONDecodeError -> continue. Poškozený řádek build neshodí.
                    continue;
                }
                // Pozor: set_id Python strippuje (`.strip()`), ostatní pole NE. Kdyby se trimovalo
                // všechno, vyšel by jiný obsah indexu než z referenčního buildu.
                String setId = firstTruthy(row.get("set_id"), row.get("id")).trim();
                if (setId.isEmpty()) {
                    continue;
                }
                String title = clip(firstTruthyOr(setId, row.get("name"), row.get("title")), MAX_TITLE);
                String fullPath = clip(firstTruthy(row.get("full_path"), row.get("path")), MAX_FULL_PATH);
                String searchBlob =
                        clip(firstTruthy(row.get("search_blob"), row.get("_search_blob")), MAX_SEARCH_BLOB);
                // Zapéct členy dimenzí do blobu, když titul dimenzi jmenuje ("…by sex…" -> ženy/muži),
                // aby byli hledatelní, ne jen skórovaní za běhu.
                List<String> dimensionTerms = CatalogDimensionMemberTerms.forTitle(title + " " + fullPath);
                if (!dimensionTerms.isEmpty()) {
                    searchBlob = clip(searchBlob + " " + String.join(" ", dimensionTerms),
                            MAX_SEARCH_BLOB_WITH_DIMENSIONS);
                }
                String territory = clip(firstTruthy(row.get("territory")), MAX_TERRITORY);
                String rowJson = objectMapper.writeValueAsString(row);

                ftsPs.setString(1, source);
                ftsPs.setString(2, setId);
                ftsPs.setString(3, rowJson);
                ftsPs.setString(4, title);
                ftsPs.setString(5, fullPath);
                ftsPs.setString(6, searchBlob);
                ftsPs.setString(7, territory);
                ftsPs.addBatch();

                lookupPs.setString(1, source);
                lookupPs.setString(2, setId);
                lookupPs.setString(3, rowJson);
                lookupPs.addBatch();

                count++;
                pending++;
                if (pending >= BATCH_SIZE) {
                    ftsPs.executeBatch();
                    // Lookup tabulku plnit i při průběžném flushi — v Pythonu tu byla chyba, kdy
                    // do ní spadl jen poslední neúplný batch a 99 % set_id chybělo.
                    lookupPs.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                ftsPs.executeBatch();
                lookupPs.executeBatch();
            }
        } catch (SQLException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("čtení " + jsonl + " selhalo: " + ex.getMessage(), ex);
        }

        String builtAt = loadBuiltAt(source);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO catalog_fts_meta(source, row_count, built_at, jsonl_path) "
                        + "VALUES (?, ?, ?, ?)")) {
            ps.setString(1, source);
            ps.setInt(2, count);
            ps.setString(3, builtAt);
            ps.setString(4, jsonl.toString());
            ps.executeUpdate();
        }

        result.put("status", "ok");
        result.put("rows", count);
        return result;
    }

    private String loadBuiltAt(String source) {
        try {
            Path meta = properties.metaPath(source);
            if (!Files.isRegularFile(meta)) {
                return "";
            }
            Map<String, Object> parsed = objectMapper.readValue(meta.toFile(), MAP_TYPE);
            return firstTruthy(parsed.get("built_at")).trim();
        } catch (Exception ex) {
            log.debug("meta read failed source={}: {}", source, ex.getMessage());
            return "";
        }
    }

    /**
     * B-tree index nad FTS shadow content tabulkou ({@code c0=source}, {@code c1=set_id}).
     *
     * <p>Bez něj je fallbackový lookup podle set_id full scan ~1,4M řádků (~3,4 s na volání),
     * což u opakovaného volání protahovalo FX/komoditní dotazy přes 70 s. S indexem ~0,2 ms.
     * Staví se až po bulk insertu — je to rychlejší než inkrementální údržba během vkládání.
     */
    private void createContentIndex(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE INDEX IF NOT EXISTS idx_fts_content_src_setid ON catalog_fts_content(c0, c1)");
        } catch (SQLException ex) {
            log.warn("idx_fts_content_src_setid build failed: {}", ex.getMessage());
        }
    }

    /** Složit WAL do hlavního souboru, ať je tmp self-contained (žádný zbytkový -wal/-shm). */
    private void checkpointWal(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException ex) {
            log.warn("wal_checkpoint(TRUNCATE) failed: {}", ex.getMessage());
        }
    }

    /**
     * Výměna hotového indexu za ostrý soubor.
     *
     * <p>Read pool se musí vyprázdnit PŘED přesunem — jinak na Windows přesun selže (otevřené
     * handly) a na Linuxu sice projde, ale pooled spojení dál čtou starý odlinkovaný soubor.
     */
    private void swapIntoPlace(Path tmp, Path target) {
        int drained = readPool.drain();
        try {
            Files.deleteIfExists(Path.of(target + "-wal"));
            Files.deleteIfExists(Path.of(target + "-shm"));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("classic FTS index swapped into {} (read pool drained: {} connections)", target, drained);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "hotový index se nepodařilo přesunout na " + target + " (zůstal jako " + tmp + "): "
                            + ex.getMessage(), ex);
        }
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * Python {@code a or b or ""} nad hodnotami řádku — bez trimování.
     *
     * <p>Trimovat se smí jen {@code set_id}; u titulu, cesty a blobu by to znamenalo jiný obsah
     * indexu než z referenčního Python buildu.
     */
    private static String firstTruthy(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String text = String.valueOf(candidate);
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    /** Jako {@link #firstTruthy}, ale s náhradou, když jsou všechny prázdné (Python {@code or sid}). */
    private static String firstTruthyOr(String fallback, Object... candidates) {
        String found = firstTruthy(candidates);
        return found.isEmpty() ? fallback : found;
    }
}
