package cz.bankintel.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.service.research.WebResearchService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * Živě zjištěno v Manager Exploreru: LLM u ARAD navrhne pro „2T repo sazba ČNB" dataset_id
 * „arad_repo_rate" - vymyšlený slug, který v katalogu neexistuje (skutečné ID je set_id "1119" +
 * indicator_id "SFTP01M11", nic co by šlo z názvu uhodnout). Fetch pak vždy selže. Tenhle test
 * ověřuje opravu: pro zdroje bez veřejně známé konvence ID (arad, csu) se navržený dataset_id
 * ignoruje a řada se dohledá v katalogu podle indicator_name; nenajde-li se nic, řada se vynechá
 * místo aby prošla dál s garantovaně nefunkčním ID.
 */
class ExploreSectorServiceOpaqueSourceResolutionTest {

    private static ExploreSectorService serviceWith(CatalogIndexStore indexStore) {
        return new ExploreSectorService(
                mock(ExploreGeoCatalog.class),
                mock(ExploreGeoResolver.class),
                mock(ExploreQueryUnderstandingService.class),
                mock(OpenAiClient.class),
                new ObjectMapper(),
                mock(ExploreDiscoveryService.class),
                mock(ExplorePresetPreviewService.class),
                mock(ExploreDiscoveryCache.class),
                mock(Environment.class),
                mock(WebResearchService.class),
                indexStore);
    }

    @Test
    void aradIndicatorWithFabricatedIdGetsResolvedToRealSetId() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        when(indexStore.searchSource(eq("arad"), eq("2T repo sazba ČNB"), anyInt()))
                .thenReturn(List.of(Map.of(
                        "set_id", "1119",
                        "indicator_id", "SFTP01M11",
                        "indicator_name", "2T repo sazba:Měsíční, Úrokové sazby ČNB, ke konci měsíce")));
        ExploreSectorService service = serviceWith(indexStore);
        List<Map<String, Object>> rows = List.of(new java.util.LinkedHashMap<>(Map.of(
                "source", "arad",
                "dataset_id", "arad_repo_rate",
                "indicator_name", "2T repo sazba ČNB")));

