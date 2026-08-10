package cz.bankintel.search.v2.vector;

import static org.assertj.core.api.Assertions.assertThat;

import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarDocumentKey;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchVectorRetrieverDiversificationTest {

    @Test
    void deduplicatesEquivalentDocumentsAndBalancesSources() {
        var diversified = SearchVectorRetriever.diversifyHits(
                List.of(
                        hit("ecb2", "e1", "same", 0.99f),
                        hit("ecb2", "e2", "same", 0.98f),
                        hit("ecb2", "e3", "unique-ecb", 0.97f),
                        hit("eurostat", "u1", "unique-eurostat", 0.96f),
                        hit("imf", "i1", "unique-imf", 0.95f)),
                List.of(),
                3);

        assertThat(diversified).extracting(hit -> hit.key().source())
                .containsExactly("ecb2", "eurostat", "imf");
        assertThat(diversified).extracting(SearchVectorIndex.VectorHit::rank).containsExactly(1, 2, 3);
    }

    @Test
    void explicitSingleSourceKeepsScoreOrder() {
        var diversified = SearchVectorRetriever.diversifyHits(
                List.of(
                        hit("ecb2", "e1", "one", 0.99f),
                        hit("ecb2", "e2", "two", 0.98f)),
                List.of("ecb2"),
                2);

        assertThat(diversified).extracting(hit -> hit.key().seriesId()).containsExactly("e1", "e2");
    }

    private static SearchVectorIndex.VectorHit hit(String source, String id, String hash, float score) {
        return new SearchVectorIndex.VectorHit(
                new SearchCatalogSidecarDocumentKey(source, id, "dataset"), score, 1, hash);
    }
}
