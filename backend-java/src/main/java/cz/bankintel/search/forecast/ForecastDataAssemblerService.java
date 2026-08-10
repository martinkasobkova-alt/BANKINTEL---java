package cz.bankintel.search.forecast;

import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.CatalogPreviewOrchestrator;
import cz.bankintel.search.analytics.AnalyticsSeriesLoader;
import cz.bankintel.service.timeseries.TimeSeriesResampler;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fetches the target + planner-discovered candidate exogenous series through the SAME preview
 * pipeline the UI chart uses ({@link CatalogPreviewOrchestrator#fetchRecords}), then normalizes
 * every one of them ({@link ForecastSeriesNormalizer}) into the generic request payload the
 * in-process Java model engine expects. This keeps forecasting on top of existing data
 * instead of a second, parallel data-loading path.
 *
 * <p>As of the feature-discovery-engine pass, this assembler no longer decides the FINAL
 * exogenous set itself — it hands the Java model engine the full discovered candidate pool
 * ({@code candidate_exog}) tagged with {@code concept}/{@code value_kind}/{@code
 * discovery_score}; the Java engine's quality scoring and backtest decide which candidates are
 * actually used. The only filtering still done here is a
 * basic "is this even fetchable data" sanity floor, not an economic-relevance decision.
 */
@Service
@RequiredArgsConstructor
public class ForecastDataAssemblerService {

    private static final Logger log = LoggerFactory.getLogger(ForecastDataAssemblerService.class);
    private static final int MIN_USABLE_OBSERVATIONS = 6;
    private static final int ANALYSIS_RECORD_LIMIT = 100_000;
    private static final int DEFAULT_CANDIDATE_FETCH_BUDGET_MS = 6_000;

    private final CatalogPreviewOrchestrator previewOrchestrator;
    private final CatalogIndexStore indexStore;

    // Candidate fan-out roughly doubled (top-2 per concept instead of top-1), so candidate series
    // are fetched concurrently to keep end-to-end request latency close to before.
    private final ExecutorService fetchExecutor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "forecast-candidate-fetch");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        fetchExecutor.shutdown();
        try {
            fetchExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public Map<String, Object> buildRequest(
            String targetSourceType,
            String targetSetId,
            String targetNameHint,
            String geoHint,
            Map<String, Object> targetQueryParams,
            Map<String, Object> targetDimensionFilters,
            String selectedIndicator,
            List<String> selectedIndicators,
            List<ForecastPlannerService.PredictorCandidate> predictors,
            List<String> horizons,
            int candidateFetchBudgetMs) {
        return buildRequest(
                targetSourceType,
                targetSetId,
                targetNameHint,
                geoHint,
                targetQueryParams,
                targetDimensionFilters,
                selectedIndicator,
                selectedIndicators,
                predictors,
                horizons,
                candidateFetchBudgetMs,
                "");
    }

    public Map<String, Object> buildRequest(
            String targetSourceType,
            String targetSetId,
            String targetNameHint,
            String geoHint,
            Map<String, Object> targetQueryParams,
            Map<String, Object> targetDimensionFilters,
            String selectedIndicator,
            List<String> selectedIndicators,
            List<ForecastPlannerService.PredictorCandidate> predictors,
            List<String> horizons,
            int candidateFetchBudgetMs,
            String targetFrequency) {

        Map<String, Object> targetIndexRow = indexStore.lookupRow(targetSourceType, targetSetId).orElse(Map.of());
        String targetName = firstNonBlank(targetNameHint, str(targetIndexRow.get("title")), str(targetIndexRow.get("name")), targetSetId);

        List<Map<String, Object>> targetRows = fetchRows(
                targetSourceType,
                targetSetId,
                targetQueryParams,
                targetDimensionFilters,
                selectedIndicator,
                selectedIndicators,
                false);
        ForecastSeriesNormalizer.NormalizedSeries targetSeries = ForecastSeriesNormalizer.normalize(
                targetRows, targetSourceType + ":" + targetSetId, targetName, targetSourceType, geoHint, null, null);
        // Frontend posila periodicitu, kterou ma uzivatel prave vybranou v grafu (Denni/Tydenni/
        // Mesicni...) - bez tohohle forecast vzdy pocital z nativni (typicky denni) frekvence bez
        // ohledu na to, co si uzivatel v grafu zvolil.
        if (targetFrequency != null && !targetFrequency.isBlank()) {
            targetSeries = resampleTargetSeries(targetSeries, targetFrequency);
        }

        Map<String, Object> targetInput = ForecastSeriesNormalizer.toSeriesInputMap(targetSeries, null, null, null, null);

        int budgetMs = candidateFetchBudgetMs > 0 ? candidateFetchBudgetMs : DEFAULT_CANDIDATE_FETCH_BUDGET_MS;
        List<Map<String, Object>> candidateExog =
                fetchCandidatePoolParallel(predictors, targetSourceType, targetSetId, geoHint, budgetMs);

        Map<String, Object> statExog = new LinkedHashMap<>();
        if (geoHint != null && !geoHint.isBlank()) {
            statExog.put("geo", geoHint);
        }
        statExog.put("source", targetSourceType);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("target", targetInput);
        request.put("hist_exog", List.of());
        request.put("futr_exog", List.of());
        request.put("candidate_exog", candidateExog);
        request.put("stat_exog", statExog);
        request.put("horizons", horizons == null ? List.of() : horizons);
        return request;
    }

    /**
     * Agreguje cílovou řadu na hrubší periodicitu (viz {@link TimeSeriesResampler}), stejnou, jakou
     * má uživatel právě vybranou v grafu. Beze změny (stejná reference), když je cílová frekvence
     * stejná/jemnější než nativní nebo se období nepodařilo naparsovat.
     */
    private static ForecastSeriesNormalizer.NormalizedSeries resampleTargetSeries(
            ForecastSeriesNormalizer.NormalizedSeries series, String targetFrequency) {
        Map<String, Double> values = AnalyticsSeriesLoader.observationsToValueMap(series.observations());
        Map<String, Double> resampled = TimeSeriesResampler.resample(values, series.frequency(), targetFrequency);
        if (resampled == values) {
            return series;
        }
        List<Map<String, Object>> observations = new ArrayList<>();
        for (Map.Entry<String, Double> entry : resampled.entrySet()) {
            Map<String, Object> obs = new LinkedHashMap<>();
            obs.put("date", entry.getKey());
            obs.put("value", entry.getValue());
            observations.add(obs);
        }
        return new ForecastSeriesNormalizer.NormalizedSeries(
                series.seriesId(),
                series.name(),
                series.source(),
                series.geo(),
                series.unit(),
                targetFrequency.trim().toUpperCase(java.util.Locale.ROOT),
                series.seasonalAdjustment(),
                observations,
                observations.size());
    }

    private List<Map<String, Object>> fetchCandidatePoolParallel(
            List<ForecastPlannerService.PredictorCandidate> predictors,
            String targetSourceType,
            String targetSetId,
            String geoHint,
            int candidateFetchBudgetMs) {
        List<String> usedKeys = new ArrayList<>();
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        for (ForecastPlannerService.PredictorCandidate candidate : predictors) {
            String key = candidate.sourceType() + ":" + candidate.setId();
            if (usedKeys.contains(key)
                    || (candidate.sourceType().equalsIgnoreCase(targetSourceType) && candidate.setId().equalsIgnoreCase(targetSetId))) {
                continue;
            }
            usedKeys.add(key);
            futures.add(CompletableFuture.supplyAsync(() -> fetchAndNormalizeCandidate(candidate, key, geoHint), fetchExecutor));
        }
        if (futures.isEmpty()) {
            return List.of();
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(candidateFetchBudgetMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException ex) {
            log.warn(
                    "forecast assembler: candidate fetch budget {}ms exhausted; using completed candidates only",
                    candidateFetchBudgetMs);
        } catch (ExecutionException ex) {
            log.warn("forecast assembler: candidate fetch failed: {}", ex.getMessage());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (CompletableFuture<Map<String, Object>> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
                continue;
            }
            try {
                Map<String, Object> result = future.getNow(null);
                if (result != null) {
                    out.add(result);
                }
            } catch (Exception ex) {
                log.debug("forecast assembler: completed candidate ignored: {}", ex.getMessage());
            }
        }
        return out;
    }

    private Map<String, Object> fetchAndNormalizeCandidate(
            ForecastPlannerService.PredictorCandidate candidate, String key, String geoHint) {
        try {
            Map<String, Object> exogDimensionFilters = (geoHint == null || geoHint.isBlank()) ? Map.of() : Map.of("geo", geoHint);
            List<Map<String, Object>> rows = fetchRows(
                    candidate.sourceType(),
                    candidate.setId(),
                    Map.of(),
                    exogDimensionFilters,
                    "",
                    List.of(),
                    true);
            if (rows.isEmpty()) {
                return null;
            }
            ForecastSeriesNormalizer.NormalizedSeries normalized = ForecastSeriesNormalizer.normalize(
                    rows, key, candidate.title(), candidate.sourceType(), geoHint, candidate.role(), null);
            if (normalized.usableObservationCount() < MIN_USABLE_OBSERVATIONS) {
                return null;
            }
            Map<String, Object> seriesInput = ForecastSeriesNormalizer.toSeriesInputMap(normalized, candidate.role(), null, null, null);
            seriesInput.put("concept", candidate.role());
            if (candidate.valueKind() != null && !candidate.valueKind().isBlank()) {
                seriesInput.put("value_kind", candidate.valueKind());
            }
            seriesInput.put("discovery_score", candidate.discoveryScore());
            return seriesInput;
        } catch (Exception ex) {
            log.warn("forecast assembler: failed to fetch/normalize candidate {}: {}", key, ex.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> fetchRows(
            String sourceType,
            String setId,
            Map<String, Object> queryParams,
            Map<String, Object> dimensionFilters,
            String selectedIndicator,
            List<String> selectedIndicators,
            boolean allowUnfilteredFallback) {
        try {
            Map<String, Object> payload = recordsPayload(sourceType, setId, queryParams, dimensionFilters, selectedIndicator, selectedIndicators);
            List<Map<String, Object>> rows = previewOrchestrator.fetchRecords(payload);
            if (rows.isEmpty()
                    && allowUnfilteredFallback
                    && dimensionFilters != null
                    && !dimensionFilters.isEmpty()) {
                // Geo code likely didn't match this dataset's dimension coding (e.g. label vs ISO2) —
                // retry unfiltered rather than silently dropping a usable series.
                log.debug("forecast assembler: dimension_filters {} yielded no rows for {}/{}, retrying unfiltered", dimensionFilters, sourceType, setId);
                return previewOrchestrator.fetchRecords(recordsPayload(sourceType, setId, queryParams, Map.of(), selectedIndicator, selectedIndicators));
            }
            return rows;
        } catch (Exception ex) {
            log.warn("forecast assembler: failed to fetch rows for {}/{}: {}", sourceType, setId, ex.getMessage());
            return List.of();
        }
    }

    private static Map<String, Object> recordsPayload(
            String sourceType,
            String setId,
            Map<String, Object> queryParams,
            Map<String, Object> dimensionFilters,
            String selectedIndicator,
            List<String> selectedIndicators) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_type", sourceType);
        payload.put("set_id", setId);
        Map<String, Object> qp = new LinkedHashMap<>(queryParams != null ? queryParams : Map.of());
        if (supportsInternalRecordWindow(sourceType)) {
            qp.put("record_mode", "analysis");
            qp.put("record_limit", ANALYSIS_RECORD_LIMIT);
        }
        payload.put("query_params", qp);
        if (dimensionFilters != null && !dimensionFilters.isEmpty()) {
            payload.put("dimension_filters", dimensionFilters);
        }
        if (selectedIndicator != null && !selectedIndicator.isBlank()) {
            payload.put("selected_indicator", selectedIndicator);
        }
        if (selectedIndicators != null && !selectedIndicators.isEmpty()) {
            payload.put("selected_indicators", selectedIndicators);
        }
        return payload;
    }

    private static boolean supportsInternalRecordWindow(String sourceType) {
        return "fred".equalsIgnoreCase(sourceType);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
