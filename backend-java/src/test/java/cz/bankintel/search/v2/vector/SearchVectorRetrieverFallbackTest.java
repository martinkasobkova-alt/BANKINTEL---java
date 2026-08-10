package cz.bankintel.search.v2.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.search.v2.normalization.SearchV2CandidateNormalizer;
import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarIndex;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchVectorRetrieverFallbackTest {

    @Test
    void disabledFeatureFlagReturnsEmptyFallbackWithoutOpeningIndex() {
        SearchVectorProperties properties = mock(SearchVectorProperties.class);
        SearchVectorIndex index = mock(SearchVectorIndex.class);
        when(properties.enabled()).thenReturn(false);
        when(properties.modelId()).thenReturn("test-model");
        SearchVectorRetriever retriever = retriever(properties, index, mock(EmbeddingProvider.class));

        var result = retriever.retrieve("query", List.of("eurostat"));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.enabled()).isFalse();
        assertThat(result.status()).isEqualTo("disabled");
        verify(index, never()).available();
    }

    @Test
    void unavailableModelReturnsEmptyFallbackWithoutEmbeddingQuery() throws Exception {
        SearchVectorProperties properties = mock(SearchVectorProperties.class);
        SearchVectorIndex index = mock(SearchVectorIndex.class);
        EmbeddingProvider provider = mock(EmbeddingProvider.class);
        when(properties.enabled()).thenReturn(true);
        when(properties.modelId()).thenReturn("test-model");
        when(index.available()).thenReturn(true);
        when(provider.available()).thenReturn(false);
        when(provider.unavailableReason()).thenReturn("model_unavailable");
        SearchVectorRetriever retriever = retriever(properties, index, provider);

        var result = retriever.retrieve("query", List.of("eurostat"));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.enabled()).isTrue();
        assertThat(result.available()).isFalse();
        assertThat(result.status()).isEqualTo("model_unavailable");
        verify(provider, never()).embedQuery("query");
    }

    private static SearchVectorRetriever retriever(
            SearchVectorProperties properties, SearchVectorIndex index, EmbeddingProvider provider) {
        return new SearchVectorRetriever(
                properties,
                provider,
                mock(VectorDocumentBuilder.class),
                index,
                mock(SearchCatalogSidecarIndex.class),
                mock(SearchV2CandidateNormalizer.class));
    }
}
