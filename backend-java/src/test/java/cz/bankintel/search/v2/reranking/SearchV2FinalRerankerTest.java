package cz.bankintel.search.v2.reranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.v2.ontology.SearchV2IndustrySectorRegistry;
import cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry;
import cz.bankintel.search.v2.schema.ExactEntityResolution;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SearchQueryVariant;
import cz.bankintel.search.v2.schema.SearchResult;
import cz.bankintel.search.v2.schema.SemanticDecision;
import cz.bankintel.search.v2.schema.SourceRoutingDecision;
import cz.bankintel.sources.eurostat.EurostatDimensionService;
import cz.bankintel.sources.eurostat.EurostatRateLimiter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2FinalRerankerTest {

    private final SearchV2MetricIntentRegistry metricIntentRegistry =
            new SearchV2MetricIntentRegistry(new ObjectMapper());
    private final SearchV2IndustrySectorRegistry industrySectorRegistry =
            new SearchV2IndustrySectorRegistry(new ObjectMapper());
    private final SearchV2FinalReranker reranker = new SearchV2FinalReranker(
            metricIntentRegistry,
            industrySectorRegistry,
            new EurostatDimensionService(new ObjectMapper(), new EurostatRateLimiter()));

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
    void industrySectionMatchBeatsSameIndexRowDifferentSectionCandidateEvenAtTiedScores() {
        // Root problem this covers: the catalog index has one row per dataset, not one per NACE
        // value, so a construction-industry series and an agriculture-industry series can land at
        // identical relevance when nothing else distinguishes them - the industry registry must break
        // that tie. Candidate titles deliberately share NO literal word with the Czech query (English
        // "construction"/"farming" vs Czech "stavebnictvi") so the pre-existing, higher-priority
        // metric-compatibility tier's raw query-token overlap ties at zero for both, isolating this
        // test to the industry tier specifically - only the cross-language registry match can decide it.
        SearchQueryPlan plan = planWithIndustrySector("zamestnanost ve stavebnictvi", List.of("F"));
        SearchCandidate construction = candidate("eurostat", "construction-emp", "Construction industry output", List.of());
        SearchCandidate agriculture = candidate("eurostat", "agriculture-emp", "Farming sector output", List.of());

        List<SearchResult> out = reranker.finalRank(
                List.of(agriculture, construction),
                List.of(
                        decision("agriculture-emp", "keep", "context", 0.50),
                        decision("construction-emp", "keep", "context", 0.50)),
                10,
                plan);

        assertThat(out).extracting(r -> r.candidate().seriesId())
                .as("matched NACE section (construction) must outrank a same-index-row different section (agriculture)")
                .containsExactly("construction-emp", "agriculture-emp");
    }

    @Test
    void differentRegisteredIndustrySectionIsPenalizedBelowUnknownIndustryCandidate() {
        // Same care as above: candidate titles share no literal token with the query, so only the
        // industry tier (not the metric tier's raw token-overlap add-on) decides this ordering.
        SearchQueryPlan plan = planWithIndustrySector("zamestnanost ve stavebnictvi", List.of("F"));
        SearchCandidate wrongSection = candidate("eurostat", "agriculture-emp", "Farming sector output", List.of());
        SearchCandidate neutralUnrelated = candidate("eurostat", "cpi-series", "Consumer price index", List.of());

        List<SearchResult> out = reranker.finalRank(
                List.of(wrongSection, neutralUnrelated),
                List.of(
                        decision("agriculture-emp", "keep", "context", 0.50),
                        decision("cpi-series", "keep", "context", 0.50)),
                10,
                plan);

        assertThat(out).extracting(r -> r.candidate().seriesId())
                .as("a candidate about a different registered section must rank below a neutral, industry-unknown candidate")
                .containsExactly("cpi-series", "agriculture-emp");
    }

    @Test
    void liveVerifiedNonZeroDataBeatsAnUnverifiedTextualMatchAtTiedScores() {
        // Root problem: a textual industry match alone doesn't guarantee the candidate dataset
        // actually HAS that industry's data (see the naio_10_pyp1620 empty-default bug this same plan
        // fixed at the dimension-resolution layer) - a candidate whose match is live-verified against
        // its own dimension metadata must outrank an equally-worded match that isn't verifiable.
        EurostatDimensionService mockEurostat = mock(EurostatDimensionService.class);
        when(mockEurostat.previewAvailableDimensions("verified_ds"))
                .thenReturn(Map.of("nace_r2", Map.of("values", List.of("F", "A"))));
        when(mockEurostat.resolvePreviewQueryParams(eq("verified_ds"), anyString()))
                .thenReturn(Map.of("geo", "CZ", "nace_r2", "A"));
        when(mockEurostat.combinationHasData(eq("verified_ds"), any())).thenReturn(true);
        when(mockEurostat.previewAvailableDimensions("unverifiable_ds"))
                .thenReturn(Map.of("nace_r2", Map.of("values", List.of("A", "B"))));
        SearchV2FinalReranker verifyingReranker =
                new SearchV2FinalReranker(metricIntentRegistry, industrySectorRegistry, mockEurostat);

        SearchQueryPlan plan = planWithIndustrySector("zamestnanost ve stavebnictvi", List.of("F"));
        SearchCandidate verified = eurostatCandidate("verified-construction", "Zamestnanost ve stavebnictvi", "verified_ds");
        SearchCandidate unverified = eurostatCandidate("unverified-construction", "Zamestnanost ve stavebnictvi", "unverifiable_ds");

        List<SearchResult> out = verifyingReranker.finalRank(
                List.of(unverified, verified),
                List.of(
                        decision("unverified-construction", "keep", "context", 0.50),
                        decision("verified-construction", "keep", "context", 0.50)),
                10,
                plan);

        assertThat(out).extracting(r -> r.candidate().seriesId())
                .as("live-verified real-data match must outrank an equally-worded but unverifiable match")
                .containsExactly("verified-construction", "unverified-construction");
    }

    @Test
    void industryVerificationNeverRunsWhenTheQueryResolvedNoIndustrySection() {
        // Guards the precompute's early-exit: a mock that would fail any interaction proves the
        // (potentially live-network) verification path is never even touched for an ordinary query.
        EurostatDimensionService strictMockEurostat = mock(EurostatDimensionService.class);
        SearchV2FinalReranker plainReranker =
                new SearchV2FinalReranker(metricIntentRegistry, industrySectorRegistry, strictMockEurostat);
        SearchCandidate a = candidate("eurostat", "series-a");
        SearchCandidate b = candidate("eurostat", "series-b");

        List<SearchResult> out = plainReranker.finalRank(
                List.of(a, b),
                List.of(decision("series-a", "keep", "context", 0.5), decision("series-b", "keep", "context", 0.4)),
                10,
                planWithIndustrySector("urokova mira", List.of()));

        assertThat(out).extracting(r -> r.candidate().seriesId()).containsExactly("series-a", "series-b");
        org.mockito.Mockito.verifyNoInteractions(strictMockEurostat);
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

    private static SearchCandidate eurostatCandidate(String id, String title, String dataset) {
        return new SearchCandidate(
                "eurostat:" + id,
                id,
                title,
                "",
                "eurostat",
                dataset,
                "CZ",
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
                metricIntents,
                List.of());
    }

    private static SearchQueryPlan planWithIndustrySector(String query, List<String> industrySectors) {
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
                List.of(),
                List.of(),
                industrySectors);
    }
}
