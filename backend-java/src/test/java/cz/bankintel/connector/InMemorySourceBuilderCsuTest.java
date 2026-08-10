package cz.bankintel.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
class InMemorySourceBuilderCsuTest {

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
    void buildCsuPreviewUsesFullDatasetAndPreservesCatalogFilters() {
        List<Map<String, Object>> csuFilters = List.of(Map.of("field", "Uzemi", "exact", "Cesko"));

        Map<String, Object> source = builder.build(
                "csu",
                Map.of(
                        "set_id", "FIN03BANKDHM",
                        "dataset_code", "FIN03",
                        "query_params", Map.of("csu_filters", csuFilters)));

        @SuppressWarnings("unchecked")
        Map<String, Object> queryParams = (Map<String, Object>) source.get("query_params");

        assertEquals("POST", source.get("method"));
        assertEquals("/api/dotaz/v1/data/sady/FIN03/vlastni", source.get("endpoint"));
        assertEquals("1", queryParams.get("csu_full_dataset"));
        assertEquals(csuFilters, queryParams.get("csu_filters"));
    }
}
