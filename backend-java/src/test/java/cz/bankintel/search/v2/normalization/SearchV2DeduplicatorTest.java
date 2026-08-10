package cz.bankintel.search.v2.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import cz.bankintel.search.v2.schema.SearchCandidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2DeduplicatorTest {

    private final SearchV2Deduplicator deduplicator = new SearchV2Deduplicator();

    @Test
    void dedupesBySourceAndSeriesIdWithoutMergingDifferentSources() {
        SearchCandidate first = candidate("eurostat", "tipsbd40", "Return on equity");
        SearchCandidate duplicate = candidate("eurostat", "tipsbd40", "Return on equity copy");
        SearchCandidate otherSource = candidate("ecb2", "tipsbd40", "Different source");

        List<SearchCandidate> out = deduplicator.dedupe(List.of(first, duplicate, otherSource), 10);

        assertThat(out).containsExactly(first, otherSource);
    }

    @Test
    void skipsCandidatesWithoutSeriesId() {
        SearchCandidate missing = candidate("fred", "", "No id");
        SearchCandidate valid = candidate("fred", "DCOILBRENTEU", "Brent");

        List<SearchCandidate> out = deduplicator.dedupe(List.of(missing, valid), 10);

        assertThat(out).containsExactly(valid);
    }

    private static SearchCandidate candidate(String source, String seriesId, String title) {
        return new SearchCandidate(
                source + ":" + seriesId,
                seriesId,
                title,
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
                1.0,
                "query",
                List.of(),
                Map.of());
    }
}
