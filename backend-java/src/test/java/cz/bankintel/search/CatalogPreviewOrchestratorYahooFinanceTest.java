package cz.bankintel.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.connector.ConnectorFactory;
import cz.bankintel.connector.InMemorySourceBuilder;
import cz.bankintel.sources.arad.AradSetIndicatorsService;
import cz.bankintel.sources.yahoo.YahooFinanceCatalogService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the yahoo_finance preview-wiring fix (2026-07-31): previously every yahoo_finance
 * candidate fell through to {@code buildIndexFallback}'s "not implemented" message because no
 * {@link cz.bankintel.connector.BaseConnector} is registered for it in {@link ConnectorFactory}.
 * {@link YahooFinanceCatalogService} already fetches/parses real OHLCV data - it was just never
 * wired into {@link CatalogPreviewOrchestrator#preview}.
 */
class CatalogPreviewOrchestratorYahooFinanceTest {

    private InMemorySourceBuilder sourceBuilder;
    private ConnectorFactory connectorFactory;
    private CatalogIndexStore indexStore;
    private AradSetIndicatorsService aradSetIndicatorsService;
    private YahooFinanceCatalogService yahooFinanceCatalogService;
    private CatalogPreviewOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        sourceBuilder = mock(InMemorySourceBuilder.class);
        connectorFactory = mock(ConnectorFactory.class);
        indexStore = mock(CatalogIndexStore.class);
        aradSetIndicatorsService = mock(AradSetIndicatorsService.class);
        yahooFinanceCatalogService = mock(YahooFinanceCatalogService.class);
        orchestrator = new CatalogPreviewOrchestrator(
                sourceBuilder, connectorFactory, indexStore, aradSetIndicatorsService, yahooFinanceCatalogService);
        when(indexStore.lookupRow(any(), any())).thenReturn(java.util.Optional.empty());
    }

    private static Map<String, Object> yahooRow(String date, double value) {
        return Map.of("date", date, "value", value, "close", value, "ticker", "AAPL");
    }

    @Test
    void yahooFinancePreviewBypassesConnectorFactoryAndReturnsRealRows() {
        when(yahooFinanceCatalogService.preview(eq("AAPL"), eq("2020-01-01"), isNull(), eq("1d")))
                .thenReturn(Map.of(
                        "ok", true,
                        "source_type", "yahoo_finance",
                        "set_id", "AAPL",
                        "name", "Apple Inc.",
                        "records", List.of(yahooRow("2024-01-02", 185.5), yahooRow("2024-01-03", 186.2))));

        Map<String, Object> response = orchestrator.preview(Map.of(
                "source_type", "yahoo_finance",
                "set_id", "AAPL",
                "query_params", Map.of("ticker", "AAPL", "start_date", "2020-01-01", "interval", "1d")));

        assertThat(response).doesNotContainEntry("preview_state", "unsupported");
        assertThat(response.get("rows")).isInstanceOf(List.class);
        assertThat((List<?>) response.get("rows")).hasSize(2);
        verify(connectorFactory, never()).isSupported(any());
        verify(connectorFactory, never()).get(any());
    }

    @Test
    void missingQueryParamsFallBackToSetIdAsTickerAndDefaultDateInterval() {
        when(yahooFinanceCatalogService.preview(eq("CEZ.PR"), eq("1990-01-01"), isNull(), eq("1d")))
                .thenReturn(Map.of(
                        "ok", true,
                        "source_type", "yahoo_finance",
                        "set_id", "CEZ.PR",
                        "name", "CEZ",
                        "records", List.of(yahooRow("2024-01-02", 950.0))));

        Map<String, Object> response =
                orchestrator.preview(Map.of("source_type", "yahoo_finance", "set_id", "CEZ.PR"));

        assertThat((List<?>) response.get("rows")).hasSize(1);
        verify(yahooFinanceCatalogService).preview("CEZ.PR", "1990-01-01", null, "1d");
    }

    @Test
    void emptyRecordsStillReturnASuccessShapedResponseNotTheUnsupportedFallback() {
        when(yahooFinanceCatalogService.preview(any(), any(), any(), any()))
                .thenReturn(Map.of("ok", true, "source_type", "yahoo_finance", "set_id", "NOPE", "records", List.of()));

        Map<String, Object> response =
                orchestrator.preview(Map.of("source_type", "yahoo_finance", "set_id", "NOPE"));

        assertThat(response.get("preview_state")).isNotEqualTo("unsupported");
        assertThat((List<?>) response.get("rows")).isEmpty();
    }

    @Test
    void fetchRecordsBypassesConnectorFactoryAndReturnsRealRecords() {
        when(yahooFinanceCatalogService.preview(eq("AAPL"), eq("2020-01-01"), isNull(), eq("1d")))
                .thenReturn(Map.of(
                        "ok", true,
                        "source_type", "yahoo_finance",
                        "set_id", "AAPL",
                        "records", List.of(yahooRow("2024-01-02", 185.5), yahooRow("2024-01-03", 186.2))));

        List<Map<String, Object>> records = orchestrator.fetchRecords(Map.of(
                "source_type", "yahoo_finance",
                "set_id", "AAPL",
                "query_params", Map.of("ticker", "AAPL", "start_date", "2020-01-01", "interval", "1d")));

        assertThat(records).hasSize(2);
        verify(connectorFactory, never()).isSupported(any());
        verify(connectorFactory, never()).get(any());
    }

    @Test
    void fetchRecordsNoLongerSilentlyReturnsEmptyForYahooFinance() {
        when(yahooFinanceCatalogService.preview(eq("CEZ.PR"), eq("1990-01-01"), isNull(), eq("1d")))
                .thenReturn(Map.of("ok", true, "records", List.of(yahooRow("2024-01-02", 950.0))));

        List<Map<String, Object>> records =
                orchestrator.fetchRecords(Map.of("source_type", "yahoo_finance", "set_id", "CEZ.PR"));

        assertThat(records)
                .as("this exact silent-empty-list regression fed ForecastDataAssemblerService "
                        + "insufficient_target_data for every stock ticker")
                .hasSize(1);
    }
}
