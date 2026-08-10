package cz.bankintel.sources.arad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Regression test for the ported {@code GET /api/arad/catalog/live-check} behaviour
 * (arad_catalog_routes.py, ř. 532): admin ping against the live ARAD API without touching the
 * catalog cache.
 */
@ExtendWith(MockitoExtension.class)
class AradCatalogServiceLiveCheckTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<byte[]> httpResponse;

    private AradCatalogService newService() {
        AradCatalogService service = new AradCatalogService(null, new ObjectMapper());
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
        return service;
    }

    @Test
    @SuppressWarnings("unchecked")
    void liveCheckReturnsOkWithLatencyOnHttp200() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("hello".getBytes());

        Map<String, Object> result = newService().liveCheck();

        assertEquals(true, result.get("ok"));
        assertEquals("arad", result.get("source"));
        assertEquals(200, result.get("status"));
        assertEquals(5L, result.get("bytes_read"));
        assertNull(result.get("error"));
        assertNotNull(result.get("elapsed_ms"));
        assertNotNull(result.get("endpoint"));
        assertNotNull(result.get("timeout"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void liveCheckReturnsNotOkAndErrorWhenRequestFails() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection refused"));

        Map<String, Object> result = newService().liveCheck();

        assertEquals(false, result.get("ok"));
        assertNull(result.get("status"));
        assertNotNull(result.get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void liveCheckReturnsNotOkOnNon2xxStatus() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(503);
        when(httpResponse.body()).thenReturn(new byte[0]);

        Map<String, Object> result = newService().liveCheck();

        assertEquals(false, result.get("ok"));
        assertEquals(503, result.get("status"));
        assertNotNull(result.get("error"));
    }
}
