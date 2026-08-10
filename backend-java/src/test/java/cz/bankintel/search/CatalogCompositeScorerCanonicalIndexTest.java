package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogCompositeScorerCanonicalIndexTest {

    @Test
    void curatedRowScoresHigherThanLongTailMirrorRow() {
        String query = "nasdaq composite";
        List<String> needles = CatalogTextUtils.needlesFromQuery(query);

        int curated = CatalogCompositeScorer.scoreWithLikelySources(
                "fred",
                query,
                Map.of(
                        "set_id", "NASDAQCOM",
                        "name", "NASDAQ Composite",
                        "curated", true),
                needles,
                10,
                CatalogTextUtils.titleMatchScore(CatalogTextUtils.foldAscii("NASDAQ Composite"), needles),
                List.of("fred"));

        int longTail = CatalogCompositeScorer.scoreWithLikelySources(
                "fred",
                query,
                Map.of(
                        "set_id", "NASDAQNQ10HANDLL",
                        "name", "Nasdaq 100 Sep 26 Handled Long",
                        "curated", false,
                        "index_seed", "full_mirror"),
                needles,
                10,
                CatalogTextUtils.titleMatchScore(
                        CatalogTextUtils.foldAscii("Nasdaq 100 Sep 26 Handled Long"), needles),
                List.of("fred"));

        assertTrue(curated > longTail, "curated=" + curated + " longTail=" + longTail);
    }
}
