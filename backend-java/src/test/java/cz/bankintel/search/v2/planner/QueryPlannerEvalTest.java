package cz.bankintel.search.v2.planner;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.v2.entity.ExactEntityResolver;
import cz.bankintel.search.v2.entity.SearchV2SourceCapabilityRegistry;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.util.BankIntelEnvVars;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueryPlannerEvalTest {

    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/chat/completions");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<Case> CASES = List.of(
            new Case("\u00farokov\u00e1 m\u00edra Rakousko", List.of("interest rate"), List.of("AT"), List.of("interest rates")),
            new Case("m\u00edra nezam\u011bstnanosti Rakousko", List.of("unemployment rate"), List.of("AT"), List.of("labour")),
            new Case("m\u00edra inflace Rakousko", List.of("inflation rate"), List.of("AT"), List.of("prices")),
            new Case("hypote\u010dn\u00ed sazby N\u011bmecko", List.of("mortgage rate", "lending rate", "interest rate"), List.of("DE"), List.of("interest rates", "banking")),
            new Case("sazby vklad\u016f Francie", List.of("deposit rate", "interest rate"), List.of("FR"), List.of("interest rates", "banking")),
            new Case("v\u00fdnos st\u00e1tn\u00edho dluhopisu It\u00e1lie", List.of("bond yield", "government bond yield"), List.of("IT"), List.of("bond yields")),
            new Case("\u00farokov\u00e9 sazby Polsko", List.of("interest rate"), List.of("PL"), List.of("interest rates")),
            new Case("\u00farokov\u00e9 sazby USA", List.of("interest rate"), List.of("US"), List.of("interest rates")),
            new Case("inflace Rusko", List.of("inflation rate"), List.of("RU"), List.of("prices", "macro")),
            new Case("Nasdaq-100", List.of("market index", "equity index"), List.of(), List.of("markets indices")),
            new Case("EUR/USD", List.of("exchange rate", "fx pair"), List.of(), List.of("fx")),
            new Case("HDP Polsko", List.of("gdp", "gross domestic product"), List.of("PL"), List.of("macro")),
            new Case("pr\u016fmyslov\u00e1 v\u00fdroba N\u011bmecko", List.of("industrial production"), List.of("DE"), List.of("industry")),
            new Case("cena zlata", List.of("gold price", "commodity price"), List.of(), List.of("commodities")),
            new Case("ROE bank \u010cesko", List.of("return on equity", "bank profitability"), List.of("CZ"), List.of("banking")));

    @Test
    void evaluatePlannerMatrix() throws Exception {
        assumeTrue(flag("BANKINTEL_QUERY_PLANNER_EVAL"));
        String apiKey = BankIntelEnvVars.get("OPENAI_API_KEY");
        if (apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not configured for eval.");
        }
        String model = BankIntelEnvVars.get("OPENAI_MODEL_PLANNER");
        if (model.isBlank()) {
            model = "gpt-5.4-nano";
        }
        String systemPrompt = plannerSystemPrompt();
        ExactEntityResolver resolver = new ExactEntityResolver(
                MAPPER, new SearchV2SourceCapabilityRegistry(MAPPER));
        SearchV2ConceptRegistry conceptRegistry = new SearchV2ConceptRegistry(MAPPER);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(3000)).build();

        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode interestRateAustria = null;
        for (Case item : CASES) {
            long started = System.nanoTime();
            JsonNode output = callPlanner(
                    client, apiKey, model, systemPrompt, plannerUserPrompt(item.query(), resolver, conceptRegistry));
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            boolean schemaOk = schemaOk(output);
            boolean conceptOk = matchesAny(join(output.path("required_concepts"), output.path("measure_types")), item.conceptNeedles());
            boolean geoOk = item.expectedGeos().isEmpty()
                    || (containsAllFolded(output.path("geographies"), item.expectedGeos())
                            && containsNoUnexpectedGeographies(output.path("geographies"), item.expectedGeos()));
            boolean familyOk = matchesAny(output.path("catalog_families").toString(), item.familyNeedles());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("query", item.query());
            row.put("latency_ms", latencyMs);
            row.put("schema_compliance", schemaOk);
            row.put("concept_accuracy", conceptOk);
            row.put("geo_accuracy", geoOk);
            row.put("source_family_routing_accuracy", familyOk);
            row.put("required_concepts", stringArray(output.path("required_concepts")));
            row.put("measure_types", stringArray(output.path("measure_types")));
            row.put("geographies", stringArray(output.path("geographies")));
            row.put("geo_memberships", stringArray(output.path("geo_memberships")));
            row.put("catalog_families", stringArray(output.path("catalog_families")));
            row.put("preferred_sources", stringArray(output.path("preferred_sources")));
            row.put("clarification_required", output.path("clarification_required").asBoolean(false));
            row.put("raw_output", MAPPER.convertValue(output, Map.class));
            rows.add(row);
            if (item.query().startsWith("\u00farokov\u00e1 m\u00edra")) {
                interestRateAustria = output;
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("provider", "openai");
        report.put("endpoint", ENDPOINT.toString());
        report.put("model", model);
        report.put("structured_output_mode", "json_schema");
        report.put("sample_count", rows.size());
        report.put("metrics", metrics(rows));
        report.put("rows", rows);

        Path outputs = Path.of("..", "outputs").toAbsolutePath().normalize();
        Files.createDirectories(outputs);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputs.resolve("query_planner_eval.json").toFile(), report);
        writeCsv(outputs.resolve("query_planner_eval.csv"), rows);
        if (interestRateAustria != null) {
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(outputs.resolve("interest_rate_austria_llm_output_valid.json").toFile(), interestRateAustria);
        }
    }

    private static JsonNode callPlanner(HttpClient client, String apiKey, String model, String systemPrompt, String userPrompt)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("temperature", 0.2);
        body.put("reasoning_effort", "none");
        body.put("max_completion_tokens", 900);
        body.put("response_format", Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", "search_query_plan",
                        "strict", true,
                        "schema", SearchV2PlannerStructuredOutput.schema())));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(ENDPOINT)
                .timeout(Duration.ofMillis(12000))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("OpenAI HTTP " + response.statusCode());
        }
        String content = MAPPER.readTree(response.body()).path("choices").path(0).path("message").path("content").asText("");
        return MAPPER.readTree(content);
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

    private static Map<String, Object> metrics(List<Map<String, Object>> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planner_success_rate", 1.0);
        out.put("timeout_rate", 0.0);
        out.put("fallback_rate", 0.0);
        out.put("schema_compliance", rate(rows, "schema_compliance"));
        out.put("concept_accuracy", rate(rows, "concept_accuracy"));
        out.put("geo_accuracy", rate(rows, "geo_accuracy"));
        out.put("source_family_routing_accuracy", rate(rows, "source_family_routing_accuracy"));
        List<Long> latencies = rows.stream().map(row -> ((Number) row.get("latency_ms")).longValue()).sorted().toList();
        out.put("p50_ms", percentile(latencies, 0.50));
        out.put("p90_ms", percentile(latencies, 0.90));
        out.put("p95_ms", percentile(latencies, 0.95));
        out.put("max_ms", latencies.isEmpty() ? 0 : latencies.getLast());
        return out;
    }

    private static double rate(List<Map<String, Object>> rows, String key) {
        long ok = rows.stream().filter(row -> Boolean.TRUE.equals(row.get(key))).count();
        return rows.isEmpty() ? 0.0 : ((double) ok) / rows.size();
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(sorted.size() * p) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static boolean schemaOk(JsonNode output) {
        return output.has("normalized_query")
                && output.has("intent")
                && output.path("required_concepts").isArray()
                && output.path("geographies").isArray()
                && output.path("catalog_families").isArray()
                && output.path("preferred_sources").isArray()
                && output.path("query_variants").isArray()
                && output.has("confidence")
                && output.has("clarification_required");
    }

    private static String join(JsonNode first, JsonNode second) {
        return String.join(" ", stringArray(first)) + " " + String.join(" ", stringArray(second));
    }

    private static boolean containsAllFolded(JsonNode values, List<String> expected) {
        String haystack = fold(String.join(" ", stringArray(values)));
        return expected.stream().allMatch(item -> haystack.contains(fold(item)));
    }

    private static boolean containsNoUnexpectedGeographies(JsonNode values, List<String> expected) {
        List<String> expectedFolded = expected.stream().map(QueryPlannerEvalTest::fold).toList();
        for (String value : stringArray(values)) {
            String folded = fold(value);
            if (!expectedFolded.contains(folded)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAny(String haystack, List<String> needles) {
        String foldedHaystack = fold(haystack);
        return needles.stream().map(QueryPlannerEvalTest::fold).anyMatch(needle -> {
            if (foldedHaystack.contains(needle)) {
                return true;
            }
            List<String> tokens = List.of(needle.split(" "));
            return !tokens.isEmpty() && tokens.stream().allMatch(foldedHaystack::contains);
        });
    }

    private static List<String> stringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                out.add(value);
            }
        });
        return out;
    }

    private static String fold(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase();
        return normalized.replace('_', ' ').replace('-', ' ').replaceAll("[^a-z0-9/ ]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static boolean flag(String envName) {
        String env = System.getenv(envName);
        return env != null && List.of("1", "true", "yes", "on").contains(env.trim().toLowerCase());
    }

    private static void writeCsv(Path path, List<Map<String, Object>> rows) throws Exception {
        List<String> columns = List.of(
                "query",
                "latency_ms",
                "schema_compliance",
                "concept_accuracy",
                "geo_accuracy",
                "source_family_routing_accuracy",
                "required_concepts",
                "measure_types",
                "geographies",
                "geo_memberships",
                "catalog_families",
                "preferred_sources",
                "clarification_required");
        List<String> lines = new ArrayList<>();
        lines.add(String.join(",", columns));
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String column : columns) {
                cells.add(csv(row.get(column)));
            }
            lines.add(String.join(",", cells));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static String csv(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw);
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private record Case(String query, List<String> conceptNeedles, List<String> expectedGeos, List<String> familyNeedles) {}
}
