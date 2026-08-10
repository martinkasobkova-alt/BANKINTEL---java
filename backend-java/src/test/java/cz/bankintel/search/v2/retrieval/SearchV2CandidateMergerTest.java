package cz.bankintel.search.v2.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import cz.bankintel.search.v2.normalization.SearchV2Deduplicator;
import cz.bankintel.search.v2.schema.SearchCandidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2CandidateMergerTest {

    private final SearchV2CandidateMerger merger = new SearchV2CandidateMerger(new SearchV2Deduplicator());

    @Test
    void balancesCandidatesAcrossSourcesBeforeApplyingLimit() {
        List<SearchCandidate> merged = merger.merge(
                List.of(
                        candidate("arad", "a1"),
                        candidate("arad", "a2"),
                        candidate("arad", "a3"),
                        candidate("ecb2", "e1"),
                        candidate("ecb2", "e2")),
                4);

        assertThat(merged).extracting(SearchCandidate::source).containsExactly("arad", "ecb2", "arad", "ecb2");
    }

    private static SearchCandidate candidate(String source, String id) {
        return new SearchCandidate(
                source + ":" + id,
                id,
                id,
                "",
                source,
                "",
                "",
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                "",
                1,
                "q",
                List.of(),
                Map.of());
    }
}
