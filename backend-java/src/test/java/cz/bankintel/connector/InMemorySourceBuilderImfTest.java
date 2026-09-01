package cz.bankintel.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.bankintel.search.CatalogSearchMetadataSidecar;
import cz.bankintel.sources.ecb.EcbCuratedCatalog;
import cz.bankintel.sources.eurostat.EurostatDimensionService;
import cz.bankintel.sources.oecd4.Oecd4BrowseService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InMemorySourceBuilderImfTest {

    @Mock
    private EcbCuratedCatalog ecbCuratedCatalog;

    @Mock
    private EurostatDimensionService eurostatDimensionService;

    @Mock
    private Oecd4BrowseService oecd4BrowseService;

    @Mock
    private CatalogSearchMetadataSidecar metadataSidecar;

    private InMemorySourceBuilder builder;

    @BeforeEach
    void setUp() {
        System.setProperty("IMF_API_KEY", "test-key");
        builder = new InMemorySourceBuilder(ecbCuratedCatalog, eurostatDimensionService, oecd4BrowseService, metadataSidecar);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("IMF_API_KEY");
    }

    @Test
    void buildImf_dimensionCountryFilterOverridesCountryPartOfSdmxKey() {
        Map<String, Object> source = builder.build(
                "imf",
                Map.of(
                        "set_id",
                        "IMF|IMF.RES|WEO|9.0.0|DEU.PCPIEPCH",
                        "dimension_filters",
                        Map.of("COUNTRY", List.of("AT", "NO"))));

        assertTrue(String.valueOf(source.get("endpoint")).endsWith("/AUT+NOR.PCPIEPCH"));
        assertEquals("AUT+NOR.PCPIEPCH", source.get("imf_sdmx_key"));
        assertEquals("AUT+NOR", source.get("imf_country"));
    }
}
