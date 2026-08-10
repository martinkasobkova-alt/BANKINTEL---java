package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogSourceRouteServiceTest {

    private static final List<String> ALL_SOURCES =
            List.of("arad", "csu", "eurostat", "ecb2", "fred", "imf", "data360", "bis", "oecd4", "commodities");

    @Mock
    private CatalogQueryPlanner queryPlanner;

    private CatalogSourceRouteService service;

    @BeforeEach
    void setUp() {
        service = new CatalogSourceRouteService(queryPlanner);
    }

    @Test
    void fullAllowedListIsReducedToOilRelevantSources() {
        when(queryPlanner.plan("cena ropy", ALL_SOURCES))
                .thenReturn(Map.of("sources", List.of("fred"), "planner", "local"));

        List<String> sources = routedSources("cena ropy");

        assertTrue(sources.contains("fred"), "expected FRED for oil query: " + sources);
        assertTrue(
                sources.contains("commodities") || sources.contains("imf") || sources.contains("data360"),
                "expected commodity-data sources for oil query: " + sources);
        assertFalse(sources.subList(0, Math.min(3, sources.size())).contains("arad"), "must not return UI order: " + sources);
    }

    @Test
    void fullAllowedListIsReducedToInflationRelevantSources() {
        when(queryPlanner.plan("inflace madarsko", ALL_SOURCES))
                .thenReturn(Map.of("sources", List.of("eurostat", "imf"), "planner", "local"));

        List<String> sources = routedSources("inflace madarsko");

        assertTrue(sources.contains("eurostat"), "expected Eurostat for EU-country inflation: " + sources);
        assertTrue(sources.contains("imf") || sources.contains("fred"), "expected macro inflation fallback: " + sources);
        assertFalse(sources.subList(0, Math.min(2, sources.size())).contains("arad"), "foreign geo must not start with ARAD: " + sources);
    }

    @SuppressWarnings("unchecked")
    private List<String> routedSources(String query) {
        Map<String, Object> result =
                service.routeSources(Map.of("q", query, "sources", ALL_SOURCES, "max_sources", 5));
        Map<String, Object> route = (Map<String, Object>) result.get("source_route");
        return (List<String>) route.get("sources");
    }
}
