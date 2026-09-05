package cz.bankintel.service.chartagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.service.research.WebResearchService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * requestedPeriodLookup dohledává dotazovaný rok v PLNÉ (ne vzorkované) řadě.
 *
 * Kontext: AI nad grafem dřív dostávala jen vzorkovaný `series_points` (nejvýš 200 bodů na
 * řadu) a pokyn „najdi nejbližší bod" — u dlouhé řady tak mohla nejbližší dostupnou hodnotu
 * vydat za rok, na který se uživatel ptal, aniž by to přiznala. Tahle metoda rok z otázky
 * vytáhne a dohledá ho deterministicky v Javě, takže model dostane hotovou odpověď místo
 * prostoru domýšlet si ji sám.
 */
class ChartAgentServiceRequestedPeriodLookupTest {

    private static Map<String, Object> seriesWithPeriods(String... periodsAndValues) {
        List<Map<String, Object>> points = new java.util.ArrayList<>();
        for (int i = 0; i < periodsAndValues.length; i += 2) {
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("period", periodsAndValues[i]);
            pt.put("value", Double.parseDouble(periodsAndValues[i + 1]));
            points.add(pt);
        }
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("id", "main");
        series.put("points", points);
        return series;
    }

    @Test
    void rokPresneVDatechDaExactMatch() {
        Map<String, Object> primary = seriesWithPeriods("2019", "40", "2020", "45.2", "2021", "50");

        Map<String, Object> out = ChartAgentService.requestedPeriodLookup("Jaká byla hodnota v roce 2020?", primary);

        assertThat(out.get("requested_year")).isEqualTo("2020");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exact = (List<Map<String, Object>>) out.get("exact_matches");
        assertThat(exact).hasSize(1);
        assertThat(exact.getFirst()).containsEntry("period", "2020").containsEntry("value", 45.2);
        assertThat(out).doesNotContainKey("nearest_available");
    }

    @Test
    void mesicniRadaVratiVsechnyBodyPozadovanehoRoku() {
        Map<String, Object> primary = seriesWithPeriods("2020-01", "10", "2020-06", "12", "2021-01", "15");

        Map<String, Object> out = ChartAgentService.requestedPeriodLookup("A co rok 2020?", primary);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exact = (List<Map<String, Object>>) out.get("exact_matches");
        assertThat(exact).hasSize(2);
        assertThat(exact).extracting(m -> m.get("period")).containsExactlyInAnyOrder("2020-01", "2020-06");
    }

    @Test
    void chybejiciRokVratiBlizsiZDvouSousednich() {
        // 2020 v datech chybí; 2015 je vzdálen 5 let, 2023 jen 3 - vyhrává 2023.
        Map<String, Object> primary = seriesWithPeriods("2015", "1", "2023", "2");

        Map<String, Object> out = ChartAgentService.requestedPeriodLookup("Jak to bylo v roce 2020?", primary);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exact = (List<Map<String, Object>>) out.get("exact_matches");
        assertThat(exact).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> nearest = (Map<String, Object>) out.get("nearest_available");
        assertThat(nearest).containsEntry("period", "2023").containsEntry("value", 2.0);
    }

    @Test
    void remizeVyhravaDrivejsiRok() {
        Map<String, Object> primary = seriesWithPeriods("2018", "1", "2022", "2");

        Map<String, Object> out = ChartAgentService.requestedPeriodLookup("rok 2020", primary);

        @SuppressWarnings("unchecked")
        Map<String, Object> nearest = (Map<String, Object>) out.get("nearest_available");
        assertThat(nearest).containsEntry("period", "2018");
    }

    @Test
    void bezRokuVOtazceVraciPrazdnouMapu() {
        Map<String, Object> primary = seriesWithPeriods("2020", "1");
        assertThat(ChartAgentService.requestedPeriodLookup("Jak se vyvíjí řada?", primary)).isEmpty();
    }

    @Test
    void bezPrimarniRadyVraciPrazdnouMapu() {
        assertThat(ChartAgentService.requestedPeriodLookup("rok 2020", null)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dotazSRokemPredaLookupAzKEkonomistovi() {
        ChartAgentEconomistService economist = mock(ChartAgentEconomistService.class);
        ChartAgentPlanner planner = mock(ChartAgentPlanner.class);
        when(planner.planChartQuestion(anyString(), anyMap(), anyList(), anyList()))
                .thenReturn(Map.of("operations", List.of("summary"), "warnings", List.of()));
        when(economist.economistAnswer(
                        anyString(), anyMap(), anyList(), anyList(), anyMap(), anyString(), anyList(), anyMap()))
                .thenReturn("Odpověď.");
        ChartAgentService service = new ChartAgentService(economist, planner, mock(WebResearchService.class));

        service.analyzeChartQuestion(Map.of(
                "question", "Jaká byla hodnota v roce 2020?",
                "chart_contract", Map.of(
                        "title", "Test",
                        "series", List.of(Map.of("id", "main", "label", "Test")),
                        "data", List.of(
                                Map.of("series_id", "main", "period", "2019", "value_raw", 40.0),
                                Map.of("series_id", "main", "period", "2020", "value_raw", 45.2)))));

        ArgumentCaptor<Map<String, Object>> lookupCaptor = ArgumentCaptor.forClass(Map.class);
        verify(economist)
                .economistAnswer(
                        anyString(), anyMap(), anyList(), anyList(), anyMap(), anyString(), anyList(),
                        lookupCaptor.capture());
        Map<String, Object> lookup = lookupCaptor.getValue();
        assertThat(lookup.get("requested_year")).isEqualTo("2020");
        List<Map<String, Object>> exact = (List<Map<String, Object>>) lookup.get("exact_matches");
        assertThat(exact).hasSize(1);
        assertThat(exact.getFirst()).containsEntry("period", "2020").containsEntry("value", 45.2);
    }
}
