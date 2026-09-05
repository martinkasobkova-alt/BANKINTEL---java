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
class InMemorySourceBuilderData360Test {

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
        builder = new InMemorySourceBuilder(ecbCuratedCatalog, eurostatDimensionService, oecd4BrowseService, metadataSidecar);
    }

    /**
     * Plošný test náhledů přes všechny katalogy (20 vzorků na zdroj): data360 bylo jediné, kde
     * náhled běžně trval desítky sekund a 3 z 10 vzorků nedoběhly ani do 120 s. Konektor stahoval
     * až 50 stránek po 1 000 řádcích, každou s 60s limitem - tedy celý globální indikátor přes
     * všechny země a roky, přestože na graf náhledu stačí jedna stránka.
     */
    @Test
    void data360PreviewBoundsPagesAndPerPageWait() {
        Map<String, Object> source = builder.build(
                "world_bank_data360", Map.of("set_id", "IMF_BOP|IMF_BOP_BEFDDAAPI_BP6_USD"));

        int maxPages = Integer.parseInt(String.valueOf(source.get("data360_max_pages")));
        int timeoutSec = Integer.parseInt(String.valueOf(source.get("data360_timeout_sec")));
        assertTrue(maxPages >= 1 && maxPages <= 3,
                "náhled nemá stahovat celý indikátor, bylo " + maxPages + " stránek");
        assertTrue(timeoutSec > 0 && timeoutSec <= 30,
                "náhled nemá čekat na jednu stránku minutu, bylo " + timeoutSec + " s");
    }

    @Test
    void buildData360ConvertsCountryHintToIso3RefArea() {
        Map<String, Object> source = builder.build(
                "world_bank_data360",
                Map.of(
                        "set_id",
                        "WB_WDI|WB_WDI_NY_GDP_MKTP_CD",
                        "country",
                        "CZ",
                        "query_params",
                        Map.of("DATABASE_ID", "WB_WDI", "INDICATOR", "WB_WDI_NY_GDP_MKTP_CD")));

        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) source.get("query_params");
        assertEquals("CZE", qp.get("REF_AREA"));
    }

    @Test
    void buildData360NormalizesLowercaseGeoQueryParamToIso3RefArea() {
        Map<String, Object> source = builder.build(
                "world_bank_data360",
                Map.of(
                        "set_id",
                        "WB_WDI|WB_WDI_NY_GDP_MKTP_CD",
                        "query_params",
                        Map.of(
                                "DATABASE_ID",
                                "WB_WDI",
                                "INDICATOR",
                                "WB_WDI_NY_GDP_MKTP_CD",
                                "geo",
                                "cz")));

        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) source.get("query_params");
        assertEquals("CZE", qp.get("REF_AREA"));
    }

    @Test
    void buildData360NormalizesRefAreaDimensionFilterListToIso3Csv() {
        Map<String, Object> source = builder.build(
                "world_bank_data360",
                Map.of(
                        "set_id",
                        "WB_WDI|WB_WDI_FP_CPI_TOTL_ZG",
                        "dimension_filters",
                        Map.of("REF_AREA", List.of("AT", "NO"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) source.get("query_params");
        assertEquals("AUT,NOR", qp.get("REF_AREA"));
    }
}
