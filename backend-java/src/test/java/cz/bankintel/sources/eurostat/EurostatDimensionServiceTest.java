package cz.bankintel.sources.eurostat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Živě zjištěno: {@code probeCombinationHasData} dřív kontrolovala jen, že Eurostat vrátil
 * neprázdnou mapu hodnot - ne že v ní je aspoň jedno nenulové číslo. Kombinace jako
 * "naio_10_pyp1620" + ind_use=T pro Česko tak formálně "prošla", i když appka pak ukázala jen
 * samé nuly. Tyhle testy pokrývají čistou {@code hasNonZeroMagnitude}, bez nutnosti mockovat HTTP.
 */
class EurostatDimensionServiceTest {

    @Test
    void hasNonZeroMagnitude_falseWhenEveryParsedValueIsZero() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("0", 0);
        values.put("1", 0.0);
        values.put("2", "0");

        assertThat(EurostatDimensionService.hasNonZeroMagnitude(values)).isFalse();
    }

    @Test
    void hasNonZeroMagnitude_trueWhenAtLeastOneValueIsNonZero() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("0", 0);
        values.put("1", 2.87);
        values.put("2", 0);

        assertThat(EurostatDimensionService.hasNonZeroMagnitude(values)).isTrue();
    }

    @Test
    void hasNonZeroMagnitude_trueWhenNothingParsesAsANumber() {
        // Nejednoznačné - žádné číslo se nepodařilo přečíst - radši projít, než appce falešně
        // zablokovat kombinaci, o které ve skutečnosti nic nevíme.
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("0", null);
        values.put("1", ":");

        assertThat(EurostatDimensionService.hasNonZeroMagnitude(values)).isTrue();
    }

    @Test
    void hasNonZeroMagnitude_trueForASingleRealNonZeroObservation() {
        assertThat(EurostatDimensionService.hasNonZeroMagnitude(Map.of("0", 507))).isTrue();
    }

    @Test
    void hasNonZeroMagnitude_falseForASingleZeroObservation() {
        assertThat(EurostatDimensionService.hasNonZeroMagnitude(Map.of("0", 0))).isFalse();
    }

    @Test
    void hasNonZeroMagnitude_falseForACompletelyEmptyValueMap() {
        // Živě zjištěno: naio_10_pyp1620 s ind_use=T/cpa2_1=CPA_T pro Česko vrací doslova
        // "value":{} - žádná pozorování vůbec, ne jen samé nuly. Prázdná mapa musí znamenat
        // "žádná data" stejně jednoznačně jako mapa samých nul.
        assertThat(EurostatDimensionService.hasNonZeroMagnitude(Map.of())).isFalse();
    }
}
