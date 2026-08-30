package cz.bankintel.connector.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import cz.bankintel.connector.health.ConnectorHealthService.ProbeTarget;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Hermetic: the probe runs against a throwaway loopback server, never the real upstreams, so the
 * test does not go red when ČNB or Eurostat has a bad day.
 */
class ConnectorHealthServiceTest {

    private static final String KEYED_SOURCE_ENV = "CONNECTOR_HEALTH_TEST_API_KEY";

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200));
        // Data APIs routinely answer a bare base URL with 4xx; that still proves they are alive.
        server.createContext("/not-found", exchange -> respond(exchange, 404));
        server.createContext("/broken", exchange -> respond(exchange, 503));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        System.clearProperty(KEYED_SOURCE_ENV);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    @Test
    void reportsUnknownBeforeTheFirstProbe() {
        ConnectorHealthService service =
                new ConnectorHealthService(List.of(new ProbeTarget("ok", "OK", baseUrl + "/ok", null)));

        Map<String, Object> snapshot = service.snapshot();

        assertThat(snapshot).containsEntry("status", "unknown");
        assertThat(snapshot).containsEntry("checked_at", null);
        assertThat(snapshot).containsEntry("total", 1);
        assertThat((List<?>) snapshot.get("sources")).isEmpty();
    }

    @Test
    void treatsAnyNon5xxResponseAsReachable() {
        ConnectorHealthService service = new ConnectorHealthService(List.of(
                new ProbeTarget("ok", "OK", baseUrl + "/ok", null),
                new ProbeTarget("bare-base-url", "Bare base URL", baseUrl + "/not-found", null)));

        Map<String, Object> snapshot = service.probeAll();

        assertThat(snapshot).containsEntry("status", "ok");
        assertThat(snapshot).containsEntry("up", 2L);
        assertThat(snapshot).containsEntry("down", 0L);
        assertThat(snapshot.get("checked_at")).isNotNull();
    }

    @Test
    void marksServerErrorsAndUnreachableHostsAsDown() {
        ConnectorHealthService service = new ConnectorHealthService(List.of(
                new ProbeTarget("ok", "OK", baseUrl + "/ok", null),
                new ProbeTarget("broken", "Broken", baseUrl + "/broken", null),
                // Port 1 on loopback: nothing listens, so this fails at connect.
                new ProbeTarget("unreachable", "Unreachable", "http://127.0.0.1:1/nowhere", null)));

        Map<String, Object> snapshot = service.probeAll();

        assertThat(snapshot).containsEntry("status", "degraded");
        assertThat(snapshot).containsEntry("up", 1L);
        assertThat(snapshot).containsEntry("down", 2L);

        Map<String, Object> broken = sourceEntry(snapshot, "broken");
        assertThat(broken).containsEntry("status", "down");
        assertThat(broken).containsEntry("http_status", 503);
        assertThat(broken.get("detail")).asString().contains("503");

        Map<String, Object> unreachable = sourceEntry(snapshot, "unreachable");
        assertThat(unreachable).containsEntry("status", "down");
        assertThat(unreachable).containsEntry("http_status", null);
        assertThat(unreachable.get("detail")).isNotNull();
    }

    @Test
    void reportsEverythingDownAsDownRatherThanDegraded() {
        ConnectorHealthService service = new ConnectorHealthService(
                List.of(new ProbeTarget("broken", "Broken", baseUrl + "/broken", null)));

        assertThat(service.probeAll()).containsEntry("status", "down");
    }

    @Test
    void separatesAMissingApiKeyFromAnUpstreamOutage() {
        ConnectorHealthService service = new ConnectorHealthService(List.of(
                new ProbeTarget("keyed", "Keyed source", baseUrl + "/ok", KEYED_SOURCE_ENV)));

        Map<String, Object> snapshot = service.probeAll();

        assertThat(snapshot).containsEntry("misconfigured", 1L);
        // A missing key must not count as DOWN, otherwise an unset key looks like an outage.
        assertThat(snapshot).containsEntry("down", 0L);
        assertThat(snapshot).containsEntry("status", "ok");

        Map<String, Object> keyed = sourceEntry(snapshot, "keyed");
        assertThat(keyed).containsEntry("status", "misconfigured");
        assertThat(keyed.get("detail")).asString().contains(KEYED_SOURCE_ENV);
    }

    @Test
    void probesTheKeyedSourceOnceTheKeyIsPresent() {
        System.setProperty(KEYED_SOURCE_ENV, "test-key");
        ConnectorHealthService service = new ConnectorHealthService(
                List.of(new ProbeTarget("keyed", "Keyed source", baseUrl + "/ok", KEYED_SOURCE_ENV)));

        Map<String, Object> snapshot = service.probeAll();

        assertThat(snapshot).containsEntry("up", 1L);
        assertThat(snapshot).containsEntry("misconfigured", 0L);
        assertThat(sourceEntry(snapshot, "keyed")).containsEntry("http_status", 200);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sourceEntry(Map<String, Object> snapshot, String sourceType) {
        return ((List<Map<String, Object>>) snapshot.get("sources"))
                .stream()
                        .filter(entry -> sourceType.equals(entry.get("source_type")))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no probe result for " + sourceType));
    }
}
