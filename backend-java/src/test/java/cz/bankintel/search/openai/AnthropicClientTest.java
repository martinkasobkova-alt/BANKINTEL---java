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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs against a loopback stand-in for the Anthropic Messages API. The point is the wire contract:
 * Claude's request/response shape is genuinely different from OpenAI's, and every consumer of
 * {@link OpenAiClient} still expects an OpenAI-shaped answer back — getting the translation wrong
 * would only show up once a deployment actually switches {@code BANKINTEL_LLM_PROVIDER} to Anthropic.
 */
class AnthropicClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final List<String> receivedBodies = new ArrayList<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = textResponse("ok", "end_turn");

    @BeforeEach
    void startServer() throws IOException {
        receivedBodies.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        System.clearProperty("BANKINTEL_ANTHROPIC_API_KEY");
        System.clearProperty("BANKINTEL_ANTHROPIC_BASE_URL");
        System.clearProperty("BANKINTEL_ANTHROPIC_MODEL_CHAT");
    }

    private void handle(HttpExchange exchange) throws IOException {
        receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private static String textResponse(String text, String stopReason) {
        return """
               {"id":"msg_1","content":[{"type":"text","text":"%s"}],"stop_reason":"%s",
                "usage":{"input_tokens":11,"output_tokens":7}}
               """
                .formatted(text, stopReason);
    }

    private static String toolUseResponse(String jsonInput) {
        return """
               {"id":"msg_2","content":[{"type":"tool_use","name":"emit_result","input":%s}],
                "stop_reason":"tool_use","usage":{"input_tokens":11,"output_tokens":7}}
               """
                .formatted(jsonInput);
    }

    private AnthropicClient configuredClient() {
        System.setProperty("BANKINTEL_ANTHROPIC_API_KEY", "sk-test");
        System.setProperty("BANKINTEL_ANTHROPIC_BASE_URL", baseUrl);
        System.setProperty("BANKINTEL_ANTHROPIC_MODEL_CHAT", "claude-sonnet-5");
        return new AnthropicClient();
    }

    @Test
    void staysUnconfiguredWithoutAnApiKey() {
        assertThat(new AnthropicClient().isConfigured()).isFalse();

        System.setProperty("BANKINTEL_ANTHROPIC_API_KEY", "sk-test");
        assertThat(new AnthropicClient().isConfigured()).isTrue();
    }

    @Test
    void sendsSystemAsATopLevelFieldNotAMessage() throws Exception {
        configuredClient().complete(
                "claude-sonnet-5", "system prompt", "user prompt", OpenAiModelTask.CHAT, null, false, 500, 3000, 12000);

        JsonNode sent = MAPPER.readTree(receivedBodies.get(0));
        assertThat(sent.path("system").asText()).isEqualTo("system prompt");
        assertThat(sent.path("messages")).hasSize(1);
        assertThat(sent.path("messages").path(0).path("role").asText()).isEqualTo("user");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(500);
        // OpenAI-specific fields must not leak into the Claude request.
        assertThat(sent.has("max_completion_tokens")).isFalse();
        assertThat(sent.has("reasoning_effort")).isFalse();
        assertThat(sent.has("response_format")).isFalse();
    }

    @Test
    void wrapsRawTextInAnOpenAiChatCompletionsEnvelope() {
        responseBody = textResponse("hello there", "end_turn");

        LlmAttempt attempt = configuredClient().complete(
                "claude-sonnet-5", "sys", "user", OpenAiModelTask.CHAT, null, false, 500, 3000, 12000);

        // Every raw consumer of OpenAiClient.chatCompletion(...) reads exactly this path.
        assertThat(attempt.json().path("choices").path(0).path("message").path("content").asText())
                .isEqualTo("hello there");
        assertThat(attempt.promptTokens()).isEqualTo(11);
        assertThat(attempt.completionTokens()).isEqualTo(7);
    }

    @Test
    void mapsMaxTokensStopReasonToLengthSoUsageMeterWarningsStillFire() {
        responseBody = textResponse("cut off", "max_tokens");

        LlmAttempt attempt = configuredClient().complete(
                "claude-sonnet-5", "sys", "user", OpenAiModelTask.CHAT, null, false, 10, 3000, 12000);

        assertThat(attempt.finishReason()).isEqualTo("length");
        assertThat(attempt.json().path("choices").path(0).path("finish_reason").asText()).isEqualTo("length");
    }

    @Test
    void asksForPlainJsonInTheSystemPromptWhenJsonModeIsWanted() {
        responseBody = textResponse("{\\\"plan\\\":1}", "end_turn");

        LlmAttempt attempt = configuredClient().complete(
                "claude-sonnet-5", "sys", "user", OpenAiModelTask.PLANNER, null, true, 900, 3000, 12000);

        assertThat(attempt.json().path("plan").asInt()).isEqualTo(1);
    }

    @Test
    void forcesASyntheticToolCallForSchemaRequests() throws Exception {
        responseBody = toolUseResponse("{\"plan\":2}");
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of());

        LlmAttempt attempt = configuredClient().complete(
                "claude-sonnet-5", "sys", "user", OpenAiModelTask.PLANNER, schema, true, 900, 3000, 12000);

        JsonNode sent = MAPPER.readTree(receivedBodies.get(0));
        assertThat(sent.path("tool_choice").path("type").asText()).isEqualTo("tool");
        assertThat(sent.path("tools").path(0).path("input_schema")).isEqualTo(MAPPER.valueToTree(schema));
        // The tool_use.input is already a validated JSON object — no string re-parsing needed.
        assertThat(attempt.json().path("plan").asInt()).isEqualTo(2);
    }

    @Test
    void classifiesAnthropicHttpErrors() {
        responseStatus = 429;
        AnthropicClient client = configuredClient();

        assertThatThrownBy(() -> client.complete(
                        "claude-sonnet-5", "sys", "user", OpenAiModelTask.CHAT, null, false, 100, 3000, 12000))
                .isInstanceOf(OpenAiClientException.class)
                .satisfies(ex -> assertThat(((OpenAiClientException) ex).errorType())
                        .isEqualTo(OpenAiErrorType.LLM_RATE_LIMIT));
    }

    @Test
    void refusesToRunWhenNotConfigured() {
        AnthropicClient client = new AnthropicClient();

        assertThatThrownBy(() -> client.complete(
                        "claude-sonnet-5", "sys", "user", OpenAiModelTask.CHAT, null, false, 100, 3000, 12000))
                .isInstanceOf(OpenAiClientException.class)
                .satisfies(ex -> assertThat(((OpenAiClientException) ex).errorType())
                        .isEqualTo(OpenAiErrorType.LLM_NOT_CONFIGURED));
    }

    @Test
    void translatesWebSearchIntoTheOpenAiResponsesApiShape() {
        responseBody = """
                {"id":"msg_3","content":[
                  {"type":"text","text":"Answer text","citations":[{"url":"https://example.com/a","title":"A"}]},
                  {"type":"web_search_tool_result","content":[{"url":"https://example.com/b","title":"B"}]}
                ],"stop_reason":"end_turn","usage":{"input_tokens":20,"output_tokens":15}}
                """;

        JsonNode result = configuredClient().webSearch("instructions", "input", 4000);

        // WebResearchService.parseResponse walks exactly this output[]/content[]/annotations shape.
        JsonNode outputText = result.path("output").path(0).path("content").path(0);
        assertThat(outputText.path("type").asText()).isEqualTo("output_text");
        assertThat(outputText.path("text").asText()).isEqualTo("Answer text");
        assertThat(outputText.path("annotations").path(0).path("url").asText()).isEqualTo("https://example.com/a");

        JsonNode webSearchCall = result.path("output").path(1);
        assertThat(webSearchCall.path("type").asText()).isEqualTo("web_search_call");
        List<String> sourceUrls = new ArrayList<>();
        webSearchCall.path("action").path("sources").forEach(source -> sourceUrls.add(source.path("url").asText()));
        assertThat(sourceUrls).contains("https://example.com/a", "https://example.com/b");
    }
}
