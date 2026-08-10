package cz.bankintel.search.v2.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.search.CatalogCommoditySearch;
import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.v2.normalization.SearchV2CandidateNormalizer;
import cz.bankintel.search.v2.normalization.SearchV2Deduplicator;
import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarIndex;
import cz.bankintel.search.v2.vector.SearchVectorProperties;
import cz.bankintel.search.v2.vector.SearchVectorRetriever;
import cz.bankintel.sources.stocks.StockSearchService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2FtsRetrieverTest {

    @Test
    void sidecarQueriesAllIndexedSourcesOncePerVariantWithoutLegacyScan() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        SearchV2QueryExpander expander = mock(SearchV2QueryExpander.class);
        SearchCatalogSidecarIndex sidecar = mock(SearchCatalogSidecarIndex.class);
        when(sidecar.sidecarEnabled(SearchCatalogSidecarIndex.MODE_SIDECAR)).thenReturn(true);
        when(sidecar.searchGlobal(anyList(), anyString(), anyInt(), any())).thenReturn(List.of(Map.of(
                "source", "eurostat",
                "set_id", "prc_hicp_manr",
                "name", "HICP annual rate of change")));
        SearchV2CandidateMerger candidateMerger = new SearchV2CandidateMerger(new SearchV2Deduplicator());
        SearchVectorProperties vectorProperties = mock(SearchVectorProperties.class);
        when(vectorProperties.enabled()).thenReturn(false);
        when(vectorProperties.rrfK()).thenReturn(60);
        SearchVectorRetriever vectorRetriever = mock(SearchVectorRetriever.class);
        SearchVectorRetriever.RetrievalResult vectorFallback = new SearchVectorRetriever.RetrievalResult(
                List.of(), false, false, "disabled", "test-model", 0, 0, 0);
        when(vectorRetriever.retrieve(anyString(), anyList())).thenReturn(vectorFallback);
        when(vectorRetriever.timeout(anyLong())).thenReturn(vectorFallback);

        SearchV2FtsRetriever retriever = new SearchV2FtsRetriever(
                indexStore,
                mock(CatalogCommoditySearch.class),
                expander,
                new SearchV2CandidateNormalizer(),
                candidateMerger,
                new SearchCandidateFusion(candidateMerger),
                sidecar,
                vectorRetriever,
                vectorProperties,
                mock(StockSearchService.class));
        try {
            var result = retriever.retrieveQueries(
                    List.of("inflation rate"),
                    List.of("eurostat", "ecb2", "imf"),
                    5_000,
                    SearchCatalogSidecarIndex.MODE_SIDECAR);

            assertThat(result.candidates()).singleElement().satisfies(candidate -> {
                assertThat(candidate.source()).isEqualTo("eurostat");
                assertThat(candidate.seriesId()).isEqualTo("prc_hicp_manr");
            });
            verify(sidecar).searchGlobal(eq(List.of("eurostat", "ecb2", "imf")), eq("inflation rate"), eq(75), any());
            assertThat(result.queryStats())
                    .extracting(stat -> stat.get("source"))
                    .containsExactly("eurostat", "ecb2", "imf", "_vector");
            verify(sidecar, never()).search(anyString(), anyString(), anyInt());
            verify(indexStore, never()).searchSourceFtsRaw(anyString(), anyString(), anyInt());
        } finally {
            retriever.shutdown();
        }
    }
}
