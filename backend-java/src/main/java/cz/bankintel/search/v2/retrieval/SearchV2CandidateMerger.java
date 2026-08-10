package cz.bankintel.search.v2.retrieval;

import cz.bankintel.search.v2.normalization.SearchV2Deduplicator;
import cz.bankintel.search.v2.schema.SearchCandidate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SearchV2CandidateMerger {

    private final SearchV2Deduplicator deduplicator;

    public List<SearchCandidate> merge(List<SearchCandidate> candidates, int maxPoolSize) {
        int limit = Math.max(1, maxPoolSize);
        List<SearchCandidate> deduped = deduplicator.dedupe(candidates, Math.max(limit * 4, limit));
        Map<String, Queue<SearchCandidate>> bySource = new LinkedHashMap<>();
        for (SearchCandidate candidate : deduped) {
            bySource.computeIfAbsent(candidate.source(), ignored -> new ArrayDeque<>()).add(candidate);
        }
        List<SearchCandidate> out = new ArrayList<>();
        while (out.size() < limit && bySource.values().stream().anyMatch(queue -> !queue.isEmpty())) {
            for (Queue<SearchCandidate> queue : bySource.values()) {
                SearchCandidate next = queue.poll();
                if (next != null) {
                    out.add(next);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out;
    }
}
