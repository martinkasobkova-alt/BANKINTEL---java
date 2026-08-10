package cz.bankintel.search.v2.planner;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.openai.OpenAiModelTask;
import cz.bankintel.search.v2.entity.ExactEntityResolver;
import cz.bankintel.search.v2.entity.SearchV2SourceCapabilityRegistry;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.util.BankIntelEnvVars;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.junit.jupiter.api.Test;

class QueryPlannerLatencyBenchmarkTest {

    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/chat/completions");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> BEFORE_QUERIES = List.of(
            "\u00farokov\u00e1 m\u00edra Rakousko",
            "inflace Rusko",
            "Nasdaq-100",
            "HDP Polsko",
            "nezam\u011bstnanost N\u011bmecko",
            "cena zlata",
            "ROE bank \u010cesko",
            "pr\u016fmyslov\u00e1 v\u00fdroba Francie",
            "sazby hypot\u00e9k It\u00e1lie",
            "EUR/USD");

    @Test
    void benchmarkPlannerLatency() throws Exception {
        assumeTrue(flag("BANKINTEL_QUERY_PLANNER_BENCHMARK", "bankintel.queryPlannerBenchmark"));
        String label = setting("BANKINTEL_QUERY_PLANNER_BENCHMARK_LABEL", "bankintel.queryPlannerBenchmark.label", "before");
        int runs = intSetting("BANKINTEL_QUERY_PLANNER_BENCHMARK_RUNS", "bankintel.queryPlannerBenchmark.runs", 30);
        int connectTimeoutMs = intSetting(
                "BANKINTEL_QUERY_PLANNER_BENCHMARK_CONNECT_TIMEOUT_MS",
                "bankintel.queryPlannerBenchmark.connectTimeoutMs",
                15000);
        int requestTimeoutMs = intSetting(
                "BANKINTEL_QUERY_PLANNER_BENCHMARK_REQUEST_TIMEOUT_MS",
                "bankintel.queryPlannerBenchmark.requestTimeoutMs",
                5000);
        String structuredOutputMode = setting(
                "BANKINTEL_QUERY_PLANNER_BENCHMARK_STRUCTURED_MODE",
                "bankintel.queryPlannerBenchmark.structuredMode",
                "after".equals(label) ? "json_schema" : "json_object");

        String apiKey = BankIntelEnvVars.get("OPENAI_API_KEY");
        if (apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not configured for benchmark.");
        }
        String model = BankIntelEnvVars.get("OPENAI_MODEL_PLANNER");
        if (model.isBlank()) {
            model = "gpt-5.4-nano";
        }

        String systemPrompt = plannerSystemPrompt();
        ExactEntityResolver resolver = new ExactEntityResolver(
                MAPPER, new SearchV2SourceCapabilityRegistry(MAPPER));
        SearchV2ConceptRegistry conceptRegistry = new SearchV2ConceptRegistry(MAPPER);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();

        List<Map<String, Object>> samples = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            String query = BEFORE_QUERIES.get(i % BEFORE_QUERIES.size());
            String userPrompt = plannerUserPrompt(query, resolver, conceptRegistry);
            samples.add(callPlanner(
                    client,
                    apiKey,
                    model,
                    systemPrompt,
                    userPrompt,
                    query,
                    i + 1,
                    connectTimeoutMs,
                    requestTimeoutMs,
                    structuredOutputMode));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("label", label);
        report.put("provider", "openai");
        report.put("endpoint", ENDPOINT.toString());
        report.put("model", model);
        report.put("structured_output_mode", structuredOutputMode);
        report.put("connect_timeout_ms", connectTimeoutMs);
        report.put("request_timeout_ms", requestTimeoutMs);
        report.put("sample_count", samples.size());
        report.put("stats", stats(samples));
        report.put("samples", samples);

        Path outputs = Path.of("..", "outputs").toAbsolutePath().normalize();
        Files.createDirectories(outputs);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputs.resolve("query_planner_latency_" + label + ".json").toFile(), report);
        writeCsv(outputs.resolve("query_planner_latency_" + label + ".csv"), samples);
    }

    private static Map<String, Object> callPlanner(
            HttpClient client,
            String apiKey,
            String model,
            String systemPrompt,
            String userPrompt,
            String query,
            int run,
            int connectTimeoutMs,
            int requestTimeoutMs,
            String structuredOutputMode) {
        long connectLatencyMs = measureTlsConnectLatencyMs(connectTimeoutMs);
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("run", run);
        sample.put("query", query);
        sample.put("connect_latency_ms", connectLatencyMs);
        sample.put("input_chars", systemPrompt.length() + userPrompt.length());
        sample.put("input_tokens_estimate", estimateTokens(systemPrompt + "\n" + userPrompt));
        sample.put("output_tokens", null);
        sample.put("fallback_used", false);
        sample.put("success", false);
        sample.put("timeout", false);
        sample.put("error_type", null);
        sample.put("http_status", null);
        long started = System.nanoTime();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)));
            body.put("temperature", 0.2);
            if ("json_schema".equals(structuredOutputMode)) {
                body.put("max_completion_tokens", 900);
                body.put("reasoning_effort", "none");
                body.put("response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", Map.of(
                                "name", "search_query_plan",
                                "strict", true,
                                "schema", SearchV2PlannerStructuredOutput.schema())));
            } else {
                body.put("response_format", Map.of("type", "json_object"));
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(ENDPOINT)
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long totalMs = elapsedMs(started);
            sample.put("total_latency_ms", totalMs);
            sample.put("response_latency_ms", Math.max(0, totalMs - Math.max(0, connectLatencyMs)));
            sample.put("http_status", response.statusCode());
            if (response.statusCode() >= 400) {
                sample.put("fallback_used", true);
                sample.put("error_type", errorTypeForStatus(response.statusCode()));
                return sample;
            }
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                sample.put("input_tokens", usage.path("prompt_tokens").isMissingNode() ? null : usage.path("prompt_tokens").asInt());
                sample.put("output_tokens", usage.path("completion_tokens").isMissingNode() ? null : usage.path("completion_tokens").asInt());
            }
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            sample.put("output_chars", content.length());
            sample.put("output_tokens_estimate", estimateTokens(content));
            sample.put("success", !content.isBlank());
            sample.put("fallback_used", content.isBlank());
            sample.put("error_type", content.isBlank() ? "LLM_EMPTY_RESPONSE" : null);
            return sample;
        } catch (java.net.http.HttpTimeoutException ex) {
            sample.put("total_latency_ms", elapsedMs(started));
            sample.put("response_latency_ms", sample.get("total_latency_ms"));
            sample.put("timeout", true);
            sample.put("fallback_used", true);
            sample.put("error_type", "LLM_REQUEST_TIMEOUT");
            return sample;
        } catch (Exception ex) {
            sample.put("total_latency_ms", elapsedMs(started));
            sample.put("response_latency_ms", sample.get("total_latency_ms"));
            sample.put("fallback_used", true);
            sample.put("error_type", ex.getClass().getSimpleName());
            return sample;
        }
    }

    private static Map<String, Object> stats(List<Map<String, Object>> samples) {
        List<Long> latencies = samples.stream()
                .map(s -> ((Number) s.getOrDefault("total_latency_ms", 0)).longValue())
                .sorted()
                .toList();
        long successes = samples.stream().filter(s -> Boolean.TRUE.equals(s.get("success"))).count();
        long timeouts = samples.stream().filter(s -> Boolean.TRUE.equals(s.get("timeout"))).count();
        long fallbacks = samples.stream().filter(s -> Boolean.TRUE.equals(s.get("fallback_used"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("p50_ms", percentile(latencies, 0.50));
        out.put("p90_ms", percentile(latencies, 0.90));
        out.put("p95_ms", percentile(latencies, 0.95));
        out.put("max_ms", latencies.isEmpty() ? 0 : latencies.getLast());
        out.put("success_rate", rate(successes, samples.size()));
        out.put("timeout_rate", rate(timeouts, samples.size()));
        out.put("fallback_rate", rate(fallbacks, samples.size()));
        return out;
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(sorted.size() * p) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static double rate(long count, int total) {
        return total == 0 ? 0.0 : ((double) count) / total;
    }

    private static String plannerSystemPrompt() throws Exception {
        Field field = SearchV2QueryPlanner.class.getDeclaredField("PROMPT");
        field.setAccessible(true);
        return String.valueOf(field.get(null));
    }

    private static String plannerUserPrompt(
            String query, ExactEntityResolver resolver, SearchV2ConceptRegistry conceptRegistry) throws Exception {
        Method method = SearchV2QueryPlanner.class.getDeclaredMethod(
                "plannerUserPrompt",
                String.class,
                List.class,
                List.class,
                Map.class,
                ExactEntityResolver.ResolutionResult.class,
                SearchV2ConceptRegistry.ConceptResolution.class,
                Map.class,
                Map.class);
        method.setAccessible(true);
        ExactEntityResolver.ResolutionResult resolution = resolver.resolve(query);
        return String.valueOf(method.invoke(
                null,
                query,
                List.of(),
                List.of(),
                Map.of("query", query, "use_ai", true),
                resolution,
                conceptRegistry.resolve(query),
                resolver.plannerContext(),
                conceptRegistry.plannerContext()));
    }

    private static long measureTlsConnectLatencyMs(int connectTimeoutMs) {
        long started = System.nanoTime();
        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket()) {
            socket.connect(new InetSocketAddress("api.openai.com", 443), connectTimeoutMs);
            socket.startHandshake();
            return elapsedMs(started);
        } catch (IOException ex) {
            return -1L;
        }
    }

    private static long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }

    private static String errorTypeForStatus(int status) {
        if (status == 401 || status == 403) {
            return "LLM_AUTH_ERROR";
        }
        if (status == 429) {
            return "LLM_RATE_LIMIT";
        }
        if (status >= 500) {
            return "LLM_SERVER_ERROR";
        }
        return "LLM_HTTP_" + status;
    }

    private static boolean flag(String envName, String propertyName) {
        String env = System.getenv(envName);
        if (env != null && !env.isBlank()) {
            return List.of("1", "true", "yes", "on").contains(env.trim().toLowerCase());
        }
        return Boolean.getBoolean(propertyName);
    }

    private static String setting(String envName, String propertyName, String fallback) {
        String env = System.getenv(envName);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String property = System.getProperty(propertyName);
        return property == null || property.isBlank() ? fallback : property.trim();
    }

    private static int intSetting(String envName, String propertyName, int fallback) {
        try {
            return Integer.parseInt(setting(envName, propertyName, String.valueOf(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static void writeCsv(Path path, List<Map<String, Object>> samples) throws IOException {
        List<String> columns = List.of(
                "run",
                "query",
                "success",
                "timeout",
                "fallback_used",
                "error_type",
                "http_status",
                "connect_latency_ms",
                "response_latency_ms",
                "total_latency_ms",
                "input_chars",
                "input_tokens",
                "input_tokens_estimate",
                "output_tokens",
                "output_tokens_estimate",
                "output_chars");
        List<String> lines = new ArrayList<>();
        lines.add(String.join(",", columns));
        for (Map<String, Object> sample : samples.stream()
                .sorted(Comparator.comparingInt(s -> ((Number) s.get("run")).intValue()))
                .toList()) {
            List<String> row = new ArrayList<>();
            for (String column : columns) {
                row.add(csv(sample.get(column)));
            }
            lines.add(String.join(",", row));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static String csv(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw);
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
