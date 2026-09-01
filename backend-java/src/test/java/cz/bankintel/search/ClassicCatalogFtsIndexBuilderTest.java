package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.config.BankIntelProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Build classic FTS indexu v Javě — náhrada za shell-out na
 * {@code scripts/build_classic_catalog_fts_index.py}.
 *
 * <p>Testuje se to, co při rozbití tiše degraduje hledání: schéma (včetně {@code catalog_rows_lookup},
 * kde v Pythonu byla chyba s průběžným flushem), zapečení členů dimenzí do {@code search_blob}
 * a atomická výměna souboru.
 */
class ClassicCatalogFtsIndexBuilderTest {

    @TempDir
    Path tempDir;

    private ClassicCatalogFtsIndexBuilder builder;
    private Path ftsDb;

    @BeforeEach
    void setUp() {
        ftsDb = tempDir.resolve("classic_catalog_search.sqlite");
        BankIntelProperties bankProps = new BankIntelProperties(
                new BankIntelProperties.Jwt("test-secret", 60, 7),
                new BankIntelProperties.Cors(""),
                new BankIntelProperties.Cookie(false, "Lax", ""),
                new BankIntelProperties.Dev(false, false),
                "",
                new BankIntelProperties.Catalog(
                        tempDir.toAbsolutePath().normalize().toString(),
                        ftsDb.toAbsolutePath().normalize().toString(),
                        ""),
                new BankIntelProperties.Chat(""),
                new BankIntelProperties.Storage("", "", ""));
        CatalogSearchProperties props = new CatalogSearchProperties(bankProps);
        builder = new ClassicCatalogFtsIndexBuilder(
                props, new CatalogSqliteReadPool(props), new ObjectMapper());
    }

    @AfterEach
    void clearEnvOverride() {
        System.clearProperty("CATALOG_FTS_PRUNE_STALE_BEFORE");
    }

