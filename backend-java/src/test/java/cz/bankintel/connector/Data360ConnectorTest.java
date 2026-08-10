package cz.bankintel.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Data360 error classification audit (2026-07-31): the connector used to catch every failure mode
 * (timeout, DNS/TLS/connection error, HTTP 4xx/5xx, malformed JSON) in one generic block, always
 * returning status 0 and "network or timeout" - indistinguishable from a genuine upstream outage.
 * These tests pin down the new classification for each of the 10 required scenarios.
 */
class Data360ConnectorTest {

    private static final Map<String, Object> SOURCE = Map.of(
            "base_url", "https://data360api.worldbank.org",
            "endpoint", "/data360/data",
            "query_params", Map.of("database_id", "IMF_FSI", "indicator", "IMF_FSI_FSREIC"));

    @Test
    void http400IsClassifiedAsStructuralNonRetryable() throws Exception {
        ConnectorFetchResult result = fetchWithStatusAndBody(400, Map.of("error", "bad request"));

        assertEquals(400, result.httpStatus());
        Map<String, Object> raw = rawMap(result);
        assertEquals(Data360ErrorClassifier.HTTP_4XX, raw.get("error_category"));
        assertEquals(false, raw.get("retryable"));
        assertEquals(400, raw.get("http_status"));
    }

    @Test
    void http404IsClassifiedAsStructuralNonRetryable() throws Exception {
        ConnectorFetchResult result = fetchWithStatusAndBody(404, Map.of("error", "not found"));

        assertEquals(404, result.httpStatus());
        Map<String, Object> raw = rawMap(result);
        assertEquals(Data360ErrorClassifier.HTTP_4XX, raw.get("error_category"));
        assertEquals(false, raw.get("retryable"));
    }

    @Test
    void http417WithUpstreamEnvelopeIsClassifiedAsUpstreamApplicationError() throws Exception {
        // The confirmed live shape of the World Bank Data360 IMF_FSI outage (2026-07-31): HTTP 417
        // with a JSON error envelope naming its own code/message, not a generic transport failure.
        ConnectorFetchResult result =
                fetchWithStatusAndBody(417, Map.of("code", "EA500001", "message", "Data retrieval failed."));

        assertEquals(417, result.httpStatus());
        Map<String, Object> raw = rawMap(result);
        assertEquals(Data360ErrorClassifier.UPSTREAM_APPLICATION_ERROR, raw.get("error_category"));
        assertEquals(true, raw.get("retryable"));
        assertEquals("EA500001", raw.get("upstream_code"));
        assertEquals("Data retrieval failed.", raw.get("upstream_message"));
        assertFalse(String.valueOf(raw.get("detail_cs")).contains("síť nebo timeout"),
                "must no longer show the generic network/timeout message for a real upstream error");
    }

    @Test
    void http429IsClassifiedAsRateLimitedAndRetryable() throws Exception {
        ConnectorFetchResult result = fetchWithStatusAndBody(429, Map.of("error", "too many requests"));

        assertEquals(429, result.httpStatus());
        Map<String, Object> raw = rawMap(result);
        assertEquals(Data360ErrorClassifier.RATE_LIMITED, raw.get("error_category"));
        assertEquals(true, raw.get("retryable"));
    }

    @Test
    void http500IsClassifiedAsRetryableServerError() throws Exception {
        ConnectorFetchResult result = fetchWithStatusAndBody(500, Map.of("error", "internal server error"));

        assertEquals(500, result.httpStatus());
        Map<String, Object> raw = rawMap(result);
        assertEquals(Data360ErrorClassifier.HTTP_5XX, raw.get("error_category"));
        assertEquals(true, raw.get("retryable"));
    }

    @Test
    void connectTimeoutIsClassifiedDistinctlyFromReadTimeout() throws Exception {
        ConnectorHttpSupport http = mock(ConnectorHttpSupport.class);
        when(http.get(anyString(), anyMap(), anyMap(), any(Duration.class)))
                .thenThrow(new HttpConnectTimeoutException("connect timed out"));

        ConnectorFetchResult result = new Data360Connector(http).fetch(SOURCE);

        Map<String, Object> raw = rawMap(result);
        assertEquals(Data360ErrorClassifier.CONNECT_TIMEOUT, raw.get("error_category"));
        assertEquals(true, raw.get("retryable"));
        assertEquals(0, result.httpStatus());
    }

