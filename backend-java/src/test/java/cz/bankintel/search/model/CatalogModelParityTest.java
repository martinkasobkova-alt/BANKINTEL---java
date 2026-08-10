package cz.bankintel.search.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ensures typed records serialize to the same JSON field names as legacy maps. */
class CatalogModelParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void catalogHitRoundTripPreservesApiFields() throws Exception {
        Map<String, Object> raw = Map.of("set_id", "SERIES1", "name", "Test series", "_fts_rank", -2.5);
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("set_id", "SERIES1");
        legacy.put("name", "Test series");
        legacy.put("title", "Test series");
        legacy.put("full_path", "/macro");
        legacy.put("catalog_id", "fred");
        legacy.put("catalog_label", "FRED");
        legacy.put("source_type", "fred");
        legacy.put("_search_score", 42);
        legacy.put("_metadata_score", 10);
        legacy.put("_lexical_score", 30);
        legacy.put("row", raw);

        CatalogHit hit = CatalogHit.fromMap(legacy);
        Map<String, Object> roundTrip = hit.toMap();

        assertEquals(legacy.get("set_id"), roundTrip.get("set_id"));
        assertEquals(legacy.get("_search_score"), roundTrip.get("_search_score"));
        assertEquals(legacy.get("source_type"), roundTrip.get("source_type"));
        assertFalse(MAPPER.writeValueAsString(legacy).contains("null"));
    }

    @Test
    void searchPlanToMapHasExpectedKeys() {
        SearchPlan plan = new SearchPlan(
                List.of("eurostat", "fred"),
                List.of("inflation"),
                List.of("eurostat"),
                GeoIntentSnapshot.fromDetection("inflace Cesko"),
                "",
                "CZ",
                "local");
        Map<String, Object> map = plan.toMap();
        assertEquals(List.of("eurostat", "fred"), map.get(CatalogKeys.SOURCES));
        assertEquals("local", map.get(CatalogKeys.PLANNER));
        assertEquals("CZ", map.get(CatalogKeys.COUNTRY_HINT));
    }
}
