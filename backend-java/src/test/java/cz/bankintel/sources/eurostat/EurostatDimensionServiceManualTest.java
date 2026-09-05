package cz.bankintel.sources.eurostat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EurostatDimensionServiceManualTest {

    @Test
    void resolvePreviewQueryParams_liveNetwork() {
        EurostatDimensionService service = new EurostatDimensionService(new ObjectMapper(), new EurostatRateLimiter());
        Map<String, Object> qp = service.resolvePreviewQueryParams("prc_hicp_midx", "CZ");
        assertFalse(qp.isEmpty(), "resolved params: " + qp);
        assertFalse(String.valueOf(qp.get("coicop")).isBlank());
    }

    /**
     * Živě zjištěno (screenshot uživatelky): naio_10_pyp1620 s ind_use=T, cpa2_1=CPA_T pro
     * Česko formálně "existuje" jako dimenze/kód, ale Eurostat pro ni vrátí doslova prázdnou
     * mapu pozorování ("value":{}) - appka to dřív ukázala jako platnou výchozí kombinaci.
     * combinationHasData teď musí tenhle konkrétní, dřív rozbitý případ odmítnout.
     */
    @Test
    void combinationHasData_liveNetwork_rejectsTheAllZeroDefaultThatUsedToPass() {
        EurostatDimensionService service = new EurostatDimensionService(new ObjectMapper(), new EurostatRateLimiter());

        boolean hasData = service.combinationHasData(
                "naio_10_pyp1620", Map.of("geo", "CZ", "ind_use", "T", "cpa2_1", "CPA_T"));

        assertThat(hasData).isFalse();
    }

    @Test
    void combinationHasData_liveNetwork_acceptsARealCombinationWithGenuineData() {
        EurostatDimensionService service = new EurostatDimensionService(new ObjectMapper(), new EurostatRateLimiter());

        boolean hasData = service.combinationHasData("prc_hicp_midx", Map.of("geo", "CZ", "coicop", "CP00"));

        assertThat(hasData).isTrue();
    }

    /**
     * End-to-end reprodukce přesně toho, co viděla uživatelka: čerstvé otevření náhledu
     * naio_10_pyp1620 pro Česko dřív dorazilo na ind_use="T" (prázdná data). Po kroku 2 plánu
     * (preferovaný kód G45 + zpřísněné ověření z kroku 1) musí cascade dojít k jinému,
     * skutečně naplněnému výběru.
     */
    @Test
    void resolvePreviewQueryParams_liveNetwork_naio10Pyp1620NoLongerDefaultsToTheEmptyTCombination() {
        EurostatDimensionService service = new EurostatDimensionService(new ObjectMapper(), new EurostatRateLimiter());

        Map<String, Object> qp = service.resolvePreviewQueryParams("naio_10_pyp1620", "CZ");

        assertFalse(qp.isEmpty(), "resolved params: " + qp);
        assertThat(String.valueOf(qp.get("ind_use"))).isNotEqualTo("T");
        Map<String, String> selected = new java.util.LinkedHashMap<>();
        qp.forEach((k, v) -> selected.put(k, String.valueOf(v)));
        assertThat(service.combinationHasData("naio_10_pyp1620", selected))
                .as("resolved combination must have real data: " + qp)
                .isTrue();
    }
}
