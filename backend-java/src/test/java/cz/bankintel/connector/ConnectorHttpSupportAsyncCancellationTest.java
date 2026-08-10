package cz.bankintel.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Proves, against a REAL local HTTP server (not a mock), that
 * {@link ConnectorHttpSupport#getAsync} returns a future whose cancellation genuinely aborts the
 * network exchange — the specific property the PR-2 investigation found missing from the
 * synchronous {@code httpClient.send(...)} path.
 */
class ConnectorHttpSupportAsyncCancellationTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void cancellingTheAsyncFutureAbortsTheRealConnectionBeforeTheServerFinishesResponding() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch clientCancelled = new CountDownLatch(1);
        CountDownLatch serverHandlerDone = new CountDownLatch(1);
        AtomicBoolean serverObservedBrokenConnection = new AtomicBoolean(false);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            requestReceived.countDown();
            try {
                // Wait for the test to cancel the client-side future before we ever try to respond.
                clientCancelled.await(5, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("ok".getBytes());
                exchange.getResponseBody().flush();
            } catch (Exception ex) {
                // Writing to a socket the client already tore down surfaces as an IOException here -
                // direct, server-observed proof that cancellation reached the real network layer,
                // not just JVM-side future bookkeeping.
                serverObservedBrokenConnection.set(true);
            } finally {
                exchange.close();
                serverHandlerDone.countDown();
            }
        });
        server.start();

        ConnectorHttpSupport http = new ConnectorHttpSupport(new ObjectMapper());
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";

        CompletableFuture<HttpResponse<String>> future = http.getAsync(url, Map.of(), Map.of(), Duration.ofSeconds(30));
        assertThat(requestReceived.await(5, TimeUnit.SECONDS)).as("server should have received the request").isTrue();

        boolean cancelled = future.cancel(true);
        clientCancelled.countDown();

        assertThat(cancelled).as("cancel() must successfully transition the pending future").isTrue();
        assertThat(future.isDone()).as("the future must not be left hanging after cancellation").isTrue();
        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .as("awaiting a cancelled request must never silently return a stale success value")
                .satisfies(thrown -> {
                    // JDK-internal race, not something this codebase controls: HttpClient.sendAsync's
                    // returned future propagates cancellation through several internal dependent
                    // stages (connection -> headers -> body), and depending on exactly which stage
                    // observes the cancellation first, get() surfaces either a bare
                    // CancellationException (the plain CompletableFuture.get() contract for an
                    // already-cancelled future) or an ExecutionException wrapping one (the ordinary
                    // "completed exceptionally" contract, taken when an internal dependent stage
                    // completes the public future exceptionally rather than the cancel() bit winning
                    // directly). Both are equally valid proof that the awaited value is not a stale
                    // success - this assertion checks the root cause, not the wrapper shape. Production
                    // code never calls get() on this future (only whenComplete/handle, which always
                    // receive the raw cause regardless of this wrapping), so this ambiguity has no
                    // effect outside this specific test's own measurement technique.
                    Throwable rootCause = thrown instanceof java.util.concurrent.ExecutionException && thrown.getCause() != null
                            ? thrown.getCause()
                            : thrown;
                    assertThat(rootCause).isInstanceOf(java.util.concurrent.CancellationException.class);
                });

        assertThat(serverHandlerDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(serverObservedBrokenConnection)
                .as("the server side must observe the connection was actually torn down, proving this "
                        + "is real socket-level cancellation and not merely a JVM-local future trick")
                .isTrue();
    }

    @Test
    void uncancelledAsyncRequestStillCompletesNormally() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fast", exchange -> {
            byte[] body = "hello".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        ConnectorHttpSupport http = new ConnectorHttpSupport(new ObjectMapper());
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/fast";

        HttpResponse<String> response =
                http.getAsync(url, Map.of(), Map.of(), Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("hello");
    }
}
