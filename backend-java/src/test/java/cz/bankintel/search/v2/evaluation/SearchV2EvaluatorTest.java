package cz.bankintel.search.v2.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogDeepSearchService;
import cz.bankintel.search.v2.orchestration.SearchV2Service;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SearchV2EvaluatorTest {

    @Test
    @SuppressWarnings("unchecked")
    void metadataOnlyEvalPassesPreviewSkippingModeToBothEngines() {
        SearchV2GoldQueries goldQueries = mock(SearchV2GoldQueries.class);
        SearchV2Service searchV2Service = mock(SearchV2Service.class);
        CatalogDeepSearchService deepSearchService = mock(CatalogDeepSearchService.class);
        when(goldQueries.load()).thenReturn(List.of(gold()));
        when(searchV2Service.search(any())).thenReturn(result("v2"));
        when(deepSearchService.deepSearch(any())).thenReturn(result("v1"));

        SearchV2Evaluator evaluator =
                new SearchV2Evaluator(goldQueries, searchV2Service, deepSearchService, new ObjectMapper());
        evaluator.evaluate(Map.of("max", 1, "mode", "metadata_only", "use_ai", false, "write_artifacts", false));

        ArgumentCaptor<Map<String, Object>> v2Payload = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> v1Payload = ArgumentCaptor.forClass(Map.class);
        verify(searchV2Service).search(v2Payload.capture());
        verify(deepSearchService).deepSearch(v1Payload.capture());

        assertThat(v2Payload.getValue()).containsEntry("eval_mode", "metadata_only");
        assertThat(v1Payload.getValue()).containsEntry("eval_mode", "metadata_only");
        assertThat(v1Payload.getValue()).containsEntry("metadata_only", true);
    }

    @Test
    void globalDimensionedTableIsNotCountedAsWrongCountry() {
        SearchV2GoldQueries goldQueries = mock(SearchV2GoldQueries.class);
        SearchV2Service searchV2Service = mock(SearchV2Service.class);
        CatalogDeepSearchService deepSearchService = mock(CatalogDeepSearchService.class);
        SearchV2GoldQuery bankProfit = new SearchV2GoldQuery(
                "bank-profit",
                "zisk bank v Cesku",
                "find_series",
                List.of("bank profit", "net income"),
                List.of("data360"),
                "",
                List.of("CZ"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("balance sheet"),
                false,
                false,
                List.of("primary"),
                "primary",
                "",
                List.of());
        when(goldQueries.load()).thenReturn(List.of(bankProfit));
        when(searchV2Service.search(any())).thenReturn(Map.of(
                "status", "ok",
                "preview_mode", "metadata_only",
                "timings", Map.of(),
                "results", List.of(Map.of(
                        "source", "data360",
                        "series_id", "bank-net-income",
                        "title", "Net income of banks",
                        "geo", "GLOBAL"))));
        when(deepSearchService.deepSearch(any())).thenReturn(Map.of("results", List.of()));

        SearchV2Evaluator evaluator =
                new SearchV2Evaluator(goldQueries, searchV2Service, deepSearchService, new ObjectMapper());
        Map<String, Object> report = evaluator.evaluate(Map.of(
                "max", 1,
                "mode", "metadata_only",
                "use_ai", false,
                "skip_v1", true,
                "write_artifacts", false));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) ((Map<String, Object>) report.get("summary")).get("v2");
        assertThat(summary).containsEntry("precision_at_5", 0.2);
        assertThat(((Number) summary.get("empty_results")).longValue()).isZero();
    }

    private static SearchV2GoldQuery gold() {
        return new SearchV2GoldQuery(
                "q1",
                "inflace CR",
                "find_series",
                List.of("inflation"),
                List.of("eurostat"),
                "",
                List.of("CZ"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("consumer prices"),
                List.of(),
                false,
                false,
                List.of("primary"),
                "primary",
                "",
                List.of());
    }

    private static Map<String, Object> result(String engine) {
        return Map.of(
                "status", "ok",
                "preview_mode", "metadata_only",
                "timings", Map.of("planner_ms", 1, "fts_ms", 2, "reranker_ms", 3, "preview_verification_ms", 0),
                "results", List.of(Map.of(
                        "source", "eurostat",
                        "series_id", engine + "-series",
                        "title", "Inflation consumer prices",
                        "geo", "CZ")));
    }
}
