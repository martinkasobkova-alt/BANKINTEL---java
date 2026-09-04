package cz.bankintel.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Meziroční změna a odvození frekvence.
 *
 * Kontext: „YoY" se počítalo natvrdo posunem o 12 pozorování bez ohledu na frekvenci, takže
 * u čtvrtletní řady to byla tříletá změna a u roční dvanáctiletá — a obojí se uživateli i modelu
 * podávalo jako meziroční. Tahle matematika neměla žádný test.
 */
class ExploreSummarizeMetricsTest {

    private static List<Map<String, Object>> series(String periodPattern, double... values) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", String.format(periodPattern, i));
            row.put("value", values[i]);
            out.add(row);
        }
        return out;
    }

    private static double[] ramp(int n, double start, double step) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = start + i * step;
        }
        return out;
    }

    @Test
    void frekvenceSeVezmeZMetadatZdroje() {
        assertThat(ExploreSummarizeFetchService.resolveFrequencyCode(Map.of("freq", "Q"), List.of())).isEqualTo("Q");
        assertThat(ExploreSummarizeFetchService.resolveFrequencyCode(Map.of("frequency", "monthly"), List.of()))
                .isEqualTo("M");
        // Roční se u zdrojů píše jako A i Y; obojí musí dopadnout stejně.
        assertThat(ExploreSummarizeFetchService.resolveFrequencyCode(Map.of("freq", "Y"), List.of())).isEqualTo("A");
    }

    @Test
    void bezMetadatSeFrekvenceOdvodiZTvaruObdobi() {
        assertThat(ExploreSummarizeFetchService.resolveFrequencyCode(Map.of(), series("20%02d", 1, 2)))
                .isEqualTo("A");
        assertThat(ExploreSummarizeFetchService.resolveFrequencyCode(Map.of(), series("2024-Q%d", 1, 2)))
                .isEqualTo("Q");
        assertThat(ExploreSummarizeFetchService.resolveFrequencyCode(Map.of(), series("2024-0%d", 1, 2)))
                .isEqualTo("M");
        assertThat(ExploreSummarizeFetchService.resolveFrequencyCode(Map.of(), series("2024-01-0%d", 1, 2)))
                .isEqualTo("D");
    }

    @Test
    void mesicniRadaPouzijePosunODvanactPozorovani() {
        // 25 měsíců, každý +1: rok zpět je o 12 pozorování, tedy o 12 níž.
        Map<String, Object> m = ExploreSummarizeFetchService.computeMetrics(series("m%02d", ramp(25, 100, 1)), "M");
        assertThat((Double) m.get("year_on_year_change")).isCloseTo(12.0 / 112.0, within(1e-9));
    }

    @Test
    void ctvrtletniRadaPouzijePosunOCtyriPozorovani() {
        // Dřív se sáhlo o 12 zpět, což byla tříletá změna označená jako meziroční.
        Map<String, Object> m = ExploreSummarizeFetchService.computeMetrics(series("q%02d", ramp(25, 100, 1)), "Q");
        assertThat((Double) m.get("year_on_year_change")).isCloseTo(4.0 / 120.0, within(1e-9));
    }

    @Test
    void rocniRadaPouzijePosunOJednoPozorovani() {
        Map<String, Object> m = ExploreSummarizeFetchService.computeMetrics(series("y%02d", ramp(25, 100, 1)), "A");
        assertThat((Double) m.get("year_on_year_change")).isCloseTo(1.0 / 123.0, within(1e-9));
    }

    @Test
    void kratkaRadaMezirocniZmenuNehlasi() {
        // Tři čtvrtletí na meziroční srovnání nestačí — dřív se u 13+ bodů počítalo vždy.
        Map<String, Object> m = ExploreSummarizeFetchService.computeMetrics(series("q%02d", 1, 2, 3), "Q");
        assertThat(m).doesNotContainKey("year_on_year_change");
    }

    @Test
    void neznamaFrekvenceRadsiNepocitaNezLze() {
        // Bez frekvence je jakýkoli posun hádání; radši číslo neuvádět než uvést špatné.
        Map<String, Object> m = ExploreSummarizeFetchService.computeMetrics(series("x%02d", ramp(25, 100, 1)), "");
        assertThat(m).doesNotContainKey("year_on_year_change");
    }
}
