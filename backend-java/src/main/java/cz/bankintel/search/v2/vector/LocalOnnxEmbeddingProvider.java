package cz.bankintel.search.v2.vector;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class LocalOnnxEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalOnnxEmbeddingProvider.class);
    private static final int EXPECTED_DIMENSIONS = 384;

    private final SearchVectorProperties properties;
    private volatile OrtSession session;
    private volatile HuggingFaceTokenizer tokenizer;
    private volatile String unavailableReason = "not_initialized";

    public LocalOnnxEmbeddingProvider(SearchVectorProperties properties) {
        this.properties = properties;
    }

    @Override
    public String modelId() {
        return properties.modelId();
    }

    @Override
    public int dimensions() {
        return EXPECTED_DIMENSIONS;
    }

    @Override
    public boolean available() {
        if (!properties.enabled()) {
            unavailableReason = "disabled";
            return false;
        }
        try {
            ensureLoaded();
            return true;
        } catch (Exception ex) {
            unavailableReason = compactMessage(ex);
            log.warn("Local embedding model is unavailable: {}", unavailableReason);
            return false;
        }
    }

    @Override
    public String unavailableReason() {
        return unavailableReason;
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(List.of(prefix("query", text))).getFirst();
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        List<String> prefixed = (texts == null ? List.<String>of() : texts).stream()
                .map(text -> prefix("passage", text))
                .toList();
        return embed(prefixed);
    }

    private List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        try {
            ensureLoaded();
            List<float[]> out = new ArrayList<>(texts.size());
            int batchSize = properties.batchSize();
            for (int from = 0; from < texts.size(); from += batchSize) {
                int to = Math.min(texts.size(), from + batchSize);
                out.addAll(runBatch(texts.subList(from, to)));
            }
            return out;
        } catch (Exception ex) {
            unavailableReason = compactMessage(ex);
            throw new IllegalStateException("Local embedding failed: " + unavailableReason, ex);
        }
    }

    private List<float[]> runBatch(List<String> texts) throws OrtException {
        List<long[]> ids = new ArrayList<>(texts.size());
        List<long[]> masks = new ArrayList<>(texts.size());
        List<long[]> types = new ArrayList<>(texts.size());
        int maxLength = 1;
        for (String text : texts) {
            Encoding encoding = tokenizer.encode(text);
            long[] encodedIds = truncate(encoding.getIds(), properties.maxTokens());
            long[] encodedMask = truncate(encoding.getAttentionMask(), encodedIds.length);
            long[] encodedTypes = truncate(encoding.getTypeIds(), encodedIds.length);
            ids.add(encodedIds);
            masks.add(encodedMask);
            types.add(encodedTypes);
            maxLength = Math.max(maxLength, encodedIds.length);
        }
        long[][] inputIds = pad(ids, maxLength);
        long[][] attentionMask = pad(masks, maxLength);
        long[][] tokenTypeIds = pad(types, maxLength);
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        try (OnnxTensor idsTensor = OnnxTensor.createTensor(environment, inputIds);
                OnnxTensor maskTensor = OnnxTensor.createTensor(environment, attentionMask);
                OnnxTensor typeTensor = OnnxTensor.createTensor(environment, tokenTypeIds)) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            SetSupport.addIfPresent(inputs, session, "input_ids", idsTensor);
            SetSupport.addIfPresent(inputs, session, "attention_mask", maskTensor);
            SetSupport.addIfPresent(inputs, session, "token_type_ids", typeTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                Object value = result.get(0).getValue();
                if (!(value instanceof float[][][] hiddenStates)) {
                    throw new IllegalStateException("Unexpected ONNX output type: " + value.getClass().getName());
                }
                List<float[]> embeddings = new ArrayList<>(hiddenStates.length);
                for (int batch = 0; batch < hiddenStates.length; batch++) {
                    embeddings.add(meanPool(hiddenStates[batch], attentionMask[batch]));
                }
                return embeddings;
            }
        }
    }

    private synchronized void ensureLoaded() throws Exception {
        if (session != null && tokenizer != null) {
            return;
        }
        if (!properties.enabled()) {
            throw new IllegalStateException("vector retrieval disabled");
        }
        downloadIfMissing(properties.modelPath(), properties.modelUrl());
        downloadIfMissing(properties.tokenizerPath(), properties.tokenizerUrl());
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        session = OrtEnvironment.getEnvironment().createSession(properties.modelPath().toString(), options);
        tokenizer = HuggingFaceTokenizer.newInstance(properties.tokenizerPath());
        unavailableReason = "";
        log.info("Local embedding model loaded model={} path={}", modelId(), properties.modelPath());
    }

    private static void downloadIfMissing(Path destination, String url) throws IOException, InterruptedException {
        if (Files.isRegularFile(destination) && Files.size(destination) > 0) {
            return;
        }
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", "BankIntel-SearchV2/1.0")
                .GET()
                .build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(temporary);
            throw new IOException("Model download returned HTTP " + response.statusCode());
        }
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String prefix(String kind, String text) {
        return kind + ": " + (text == null ? "" : text.trim());
    }

    private static long[] truncate(long[] values, int limit) {
        if (values == null || values.length == 0) {
            return new long[] {0};
        }
        return java.util.Arrays.copyOf(values, Math.min(values.length, Math.max(1, limit)));
    }

    private static long[][] pad(List<long[]> values, int length) {
        long[][] out = new long[values.size()][length];
        for (int i = 0; i < values.size(); i++) {
            System.arraycopy(values.get(i), 0, out[i], 0, Math.min(length, values.get(i).length));
        }
        return out;
    }

    private static float[] meanPool(float[][] tokens, long[] mask) {
        if (tokens.length == 0) {
            return new float[EXPECTED_DIMENSIONS];
        }
        int dimensions = tokens[0].length;
        float[] pooled = new float[dimensions];
        double count = 0;
        for (int token = 0; token < tokens.length && token < mask.length; token++) {
            if (mask[token] == 0) {
                continue;
            }
            count++;
            for (int dimension = 0; dimension < dimensions; dimension++) {
                pooled[dimension] += tokens[token][dimension];
            }
        }
        double norm = 0;
        double divisor = Math.max(1.0, count);
        for (int dimension = 0; dimension < pooled.length; dimension++) {
            pooled[dimension] /= (float) divisor;
            norm += pooled[dimension] * pooled[dimension];
        }
        norm = Math.sqrt(Math.max(norm, 1.0e-12));
        for (int dimension = 0; dimension < pooled.length; dimension++) {
            pooled[dimension] /= (float) norm;
        }
        return pooled;
    }

    private static String compactMessage(Exception ex) {
        String message = ex.getMessage();
        return ex.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ":" + message);
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
                // Best-effort shutdown.
            }
            session = null;
        }
        if (tokenizer != null) {
            tokenizer.close();
            tokenizer = null;
        }
    }

    private static final class SetSupport {
        private SetSupport() {}

        private static void addIfPresent(
                Map<String, OnnxTensor> target, OrtSession session, String name, OnnxTensor tensor) {
            if (session.getInputNames().contains(name)) {
                target.put(name, tensor);
            }
        }
    }
}
