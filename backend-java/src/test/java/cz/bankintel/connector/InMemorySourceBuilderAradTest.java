package cz.bankintel.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cz.bankintel.search.CatalogSearchMetadataSidecar;
import cz.bankintel.sources.ecb.EcbCuratedCatalog;
import cz.bankintel.sources.eurostat.EurostatDimensionService;
import cz.bankintel.sources.oecd4.Oecd4BrowseService;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InMemorySourceBuilderAradTest {

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
        System.setProperty("ARAD_API_KEY", "test-key");
        builder = new InMemorySourceBuilder(ecbCuratedCatalog, eurostatDimensionService, oecd4BrowseService, metadataSidecar);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("ARAD_API_KEY");
    }

    @Test
    void buildAradConvertsCompositeSeriesIdToDatasetFetchAndSelectedIndicator() {
        Map<String, Object> source = builder.build("arad", Map.of("set_id", "1013:SBBBM06931"));

        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) source.get("query_params");
        assertEquals("1013", qp.get("set_id"));
        assertEquals("1013", source.get("set_id"));
        assertEquals("1013:SBBBM06931", source.get("requested_set_id"));
        assertEquals("SBBBM06931", source.get("selected_indicator"));
    }
}
