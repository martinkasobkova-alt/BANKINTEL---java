package cz.bankintel.search.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cz.bankintel.util.BankIntelEnvVars;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Anthropic (Claude) Messages API client, selectable as the primary LLM provider via
 * {@code BANKINTEL_LLM_PROVIDER=anthropic} (see {@link OpenAiClient}, which dispatches to this
 * collaborator exactly the way it already dispatches to {@link LocalLlmFallbackClient}).
 *
 * <p>Claude's wire format differs from OpenAI's in several ways this class has to bridge, always in
 * the direction of matching what {@link OpenAiClient}'s callers already expect:
 *
 * <ul>
 *   <li>{@code system} is a top-level request field, not a {@code role:"system"} message.
 *   <li>{@code max_tokens} instead of {@code max_completion_tokens}; no {@code reasoning_effort}.
 *   <li>No {@code response_format}. A requested {@code json_schema} becomes a single forced tool call
 *       (stronger than OpenAI's json_schema mode: the {@code tool_use.input} is already a validated
 *       JSON object, not a string to re-parse). A plain {@code json_object} request degrades to a
 *       prompt instruction — best-effort, not server-enforced, the same trade
 *       {@link LocalLlmFallbackClient} already makes for the same case.
 *   <li>Raw (non-JSON) replies are wrapped in a synthetic OpenAI Chat Completions envelope
 *       ({@code choices[0].message.content}) because every raw caller already hand-parses that shape.
 *   <li>{@link #webSearch} translates Claude's hosted web search tool result into the same OpenAI
 *       Responses-API {@code output[]} shape {@code WebResearchService} already walks.
 * </ul>
 */
@Service
public class AnthropicClient {

    public static final String PROVIDER = "anthropic";

    private static final URI DEFAULT_BASE_URI = URI.create("https://api.anthropic.com");
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 3000L;
    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 12000L;
    private static final String TOOL_NAME = "emit_result";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object httpClientLock = new Object();
    private volatile HttpClient httpClient;
    private volatile long httpClientConnectTimeoutMs = -1L;

    @Value("${bankintel.anthropic.api-key:${BANKINTEL_ANTHROPIC_API_KEY:}}")
    private String apiKey;

    @Value("${bankintel.anthropic.base-url:${BANKINTEL_ANTHROPIC_BASE_URL:}}")
    private String baseUrl;

    @Value("${bankintel.anthropic.model-chat:${BANKINTEL_ANTHROPIC_MODEL_CHAT:claude-sonnet-5}}")
    private String chatModel;

    @Value("${bankintel.anthropic.model-planner:${BANKINTEL_ANTHROPIC_MODEL_PLANNER:claude-sonnet-5}}")
    private String plannerModel;

    @Value("${bankintel.anthropic.model-reranker:${BANKINTEL_ANTHROPIC_MODEL_RERANKER:claude-haiku-4-5}}")
    private String rerankerModel;

    @Value("${bankintel.anthropic.model-web-search:${BANKINTEL_ANTHROPIC_MODEL_WEB_SEARCH:claude-sonnet-5}}")
    private String webSearchModel;

    @Value("${bankintel.anthropic.connect-timeout-ms:${BANKINTEL_ANTHROPIC_CONNECT_TIMEOUT_MS:3000}}")
    private long connectTimeoutMs;

    @Value("${bankintel.anthropic.request-timeout-ms:${BANKINTEL_ANTHROPIC_REQUEST_TIMEOUT_MS:12000}}")
    private long requestTimeoutMs;

    public boolean isConfigured() {
        return !resolvedApiKey().isBlank();
    }

    public String modelFor(OpenAiModelTask task) {
        return switch (task) {
            case PLANNER -> firstNonBlank(env("BANKINTEL_ANTHROPIC_MODEL_PLANNER"), plannerModel);
            case RERANKER -> firstNonBlank(env("BANKINTEL_ANTHROPIC_MODEL_RERANKER"), rerankerModel);
            case CHAT -> firstNonBlank(env("BANKINTEL_ANTHROPIC_MODEL_CHAT"), chatModel);
        };
    }

    public long configuredConnectTimeoutMs() {
        return positiveLong(env("BANKINTEL_ANTHROPIC_CONNECT_TIMEOUT_MS"), connectTimeoutMs, DEFAULT_CONNECT_TIMEOUT_MS);
    }

    public long configuredRequestTimeoutMs() {
        return positiveLong(env("BANKINTEL_ANTHROPIC_REQUEST_TIMEOUT_MS"), requestTimeoutMs, DEFAULT_REQUEST_TIMEOUT_MS);
    }

    /**
     * Mirrors {@link OpenAiClient}'s {@code sendOnce}/{@link LocalLlmFallbackClient#complete} shape so
     * the shared retry/failover logic in {@link OpenAiClient#complete} keeps working unchanged.
     */
    LlmAttempt complete(
            String model,
            String systemPrompt,
            String userPrompt,
            OpenAiModelTask task,
            Map<String, Object> jsonSchema,
            boolean jsonMode,
            int maxTokens,
            long connectMs,
            long requestTimeoutMs) {
        if (!isConfigured()) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_NOT_CONFIGURED, "BANKINTEL_ANTHROPIC_API_KEY is not configured");
        }
        long started = System.nanoTime();
        String effectiveSystem = systemPrompt == null ? "" : systemPrompt;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt)));
        if (jsonSchema != null) {
            body.put("system", effectiveSystem);
            body.put("tools", List.of(Map.of(
                    "name", TOOL_NAME,
                    "description", "Return the result.",
                    "input_schema", jsonSchema)));
            body.put("tool_choice", Map.of("type", "tool", "name", TOOL_NAME));
        } else if (jsonMode) {
            body.put("system", effectiveSystem
                    + "\n\nRespond with only valid JSON. No markdown code fences, no commentary before or after.");
        } else {
            body.put("system", effectiveSystem);
        }
        try {
            HttpResponse<String> response = send(body, connectMs, requestTimeoutMs);
            if (response.statusCode() >= 400) {
                throw httpError(response.statusCode(), response.body());
            }
            return parseComplete(response, jsonSchema != null, jsonMode, elapsedMs(started));
        } catch (OpenAiClientException ex) {
            throw ex;
        } catch (HttpConnectTimeoutException | ConnectException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_CONNECT_TIMEOUT, "Anthropic connection timed out", ex);
        } catch (HttpTimeoutException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_REQUEST_TIMEOUT, "Anthropic request timed out", ex);
        } catch (JsonProcessingException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_PARSE_ERROR, "Anthropic response JSON parse failed", ex);
        } catch (Exception ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_UNKNOWN_ERROR, "Anthropic request failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Runs Claude's hosted web search tool and translates the result into the same OpenAI
     * Responses-API {@code output[]} shape {@code WebResearchService.parseResponse} already walks, so
     * that caller needs zero changes to accept an Anthropic-served answer.
     */
    JsonNode webSearch(String instructions, String input, int maxOutputTokens) {
        if (!isConfigured()) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_NOT_CONFIGURED, "BANKINTEL_ANTHROPIC_API_KEY is not configured");
        }
        long started = System.nanoTime();
        Map<String, Object> body = new LinkedHashMap<>();
        String model = firstNonBlank(env("BANKINTEL_ANTHROPIC_MODEL_WEB_SEARCH"), webSearchModel);
        body.put("model", model.isBlank() ? modelFor(OpenAiModelTask.CHAT) : model);
        body.put("max_tokens", maxOutputTokens);
        body.put("system", instructions == null ? "" : instructions);
        body.put("messages", List.of(Map.of("role", "user", "content", input == null ? "" : input)));
        body.put("tools", List.of(Map.of("type", "web_search_20260209", "name", "web_search")));
        try {
            HttpResponse<String> response = send(
                    body, configuredConnectTimeoutMs(), Math.max(configuredRequestTimeoutMs(), 15000L));
            if (response.statusCode() >= 400) {
                throw httpError(response.statusCode(), response.body());
            }
            return translateWebSearchResponse(objectMapper.readTree(response.body()), elapsedMs(started));
        } catch (OpenAiClientException ex) {
            throw ex;
        } catch (HttpConnectTimeoutException | ConnectException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_CONNECT_TIMEOUT, "Anthropic web search connection timed out", ex);
        } catch (HttpTimeoutException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_REQUEST_TIMEOUT, "Anthropic web search timed out", ex);
        } catch (JsonProcessingException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_PARSE_ERROR, "Anthropic web search response parse failed", ex);
        } catch (Exception ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_UNKNOWN_ERROR, "Anthropic web search failed: " + ex.getMessage(), ex);
        }
    }

    private HttpResponse<String> send(Map<String, Object> body, long connectMs, long requestTimeoutMs)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(messagesUri())
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .header("x-api-key", resolvedApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        return httpClient(connectMs).send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private LlmAttempt parseComplete(HttpResponse<String> response, boolean schemaMode, boolean jsonMode, long elapsedMs)
            throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode usage = root.path("usage");
        Integer promptTokens = usage.path("input_tokens").isInt() ? usage.path("input_tokens").asInt() : null;
        Integer completionTokens = usage.path("output_tokens").isInt() ? usage.path("output_tokens").asInt() : null;
        String finishReason = mapStopReason(root.path("stop_reason").asText(null));

        if (schemaMode) {
            for (JsonNode block : root.path("content")) {
                if ("tool_use".equals(block.path("type").asText(""))) {
                    JsonNode toolInput = block.path("input");
                    return new LlmAttempt(
                            toolInput, response.statusCode(), elapsedMs, promptTokens, completionTokens,
                            toolInput.toString().length(), finishReason);
                }
            }
            throw new OpenAiClientException(OpenAiErrorType.LLM_EMPTY_RESPONSE, "Anthropic returned no tool_use block");
        }

        String text = concatenatedText(root);
        if (text.isBlank()) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_EMPTY_RESPONSE, "Anthropic returned empty content");
        }
        if (jsonMode) {
            try {
                JsonNode parsed = objectMapper.readTree(stripCodeFences(text));
                return new LlmAttempt(
                        parsed, response.statusCode(), elapsedMs, promptTokens, completionTokens, text.length(), finishReason);
            } catch (JsonProcessingException ex) {
                throw new OpenAiClientException(OpenAiErrorType.LLM_PARSE_ERROR, "Anthropic content JSON parse failed", ex);
            }
        }
        ObjectNode envelope = objectMapper.createObjectNode();
        ArrayNode choices = envelope.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.putObject("message").put("content", text);
        choice.put("finish_reason", finishReason);
        ObjectNode usageNode = envelope.putObject("usage");
        if (promptTokens != null) {
            usageNode.put("prompt_tokens", promptTokens);
        }
        if (completionTokens != null) {
            usageNode.put("completion_tokens", completionTokens);
        }
        return new LlmAttempt(
                envelope, response.statusCode(), elapsedMs, promptTokens, completionTokens, text.length(), finishReason);
    }

    private JsonNode translateWebSearchResponse(JsonNode root, long elapsedMs) {
        ObjectMapper mapper = objectMapper;
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("id", root.path("id").asText(""));
        ArrayNode output = envelope.putArray("output");

        StringBuilder text = new StringBuilder();
        java.util.LinkedHashMap<String, String> sources = new java.util.LinkedHashMap<>();
        for (JsonNode block : root.path("content")) {
            String type = block.path("type").asText("");
            if ("text".equals(type)) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(block.path("text").asText(""));
                for (JsonNode citation : block.path("citations")) {
                    String url = citation.path("url").asText("");
                    if (!url.isBlank()) {
                        sources.putIfAbsent(url, citation.path("title").asText(""));
                    }
                }
            } else if ("web_search_tool_result".equals(type)) {
                for (JsonNode result : block.path("content")) {
                    String url = result.path("url").asText("");
                    if (!url.isBlank()) {
                        sources.putIfAbsent(url, result.path("title").asText(""));
                    }
                }
            }
        }

        ObjectNode message = output.addObject();
        message.put("type", "message");
        ArrayNode messageContent = message.putArray("content");
        ObjectNode outputText = messageContent.addObject();
        outputText.put("type", "output_text");
        outputText.put("text", text.toString());
        ArrayNode annotations = outputText.putArray("annotations");
        sources.forEach((url, title) -> {
            ObjectNode annotation = annotations.addObject();
            annotation.put("url", url);
            annotation.put("title", title);
        });

        ObjectNode webSearchCall = output.addObject();
        webSearchCall.put("type", "web_search_call");
        ArrayNode callSources = webSearchCall.putObject("action").putArray("sources");
        sources.forEach((url, title) -> {
            ObjectNode source = callSources.addObject();
            source.put("url", url);
            source.put("title", title);
        });

        JsonNode usage = root.path("usage");
        ObjectNode usageNode = envelope.putObject("usage");
        if (usage.path("input_tokens").isInt()) {
            usageNode.put("input_tokens", usage.path("input_tokens").asInt());
        }
        if (usage.path("output_tokens").isInt()) {
            usageNode.put("output_tokens", usage.path("output_tokens").asInt());
        }
        return envelope;
    }

    private static String concatenatedText(JsonNode root) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText(""))) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(block.path("text").asText(""));
            }
        }
        return text.toString().trim();
    }

    private static String stripCodeFences(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int start = trimmed.indexOf('\n');
        int end = trimmed.lastIndexOf("```");
        if (start >= 0 && end > start) {
            return trimmed.substring(start + 1, end).trim();
        }
        return trimmed;
    }

    private static String mapStopReason(String stopReason) {
        if (stopReason == null) {
            return null;
        }
        return "max_tokens".equals(stopReason) ? "length" : "stop";
    }

    URI messagesUri() {
        return URI.create(resolvedBaseUrl() + "/v1/messages");
    }

    private HttpClient httpClient(long connectMs) {
        long timeout = Math.max(500L, connectMs);
        HttpClient current = httpClient;
        if (current != null && httpClientConnectTimeoutMs == timeout) {
            return current;
        }
        synchronized (httpClientLock) {
            if (httpClient == null || httpClientConnectTimeoutMs != timeout) {
                httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(timeout))
                        .version(HttpClient.Version.HTTP_2)
                        .build();
                httpClientConnectTimeoutMs = timeout;
            }
            return httpClient;
        }
    }

    private String resolvedBaseUrl() {
        String resolved = firstNonBlank(env("BANKINTEL_ANTHROPIC_BASE_URL"), baseUrl);
        if (resolved.isBlank()) {
            return DEFAULT_BASE_URI.toString();
        }
        return resolved.endsWith("/") ? resolved.substring(0, resolved.length() - 1) : resolved;
    }

    private String resolvedApiKey() {
        return firstNonBlank(env("BANKINTEL_ANTHROPIC_API_KEY"), apiKey);
    }

    private static String env(String name) {
        return BankIntelEnvVars.get(name);
    }

    private static OpenAiClientException httpError(int status, String body) {
        OpenAiErrorType type;
        if (status == 401 || status == 403) {
            type = OpenAiErrorType.LLM_AUTH_ERROR;
        } else if (status == 429) {
            type = OpenAiErrorType.LLM_RATE_LIMIT;
        } else if (status >= 500) {
            type = OpenAiErrorType.LLM_SERVER_ERROR;
        } else if (body != null && body.toLowerCase(java.util.Locale.ROOT).contains("schema")) {
            type = OpenAiErrorType.LLM_SCHEMA_ERROR;
        } else {
            type = OpenAiErrorType.LLM_CLIENT_ERROR;
        }
        return new OpenAiClientException(type, status, "Anthropic HTTP " + status + ": " + safeBody(body));
    }

    private static String safeBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    private static long positiveLong(String envValue, long propertyValue, long fallback) {
        if (envValue != null && !envValue.isBlank()) {
            try {
                long parsed = Long.parseLong(envValue.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the property value
            }
        }
        return propertyValue > 0 ? propertyValue : fallback;
    }

    private static long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }
}