    @Test
    void readTimeoutIsClassifiedAsReadTimeoutNotConnectTimeout() throws Exception {
        ConnectorHttpSupport http = mock(ConnectorHttpSupport.class);
        when(http.get(anyString(), anyMap(), anyMap(), any(Duration.class)))
                .thenThrow(new HttpTimeoutException("response timed out"));

        ConnectorFetchResult result = new Data360Connector(http).fetch(SOURCE);

        Map<String, Object> raw = rawMap(result);
        assertEquals(Data360ErrorClassifier.READ_TIMEOUT, raw.get("error_category"));
        assertEquals(true, raw.get("retryable"));
    }

    @Test
    void connectionResetIsClassifiedAsConnectionFailure() throws Exception {
        ConnectorHttpSupport http = mock(ConnectorHttpSupport.class);
        when(http.get(anyString(), anyMap(), anyMap(), any(Duration.class)))
                .thenThrow(new IOException("Connection reset by peer"));

        ConnectorFetchResult result = new Data360Connector(http).fetch(SOURCE);

        Map<String, Object> raw = rawMap(result);
        assertEquals(Data360ErrorClassifier.CONNECTION_FAILURE, raw.get("error_category"));
        assertEquals(true, raw.get("retryable"));
    }

    @Test
    void malformedJsonOnASuccessfulStatusIsClassifiedAsParserErrorNotNetworkFailure() throws Exception {
        ConnectorHttpSupport http = mock(ConnectorHttpSupport.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("not actually json {{{");
        when(http.get(anyString(), anyMap(), anyMap(), any(Duration.class))).thenReturn(response);
        JsonProcessingException parseFailure = new JsonParseException(null, "Unexpected character");
        when(http.parseJson("not actually json {{{")).thenThrow(parseFailure);

        ConnectorFetchResult result = new Data360Connector(http).fetch(SOURCE);

        Map<String, Object> raw = rawMap(result);
        assertEquals(Data360ErrorClassifier.PARSER_ERROR, raw.get("error_category"));
        assertEquals(false, raw.get("retryable"));
    }

    @Test
    void successfulResponseParsesNormally() throws Exception {
        ConnectorHttpSupport http = mock(ConnectorHttpSupport.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"value\":[]}");
        when(http.get(anyString(), anyMap(), anyMap(), any(Duration.class))).thenReturn(response);
        when(http.parseJson("{\"value\":[]}")).thenReturn(Map.of("value", java.util.List.of()));

        ConnectorFetchResult result = new Data360Connector(http).fetch(SOURCE);

        assertTrue(result.isSuccess());
        assertEquals(200, result.httpStatus());
    }

    @Test
    void missingDatabaseIdIsStructural4xxWithoutAnyHttpCall() {
        ConnectorHttpSupport http = mock(ConnectorHttpSupport.class);

        ConnectorFetchResult result = new Data360Connector(http).fetch(Map.of("query_params", Map.of()));

        assertEquals(400, result.httpStatus());
        Map<String, Object> raw = rawMap(result);
        assertEquals(Data360ErrorClassifier.HTTP_4XX, raw.get("error_category"));
        assertEquals(false, raw.get("retryable"));
    }

    private static ConnectorFetchResult fetchWithStatusAndBody(int status, Map<String, Object> body) throws Exception {
        ConnectorHttpSupport http = mock(ConnectorHttpSupport.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        String bodyJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        when(response.body()).thenReturn(bodyJson);
        when(http.get(anyString(), anyMap(), anyMap(), any(Duration.class))).thenReturn(response);
        when(http.parseJson(bodyJson)).thenReturn(body);

        return new Data360Connector(http).fetch(SOURCE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rawMap(ConnectorFetchResult result) {
        return (Map<String, Object>) result.raw();
    }
}
