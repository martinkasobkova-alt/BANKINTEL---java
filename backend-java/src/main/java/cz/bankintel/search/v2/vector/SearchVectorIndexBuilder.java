package cz.bankintel.search.v2.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarDocument;
import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class SearchVectorIndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(SearchVectorIndexBuilder.class);
    private static final String SCHEMA_VERSION = "search-vector-v1";

    private final SearchVectorProperties properties;
    private final EmbeddingProvider embeddingProvider;
    private final VectorDocumentBuilder documentBuilder;
    private final SearchCatalogSidecarIndex sidecarIndex;
    private final SearchVectorIndex vectorIndex;
    private final ObjectMapper objectMapper;

    public SearchVectorIndexBuilder(
            SearchVectorProperties properties,
            EmbeddingProvider embeddingProvider,
            VectorDocumentBuilder documentBuilder,
            SearchCatalogSidecarIndex sidecarIndex,
            SearchVectorIndex vectorIndex,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.embeddingProvider = embeddingProvider;
        this.documentBuilder = documentBuilder;
        this.sidecarIndex = sidecarIndex;
        this.vectorIndex = vectorIndex;
        this.objectMapper = objectMapper;
    }

    public synchronized Map<String, Object> rebuild() {
        long started = System.currentTimeMillis();
        if (!properties.enabled()) {
            return failure("vector_retrieval_disabled", started);
        }
        if (!embeddingProvider.available()) {
            return failure(embeddingProvider.unavailableReason(), started);
        }
        Path target = properties.indexPath();
        Path temporary = target.resolveSibling(target.getFileName() + ".building-" + UUID.randomUUID());
        AtomicLong documents = new AtomicLong();
        AtomicLong embeddedTexts = new AtomicLong();
        AtomicLong cacheHits = new AtomicLong();
        try {
            deleteRecursively(temporary);
            Files.createDirectories(temporary);
            IndexWriterConfig writerConfig = new IndexWriterConfig(new StandardAnalyzer());
            writerConfig.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            writerConfig.setRAMBufferSizeMB(256);
            try (SearchEmbeddingCache cache = new SearchEmbeddingCache(properties.cachePath());
                    FSDirectory directory = FSDirectory.open(temporary);
                    IndexWriter writer = new IndexWriter(directory, writerConfig)) {
                PendingBatch batch = new PendingBatch(writer, cache, documents, embeddedTexts, cacheHits);
                sidecarIndex.forEachDocument(batch::accept);
                batch.flush();
                writer.setLiveCommitData(Map.of(
                                "schema_version", SCHEMA_VERSION,
                                "model_id", embeddingProvider.modelId(),
                                "dimensions", String.valueOf(embeddingProvider.dimensions()),
                                "document_count", String.valueOf(documents.get()),
                                "built_at", Instant.now().toString())
                        .entrySet());
                writer.commit();
            }
            Map<String, Object> manifest = manifest(documents.get(), embeddedTexts.get(), cacheHits.get(), started);
            Files.writeString(
                    temporary.resolve("vector-manifest.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                    StandardCharsets.UTF_8);
            activate(temporary, target);
            vectorIndex.invalidate();
            return manifest;
        } catch (Exception ex) {
            log.error("Search vector index rebuild failed", ex);
            try {
                deleteRecursively(temporary);
            } catch (Exception ignored) {
                // Preserve the original failure.
            }
            return failure(ex.getClass().getSimpleName() + ":" + ex.getMessage(), started);
        }
    }

    public Map<String, Object> status() {
        Path manifest = properties.indexPath().resolve("vector-manifest.json");
        if (!Files.isRegularFile(manifest)) {
            return Map.of(
                    "enabled", properties.enabled(),
                    "available", vectorIndex.available(),
                    "model", properties.modelId(),
                    "index_path", properties.indexPath().toString());
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(manifest.toFile(), Map.class);
            Map<String, Object> out = new LinkedHashMap<>(parsed);
            out.put("enabled", properties.enabled());
            out.put("available", vectorIndex.available());
            out.put("index_path", properties.indexPath().toString());
            return out;
        } catch (Exception ex) {
            return Map.of("enabled", properties.enabled(), "available", false, "error", ex.getMessage());
        }
    }

    private Map<String, Object> manifest(long documents, long embeddedTexts, long cacheHits, long started) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("status", "ok");
        out.put("schema_version", SCHEMA_VERSION);
        out.put("model", embeddingProvider.modelId());
        out.put("dimensions", embeddingProvider.dimensions());
        out.put("document_count", documents);
        out.put("embedded_text_count", embeddedTexts);
        out.put("embedding_cache_hits", cacheHits);
        out.put("duration_ms", System.currentTimeMillis() - started);
        out.put("built_at", Instant.now().toString());
        return out;
    }

    private static Map<String, Object> failure(String error, long started) {
        return Map.of(
                "ok", false,
                "status", "error",
                "error", error == null ? "unknown" : error,
                "duration_ms", System.currentTimeMillis() - started);
    }

    private void activate(Path temporary, Path target) throws IOException {
        vectorIndex.invalidate();
        Path backup = target.resolveSibling(target.getFileName() + ".previous");
        deleteRecursively(backup);
        if (Files.exists(target)) {
            Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            deleteRecursively(backup);
        } catch (IOException ex) {
            if (Files.exists(backup) && !Files.exists(target)) {
                Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
            }
            throw ex;
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private final class PendingBatch {
        private final IndexWriter writer;
        private final SearchEmbeddingCache cache;
        private final AtomicLong documents;
        private final AtomicLong embeddedTexts;
        private final AtomicLong cacheHits;
        private final Map<String, PendingGroup> groups = new LinkedHashMap<>();

        private PendingBatch(
                IndexWriter writer,
                SearchEmbeddingCache cache,
                AtomicLong documents,
                AtomicLong embeddedTexts,
                AtomicLong cacheHits) {
            this.writer = writer;
            this.cache = cache;
            this.documents = documents;
            this.embeddedTexts = embeddedTexts;
            this.cacheHits = cacheHits;
        }

        private void accept(SearchCatalogSidecarDocument document) {
            try {
                String text = documentBuilder.build(document);
                if (text.isBlank()) {
                    return;
                }
                String hash = sha256(text + "\n" + embeddingProvider.modelId());
                var cached = cache.get(hash, embeddingProvider.modelId(), embeddingProvider.dimensions());
                if (cached.isPresent()) {
                    addDocument(writer, document, hash, cached.get());
                    documents.incrementAndGet();
                    cacheHits.incrementAndGet();
                    return;
                }
                groups.computeIfAbsent(hash, ignored -> new PendingGroup(hash, text, new ArrayList<>()))
                        .documents()
                        .add(document);
                if (groups.size() >= properties.batchSize()) {
                    flush();
                }
            } catch (Exception ex) {
                throw new VectorBuildException(ex);
            }
        }

        private void flush() throws Exception {
            if (groups.isEmpty()) {
                return;
            }
            List<PendingGroup> pending = new ArrayList<>(groups.values());
            groups.clear();
            List<float[]> vectors = embeddingProvider.embedDocuments(
                    pending.stream().map(PendingGroup::text).toList());
            if (vectors.size() != pending.size()) {
                throw new IllegalStateException("Embedding batch size mismatch");
            }
            for (int i = 0; i < pending.size(); i++) {
                PendingGroup group = pending.get(i);
                float[] vector = vectors.get(i);
                cache.put(group.hash(), embeddingProvider.modelId(), vector);
                embeddedTexts.incrementAndGet();
                for (SearchCatalogSidecarDocument document : group.documents()) {
                    addDocument(writer, document, group.hash(), vector);
                    documents.incrementAndGet();
                }
            }
            if (documents.get() > 0 && documents.get() % 25_000 < properties.batchSize()) {
                log.info(
                        "Search vector index progress documents={} embedded_texts={} cache_hits={}",
                        documents.get(),
                        embeddedTexts.get(),
                        cacheHits.get());
            }
        }
    }

    private static void addDocument(
            IndexWriter writer, SearchCatalogSidecarDocument source, String contentHash, float[] vector)
            throws IOException {
        Document document = new Document();
        document.add(new StringField(SearchVectorIndex.FIELD_SOURCE, source.source(), Field.Store.YES));
        document.add(new StringField(SearchVectorIndex.FIELD_SERIES_ID, source.seriesId(), Field.Store.YES));
        document.add(new StringField(SearchVectorIndex.FIELD_DATASET, source.dataset(), Field.Store.YES));
        document.add(new StoredField(SearchVectorIndex.FIELD_CONTENT_HASH, contentHash));
        document.add(new KnnFloatVectorField(
                SearchVectorIndex.FIELD_VECTOR, vector, VectorSimilarityFunction.COSINE));
        writer.addDocument(document);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record PendingGroup(String hash, String text, List<SearchCatalogSidecarDocument> documents) {}

    private static final class VectorBuildException extends RuntimeException {
        private VectorBuildException(Throwable cause) {
            super(cause);
        }
    }
}
