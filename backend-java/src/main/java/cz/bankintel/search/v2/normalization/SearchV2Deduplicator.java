package cz.bankintel.search.v2.normalization;

import cz.bankintel.search.v2.schema.SearchCandidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SearchV2Deduplicator {

    public List<SearchCandidate> dedupe(List<SearchCandidate> candidates, int limit) {
        Map<String, SearchCandidate> byKey = new LinkedHashMap<>();
        for (SearchCandidate candidate : candidates == null ? List.<SearchCandidate>of() : candidates) {
            String key = (candidate.source() + ":" + candidate.seriesId()).toLowerCase(Locale.ROOT);
            if (candidate.seriesId() == null || candidate.seriesId().isBlank() || byKey.containsKey(key)) {
                continue;
            }
            byKey.put(key, candidate);
            if (byKey.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(byKey.values());
    }
}
