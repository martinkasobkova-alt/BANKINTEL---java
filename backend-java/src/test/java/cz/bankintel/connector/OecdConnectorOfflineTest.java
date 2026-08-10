package cz.bankintel.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cz.bankintel.sources.oecd4.Oecd4BrowseService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OecdConnectorOfflineTest {

    @Mock
    private ConnectorHttpSupport http;

    @Mock
    private Oecd4BrowseService oecd4BrowseService;

    @Test
    void fetch_usesOecd4OfflineRowsWithoutHttp() {
        OecdConnector connector = new OecdConnector(http, oecd4BrowseService);
        when(oecd4BrowseService.previewRows(anyMap()))
                .thenReturn(List.of(Map.of("TIME_PERIOD", "2024", "value", 120.5, "REF_AREA", "CZE")));

        ConnectorFetchResult result = connector.fetch(Map.of(
                "source_type",
                "oecd",
                "set_id",
                "housing_prices/CZE/RHP/_/A",
                "oecd4_offline",
                true,
                "query_params",
                Map.of(
                        "provider",
                        "oecd4",
                        "oecd4_key",
                        "housing_prices",
                        "oecd4_ref_area",
                        "CZE",
                        "oecd4_measure",
                        "RHP",
                        "freq",
                        "A")));

        assertTrue(result.isSuccess());
        List<Map<String, Object>> rows = connector.parse(result.raw(), result.sourceMeta());
        assertEquals(1, rows.size());
        assertEquals("2024", rows.getFirst().get("TIME_PERIOD"));
        verify(oecd4BrowseService).previewRows(anyMap());
        verifyNoInteractions(http);
    }
}
