package cz.bankintel.search.v2.orchestration;

import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SourceRoutingDecision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Chooses which {@code verifyLimit} candidates from the already fully-ranked {@code ranked} list get
 * sent to live preview verification. Sits ONLY between {@code SearchV2FinalReranker.finalRank()} and
 * {@code SearchV2PreviewVerifier.verifyTopOnly()} - it never changes relevance scores, candidate
 * order outside of preview selection, or the final result list's order (callers re-sort the accepted
 * subset back into {@code ranked}'s original order before building final results).
 *
 * <p>Root cause this addresses: a naive "top N by rank" slice can let one source (typically a
 * multi-country aggregate catalog like Eurostat, whose candidates carry a blank {@code geo} field
 * because country is a per-request dimension rather than static metadata) occupy every preview slot
 * for a non-EU-country query, even when candidates from other sources with an explicit, correct
 * geography match are sitting lower in {@code ranked}. See the giant-dump/preview-selector diagnosis
 * for the full analysis (2026-07-30).
 *
 * <p>Deliberately does NOT: change retrieval, scoring, the semantic validator, {@code
 * SearchV2SourceCapabilityRegistry}, timeouts, or infer geo-compatibility for blank-geo candidates -
 * a dataset with blank geo may still genuinely cover the requested country; only an explicit,
 * candidate-declared geo match is ever reserved for, and a blank geo is never auto-excluded either.
 */
final class SearchV2PreviewCandidateSelector {

    private SearchV2PreviewCandidateSelector() {}

    /** Cheap, low-cardinality diagnostics for this one selection - no raw query text. */
    record SelectionTelemetry(
            int inputCount,
            int dedupedCount,
            int selectedCount,
            int explicitGeoSelectedCount,
            Map<String, Integer> sourceCounts,
            boolean softCapRelaxed) {}

    record Selection(List<SearchResult> candidates, SelectionTelemetry telemetry) {}

    static Selection select(
            List<SearchResult> ranked,
            int verifyLimit,
            List<String> requestedGeographies,
            SourceRoutingDecision sourceRouting) {
        List<SearchResult> input = ranked == null ? List.of() : ranked;
        int limit = Math.max(0, verifyLimit);
        if (input.isEmpty() || limit <= 0) {
            return new Selection(input, new SelectionTelemetry(input.size(), input.size(), input.size(), 0, Map.of(), false));
        }

        List<SearchResult> deduped = dedupeBySeriesId(input);
        Set<String> requestedGeo = normalizeGeographies(requestedGeographies);
        int geoReserveCap = requestedGeo.isEmpty() ? 0 : ceilDiv(limit, 2);
        int sourceCap = ceilDiv(limit * 3, 4);

        List<SearchResult> selected = new ArrayList<>(limit);
        Set<String> selectedIds = new LinkedHashSet<>();
        Map<String, Integer> perSourceCount = new LinkedHashMap<>();
        int explicitGeoSelected = 0;

        // Step B: reserve up to geoReserveCap slots for explicit requested-geo matches, in ranked order.
        if (geoReserveCap > 0) {
            for (SearchResult result : deduped) {
                if (selected.size() >= geoReserveCap) {
                    break;
                }
                if (isExplicitGeoMatch(result, requestedGeo)) {
                    selected.add(result);
                    selectedIds.add(result.candidate().seriesId());
                    perSourceCount.merge(sourceKey(result), 1, Integer::sum);
                    explicitGeoSelected++;
                }
            }
        }

        // Step C: fill remaining slots in ranked order, respecting the soft per-source cap.
        List<SearchResult> deferredByCap = new ArrayList<>();
        for (SearchResult result : deduped) {
            if (selected.size() >= limit) {
                break;
            }
            String seriesId = result.candidate().seriesId();
            if (selectedIds.contains(seriesId)) {
                continue;
            }
            String source = sourceKey(result);
            if (perSourceCount.getOrDefault(source, 0) >= sourceCap) {
                deferredByCap.add(result);
                continue;
            }
            selected.add(result);
            selectedIds.add(seriesId);
            perSourceCount.merge(source, 1, Integer::sum);
        }

        // Step D: cap fallback - never return fewer than a naive top-N would, purely for diversity.
        boolean softCapRelaxed = false;
        if (selected.size() < limit) {
            for (SearchResult result : deferredByCap) {
                if (selected.size() >= limit) {
                    break;
                }
                String seriesId = result.candidate().seriesId();
                if (selectedIds.contains(seriesId)) {
                    continue;
                }
                selected.add(result);
                selectedIds.add(seriesId);
                perSourceCount.merge(sourceKey(result), 1, Integer::sum);
                softCapRelaxed = true;
            }
        }

        SelectionTelemetry telemetry = new SelectionTelemetry(
                input.size(), deduped.size(), selected.size(), explicitGeoSelected,
                Map.copyOf(perSourceCount), softCapRelaxed);
        return new Selection(List.copyOf(selected), telemetry);
    }

    /**
     * Restores {@code accepted}'s order to match its position in {@code ranked} - {@code
     * SearchV2PreviewVerifier} otherwise returns accepted candidates in whatever order they were
     * checked (this selector's order), and final-result order must stay exactly as {@code
     * finalReranker.finalRank()} produced it.
     */
    static List<SearchResult> restoreRankedOrder(List<SearchResult> accepted, List<SearchResult> ranked) {
        if (accepted == null || accepted.isEmpty() || ranked == null || ranked.isEmpty()) {
            return accepted == null ? List.of() : accepted;
        }
        Map<String, Integer> originalIndex = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            originalIndex.putIfAbsent(ranked.get(i).candidate().seriesId(), i);
        }
        return accepted.stream()
                .sorted((a, b) -> Integer.compare(
                        originalIndex.getOrDefault(a.candidate().seriesId(), Integer.MAX_VALUE),
                        originalIndex.getOrDefault(b.candidate().seriesId(), Integer.MAX_VALUE)))
                .toList();
    }

    private static List<SearchResult> dedupeBySeriesId(List<SearchResult> ranked) {
        Map<String, SearchResult> bySeriesId = new LinkedHashMap<>();
        for (SearchResult result : ranked) {
            String seriesId = result.candidate() == null ? null : result.candidate().seriesId();
            if (seriesId == null || seriesId.isBlank()) {
                continue;
            }
            bySeriesId.putIfAbsent(seriesId, result);
        }
        return List.copyOf(bySeriesId.values());
    }

    private static boolean isExplicitGeoMatch(SearchResult result, Set<String> requestedGeo) {
        String candidateGeo = normalizeOne(result.candidate() == null ? null : result.candidate().geo());
        return !candidateGeo.isBlank() && requestedGeo.contains(candidateGeo);
    }

    private static String sourceKey(SearchResult result) {
        String source = result.candidate() == null ? null : result.candidate().source();
        return source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalizeGeographies(List<String> requestedGeographies) {
        if (requestedGeographies == null || requestedGeographies.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String geo : requestedGeographies) {
            String normalized = normalizeOne(geo);
            if (!normalized.isBlank()) {
                out.add(normalized);
            }
        }
        return out;
    }

    private static String normalizeOne(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static int ceilDiv(int numerator, int denominator) {
        return (numerator + denominator - 1) / denominator;
    }
}
