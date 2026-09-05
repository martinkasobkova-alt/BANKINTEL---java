package cz.bankintel.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
class InMemorySourceBuilderEcbTest {

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

    @Test
    void buildEcb_refAreaDimensionFilterRewritesKnownRefAreaDimension() {
        Map<String, Object> source = builder.build(
                "ecb",
                Map.of(
                        "set_id",
                        "ICP/M.U2.N.PCCI00.3.3MM",
                        "dimension_filters",
                        Map.of("REF_AREA", List.of("AT", "DE"))));

        assertEquals("/service/data/ICP/M.AT+DE.N.PCCI00.3.3MM", source.get("endpoint"));
        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) source.get("query_params");
        assertEquals("M.AT+DE.N.PCCI00.3.3MM", qp.get("ecb_series_key"));
        assertEquals("M.AT+DE.N.PCCI00.3.3MM", qp.get("seriesKey"));
    }

    @Test
    void buildEcb_doesNotTreatExchangeRateCurrencyAsRefAreaDimension() {
        Map<String, Object> source = builder.build(
                "ecb",
                Map.of(
                        "set_id",
                        "EXR/D.USD.EUR.SP00.A",
                        "dimension_filters",
                        Map.of("REF_AREA", "DE")));

        assertEquals("/service/data/EXR/D.USD.EUR.SP00.A", source.get("endpoint"));
    }
}
