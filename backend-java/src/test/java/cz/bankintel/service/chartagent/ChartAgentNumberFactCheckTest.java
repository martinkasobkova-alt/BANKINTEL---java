package cz.bankintel.service.chartagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Kontrola čísel v odpovědi AI nad grafem proti skutečným datům.
 *
 * Kontext: odpověď formuluje jazykový model nad deterministicky spočítanými daty, ale nic dřív
 * neověřovalo, že model v textu neuvedl číslo, které se v grafu vůbec nevyskytuje.
 */
class ChartAgentNumberFactCheckTest {

    private static Map<String, Object> series(double... values) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (double v : values) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("period", "p" + points.size());
            point.put("value", v);
            points.add(point);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", "main");
        out.put("label", "Test");
        out.put("points", points);
        return out;
    }

    @Test
    void holaMalaCislaSeIgnoruji() {
        // "3 řady" není datový údaj, i kdyz se 3 nikde v datech nenachází.
        List<String> result = ChartAgentNumberFactCheck.unverifiedNumbers(
                "Graf obsahuje 3 řady.", List.of(series(100.0)), List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void ctyrciselnyRokSeIgnoruje() {
        List<String> result = ChartAgentNumberFactCheck.unverifiedNumbers(
                "V roce 2024 hodnota klesla.", List.of(series(100.0)), List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void cisloBlizkeSkutecneHodnoteJeOvereno() {
        // Model zaokrouhlil 45,678 na 45,68 — musí to projít.
        List<String> result = ChartAgentNumberFactCheck.unverifiedNumbers(
                "Poslední hodnota je 45,68.", List.of(series(45.678)), List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void cisloBezOporyVDatechJeOznacenoJakoNeoverene() {
        List<String> result = ChartAgentNumberFactCheck.unverifiedNumbers(
                "Poslední hodnota je 99,9.", List.of(series(45.678)), List.of());
        assertThat(result).containsExactly("99,9");
    }

    @Test
    void procentniZmenaOdvozenaZBoduJeOverena() {
        // 100 -> 110 je mezidobní změna +10 %; tohle číslo se v surových bodech neobjeví.
        List<String> result =
                ChartAgentNumberFactCheck.unverifiedNumbers("Meziročně to vzrostlo o 10 %.", List.of(series(100.0, 110.0)), List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void cisloSTisicovymOddelovacemSeSpravneNormalizuje() {
        List<String> result = ChartAgentNumberFactCheck.unverifiedNumbers(
                "Objem dosáhl 12 345,6.", List.of(series(12345.6)), List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void cisloZVypoctuJeOvereno() {
        Map<String, Object> calc = Map.of("type", "sharpe_ratio", "value", 1.234);
        List<String> result = ChartAgentNumberFactCheck.unverifiedNumbers(
                "Sharpe ratio vychází 1,234.", List.of(), List.of(calc));
        assertThat(result).isEmpty();
    }

    @Test
    void prazdnaOdpovedNeprodukujeNalezy() {
        assertThat(ChartAgentNumberFactCheck.unverifiedNumbers("", List.of(series(1.0)), List.of()))
                .isEmpty();
        assertThat(ChartAgentNumberFactCheck.unverifiedNumbers(null, List.of(series(1.0)), List.of()))
                .isEmpty();
    }

    @Test
    void nahradniHodnotaBezSkutecnehoObdobiVyvolaVarovani() {
        Map<String, Object> lookup = Map.of(
                "requested_year", "2020",
                "exact_matches", List.of(),
                "nearest_available", Map.of("period", "2018", "value", 45.2));

        String warning = ChartAgentNumberFactCheck.misattributedPeriodWarning(
                "V roce 2020 dosáhla hodnota 45,2.", lookup);

        assertThat(warning).contains("2020").contains("2018");
    }

    @Test
    void nahradniHodnotaSUvedenymObdobimNevyvolaVarovani() {
        Map<String, Object> lookup = Map.of(
                "requested_year", "2020",
                "exact_matches", List.of(),
                "nearest_available", Map.of("period", "2018", "value", 45.2));

        String warning = ChartAgentNumberFactCheck.misattributedPeriodWarning(
                "Pro rok 2020 nemám data, nejbližší dostupná hodnota je z roku 2018: 45,2.", lookup);

        assertThat(warning).isNull();
    }

    @Test
    void existujiciExactMatchNevyvolaVarovani() {
        Map<String, Object> lookup = Map.of(
                "requested_year", "2020",
                "exact_matches", List.of(Map.of("period", "2020", "value", 45.2)));

        assertThat(ChartAgentNumberFactCheck.misattributedPeriodWarning("V roce 2020: 45,2.", lookup))
                .isNull();
    }

    @Test
    void prazdnyLookupNevyvolaVarovani() {
        assertThat(ChartAgentNumberFactCheck.misattributedPeriodWarning("cokoliv", Map.of())).isNull();
        assertThat(ChartAgentNumberFactCheck.misattributedPeriodWarning("cokoliv", null)).isNull();
    }
}
