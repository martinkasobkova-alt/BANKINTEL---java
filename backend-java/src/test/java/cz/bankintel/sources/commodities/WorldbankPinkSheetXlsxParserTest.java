package cz.bankintel.sources.commodities;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * Živě zjištěno (data/worldbank_pink_sheet_catalog.json): appka v katalogu Komodity → Pink
 * Sheet → Ostatní ukazovala 17 fantomových karet "SERIES", "SERIES_2" ... "SERIES_17" - žádná
 * z nich neměla za sebou jediné pozorování. Příčina: sloupec bez skutečné hlavičky (ani kódový
 * řádek, ani název) prošel přes {@link WorldbankPinkSheetXlsxParser#slugify}, jehož fallback pro
 * prázdný vstup vrací neprázdný řetězec "SERIES" - takže kontrola "je kód prázdný?" o pár řádků
 * níž už tenhle případ nikdy nezachytila.
 */
class WorldbankPinkSheetXlsxParserTest {

    /**
     * Postaví minimální "Monthly Prices" list přesně ve tvaru, který parser čeká: řádek 3 (0-idx)
     * "Updated on ...", řádek 4 názvy, řádek 5 jednotky, řádek 6 kódy (sloupec 0 musí NEvypadat
     * jako perioda, jinak parser usoudí, že kódový řádek chybí), řádek 7+ data (YYYYMmm + hodnoty).
     * Sloupec 2 je záměrně úplně bez hlavičky (ani kód, ani název) - simuluje reálný fantomový
     * sloupec z produkčního souboru.
     */
    private static byte[] buildMonthlyPricesWorkbook() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Monthly Prices");
            setRow(sheet, 0, "World Bank Commodity Price Data (Pink Sheet)");
            setRow(sheet, 1, "");
            setRow(sheet, 2, "");
            setRow(sheet, 3, "Updated on January 2, 2026");
            setRow(sheet, 4, "", "Crude oil, average", "", "Gold");
            setRow(sheet, 5, "", "$/bbl", "", "$/toz");
            setRow(sheet, 6, "", "CRUDE_PETRO", "", "GOLD");
            setRow(sheet, 7, "1990M01", "22.9", "999", "383.5");
            setRow(sheet, 8, "1990M02", "21.6", "999", "401.9");
            return toBytes(wb);
        }
    }

    private static void setRow(Sheet sheet, int rowIdx, String... values) {
        Row row = sheet.createRow(rowIdx);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }

    private static byte[] toBytes(XSSFWorkbook wb) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void parsePinkSheetMonthlyXlsx_skipsColumnWithNoCodeAndNoName() throws Exception {
        Map<String, Object> out = WorldbankPinkSheetXlsxParser.parsePinkSheetMonthlyXlsx(buildMonthlyPricesWorkbook());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) out.get("series");

        assertThat(series)
                .as("sloupec bez hlavičky (ani kód, ani název) se vůbec nemá stát kartou")
                .extracting(s -> s.get("code"))
                .doesNotContain("SERIES");
        assertThat(series).extracting(s -> s.get("code")).containsExactlyInAnyOrder("CRUDE_PETRO", "GOLD");
    }

    @Test
    void parsePinkSheetMonthlyXlsx_realColumnsStillParseWithObservations() throws Exception {
        Map<String, Object> out = WorldbankPinkSheetXlsxParser.parsePinkSheetMonthlyXlsx(buildMonthlyPricesWorkbook());

        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> observations =
                (Map<String, List<Map<String, Object>>>) out.get("observations");

        assertThat(observations.get("GOLD"))
                .as("skutečný sloupec s daty se opravou nesmí ztratit")
                .hasSize(2)
                .anySatisfy(point -> assertThat(point.get("value")).isEqualTo(383.5));
    }

    /**
     * Živě zjištěno PROTI SKUTEČNĚ STAŽENÉMU souboru: první verze týhle opravy prošla syntetickým
     * testem výš, ale na živém souboru fantomy vůbec neodstranila - aktuální rok World Bank
     * souboru totiž NEMÁ vlastní kódový řádek (řádek 6 je rovnou první řádek dat), takže parser
     * jde větví, která si kódy dřív předem slugifikovala ZE VŠECH názvů (i prázdných na "SERIES")
     * JEŠTĚ PŘED kontrolou "je sloupec prázdný?" - ta pak už viděla jen hotový, nikdy prázdný
     * fallback. Tenhle test simuluje přesně tenhle (žádný kódový řádek) tvar souboru, aby se
     * regrese příště chytila tady, ne až živým dotazem na worldbank.org.
     */
    private static byte[] buildMonthlyPricesWorkbookWithoutCodeRow() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Monthly Prices");
            setRow(sheet, 0, "World Bank Commodity Price Data (Pink Sheet)");
            setRow(sheet, 1, "");
            setRow(sheet, 2, "");
            setRow(sheet, 3, "Updated on January 2, 2026");
            setRow(sheet, 4, "", "Crude oil, average", "", "Gold");
            setRow(sheet, 5, "", "$/bbl", "", "$/toz");
            // Řádek 6 = rovnou první řádek dat (platná perioda v prvním sloupci), ne kódy - přesně
            // tvar, který má aktuální živě stažený soubor.
            setRow(sheet, 6, "1990M01", "22.9", "999", "383.5");
            setRow(sheet, 7, "1990M02", "21.6", "999", "401.9");
            return toBytes(wb);
        }
    }

    @Test
    void parsePinkSheetMonthlyXlsx_skipsHeaderlessColumnEvenWithoutADedicatedCodeRow() throws Exception {
        Map<String, Object> out =
                WorldbankPinkSheetXlsxParser.parsePinkSheetMonthlyXlsx(buildMonthlyPricesWorkbookWithoutCodeRow());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) out.get("series");

        assertThat(series).extracting(s -> s.get("code")).doesNotContain("SERIES");
        assertThat(series).extracting(s -> s.get("code")).containsExactlyInAnyOrder("CRUDE_OIL_AVERAGE", "GOLD");
    }

    @Test
    void slugify_blankInputStillReturnsNonBlankFallback_documentingWhyTheColumnCheckMattersUpstream() {
        // slugify() samo o sobě zůstává beze změny - "SERIES" je platný fallback pro jiná volací
        // místa (např. když kód chybí, ale NÁZEV existuje). Bezpečnostní pojistka proti
        // fantomovým kartám patří výš, na úrovni sloupce (viz test výše) - tohle jen dokumentuje,
        // proč ta pojistka nemůže spoléhat na "je výsledek slugify prázdný?".
        assertThat(WorldbankPinkSheetXlsxParser.slugify("")).isEqualTo("SERIES");
    }
}
