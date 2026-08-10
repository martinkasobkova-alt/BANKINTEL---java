package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SemanticDecision;
import cz.bankintel.search.v2.schema.SourceRoutingDecision;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SearchV2PreviewCandidateSelector}: dedup by seriesId, explicit-geo-match
 * reservation, soft per-source cap with fallback, and the no-geography / small-limit edge cases.
 * See the preview-selector root-cause diagnosis (2026-07-30) for why this exists: a naive top-N
 * slice of {@code ranked} let one source occupy every live-preview slot for a non-covered-country
 * query, even when a correctly geo-tagged candidate from another source existed lower in rank.
 */
class SearchV2PreviewCandidateSelectorTest {

    private static SearchResult result(String seriesId, String source, String geo, double relevanceScore) {
        SearchCandidate candidate = new SearchCandidate(
                source + ":" + seriesId, seriesId, "title " + seriesId, "", source, "dataset", geo,
                "A", "", "", List.of(), List.of(), List.of(), "", 0.5, "query", List.of(), java.util.Map.of());
        SemanticDecision decision = new SemanticDecision(
                seriesId, "keep", relevanceScore, 0.38, List.of(), List.of(), "reason", "primary");
        return new SearchResult(candidate, decision, 0);
    }

    @Test
    void duplicateSeriesIdOccupiesOnlyOneSlotAndFreedSlotsGoToOtherUniqueCandidates() {
        List<SearchResult> ranked = List.of(
                result("A", "eurostat", "", 0.9),
                result("A", "eurostat", "", 0.9),
                result("A", "eurostat", "", 0.9),
                result("B", "eurostat", "", 0.8),
                result("C", "eurostat", "", 0.7),
                result("D", "eurostat", "", 0.6),
                result("E", "eurostat", "", 0.5),
                result("F", "eurostat", "", 0.4),
                result("G", "eurostat", "", 0.3));

        SearchV2PreviewCandidateSelector.Selection selection =
                SearchV2PreviewCandidateSelector.select(ranked, 8, List.of(), SourceRoutingDecision.empty());

        List<String> ids = selection.candidates().stream().map(r -> r.candidate().seriesId()).toList();
        assertThat(ids).containsExactly("A", "B", "C", "D", "E", "F", "G");
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(selection.telemetry().dedupedCount()).isEqualTo(7);
        assertThat(selection.telemetry().selectedCount()).isEqualTo(7);
    }

    @Test
    void upToHalfOfSlotsAreReservedForExplicitRequestedGeoMatchesInOriginalOrder() {
        List<SearchResult> ranked = new ArrayList<>();
        // top-ranked candidates all blank geo (e.g. multi-country aggregate catalog)
        for (int i = 0; i < 10; i++) {
            ranked.add(result("blank" + i, "eurostat", "", 0.9 - i * 0.01));
        }
        // lower-ranked candidates with an explicit RU match
        ranked.add(result("ru1", "fred", "RU", 0.5));
        ranked.add(result("ru2", "imf", "RU", 0.4));
        ranked.add(result("ru3", "fred", "RU", 0.3));
        ranked.add(result("ru4", "imf", "RU", 0.2));
        ranked.add(result("ru5", "fred", "RU", 0.1));

        SearchV2PreviewCandidateSelector.Selection selection =
                SearchV2PreviewCandidateSelector.select(ranked, 8, List.of("RU"), SourceRoutingDecision.empty());

        List<String> ids = selection.candidates().stream().map(r -> r.candidate().seriesId()).toList();
        long ruCount = ids.stream().filter(id -> id.startsWith("ru")).count();
        assertThat(ruCount).as("up to ceil(8/2)=4 explicit RU matches must be reserved").isEqualTo(4);
        assertThat(ids).containsSubsequence("ru1", "ru2", "ru3", "ru4")
                .as("reserved RU candidates keep their original relative order");
        assertThat(selection.telemetry().explicitGeoSelectedCount()).isEqualTo(4);
    }

    @Test
    void softSourceCapLimitsToSixOfEightWhenAlternativesExist() {
        List<SearchResult> ranked = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ranked.add(result("eu" + i, "eurostat", "", 0.9 - i * 0.01));
        }
        ranked.add(result("f1", "fred", "RU", 0.5));
        ranked.add(result("f2", "fred", "RU", 0.4));
        ranked.add(result("i1", "imf", "RU", 0.3));

        SearchV2PreviewCandidateSelector.Selection selection =
                SearchV2PreviewCandidateSelector.select(ranked, 8, List.of(), SourceRoutingDecision.empty());

