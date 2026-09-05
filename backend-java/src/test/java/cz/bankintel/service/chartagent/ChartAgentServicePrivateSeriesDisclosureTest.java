package cz.bankintel.service.chartagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.bankintel.service.research.WebResearchService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Odpověď o soukromých řadách se nesmí mlčky vytratit.
 *
 * Kontext: v grafu se soukromými/nahranými řadami volá ekonomista jen nad veřejnými řadami
 * (soukromá data se do promptu pro OpenAI nikdy nedostanou), ale ta LLM odpověď bezpodmínečně
 * přepsala i deterministický návrh, který mohl soukromé řady pokrývat. Uživatel neměl žádný
 * signál, že text nad odpovědí o části grafu mlčí. Cedulka `methodology_cz` teď explicitně říká,
 * že soukromé řady ve znění odpovědi nejsou, místo jen "zůstaly na backendu" (což zní jako
 * ujištění, ne jako varování).
 */
class ChartAgentServicePrivateSeriesDisclosureTest {

    private static Map<String, Object> contractWithOnePrivateSeries() {
        return Map.of(
                "title", "Test",
                "series", List.of(
                        Map.of("id", "main", "label", "Veřejná řada"),
                        Map.of("id", "secret", "label", "Tajná řada", "privacy", "private")),
                "data", List.of(
                        Map.of("series_id", "main", "period", "2024", "value_raw", 100.0),
                        Map.of("series_id", "secret", "period", "2024", "value_raw", 999.0)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void metodologieVyslovneRekneZeSoukromaRadaNeniVOdpovedi() {
        ChartAgentEconomistService economist = mock(ChartAgentEconomistService.class);
        ChartAgentPlanner planner = mock(ChartAgentPlanner.class);
        when(planner.planChartQuestion(anyString(), anyMap(), anyList(), anyList()))
                .thenReturn(Map.of("operations", List.of("summary"), "warnings", List.of()));
        when(economist.economistAnswer(
                        anyString(), anyMap(), anyList(), anyList(), anyMap(), anyString(), anyList(), anyMap()))
                .thenReturn("Veřejná řada v roce 2024 dosáhla 100.");
        ChartAgentService service = new ChartAgentService(economist, planner, mock(WebResearchService.class));

        Map<String, Object> response = service.analyzeChartQuestion(
                Map.of("question", "Jak se vyvíjí graf?", "chart_contract", contractWithOnePrivateSeries()));

        String methodology = (String) response.get("methodology_cz");
        assertThat(methodology)
                .contains("1 privátní")
                .contains("se do znění odpovědi výše vůbec nedostala")
                .doesNotContain("zůstaly pro výpočty na backendu");
    }
}