        List<Map<String, Object>> resolved = service.resolveOpaqueSourceIndicators(rows);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().get("dataset_id")).isEqualTo("1119");
        assertThat(resolved.getFirst().get("set_id")).isEqualTo("1119");
    }

    @Test
    void aradIndicatorThatDoesNotResolveIsDroppedNotPassedThroughBroken() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        when(indexStore.searchSource(eq("arad"), eq("Zcela vymyšlený ukazatel"), anyInt()))
                .thenReturn(List.of());
        ExploreSectorService service = serviceWith(indexStore);
        List<Map<String, Object>> rows = List.of(new java.util.LinkedHashMap<>(Map.of(
                "source", "arad",
                "dataset_id", "arad_neexistujici_radu",
                "indicator_name", "Zcela vymyšlený ukazatel")));

        List<Map<String, Object>> resolved = service.resolveOpaqueSourceIndicators(rows);

        assertThat(resolved).isEmpty();
    }

    /**
     * Živě zjištěno PO opravě: 3 z 5 ARAD kandidátů se vyřešily správně, ale 2 z 5 pořád selhaly
     * s ID jako „arad_repo_rate" - tentokrát ne z LLM, ale z {@code CatalogIndexStore}'s vlastního
     * „sidecar rescue": když FTS nenajde shodu, sidecar vrátí náhradní řádek se svým vlastním
     * klíčem jako `set_id`, a ten klíč může být STEJNÝ druh nefunkčního slugu (`AradSeriesIdentity
     * .parse` ho nerozparsuje, protože nemá dvojtečku, takže projde beze změny místo skutečného
     * čísla). Bez týhle dodatečné kontroly `resolveAgainstCatalog` takový sidecar-rescue výsledek
     * přijal jako „vyřešeno", jen vyměnil jeden vymyšlený ARAD ID za jiný.
     */
    @Test
    void aradSidecarRescuePlaceholderIsTreatedAsUnresolvedNotAsAFix() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        when(indexStore.searchSource(eq("arad"), eq("Diskontní sazba ČNB"), anyInt()))
                .thenReturn(List.of(Map.of(
                        "set_id", "arad_discount_rate",
                        "indicator_name", "Diskontní sazba ČNB",
                        "_sidecar_metadata_only", true)));
        ExploreSectorService service = serviceWith(indexStore);
        List<Map<String, Object>> rows = List.of(new java.util.LinkedHashMap<>(Map.of(
                "source", "arad",
                "dataset_id", "arad_discount_rate",
                "indicator_name", "Diskontní sazba ČNB")));

        List<Map<String, Object>> resolved = service.resolveOpaqueSourceIndicators(rows);

        assertThat(resolved).isEmpty();
    }

    /**
     * Živě ověřeno v katalogovém indexu: OECD4 řady mají query_params jako
     * {@code {"oecd4_measure":"CGFL","ref_area":"CHL","freq":"A",...}} - bez nich
     * {@link cz.bankintel.explore.manager.fetch.Oecd4ManagerFetch} vždy spadne na natvrdo
     * nastavený fallback "GDPV_ANNPCT" (růst HDP) bez ohledu na to, na co se uživatel ptal.
     */
    @Test
    void oecd4IndicatorGetsResolvedWithRealQueryParamsNotJustSetId() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        Map<String, Object> queryParams = Map.of("oecd4_measure", "CGFL", "ref_area", "CHL", "freq", "A");
        when(indexStore.searchSource(eq("oecd4"), eq("Government financial liabilities"), anyInt()))
                .thenReturn(List.of(Map.of(
                        "set_id", "economic_outlook_118/CHL/CGFL/_/A",
                        "indicator_name", "Government financial liabilities",
                        "query_params", queryParams)));
        ExploreSectorService service = serviceWith(indexStore);
        List<Map<String, Object>> rows = List.of(new java.util.LinkedHashMap<>(Map.of(
                "source", "oecd4",
                "dataset_id", "GDPV_ANNPCT",
                "indicator_name", "Government financial liabilities")));

        List<Map<String, Object>> resolved = service.resolveOpaqueSourceIndicators(rows);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().get("set_id")).isEqualTo("economic_outlook_118/CHL/CGFL/_/A");
        assertThat(resolved.getFirst().get("query_params")).isEqualTo(queryParams);
    }

    /**
     * Živě ověřeno v katalogovém indexu: World Bank Data360 řady mají query_params jako
     * {@code {"DATABASE_ID":"IMF_BOP","INDICATOR":"...","skip":"0"}} - bez DATABASE_ID
     * {@link cz.bankintel.connector.Data360Connector} vždy vrátí HTTP 400.
     */
    @Test
    void data360IndicatorGetsResolvedWithRealQueryParams() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        Map<String, Object> queryParams = Map.of("DATABASE_ID", "IMF_BOP", "INDICATOR", "IMF_BOP_X", "skip", "0");
        when(indexStore.searchSource(eq("data360"), eq("Current account balance"), anyInt()))
                .thenReturn(List.of(Map.of(
                        "set_id", "IMF_BOP|IMF_BOP_X",
                        "indicator_name", "Current account balance",
                        "query_params", queryParams)));
        ExploreSectorService service = serviceWith(indexStore);
        List<Map<String, Object>> rows = List.of(new java.util.LinkedHashMap<>(Map.of(
                "source", "data360",
                "dataset_id", "BN.CAB.XOKA.CD",
                "indicator_name", "Current account balance")));

        List<Map<String, Object>> resolved = service.resolveOpaqueSourceIndicators(rows);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().get("query_params")).isEqualTo(queryParams);
    }

    /**
     * Živě ověřeno přímo v classic_catalog_search.sqlite: klasický "worldbank" katalog má 0
     * řádků (appka ho už nepoužívá, jak potvrdila uživatelka), zatímco "data360" jich má 259.
     * Když LLM přesto navrhne source "worldbank", dohledání proto musí hledat pod "data360",
     * jinak {@code indexStore.searchSource("worldbank", ...)} vždy vrátí prázdno a řada se
     * zbytečně zahodí, přestože v katalogu reálně existuje.
     */
    @Test
    void worldbankSourceIsResolvedAgainstTheData360CatalogNamespace() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        Map<String, Object> queryParams = Map.of("DATABASE_ID", "WB_WDI", "INDICATOR", "EG_ELC_FOSL_ZS", "skip", "0");
        when(indexStore.searchSource(eq("data360"), eq("Electricity from fossil fuels"), anyInt()))
                .thenReturn(List.of(Map.of(
                        "set_id", "WB_WDI|EG_ELC_FOSL_ZS",
                        "indicator_name", "Electricity from fossil fuels",
                        "query_params", queryParams)));
        ExploreSectorService service = serviceWith(indexStore);
        List<Map<String, Object>> rows = List.of(new java.util.LinkedHashMap<>(Map.of(
                "source", "worldbank",
                "dataset_id", "EG.ELC.FOSL.ZS",
                "indicator_name", "Electricity from fossil fuels")));

        List<Map<String, Object>> resolved = service.resolveOpaqueSourceIndicators(rows);

        assertThat(resolved).hasSize(1);
        // Zdroj se přepíše na "data360" - pod tím se doopravdy hledalo a odtud pocházejí
        // dataset_id/query_params, takže downstream routing (connectorSourceType) i katalogové
        // popisky musí sedět na "data360", ne na nálepce "worldbank".
        assertThat(resolved.getFirst().get("source")).isEqualTo("data360");
        assertThat(resolved.getFirst().get("set_id")).isEqualTo("WB_WDI|EG_ELC_FOSL_ZS");
        assertThat(resolved.getFirst().get("query_params")).isEqualTo(queryParams);
        verify(indexStore, never()).searchSource(eq("worldbank"), anyString(), anyInt());
    }

    @Test
    void csuIndicatorAlsoGetsResolvedAgainstCatalog() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        when(indexStore.searchSource(eq("csu"), eq("Stavební produkce"), anyInt()))
                .thenReturn(List.of(Map.of("set_id", "STA01T1", "indicator_name", "Stavební produkce")));
        ExploreSectorService service = serviceWith(indexStore);
        List<Map<String, Object>> rows = List.of(new java.util.LinkedHashMap<>(Map.of(
                "source", "csu",
                "dataset_id", "csu_stavebni_produkce_guess",
                "indicator_name", "Stavební produkce")));

        List<Map<String, Object>> resolved = service.resolveOpaqueSourceIndicators(rows);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().get("dataset_id")).isEqualTo("STA01T1");
    }

    /**
     * "eurostat"/"imf"/"ecb2" NEJSOU v tomhle seznamu - ty mají vlastní, jemnější dohledávací
     * chování (viz {@link ExploreSectorServiceParamEnrichmentResolutionTest}), které katalog
     * naopak volá vždy, aby doplnilo query_params.
     */
    @Test
    void nonOpaqueSourcesPassThroughUntouchedWithoutCallingCatalog() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        ExploreSectorService service = serviceWith(indexStore);
        List<Map<String, Object>> rows = List.of(
                Map.of("source", "fred", "dataset_id", "FPCPITOTLZGCZE", "indicator_name", "Inflation"),
                Map.of("source", "bis", "dataset_id", "WS_CBPOL", "indicator_name", "Policy rate"));

        List<Map<String, Object>> resolved = service.resolveOpaqueSourceIndicators(rows);

        assertThat(resolved).isEqualTo(rows);
        org.mockito.Mockito.verifyNoInteractions(indexStore);
    }
}