        long eurostatCount = selection.candidates().stream()
                .filter(r -> "eurostat".equals(r.candidate().source())).count();
        assertThat(eurostatCount).as("ceil(8*0.75)=6 max per source when alternatives exist").isEqualTo(6);
        assertThat(selection.candidates()).hasSize(8);
        assertThat(selection.telemetry().sourceCounts()).containsEntry("eurostat", 6);
    }

    @Test
    void capFallbackReturnsFullLimitFromASingleSourceWhenNoAlternativesExist() {
        List<SearchResult> ranked = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ranked.add(result("eu" + i, "eurostat", "", 0.9 - i * 0.01));
        }

        SearchV2PreviewCandidateSelector.Selection selection =
                SearchV2PreviewCandidateSelector.select(ranked, 8, List.of(), SourceRoutingDecision.empty());

        assertThat(selection.candidates())
                .as("never fewer than a naive top-N just because of the diversity cap")
                .hasSize(8);
        assertThat(selection.telemetry().softCapRelaxed()).isTrue();
    }

    @Test
    void noRequestedGeographyMeansNoGeoReservationButDedupeAndCapStillApply() {
        List<SearchResult> ranked = new ArrayList<>();
        ranked.add(result("dup", "eurostat", "RU", 0.9));
        ranked.add(result("dup", "eurostat", "RU", 0.9));
        for (int i = 0; i < 10; i++) {
            ranked.add(result("eu" + i, "eurostat", "", 0.8 - i * 0.01));
        }
        ranked.add(result("f1", "fred", "RU", 0.5));
        ranked.add(result("f2", "fred", "RU", 0.4));

        SearchV2PreviewCandidateSelector.Selection selection =
                SearchV2PreviewCandidateSelector.select(ranked, 8, List.of(), SourceRoutingDecision.empty());

        assertThat(selection.telemetry().explicitGeoSelectedCount())
                .as("no requested geography -> no geo reservation, even though 'dup' has an explicit RU geo")
                .isZero();
        List<String> ids = selection.candidates().stream().map(r -> r.candidate().seriesId()).toList();
        assertThat(ids).doesNotHaveDuplicates();
        long eurostatCount = selection.candidates().stream()
                .filter(r -> "eurostat".equals(r.candidate().source())).count();
        assertThat(eurostatCount).isLessThanOrEqualTo(6);
    }

    @Test
    void verifyLimitSmallerThanEightComputesCeilingsCorrectly() {
        List<SearchResult> ranked = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ranked.add(result("eu" + i, "eurostat", "", 0.9 - i * 0.01));
        }
        ranked.add(result("ru1", "fred", "RU", 0.5));
        ranked.add(result("ru2", "imf", "RU", 0.4));
        ranked.add(result("ru3", "fred", "RU", 0.3));

        // limit=5: geo reserve = ceil(5/2)=3, source cap = ceil(5*0.75)=4
        SearchV2PreviewCandidateSelector.Selection five =
                SearchV2PreviewCandidateSelector.select(ranked, 5, List.of("RU"), SourceRoutingDecision.empty());
        assertThat(five.candidates()).hasSize(5);
        assertThat(five.telemetry().explicitGeoSelectedCount()).isEqualTo(3);
        assertThat(five.telemetry().sourceCounts().getOrDefault("eurostat", 0)).isLessThanOrEqualTo(4);

        // limit=1: geo reserve = ceil(1/2)=1, source cap = ceil(1*0.75)=1
        SearchV2PreviewCandidateSelector.Selection one =
                SearchV2PreviewCandidateSelector.select(ranked, 1, List.of("RU"), SourceRoutingDecision.empty());
        assertThat(one.candidates()).hasSize(1);
        assertThat(one.telemetry().explicitGeoSelectedCount()).isEqualTo(1);
    }

    @Test
    void restoreRankedOrderPutsAcceptedResultsBackIntoOriginalRankOrder() {
        List<SearchResult> ranked = List.of(
                result("first", "eurostat", "", 0.9),
                result("second", "fred", "RU", 0.5),
                result("third", "imf", "RU", 0.3));
        // Simulate the preview verifier accepting them in a different (selection) order.
        List<SearchResult> acceptedInSelectionOrder = List.of(ranked.get(1), ranked.get(2), ranked.get(0));

        List<SearchResult> restored = SearchV2PreviewCandidateSelector.restoreRankedOrder(acceptedInSelectionOrder, ranked);

        assertThat(restored.stream().map(r -> r.candidate().seriesId()).toList())
                .containsExactly("first", "second", "third");
    }
}
