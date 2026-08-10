package cz.bankintel.search.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Service
public class OpenAiClient {

    public static final String PROVIDER = "openai";
    public static final URI CHAT_COMPLETIONS = URI.create("https://api.openai.com/v1/chat/completions");
    public static final URI RESPONSES = URI.create("https://api.openai.com/v1/responses");

    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 3000L;
    private static final long DEFAULT_PLANNER_REQUEST_TIMEOUT_MS = 12000L;
    private static final long DEFAULT_CHAT_REQUEST_TIMEOUT_MS = 120000L;
    private static final long RETRY_BACKOFF_MS = 200L;
    private static final long MIN_RETRY_BUDGET_MS = 1000L;
    private static final int PLANNER_MAX_COMPLETION_TOKENS = 900;
    private static final int RERANKER_MAX_COMPLETION_TOKENS = 3000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object httpClientLock = new Object();
    private final HttpClient chatHttpClient = buildHttpClient(15_000L);
    private volatile HttpClient plannerHttpClient;
    private volatile long plannerHttpClientConnectTimeoutMs = -1L;

    @Value("${OPENAI_API_KEY:}")
    private String apiKey;

    @Value("${bankintel.openai.model-chat:${OPENAI_MODEL_CHAT:gpt-5.4-mini}}")
    private String chatModel;

    @Value("${bankintel.openai.model-planner:${OPENAI_MODEL_PLANNER:gpt-5.4-nano}}")
    private String plannerModel;

    @Value("${bankintel.openai.model-reranker:${OPENAI_MODEL_RERANKER:gpt-5.4-nano}}")
    private String rerankerModel;

    @Value("${bankintel.openai.model-web-search:${OPENAI_MODEL_WEB_SEARCH:gpt-5.4-mini}}")
    private String webSearchModel;

    @Value("${bankintel.openai.search-v2-llm-connect-timeout-ms:${SEARCH_V2_LLM_CONNECT_TIMEOUT_MS:3000}}")
    private long connectTimeoutMs;

    @Value("${bankintel.openai.search-v2-llm-request-timeout-ms:${SEARCH_V2_LLM_REQUEST_TIMEOUT_MS:12000}}")
    private long plannerRequestTimeoutMs;

    @Value("${bankintel.openai.search-v2-llm-reasoning-effort:${SEARCH_V2_LLM_REASONING_EFFORT:none}}")
    private String plannerReasoningEffort;

    /** @deprecated override via OPENAI_MODEL_CHAT / OPENAI_MODEL_PLANNER */
    @Deprecated
    @Value("${OPENAI_MODEL:}")
    private String legacyModel;

    public boolean isConfigured() {
        if (BankIntelEnvVars.isFalsy("OPENAI_COMMENTARY")) {
            return false;
        }
        return apiKey != null && !apiKey.isBlank();
    }

    public String modelFor(OpenAiModelTask task) {
        if (legacyModel != null && !legacyModel.isBlank()) {
            return legacyModel.trim();
        }
        return switch (task) {
            case PLANNER -> plannerModel;
            case RERANKER -> rerankerModel;
            case CHAT -> chatModel;
        };
    }

    public long configuredConnectTimeoutMs() {
        return positiveLong(BankIntelEnvVars.get("SEARCH_V2_LLM_CONNECT_TIMEOUT_MS"), connectTimeoutMs, DEFAULT_CONNECT_TIMEOUT_MS);
    }

    public long configuredPlannerRequestTimeoutMs() {
        return positiveLong(
                BankIntelEnvVars.get("SEARCH_V2_LLM_REQUEST_TIMEOUT_MS"),
                plannerRequestTimeoutMs,
                DEFAULT_PLANNER_REQUEST_TIMEOUT_MS);
    }

    public String configuredPlannerReasoningEffort() {
        String env = BankIntelEnvVars.get("SEARCH_V2_LLM_REASONING_EFFORT");
        String value = env.isBlank() ? plannerReasoningEffort : env;
        return value == null ? "none" : value.trim();
    }

    public JsonNode chatCompletion(String systemPrompt, String userPrompt) {
        return chatCompletion(systemPrompt, userPrompt, OpenAiModelTask.CHAT);
    }

    public JsonNode chatCompletion(String systemPrompt, String userPrompt, OpenAiModelTask task) {
        return complete(systemPrompt, userPrompt, task, null, false).json();
    }

