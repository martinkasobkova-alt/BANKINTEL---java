package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.config.BankIntelProperties;
import cz.bankintel.util.BankIntelDataPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parita Java buildu proti ostrému indexu postavenému referenčním Python skriptem.
 *
 * <p>Tohle je ten test, kvůli kterému se port dá vůbec nasadit: {@link ClassicCatalogFtsIndexBuilder}
 * musí ze stejného JSONL vyrobit znak po znaku stejný {@code title}, {@code full_path},
 * {@code search_blob} a {@code territory} jako {@code build_fts_database} v Pythonu. Kdyby se
 * lišily, posunou se výsledky hledání — a nikdo by si toho nevšiml, protože index se tváří zdravě.
 *
 * <p>Test se přeskočí, když ostrý index nebo JSONL nejsou k dispozici (CI bez dat), a taky když
 * počty řádků v indexu neodpovídají JSONL — to znamená, že index je starší než vstup a rozdíl by
 * nebyl chybou portu, ale zastaralými daty.
 *
 * <p>{@code row_json} se porovnává jako rozparsovaný JSON, ne jako řetězec: Jackson serializuje
 * bez mezer ({@code {"a":1}}), Python s nimi ({@code {"a": 1}}). Sloupec je {@code UNINDEXED},
 * takže na hledání to nemá vliv — viz javadoc builderu.
 *
 * <p><b>Sentinel {@code __dimx__}.</b> Ostrý index není jen výstup buildu — u části řad (eurostat,
 * csu) po něm ještě proběhlo {@code scripts/enrich_fts_dimensions_inplace.py}, které dopočítá
 * členy dimenzí přímo v indexu a připíše {@code __dimx__} jako značku „tenhle řádek už jsem
 * viděl". Build ji nepřidává, protože členy zapéká rovnou a idempotenci nepotřebuje. Při
 * porovnání se proto odstraňuje — ověřeno, že samotné členy dimenzí vycházejí shodně.
 */
class ClassicCatalogFtsIndexBuilderPythonParityTest {

    /** Malé zdroje — pokrývají českou diakritiku (arad, csu) i anglické tituly (bis). */
    private static final List<String> SOURCES = List.of("arad", "bis", "csu");

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void javaBuildDaStejnyObsahJakoPythonBuild() throws Exception {
        Path pythonIndex = BankIntelDataPaths.catalogSearchIndexDir().resolve("classic_catalog_search.sqlite");
        Assumptions.assumeTrue(Files.isRegularFile(pythonIndex), "ostrý index chybí: " + pythonIndex);
        Path indexDir = pythonIndex.getParent();
        for (String source : SOURCES) {
            Assumptions.assumeTrue(
                    Files.isRegularFile(indexDir.resolve(source + ".jsonl")),
                    "chybí JSONL pro " + source);
        }

        Path javaIndex = tempDir.resolve("classic_catalog_search.sqlite");
        buildWithJava(indexDir, javaIndex);

        try (Connection python = openRo(pythonIndex);
                Connection java = openRo(javaIndex)) {
            for (String source : SOURCES) {
                Map<String, Row> pythonRows = readRows(python, source);
                Map<String, Row> javaRows = readRows(java, source);

                Assumptions.assumeTrue(
                        !pythonRows.isEmpty(),
                        "ostrý index neobsahuje zdroj " + source + " — není co porovnat");
                Assumptions.assumeTrue(
                        pythonRows.size() == javaRows.size(),
                        "index je nejspíš starší než JSONL (" + source + ": python=" + pythonRows.size()
                                + ", java=" + javaRows.size() + ") — rozdíl není chybou portu");

                List<String> differences = new ArrayList<>();
                for (Map.Entry<String, Row> entry : pythonRows.entrySet()) {
                    Row expected = entry.getValue();
                    Row actual = javaRows.get(entry.getKey());
                    if (actual == null) {
                        differences.add(source + "/" + entry.getKey() + ": v Java indexu chybí");
                        continue;
                    }
                    describeDifference(source, entry.getKey(), expected, actual).ifPresent(differences::add);
                }
                assertTrue(
                        differences.isEmpty(),
                        "Java build se liší od Python buildu u " + source + " (" + differences.size()
                                + " řádků). Prvních pár:\n  " + String.join("\n  ", differences.subList(0,
                                        Math.min(5, differences.size()))));
            }
        }
    }

