package cz.bankintel.search.v2.reranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry;
import cz.bankintel.search.v2.schema.ExactEntityResolution;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SearchQueryVariant;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SemanticDecision;
import cz.bankintel.search.v2.schema.SourceRoutingDecision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2FinalRerankerTest {

    private final SearchV2MetricIntentRegistry metricIntentRegistry =
            new SearchV2MetricIntentRegistry(new ObjectMapper());
    private final SearchV2FinalReranker reranker = new SearchV2FinalReranker(metricIntentRegistry);

    @Test
    void keepsPrimaryBeforeSupportingAndDropsRejects() {
        SearchCandidate supporting = candidate("supporting");
        SearchCandidate primary = candidate("primary");
        SearchCandidate dropped = candidate("dropped");

        List<SearchResult> out = reranker.finalRank(
                List.of(supporting, primary, dropped),
                List.of(
                        decision("supporting", "supporting", "context", 0.95),
                        decision("primary", "keep", "primary", 0.70),
                        decision("dropped", "drop", "reject", 0.99)),
                10);

        assertThat(out).extracting(r -> r.candidate().seriesId()).containsExactly("primary", "supporting");
        assertThat(out.get(0).rank()).isEqualTo(1);
    }

    @Test
    void finalRankingDoesNotForceSourceDiversity() {
        List<SearchCandidate> candidates = List.of(
                candidate("ecb2", "best-1"),
                candidate("ecb2", "best-2"),
                candidate("ecb2", "best-3"),
                candidate("ecb2", "best-4"),
                candidate("ecb2", "best-5"),
                candidate("fred", "weaker"));

        List<SearchResult> out = reranker.finalRank(
                candidates,
                List.of(
                        decision("best-1", "keep", "primary", 0.99),
                        decision("best-2", "keep", "primary", 0.98),
                        decision("best-3", "keep", "primary", 0.97),
                        decision("best-4", "keep", "primary", 0.96),
                        decision("best-5", "keep", "primary", 0.95),
                        decision("weaker", "keep", "primary", 0.50)),
                5);

        assertThat(out).extracting(r -> r.candidate().source()).containsOnly("ecb2");
        assertThat(out).extracting(r -> r.candidate().seriesId())
                .containsExactly("best-1", "best-2", "best-3", "best-4", "best-5");
    }

    @Test
    void exactEntityPlanUsesPreferredSourceBeforeWeakFallbackScore() {
        SearchQueryPlan plan = exactPlan(List.of("ecb2", "fred", "bis"));
        List<SearchCandidate> candidates = List.of(
                candidate("bis", "generic-usd"),
                candidate("ecb2", "ecb-exr"));

        List<SearchResult> out = reranker.finalRank(
                candidates,
                List.of(
                        decision("generic-usd", "keep", "primary", 0.40),
                        decision("ecb-exr", "keep", "primary", 0.39)),
                10,
                plan);

        assertThat(out).extracting(r -> r.candidate().seriesId())
                .containsExactly("ecb-exr", "generic-usd");
    }

    @Test
    void higherVectorScoreBreaksTiesWhenRoleScoreAndConfidenceAreIdentical() {
        // Reproduces the "zadluzeni domacnosti" relevance regression: with the semantic reranker
        // unavailable, both candidates get clamped into the exact same score/confidence band (the
        // fallback validator path has no proven structured conflict to differentiate them). The
        // household-loans candidate's title uses the Czech dative "domacnostem", which misses the
        // lexical/FTS match for a query written in the nominative "domacnosti" - but its vector
        // embedding still recognizes the semantic match, unlike the topically-unrelated candidate.
        SearchCandidate onTopicButDeclinedTitle =
                candidateWithVectorScore("arad", "household-loans", "Uvery domacnostem celkem", 0.92);
        SearchCandidate offTopicSameSector =
                candidateWithVectorScore("oecd4", "ict-households", "Domacnosti s mobilnim broadbandem", 0.0);

        List<SearchResult> out = reranker.finalRank(
                List.of(offTopicSameSector, onTopicButDeclinedTitle),
                List.of(
                        decision("household-loans", "keep", "context", 0.38),
                        decision("ict-households", "keep", "context", 0.38)),
                10);

        assertThat(out).extracting(r -> r.candidate().seriesId())
                .as("higher vector similarity must win the tie, not candidate/list order")
                .containsExactly("household-loans", "ict-households");
    }

    @Test
    void registeredMetricClusterMatchBeatsSameSectorCandidateEvenWithoutTiedScores() {
        // The exact deterministic (use_ai=false) reproduction: scores are NOT tied here (0.38 vs 0.2 -
        // the validator's own rank-decay term, unchanged/untouched), so the vector-score tie-break
        // above never even applies. "loans" (debt cluster alias) appears nowhere in the ICT candidate,
        // while it appears in the household-loans candidate's title - metric compatibility must
        // out-rank the raw relevance-score gap coming from an unrelated rank-decay artifact.
        SearchQueryPlan plan = planWithMetricIntent("zadluzeni domacnosti", List.of("debt"), List.of("households"));
        SearchCandidate householdLoans = candidate("arad", "household-loans", "Uvery domacnostem celkem", List.of());
        SearchCandidate ictBroadband =
                candidate("oecd4", "ict-households", "Domacnosti s mobilnim broadbandem", List.of());

        List<SearchResult> out = reranker.finalRank(
                List.of(ictBroadband, householdLoans),
                List.of(
                        decision("ict-households", "keep", "context", 0.38),
                        decision("household-loans", "keep", "context", 0.20)),
                10,
                plan);

        assertThat(out).extracting(r -> r.candidate().seriesId())
                .as("debt-cluster metric match must beat a same-sector-only candidate despite a lower raw score")
                .containsExactly("household-loans", "ict-households");
    }

    @Test
    void unregisteredMetricStillRanksViaPlainQueryTokenOverlap() {
        // Section 6/task requirement: a metric absent from the registry ("free_metric_intent") must
        // not become dead weight - retrieval/ranking still differentiates via plain lexical overlap
        // with the query's own words, no registry entry required.
        SearchQueryPlan plan = planWithMetricIntent("elektrifikace vesnic", List.of(), List.of());
        SearchCandidate onTopic = candidate("worldbank", "villages-electrified", "Pocet elektrifikovanych vesnic", List.of());
        SearchCandidate offTopic = candidate("worldbank", "road-crashes", "Pocet dopravnich nehod", List.of());

        List<SearchResult> out = reranker.finalRank(
                List.of(offTopic, onTopic),
                List.of(
                        decision("road-crashes", "keep", "context", 0.30),
                        decision("villages-electrified", "keep", "context", 0.30)),
                10,
                plan);

        assertThat(out).extracting(r -> r.candidate().seriesId())
                .as("plain query-token overlap must still work when the metric is unregistered")
                .containsExactly("villages-electrified", "road-crashes");
    }

    @Test
    void exactRoeTermBeatsGenericProfitabilitySynonymAtTiedScores() {
        // Metric hierarchy audit (2026-07-31): the OLD scoring treated every alias in a cluster as
        // interchangeable (flat +2 for "roe" or "zisk" alike), so a "ROE" query could rank a
        // generic-profit candidate above an actual ROE series. Exact term match must now outrank a
        // same-cluster-but-different-word match, even at identical relevance scores.
        SearchQueryPlan plan = planWithMetricIntent("ROE bank cesko", List.of("profitability"), List.of("banks"));
        SearchCandidate roeSeries = candidate("ecb2", "roe-series", "ROE bank Ceska republika", List.of());
        SearchCandidate genericProfit = candidate("ecb2", "profit-series", "Zisk bank Ceska republika", List.of());

        List<SearchResult> out = reranker.finalRank(
                List.of(genericProfit, roeSeries),
                List.of(
                        decision("profit-series", "keep", "context", 0.60),
                        decision("roe-series", "keep", "context", 0.60)),
                10,
                plan);

        assertThat(out).extracting(r -> r.candidate().seriesId())
                .as("exact metric term (roe) must outrank a same-cluster generic synonym (zisk)")
                .containsExactly("roe-series", "profit-series");
    }

    @Test
    void canonicalMultiWordSynonymOutranksGenericSingleWordSynonym() {
        // Both titles share the identical "bank cesko" raw-token overlap with the query, so only the
        // metric-tier difference (canonical multi-word vs. related single-word) can decide the order.
        SearchQueryPlan plan = planWithMetricIntent("ROE bank cesko", List.of("profitability"), List.of("banks"));
        SearchCandidate returnOnEquity =
                candidate("ecb2", "roe-full-phrase", "Return on equity bank cesko", List.of());
        SearchCandidate genericProfit = candidate("ecb2", "profit-series", "Zisk bank cesko", List.of());

        List<SearchResult> out = reranker.finalRank(
                List.of(genericProfit, returnOnEquity),
                List.of(
                        decision("profit-series", "keep", "context", 0.60),
                        decision("roe-full-phrase", "keep", "context", 0.60)),
                10,
                plan);

        assertThat(out).extracting(r -> r.candidate().seriesId())
                .as("canonical multi-word synonym (\"return on equity\") must outrank a generic "
                        + "single-word synonym (\"zisk\") for the same cluster")
                .containsExactly("roe-full-phrase", "profit-series");
    }

    @Test
    void differentRegisteredMetricIsPenalizedBelowUnknownMetricCandidate() {
        // Conflict tier: a candidate that is clearly about a DIFFERENT registered metric (cost, not
        // debt) must rank BELOW a candidate that mentions no registered metric at all (neutral,
        // unknown) - not merely below an on-topic match.
        SearchQueryPlan plan = planWithMetricIntent("zadluzeni domacnosti", List.of("debt"), List.of("households"));
        SearchCandidate wrongMetric =
                candidate("csu", "household-costs", "Naklady domacnosti na bydleni", List.of());
        SearchCandidate neutralUnrelated =
                candidate("csu", "household-count", "Pocet domacnosti v CR", List.of());

        List<SearchResult> out = reranker.finalRank(
                List.of(wrongMetric, neutralUnrelated),
                List.of(
                        decision("household-costs", "keep", "context", 0.50),
                        decision("household-count", "keep", "context", 0.50)),
                10,
                plan);

        assertThat(out).extracting(r -> r.candidate().seriesId())
                .as("a candidate about a different registered metric (cost) must rank below a neutral, "
                        + "metric-unknown candidate at the same relevance score")
                .containsExactly("household-count", "household-costs");
    }

    @Test
    void finalRankingPreservesLlmSemanticDecisionsWithoutASecondConceptGate() {
        SearchQueryPlan plan = conceptPlan("interest_rate");
        SearchCandidate interest = candidate("ecb2", "interest", "Úroková sazba Německo", List.of("interest_rate"));
        SearchCandidate unemployment = candidate(
                "ecb2",
                "unemployment",
                "Míra nezaměstnanosti 15-24 let EU",
                List.of("unemployment_rate"));
        SearchCandidate unknownRate = candidate(
                "eurostat",
                "electrification",
                "Míra elektrifikace",
                List.of("electrification_rate"));

        List<SearchResult> out = reranker.finalRank(
                List.of(interest, unemployment, unknownRate),
                List.of(
                        decision("interest", "keep", "primary", 0.92),
                        decision("unemployment", "supporting", "context", 0.70),
                        decision("electrification", "supporting", "context", 0.69)),
                10,
                plan);

        assertThat(out).extracting(r -> r.candidate().seriesId())
                .containsExactly("interest", "unemployment", "electrification");
    }

    private static SearchCandidate candidate(String id) {
        return candidate("fred", id);
    }

    private static SearchCandidate candidate(String source, String id) {
        return candidate(source, id, id, List.of());
    }

    private static SearchCandidate candidate(String source, String id, String title, List<String> concepts) {
        return new SearchCandidate(
                source + ":" + id,
                id,
                title,
                "",
                source,
                "",
                "",
                "",
                "",
                "",
                concepts,
                concepts,
                List.of(),
                "",
                1.0,
                "query",
                List.of(),
                Map.of());
    }

    private static SearchCandidate candidateWithVectorScore(String source, String id, String title, double vectorScore) {
        return new SearchCandidate(
                source + ":" + id,
                id,
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
                Map.of("_vector_score", vectorScore));
    }

    private static SemanticDecision decision(String id, String decision, String role, double relevance) {
        return new SemanticDecision(id, decision, relevance, 0.9, List.of(), List.of(), "reason", role);
    }

    private static SearchQueryPlan exactPlan(List<String> preferredSources) {
        ExactEntityResolution resolution = new ExactEntityResolution(
                "exact_entity",
                1.0,
                "fx_pair",
                "EUR/USD",
                List.of("EUR/USD", "EURUSD"),
                List.of("eur usd"),
                "fx",
                preferredSources,
                List.of("EUR/USD", "EURUSD", "EUR USD"),
                List.of(),
                false,
                "test",
                Map.of());
        return new SearchQueryPlan(
                "EUR/USD",
                "en",
                "find_series",
                List.of("EUR/USD"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of("EUR/USD"),
                List.of("exchange rate"),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "exact_entity_resolver",
                null,
                resolution,
                new SourceRoutingDecision(List.of("fx"), preferredSources, List.of(), Map.of()),
                List.of(new SearchQueryVariant("EUR/USD", "original_exact", 1.0)));
    }

    private static SearchQueryPlan conceptPlan(String concept) {
        return new SearchQueryPlan(
                "urokova mira nemecko",
                "cs",
                "find_series",
                List.of(concept),
                List.of(concept),
                List.of("DE"),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of("urokova mira nemecko", "interest rate Germany"),
                List.of(concept),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "openai",
                "test",
                ExactEntityResolution.openTopic("test"),
                SourceRoutingDecision.empty(),
                List.of());
    }

    private static SearchQueryPlan planWithMetricIntent(
            String query, List<String> metricIntents, List<String> institutionalSectors) {
        return new SearchQueryPlan(
                query,
                "cs",
                "find_series",
                List.of(query),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(query),
                List.of(query),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                "openai",
                "test",
                ExactEntityResolution.openTopic("test"),
                SourceRoutingDecision.empty(),
                List.of(),
                Map.of(),
                Map.of(),
                institutionalSectors,
                metricIntents);
    }
}
