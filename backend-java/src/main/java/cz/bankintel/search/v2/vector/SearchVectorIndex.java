package cz.bankintel.search.v2.vector;

import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarDocumentKey;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.springframework.stereotype.Component;

@Component
public final class SearchVectorIndex {

    static final String FIELD_VECTOR = "embedding";
    static final String FIELD_SOURCE = "source";
    static final String FIELD_SERIES_ID = "series_id";
    static final String FIELD_DATASET = "dataset";
    static final String FIELD_CONTENT_HASH = "content_hash";

    private final SearchVectorProperties properties;
    private DirectoryReader reader;
    private IndexSearcher searcher;
    private long openedRevision = Long.MIN_VALUE;

    public SearchVectorIndex(SearchVectorProperties properties) {
        this.properties = properties;
    }

    public boolean available() {
        Path path = properties.indexPath();
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (FSDirectory directory = FSDirectory.open(path)) {
            return DirectoryReader.indexExists(directory);
        } catch (Exception ignored) {
            return false;
        }
    }

    public synchronized List<VectorHit> search(float[] queryVector, List<String> sources, int limit) throws IOException {
        ensureSearcher();
        if (searcher == null || queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        Query filter = sourceFilter(sources);
        Query query = filter == null
                ? new KnnFloatVectorQuery(FIELD_VECTOR, queryVector, limit)
                : new KnnFloatVectorQuery(FIELD_VECTOR, queryVector, limit, filter);
        ScoreDoc[] hits = searcher.search(query, limit).scoreDocs;
        List<VectorHit> out = new ArrayList<>(hits.length);
        for (int i = 0; i < hits.length; i++) {
            Document document = searcher.storedFields().document(hits[i].doc);
            out.add(new VectorHit(
                    new SearchCatalogSidecarDocumentKey(
                            document.get(FIELD_SOURCE),
                            document.get(FIELD_SERIES_ID),
                            document.get(FIELD_DATASET)),
                    hits[i].score,
                    i + 1,
                    document.get(FIELD_CONTENT_HASH)));
        }
        return out;
    }

    public synchronized void invalidate() {
        closeReader();
    }

    private void ensureSearcher() throws IOException {
        Path path = properties.indexPath();
        if (!Files.isDirectory(path)) {
            closeReader();
            return;
        }
        long revision = Files.getLastModifiedTime(path).toMillis();
        if (reader != null && revision == openedRevision) {
            return;
        }
        closeReader();
        FSDirectory directory = FSDirectory.open(path);
        if (!DirectoryReader.indexExists(directory)) {
            directory.close();
            return;
        }
        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);
        openedRevision = revision;
    }

    private static Query sourceFilter(List<String> sources) {
        Set<String> normalized = (sources == null ? List.<String>of() : sources).stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.size() == 1) {
            return new org.apache.lucene.search.TermQuery(new Term(FIELD_SOURCE, normalized.iterator().next()));
        }
        return new TermInSetQuery(
                FIELD_SOURCE, normalized.stream().map(BytesRef::new).toList());
    }

    @PreDestroy
    void close() {
        closeReader();
    }

    private void closeReader() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
                // Best-effort reload/shutdown.
            }
        }
        reader = null;
        searcher = null;
        openedRevision = Long.MIN_VALUE;
    }

    public record VectorHit(SearchCatalogSidecarDocumentKey key, float score, int rank, String contentHash) {}
}
