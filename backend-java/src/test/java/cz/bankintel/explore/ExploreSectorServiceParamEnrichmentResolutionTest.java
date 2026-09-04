package cz.bankintel.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.service.research.WebResearchService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * Eurostat/IMF/ECB liší se od ARAD/ČSÚ/OECD4/World Bank v {@link
 * ExploreSectorService#resolveOpaqueSourceIndicators}: jejich dataset_id od LLM je často
 * uhodnutelné (veřejně známé kódy jako Eurostat "sts_inpr_m"), takže se nezahazuje automaticky -
 * nejdřív se zkusí přesné dohledání podle něj, a jen když selže, padne se na dohledání podle
 * indicator_name. Konektor navíc na chybějící query_params nespadne tvrdě, takže na úplné
 * selhání dohledání se řádek vrátí beze změny místo aby se zahodil.
 */
class ExploreSectorServiceParamEnrichmentResolutionTest {

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
    void eurostatIndicatorWithRealDatasetIdKeepsItAndOnlyAddsQueryParams() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        Map<String, Object> queryParams = Map.of("nace_r2", "C29", "unit", "I15", "s_adj", "SCA");
        when(indexStore.lookupRow(eq("eurostat"), eq("sts_inpr_m")))
                .thenReturn(Optional.of(Map.of("set_id", "sts_inpr_m", "query_params", queryParams)));
        ExploreSectorService service = serviceWith(indexStore);
        List<Map<String, Object>> rows = List.of(new java.util.LinkedHashMap<>(Map.of(
                "source", "eurostat",
                "dataset_id", "sts_inpr_m",
                "indicator_name", "Industrial production")));

        List<Map<String, Object>> resolved = service.resolveOpaqueSourceIndicators(rows);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().get("dataset_id")).isEqualTo("sts_inpr_m");
        assertThat(resolved.getFirst().get("query_params")).isEqualTo(queryParams);
        org.mockito.Mockito.verify(indexStore, org.mockito.Mockito.never())
                .searchSource(eq("eurostat"), org.mockito.ArgumentMatchers.anyString(), anyInt());
    }

    @Test
    void imfIndicatorWithFabricatedDatasetIdFallsBackToNameSearch() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        when(indexStore.lookupRow(eq("imf"), eq("IMF_MADE_UP_CODE"))).thenReturn(Optional.empty());
        Map<String, Object> queryParams = Map.of("imf_country", "CZ", "imf_flow", "PCPS");
        when(indexStore.searchSource(eq("imf"), eq("Consumer prices"), anyInt()))
                .thenReturn(List.of(Map.of(
                        "set_id", "PCPS.CZ",
                        "indicator_name", "Consumer prices",
                        "query_params", queryParams)));
        ExploreSectorService service = serviceWith(indexStore);
        List<Map<String, Object>> rows = List.of(new java.util.LinkedHashMap<>(Map.of(
                "source", "imf",
                "dataset_id", "IMF_MADE_UP_CODE",
                "indicator_name", "Consumer prices")));

        List<Map<String, Object>> resolved = service.resolveOpaqueSourceIndicators(rows);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().get("dataset_id")).isEqualTo("PCPS.CZ");
        assertThat(resolved.getFirst().get("query_params")).isEqualTo(queryParams);
    }

    @Test
    void ecbIndicatorThatDoesNotResolveEitherWayIsPassedThroughUnchangedNotDropped() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        when(indexStore.lookupRow(eq("ecb2"), eq("EXR.M.USD.EUR.SP00.A"))).thenReturn(Optional.empty());
        when(indexStore.searchSource(eq("ecb2"), eq("Neexistující ECB řada"), anyInt())).thenReturn(List.of());
        ExploreSectorService service = serviceWith(indexStore);
        Map<String, Object> row = new java.util.LinkedHashMap<>(Map.of(
                "source", "ecb2",
                "dataset_id", "EXR.M.USD.EUR.SP00.A",
                "indicator_name", "Neexistující ECB řada"));
        List<Map<String, Object>> rows = List.of(row);

        List<Map<String, Object>> resolved = service.resolveOpaqueSourceIndicators(rows);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst()).isEqualTo(row);
    }

    @Test
    void ecbAliasNormalizesToEcb2ForLookup() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        Map<String, Object> queryParams = Map.of("ecb_country", "CZ");
        when(indexStore.lookupRow(eq("ecb2"), eq("BSI.M.CZ.N.A")))
                .thenReturn(Optional.of(Map.of("set_id", "BSI.M.CZ.N.A", "query_params", queryParams)));
        ExploreSectorService service = serviceWith(indexStore);
        List<Map<String, Object>> rows = List.of(new java.util.LinkedHashMap<>(Map.of(
                "source", "ecb",
                "dataset_id", "BSI.M.CZ.N.A",
                "indicator_name", "Money supply")));

        List<Map<String, Object>> resolved = service.resolveOpaqueSourceIndicators(rows);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().get("query_params")).isEqualTo(queryParams);
    }
}
