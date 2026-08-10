package cz.bankintel.search.v2.vector;

import cz.bankintel.search.CatalogSearchProperties;
import cz.bankintel.util.BankIntelEnvVars;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public final class SearchVectorProperties {

    static final String DEFAULT_MODEL_ID = "intfloat/multilingual-e5-small";
    static final String DEFAULT_MODEL_URL =
            "https://huggingface.co/intfloat/multilingual-e5-small/resolve/main/onnx/model.onnx";
    static final String DEFAULT_TOKENIZER_URL =
            "https://huggingface.co/intfloat/multilingual-e5-small/resolve/main/tokenizer.json";

    private final CatalogSearchProperties catalogProperties;

    public SearchVectorProperties(CatalogSearchProperties catalogProperties) {
        this.catalogProperties = catalogProperties;
    }

    public boolean enabled() {
        return truthy(value("SEARCH_VECTOR_RETRIEVAL_ENABLED", "false"));
    }

    public Path indexPath() {
        return path("SEARCH_VECTOR_INDEX_PATH", catalogProperties.sidecarDir().resolve("vector-lucene"));
    }

    public Path cachePath() {
        return path("SEARCH_VECTOR_CACHE_PATH", catalogProperties.sidecarDir().resolve("vector-embedding-cache.sqlite"));
    }

    public Path modelPath() {
        Path defaultPath = Path.of(System.getProperty("user.home"), ".cache", "bankintel", "models", "multilingual-e5-small");
        return path("SEARCH_EMBEDDING_MODEL_PATH", defaultPath.resolve("model.onnx"));
    }

    public Path tokenizerPath() {
        return path("SEARCH_EMBEDDING_TOKENIZER_PATH", modelPath().resolveSibling("tokenizer.json"));
    }

    public String modelId() {
        return value("SEARCH_EMBEDDING_MODEL", DEFAULT_MODEL_ID);
    }

    public String modelUrl() {
        return value("SEARCH_EMBEDDING_MODEL_URL", DEFAULT_MODEL_URL);
    }

    public String tokenizerUrl() {
        return value("SEARCH_EMBEDDING_TOKENIZER_URL", DEFAULT_TOKENIZER_URL);
    }

    public int topK() {
        return boundedInt("SEARCH_VECTOR_TOP_K", 60, 1, 500);
    }

    public int rrfK() {
        return boundedInt("SEARCH_VECTOR_RRF_K", 60, 1, 1_000);
    }

    public int batchSize() {
        return boundedInt("SEARCH_VECTOR_BATCH_SIZE", 24, 1, 128);
    }

    public int maxTokens() {
        return boundedInt("SEARCH_VECTOR_MAX_TOKENS", 192, 32, 512);
    }

    private Path path(String key, Path fallback) {
        String raw = BankIntelEnvVars.get(key);
        return (raw == null || raw.isBlank() ? fallback : Path.of(raw)).toAbsolutePath().normalize();
    }

    private static String value(String key, String fallback) {
        String raw = BankIntelEnvVars.get(key);
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private static int boundedInt(String key, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value(key, String.valueOf(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean truthy(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }
}
