package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.GeoIntentSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogQuerySemanticProfileTest {

    @Test
    void profileExplainsMetricDomainQueryWithoutTopicSpecificCode() {
        Map<String, Object> profile = CatalogQuerySemanticProfile.build(
                "rentabilita bank",
                GeoIntentSnapshot.empty(),
                List.of("return on equity", "bank profitability"),
                List.of("rentabilita bank", "return equity"),
                List.of("bis", "ecb2"));

        assertEquals("metric_domain", profile.get(CatalogKeys.QUERY_SHAPE));
        assertEquals("rentabilita / bank", profile.get(CatalogKeys.TOPIC));
        assertLabelsContain(profile, CatalogKeys.METRIC_TERMS, "rentabilita");
        assertLabelsContain(profile, CatalogKeys.DOMAIN_TERMS, "bank");
        assertTrue(((List<?>) profile.get("source_hints")).size() >= 2);
    }

    @Test
    void profileKeepsOpenTopicQueriesGeneric() {
        Map<String, Object> profile = CatalogQuerySemanticProfile.build(
                "spotreba vody v municipalitach",
                GeoIntentSnapshot.empty(),
                List.of("water consumption", "municipal water"),
                List.of("spotreba vody", "water"),
                List.of("eurostat", "data360"));

        assertEquals("expanded_open_topic", profile.get(CatalogKeys.QUERY_SHAPE));
        String serialized = String.valueOf(profile).toLowerCase();
        assertFalse(serialized.contains("gross domestic product"));
        assertFalse(serialized.contains("gdp growth"));
        assertTrue(((List<?>) profile.get(CatalogKeys.QUERY_VARIANTS)).contains("water consumption"));
    }

    @Test
    void profileDoesNotTurnNullGeoIntentIntoGeoTerm() {
        GeoIntentSnapshot geo = GeoIntentSnapshot.fromDetection("spotreba vody v municipalitach");
        Map<String, Object> profile = CatalogQuerySemanticProfile.build(
                "spotreba vody v municipalitach",
                geo,
                List.of("water consumption"),
                List.of("spotreba vody"),
                List.of("eurostat", "data360"));

        assertEquals("expanded_open_topic", profile.get(CatalogKeys.QUERY_SHAPE));
        assertFalse(String.valueOf(profile).toLowerCase().contains("label=null"));
    }

    @SuppressWarnings("unchecked")
    private static void assertLabelsContain(Map<String, Object> profile, String key, String expected) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) profile.get(key);
        assertTrue(rows.stream().anyMatch(row -> expected.equals(row.get("label"))));
    }
}
