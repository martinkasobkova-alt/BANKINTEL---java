package cz.bankintel.connector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FredConnectorTest {

    @Test
    void fetchUsesBoundedPreviewLimitAcceptedByFred() throws Exception {
        System.setProperty("FRED_API_KEY", "test-key");
        try {
            ConnectorHttpSupport http = mock(ConnectorHttpSupport.class);
            @SuppressWarnings("unchecked")
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn("{\"observations\":[]}");
            when(http.get(anyString(), anyMap(), anyMap(), any(Duration.class))).thenReturn(response);
            when(http.parseJson(anyString())).thenReturn(Map.of("observations", List.of()));

            ConnectorFetchResult result = new FredConnector(http).fetch(Map.of("set_id", "ACOILBRENTEU"));

            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> queryCaptor = ArgumentCaptor.forClass(Map.class);
            verify(http).get(urlCaptor.capture(), eq(Map.of()), queryCaptor.capture(), any(Duration.class));
            String url = urlCaptor.getValue();
            assertEquals("https://api.stlouisfed.org/fred/series/observations", url);
            Map<String, Object> query = queryCaptor.getValue();
            assertEquals("50000", query.get("limit"));
            assertFalse(query.containsKey("sort_order"));
            assertFalse(String.valueOf(query.get("limit")).contains("?"), query.toString());
            assertTrue(result.isSuccess());
        } finally {
            System.clearProperty("FRED_API_KEY");
        }
    }

    @Test
    void fetchUsesLatestFirstLargeLimitForAnalysisMode() throws Exception {
        System.setProperty("FRED_API_KEY", "test-key");
        try {
            ConnectorHttpSupport http = mock(ConnectorHttpSupport.class);
            @SuppressWarnings("unchecked")
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn("{\"observations\":[]}");
            when(http.get(anyString(), anyMap(), anyMap(), any(Duration.class))).thenReturn(response);
            when(http.parseJson(anyString())).thenReturn(Map.of("observations", List.of()));

            ConnectorFetchResult result = new FredConnector(http).fetch(Map.of(
                    "set_id", "DCOILBRENTEU",
                    "query_params", Map.of(
                            "record_mode", "analysis",
                            "record_limit", "5000",
                            "observation_start", "2000-01-01")));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> queryCaptor = ArgumentCaptor.forClass(Map.class);
            verify(http).get(anyString(), eq(Map.of()), queryCaptor.capture(), any(Duration.class));
            Map<String, Object> query = queryCaptor.getValue();
            assertEquals("5000", query.get("limit"));
            assertEquals("desc", query.get("sort_order"));
            assertEquals("2000-01-01", query.get("observation_start"));
            assertTrue(result.isSuccess());
        } finally {
            System.clearProperty("FRED_API_KEY");
        }
    }
}
