package cz.bankintel.search.v2.reranking;

import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SemanticDecision;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SearchV2FinalReranker {

    private final SearchV2MetricIntentRegistry metricIntentRegistry;

    public SearchV2FinalReranker(SearchV2MetricIntentRegistry metricIntentRegistry) {
        this.metricIntentRegistry = metricIntentRegistry;
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
        results.sort(Comparator
                .comparingInt((SearchResult r) -> roleRank(r.role()))
                .thenComparingInt(r -> sourcePreferenceRank(plan, r.candidate()))
                .thenComparing((SearchResult r) -> -metricCompatibilityScore(plan, r.candidate()))
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
