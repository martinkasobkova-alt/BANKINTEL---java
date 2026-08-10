package cz.bankintel.search.v2.retrieval;

import cz.bankintel.search.v2.schema.SearchCandidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public final class SearchCandidateFusion {

    private final SearchV2CandidateMerger candidateMerger;

    public SearchCandidateFusion(SearchV2CandidateMerger candidateMerger) {
        this.candidateMerger = candidateMerger;
    }

    public FusionResult fuse(
            List<SearchCandidate> ftsCandidates,
            List<SearchCandidate> vectorCandidates,
            int rrfK,
            int maxPoolSize) {
        Map<String, MutableCandidate> candidates = new LinkedHashMap<>();
        addLane(candidates, ftsCandidates, "fts", Math.max(1, rrfK));
        addLane(candidates, vectorCandidates, "vector", Math.max(1, rrfK));
        List<SearchCandidate> ranked = candidates.values().stream()
                .sorted(Comparator.comparingDouble(MutableCandidate::rrfScore).reversed())
                .map(MutableCandidate::toCandidate)
                .toList();
        List<SearchCandidate> balanced = candidateMerger.merge(ranked, maxPoolSize);
        long vectorOnly = candidates.values().stream().filter(value -> value.lanes.equals(Set.of("vector"))).count();
        long both = candidates.values().stream().filter(value -> value.lanes.size() > 1).count();
        return new FusionResult(balanced, vectorOnly, both, candidates.size());
    }

    private static void addLane(
            Map<String, MutableCandidate> target, List<SearchCandidate> candidates, String lane, int rrfK) {
        int rank = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (SearchCandidate candidate : candidates == null ? List.<SearchCandidate>of() : candidates) {
            String identity = identity(candidate);
            if (identity.isBlank() || !seen.add(identity)) {
                continue;
            }
            rank++;
            MutableCandidate mutable = target.computeIfAbsent(identity, ignored -> new MutableCandidate(candidate));
            mutable.lanes.add(lane);
            mutable.rrfScore += 1.0 / (rrfK + rank);
            if ("fts".equals(lane)) {
                mutable.ftsRank = rank;
                mutable.prefer(candidate);
            } else {
                mutable.vectorRank = rank;
                Map<String, Object> raw = candidate.raw() == null ? Map.of() : candidate.raw();
                mutable.vectorScore = number(raw.get("_vector_score"));
            }
        }
    }

    private static String identity(SearchCandidate candidate) {
        if (candidate == null || candidate.seriesId() == null || candidate.seriesId().isBlank()) {
            return "";
        }
        return (candidate.source() + ":" + candidate.seriesId()).toLowerCase(Locale.ROOT);
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static final class MutableCandidate {
        private SearchCandidate candidate;
        private final Set<String> lanes = new LinkedHashSet<>();
        private double rrfScore;
        private int ftsRank;
        private int vectorRank;
        private double vectorScore;

        private MutableCandidate(SearchCandidate candidate) {
            this.candidate = candidate;
        }

        private void prefer(SearchCandidate preferred) {
            candidate = preferred;
        }

        private double rrfScore() {
            return rrfScore;
        }

        private SearchCandidate toCandidate() {
            Map<String, Object> raw = new LinkedHashMap<>(candidate.raw() == null ? Map.of() : candidate.raw());
            raw.put("_retrieval_lanes", new ArrayList<>(lanes));
            raw.put("_rrf_score", rrfScore);
            raw.put("_fts_fusion_rank", ftsRank);
            raw.put("_vector_rank", vectorRank);
            raw.put("_vector_score", vectorScore);
            return new SearchCandidate(
                    candidate.candidateId(),
                    candidate.seriesId(),
                    candidate.title(),
                    candidate.description(),
                    candidate.source(),
                    candidate.dataset(),
                    candidate.geo(),
                    candidate.frequency(),
                    candidate.unit(),
                    candidate.seasonalAdjustment(),
                    candidate.concepts(),
                    candidate.tags(),
                    candidate.categoryPath(),
                    candidate.latestDate(),
                    rrfScore,
                    candidate.matchedQuery(),
                    candidate.matchedFields(),
                    raw);
        }
    }

    public record FusionResult(
            List<SearchCandidate> candidates, long vectorOnlyCount, long bothLanesCount, int fusionCandidateCount) {}
}
