package cz.bankintel.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import cz.bankintel.search.CatalogSearchMetadataSidecar;
import cz.bankintel.sources.ecb.EcbCuratedCatalog;
import cz.bankintel.sources.eurostat.EurostatDimensionService;
import cz.bankintel.sources.oecd4.Oecd4BrowseService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Složené {@code series_id} se nesmí posílat upstreamu jako kód datasetu.
 *
 * <p>Metadatový sidecar rozlišuje identitu řady od kódu datasetu — Eurostat
 * {@code prc_hicp_midx_hicp_all_items} je řada uvnitř datasetu {@code prc_hicp_midx}. Ověřeno
 * proti živému API 2026-09-01: {@code /data/prc_hicp_midx} vrací 200, celá složenina 404.
 *
 * <p>Reálný dopad: v grafu šlo přidat srovnání další země a widget místo dat ukázal
 * „Eurostat API vrátilo HTTP 404". Ta cesta ({@code ExternalCatalogChartWidgetResolver} →
 * {@code CatalogPreviewService}) ukládá {@code set_id} syrový, bez frontendové normalizace
 * v {@code catalogPreviewBody.js}, takže spolehnout se na frontend nestačí.
 */
@ExtendWith(MockitoExtension.class)
class InMemorySourceBuilderDatasetIdResolutionTest {

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
        builder = new InMemorySourceBuilder(
                ecbCuratedCatalog, eurostatDimensionService, oecd4BrowseService, metadataSidecar);
    }

    private static Map<String, Object> sidecarRecord(String datasetId) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("dataset_id", datasetId);
        return rec;
    }

    private Map<String, Object> buildEurostat(String setId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("set_id", setId);
        return builder.build("eurostat", params);
    }

    @Test
    void slozeneSeriesIdSePosleUpstreamuJakoDataset() {
        when(metadataSidecar.getSearchMetadata("eurostat", "prc_hicp_midx_hicp_all_items", ""))
                .thenReturn(sidecarRecord("prc_hicp_midx"));

        Map<String, Object> source = buildEurostat("prc_hicp_midx_hicp_all_items");

        assertEquals("/prc_hicp_midx", source.get("endpoint"),
                "upstreamu patří kód datasetu, ne identita řady");
        assertEquals("prc_hicp_midx", source.get("set_id"));
        assertEquals("prc_hicp_midx_hicp_all_items", source.get("requested_set_id"),
                "původní identita se má zachovat, ať je v odpovědi dohledatelná");
    }

    @Test
    void kdyzSidecarNicNevraciSetIdZustava() {
        when(metadataSidecar.getSearchMetadata(anyString(), anyString(), anyString())).thenReturn(null);

        Map<String, Object> source = buildEurostat("prc_hicp_midx");

        assertEquals("/prc_hicp_midx", source.get("endpoint"));
        assertNull(source.get("requested_set_id"), "beze změny se příznak nepřidává");
    }

    @Test
    void nesouvisejiciDatasetIdSeIgnoruje() {
        // Zúžení je schválně opatrné: dataset se použije, jen když je set_id jeho prefixem
        // s oddělovačem. Jinak by nedorozumění v datech tiše zaměnilo jednu řadu za jinou.
        when(metadataSidecar.getSearchMetadata("eurostat", "nama_10_gdp", ""))
                .thenReturn(sidecarRecord("uplne_jiny_dataset"));

        Map<String, Object> source = buildEurostat("nama_10_gdp");

        assertEquals("/nama_10_gdp", source.get("endpoint"));
        assertNull(source.get("requested_set_id"));
    }

    @Test
    void shodneDatasetIdNicNemeni() {
        when(metadataSidecar.getSearchMetadata("eurostat", "nama_10_gdp", ""))
                .thenReturn(sidecarRecord("nama_10_gdp"));

        Map<String, Object> source = buildEurostat("nama_10_gdp");

        assertEquals("/nama_10_gdp", source.get("endpoint"));
        assertNull(source.get("requested_set_id"));
    }

    @Test
    void ecbKlicSeNechavaBytIKdyzVypadaSlozene() {
        // EXR/D.USD.EUR.SP00.A je legitimní ECB flow+klíč, ne dataset_id + indikátor.
        // Sidecar se na něj ani nesmí ptát, natož ho zkracovat.
        lenient().when(metadataSidecar.getSearchMetadata(anyString(), anyString(), anyString()))
                .thenReturn(sidecarRecord("EXR"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("set_id", "EXR/D.USD.EUR.SP00.A");
        Map<String, Object> source = builder.build("ecb", params);

        assertNull(source.get("requested_set_id"), "ECB klíč se nesmí přepsat na dataset");
    }

    @Test
    void vypadekSidecaruNeshodiNahled() {
        when(metadataSidecar.getSearchMetadata(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("sidecar nedostupný"));

        Map<String, Object> source = buildEurostat("prc_hicp_midx");

        assertEquals("/prc_hicp_midx", source.get("endpoint"),
                "když se dataset nepodaří dohledat, náhled má proběhnout s původním set_id");
    }
}
