package cz.bankintel.search.v2.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import cz.bankintel.search.v2.geo.SearchV2GeoCompatibility;
import cz.bankintel.search.v2.normalization.SearchV2Deduplicator;
import cz.bankintel.search.v2.schema.SearchCandidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchCandidateFusionTest {

    private final SearchCandidateFusion fusion =
            new SearchCandidateFusion(new SearchV2CandidateMerger(new SearchV2Deduplicator()));

    @Test
    void vectorOnlyCandidateEntersTheSharedPool() {
        var result = fusion.fuse(
                List.of(candidate("fts", "eurostat", "fts-only", "AT", Map.of())),
                List.of(candidate("vector", "ecb2", "semantic-only", "AT", Map.of("_vector_score", 0.91))),
                60,
                20);

        assertThat(result.candidates()).extracting(SearchCandidate::seriesId).contains("semantic-only");
        assertThat(result.vectorOnlyCount()).isEqualTo(1);
    }

    @Test
    void duplicateFromFtsAndVectorIsMergedOnce() {
        var result = fusion.fuse(
                List.of(candidate("fts", "eurostat", "same", "AT", Map.of())),
                List.of(candidate("vector", "eurostat", "same", "AT", Map.of("_vector_score", 0.88))),
                60,
                20);

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.bothLanesCount()).isEqualTo(1);
        assertThat(result.candidates().getFirst().raw().get("_retrieval_lanes"))
                .isEqualTo(List.of("fts", "vector"));
    }

    @Test
    void rrfPreservesEvidenceFromBothRetrievalLanes() {
        var result = fusion.fuse(
                List.of(candidate("fts", "eurostat", "same", "AT", Map.of())),
                List.of(
                        candidate("vector", "eurostat", "other", "AT", Map.of("_vector_score", 0.95)),
                        candidate("vector", "eurostat", "same", "AT", Map.of("_vector_score", 0.90))),
                60,
                20);

        SearchCandidate shared = result.candidates().stream()
                .filter(candidate -> "same".equals(candidate.seriesId()))
                .findFirst()
                .orElseThrow();
        assertThat((double) shared.raw().get("_rrf_score")).isGreaterThan(1.0 / 60.0);
        assertThat(shared.raw().get("_vector_rank")).isEqualTo(2);
    }

    @Test
    void vectorSimilarityCannotEraseExplicitGeoConflict() {
        SearchCandidate wrongGeo = candidate(
                "vector", "eurostat", "high-similarity", "DE", Map.of("_vector_score", 0.999));
        SearchCandidate fused = fusion.fuse(List.of(), List.of(wrongGeo), 60, 20).candidates().getFirst();

        var assessment = SearchV2GeoCompatibility.assessCandidateGeo(fused, List.of("AT"), null);

        assertThat(assessment.hardConflict()).isTrue();
        assertThat(assessment.status()).isEqualTo("explicit_conflict");
    }

    private static SearchCandidate candidate(
            String lane, String source, String id, String geo, Map<String, Object> raw) {
        return new SearchCandidate(
                source + ":" + id,
                id,
                id,
                "",
                source,
                "dataset",
                geo,
                "A",
                "%",
                "",
                List.of("concept"),
                List.of(),
                List.of(),
                "",
                "fts".equals(lane) ? 10.0 : 0.0,
                "query",
                List.of(lane),
                raw);
    }
}
