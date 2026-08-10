package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.GeoIntentSnapshot;
import cz.bankintel.search.model.SearchPlan;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.v2.planner.SearchV2QueryPlanner;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class CatalogQueryEvalRegressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> dataDrivenLocalPlannerEval() throws IOException {
        InputStream in = CatalogQueryEvalRegressionTest.class.getResourceAsStream("/catalog/query_eval_regression.json");
        assertNotNull(in, "query_eval_regression.json must be available on the test classpath");
        JsonNode root = MAPPER.readTree(in);
        CatalogQueryPlanner planner = new CatalogQueryPlanner(mock(OpenAiClient.class), mock(SearchV2QueryPlanner.class));

        return StreamSupport.stream(root.spliterator(), false)
                .map(node -> DynamicTest.dynamicTest(
                        node.path("id").asText(node.path("query").asText()), () -> assertEvalCase(planner, node)));
    }

    private static void assertEvalCase(CatalogQueryPlanner planner, JsonNode node) {
        String query = node.path("query").asText();
        SearchPlan plan = planner.planTyped(query, List.of(), false);
        Map<String, Object> profile = plan.semanticProfile();

        assertFalse(profile.isEmpty(), "semantic profile must be present for: " + query);
        assertFalse(plan.indexProbeTerms().isEmpty(), "index probe terms must be present for: " + query);

        List<String> allowedShapes = strings(node.path("allowed_query_shapes"));
        if (!allowedShapes.isEmpty()) {
            String shape = String.valueOf(profile.get(CatalogKeys.QUERY_SHAPE));
            assertTrue(allowedShapes.contains(shape), "shape=" + shape + " allowed=" + allowedShapes + " query=" + query);
        }

        assertCountryCodes(plan.geoIntent(), strings(node.path("expected_country_codes")), query);
        assertProfileContainsAny(plan, strings(node.path("expected_profile_contains_any")), query);
        assertProfileExcludes(plan, strings(node.path("forbidden_profile_terms")), query);
        assertActiveGroupsContainAny(profile, strings(node.path("expected_active_groups_any")), query);
    }

    private static void assertCountryCodes(GeoIntentSnapshot geo, List<String> expected, String query) {
        if (expected.isEmpty()) {
            return;
        }
        List<String> actual = new ArrayList<>(geo.countryCodes());
        if (!geo.countryCode().isBlank() && !actual.contains(geo.countryCode())) {
            actual.add(geo.countryCode());
        }
        for (String code : expected) {
            assertTrue(actual.contains(code), "geo=" + geo.toMap() + " expected=" + expected + " query=" + query);
        }
    }

    private static void assertProfileContainsAny(SearchPlan plan, List<String> expected, String query) {
        if (expected.isEmpty()) {
            return;
        }
        String haystack = CatalogTextUtils.foldAscii(plan.toMap().toString());
        assertTrue(
                expected.stream().map(CatalogTextUtils::foldAscii).anyMatch(haystack::contains),
                "profile did not contain any of " + expected + " for query=" + query + " profile=" + plan.toMap());
    }

    private static void assertProfileExcludes(SearchPlan plan, List<String> forbidden, String query) {
        String haystack = CatalogTextUtils.foldAscii(plan.toMap().toString());
        for (String term : forbidden) {
            String folded = CatalogTextUtils.foldAscii(term);
            assertFalse(haystack.contains(folded), "profile leaked '" + term + "' for query=" + query);
        }
    }

    private static void assertActiveGroupsContainAny(Map<String, Object> profile, List<String> expected, String query) {
        if (expected.isEmpty()) {
            return;
        }
        List<String> active = objectStringList(profile.get(CatalogKeys.ACTIVE_GROUPS));
        assertTrue(
                expected.stream().anyMatch(active::contains),
                "active_groups=" + active + " expected any=" + expected + " query=" + query);
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> objectStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String value = String.valueOf(item).trim();
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }
}
