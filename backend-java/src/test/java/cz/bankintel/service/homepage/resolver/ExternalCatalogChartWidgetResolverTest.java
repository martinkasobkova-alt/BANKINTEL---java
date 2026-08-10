package cz.bankintel.service.homepage.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.bankintel.search.CatalogPreviewService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExternalCatalogChartWidgetResolverTest {

    private CatalogPreviewService catalogPreviewService;
    private DatasetViewResolver datasetViewResolver;
    private ExternalCatalogChartWidgetResolver resolver;

    @BeforeEach
    void setUp() {
        catalogPreviewService = mock(CatalogPreviewService.class);
        datasetViewResolver = mock(DatasetViewResolver.class);
        resolver = new ExternalCatalogChartWidgetResolver(catalogPreviewService, datasetViewResolver);
    }

    @Test
    void mergesCrossCatalogSeriesWithoutDroppingPrimaryDimensionSeries() {
        when(catalogPreviewService.preview(any())).thenAnswer(invocation -> {
            Map<String, Object> body = invocation.getArgument(0);
            String setId = String.valueOf(body.get("set_id"));
            if ("tipsbd40".equals(setId)) {
                return Map.of("rows", List.of(Map.of("date", "2024", "value", 1)));
            }
            return Map.of("rows", List.of(Map.of("date", "2024", "value", 25)));
        });
        when(datasetViewResolver.resolveFromRows(any(), any(), any(), any())).thenAnswer(invocation -> {
            Map<String, Object> cfg = invocation.getArgument(1);
            if ("tipsbd40".equals(String.valueOf(cfg.get("set_id")))) {
                Map<String, Object> primary = new LinkedHashMap<>();
                primary.put("title", "Return on equity of banks");
                primary.put("multi_series", true);
                primary.put("rows", List.of());
                primary.put("series", List.of(
                        Map.of("key", "AT", "label", "Austria", "rows", List.of(Map.of("x", "2024", "y", 1))),
                        Map.of("key", "NO", "label", "Norway", "rows", List.of(Map.of("x", "2024", "y", 2)))));
                return primary;
            }
            return new LinkedHashMap<>(Map.of(
                    "title", "EUR/USD",
                    "rows", List.of(Map.of("x", "2024", "y", 25), Map.of("x", "2025", "y", 26))));
        });

        Map<String, Object> primarySnapshot = new LinkedHashMap<>();
        primarySnapshot.put("title", "Return on equity of banks");
        primarySnapshot.put("multi_series", true);
        primarySnapshot.put("rows", List.of(Map.of("period", "2024", "AT", 1, "NO", 2)));
        primarySnapshot.put("series", List.of(
                Map.of("key", "AT", "label", "Austria"),
                Map.of("key", "NO", "label", "Norway")));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("catalog", "eurostat");
        config.put("set_id", "tipsbd40");
        config.put("chart_primary_snapshot", primarySnapshot);
        config.put("chart_compare_with", List.of(Map.of(
                "catalog", "ecb2",
                "set_id", "EXR/M.USD.EUR.SP00.A",
                "name", "EUR/USD")));

        Map<String, Object> result = resolver.resolve(config, null);

        assertThat(result.get("compare_requested_count")).isEqualTo(1);
        assertThat(result.get("compare_added_count")).isEqualTo(1);
        assertThat(result.get("multi_series")).isEqualTo(true);
        assertThat((List<?>) result.get("series")).hasSize(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        assertThat(rows).containsExactly(
                Map.of("period", "2024", "s0", 1.0, "s1", 2.0, "s2", 25.0),
                Map.of("period", "2025", "s2", 26.0));
    }

    @Test
    void reportsRejectedComparisonInsteadOfPretendingItWasApplied() {
        when(catalogPreviewService.preview(any())).thenAnswer(invocation -> {
            Map<String, Object> body = invocation.getArgument(0);
            return "tipsbd40".equals(String.valueOf(body.get("set_id")))
                    ? Map.of("rows", List.of(Map.of("date", "2024", "value", 1)))
                    : Map.of("error", "Series is unavailable");
        });
        Map<String, Object> primary = new LinkedHashMap<>();
        primary.put("title", "Return on equity of banks");
        primary.put("rows", List.of(Map.of("x", "2024", "y", 1)));
        when(datasetViewResolver.resolveFromRows(any(), any(), any(), any())).thenReturn(primary);

        Map<String, Object> result = resolver.resolve(new LinkedHashMap<>(Map.of(
                "catalog", "eurostat",
                "set_id", "tipsbd40",
                "chart_compare_with", List.of(Map.of(
                        "catalog", "ecb2",
                        "set_id", "missing",
                        "name", "Unavailable")))), null);

        assertThat(result.get("compare_requested_count")).isEqualTo(1);
        assertThat(result.get("compare_added_count")).isEqualTo(0);
        assertThat((List<?>) result.get("compare_errors")).hasSize(1);
        assertThat(result.get("multi_series")).isNull();
    }
}
