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

/**
 * Locally hosted fallback model (Ollama, vLLM, or anything else exposing an OpenAI-compatible
 * {@code /v1/chat/completions} endpoint).
 *
 * <p>Exists so an OpenAI outage, rate limit or key problem degrades the product instead of
 * breaking it. Because both Ollama and vLLM speak the OpenAI wire format, this reuses the same
 * request and response shape — only the base URL, the model name and a few dialect details differ:
 *
 * <ul>
 *   <li>{@code max_tokens} instead of {@code max_completion_tokens} — the older spelling is the one
 *       both servers accept.
 *   <li>No {@code reasoning_effort}; local servers reject the unknown field.
 *   <li>A strict {@code json_schema} request degrades to plain {@code json_object} mode, because
 *       schema enforcement is inconsistent across local runtimes. Callers still get JSON, just
 *       without the server-side guarantee — they already have to tolerate a bad plan.
 * </ul>
 *
 * <p>Disabled by default: without {@code BANKINTEL_LLM_FALLBACK_ENABLED} nothing here ever runs.
 */
@Service
public class LocalLlmFallbackClient {

    public static final String PROVIDER = "local";

    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 2000L;
    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 30000L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object httpClientLock = new Object();
    private volatile HttpClient httpClient;
    private volatile long httpClientConnectTimeoutMs = -1L;

    @Value("${bankintel.llm.fallback.enabled:${BANKINTEL_LLM_FALLBACK_ENABLED:false}}")
    private boolean enabled;

    @Value("${bankintel.llm.fallback.base-url:${BANKINTEL_LLM_FALLBACK_BASE_URL:}}")
    private String baseUrl;

    /** Ollama ignores it, vLLM can require one. Blank means no Authorization header is sent. */
    @Value("${bankintel.llm.fallback.api-key:${BANKINTEL_LLM_FALLBACK_API_KEY:}}")
    private String apiKey;

    @Value("${bankintel.llm.fallback.model-chat:${BANKINTEL_LLM_FALLBACK_MODEL_CHAT:}}")
    private String chatModel;

    @Value("${bankintel.llm.fallback.model-planner:${BANKINTEL_LLM_FALLBACK_MODEL_PLANNER:}}")
    private String plannerModel;

    @Value("${bankintel.llm.fallback.model-reranker:${BANKINTEL_LLM_FALLBACK_MODEL_RERANKER:}}")
    private String rerankerModel;

    @Value("${bankintel.llm.fallback.connect-timeout-ms:${BANKINTEL_LLM_FALLBACK_CONNECT_TIMEOUT_MS:2000}}")
    private long connectTimeoutMs;

    @Value("${bankintel.llm.fallback.request-timeout-ms:${BANKINTEL_LLM_FALLBACK_REQUEST_TIMEOUT_MS:30000}}")
    private long requestTimeoutMs;

    public boolean isConfigured() {
        if (!enabledFlag()) {
            return false;
        }
        return !resolvedBaseUrl().isBlank() && !modelFor(OpenAiModelTask.CHAT).isBlank();
    }

    /**
     * Falls back across tasks: a deployment that only names one local model still gets failover for
     * planner and reranker rather than silently having none.
     */
    public String modelFor(OpenAiModelTask task) {
        String configured = switch (task) {
            case PLANNER -> firstNonBlank(env("BANKINTEL_LLM_FALLBACK_MODEL_PLANNER"), plannerModel);
            case RERANKER -> firstNonBlank(env("BANKINTEL_LLM_FALLBACK_MODEL_RERANKER"), rerankerModel);
            case CHAT -> "";
        };
        if (!configured.isBlank()) {
            return configured;
        }
        return firstNonBlank(env("BANKINTEL_LLM_FALLBACK_MODEL_CHAT"), chatModel);
    }

    public long configuredRequestTimeoutMs() {
        return positiveLong(env("BANKINTEL_LLM_FALLBACK_REQUEST_TIMEOUT_MS"), requestTimeoutMs, DEFAULT_REQUEST_TIMEOUT_MS);
    }

    public long configuredConnectTimeoutMs() {
        return positiveLong(env("BANKINTEL_LLM_FALLBACK_CONNECT_TIMEOUT_MS"), connectTimeoutMs, DEFAULT_CONNECT_TIMEOUT_MS);
    }

