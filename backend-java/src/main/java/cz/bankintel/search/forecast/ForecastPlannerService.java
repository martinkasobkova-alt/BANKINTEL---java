package cz.bankintel.search.forecast;

import cz.bankintel.search.CatalogMultiSearchService;
import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.model.CatalogKeys;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Selects candidate exogenous predictor series for a forecast target.
 *
 * <p>Domain detection ({@link ForecastPredictorConfig#resolveDomain}) is the only place text
 * similarity is used — it just decides which curated predictor list applies (inflation vs.
 * mortgages vs. house prices, ...). The predictors themselves come from that curated economic
 * relationship list, and for each one this planner runs a real catalog search (reusing the
 * existing {@link CatalogMultiSearchService} search lanes) scoped by a geo hint, so the actual
 * exogenous series chosen still depends on what data genuinely exists in the catalog — this is
 * intentionally NOT a hardcoded per-query series id lookup.
 */
@Service
@RequiredArgsConstructor
public class ForecastPlannerService {

    private static final Logger log = LoggerFactory.getLogger(ForecastPlannerService.class);
    private static final List<String> DEFAULT_SOURCES = List.of("fred", "ecb2", "eurostat", "arad", "csu", "bis", "imf", "oecd");
    private static final int CANDIDATE_LIMIT_PER_CONCEPT = 6;
    private static final int TOP_N_PER_CONCEPT = 2;

    private final CatalogMultiSearchService multiSearchService;

    /**
     * One discovered candidate series for a given economic concept/role. {@code discoveryScore}
     * (0-1) reflects how confident the *discovery* step is (search rank + geo match) — it is a
     * hint fed into the Python feature-discovery quality score, not itself a selection decision;
     * the actual keep/drop/feature-selection call is made downstream by guardrails + backtest.
     */
    public record PredictorCandidate(
            String role, String conceptCz, String sourceType, String setId, String title, int searchScore, String valueKind, double discoveryScore) {}

    public record PlanResult(Optional<String> domainKey, Optional<String> domainLabelCz, List<PredictorCandidate> candidates) {}

    public PlanResult planDomainOnly(String targetLabel) {
        Optional<ForecastPredictorConfig.Domain> domain = ForecastPredictorConfig.get().resolveDomain(targetLabel);
        if (domain.isEmpty()) {
            return new PlanResult(Optional.empty(), Optional.empty(), List.of());
        }
        return new PlanResult(Optional.of(domain.get().key()), Optional.of(domain.get().labelCz()), List.of());
    }

    public PlanResult plan(String targetLabel, String targetSourceType, String targetSetId, String geoHint) {
        Optional<ForecastPredictorConfig.Domain> domain = ForecastPredictorConfig.get().resolveDomain(targetLabel);
        if (domain.isEmpty()) {
            log.debug("forecast planner: no predictor domain matched for target label '{}'", targetLabel);
            return new PlanResult(Optional.empty(), Optional.empty(), List.of());
        }
        List<PredictorCandidate> candidates = new ArrayList<>();
        for (ForecastPredictorConfig.Predictor predictor : domain.get().predictors()) {
            candidates.addAll(findTopMatches(predictor, geoHint, targetSourceType, targetSetId, TOP_N_PER_CONCEPT));
        }
        return new PlanResult(Optional.of(domain.get().key()), Optional.of(domain.get().labelCz()), candidates);
    }

    // Generic words that carry no discriminating economic meaning on their own — a hit whose
    // title only overlaps the search query on these terms is not a relevance match.
    private static final Set<String> STOPWORDS = Set.of(
            "index", "rate", "price", "prices", "the", "of", "and", "for", "growth", "real", "average", "annual",
            "monthly", "quarterly", "national", "total", "general", "level", "data");

    private List<PredictorCandidate> findTopMatches(
            ForecastPredictorConfig.Predictor predictor, String geoHint, String targetSourceType, String targetSetId, int topN) {
        String query = geoHint == null || geoHint.isBlank() ? predictor.searchQuery() : predictor.searchQuery() + " " + geoHint;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(CatalogKeys.QUERY, query);
        payload.put(CatalogKeys.SOURCES, DEFAULT_SOURCES);
        payload.put("limit_per_source", CANDIDATE_LIMIT_PER_CONCEPT);
        try {
            Map<String, Object> result = multiSearchService.multiSearch(payload);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hits = (List<Map<String, Object>>) result.getOrDefault("results", List.of());
            List<Map.Entry<Map<String, Object>, Double>> ranked =
                    pickTopN(hits, geoHint, targetSourceType, targetSetId, predictor.searchQuery(), topN);
            List<PredictorCandidate> out = new ArrayList<>();
            for (Map.Entry<Map<String, Object>, Double> entry : ranked) {
                Map<String, Object> hit = entry.getKey();
                String sourceType = str(hit.getOrDefault(CatalogKeys.SOURCE_TYPE, hit.get(CatalogKeys.CATALOG_ID)));
                String setId = str(hit.get(CatalogKeys.SET_ID));
                String title = str(hit.getOrDefault(CatalogKeys.TITLE, hit.get(CatalogKeys.NAME)));
                int score = hit.get(CatalogKeys.SEARCH_SCORE) instanceof Number n ? n.intValue() : 0;
                out.add(new PredictorCandidate(
                        predictor.role(), predictor.conceptCz(), sourceType, setId, title, score, predictor.valueKind(), entry.getValue()));
            }
            return out;
        } catch (Exception ex) {
            log.warn("forecast planner: predictor search failed for '{}': {}", predictor.searchQuery(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * Ranks hits into: geo-matched + relevant first, then relevant-only, preserving catalog
     * search order within each bucket, and returns up to {@code topN} distinct series with a
     * normalized 0-1 discovery score (geo match + rank position). The target series itself is
     * never returned as its own predictor.
     */
    @SuppressWarnings("unchecked")
    private static List<Map.Entry<Map<String, Object>, Double>> pickTopN(
            List<Map<String, Object>> hits,
            String geoHint,
            String targetSourceType,
            String targetSetId,
            String searchQuery,
            int topN) {
        String geoFolded = geoHint == null ? "" : geoHint.toLowerCase(Locale.ROOT);
        Set<String> queryKeywords = keywords(searchQuery);
        List<Map<String, Object>> geoAndRelevant = new ArrayList<>();
        List<Map<String, Object>> relevantOnly = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        for (Map<String, Object> hit : hits) {
            String sourceType = str(hit.getOrDefault(CatalogKeys.SOURCE_TYPE, hit.get(CatalogKeys.CATALOG_ID)));
            String setId = str(hit.get(CatalogKeys.SET_ID));
            if (sourceType.equalsIgnoreCase(targetSourceType) && setId.equalsIgnoreCase(targetSetId)) {
                continue; // never pick the target series itself as its own predictor
            }
            String dedupeKey = sourceType.toLowerCase(Locale.ROOT) + ":" + setId.toLowerCase(Locale.ROOT);
            if (!seenKeys.add(dedupeKey)) {
                continue;
            }
            String blob = (str(hit.get(CatalogKeys.TITLE)) + " " + str(hit.get(CatalogKeys.NAME)) + " " + rowBlob(hit))
                    .toLowerCase(Locale.ROOT);
            // Require at least one non-generic keyword overlap between the curated economic
            // concept's search query and the candidate's title — this is a sanity filter, not the
            // predictor selection criterion itself (that's the domain -> concept map), so it just
            // stops the catalog's full-text search from handing back an unrelated series (e.g. an
            // insurance-claims dataset for a "wages" concept) as if it were economically relevant.
            if (!hasKeywordOverlap(blob, queryKeywords)) {
                continue;
            }
            if (!geoFolded.isBlank() && blob.contains(geoFolded)) {
                geoAndRelevant.add(hit);
            } else {
                relevantOnly.add(hit);
            }
        }
        List<Map.Entry<Map<String, Object>, Double>> out = new ArrayList<>();
        for (Map<String, Object> hit : geoAndRelevant) {
            if (out.size() >= topN) {
                break;
            }
            out.add(Map.entry(hit, discoveryScore(true, out.size())));
        }
        for (Map<String, Object> hit : relevantOnly) {
            if (out.size() >= topN) {
                break;
            }
            out.add(Map.entry(hit, discoveryScore(false, out.size())));
        }
        return out;
    }

    private static double discoveryScore(boolean geoMatched, int rankIndex) {
        double base = geoMatched ? 1.0 : 0.6;
        return Math.max(0.1, base * (1.0 - 0.15 * rankIndex));
    }

    private static Set<String> keywords(String searchQuery) {
        Set<String> out = new LinkedHashSet<>();
        if (searchQuery == null) {
            return out;
        }
        String folded = CatalogTextUtils.foldAscii(searchQuery).toLowerCase(Locale.ROOT);
        for (String word : folded.split("[^a-z0-9]+")) {
            if (word.length() >= 4 && !STOPWORDS.contains(word)) {
                out.add(word);
            }
        }
        return out;
    }

    private static boolean hasKeywordOverlap(String blob, Set<String> queryKeywords) {
        if (queryKeywords.isEmpty()) {
            return true; // nothing discriminating to check against (e.g. a very short query)
        }
        String folded = CatalogTextUtils.foldAscii(blob);
        for (String keyword : queryKeywords) {
            if (folded.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String rowBlob(Map<String, Object> hit) {
        Object row = hit.get(CatalogKeys.ROW);
        return row instanceof Map<?, ?> map ? String.valueOf(map) : "";
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
