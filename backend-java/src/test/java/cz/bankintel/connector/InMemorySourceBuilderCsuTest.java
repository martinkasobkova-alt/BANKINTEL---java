package cz.bankintel.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.bankintel.search.CatalogSearchMetadataSidecar;
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

    @Mock
    private CatalogSearchMetadataSidecar metadataSidecar;

    private InMemorySourceBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new InMemorySourceBuilder(ecbCuratedCatalog, eurostatDimensionService, oecd4BrowseService, metadataSidecar);
    }

    /**
     * Náhled nesmí na celý dataset čekat minuty. Naměřeno na ČSÚ CEN0101A: full-dataset POST
     * doběhne za 336 s, kdežto výběrový endpoint dá data za 0,2 s — bez stropu se panel v UI
     * tváří zaseknutě. Viz {@code CsuConnector#fullDatasetTimeout}.
     */
    @Test
    void csuPreviewBoundsHowLongItWaitsForTheFullDataset() {
        Map<String, Object> source = builder.build(
                "csu", Map.of("set_id", "CEN0101AT01", "dataset_code", "CEN0101A"));

        assertEquals("POST", source.get("method"));
        int timeoutSec = Integer.parseInt(String.valueOf(source.get("csu_full_dataset_timeout_sec")));
        assertTrue(timeoutSec > 0 && timeoutSec <= 60,
                "preview must not wait minutes for the full dataset, was " + timeoutSec + " s");
    }

    /** Bez dataset_code jde náhled rovnou na výběrový endpoint a strop neřeší. */
    @Test
    void csuPreviewWithoutDatasetCodeUsesTheSelectionEndpoint() {
        Map<String, Object> source = builder.build("csu", Map.of("set_id", "WCEN04T02"));

        assertEquals("/api/dotaz/v1/data/vybery/WCEN04T02", source.get("endpoint"));
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
