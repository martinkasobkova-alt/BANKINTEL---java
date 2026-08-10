package cz.bankintel.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import cz.bankintel.sources.ecb.EcbCuratedCatalog;
import cz.bankintel.sources.eurostat.EurostatDimensionService;
import cz.bankintel.sources.oecd4.Oecd4BrowseService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InMemorySourceBuilderEurostatTest {

    @Mock
    private EurostatDimensionService eurostatDimensionService;

    @Mock
    private EcbCuratedCatalog ecbCuratedCatalog;

    @Mock
    private Oecd4BrowseService oecd4BrowseService;

    private InMemorySourceBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new InMemorySourceBuilder(ecbCuratedCatalog, eurostatDimensionService, oecd4BrowseService);
    }

    @Test
    void buildEurostat_usesResolvedDimensionsAndHistoryPreview() {
        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put("geo", "CZ");
        resolved.put("coicop", "CP00");
        resolved.put("unit", "RCH_M");
        resolved.put("lastTimePeriod", "1");
        when(eurostatDimensionService.resolvePreviewQueryParams(eq("prc_hicp_midx"), eq("CZ")))
                .thenReturn(resolved);

        Map<String, Object> params = Map.of("country", "CZ");
        Map<String, Object> source = builder.build("eurostat", Map.of("set_id", "prc_hicp_midx", "country", "CZ"));

        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) source.get("query_params");
        assertFalse(qp.containsKey("lastTimePeriod"));
        assertEquals("CZ", qp.get("geo"));
        assertEquals("CP00", qp.get("coicop"));
    }

    @Test
    void buildEurostat_defaultsToHistoryWhenDimensionsAlreadyPresent() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("set_id", "nama_10_gdp");
        params.put("query_params", Map.of("geo", "CZ", "unit", "CP_MEUR", "na_item", "B1GQ"));

        Map<String, Object> source = builder.build("eurostat", params);

        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) source.get("query_params");
        assertFalse(qp.containsKey("lastTimePeriod"));
    }

    @Test
    void buildEurostat_mergesDimensionFiltersIntoRemoteQueryParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("set_id", "prc_hicp_manr");
        params.put("query_params", Map.of("format", "JSON", "lang", "EN"));
        params.put("dimension_filters", Map.of(
                "geo", List.of("CZ", "AT", "NO"),
                "coicop", "CP09132"));

        Map<String, Object> source = builder.build("eurostat", params);

        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) source.get("query_params");
        assertEquals(List.of("CZ", "AT", "NO"), qp.get("geo"));
        assertEquals("CP09132", qp.get("coicop"));
        assertFalse(qp.containsKey("lastTimePeriod"));
    }

    @Test
    void buildEurostat_preservesExplicitMultiGeoWhenResolvingMissingDimensions() {
        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put("geo", "CZ");
        resolved.put("coicop", "CP00");
        resolved.put("unit", "RCH_A");
        when(eurostatDimensionService.resolvePreviewQueryParams(eq("prc_hicp_manr"), eq("CZ")))
                .thenReturn(resolved);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("set_id", "prc_hicp_manr");
        params.put("query_params", Map.of(
                "lastTimePeriod", "120",
                "geo", List.of("CZ", "AT", "NO")));

        Map<String, Object> source = builder.build("eurostat", params);

        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) source.get("query_params");
        assertEquals(List.of("CZ", "AT", "NO"), qp.get("geo"));
        assertEquals("CP00", qp.get("coicop"));
        assertEquals("RCH_A", qp.get("unit"));

        @SuppressWarnings("unchecked")
        Map<String, Object> applied = (Map<String, Object>) source.get("_preview_filters_applied");
        assertEquals(List.of("CZ", "AT", "NO"), applied.get("geo"));
        assertEquals("CP00", applied.get("coicop"));
    }
}
