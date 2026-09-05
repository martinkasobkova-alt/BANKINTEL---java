package cz.bankintel.search.v2.reranking;

import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.v2.ontology.SearchV2IndustrySectorRegistry;
import cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SemanticDecision;
import cz.bankintel.sources.eurostat.EurostatDimensionService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SearchV2FinalReranker {

    private static final Logger log = LoggerFactory.getLogger(SearchV2FinalReranker.class);

    private final SearchV2MetricIntentRegistry metricIntentRegistry;
    private final SearchV2IndustrySectorRegistry industrySectorRegistry;
    private final EurostatDimensionService eurostatDimensionService;

    public SearchV2FinalReranker(
            SearchV2MetricIntentRegistry metricIntentRegistry,
            SearchV2IndustrySectorRegistry industrySectorRegistry,
            EurostatDimensionService eurostatDimensionService) {
        this.metricIntentRegistry = metricIntentRegistry;
        this.industrySectorRegistry = industrySectorRegistry;
        this.eurostatDimensionService = eurostatDimensionService;
    }

    public List<SearchResult> finalRank(List<SearchCandidate> candidates, List<SemanticDecision> decisions, int limit) {
        return finalRank(candidates, decisions, limit, null);
    }

    public List<SearchResult> finalRank(
            List<SearchCandidate> candidates, List<SemanticDecision> decisions, int limit, SearchQueryPlan plan) {
        Map<String, SearchCandidate> byId = new LinkedHashMap<>();
        for (SearchCandidate candidate : candidates == null ? List.<SearchCandidate>of() : candidates) {
            byId.putIfAbsent(candidate.seriesId(), candidate);
        }
        List<SearchResult> results = new ArrayList<>();
        for (SemanticDecision decision : decisions == null ? List.<SemanticDecision>of() : decisions) {
            if (!decision.keepLike()) {
                continue;
            }
            SearchCandidate candidate = byId.get(decision.seriesId());
            if (candidate != null) {
                results.add(new SearchResult(candidate, decision, 0));
            }
        }
        // Precomputed ONCE per unique candidate before sorting - never inside the comparator, which
        // Java's sort can invoke many times per element. industryCompatibilityScore's verified tier
        // makes a live Eurostat call for a bounded number of candidates; recomputing it per comparison
        // would multiply that live-call count by the number of comparisons instead of the number of
        // candidates.
        Map<String, Double> industryScores = precomputeIndustryScores(plan, results);
        results.sort(Comparator
                .comparingInt((SearchResult r) -> roleRank(r.role()))
                .thenComparingInt(r -> sourcePreferenceRank(plan, r.candidate()))
                .thenComparing((SearchResult r) -> -metricCompatibilityScore(plan, r.candidate()))
                .thenComparing((SearchResult r) -> -industryScores.getOrDefault(stableIdentity(r.candidate()), 0.0))
                .thenComparing((SearchResult r) -> -r.decision().relevanceScore())
                .thenComparing(r -> -r.decision().confidence())
                .thenComparing((SearchResult r) -> -vectorScore(r.candidate()))
                .thenComparing(r -> r.candidate().ftsScore())
                .thenComparing(r -> stableIdentity(r.candidate())));
        List<SearchResult> ranked = new ArrayList<>();
        int rank = 1;
        for (SearchResult result : results.stream().limit(Math.max(1, limit)).toList()) {
            ranked.add(new SearchResult(result.candidate(), result.decision(), rank++));
        }
        return ranked;
    }

    /** Exact metric term match (query's own alias, e.g. "roe", found verbatim in the candidate). */
    private static final double METRIC_TIER_EXACT = 5.0;
    /** Same metric cluster, matched via a multi-word alias (e.g. "return on equity") - a full canonical
     * name rather than a loose single-word synonym, so treated as more precise than {@link
     * #METRIC_TIER_RELATED} - the same "more tokens -&gt; more specific" rule {@code
     * SearchV2ConceptRegistry} already applies to its own confidence scoring. */
    private static final double METRIC_TIER_CANONICAL_SYNONYM = 4.0;
    /** Same metric cluster (e.g. query "roe", candidate "zisk"), matched via a single generic word. */
    private static final double METRIC_TIER_RELATED = 3.0;
    /** Candidate mentions no registered metric cluster at all - "free_metric_intent", no signal either way. */
    private static final double METRIC_TIER_UNKNOWN = 0.0;
    /** Candidate is clearly about a DIFFERENT registered metric (e.g. query "dluh", candidate "naklady"). */
    private static final double METRIC_TIER_CONFLICT = -2.0;

    /**
     * A separate ranking axis alongside role/relevance-score/vector-score: does this candidate
     * actually talk about the METRIC the query asked about (debt, profitability, cost, assets...),
     * not just the same institutional sector? Root cause this addresses: within the fallback
     * (non-LLM) score band, "domácnosti s mobilním broadbandem" (households WITH BROADBAND) and
     * "Úvěry domácnostem celkem" (household LOANS) can land at the same or even inverted score simply
     * because both mention the sector word - nothing previously distinguished "on-topic metric" from
     * "same sector, unrelated topic".
     *
     * <p>Metric-intent registry audit (2026-07-31) found the ORIGINAL version of this method treated
     * every alias in a cluster as interchangeable (flat +2 for "roe" or "zisk" or "return on equity"
     * alike), so a "ROE" query could rank a generic-profitability candidate above an actual ROE series.
     * Replaced with an explicit hierarchy - exact metric &gt; canonical synonym &gt; related metric &gt;
     * unknown &gt; metric conflict (penalized) - built entirely from the EXISTING {@link
     * SearchV2MetricIntentRegistry} cluster/alias data; no new metrics or sector-specific rules.
     *
     * <p>Independent of this tiered signal, raw query-token overlap (+1 per token) still applies on
     * top - plain literal overlap with the query's own words, unaffected by whether the metric is
     * registered at all.
     */
    private double metricCompatibilityScore(SearchQueryPlan plan, SearchCandidate candidate) {
        if (plan == null || candidate == null) {
            return 0.0;
        }
        String candidateText = normalize(String.join(
                " ",
                candidate.title() == null ? "" : candidate.title(),
                candidate.description() == null ? "" : candidate.description()));
        if (candidateText.isBlank()) {
            return 0.0;
        }
        double score = metricHierarchyScore(plan, candidateText);
        for (String token : queryTokens(plan.originalQuery())) {
            if (containsToken(candidateText, token)) {
                score += 1.0;
            }
        }
        return score;
    }

    private double metricHierarchyScore(SearchQueryPlan plan, String candidateText) {
        if (plan.metricIntents().isEmpty()) {
            return 0.0;
        }
        String queryText = plan.originalQuery();
        String queryMatchedAlias = metricIntentRegistry.matchedAlias(queryText);
        if (!queryMatchedAlias.isBlank() && containsToken(candidateText, queryMatchedAlias)) {
            return METRIC_TIER_EXACT;
        }
        String queryMetric = metricIntentRegistry.resolve(queryText);
        String candidateMatchedAlias = metricIntentRegistry.matchedAlias(candidateText);
        if (candidateMatchedAlias.isBlank()) {
            return METRIC_TIER_UNKNOWN;
        }
        String candidateMetric = metricIntentRegistry.resolve(candidateText);
        if (!candidateMetric.equals(queryMetric)) {
            return METRIC_TIER_CONFLICT;
        }
        boolean canonicalPhrase = candidateMatchedAlias.trim().contains(" ");
        return canonicalPhrase ? METRIC_TIER_CANONICAL_SYNONYM : METRIC_TIER_RELATED;
    }

    /** Same-section textual match (query "stavebnictvi", candidate mentions "stavebnictvi" or "construction"). */
    private static final double INDUSTRY_TIER_MATCH = 5.0;
    /** Candidate mentions no registered NACE section at all - "free_industry_intent", no signal either way. */
    private static final double INDUSTRY_TIER_UNKNOWN = 0.0;
    /** Candidate is clearly about a DIFFERENT registered section (e.g. query "stavebnictvi", candidate "zemedelstvi"). */
    private static final double INDUSTRY_TIER_CONFLICT = -2.0;
    /** Additive bonus on top of {@link #INDUSTRY_TIER_MATCH} when a Eurostat candidate's own dimension
     * metadata was live-verified (via {@link EurostatDimensionService#combinationHasData}) to actually
     * hold non-zero data for the matched section - not just a textual mention. Bounded to a handful of
     * candidates per search, see {@link #precomputeIndustryScores}. */
    private static final double INDUSTRY_VERIFIED_DATA_BONUS = 3.0;
    /** Hard cap on live Eurostat verification calls per search - textual matching already covers every
     * candidate; this only adds confidence for a bounded top slice, keeping worst-case latency and
     * {@link cz.bankintel.sources.eurostat.EurostatRateLimiter} pressure predictable. */
    private static final int MAX_INDUSTRY_VERIFICATION_PROBES = 8;
    private static final List<String> INDUSTRY_DIMENSION_KEYS = List.of("nace_r2", "nace_r1", "ind_use", "cpa2_1");

    /**
     * A separate ranking axis alongside role/relevance-score/vector-score/metric-compatibility: does
     * this candidate actually cover the INDUSTRY (NACE section) the query asked about, not just some
     * other axis of the same topic? Root cause this addresses: the catalog search index has one row
     * per dataset, not one per dimension value, so "zamestnanost ve stavebnictvi" and "...v
     * zemedelstvi" retrieve the identical row with identical relevance - nothing previously
     * distinguished "names my industry" from "names a different industry" or "doesn't mention
     * industry at all". Mirrors {@link #metricHierarchyScore}'s tiered-not-flat design for the same
     * reason (an exact section match should outrank a same-index-row-different-section one).
     *
     * <p>{@code verifiedBonus} is precomputed separately (see {@link #precomputeIndustryScores}) since
     * it may involve a live network call - this method itself stays pure/offline like {@link
     * #metricHierarchyScore}.
     */
    private double industryTextScore(SearchQueryPlan plan, SearchCandidate candidate) {
        if (plan == null || candidate == null || plan.industrySectors().isEmpty()) {
            return 0.0;
        }
        String candidateText = normalize(String.join(
                " ",
                candidate.title() == null ? "" : candidate.title(),
                candidate.description() == null ? "" : candidate.description()));
        if (candidateText.isBlank()) {
            return 0.0;
        }
        String querySector = industrySectorRegistry.resolve(plan.originalQuery());
        if (querySector.isBlank()) {
            return 0.0;
        }
        String candidateMatchedAlias = industrySectorRegistry.matchedAlias(candidateText);
        if (candidateMatchedAlias.isBlank()) {
            return INDUSTRY_TIER_UNKNOWN;
        }
        String candidateSector = industrySectorRegistry.resolve(candidateText);
        return candidateSector.equals(querySector) ? INDUSTRY_TIER_MATCH : INDUSTRY_TIER_CONFLICT;
    }

    /**
     * Precomputes each candidate's full industry score (textual tier + live-verified bonus) exactly
     * ONCE, before sorting - see the {@code finalRank} call site for why this must not happen inside
     * the sort comparator. Short-circuits to an empty map (zero cost, zero network calls) whenever the
     * query resolved no industry sector at all - the overwhelming majority of searches.
     */
    private Map<String, Double> precomputeIndustryScores(SearchQueryPlan plan, List<SearchResult> results) {
        if (plan == null || plan.industrySectors().isEmpty()) {
            return Map.of();
        }
        String querySector = industrySectorRegistry.resolve(plan.originalQuery());
        if (querySector.isBlank()) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        int verificationProbesUsed = 0;
        for (SearchResult result : results) {
            SearchCandidate candidate = result.candidate();
            String key = stableIdentity(candidate);
            if (out.containsKey(key)) {
                continue;
            }
            double score = industryTextScore(plan, candidate);
            if (score >= INDUSTRY_TIER_MATCH
                    && candidate.source() != null
                    && "eurostat".equalsIgnoreCase(candidate.source())
                    && verificationProbesUsed < MAX_INDUSTRY_VERIFICATION_PROBES) {
                verificationProbesUsed++;
                if (verifyIndustryHasData(querySector, candidate, plan)) {
                    score += INDUSTRY_VERIFIED_DATA_BONUS;
                }
            }
            out.put(key, score);
        }
        return out;
    }

    /**
     * Tries the query's resolved NACE section against this specific Eurostat candidate's OWN cached
     * dimension metadata (never a blind guess) - if the section (or its CPA-prefixed equivalent) is
     * actually one of the dataset's real dimension values, re-verifies the resulting combination has
     * genuine non-zero data via the same {@code combinationHasData} step 1/2 of this plan already
     * fixed to reject empty/all-zero combinations. Reuses {@code resolvePreviewQueryParams}'s own
     * verified baseline for every OTHER dimension, so this never has to guess freq/unit/etc. itself -
     * only the ONE matched industry dimension is overridden. Never throws: a metadata fetch or probe
     * failure just means "not verified", the same as any candidate this layer doesn't recognize.
     */
    private boolean verifyIndustryHasData(String sectionCode, SearchCandidate candidate, SearchQueryPlan plan) {
        String datasetId = candidate.dataset();
        if (datasetId == null || datasetId.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> availableDims = eurostatDimensionService.previewAvailableDimensions(datasetId);
            String matchedDimKey = null;
            String matchedCode = null;
            for (String dimKey : INDUSTRY_DIMENSION_KEYS) {
                Object spec = availableDims.get(dimKey);
                if (!(spec instanceof Map<?, ?> specMap) || !(specMap.get("values") instanceof List<?> values)) {
                    continue;
                }
                for (Object rawValue : values) {
                    String code = String.valueOf(rawValue).trim();
                    if (code.equalsIgnoreCase(sectionCode) || code.equalsIgnoreCase("CPA_" + sectionCode)) {
                        matchedDimKey = dimKey;
                        matchedCode = code;
                        break;
                    }
                }
                if (matchedDimKey != null) {
                    break;
                }
            }
            if (matchedDimKey == null) {
                return false;
            }
            String candidateGeo = candidate.geo();
            List<String> planGeographies = plan == null ? null : plan.geographies();
            String geo = candidateGeo != null && !candidateGeo.isBlank()
                    ? candidateGeo
                    : (planGeographies != null && !planGeographies.isEmpty() ? planGeographies.get(0) : "CZ");
            Map<String, Object> baseline = eurostatDimensionService.resolvePreviewQueryParams(datasetId, geo);
            if (baseline.isEmpty()) {
                return false;
            }
            Map<String, String> trial = new LinkedHashMap<>();
            baseline.forEach((k, v) -> trial.put(k, String.valueOf(v)));
            trial.put(matchedDimKey, matchedCode);
            return eurostatDimensionService.combinationHasData(datasetId, trial);
        } catch (Exception ex) {
            log.debug("industry verification failed for {}: {}", datasetId, ex.getMessage());
            return false;
        }
    }

    private static List<String> queryTokens(String query) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String word : normalized.split("\\s+")) {
            if (word.length() >= 4) {
                tokens.add(word);
            }
        }
        return tokens;
    }

    private static boolean containsToken(String haystack, String needle) {
        return !needle.isBlank() && (" " + haystack + " ").contains(" " + needle + " ");
    }

    private static String normalize(String value) {
        return CatalogTextUtils.normalizeTokenBoundaries(value == null ? "" : value);
    }

    private static int roleRank(String role) {
        return switch (role == null ? "" : role) {
            case "primary" -> 0;
            case "comparison" -> 1;
            case "driver", "context", "supporting" -> 2;
            default -> 3;
        };
    }

    /**
     * Tie-break for candidates whose role/relevance-score/confidence are otherwise identical - the
     * common case whenever the (non-LLM) semantic validator fallback path is used, since it clamps
     * every candidate lacking a proven structured conflict into the same narrow score band regardless
     * of how well its actual content matches the query (see the fallback path's {@code
     * Math.min(Math.max(score, 0.2), 0.38)} clamp in {@code SearchV2SemanticValidator} - unchanged
     * here, only consumed).
     *
     * <p>Root cause this addresses: a candidate whose title uses an inflected Czech word form (e.g.
     * "domácnostem", dative plural) can completely miss the lexical/FTS match for a query written in
     * the nominative ("domácnosti"), landing it in the same tied score band as - or even behind - a
     * same-sector-but-topically-unrelated candidate that only happens to share the base sector word.
     * Vector similarity does not have this surface-form blind spot (it is exactly the case embeddings
     * are good at), so preferring higher vector score among ties favors the semantically closer match
     * without hardcoding any specific concept, sector, or word list - it works the same way for any
     * query in any sector.
     */
    private static double vectorScore(SearchCandidate candidate) {
        if (candidate == null || candidate.raw() == null) {
            return 0.0;
        }
        return CatalogMapSupport.toDouble(candidate.raw().get("_vector_score"));
    }

    private static String stableIdentity(SearchCandidate candidate) {
        if (candidate == null) {
            return "";
        }
        return String.join(
                ":",
                candidate.source() == null ? "" : candidate.source().toLowerCase(Locale.ROOT),
                candidate.seriesId() == null ? "" : candidate.seriesId().toLowerCase(Locale.ROOT));
    }

    private static int sourcePreferenceRank(SearchQueryPlan plan, SearchCandidate candidate) {
        if (plan == null || !plan.highConfidenceExactEntity() || candidate == null || plan.sourceRouting() == null) {
            return 0;
        }
        List<String> preferred = plan.sourceRouting().preferredSources();
        if (preferred == null || preferred.isEmpty()) {
            return 0;
        }
        String source = candidate.source() == null ? "" : candidate.source().trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < preferred.size(); i++) {
            if (source.equals(String.valueOf(preferred.get(i)).trim().toLowerCase(Locale.ROOT))) {
                return i;
            }
        }
        return preferred.size();
    }
}
