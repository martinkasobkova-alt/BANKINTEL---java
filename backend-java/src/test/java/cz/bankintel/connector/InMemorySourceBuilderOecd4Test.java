package cz.bankintel.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import cz.bankintel.sources.ecb.EcbCuratedCatalog;
import cz.bankintel.sources.eurostat.EurostatDimensionService;
import cz.bankintel.sources.oecd4.Oecd4BrowseService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InMemorySourceBuilderOecd4Test {

    @Mock
    private EcbCuratedCatalog ecbCuratedCatalog;

    @Mock
    private EurostatDimensionService eurostatDimensionService;

    @Mock
    private Oecd4BrowseService oecd4BrowseService;

    private InMemorySourceBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new InMemorySourceBuilder(ecbCuratedCatalog, eurostatDimensionService, oecd4BrowseService);
    }

    @Test
    void buildOecd_usesOfflineMirrorForOecd4StructuredSetId() {
        Map<String, Object> source = builder.build("oecd", Map.of("set_id", "OECD4|housing_prices|CZE|RHP|A"));

        assertTrue(Boolean.TRUE.equals(source.get("oecd4_offline")));
        assertEquals("oecd4://local", source.get("base_url"));
        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) source.get("query_params");
        assertEquals("oecd4_offline", qp.get("oecd_api_mode"));
        assertEquals("housing_prices", qp.get("oecd4_key"));
        assertEquals("CZE", qp.get("oecd4_ref_area"));
        assertEquals("RHP", qp.get("oecd4_measure"));
        assertEquals("A", qp.get("freq"));
    }

    @Test
    void buildOecd_usesOfflineMirrorForIndexedOecd4QueryParams() {
        Map<String, Object> source = builder.build(
                "oecd",
                Map.of(
                        "set_id",
                        "housing_prices/CZE/RHP/_/A",
                        "query_params",
                        Map.of(
                                "provider",
                                "oecd4",
                                "oecd_api_mode",
                                "oecd4_offline",
                                "oecd4_key",
                                "housing_prices",
                                "oecd4_ref_area",
                                "CZE",
                                "oecd4_measure",
                                "RHP",
                                "freq",
                                "A")));

        assertTrue(Boolean.TRUE.equals(source.get("oecd4_offline")));
        assertEquals("housing_prices/CZE/RHP/_/A", source.get("set_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) source.get("query_params");
        assertEquals("RHP", qp.get("oecd4_measure"));
    }

    @Test
    void buildOecd_dimensionFilterOverridesOecd4SetIdRefArea() {
        Map<String, Object> source = builder.build(
                "oecd",
                Map.of(
                        "set_id",
                        "OECD4|housing_prices|CZE|RHP|A",
                        "dimension_filters",
                        Map.of("REF_AREA", List.of("AT", "DE"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) source.get("query_params");
        assertEquals("AUT+DEU", qp.get("oecd4_ref_area"));
        assertEquals("AUT+DEU", qp.get("REF_AREA"));
    }

    @Test
    void buildOecd_dimensionFilterRewritesLegacyLeadingGeoFilter() {
        Map<String, Object> source = builder.build(
                "oecd",
                Map.of(
                        "set_id",
                        "housing_prices/CZE/RHP/_/A",
                        "dimension_filters",
                        Map.of("REF_AREA", List.of("AT", "DE"))));

        assertEquals("/SDMX-JSON/data/housing_prices/AUT+DEU/RHP/_/A/all", source.get("endpoint"));
    }

    // --- slash-format offline-mirror routing fix (2026-07-30) ---

    @Test
    void buildOecd_routesSlashFormatSetIdToOfflineMirrorWhenLocalSnapshotExists() {
        when(oecd4BrowseService.hasOfflineSnapshot("economic_outlook_117")).thenReturn(true);

        Map<String, Object> source =
                builder.build("oecd", Map.of("set_id", "economic_outlook_117/CZE/GDP_USD/_/A"));

        assertTrue(Boolean.TRUE.equals(source.get("oecd4_offline")));
        assertEquals("oecd4://local", source.get("base_url"));
        assertEquals("economic_outlook_117/CZE/GDP_USD/_/A", source.get("set_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) source.get("query_params");
        assertEquals("economic_outlook_117", qp.get("oecd4_key"));
        assertEquals("CZE", qp.get("oecd4_ref_area"));
        assertEquals("GDP_USD", qp.get("oecd4_measure"));
        assertEquals("A", qp.get("freq"));
    }

    @Test
    void buildOecd_slashFormatSetIdFallsBackToLegacyWhenNoLocalSnapshotExists() {
        when(oecd4BrowseService.hasOfflineSnapshot("some_unmirrored_dataset")).thenReturn(false);

        Map<String, Object> source =
                builder.build("oecd", Map.of("set_id", "some_unmirrored_dataset/CZE/XYZ/_/A"));

        assertEquals("https://stats.oecd.org", source.get("base_url"));
        assertEquals("/SDMX-JSON/data/some_unmirrored_dataset/CZE/XYZ/_/A/all", source.get("endpoint"));
    }

    @Test
    void buildOecd_dimensionFilterOverridesRefAreaForSlashFormatOfflineRouting() {
        when(oecd4BrowseService.hasOfflineSnapshot("housing_prices")).thenReturn(true);

        Map<String, Object> source = builder.build(
                "oecd",
                Map.of(
                        "set_id",
                        "housing_prices/CZE/RHP/_/A",
                        "dimension_filters",
                        Map.of("REF_AREA", List.of("AT", "DE"))));

        assertTrue(Boolean.TRUE.equals(source.get("oecd4_offline")));
        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) source.get("query_params");
        assertEquals("AUT+DEU", qp.get("oecd4_ref_area"));
        assertEquals("AUT+DEU", qp.get("REF_AREA"));
    }

    @Test
    void buildOecd_slashFormatWithTooFewSegmentsFallsBackToLegacy() {
        Map<String, Object> source = builder.build("oecd", Map.of("set_id", "housing_prices/CZE/RHP"));

        assertEquals("https://stats.oecd.org", source.get("base_url"));
    }
}