    private void writeJsonl(String source, String... lines) throws Exception {
        Files.writeString(
                tempDir.resolve(source + ".jsonl"),
                String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
    }

    @Test
    void postaviIndexSObemaTabulkamiAMetadaty() throws Exception {
        writeJsonl(
                "arad",
                "{\"set_id\":\"A1\",\"name\":\"Hrubý domácí produkt\",\"full_path\":\"Národní účty\","
                        + "\"search_blob\":\"hdp gdp\",\"territory\":\"CZ\"}",
                "{\"set_id\":\"A2\",\"name\":\"Inflace\",\"search_blob\":\"cpi\"}");

        Map<String, Object> summary = builder.build(List.of("arad"));

        assertEquals(2L, ((Number) summary.get("total_rows")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> arad = (Map<String, Object>) ((Map<String, Object>) summary.get("sources")).get("arad");
        assertEquals("ok", arad.get("status"));
        assertEquals(2, arad.get("rows"));
        assertTrue(Files.isRegularFile(ftsDb), "index se měl přesunout na cílovou cestu");
        assertFalse(
                Files.exists(tempDir.resolve("classic_catalog_search.tmp.sqlite")),
                "dočasný soubor měl po výměně zmizet");

        try (Connection conn = open()) {
            assertEquals(2, count(conn, "SELECT COUNT(*) FROM catalog_fts"));
            // Lookup tabulka musí mít VŠECHNY řádky, ne jen poslední batch — to byla reálná chyba
            // v Pythonu, kvůli které rychlá indexovaná cesta míjela a lookup padal na pomalý scan.
            assertEquals(2, count(conn, "SELECT COUNT(*) FROM catalog_rows_lookup"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM catalog_fts_meta WHERE source='arad' AND row_count=2"));
        }
    }

    @Test
    void lookupTabulkaObsahujeIRadkyZPrubeznehoFlushe() throws Exception {
        // Přes 500 řádků => aspoň jeden průběžný flush batche.
        String[] lines = new String[1200];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = "{\"set_id\":\"S" + i + "\",\"name\":\"Řada " + i + "\"}";
        }
        writeJsonl("csu", lines);

        builder.build(List.of("csu"));

        try (Connection conn = open()) {
            assertEquals(1200, count(conn, "SELECT COUNT(*) FROM catalog_fts"));
            assertEquals(1200, count(conn, "SELECT COUNT(*) FROM catalog_rows_lookup"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM catalog_rows_lookup WHERE set_id='S0'"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM catalog_rows_lookup WHERE set_id='S1199'"));
        }
    }

    @Test
    void cleniDimenziSeZapecouDoSearchBlobuATimJsouHledatelni() throws Exception {
        writeJsonl(
                "eurostat",
                "{\"set_id\":\"E1\",\"name\":\"Unemployment rate by sex\",\"search_blob\":\"unemployment\"}",
                "{\"set_id\":\"E2\",\"name\":\"Gross domestic product\",\"search_blob\":\"gdp\"}");

        builder.build(List.of("eurostat"));

        try (Connection conn = open()) {
            // "zeny" není nikde v původních datech - do blobu se dostane jen zapečením dimenze.
            assertEquals(
                    1, count(conn, "SELECT COUNT(*) FROM catalog_fts WHERE catalog_fts MATCH 'zeny'"),
                    "řada s dimenzí by sex má být dohledatelná přes český člen dimenze");
            assertEquals(
                    "E1",
                    scalar(conn, "SELECT set_id FROM catalog_fts WHERE catalog_fts MATCH 'zeny'"));
            // Řada bez dimenze v titulu nesmí dostat nic navíc.
            assertEquals("gdp", scalar(conn, "SELECT search_blob FROM catalog_fts WHERE set_id='E2'"));
        }
    }

    @Test
    void radekBezSetIdNeboRozbityJsonSeVynechaAleBuildDobehne() throws Exception {
        writeJsonl(
                "bis",
                "{\"set_id\":\"B1\",\"name\":\"Platný\"}",
                "{\"name\":\"Bez set_id\"}",
                "{tohle není json}",
                "",
                "{\"id\":\"B2\",\"name\":\"Fallback na id\"}");

        Map<String, Object> summary = builder.build(List.of("bis"));

        assertEquals(2L, ((Number) summary.get("total_rows")).longValue());
        try (Connection conn = open()) {
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM catalog_rows_lookup WHERE set_id='B2'"));
        }
    }

    @Test
    void chybejiciJsonlSeOznaciAleNeshodiBuild() throws Exception {
        writeJsonl("arad", "{\"set_id\":\"A1\",\"name\":\"Je tu\"}");

        Map<String, Object> summary = builder.build(List.of("arad", "imf"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sources = (Map<String, Object>) summary.get("sources");
        @SuppressWarnings("unchecked")
        Map<String, Object> imf = (Map<String, Object>) sources.get("imf");
        assertEquals("missing_jsonl", imf.get("status"));
        assertEquals(0, imf.get("rows"));
        assertEquals(1L, ((Number) summary.get("total_rows")).longValue());
    }

    @Test
    void titulSeVezmeZeSetIdKdyzChybiNazev() throws Exception {
        writeJsonl("arad", "{\"set_id\":\"A9\"}");

        builder.build(List.of("arad"));

        try (Connection conn = open()) {
            assertEquals("A9", scalar(conn, "SELECT title FROM catalog_fts WHERE set_id='A9'"));
        }
    }

    @Test
    void prestavbaNahradiPuvodniIndex() throws Exception {
        writeJsonl("arad", "{\"set_id\":\"A1\",\"name\":\"První verze\"}");
        builder.build(List.of("arad"));

        writeJsonl(
                "arad",
                "{\"set_id\":\"A1\",\"name\":\"Druhá verze\"}",
                "{\"set_id\":\"A2\",\"name\":\"Nová řada\"}");
        builder.build(List.of("arad"));

        try (Connection conn = open()) {
            assertEquals(2, count(conn, "SELECT COUNT(*) FROM catalog_fts"));
            assertEquals("Druhá verze", scalar(conn, "SELECT title FROM catalog_fts WHERE set_id='A1'"));
        }
    }

    @Test
    void rebuildKteryByVratilProrezaneRadkySeZastaviAIndexNechaBytZeStareho() throws Exception {
        // Ostrý index je prořezaný nástroji, které pracují PŘÍMO nad ním (prune_fred_local_series.py
        // a spol.) - naměřeno 2026-09-01: fred má v JSONL 844 759 řad, v indexu 261 602. Rebuild
        // z JSONL by je všechny vrátil a zanesl hledání mrtvými řadami. Python to udělá mlčky.
        String[] few = new String[200];
        for (int i = 0; i < few.length; i++) {
            few[i] = "{\"set_id\":\"F" + i + "\",\"name\":\"Kurátorovaná řada " + i + "\"}";
        }
        writeJsonl("fred", few);
        builder.build(List.of("fred"));

        String[] many = new String[1000];
        for (int i = 0; i < many.length; i++) {
            many[i] = "{\"set_id\":\"F" + i + "\",\"name\":\"Řada " + i + "\"}";
        }
        writeJsonl("fred", many);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> builder.build(List.of("fred")));
        assertTrue(ex.getMessage().contains("kurátorováním"), ex.getMessage());

        try (Connection conn = open()) {
            assertEquals(200, count(conn, "SELECT COUNT(*) FROM catalog_fts"),
                    "index se nesměl vyměnit, dokud to někdo vědomě nepovolí");
        }
        assertTrue(
                Files.exists(tempDir.resolve("classic_catalog_search.tmp.sqlite")),
                "hotový build má zůstat k inspekci");
    }

    @Test
    void sPovolenymResetemKuratorovaniRebuildProjde() throws Exception {
        writeJsonl("fred", "{\"set_id\":\"F1\",\"name\":\"Jedna\"}");
        builder.build(List.of("fred"));

        String[] many = new String[500];
        for (int i = 0; i < many.length; i++) {
            many[i] = "{\"set_id\":\"F" + i + "\",\"name\":\"Řada " + i + "\"}";
        }
        writeJsonl("fred", many);

        withEnv("CATALOG_FTS_ALLOW_CURATION_RESET", "1", () -> builder.build(List.of("fred")));

        try (Connection conn = open()) {
            assertEquals(500, count(conn, "SELECT COUNT(*) FROM catalog_fts"));
        }
    }

    @Test
    void bezneDoplneniParRadNeniPovazovaneZaZahozeniKuratorovani() throws Exception {
        String[] before = new String[300];
        for (int i = 0; i < before.length; i++) {
            before[i] = "{\"set_id\":\"X" + i + "\",\"name\":\"Řada " + i + "\"}";
        }
        writeJsonl("arad", before);
        builder.build(List.of("arad"));

        // +5 nových řad je normální upstream přírůstek, ne ztráta kurátorování.
        String[] after = new String[305];
        for (int i = 0; i < after.length; i++) {
            after[i] = "{\"set_id\":\"X" + i + "\",\"name\":\"Řada " + i + "\"}";
        }
        writeJsonl("arad", after);

        Map<String, Object> summary = builder.build(List.of("arad"));
        assertEquals(305L, ((Number) summary.get("total_rows")).longValue());
    }

    @Test
    void nastavenyPruneStaleBeforeBuildOdmitneMistoAbyPostavilJinyIndex() {
        // Kurátorování podle aktuálnosti se do Javy neportovalo. Kdyby se proměnná tiše ignorovala,
        // vznikl by index s řadami, které by Python zahodil - a nikdo by si toho nevšiml.
        withEnv("CATALOG_FTS_PRUNE_STALE_BEFORE", "2024", () -> {
            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> builder.build(List.of("arad")));
            assertTrue(ex.getMessage().contains("CATALOG_FTS_PRUNE_STALE_BEFORE"), ex.getMessage());
        });
    }

    @Test
    void nulovyPruneStaleBeforeJeBrannJakoVypnuty() throws Exception {
        writeJsonl("arad", "{\"set_id\":\"A1\",\"name\":\"Běží\"}");
        withEnv("CATALOG_FTS_PRUNE_STALE_BEFORE", "0", () -> {
            Map<String, Object> summary = builder.build(List.of("arad"));
            assertEquals(1L, ((Number) summary.get("total_rows")).longValue());
        });
    }

    private void withEnv(String key, String value, Runnable body) {
        System.setProperty(key, value);
        try {
            body.run();
        } finally {
            System.clearProperty(key);
        }
    }

    private Connection open() throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + ftsDb.toAbsolutePath());
        assertNotNull(conn);
        return conn;
    }

    private static int count(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static String scalar(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