    private void buildWithJava(Path indexDir, Path javaIndex) {
        BankIntelProperties bankProps = new BankIntelProperties(
                new BankIntelProperties.Jwt("test-secret", 60, 7),
                new BankIntelProperties.Cors(""),
                new BankIntelProperties.Cookie(false, "Lax", ""),
                new BankIntelProperties.Dev(false, false),
                "",
                // indexDir ukazuje na ostrá JSONL, ftsDb do tempu — ostrý index se nesmí přepsat.
                new BankIntelProperties.Catalog(
                        indexDir.toAbsolutePath().normalize().toString(),
                        javaIndex.toAbsolutePath().normalize().toString(),
                        ""),
                new BankIntelProperties.Chat(""),
                new BankIntelProperties.Storage("", "", ""));
        CatalogSearchProperties props = new CatalogSearchProperties(bankProps);
        new ClassicCatalogFtsIndexBuilder(props, new CatalogSqliteReadPool(props), objectMapper).build(SOURCES);
    }

    private java.util.Optional<String> describeDifference(String source, String setId, Row expected, Row actual)
            throws Exception {
        if (!expected.title.equals(actual.title)) {
            return java.util.Optional.of(
                    source + "/" + setId + ": title python=[" + expected.title + "] java=[" + actual.title + "]");
        }
        if (!expected.fullPath.equals(actual.fullPath)) {
            return java.util.Optional.of(source + "/" + setId + ": full_path se liší");
        }
        String expectedBlob = withoutEnrichSentinel(expected.searchBlob);
        if (!expectedBlob.equals(actual.searchBlob)) {
            return java.util.Optional.of(
                    source + "/" + setId + ": search_blob python=[" + expectedBlob + "] java=["
                            + actual.searchBlob + "]");
        }
        if (!expected.territory.equals(actual.territory)) {
            return java.util.Optional.of(source + "/" + setId + ": territory se liší");
        }
        Map<String, Object> expectedJson = parse(expected.rowJson);
        Map<String, Object> actualJson = parse(actual.rowJson);
        if (!expectedJson.equals(actualJson)) {
            return java.util.Optional.of(source + "/" + setId + ": row_json se liší obsahem (ne jen formátem)");
        }
        return java.util.Optional.empty();
    }

    private Map<String, Object> parse(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    private record Row(String title, String fullPath, String searchBlob, String territory, String rowJson) {}

    private static Map<String, Row> readRows(Connection conn, String source) throws Exception {
        Map<String, Row> out = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT set_id, title, full_path, search_blob, territory, row_json FROM catalog_fts "
                        + "WHERE source = ?")) {
            ps.setString(1, source);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(
                            rs.getString(1),
                            new Row(
                                    nullToEmpty(rs.getString(2)),
                                    nullToEmpty(rs.getString(3)),
                                    nullToEmpty(rs.getString(4)),
                                    nullToEmpty(rs.getString(5)),
                                    nullToEmpty(rs.getString(6))));
                }
            }
        }
        return out;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Značka z {@code enrich_fts_dimensions_inplace.py} — build ji nepřidává, viz javadoc třídy. */
    private static String withoutEnrichSentinel(String blob) {
        return blob.endsWith(" __dimx__") ? blob.substring(0, blob.length() - " __dimx__".length()) : blob;
    }

    private static Connection openRo(Path db) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:file:" + db.toAbsolutePath() + "?mode=ro");
    }

    @Test
    void poctyRadkuSedeziSPythonIndexem() throws Exception {
        Path pythonIndex = BankIntelDataPaths.catalogSearchIndexDir().resolve("classic_catalog_search.sqlite");
        Assumptions.assumeTrue(Files.isRegularFile(pythonIndex), "ostrý index chybí: " + pythonIndex);
        Path indexDir = pythonIndex.getParent();
        Assumptions.assumeTrue(
                Files.isRegularFile(indexDir.resolve("bis.jsonl")), "chybí bis.jsonl");

        Path javaIndex = tempDir.resolve("classic_catalog_search.sqlite");
        buildWithJava(indexDir, javaIndex);

        try (Connection python = openRo(pythonIndex);
                Connection java = openRo(javaIndex)) {
            // catalog_rows_lookup musí mít stejně řádků jako catalog_fts — právě tady měl Python
            // chybu s průběžným flushem, kvůli které v lookupu chybělo 99 % set_id.
            assertEquals(
                    readRows(java, "bis").size(),
                    countLookup(java, "bis"),
                    "catalog_rows_lookup má mít stejně řádků jako catalog_fts");
            assertEquals(readRows(python, "bis").size(), readRows(java, "bis").size());
        }
    }

    private static int countLookup(Connection conn, String source) throws Exception {
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT COUNT(*) FROM catalog_rows_lookup WHERE source = ?")) {
            ps.setString(1, source);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }
}
