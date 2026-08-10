package cz.bankintel.search.analytics;

import cz.bankintel.search.CatalogPreviewOrchestrator;
import cz.bankintel.search.forecast.ForecastSeriesNormalizer;
import cz.bankintel.service.timeseries.SeriesCompatibilityGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fetches preview rows and converts them into the {@code Map<period, value>} shape consumed by
 * {@code TimeSeriesMetricsService} and related math services — reusing the same preview pipeline
 * and normalizer as the forecast engine.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsSeriesLoader {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsSeriesLoader.class);
    private static final int ANALYSIS_RECORD_LIMIT = 100_000;

    private final CatalogPreviewOrchestrator previewOrchestrator;

    public record LoadedSeries(
            ForecastSeriesNormalizer.NormalizedSeries normalized,
            Map<String, Double> values,
            SeriesCompatibilityGuard.SeriesMetadata metadata) {}

    public LoadedSeries load(
            String sourceType,
            String setId,
            String nameHint,
            String geoHint,
            Map<String, Object> dimensionFilters,
            String role) {
        return load(sourceType, setId, nameHint, geoHint, Map.of(), dimensionFilters, "", List.of(), role);
    }

    public LoadedSeries load(
            String sourceType,
            String setId,
            String nameHint,
            String geoHint,
            Map<String, Object> queryParams,
            Map<String, Object> dimensionFilters,
            String selectedIndicator,
            List<String> selectedIndicators,
            String role) {
        return loadInternal(
                sourceType,
                setId,
                nameHint,
                geoHint,
                queryParams,
                dimensionFilters,
                selectedIndicator,
                selectedIndicators,
                role,
                true);
    }

    public LoadedSeries loadStrict(
            String sourceType,
            String setId,
            String nameHint,
            String geoHint,
            Map<String, Object> queryParams,
            Map<String, Object> dimensionFilters,
            String selectedIndicator,
            List<String> selectedIndicators,
            String role) {
        return loadInternal(
                sourceType,
                setId,
                nameHint,
                geoHint,
                queryParams,
                dimensionFilters,
                selectedIndicator,
                selectedIndicators,
                role,
                false);
    }

    private LoadedSeries loadInternal(
            String sourceType,
            String setId,
            String nameHint,
            String geoHint,
            Map<String, Object> queryParams,
            Map<String, Object> dimensionFilters,
            String selectedIndicator,
            List<String> selectedIndicators,
            String role,
            boolean retryWithoutFilters) {
        List<Map<String, Object>> rows = fetchRows(
                sourceType,
                setId,
                queryParams,
                dimensionFilters,
                selectedIndicator,
                selectedIndicators,
                retryWithoutFilters);
        String seriesId = sourceType + ":" + setId;
        ForecastSeriesNormalizer.NormalizedSeries normalized =
                ForecastSeriesNormalizer.normalize(rows, seriesId, nameHint, sourceType, geoHint, role, null);
        Map<String, Double> values = observationsToValueMap(normalized.observations());
        SeriesCompatibilityGuard.SeriesMetadata metadata = new SeriesCompatibilityGuard.SeriesMetadata(
                normalized.frequency(), normalized.geo(), normalized.unit(), normalized.seasonalAdjustment(), null, null);
        return new LoadedSeries(normalized, values, metadata);
    }

    public static Map<String, Double> observationsToValueMap(List<Map<String, Object>> observations) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map<String, Object> obs : observations) {
            Object dateObj = obs.get("date");
            Object valueObj = obs.get("value");
            if (dateObj == null || valueObj == null) {
                continue;
            }
            String period = String.valueOf(dateObj).trim();
            double value;
            if (valueObj instanceof Number n) {
                value = n.doubleValue();
            } else {
                try {
                    value = Double.parseDouble(String.valueOf(valueObj).trim().replace(",", "."));
                } catch (NumberFormatException ex) {
                    continue;
                }
            }
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                continue;
            }
            out.put(period, value);
        }
        return out;
    }

    private List<Map<String, Object>> fetchRows(
            String sourceType,
            String setId,
            Map<String, Object> queryParams,
            Map<String, Object> dimensionFilters,
            String selectedIndicator,
            List<String> selectedIndicators,
            boolean retryWithoutFilters) {
        try {
            Map<String, Object> payload = recordsPayload(sourceType, setId, queryParams, dimensionFilters, selectedIndicator, selectedIndicators);
            List<Map<String, Object>> rows = previewOrchestrator.fetchRecords(payload);
            if (retryWithoutFilters && rows.isEmpty() && dimensionFilters != null && !dimensionFilters.isEmpty()) {
                log.debug("analytics loader: dimension_filters {} yielded no rows for {}/{}, retrying unfiltered", dimensionFilters, sourceType, setId);
                return previewOrchestrator.fetchRecords(recordsPayload(sourceType, setId, queryParams, Map.of(), selectedIndicator, selectedIndicators));
            }
            return rows;
        } catch (Exception ex) {
            log.warn("analytics loader: failed to fetch rows for {}/{}: {}", sourceType, setId, ex.getMessage());
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
}
