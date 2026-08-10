package cz.bankintel.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.openai.OpenAiJsonSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogSeriesExplainServiceTest {

    private final OpenAiClient openAiClient = mock(OpenAiClient.class);
    private final OpenAiJsonSupport openAiJsonSupport = mock(OpenAiJsonSupport.class);
    private final CatalogSeriesExplainService service =
            new CatalogSeriesExplainService(openAiClient, openAiJsonSupport);

    @Test
    void returnsClientContractWithMeaningfulHeuristicExplanation() {
        when(openAiClient.isConfigured()).thenReturn(false);

        Map<String, Object> result = service.explainSeries(Map.of(
                "title", "Rentabilita vlastního kapitálu bank",
                "source_type", "eurostat",
                "set_id", "tipsbd40"));

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("ai_used", false)
                .containsEntry("source", "heuristic")
                .containsEntry("fallback_reason", "openai_unavailable");
        assertThat(result.get("explanation_cz"))
                .asString()
                .containsIgnoringCase("ziskovost")
                .doesNotContain("preview verified");
    }

    @Test
    void foldsCurrentChartReadingIntoHeuristicWhenContractProvided() {
        when(openAiClient.isConfigured()).thenReturn(false);

        Map<String, Object> contract = Map.of(
                "series", List.of(Map.of("id", "main", "label", "Míra inflace", "unit", "%")),
                "data", List.of(
                        Map.of("series_id", "main", "period", "2023", "value_raw", 10.7, "unit", "%"),
                        Map.of("series_id", "main", "period", "2024", "value_raw", 2.4, "unit", "%")));

        Map<String, Object> result = service.explainSeries(Map.of(
                "title", "Míra inflace",
                "source_type", "csu",
                "chart_contract", contract));

        // I bez AI musí vysvětlení mluvit o skutečném grafu (poslední hodnota + směr), ne jen definici.
        assertThat(result.get("explanation_cz"))
                .asString()
                .contains("Aktuální graf")
                .contains("2.40")
                .containsIgnoringCase("klesla");
    }

    @Test
    void usesClientChartSummaryWhenNoFullContract() {
        when(openAiClient.isConfigured()).thenReturn(false);

        // Explorer/widget ✨ posílá kompaktní chart_summary místo celého kontraktu.
        Map<String, Object> summary = Map.of(
                "first_period", "2020", "first_value", 3.2,
                "last_period", "2024", "last_value", 2.4,
                "min_value", 2.4, "min_period", "2024",
                "max_value", 15.1, "max_period", "2022",
                "n_points", 5);

        Map<String, Object> result = service.explainSeries(Map.of(
                "title", "Míra inflace",
                "source_type", "csu",
                "chart_summary", summary));

        assertThat(result.get("explanation_cz"))
                .asString()
                .contains("Aktuální graf")
                .contains("2.40")
                .containsIgnoringCase("klesla");
    }

    @Test
    void ignoresPrivateContractForDataReading() {
        when(openAiClient.isConfigured()).thenReturn(false);

        Map<String, Object> contract = Map.of(
                "metadata", Map.of("contains_private_series", true),
                "series", List.of(Map.of("id", "main", "label", "Nahraná řada", "unit", "%")),
                "data", List.of(
                        Map.of("series_id", "main", "period", "2023", "value_raw", 10.7),
                        Map.of("series_id", "main", "period", "2024", "value_raw", 2.4)));

        Map<String, Object> result = service.explainSeries(Map.of(
                "title", "Míra inflace",
                "source_type", "csu",
                "chart_contract", contract));

        // Privátní/nahraná data se do promptu ani do textu nikdy nepromítnou.
        assertThat(result.get("explanation_cz")).asString().doesNotContain("Aktuální graf");
    }
}
