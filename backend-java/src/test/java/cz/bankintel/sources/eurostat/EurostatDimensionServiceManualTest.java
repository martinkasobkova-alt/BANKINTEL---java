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
     * Česko formálně "existuje" (Eurostat vrátí neprázdnou odpověď), ale všechny hodnoty jsou
     * nula - appka to dřív ukázala jako platnou výchozí kombinaci. combinationHasData teď musí
     * tenhle konkrétní, dřív rozbitý případ odmítnout.
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
}