    public JsonNode chatCompletionJson(String systemPrompt, String userPrompt) {
        return chatCompletionJson(systemPrompt, userPrompt, OpenAiModelTask.CHAT);
    }

    public JsonNode chatCompletionJson(String systemPrompt, String userPrompt, OpenAiModelTask task) {
        return complete(systemPrompt, userPrompt, task, null, true).json();
    }

    public JsonNode plannerCompletionJson(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, OpenAiModelTask.PLANNER, null, true).json();
    }

    public CompletionResult plannerCompletionJson(String systemPrompt, String userPrompt, Map<String, Object> jsonSchema) {
        return complete(systemPrompt, userPrompt, OpenAiModelTask.PLANNER, jsonSchema, true);
    }

    /**
     * Runs an OpenAI-hosted web search through the Responses API. The returned object is the full
     * Responses payload because callers need both generated text and first-party URL citations.
     */
    public JsonNode webSearch(String instructions, String input) {
        if (!isConfigured()) {
            throw new OpenAiClientException(
                    OpenAiErrorType.LLM_NOT_CONFIGURED,
                    "OPENAI_API_KEY is not configured or OpenAI is disabled");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", webSearchModel == null || webSearchModel.isBlank() ? chatModel : webSearchModel.trim());
        body.put("instructions", instructions == null ? "" : instructions);
        body.put("input", input == null ? "" : input);
        body.put("tools", List.of(Map.of(
                "type", "web_search",
                "search_context_size", "medium")));
        body.put("tool_choice", "auto");
        body.put("include", List.of("web_search_call.action.sources"));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(RESPONSES)
                    .timeout(Duration.ofMillis(DEFAULT_CHAT_REQUEST_TIMEOUT_MS))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = chatHttpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw httpError(response.statusCode(), response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (OpenAiClientException ex) {
            throw ex;
        } catch (HttpConnectTimeoutException | ConnectException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_CONNECT_TIMEOUT, "OpenAI web search connection timed out", ex);
        } catch (HttpTimeoutException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_REQUEST_TIMEOUT, "OpenAI web search timed out", ex);
        } catch (JsonProcessingException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_PARSE_ERROR, "OpenAI web search response parse failed", ex);
        } catch (Exception ex) {
            throw new OpenAiClientException(
                    OpenAiErrorType.LLM_UNKNOWN_ERROR,
                    "OpenAI web search failed: " + ex.getMessage(),
                    ex);
        }
    }

    private CompletionResult complete(
            String systemPrompt,
            String userPrompt,
            OpenAiModelTask task,
            Map<String, Object> jsonSchema,
            boolean jsonMode) {
        if (!isConfigured()) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_NOT_CONFIGURED, "OPENAI_API_KEY is not configured or OpenAI is disabled");
        }
        String model = modelFor(task);
        boolean latencySensitive = task == OpenAiModelTask.PLANNER || task == OpenAiModelTask.RERANKER;
        long connectMs = latencySensitive ? configuredConnectTimeoutMs() : 15000L;
        long requestBudgetMs = latencySensitive ? configuredPlannerRequestTimeoutMs() : DEFAULT_CHAT_REQUEST_TIMEOUT_MS;
        Map<String, Object> trace = baseTrace(model, jsonSchema != null ? "json_schema" : (jsonMode ? "json_object" : "raw"), connectMs, requestBudgetMs);
        long started = System.nanoTime();
        OpenAiClientException lastError = null;
        int attempt = 0;
        while (attempt < 2) {
            attempt++;
            trace.put("attempt_count", attempt);
            long elapsedBeforeAttempt = elapsedMs(started);
            long remaining = requestBudgetMs - elapsedBeforeAttempt;
            if (remaining <= MIN_RETRY_BUDGET_MS) {
                OpenAiClientException ex = lastError != null
                        ? lastError
                        : new OpenAiClientException(OpenAiErrorType.LLM_REQUEST_TIMEOUT, "No request budget remains for OpenAI call");
                traceFailure(trace, ex, started);
                throw ex.withTrace(trace);
            }
            try {
                AttemptResult result = sendOnce(
                        model,
                        systemPrompt,
                        userPrompt,
                        task,
                        jsonSchema,
                        jsonMode,
                        connectMs,
                        Math.min(requestBudgetMs, remaining));
                trace.put("http_status", result.statusCode());
                trace.put("input_chars", safeLength(systemPrompt) + safeLength(userPrompt));
                trace.put("input_tokens", result.promptTokens());
                trace.put("output_tokens", result.completionTokens());
                trace.put("output_chars", result.contentChars());
                trace.put("total_latency_ms", elapsedMs(started));
                trace.put("response_latency_ms", result.elapsedMs());
                trace.put("success", true);
                trace.put("error_type", null);
                trace.put("fallback_used", false);
                return new CompletionResult(result.json(), trace);
            } catch (OpenAiClientException ex) {
                lastError = ex;
                if (!shouldRetry(task, ex, attempt, started, requestBudgetMs)) {
                    traceFailure(trace, ex, started);
                    throw ex.withTrace(trace);
                }
                sleepQuietly(RETRY_BACKOFF_MS);
            }
        }
        traceFailure(trace, lastError, started);
        throw (lastError == null
                ? new OpenAiClientException(OpenAiErrorType.LLM_UNKNOWN_ERROR, "OpenAI request failed")
                : lastError).withTrace(trace);
    }

    private AttemptResult sendOnce(
            String model,
            String systemPrompt,
            String userPrompt,
            OpenAiModelTask task,
            Map<String, Object> jsonSchema,
            boolean jsonMode,
            long connectMs,
            long requestTimeoutMs) {
        long started = System.nanoTime();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put(
                "messages",
                List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        body.put("temperature", 0.2);
        if (task == OpenAiModelTask.PLANNER) {
            body.put("max_completion_tokens", PLANNER_MAX_COMPLETION_TOKENS);
            String reasoningEffort = configuredPlannerReasoningEffort();
            if (!reasoningEffort.isBlank()) {
                body.put("reasoning_effort", reasoningEffort);
            }
        } else if (task == OpenAiModelTask.RERANKER) {
            body.put("max_completion_tokens", RERANKER_MAX_COMPLETION_TOKENS);
            String reasoningEffort = configuredPlannerReasoningEffort();
            if (!reasoningEffort.isBlank()) {
                body.put("reasoning_effort", reasoningEffort);
            }
        }
        if (jsonSchema != null) {
            body.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", "search_query_plan",
                            "strict", true,
                            "schema", jsonSchema)));
        } else if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(CHAT_COMPLETIONS)
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClientFor(task, connectMs)
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw httpError(response.statusCode(), response.body());
            }
            return parseResponse(response, jsonMode || jsonSchema != null, elapsedMs(started));
        } catch (OpenAiClientException ex) {
            throw ex;
        } catch (HttpConnectTimeoutException | ConnectException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_CONNECT_TIMEOUT, "OpenAI connection timed out", ex);
        } catch (HttpTimeoutException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_REQUEST_TIMEOUT, "OpenAI request timed out", ex);
        } catch (JsonProcessingException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_PARSE_ERROR, "OpenAI response JSON parse failed", ex);
        } catch (Exception ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_UNKNOWN_ERROR, "OpenAI request failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Reuse HTTP/2 clients so planner, reranker batches and answer synthesis share DNS, TLS and
     * connection pools. Building a new client for every OpenAI call made one search pay the
     * connection setup cost several times and prevented multiplexing concurrent rerank batches.
     */
    private HttpClient httpClientFor(OpenAiModelTask task, long connectMs) {
        if (task == OpenAiModelTask.CHAT) {
            return chatHttpClient;
        }
        long timeout = Math.max(500L, connectMs);
        HttpClient current = plannerHttpClient;
        if (current != null && plannerHttpClientConnectTimeoutMs == timeout) {
            return current;
        }
        synchronized (httpClientLock) {
            if (plannerHttpClient == null || plannerHttpClientConnectTimeoutMs != timeout) {
                plannerHttpClient = buildHttpClient(timeout);
                plannerHttpClientConnectTimeoutMs = timeout;
            }
            return plannerHttpClient;
        }
    }

    private static HttpClient buildHttpClient(long connectTimeoutMs) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500L, connectTimeoutMs)))
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    private AttemptResult parseResponse(HttpResponse<String> response, boolean contentJson, long elapsedMs)
            throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode usage = root.path("usage");
        Integer promptTokens = usage.path("prompt_tokens").isInt() ? usage.path("prompt_tokens").asInt() : null;
        Integer completionTokens = usage.path("completion_tokens").isInt() ? usage.path("completion_tokens").asInt() : null;
        if (!contentJson) {
            return new AttemptResult(root, response.statusCode(), elapsedMs, promptTokens, completionTokens, response.body().length());
        }
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_EMPTY_RESPONSE, "OpenAI returned empty content");
        }
        try {
            return new AttemptResult(
                    objectMapper.readTree(content),
                    response.statusCode(),
                    elapsedMs,
                    promptTokens,
                    completionTokens,
                    content.length());
        } catch (JsonProcessingException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_PARSE_ERROR, "OpenAI content JSON parse failed", ex);
        }
    }

    private static OpenAiClientException httpError(int status, String body) {
        OpenAiErrorType type;
        if (status == 401 || status == 403) {
            type = OpenAiErrorType.LLM_AUTH_ERROR;
        } else if (status == 429) {
            type = OpenAiErrorType.LLM_RATE_LIMIT;
        } else if (status >= 500) {
            type = OpenAiErrorType.LLM_SERVER_ERROR;
        } else if (body != null && body.toLowerCase().contains("schema")) {
            type = OpenAiErrorType.LLM_SCHEMA_ERROR;
        } else {
            type = OpenAiErrorType.LLM_CLIENT_ERROR;
        }
        return new OpenAiClientException(type, status, "OpenAI HTTP " + status + ": " + safeBody(body));
    }

    private static Map<String, Object> baseTrace(String model, String structuredOutputMode, long connectMs, long requestMs) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("called", true);
        trace.put("provider", PROVIDER);
        trace.put("model", model);
        trace.put("endpoint", CHAT_COMPLETIONS.toString());
        trace.put("structured_output_mode", structuredOutputMode);
        trace.put("attempt_count", 0);
        trace.put("connect_timeout_ms", connectMs);
        trace.put("request_timeout_ms", requestMs);
        trace.put("connect_latency_ms", 0);
        trace.put("response_latency_ms", null);
        trace.put("total_latency_ms", null);
        trace.put("http_status", null);
        trace.put("success", false);
        trace.put("error_type", null);
        trace.put("fallback_used", false);
        return trace;
    }

    private static void traceFailure(Map<String, Object> trace, OpenAiClientException ex, long started) {
        OpenAiClientException safe = ex == null
                ? new OpenAiClientException(OpenAiErrorType.LLM_UNKNOWN_ERROR, "OpenAI request failed")
                : ex;
        trace.put("total_latency_ms", elapsedMs(started));
        trace.put("success", false);
        trace.put("error_type", safe.errorType().name());
        trace.put("http_status", safe.httpStatus() == 0 ? null : safe.httpStatus());
        trace.put("fallback_used", true);
    }

    private static boolean shouldRetry(
            OpenAiModelTask task, OpenAiClientException ex, int attempt, long started, long requestBudgetMs) {
        if (task != OpenAiModelTask.PLANNER || attempt >= 2 || ex == null) {
            return false;
        }
        boolean retryable = ex.errorType() == OpenAiErrorType.LLM_REQUEST_TIMEOUT
                || ex.errorType() == OpenAiErrorType.LLM_CONNECT_TIMEOUT
                || ex.errorType() == OpenAiErrorType.LLM_RATE_LIMIT
                || ex.errorType() == OpenAiErrorType.LLM_SERVER_ERROR;
        if (!retryable) {
            return false;
        }
        long remainingAfterBackoff = requestBudgetMs - elapsedMs(started) - RETRY_BACKOFF_MS;
        return remainingAfterBackoff >= MIN_RETRY_BUDGET_MS;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static long positiveLong(String envValue, long propertyValue, long fallback) {
        long fromEnv = parseLong(envValue, 0L);
        if (fromEnv > 0) {
            return fromEnv;
        }
        if (propertyValue > 0) {
            return propertyValue;
        }
        return fallback;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static int safeLength(String text) {
        return text == null ? 0 : text.length();
    }

    private static String safeBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500);
    }

    public record CompletionResult(JsonNode json, Map<String, Object> trace) {}

    private record AttemptResult(
            JsonNode json,
            int statusCode,
            long elapsedMs,
            Integer promptTokens,
            Integer completionTokens,
            int contentChars) {}
}
