package cz.bankintel.service.calculations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Základní výpočty nad dvěma řadami (poměr, součet, rozdíl, násobek, procento).
 *
 * Kontext: tahle třída neměla žádný test, přestože přes ni chodí všechny „výpočty nad grafy"
 * včetně ochrany proti dělení nulou. A když se řady nepotkaly v období, hlásila jen „žádné
 * společné období" — uživatel neměl jak zjistit, že příčinou je rozdílná frekvence.
 */
class SeriesCalculationEngineTest {

    private static Map<String, Double> series(String[] periods, double... values) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < periods.length; i++) {
            out.put(periods[i], values[i]);
        }
        return out;
    }

    private static final String[] MESICE = {"2024-01", "2024-02"};

    @Test
    void pomerDeliHodnoty() {
        var result = SeriesCalculationEngine.computeBinaryScaled(
                series(MESICE, 10, 20), series(MESICE, 2, 4), "ratio", 1, 1);
        assertThat(result.rows()).hasSize(2);
        assertThat((Double) result.rows().get(0).get("value")).isCloseTo(5.0, within(1e-9));
    }

    @Test
    void soucetRozdilANasobekPocitajiSpravne() {
        var soucet = SeriesCalculationEngine.computeBinaryScaled(
                series(MESICE, 10, 20), series(MESICE, 1, 2), "sum", 1, 1);
        assertThat((Double) soucet.rows().get(1).get("value")).isCloseTo(22.0, within(1e-9));

        var rozdil = SeriesCalculationEngine.computeBinaryScaled(
                series(MESICE, 10, 20), series(MESICE, 1, 2), "diff", 1, 1);
        assertThat((Double) rozdil.rows().get(1).get("value")).isCloseTo(18.0, within(1e-9));

        var nasobek = SeriesCalculationEngine.computeBinaryScaled(
                series(MESICE, 10, 20), series(MESICE, 3, 4), "mult", 1, 1);
        assertThat((Double) nasobek.rows().get(0).get("value")).isCloseTo(30.0, within(1e-9));
    }

    @Test
    void deleniNulouBodPreskociAVarujeMistoNekonecna() {
        var result = SeriesCalculationEngine.computeBinaryScaled(
                series(MESICE, 10, 20), series(MESICE, 0, 4), "ratio", 1, 1);
        assertThat(result.rows()).hasSize(1);
        assertThat(result.warnings()).anyMatch(w -> w.contains("dělení nulou"));
    }

    @Test
    void ruznaFrekvenceToRekneMistoObecneHlasky() {
        // Měsíční a čtvrtletní řada se spojují přesnou shodou textu období, takže se nikdy
        // nepotkají. Uživatel to musí poznat z hlášky.
        var result = SeriesCalculationEngine.computeBinaryScaled(
                series(MESICE, 10, 20),
                series(new String[] {"2024-Q1", "2024-Q2"}, 1, 2),
                "ratio", 1, 1);
        assertThat(result.rows()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("různou frekvenci").contains("měsíčně").contains("čtvrtletně");
    }

    @Test
    void stejnaFrekvenceBezPrekryvuHlasiObecnouPricinu() {
        var result = SeriesCalculationEngine.computeBinaryScaled(
                series(new String[] {"2024-01"}, 10),
                series(new String[] {"2025-01"}, 20),
                "ratio", 1, 1);
        assertThat(result.rows()).isEmpty();
        assertThat(result.warnings().get(0)).contains("Žádné společné období");
    }

    @Test
    void spolecnaObdobiSeProtnouIKdyzSeRadyLisiRozsahem() {
        var result = SeriesCalculationEngine.computeBinaryScaled(
                series(new String[] {"2024-01", "2024-02", "2024-03"}, 10, 20, 30),
                series(new String[] {"2024-02", "2024-03"}, 2, 3),
                "diff", 1, 1);
        assertThat(result.rows()).hasSize(2);
        assertThat(List.of(result.rows().get(0).get("period"), result.rows().get(1).get("period")))
                .containsExactly("2024-02", "2024-03");
    }
}
