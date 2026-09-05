package cz.bankintel.search.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs against a loopback stand-in for Ollama/vLLM. The point is the wire contract: what we send a
 * local server has to differ from the OpenAI payload in a few specific ways, and getting that wrong
 * only shows up during an actual outage — the worst possible time to find out.
 */
class LocalLlmFallbackClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final List<String> receivedBodies = new ArrayList<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = chatResponse("ok", "stop");

    @BeforeEach
    void startServer() throws IOException {
        receivedBodies.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        System.clearProperty("BANKINTEL_LLM_FALLBACK_ENABLED");
        System.clearProperty("BANKINTEL_LLM_FALLBACK_BASE_URL");
        System.clearProperty("BANKINTEL_LLM_FALLBACK_MODEL_CHAT");
    }

    private void handle(HttpExchange exchange) throws IOException {
        receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private static String chatResponse(String content, String finishReason) {
        return """
               {"choices":[{"message":{"content":"%s"},"finish_reason":"%s"}],
                "usage":{"prompt_tokens":11,"completion_tokens":7}}
               """
                .formatted(content, finishReason);
    }

    private LocalLlmFallbackClient configuredClient() {
        System.setProperty("BANKINTEL_LLM_FALLBACK_ENABLED", "1");
        System.setProperty("BANKINTEL_LLM_FALLBACK_BASE_URL", baseUrl);
        System.setProperty("BANKINTEL_LLM_FALLBACK_MODEL_CHAT", "llama3.1");
        return new LocalLlmFallbackClient();
    }

    @Test
    void staysDisabledUntilExplicitlyTurnedOn() {
        assertThat(new LocalLlmFallbackClient().isConfigured()).isFalse();

        System.setProperty("BANKINTEL_LLM_FALLBACK_BASE_URL", baseUrl);
        System.setProperty("BANKINTEL_LLM_FALLBACK_MODEL_CHAT", "llama3.1");
        // Base URL and model alone must not activate it — the flag is the switch.
        assertThat(new LocalLlmFallbackClient().isConfigured()).isFalse();

        System.setProperty("BANKINTEL_LLM_FALLBACK_ENABLED", "1");
        assertThat(new LocalLlmFallbackClient().isConfigured()).isTrue();
    }

    @Test
    void sendsTheDialectLocalServersAcceptRatherThanTheOpenAiOne() throws Exception {
        configuredClient().complete("sys", "user", OpenAiModelTask.CHAT, false, 1234);

        JsonNode sent = MAPPER.readTree(receivedBodies.get(0));
        // Ollama and vLLM take max_tokens; max_completion_tokens is the newer OpenAI-only spelling.
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(1234);
        assertThat(sent.has("max_completion_tokens")).isFalse();
        // Local servers reject unknown fields; reasoning_effort is OpenAI-specific.
        assertThat(sent.has("reasoning_effort")).isFalse();
        assertThat(sent.path("model").asText()).isEqualTo("llama3.1");
    }

    @Test
    void asksForPlainJsonModeWhenJsonIsWanted() throws Exception {
        responseBody = chatResponse("{\\\"plan\\\":1}", "stop");

        configuredClient().complete("sys", "user", OpenAiModelTask.PLANNER, true, 900);

        JsonNode sent = MAPPER.readTree(receivedBodies.get(0));
        assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_object");
    }

    @Test
    void parsesContentUsageAndFinishReason() {
        responseBody = chatResponse("{\\\"plan\\\":1}", "length");

        LlmAttempt attempt = configuredClient().complete("sys", "user", OpenAiModelTask.CHAT, true, 100);

        assertThat(attempt.json().path("plan").asInt()).isEqualTo(1);
        assertThat(attempt.promptTokens()).isEqualTo(11);
        assertThat(attempt.completionTokens()).isEqualTo(7);
        assertThat(attempt.finishReason()).isEqualTo("length");
    }

    @Test
    void fallsBackToTheChatModelWhenNoTaskSpecificModelIsNamed() {
        LocalLlmFallbackClient client = configuredClient();

        // Naming only one local model must still give planner and reranker a fallback.
        assertThat(client.modelFor(OpenAiModelTask.PLANNER)).isEqualTo("llama3.1");
        assertThat(client.modelFor(OpenAiModelTask.RERANKER)).isEqualTo("llama3.1");
    }

    @Test
    void acceptsBaseUrlWithOrWithoutTheVersionSuffix() {
        System.setProperty("BANKINTEL_LLM_FALLBACK_ENABLED", "1");
        System.setProperty("BANKINTEL_LLM_FALLBACK_MODEL_CHAT", "llama3.1");

        System.setProperty("BANKINTEL_LLM_FALLBACK_BASE_URL", "http://host:11434");
        assertThat(new LocalLlmFallbackClient().chatCompletionsUri())
                .hasToString("http://host:11434/v1/chat/completions");

        System.setProperty("BANKINTEL_LLM_FALLBACK_BASE_URL", "http://host:11434/v1");
        assertThat(new LocalLlmFallbackClient().chatCompletionsUri())
                .hasToString("http://host:11434/v1/chat/completions");

        System.setProperty("BANKINTEL_LLM_FALLBACK_BASE_URL", "http://host:11434/v1/");
        assertThat(new LocalLlmFallbackClient().chatCompletionsUri())
                .hasToString("http://host:11434/v1/chat/completions");
    }

    @Test
    void classifiesLocalServerErrors() {
        responseStatus = 503;
        LocalLlmFallbackClient client = configuredClient();

        assertThatThrownBy(() -> client.complete("sys", "user", OpenAiModelTask.CHAT, false, 100))
                .isInstanceOf(OpenAiClientException.class)
                .satisfies(ex -> assertThat(((OpenAiClientException) ex).errorType())
                        .isEqualTo(OpenAiErrorType.LLM_SERVER_ERROR));
    }

    @Test
    void refusesToRunWhenNotConfigured() {
        LocalLlmFallbackClient client = new LocalLlmFallbackClient();

        assertThatThrownBy(() -> client.complete("sys", "user", OpenAiModelTask.CHAT, false, 100))
                .isInstanceOf(OpenAiClientException.class)
                .satisfies(ex -> assertThat(((OpenAiClientException) ex).errorType())
                        .isEqualTo(OpenAiErrorType.LLM_NOT_CONFIGURED));
    }
}
