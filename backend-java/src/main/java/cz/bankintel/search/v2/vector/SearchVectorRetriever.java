package cz.bankintel.search.v2.vector;

import cz.bankintel.search.v2.normalization.SearchV2CandidateNormalizer;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarDocument;
import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarIndex;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public final class SearchVectorRetriever {

    private final SearchVectorProperties properties;
    private final EmbeddingProvider embeddingProvider;
    private final VectorDocumentBuilder documentBuilder;
    private final SearchVectorIndex vectorIndex;
    private final SearchCatalogSidecarIndex sidecarIndex;
    private final SearchV2CandidateNormalizer normalizer;

    public SearchVectorRetriever(
            SearchVectorProperties properties,
            EmbeddingProvider embeddingProvider,
            VectorDocumentBuilder documentBuilder,
            SearchVectorIndex vectorIndex,
            SearchCatalogSidecarIndex sidecarIndex,
            SearchV2CandidateNormalizer normalizer) {
        this.properties = properties;
        this.embeddingProvider = embeddingProvider;
        this.documentBuilder = documentBuilder;
        this.vectorIndex = vectorIndex;
        this.sidecarIndex = sidecarIndex;
        this.normalizer = normalizer;
    }

    public RetrievalResult retrieve(SearchQueryPlan plan, List<String> allowedSources) {
        String queryText = documentBuilder.queryText(
                plan == null ? "" : plan.originalQuery(),
                plan == null ? List.of() : plan.primaryConcepts(),
                plan == null ? List.of() : plan.semanticSearchTerms());
        return retrieve(queryText, allowedSources);
    }

    public RetrievalResult retrieve(String queryText, List<String> allowedSources) {
        long started = System.currentTimeMillis();
        if (!properties.enabled()) {
            return unavailable("disabled", started);
        }
        if (!vectorIndex.available()) {
            return unavailable("index_unavailable", started);
        }
        if (!embeddingProvider.available()) {
            return unavailable(embeddingProvider.unavailableReason(), started);
        }
        try {
            long embeddingStarted = System.currentTimeMillis();
            float[] queryVector = embeddingProvider.embedQuery(queryText);
            long embeddingMs = System.currentTimeMillis() - embeddingStarted;
            long searchStarted = System.currentTimeMillis();
            int topK = properties.topK();
            int overfetch = Math.min(1_200, Math.max(topK, topK * 20));
            List<SearchVectorIndex.VectorHit> hits = diversifyHits(
                    vectorIndex.search(queryVector, allowedSources, overfetch), allowedSources, topK);
            long searchMs = System.currentTimeMillis() - searchStarted;
            var documents = sidecarIndex.documents(hits.stream().map(SearchVectorIndex.VectorHit::key).toList());
            List<SearchCandidate> candidates = new ArrayList<>();
            for (SearchVectorIndex.VectorHit hit : hits) {
                SearchCatalogSidecarDocument document = documents.get(hit.key());
                if (document == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>(
                        document.toSearchRow(hit.score(), queryText, List.of("vector:semantic")));
                row.put("_vector_score", hit.score());
                row.put("_vector_rank", hit.rank());
                row.put("_retrieval_lane", "vector");
                row.put("_retrieval_lanes", List.of("vector"));
                row.put("_embedding_model", embeddingProvider.modelId());
                candidates.add(normalizer.normalize(document.source(), row, queryText));
            }
            return new RetrievalResult(
                    candidates,
                    true,
                    true,
                    "ok",
                    embeddingProvider.modelId(),
                    embeddingMs,
                    searchMs,
                    System.currentTimeMillis() - started);
        } catch (Exception ex) {
            return unavailable(ex.getClass().getSimpleName() + ":" + ex.getMessage(), started);
        }
    }

    public RetrievalResult timeout(long timeoutMs) {
        return new RetrievalResult(
                List.of(), properties.enabled(), false, "timeout_ms=" + timeoutMs, properties.modelId(), 0, 0, timeoutMs);
    }

    static List<SearchVectorIndex.VectorHit> diversifyHits(
            List<SearchVectorIndex.VectorHit> hits, List<String> allowedSources, int limit) {
        int safeLimit = Math.max(1, limit);
        Set<String> seenContent = new LinkedHashSet<>();
        List<SearchVectorIndex.VectorHit> deduplicated = new ArrayList<>();
        for (SearchVectorIndex.VectorHit hit : hits == null ? List.<SearchVectorIndex.VectorHit>of() : hits) {
            String identity = hit.contentHash() == null || hit.contentHash().isBlank()
                    ? hit.key().source() + ":" + hit.key().seriesId() + ":" + hit.key().dataset()
                    : hit.contentHash();
            if (seenContent.add(identity)) {
                deduplicated.add(hit);
            }
        }
        boolean singleExplicitSource = allowedSources != null && allowedSources.size() == 1;
        List<SearchVectorIndex.VectorHit> selected = singleExplicitSource
                ? deduplicated.stream().limit(safeLimit).toList()
                : balanceSources(deduplicated, safeLimit);
        List<SearchVectorIndex.VectorHit> ranked = new ArrayList<>(selected.size());
        for (int index = 0; index < selected.size(); index++) {
            SearchVectorIndex.VectorHit hit = selected.get(index);
            ranked.add(new SearchVectorIndex.VectorHit(hit.key(), hit.score(), index + 1, hit.contentHash()));
        }
        return ranked;
    }

    private static List<SearchVectorIndex.VectorHit> balanceSources(
            List<SearchVectorIndex.VectorHit> hits, int limit) {
        Map<String, Queue<SearchVectorIndex.VectorHit>> bySource = new LinkedHashMap<>();
        for (SearchVectorIndex.VectorHit hit : hits) {
            bySource.computeIfAbsent(hit.key().source(), ignored -> new ArrayDeque<>()).add(hit);
        }
        List<SearchVectorIndex.VectorHit> out = new ArrayList<>();
        while (out.size() < limit && bySource.values().stream().anyMatch(queue -> !queue.isEmpty())) {
            for (Queue<SearchVectorIndex.VectorHit> queue : bySource.values()) {
                SearchVectorIndex.VectorHit hit = queue.poll();
                if (hit != null) {
                    out.add(hit);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    private RetrievalResult unavailable(String reason, long started) {
        return new RetrievalResult(
                List.of(),
                properties.enabled(),
                false,
                reason,
                properties.modelId(),
                0,
                0,
                System.currentTimeMillis() - started);
    }

    public record RetrievalResult(
            List<SearchCandidate> candidates,
            boolean enabled,
            boolean available,
            String status,
            String model,
            long embeddingMs,
            long searchMs,
            long latencyMs) {

        public Map<String, Object> toStat() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", "_vector");
            out.put("retrieval_lane", "vector");
            out.put("vector_enabled", enabled);
            out.put("vector_available", available);
            out.put("embedding_model", model);
            out.put("embedding_ms", embeddingMs);
            out.put("vector_search_ms", searchMs);
            out.put("count", candidates.size());
            out.put("latency_ms", latencyMs);
            out.put("ok", available);
            if (!"ok".equals(status)) {
                out.put("error", status);
            }
            return out;
        }
    }
}
