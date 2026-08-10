package cz.bankintel.search.v2.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class LocalOnnxEmbeddingProviderIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "BANKINTEL_EMBEDDING_INTEGRATION", matches = "true")
    void createsRealNormalizedMultilingualEmbeddings() {
        SearchVectorProperties properties = mock(SearchVectorProperties.class);
        Path cache = Path.of(System.getProperty("user.home"), ".cache", "bankintel", "models", "multilingual-e5-small");
        when(properties.enabled()).thenReturn(true);
        when(properties.modelId()).thenReturn(SearchVectorProperties.DEFAULT_MODEL_ID);
        when(properties.modelPath()).thenReturn(cache.resolve("model.onnx"));
        when(properties.tokenizerPath()).thenReturn(cache.resolve("tokenizer.json"));
        when(properties.modelUrl()).thenReturn(SearchVectorProperties.DEFAULT_MODEL_URL);
        when(properties.tokenizerUrl()).thenReturn(SearchVectorProperties.DEFAULT_TOKENIZER_URL);
        when(properties.batchSize()).thenReturn(4);
        when(properties.maxTokens()).thenReturn(96);

        try (LocalOnnxEmbeddingProvider provider = new LocalOnnxEmbeddingProvider(properties)) {
            float[] wagesCzech = provider.embedQuery("vývoj mezd v Rakousku");
            float[] wagesEnglish = provider.embedQuery("average wage growth in Austria");
            float[] unrelated = provider.embedQuery("crude oil price in the United States");

            assertThat(wagesCzech).hasSize(384);
            assertThat(norm(wagesCzech)).isBetween(0.999, 1.001);
            assertThat(cosine(wagesCzech, wagesEnglish)).isGreaterThan(cosine(wagesCzech, unrelated));
        }
    }

    private static double norm(float[] vector) {
        return Math.sqrt(cosineNumerator(vector, vector));
    }

    private static double cosine(float[] left, float[] right) {
        return cosineNumerator(left, right) / (norm(left) * norm(right));
    }

    private static double cosineNumerator(float[] left, float[] right) {
        double sum = 0;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            sum += left[i] * right[i];
        }
        return sum;
    }
}
