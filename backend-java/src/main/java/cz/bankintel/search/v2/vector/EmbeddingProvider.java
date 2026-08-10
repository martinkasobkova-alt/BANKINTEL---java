package cz.bankintel.search.v2.vector;

import java.util.List;

public interface EmbeddingProvider extends AutoCloseable {

    String modelId();

    int dimensions();

    boolean available();

    String unavailableReason();

    float[] embedQuery(String text);

    default float[] embedDocument(String text) {
        return embedDocuments(List.of(text)).getFirst();
    }

    List<float[]> embedDocuments(List<String> texts);

    @Override
    default void close() {}
}
