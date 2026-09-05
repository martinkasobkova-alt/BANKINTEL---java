package cz.bankintel.search.v2.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogDeepSearchService;
import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.v2.orchestration.SearchV2Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SearchV2Evaluator {

    private static final int DEFAULT_MAX = 40;
    private static final int RESULT_LIMIT = 20;
    private static final Set<String> DIMENSION_SELECTABLE_SOURCES =
            Set.of("eurostat", "ecb2", "bis", "imf", "oecd4", "data360", "worldbank");

    private final SearchV2GoldQueries goldQueries;
    private final SearchV2Service searchV2Service;
    private final CatalogDeepSearchService catalogDeepSearchService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> evaluate(Map<String, Object> request) {
        Map<String, Object> payload = request == null ? Map.of() : request;
        int max = CatalogMapSupport.toInt(payload.get("max"), DEFAULT_MAX);
        boolean useAi = parseBoolean(payload.get("use_ai"), true);
        boolean diagnoseRetrieval = parseBoolean(payload.get("diagnose_retrieval"), false);
        boolean skipV1 = parseBoolean(payload.get("skip_v1"), false);
        boolean includeLlmVariants = parseBoolean(payload.get("include_llm_variants"), true);
        String mode = evalMode(payload);
        int previewTopN = Math.max(1, Math.min(8, CatalogMapSupport.toInt(payload.get("preview_top_n"), 5)));
        List<SearchV2GoldQuery> gold = goldQueries.load().stream().limit(Math.max(1, max)).toList();
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SearchV2GoldQuery item : gold) {
            rows.add(evaluateOne(item, useAi, mode, previewTopN, diagnoseRetrieval, skipV1, includeLlmVariants));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("v1", summary(rows, "v1"));
        summary.put("v2", summary(rows, "v2"));
        summary.put("delta", delta(summary));
        if (diagnoseRetrieval) {
            summary.put("retrieval_variants", variantSummary(rows));
            summary.put("failure_buckets", failureBucketSummary(rows));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("search_engine", "v1_vs_v2");
        out.put("mode", mode);
        out.put("preview_top_n", previewTopN);
        out.put("query_count", rows.size());
        out.put("rows", rows);
        out.put("summary", summary);
        out.put("diagnose_retrieval", diagnoseRetrieval);
        out.put("skip_v1", skipV1);
        out.put("include_llm_variants", includeLlmVariants);
        out.put("latency_ms", System.currentTimeMillis() - start);
        out.put("artifacts", artifactPaths());
        if (parseBoolean(payload.get("write_artifacts"), true)) {
            writeArtifacts(out);
        }
        return out;
    }

    private Map<String, Object> evaluateOne(
            SearchV2GoldQuery gold,
            boolean useAi,
            String mode,
            int previewTopN,
            boolean diagnoseRetrieval,
            boolean skipV1,
            boolean includeLlmVariants) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", gold.id());
        row.put("query", gold.query());
        row.put("intent", gold.intent());
        row.put("expected_geo", gold.expectedGeos());
        row.put("expected_concepts", gold.expectedConceptSignals());
        row.put("required_source", gold.requiredSource());
        row.put("v1", skipV1 ? Map.of("status", "skipped", "empty_result", true) : evaluateEngine("v1", gold, useAi, mode, previewTopN));
        row.put("v2", evaluateEngine("v2", gold, useAi, mode, previewTopN));
        if (diagnoseRetrieval) {
            Map<String, Object> variants = new LinkedHashMap<>();
            variants.put("A_legacy_fallback", evaluateEngine("v2", gold, false, mode, previewTopN, "legacy"));
            variants.put("C_sidecar_fallback", evaluateEngine("v2", gold, false, mode, previewTopN, "sidecar"));
            if (includeLlmVariants) {
                variants.put("B_legacy_llm", evaluateEngine("v2", gold, true, mode, previewTopN, "legacy"));
                variants.put("D_sidecar_llm", evaluateEngine("v2", gold, true, mode, previewTopN, "sidecar"));
            }
            row.put("retrieval_variants", variants);
        }
        return row;
    }

    private Map<String, Object> evaluateEngine(
            String engine, SearchV2GoldQuery gold, boolean useAi, String mode, int previewTopN) {
        return evaluateEngine(engine, gold, useAi, mode, previewTopN, "");
    }

    private Map<String, Object> evaluateEngine(
            String engine, SearchV2GoldQuery gold, boolean useAi, String mode, int previewTopN, String indexMode) {
        long start = System.currentTimeMillis();
        Map<String, Object> result;
        try {
            result = "v2".equals(engine)
                    ? searchV2Service.search(v2Payload(gold, useAi, mode, previewTopN, indexMode))
                    : catalogDeepSearchService.deepSearch(v1Payload(gold, useAi, mode, previewTopN));
        } catch (Exception ex) {
            result = new LinkedHashMap<>();
            result.put("ok", false);
            result.put("status", "error");
            result.put("error", ex.getMessage());
            result.put("results", List.of());
        }
        long latencyMs = System.currentTimeMillis() - start;
        List<Map<String, Object>> results = extractResults(result);
        boolean clarification = result.get("clarification") instanceof Map<?, ?> clarificationMap
                && Boolean.TRUE.equals(CatalogMapSupport.castMap(clarificationMap).get("required"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", CatalogMapSupport.firstNonBlank(result.get("status"), result.get("ok")));
        out.put("precision_at_5", precisionAt(gold, results, 5));
        out.put("precision_at_10", precisionAt(gold, results, 10));
        out.put("recall_at_20", recallAt20(gold, results));
        out.put("mrr", mrr(gold, results));
        out.put("ndcg_at_10", ndcgAt(gold, results, 10));
        out.put("irrelevant_top_10", irrelevantCount(gold, results, 10));
        out.put("empty_result", results.isEmpty());
        out.put("clarification_expected", gold.expectsClarification());
        out.put("clarification_actual", clarification);
        out.put("source_constraint_applies", sourceConstraintApplies(gold));
        out.put("source_constraint_ok", sourceConstraintOk(gold, results));
        out.put("relevance_label", relevanceLabel(CatalogMapSupport.toDouble(out.get("precision_at_5")), CatalogMapSupport.toDouble(out.get("mrr"))));
        out.put("fallback_used", fallbackUsed(result));
        out.put("preview_failed", previewFailed(result));
        out.put("preview_mode", CatalogMapSupport.firstNonBlank(result.get("preview_mode"), mode));
        out.put("latency_ms", latencyMs);
        out.put("planner_latency_ms", timing(result, "planner_ms", "plan_ms"));
        out.put("fts_latency_ms", timing(result, "fts_ms", "lanes_ms"));
        out.put("reranker_latency_ms", timing(result, "reranker_ms"));
        out.put("preview_latency_ms", timing(result, "preview_verification_ms", "preview_ms"));
        out.put("llm_calls", llmCalls(result));
        out.put("token_usage_estimate", CatalogMapSupport.toInt(result.get("semantic_prompt_tokens_estimate"), 0));
        out.put("catalog_index_mode", CatalogMapSupport.firstNonBlank(result.get("catalog_index_mode"), indexMode));
        Map<String, Object> retrieval = retrievalDiagnostics(result);
        out.put("candidate_recall_at_20", candidateRecallAt(gold, topList(retrieval, "pre_merge_top_200"), 20));
        out.put("candidate_recall_at_50", candidateRecallAt(gold, topList(retrieval, "pre_merge_top_200"), 50));
        out.put("candidate_recall_at_100", candidateRecallAt(gold, topList(retrieval, "pre_merge_top_200"), 100));
        out.put("rank_relevant_pre_merge", rankRelevant(gold, topList(retrieval, "pre_merge_top_200")));
        out.put("rank_relevant_merged", rankRelevant(gold, topList(retrieval, "merged_top_200")));
        out.put("retrieval_failure_bucket", retrievalFailureBucket(gold, result, results));
        out.put("top_source", first(results, "source"));
        out.put("top_title", first(results, "title"));
        out.put("top_series", first(results, "series_id"));
        out.put("errors", errors(result));
        out.put("top_10", compactTop(results, 10));
        return out;
    }

    private static Map<String, Object> v2Payload(
            SearchV2GoldQuery gold, boolean useAi, String mode, int previewTopN, String indexMode) {
        Map<String, Object> payload = basePayload(gold, useAi, mode, previewTopN);
        payload.put("search_engine_version", "v2");
        payload.put("debug", true);
        payload.put("include_retrieval_diagnostics", true);
        payload.put("no_cache", true);
        if (indexMode != null && !indexMode.isBlank()) {
            payload.put("search_catalog_index", indexMode);
        }
        return payload;
    }

    private static Map<String, Object> v1Payload(
            SearchV2GoldQuery gold, boolean useAi, String mode, int previewTopN) {
        Map<String, Object> payload = basePayload(gold, useAi, mode, previewTopN);
        payload.put("limit_per_source", 6);
        if (!"full".equals(mode)) {
            payload.put("metadata_only", true);
        }
        return payload;
    }

    private static Map<String, Object> basePayload(
            SearchV2GoldQuery gold, boolean useAi, String mode, int previewTopN) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", gold.query());
        payload.put("use_ai", useAi);
        payload.put("limit", RESULT_LIMIT);
        payload.put("eval_mode", mode);
        payload.put("preview_top_n", previewTopN);
        if (gold.requiredSource() != null && !gold.requiredSource().isBlank()) {
            payload.put("sources", gold.requiredSource());
        }
        return payload;
    }

    private static Map<String, Object> summary(List<Map<String, Object>> rows, String engine) {
        List<Map<String, Object>> metrics = rows.stream()
                .map(row -> row.get(engine))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(CatalogMapSupport::castMap)
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("precision_at_5", avg(metrics, "precision_at_5"));
        out.put("precision_at_10", avg(metrics, "precision_at_10"));
        out.put("recall_at_20", avg(metrics, "recall_at_20"));
        out.put("mrr", avg(metrics, "mrr"));
        out.put("ndcg_at_10", avg(metrics, "ndcg_at_10"));
        out.put("irrelevant_top_10", sumInt(metrics, "irrelevant_top_10"));
        out.put("empty_results", countTrue(metrics, "empty_result"));
        out.put("clarification_queries", countTrue(metrics, "clarification_actual"));
        out.put("clarification_accuracy", clarificationAccuracy(metrics));
        out.put("source_constraint_accuracy", sourceConstraintAccuracy(metrics));
        out.put("median_latency_ms", percentile(metrics, "latency_ms", 0.50));
        out.put("p95_latency_ms", percentile(metrics, "latency_ms", 0.95));
        out.put("planner_latency_ms", avg(metrics, "planner_latency_ms"));
        out.put("fts_latency_ms", avg(metrics, "fts_latency_ms"));
        out.put("reranker_latency_ms", avg(metrics, "reranker_latency_ms"));
        out.put("preview_latency_ms", avg(metrics, "preview_latency_ms"));
        out.put("llm_calls", sumInt(metrics, "llm_calls"));
        out.put("avg_token_usage", avg(metrics, "token_usage_estimate"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> variantSummary(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> byVariant = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object raw = row.get("retrieval_variants");
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> metrics) {
                    byVariant.computeIfAbsent(String.valueOf(entry.getKey()), ignored -> new ArrayList<>())
                            .add(CatalogMapSupport.castMap(metrics));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byVariant.entrySet()) {
            List<Map<String, Object>> metrics = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("candidate_recall_at_20", avg(metrics, "candidate_recall_at_20"));
            row.put("candidate_recall_at_50", avg(metrics, "candidate_recall_at_50"));
            row.put("candidate_recall_at_100", avg(metrics, "candidate_recall_at_100"));
            row.put("precision_at_5", avg(metrics, "precision_at_5"));
            row.put("mrr", avg(metrics, "mrr"));
            row.put("ndcg_at_10", avg(metrics, "ndcg_at_10"));
            row.put("empty_results", countTrue(metrics, "empty_result"));
            row.put("source_constraint_accuracy", sourceConstraintAccuracy(metrics));
            row.put("median_latency_ms", percentile(metrics, "latency_ms", 0.50));
            row.put("p95_latency_ms", percentile(metrics, "latency_ms", 0.95));
            out.put(entry.getKey(), row);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> failureBucketSummary(List<Map<String, Object>> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int totalFailures = 0;
        for (Map<String, Object> row : rows) {
            Object raw = row.get("retrieval_variants");
            if (!(raw instanceof Map<?, ?> variants)) {
                continue;
            }
            Object fallback = variants.get("A_legacy_fallback");
            if (!(fallback instanceof Map<?, ?> metrics)) {
                continue;
            }
            String bucket = CatalogMapSupport.str(CatalogMapSupport.castMap(metrics).get("retrieval_failure_bucket"));
            if (bucket.isBlank() || "ok".equals(bucket)) {
                continue;
            }
            counts.put(bucket, counts.getOrDefault(bucket, 0) + 1);
            totalFailures++;
        }
        int retrieval = 0;
        int reranking = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if ("relevant_series_retrieved_but_reranked_low".equals(entry.getKey())) {
                reranking += entry.getValue();
            } else {
                retrieval += entry.getValue();
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", counts);
        out.put("retrieval_failure_count", retrieval);
        out.put("reranking_failure_count", reranking);
        out.put("retrieval_failure_share", totalFailures == 0 ? 0.0 : retrieval / (double) totalFailures);
        out.put("reranking_failure_share", totalFailures == 0 ? 0.0 : reranking / (double) totalFailures);
        return out;
    }

    private static Map<String, Object> delta(Map<String, Object> summary) {
        Map<String, Object> v1 = CatalogMapSupport.castMap((Map<?, ?>) summary.get("v1"));
        Map<String, Object> v2 = CatalogMapSupport.castMap((Map<?, ?>) summary.get("v2"));
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("precision_at_5", "precision_at_10", "recall_at_20", "mrr", "ndcg_at_10")) {
            out.put(key, CatalogMapSupport.toDouble(v2.get(key)) - CatalogMapSupport.toDouble(v1.get(key)));
        }
        out.put("median_latency_ms", CatalogMapSupport.toDouble(v2.get("median_latency_ms")) - CatalogMapSupport.toDouble(v1.get("median_latency_ms")));
        out.put("p95_latency_ms", CatalogMapSupport.toDouble(v2.get("p95_latency_ms")) - CatalogMapSupport.toDouble(v1.get("p95_latency_ms")));
        return out;
    }

    private static double precisionAt(SearchV2GoldQuery gold, List<Map<String, Object>> results, int k) {
        if (gold.expectsClarification()) {
            return results.isEmpty() ? 1.0 : 0.0;
        }
        int limit = Math.min(k, results.size());
        if (limit == 0) {
            return 0.0;
        }
        int relevant = 0;
        for (int i = 0; i < limit; i++) {
            if (isRelevant(gold, results.get(i))) {
                relevant++;
            }
        }
        return relevant / (double) k;
    }

    private static double recallAt20(SearchV2GoldQuery gold, List<Map<String, Object>> results) {
        List<String> series = gold.relevantSeries();
        if (series.isEmpty()) {
            return precisionAt(gold, results, Math.min(20, Math.max(1, results.size())));
        }
        int found = 0;
        for (String expected : series) {
            for (Map<String, Object> result : results.stream().limit(20).toList()) {
                String actual = CatalogMapSupport.firstNonBlank(result.get("series_id"), result.get("set_id"), result.get("id"));
                if (!actual.isBlank() && actual.equalsIgnoreCase(expected)) {
                    found++;
                    break;
                }
            }
        }
        return found / (double) series.size();
    }

    private static double mrr(SearchV2GoldQuery gold, List<Map<String, Object>> results) {
        for (int i = 0; i < results.size(); i++) {
            if (isRelevant(gold, results.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private static double ndcgAt(SearchV2GoldQuery gold, List<Map<String, Object>> results, int k) {
        double dcg = 0.0;
        int relevantTotal = 0;
        for (int i = 0; i < Math.min(k, results.size()); i++) {
            int rel = isRelevant(gold, results.get(i)) ? 1 : 0;
            relevantTotal += rel;
            dcg += rel / (Math.log(i + 2) / Math.log(2));
        }
        if (relevantTotal == 0) {
            return 0.0;
        }
        double idcg = 0.0;
        for (int i = 0; i < relevantTotal; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return dcg / idcg;
    }

    private static int irrelevantCount(SearchV2GoldQuery gold, List<Map<String, Object>> results, int k) {
        int count = 0;
        for (Map<String, Object> result : results.stream().limit(k).toList()) {
            if (!isRelevant(gold, result)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isRelevant(SearchV2GoldQuery gold, Map<String, Object> result) {
        String source = CatalogMapSupport.str(result.get("source"));
        if (gold.requiredSource() != null && !gold.requiredSource().isBlank() && !gold.requiredSource().equalsIgnoreCase(source)) {
            return false;
        }
        if (gold.forbiddenSources() != null && containsIgnoreCase(gold.forbiddenSources(), source)) {
            return false;
        }
        if (gold.acceptableSources() != null && !gold.acceptableSources().isEmpty() && !containsIgnoreCase(gold.acceptableSources(), source)) {
            return false;
        }
        String actualId = CatalogMapSupport.firstNonBlank(result.get("series_id"), result.get("set_id"), result.get("id"));
        if (!actualId.isBlank() && containsIgnoreCase(gold.relevantSeries(), actualId)) {
            return true;
        }
        if (!geoCompatible(gold, result)) {
            return false;
        }
        String haystack = folded(result);
        for (String forbidden : gold.forbiddenConceptSignals()) {
            if (!forbidden.isBlank() && haystack.contains(fold(forbidden))) {
                return false;
            }
        }
        List<String> expected = gold.expectedConceptSignals();
        return expected.isEmpty() || expected.stream().map(SearchV2Evaluator::fold).anyMatch(haystack::contains);
    }

    private static boolean geoCompatible(SearchV2GoldQuery gold, Map<String, Object> result) {
        List<String> expected = gold.expectedGeos();
        if (expected.isEmpty()) {
            return true;
        }
        String geo = CatalogMapSupport.firstNonBlank(
                result.get("geo"), result.get("country"), result.get("ref_area"), result.get("geography"));
        if (geo.isBlank()) {
            return true;
        }
        String foldedGeo = fold(geo);
        String source = CatalogMapSupport.str(result.get("source")).trim().toLowerCase(Locale.ROOT);
        if ("global".equals(foldedGeo) && DIMENSION_SELECTABLE_SOURCES.contains(source)) {
            return true;
        }
        return expected.stream().map(SearchV2Evaluator::fold).anyMatch(expectedGeo ->
                foldedGeo.equals(expectedGeo) || foldedGeo.contains(expectedGeo) || expectedGeo.contains(foldedGeo));
    }

    private static String folded(Map<String, Object> result) {
        String text = String.join(" ",
                CatalogMapSupport.str(result.get("title")),
                CatalogMapSupport.str(result.get("name")),
                CatalogMapSupport.str(result.get("description")),
                CatalogMapSupport.str(result.get("dataset")),
                CatalogMapSupport.str(result.get("geo")),
                CatalogMapSupport.str(result.get("country")),
                CatalogMapSupport.str(result.get("concepts")),
                CatalogMapSupport.str(result.get("tags")),
                CatalogMapSupport.str(result.get("primary_concept")),
                CatalogMapSupport.str(result.get("secondary_concepts")),
                CatalogMapSupport.str(result.get("measure_type")),
                CatalogMapSupport.str(result.get("economic_object")),
                CatalogMapSupport.str(result.get("catalog_family")),
                CatalogMapSupport.str(result.get("dataset_family")),
                CatalogMapSupport.str(result.get("category_path")));
        return fold(text);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractResults(Map<String, Object> result) {
        List<Map<String, Object>> out = resultList(result.get("results"));
        if (!out.isEmpty()) {
            return out;
        }
        out = new ArrayList<>();
        out.addAll(resultList(result.get("verified")));
        out.addAll(resultList(result.get("possible")));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resultList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(CatalogMapSupport.castMap(map));
            }
        }
        return out;
    }

    private static Map<String, Object> retrievalDiagnostics(Map<String, Object> result) {
        Object raw = result.get("retrieval_diagnostics");
        if (raw instanceof Map<?, ?> map) {
            return CatalogMapSupport.castMap(map);
        }
        return Map.of();
    }

    private static List<Map<String, Object>> topList(Map<String, Object> retrieval, String key) {
        return resultList(retrieval.get(key));
    }

    private static double candidateRecallAt(SearchV2GoldQuery gold, List<Map<String, Object>> candidates, int k) {
        if (gold.expectsClarification()) {
            return 1.0;
        }
        List<String> series = gold.relevantSeries();
        List<Map<String, Object>> window = candidates.stream().limit(k).toList();
        if (series.isEmpty()) {
            return window.stream().anyMatch(candidate -> isRelevant(gold, candidate)) ? 1.0 : 0.0;
        }
        int found = 0;
        for (String expected : series) {
            for (Map<String, Object> candidate : window) {
                String actual = CatalogMapSupport.firstNonBlank(candidate.get("series_id"), candidate.get("set_id"), candidate.get("id"));
                if (!actual.isBlank() && actual.equalsIgnoreCase(expected)) {
                    found++;
                    break;
                }
            }
        }
        return found / (double) series.size();
    }

    private static int rankRelevant(SearchV2GoldQuery gold, List<Map<String, Object>> candidates) {
        int rank = 1;
        for (Map<String, Object> candidate : candidates == null ? List.<Map<String, Object>>of() : candidates) {
            if (isRelevant(gold, candidate)) {
                return rank;
            }
            rank++;
        }
        return -1;
    }

    private static String retrievalFailureBucket(
            SearchV2GoldQuery gold, Map<String, Object> result, List<Map<String, Object>> finalResults) {
        if (gold.expectsClarification()) {
            return "not_applicable_clarification";
        }
        Map<String, Object> retrieval = retrievalDiagnostics(result);
        List<Map<String, Object>> preMerge = topList(retrieval, "pre_merge_top_200");
        List<Map<String, Object>> merged = topList(retrieval, "merged_top_200");
        int preRank = rankRelevant(gold, preMerge);
        int mergedRank = rankRelevant(gold, merged);
        int finalRank = rankRelevant(gold, finalResults);
        if (finalRank > 0 && finalRank <= 10) {
            return "ok";
        }
        if (preMerge.isEmpty() && sourceHadNoCandidates(result, gold)) {
            return "relevant_series_not_ingested";
        }
        if (preRank < 0) {
            if (sourceConstraintApplies(gold) && !sourceQueried(result, gold.requiredSource())) {
                return "wrong_source_metadata";
            }
            if (!geoCandidatesCompatible(gold, preMerge)) {
                return "wrong_geo_metadata";
            }
            return "relevant_series_not_retrieved";
        }
        if (mergedRank < 0) {
            return "relevant_series_retrieved_but_truncated";
        }
        if (finalRank < 0 || finalRank > 10) {
            return "relevant_series_retrieved_but_reranked_low";
        }
        if (duplicateSeriesCount(merged) > 0) {
            return "duplicate_or_variant_problem";
        }
        return "metadata_too_ambiguous";
    }

    private static boolean sourceHadNoCandidates(Map<String, Object> result, SearchV2GoldQuery gold) {
        String required = gold.requiredSource();
        if (required == null || required.isBlank()) {
            return false;
        }
        for (Map<String, Object> stat : resultList(CatalogMapSupport.castMap(retrievalDiagnostics(result)).get("query_stats"))) {
            if (required.equalsIgnoreCase(CatalogMapSupport.str(stat.get("source")))
                    && CatalogMapSupport.toInt(stat.get("count"), 0) > 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean sourceQueried(Map<String, Object> result, String requiredSource) {
        if (requiredSource == null || requiredSource.isBlank()) {
            return true;
        }
        Map<String, Object> retrieval = retrievalDiagnostics(result);
        for (Map<String, Object> stat : resultList(retrieval.get("query_stats"))) {
            if (requiredSource.equalsIgnoreCase(CatalogMapSupport.str(stat.get("source")))) {
                return true;
            }
        }
        return false;
    }

    private static boolean geoCandidatesCompatible(SearchV2GoldQuery gold, List<Map<String, Object>> candidates) {
        List<String> expected = gold.expectedGeos();
        if (expected.isEmpty() || candidates.isEmpty()) {
            return true;
        }
        return candidates.stream().anyMatch(candidate -> geoCompatible(gold, candidate));
    }

    private static int duplicateSeriesCount(List<Map<String, Object>> candidates) {
        Set<String> seen = new java.util.HashSet<>();
        int duplicates = 0;
        for (Map<String, Object> candidate : candidates == null ? List.<Map<String, Object>>of() : candidates) {
            String key = CatalogMapSupport.firstNonBlank(candidate.get("source"), "")
                    + ":"
                    + CatalogMapSupport.firstNonBlank(candidate.get("series_id"), candidate.get("set_id"), candidate.get("id"));
            if (!key.isBlank() && !seen.add(key.toLowerCase(Locale.ROOT))) {
                duplicates++;
            }
        }
        return duplicates;
    }

    private static List<Map<String, Object>> compactTop(List<Map<String, Object>> results, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> result : results.stream().limit(limit).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank++);
            row.put("source", result.get("source"));
            row.put("series_id", CatalogMapSupport.firstNonBlank(result.get("series_id"), result.get("set_id"), result.get("id")));
            row.put("title", CatalogMapSupport.firstNonBlank(result.get("title"), result.get("name")));
            row.put("geo", result.get("geo"));
            row.put("role", CatalogMapSupport.firstNonBlank(result.get("role"), result.get("result_role")));
            out.add(row);
        }
        return out;
    }

    private void writeArtifacts(Map<String, Object> report) {
        try {
            Path root = projectRoot();
            Files.createDirectories(root.resolve("outputs"));
            Files.createDirectories(root.resolve("docs"));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("outputs/search_eval_v1_vs_v2.json").toFile(), report);
            Files.writeString(root.resolve("outputs/search_eval_v1_vs_v2.csv"), csv(report), StandardCharsets.UTF_8);
            Files.writeString(root.resolve("docs/archive/search_v2_evaluation_report.md"), markdown(report), StandardCharsets.UTF_8);
            if (Boolean.TRUE.equals(report.get("diagnose_retrieval"))) {
                Files.writeString(
                        root.resolve("outputs/search_v2_retrieval_diagnosis.csv"),
                        retrievalDiagnosisCsv(report),
                        StandardCharsets.UTF_8);
                Files.writeString(
                        root.resolve("docs/archive/search_v2_retrieval_diagnosis.md"),
                        retrievalDiagnosisMarkdown(report),
                        StandardCharsets.UTF_8);
            }
        } catch (Exception ex) {
            report.put("artifact_error", ex.getMessage());
        }
    }

    private static Map<String, Object> artifactPaths() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("json", "outputs/search_eval_v1_vs_v2.json");
        out.put("csv", "outputs/search_eval_v1_vs_v2.csv");
        out.put("markdown", "docs/archive/search_v2_evaluation_report.md");
        out.put("retrieval_diagnosis_csv", "outputs/search_v2_retrieval_diagnosis.csv");
        out.put("retrieval_diagnosis_markdown", "docs/archive/search_v2_retrieval_diagnosis.md");
        return out;
    }

    private static Path projectRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("backend-java"))) {
            return cwd;
        }
        if (cwd.getFileName() != null && "backend-java".equals(cwd.getFileName().toString())) {
            return cwd.getParent();
        }
        return cwd;
    }

    @SuppressWarnings("unchecked")
    private static String csv(Map<String, Object> report) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) report.getOrDefault("rows", List.of());
        List<String> lines = new ArrayList<>();
        lines.add("id,query,v1_p5,v2_p5,v1_p10,v2_p10,v1_mrr,v2_mrr,v1_latency_ms,v2_latency_ms,v1_status,v2_status,v1_top,v2_top");
        for (Map<String, Object> row : rows) {
            Map<String, Object> v1 = CatalogMapSupport.castMap((Map<?, ?>) row.get("v1"));
            Map<String, Object> v2 = CatalogMapSupport.castMap((Map<?, ?>) row.get("v2"));
            lines.add(List.of(
                            csvCell(row.get("id")),
                            csvCell(row.get("query")),
                            csvCell(v1.get("precision_at_5")),
                            csvCell(v2.get("precision_at_5")),
                            csvCell(v1.get("precision_at_10")),
                            csvCell(v2.get("precision_at_10")),
                            csvCell(v1.get("mrr")),
                            csvCell(v2.get("mrr")),
                            csvCell(v1.get("latency_ms")),
                            csvCell(v2.get("latency_ms")),
                            csvCell(v1.get("status")),
                            csvCell(v2.get("status")),
                            csvCell(v1.get("top_title")),
                            csvCell(v2.get("top_title")))
                    .stream()
                    .collect(Collectors.joining(",")));
        }
        return String.join("\n", lines) + "\n";
    }

    @SuppressWarnings("unchecked")
    private static String markdown(Map<String, Object> report) {
        Map<String, Object> summary = CatalogMapSupport.castMap((Map<?, ?>) report.get("summary"));
        Map<String, Object> v1 = CatalogMapSupport.castMap((Map<?, ?>) summary.get("v1"));
        Map<String, Object> v2 = CatalogMapSupport.castMap((Map<?, ?>) summary.get("v2"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) report.getOrDefault("rows", List.of());
        StringBuilder md = new StringBuilder();
        md.append("# Search V2 Evaluation Report\n\n");
        md.append("- Mode: `").append(report.get("mode")).append("`\n");
        md.append("- Queries: ").append(report.get("query_count")).append("\n\n");
        md.append("| Metric | V1 | V2 |\n|---|---:|---:|\n");
        for (String key : List.of(
                "precision_at_5",
                "precision_at_10",
                "recall_at_20",
                "mrr",
                "ndcg_at_10",
                "median_latency_ms",
                "p95_latency_ms",
                "empty_results",
                "source_constraint_accuracy")) {
            md.append("| ").append(key).append(" | ").append(v1.get(key)).append(" | ").append(v2.get(key)).append(" |\n");
        }
        md.append("\n## Per Query Top Results\n\n");
        md.append("| ID | Query | V1 label | V1 top | V2 label | V2 top |\n|---|---|---|---|---|---|\n");
        for (Map<String, Object> row : rows) {
            Map<String, Object> r1 = CatalogMapSupport.castMap((Map<?, ?>) row.get("v1"));
            Map<String, Object> r2 = CatalogMapSupport.castMap((Map<?, ?>) row.get("v2"));
            md.append("| ")
                    .append(escapeMd(row.get("id")))
                    .append(" | ")
                    .append(escapeMd(row.get("query")))
                    .append(" | ")
                    .append(escapeMd(r1.get("relevance_label")))
                    .append(" | ")
                    .append(escapeMd(r1.get("top_title")))
                    .append(" | ")
                    .append(escapeMd(r2.get("relevance_label")))
                    .append(" | ")
                    .append(escapeMd(r2.get("top_title")))
                    .append(" |\n");
        }
        return md.toString();
    }

    @SuppressWarnings("unchecked")
    private static String retrievalDiagnosisCsv(Map<String, Object> report) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) report.getOrDefault("rows", List.of());
        List<String> lines = new ArrayList<>();
        lines.add("id,query,variant,index_mode,use_ai,candidate_recall_at_20,candidate_recall_at_50,candidate_recall_at_100,rank_relevant_pre_merge,rank_relevant_merged,precision_at_5,mrr,ndcg_at_10,latency_ms,failure_bucket,top_source,top_series,top_title");
        for (Map<String, Object> row : rows) {
            Object rawVariants = row.get("retrieval_variants");
            if (!(rawVariants instanceof Map<?, ?> variants)) {
                continue;
            }
            for (Map.Entry<?, ?> entry : variants.entrySet()) {
                Map<String, Object> metrics = CatalogMapSupport.castMap((Map<?, ?>) entry.getValue());
                String variant = String.valueOf(entry.getKey());
                lines.add(List.of(
                                csvCell(row.get("id")),
                                csvCell(row.get("query")),
                                csvCell(variant),
                                csvCell(metrics.get("catalog_index_mode")),
                                csvCell(variant.contains("_llm")),
                                csvCell(metrics.get("candidate_recall_at_20")),
                                csvCell(metrics.get("candidate_recall_at_50")),
                                csvCell(metrics.get("candidate_recall_at_100")),
                                csvCell(metrics.get("rank_relevant_pre_merge")),
                                csvCell(metrics.get("rank_relevant_merged")),
                                csvCell(metrics.get("precision_at_5")),
                                csvCell(metrics.get("mrr")),
                                csvCell(metrics.get("ndcg_at_10")),
                                csvCell(metrics.get("latency_ms")),
                                csvCell(metrics.get("retrieval_failure_bucket")),
                                csvCell(metrics.get("top_source")),
                                csvCell(metrics.get("top_series")),
                                csvCell(metrics.get("top_title")))
                        .stream()
                        .collect(Collectors.joining(",")));
            }
        }
        return String.join("\n", lines) + "\n";
    }

    @SuppressWarnings("unchecked")
    private static String retrievalDiagnosisMarkdown(Map<String, Object> report) {
        Map<String, Object> summary = CatalogMapSupport.castMap((Map<?, ?>) report.get("summary"));
        Map<String, Object> variants = CatalogMapSupport.castMap((Map<?, ?>) summary.get("retrieval_variants"));
        Map<String, Object> buckets = CatalogMapSupport.castMap((Map<?, ?>) summary.get("failure_buckets"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) report.getOrDefault("rows", List.of());
        StringBuilder md = new StringBuilder();
        md.append("# Search V2 Retrieval Diagnosis\n\n");
        md.append("- Mode: `").append(report.get("mode")).append("`\n");
        md.append("- Queries: ").append(report.get("query_count")).append("\n");
        md.append("- Retrieval failure share: ").append(buckets.get("retrieval_failure_share")).append("\n");
        md.append("- Reranking failure share: ").append(buckets.get("reranking_failure_share")).append("\n\n");
        md.append("## Variant Summary\n\n");
        md.append("| Variant | Candidate R@20 | Candidate R@50 | Candidate R@100 | P@5 | MRR | nDCG@10 | Empty | Median ms | P95 ms |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Map.Entry<String, Object> entry : variants.entrySet()) {
            Map<String, Object> row = CatalogMapSupport.castMap((Map<?, ?>) entry.getValue());
            md.append("| ")
                    .append(escapeMd(entry.getKey()))
                    .append(" | ")
                    .append(row.get("candidate_recall_at_20"))
                    .append(" | ")
                    .append(row.get("candidate_recall_at_50"))
                    .append(" | ")
                    .append(row.get("candidate_recall_at_100"))
                    .append(" | ")
                    .append(row.get("precision_at_5"))
                    .append(" | ")
                    .append(row.get("mrr"))
                    .append(" | ")
                    .append(row.get("ndcg_at_10"))
                    .append(" | ")
                    .append(row.get("empty_results"))
                    .append(" | ")
                    .append(row.get("median_latency_ms"))
                    .append(" | ")
                    .append(row.get("p95_latency_ms"))
                    .append(" |\n");
        }
        md.append("\n## Failure Buckets (A legacy fallback)\n\n");
        md.append("`").append(buckets.get("counts")).append("`\n\n");
        md.append("## Per Query Buckets\n\n");
        md.append("| ID | Query | A bucket | C bucket | A top | C top |\n|---|---|---|---|---|---|\n");
        for (Map<String, Object> row : rows) {
            Map<String, Object> rowVariants = CatalogMapSupport.castMap((Map<?, ?>) row.get("retrieval_variants"));
            Map<String, Object> a = CatalogMapSupport.castMap((Map<?, ?>) rowVariants.get("A_legacy_fallback"));
            Map<String, Object> c = CatalogMapSupport.castMap((Map<?, ?>) rowVariants.get("C_sidecar_fallback"));
            md.append("| ")
                    .append(escapeMd(row.get("id")))
                    .append(" | ")
                    .append(escapeMd(row.get("query")))
                    .append(" | ")
                    .append(escapeMd(a.get("retrieval_failure_bucket")))
                    .append(" | ")
                    .append(escapeMd(c.get("retrieval_failure_bucket")))
                    .append(" | ")
                    .append(escapeMd(a.get("top_title")))
                    .append(" | ")
                    .append(escapeMd(c.get("top_title")))
                    .append(" |\n");
        }
        return md.toString();
    }

    private static boolean sourceConstraintApplies(SearchV2GoldQuery gold) {
        return (gold.requiredSource() != null && !gold.requiredSource().isBlank())
                || (gold.forbiddenSources() != null && !gold.forbiddenSources().isEmpty());
    }

    private static boolean sourceConstraintOk(SearchV2GoldQuery gold, List<Map<String, Object>> results) {
        if (!sourceConstraintApplies(gold)) {
            return true;
        }
        if (results.isEmpty()) {
            return false;
        }
        return results.stream().limit(10).allMatch(result -> isSourceAllowed(gold, CatalogMapSupport.str(result.get("source"))));
    }

    private static boolean isSourceAllowed(SearchV2GoldQuery gold, String source) {
        if (gold.requiredSource() != null && !gold.requiredSource().isBlank()) {
            return gold.requiredSource().equalsIgnoreCase(source);
        }
        return gold.forbiddenSources() == null || !containsIgnoreCase(gold.forbiddenSources(), source);
    }

    private static boolean fallbackUsed(Map<String, Object> result) {
        String status = CatalogMapSupport.str(result.get("semantic_rerank_status")).toLowerCase(Locale.ROOT);
        return List.of("disabled", "unavailable", "partial").contains(status);
    }

    private static boolean previewFailed(Map<String, Object> result) {
        for (Map<String, Object> status : resultList(result.get("preview_verification"))) {
            if (Boolean.FALSE.equals(status.get("ok"))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> errors(Map<String, Object> result) {
        List<String> out = new ArrayList<>();
        String error = CatalogMapSupport.str(result.get("error"));
        if (!error.isBlank()) {
            out.add(error);
        }
        Object semantic = result.get("semantic_errors");
        if (semantic instanceof List<?> list) {
            list.stream().map(CatalogMapSupport::str).filter(s -> !s.isBlank()).forEach(out::add);
        }
        return out;
    }

    private static int llmCalls(Map<String, Object> result) {
        String model = CatalogMapSupport.str(result.get("semantic_model"));
        if (model.isBlank()) {
            return 0;
        }
        return resultList(result.get("semantic_batches")).size();
    }

    private static double timing(Map<String, Object> result, String... keys) {
        Object raw = result.get("timings");
        if (!(raw instanceof Map<?, ?> map)) {
            return 0.0;
        }
        Map<String, Object> timings = CatalogMapSupport.castMap(map);
        for (String key : keys) {
            double value = CatalogMapSupport.toDouble(timings.get(key));
            if (value != 0.0) {
                return value;
            }
        }
        return 0.0;
    }

    private static String relevanceLabel(double precisionAt5, double mrr) {
        if (precisionAt5 >= 0.6) {
            return "good";
        }
        if (mrr > 0.0) {
            return "partial";
        }
        return "bad";
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        return values != null && values.stream().anyMatch(v -> v != null && v.equalsIgnoreCase(value));
    }

    private static Object first(List<Map<String, Object>> results, String key) {
        return results.isEmpty() ? null : results.get(0).get(key);
    }

    private static double avg(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToDouble(row -> CatalogMapSupport.toDouble(row.get(key))).average().orElse(0.0);
    }

    private static int sumInt(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToInt(row -> CatalogMapSupport.toInt(row.get(key), 0)).sum();
    }

    private static long countTrue(List<Map<String, Object>> rows, String key) {
        return rows.stream().filter(row -> Boolean.TRUE.equals(row.get(key))).count();
    }

    private static double clarificationAccuracy(List<Map<String, Object>> rows) {
        return rows.stream()
                .filter(row -> Boolean.valueOf(String.valueOf(row.get("clarification_expected")))
                        == Boolean.valueOf(String.valueOf(row.get("clarification_actual"))))
                .count()
                / (double) Math.max(1, rows.size());
    }

    private static double sourceConstraintAccuracy(List<Map<String, Object>> rows) {
        List<Map<String, Object>> constrained = rows.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("source_constraint_applies")))
                .toList();
        if (constrained.isEmpty()) {
            return 1.0;
        }
        return constrained.stream().filter(row -> Boolean.TRUE.equals(row.get("source_constraint_ok"))).count()
                / (double) constrained.size();
    }

    private static double percentile(List<Map<String, Object>> rows, String key, double percentile) {
        List<Double> values = rows.stream()
                .map(row -> CatalogMapSupport.toDouble(row.get(key)))
                .sorted(Comparator.naturalOrder())
                .toList();
        if (values.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private static String evalMode(Map<String, Object> request) {
        String fromRequest = CatalogMapSupport.firstNonBlank(request.get("mode"), request.get("eval_mode"));
        String raw = fromRequest.isBlank() ? System.getenv().getOrDefault("SEARCH_EVAL_MODE", "metadata_only") : fromRequest;
        String mode = raw.trim().toLowerCase(Locale.ROOT);
        return List.of("metadata_only", "top_preview", "full").contains(mode) ? mode : "metadata_only";
    }

    private static boolean parseBoolean(Object raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (List.of("0", "false", "no", "off").contains(value)) {
            return false;
        }
        if (List.of("1", "true", "yes", "on").contains(value)) {
            return true;
        }
        return fallback;
    }

    private static String fold(String value) {
        return CatalogTextUtils.foldAscii(value == null ? "" : value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String csvCell(Object value) {
        String text = String.valueOf(value == null ? "" : value).replace("\"", "\"\"");
        return "\"" + text + "\"";
    }

    private static String escapeMd(Object value) {
        return String.valueOf(value == null ? "" : value).replace("|", "\\|").replace("\n", " ");
    }
}
