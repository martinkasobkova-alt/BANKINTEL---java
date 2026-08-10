package cz.bankintel.connector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.sources.ecb.EcbAvailabilityService;
import cz.bankintel.sources.ecb.EcbCuratedCatalog;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EcbConnectorTest {

    @Test
    void fetchDoesNotSendInternalRoutingParamsToEcbApi() throws Exception {
        ConnectorHttpSupport http = mock(ConnectorHttpSupport.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body())
                .thenReturn("KEY,TIME_PERIOD,OBS_VALUE\nCBD2.A.U2.W0.11._Z._Z.A.A.I2004._Z._Z._Z._Z._Z._Z.PC,2025,0.67\n");
        when(http.get(anyString(), anyMap(), anyMap(), any(Duration.class))).thenReturn(response);

        String setId = "CBD2/A.U2.W0.11._Z._Z.A.A.I2004._Z._Z._Z._Z._Z._Z.PC";
        Map<String, Object> source = Map.of(
                "base_url", "https://data-api.ecb.europa.eu",
                "set_id", setId,
                "query_params", Map.of(
                        "ecb_flow", "CBD2",
                        "ecb_series_key", setId.substring("CBD2/".length()),
                        "flowRef", "CBD2",
                        "seriesKey", setId.substring("CBD2/".length()),
                        "lastNObservations", "1"));

        ConnectorFetchResult result =
                new EcbConnector(http, mock(EcbCuratedCatalog.class), mock(EcbAvailabilityService.class)).fetch(source);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(http).get(urlCaptor.capture(), anyMap(), anyMap(), any(Duration.class));
        String url = urlCaptor.getValue();
        assertTrue(result.isSuccess());
        assertTrue(url.contains("/service/data/CBD2/A.U2.W0.11._Z._Z.A.A.I2004._Z._Z._Z._Z._Z._Z.PC?"), url);
        assertTrue(url.contains("lastNObservations=1"), url);
        assertFalse(url.contains("ecb_flow"), url);
        assertFalse(url.contains("ecb_series_key"), url);
        assertFalse(url.contains("flowRef"), url);
        assertFalse(url.contains("seriesKey"), url);
    }
}
