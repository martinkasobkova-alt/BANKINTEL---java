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
     * naio_10_pyp1620 pro Česko dřív dorazilo na ind_use=T/cpa2_1=CPA_T (prázdná data). Po
     * kroku 2 plánu (preferovaný kód G45 + zpřísněné ověření z kroku 1) musí cascade dojít
     * k výběru se skutečnými daty.
     *
     * <p>Živě ověřeno (2026-09-05), že KTERÁ konkrétní reálná kombinace to bude, se může
     * lišit běh od běhu - cascade je omezený, souběžný live-probe proti Eurostatu (viz
     * {@code filterOptionsByLiveProbe}), takže na jednom běhu vyhraje jiný kandidát než na
     * jiném podle toho, jak rychle Eurostat odpoví. Appka to jednou dokonce sama nechala
     * doběhnout zpátky na ind_use=T - ale spárované s jiným, skutečně naplněným cpa2_1 (ne
     * CPA_T), takže to NENÍ ta stará chyba: {@code combinationHasData} přesně tenhle výběr
     * před vrácením ověřuje, takže vrácená kombinace má vždy nenulová data bez ohledu na to,
     * který konkrétní kód nakonec vyhraje. Testujeme tedy jen to, co appka doopravdy garantuje:
     * (1) není to znovu přesně ten starý prázdný pár T/CPA_T, (2) výsledek má opravdu data.
     */
    @Test
    void resolvePreviewQueryParams_liveNetwork_naio10Pyp1620NoLongerDefaultsToTheEmptyTCombination() {
        EurostatDimensionService service = new EurostatDimensionService(new ObjectMapper(), new EurostatRateLimiter());

        Map<String, Object> qp = service.resolvePreviewQueryParams("naio_10_pyp1620", "CZ");

        assertFalse(qp.isEmpty(), "resolved params: " + qp);
        boolean isOldBrokenPair =
                "T".equals(String.valueOf(qp.get("ind_use"))) && "CPA_T".equals(String.valueOf(qp.get("cpa2_1")));
        assertThat(isOldBrokenPair).as("must not resolve back to the old empty T/CPA_T pair: " + qp).isFalse();
        Map<String, String> selected = new java.util.LinkedHashMap<>();
        qp.forEach((k, v) -> selected.put(k, String.valueOf(v)));
        assertThat(service.combinationHasData("naio_10_pyp1620", selected))
                .as("resolved combination must have real data: " + qp)
                .isTrue();
    }
}
