package cz.bankintel.search.v2.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class SearchVectorIndexIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "BANKINTEL_VECTOR_INDEX_INTEGRATION", matches = "true")
    void globalRecallAndExplicitSourceFilterUseTheProductionIndex() throws Exception {
        SearchVectorProperties properties = properties();
        try (LocalOnnxEmbeddingProvider provider = new LocalOnnxEmbeddingProvider(properties)) {
            SearchVectorIndex index = new SearchVectorIndex(properties);
            float[] profitability = provider.embedQuery("bank profitability return on equity of banks");

            var global = index.search(profitability, List.of(), 1_200);
            var ecbOnly = index.search(profitability, List.of("ecb2"), 30);
            assertThat(global).isNotEmpty();
            assertThat(global).anySatisfy(hit -> assertThat(hit.key().seriesId()).isEqualTo("tipsbd40"));
            assertThat(ecbOnly).isNotEmpty().allSatisfy(hit -> assertThat(hit.key().source()).isEqualTo("ecb2"));
        }
    }

    private static SearchVectorProperties properties() {
        SearchVectorProperties properties = mock(SearchVectorProperties.class);
        Path modelCache = Path.of(
                System.getProperty("user.home"), ".cache", "bankintel", "models", "multilingual-e5-small");
        when(properties.enabled()).thenReturn(true);
        when(properties.modelId()).thenReturn(SearchVectorProperties.DEFAULT_MODEL_ID);
        when(properties.modelPath()).thenReturn(modelCache.resolve("model.onnx"));
        when(properties.tokenizerPath()).thenReturn(modelCache.resolve("tokenizer.json"));
        when(properties.modelUrl()).thenReturn(SearchVectorProperties.DEFAULT_MODEL_URL);
        when(properties.tokenizerUrl()).thenReturn(SearchVectorProperties.DEFAULT_TOKENIZER_URL);
        when(properties.indexPath()).thenReturn(Path.of(
                "C:/Bankoapp-main/BankIntel-v2/data/search_v2_sidecar/vector-lucene"));
        when(properties.batchSize()).thenReturn(24);
        when(properties.maxTokens()).thenReturn(192);
        return properties;
    }
}