    LlmAttempt complete(
            String systemPrompt,
            String userPrompt,
            OpenAiModelTask task,
            boolean jsonMode,
            int maxTokens) {
        if (!isConfigured()) {
            throw new OpenAiClientException(
                    OpenAiErrorType.LLM_NOT_CONFIGURED, "Local LLM fallback is not configured");
        }
        String model = modelFor(task);
        long started = System.nanoTime();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put(
                "messages",
                List.of(
                        Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt),
                        Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt)));
        body.put("temperature", 0.2);
        body.put("max_tokens", maxTokens);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(chatCompletionsUri())
                    .timeout(Duration.ofMillis(configuredRequestTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            String key = resolvedApiKey();
            if (!key.isBlank()) {
                builder.header("Authorization", "Bearer " + key);
            }
            HttpResponse<String> response = httpClient()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw httpError(response.statusCode(), response.body());
            }
            return parseResponse(response, jsonMode, elapsedMs(started));
        } catch (OpenAiClientException ex) {
            throw ex;
        } catch (HttpConnectTimeoutException | ConnectException ex) {
            throw new OpenAiClientException(
                    OpenAiErrorType.LLM_CONNECT_TIMEOUT, "Local LLM connection timed out", ex);
        } catch (HttpTimeoutException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_REQUEST_TIMEOUT, "Local LLM request timed out", ex);
        } catch (JsonProcessingException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_PARSE_ERROR, "Local LLM response JSON parse failed", ex);
        } catch (Exception ex) {
            throw new OpenAiClientException(
                    OpenAiErrorType.LLM_UNKNOWN_ERROR, "Local LLM request failed: " + ex.getMessage(), ex);
        }
    }

    URI chatCompletionsUri() {
        String base = resolvedBaseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // Accept both "http://host:11434" and "http://host:11434/v1" so operators cannot get it
        // subtly wrong and only find out during an outage.
        if (base.endsWith("/chat/completions")) {
            return URI.create(base);
        }
        if (base.endsWith("/v1")) {
            return URI.create(base + "/chat/completions");
        }
        return URI.create(base + "/v1/chat/completions");
    }

    private LlmAttempt parseResponse(HttpResponse<String> response, boolean contentJson, long elapsedMs)
            throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode usage = root.path("usage");
        Integer promptTokens = usage.path("prompt_tokens").isInt() ? usage.path("prompt_tokens").asInt() : null;
        Integer completionTokens =
                usage.path("completion_tokens").isInt() ? usage.path("completion_tokens").asInt() : null;
        String finishReason = root.path("choices").path(0).path("finish_reason").asText(null);
        if (!contentJson) {
            return new LlmAttempt(
                    root,
                    response.statusCode(),
                    elapsedMs,
                    promptTokens,
                    completionTokens,
                    response.body().length(),
                    finishReason);
        }
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_EMPTY_RESPONSE, "Local LLM returned empty content");
        }
        try {
            return new LlmAttempt(
                    objectMapper.readTree(content),
                    response.statusCode(),
                    elapsedMs,
                    promptTokens,
                    completionTokens,
                    content.length(),
                    finishReason);
        } catch (JsonProcessingException ex) {
            throw new OpenAiClientException(OpenAiErrorType.LLM_PARSE_ERROR, "Local LLM content JSON parse failed", ex);
        }
    }

    private HttpClient httpClient() {
        long timeout = Math.max(500L, configuredConnectTimeoutMs());
        HttpClient current = httpClient;
        if (current != null && httpClientConnectTimeoutMs == timeout) {
            return current;
        }
        synchronized (httpClientLock) {
            if (httpClient == null || httpClientConnectTimeoutMs != timeout) {
                httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(timeout))
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
                httpClientConnectTimeoutMs = timeout;
            }
            return httpClient;
        }
    }

    private boolean enabledFlag() {
        String env = env("BANKINTEL_LLM_FALLBACK_ENABLED");
        if (!env.isBlank()) {
            return BankIntelEnvVars.isTruthy("BANKINTEL_LLM_FALLBACK_ENABLED");
        }
        return enabled;
    }

    private String resolvedBaseUrl() {
        return firstNonBlank(env("BANKINTEL_LLM_FALLBACK_BASE_URL"), baseUrl);
    }

    private String resolvedApiKey() {
        return firstNonBlank(env("BANKINTEL_LLM_FALLBACK_API_KEY"), apiKey);
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
        } else {
            type = OpenAiErrorType.LLM_CLIENT_ERROR;
        }
        String safe = body == null || body.isBlank() ? "" : (body.length() <= 500 ? body : body.substring(0, 500));
        return new OpenAiClientException(type, status, "Local LLM HTTP " + status + ": " + safe);
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
