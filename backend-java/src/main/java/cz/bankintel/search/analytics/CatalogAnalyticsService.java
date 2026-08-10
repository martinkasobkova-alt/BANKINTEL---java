package cz.bankintel.search.analytics;

import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.forecast.CatalogForecastService;
import cz.bankintel.search.forecast.ForecastPlannerService;
import cz.bankintel.service.timeseries.AnomalySeriesDetector;
import cz.bankintel.service.timeseries.RealValuesAnalyticsService;
import cz.bankintel.service.timeseries.SeriesComparisonService;
import cz.bankintel.service.timeseries.SeriesCompatibilityGuard;
import cz.bankintel.service.timeseries.TimeSeriesMath;
import cz.bankintel.service.timeseries.TimeSeriesMetricsService;
import cz.bankintel.service.timeseries.TimeSeriesResampler;
import cz.bankintel.service.timeseries.TrendAnalyticsService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates {@code POST /api/catalog/analytics}: plan calculations -> load series -> run
 * deterministic math services -> attach narrative. Reuses the forecast pipeline when the playbook
 * requests {@code forecast}/{@code scenarios}.
 */
@Service
@RequiredArgsConstructor
public class CatalogAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(CatalogAnalyticsService.class);
    private static final int MAX_RELATIONSHIP_SERIES = 4;

    private final CatalogIndexStore indexStore;
    private final AnalyticsPlannerService plannerService;
    private final AnalyticsSeriesLoader seriesLoader;
    private final TimeSeriesMetricsService metricsService;
    private final TrendAnalyticsService trendAnalyticsService;
    private final SeriesComparisonService comparisonService;
    private final AnomalySeriesDetector anomalyDetector;
    private final RealValuesAnalyticsService realValuesService;
    private final SeriesCompatibilityGuard compatibilityGuard;
    private final AnalyticsNarrativeService narrativeService;
    private final CatalogForecastService forecastService;

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyze(Map<String, Object> body) {
        String sourceType = str(body.get("source_type"));
        String setId = str(body.get("set_id"));
        if (sourceType.isBlank() || setId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing source_type or set_id");
        }

        String query = str(body.get("query"));
        String nameHint = str(body.get("name"));
        String geoHint = str(body.get("geo"));
        String basePeriod = str(body.get("base_period"));
        if (basePeriod.isBlank()) {
            basePeriod = "2019";
        }

        Map<String, Object> indexRow = indexStore.lookupRow(sourceType, setId).orElse(Map.of());
        String labelForDomain = firstNonBlank(nameHint, str(indexRow.get("title")), str(indexRow.get("name")), setId);
        if (geoHint.isBlank()) {
            geoHint = firstNonBlank(str(indexRow.get("geo_label")), str(indexRow.get("geo")), str(indexRow.get("country_label")));
        }

        Map<String, Object> queryParams =
                body.get("query_params") instanceof Map<?, ?> queryMap ? castMap(queryMap) : Map.of();
        Map<String, Object> dimensionFilters =
                body.get("dimension_filters") instanceof Map<?, ?> map ? castMap(map) : Map.of();
        String selectedIndicator = str(body.get("selected_indicator"));
        List<String> selectedIndicators = body.get("selected_indicators") instanceof List<?> selectedList
                ? selectedList.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList()
                : (selectedIndicator.isBlank() ? List.of() : List.of(selectedIndicator));
        String comparisonDimension = str(body.get("comparison_dimension"));
        List<Map<String, Object>> comparisonGroups = mapList(body.get("comparison_groups"));
        if (comparisonGroups.isEmpty() && !comparisonDimension.isBlank() && selectedIndicators.size() > 1) {
            comparisonGroups = selectedIndicators.stream()
                    .map(value -> Map.<String, Object>of("value", value, "label", value))
                    .toList();
        }
        boolean includeForecast = booleanValue(body.get("include_forecast"), false);
        boolean includeComparison = booleanValue(body.get("include_comparison"), false);
        boolean hasExplicitComparison = body.get("compare_to") instanceof List<?> compareToList && !compareToList.isEmpty();
        int relationshipLimit = Math.max(0, intValue(body.get("relationship_limit"), MAX_RELATIONSHIP_SERIES));

        AnalyticsPlannerService.PlanResult plan =
                plannerService.plan(labelForDomain, sourceType, setId, geoHint, includeForecast || relationshipLimit > 0);
        List<String> calcTypes = new ArrayList<>(plan.calculationTypes());
        if (!includeForecast) {
            calcTypes.removeIf(type -> "forecast".equals(type) || "scenarios".equals(type));
        }
        if (relationshipLimit <= 0) {
            calcTypes.removeIf(type -> "relationships".equals(type));
        }
        if (!includeComparison && !hasExplicitComparison) {
            calcTypes.removeIf(type -> "comparison".equals(type));
        }

        AnalyticsSeriesLoader.LoadedSeries target =
                seriesLoader.load(
                        sourceType,
                        setId,
                        labelForDomain,
                        geoHint,
                        queryParams,
                        dimensionFilters,
                        selectedIndicator,
                        selectedIndicators,
                        null);
        Map<String, Double> targetValues = target.values();
        // Frontend pošle periodicitu, kterou má uživatel právě vybranou v grafu (Denní/Týdenní/
        // Měsíční…) - bez tohohle Analytika vždy počítala z nativní (typicky denní) frekvence bez
        // ohledu na to, co si uživatel v grafu zvolil.
        String targetFrequency = str(body.get("target_frequency"));
        String effectiveFrequency = target.normalized().frequency();
        if (!targetFrequency.isBlank()) {
            Map<String, Double> resampled =
                    TimeSeriesResampler.resample(targetValues, effectiveFrequency, targetFrequency);
            if (resampled != targetValues) {
                targetValues = resampled;
                effectiveFrequency = targetFrequency.trim().toUpperCase(java.util.Locale.ROOT);
            }
        }

        List<String> qualityWarnings = new ArrayList<>();
        SeriesCompatibilityGuard.GuardrailResult targetGuard = compatibilityGuard.checkSeries(targetValues);
        qualityWarnings.addAll(targetGuard.warnings());
        if ("not_reliable".equals(targetGuard.status())) {
            return notReliableResponse(query, sourceType, setId, labelForDomain, plan, target, targetGuard);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analysis_id", UUID.randomUUID().toString());
        out.put("query", query.isBlank() ? labelForDomain : query);
        out.put("series_id", sourceType + ":" + setId);
        out.put("analysis_type", plan.domainKey().orElse("general"));
        out.put("target_resolution", targetResolution(plan, target, labelForDomain, effectiveFrequency));
        out.put("planner", plannerService.planSummary(plan));

        List<String> completed = new ArrayList<>();

        if (calcTypes.contains("basic_metrics") || calcTypes.contains("indexation")) {
            Map<String, Object> metrics = metricsService.computeMetrics(targetValues, basePeriod);
            out.put("metrics", metrics);
            out.put("indexation", extendedIndexation(targetValues, basePeriod));
            completed.add("basic_metrics");
            if (calcTypes.contains("indexation")) {
                completed.add("indexation");
            }
        }

        if (calcTypes.contains("trend")) {
            out.put("trend", trendAnalyticsService.computeTrendMetrics(targetValues, effectiveFrequency));
            completed.add("trend");
        }

        if (calcTypes.contains("anomalies")) {
            List<Map<String, Object>> anomalies =
                    anomalyDetector.detect(targetValues).stream().map(AnomalySeriesDetector::toMap).toList();
            out.put("anomalies", anomalies);
            completed.add("anomalies");
        }

        if (!comparisonDimension.isBlank() && comparisonGroups.size() > 1) {
            out.put(
                    "group_comparison",
                    buildGroupComparison(
                            sourceType,
                            setId,
                            labelForDomain,
                            geoHint,
                            queryParams,
                            dimensionFilters,
                            comparisonDimension,
                            comparisonGroups,
                            basePeriod,
                            qualityWarnings));
            completed.add("group_comparison");
        }

        if (calcTypes.contains("comparison")) {
            out.put("comparisons", buildComparisons(body, target, targetValues, labelForDomain, geoHint, dimensionFilters, plan, qualityWarnings));
            completed.add("comparison");
        }

        if (calcTypes.contains("relationships")) {
            out.put("relationships", buildRelationships(plan, target, targetValues, labelForDomain, geoHint, qualityWarnings, relationshipLimit));
            completed.add("relationships");
        }

        if (calcTypes.contains("real_values")) {
            out.put("real_values", buildRealValues(plan, targetValues, labelForDomain, geoHint, dimensionFilters, qualityWarnings));
            completed.add("real_values");
        }

        if (calcTypes.contains("forecast") || calcTypes.contains("scenarios")) {
            try {
                Map<String, Object> forecastBody = new LinkedHashMap<>(body);
                forecastBody.putIfAbsent("name", labelForDomain);
                forecastBody.putIfAbsent("geo", geoHint);
                forecastBody.putIfAbsent("query_params", queryParams);
                forecastBody.putIfAbsent("dimension_filters", dimensionFilters);
                if (!selectedIndicator.isBlank()) {
                    forecastBody.putIfAbsent("selected_indicator", selectedIndicator);
                }
                if (!selectedIndicators.isEmpty()) {
                    forecastBody.putIfAbsent("selected_indicators", selectedIndicators);
                }
                Map<String, Object> forecastResult = forecastService.forecast(forecastBody);
                if (calcTypes.contains("forecast")) {
                    out.put("forecasts", forecastResult.get("forecast"));
                    out.put("forecast_meta", Map.of(
                            "model_selection", forecastResult.get("model_selection"),
                            "backtest", forecastResult.get("backtest"),
                            "data_quality", forecastResult.get("data_quality")));
                    completed.add("forecast");
                }
                if (calcTypes.contains("scenarios")) {
                    out.put("scenarios", forecastResult.get("scenarios"));
                    completed.add("scenarios");
                }
                qualityWarnings.addAll(extractWarnings(forecastResult.get("data_quality")));
            } catch (Exception ex) {
                log.warn("analytics: forecast/scenarios failed for {}/{}: {}", sourceType, setId, ex.getMessage());
                qualityWarnings.add("forecast_not_computed: " + ex.getMessage());
            }
        }

        out.put("quality_warnings", qualityWarnings);
        out.put("quality_status", qualityWarnings.isEmpty() ? "ok" : "warning");

        Map<String, Object> narrative = narrativeService.buildNarrative(out, plan.domainLabelCz().orElse(null));
        out.put("executive_summary", narrative.get("executive_summary"));
        out.put("key_numbers", narrative.get("key_numbers"));
        out.put("chart_annotations", narrative.get("chart_annotations"));
        out.put("methodology_note", narrative.get("methodology_note"));
        out.put("methodology_sections", narrative.get("methodology_sections"));
        out.put("narrative", narrative);

        @SuppressWarnings("unchecked")
        Map<String, Object> plannerSummary = (Map<String, Object>) out.get("planner");
        plannerSummary.put("calculation_types_completed", completed);
        return out;
    }

    private static Map<String, Object> targetResolution(
            AnalyticsPlannerService.PlanResult plan,
            AnalyticsSeriesLoader.LoadedSeries target,
            String label,
            String effectiveFrequency) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target_concept", plan.domainKey().orElse("general"));
        out.put("geo", target.normalized().geo());
        out.put("target_series_id", target.normalized().seriesId());
        out.put("target_series_name", label);
        out.put("frequency", effectiveFrequency);
        out.put("unit", target.normalized().unit());
        out.put("transformation", "level");
        return out;
    }

    private static Map<String, Object> extendedIndexation(Map<String, Double> series, String basePeriod) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index_100_at_first_period_last", TimeSeriesMath.lastValue(TimeSeriesMath.indexTo(series, null, 100.0)));
        Map<String, Double> idx2019 = TimeSeriesMath.indexTo(series, "2019", 100.0);
        out.put("index_100_at_2019_last", TimeSeriesMath.lastValue(idx2019));
        out.put("index_100_at_base_period_last", TimeSeriesMath.lastValue(TimeSeriesMath.indexTo(series, basePeriod, 100.0)));
        out.put("base_period_requested", basePeriod);
        out.put("zscore_last", TimeSeriesMath.lastValue(TimeSeriesMath.zscoreSeries(series)));
        out.put("percentile_rank_last", TimeSeriesMath.lastValue(TimeSeriesMath.percentileRankHistory(series)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildComparisons(
            Map<String, Object> body,
            AnalyticsSeriesLoader.LoadedSeries target,
            Map<String, Double> targetValues,
            String targetLabel,
            String geoHint,
            Map<String, Object> dimensionFilters,
            AnalyticsPlannerService.PlanResult plan,
            List<String> qualityWarnings) {
        List<Map<String, Object>> comparisons = new ArrayList<>();
        Object compareToObj = body.get("compare_to");
        if (compareToObj instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> spec = (Map<String, Object>) raw;
                String st = str(spec.get("source_type"));
                String sid = str(spec.get("set_id"));
                String name = str(spec.get("name"));
                if (st.isBlank() || sid.isBlank()) {
                    continue;
                }
                addComparison(comparisons, target, targetValues, targetLabel, st, sid, name, geoHint, Map.of(), dimensionFilters, qualityWarnings);
            }
            return comparisons;
        }
        if (plan.benchmarkGroup() != null) {
            qualityWarnings.add("benchmark_group_" + plan.benchmarkGroup()
                    + ": automatické načtení peer řad z geo skupiny zatím vyžaduje explicitní compare_to v requestu.");
        }
        return comparisons;
    }

    private void addComparison(
            List<Map<String, Object>> comparisons,
            AnalyticsSeriesLoader.LoadedSeries target,
            Map<String, Double> targetValues,
            String targetLabel,
            String peerSourceType,
            String peerSetId,
            String peerName,
            String geoHint,
            Map<String, Object> queryParams,
            Map<String, Object> dimensionFilters,
            List<String> qualityWarnings) {
        AnalyticsSeriesLoader.LoadedSeries peer =
                seriesLoader.load(peerSourceType, peerSetId, peerName, geoHint, queryParams, dimensionFilters, "", List.of(), null);
        SeriesCompatibilityGuard.GuardrailResult compat =
                compatibilityGuard.checkCompatibility(target.metadata(), peer.metadata());
        if ("not_reliable".equals(compat.status())) {
            comparisons.add(Map.of(
                    "label_b", peerName,
                    "status", "skipped",
                    "warnings", compat.warnings()));
            return;
        }
        qualityWarnings.addAll(compat.warnings());
        Map<String, Object> cmp = comparisonService.comparePair(targetLabel, targetValues, peerName, peer.values());
        cmp.put("status", "ok");
        comparisons.add(cmp);
    }

    private Map<String, Object> buildGroupComparison(
            String sourceType,
            String setId,
            String targetLabel,
            String geoHint,
            Map<String, Object> queryParams,
            Map<String, Object> dimensionFilters,
            String comparisonDimension,
            List<Map<String, Object>> comparisonGroups,
            String basePeriod,
            List<String> qualityWarnings) {
        List<SeriesComparisonService.NamedSeries> namedSeries = new ArrayList<>();
        List<Map<String, Object>> members = new ArrayList<>();

        for (Map<String, Object> group : comparisonGroups.stream().limit(8).toList()) {
            String value = str(group.get("value"));
            String label = firstNonBlank(str(group.get("label")), value);
            if (value.isBlank()) {
                continue;
            }
            Map<String, Object> groupFilters = new LinkedHashMap<>(dimensionFilters);
            groupFilters.put(comparisonDimension, value);
            String memberGeo = isGeoDimension(comparisonDimension) ? value : geoHint;
            AnalyticsSeriesLoader.LoadedSeries loaded = seriesLoader.loadStrict(
                    sourceType,
                    setId,
                    targetLabel + " · " + label,
                    memberGeo,
                    queryParams,
                    groupFilters,
                    value,
                    List.of(value),
                    null);
            SeriesCompatibilityGuard.GuardrailResult guard = compatibilityGuard.checkSeries(loaded.values());
            if ("not_reliable".equals(guard.status())) {
                qualityWarnings.add("comparison_group_" + value + ": "
                        + (guard.warnings().isEmpty() ? "nedostatek dat" : guard.warnings().getFirst()));
                continue;
            }
            Map<String, Object> memberMetrics = metricsService.computeMetrics(loaded.values(), basePeriod);
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("value", value);
            member.put("label", label);
            member.put("metrics", memberMetrics);
            member.put("trend", trendAnalyticsService.computeTrendMetrics(
                    loaded.values(), loaded.normalized().frequency()));
            members.add(member);
            namedSeries.add(new SeriesComparisonService.NamedSeries(label, loaded.values()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dimension", comparisonDimension);
        out.put("requested_series_count", comparisonGroups.size());
        out.put("series_count", namedSeries.size());
        out.put("members", members);
        if (namedSeries.size() < 2) {
            out.put("status", "not_reliable");
            out.put("message", "Pro společné srovnání se nepodařilo načíst alespoň dvě použitelné řady.");
            out.put("pairwise", List.of());
            return out;
        }

        out.put("status", "ok");
        out.put("ranking", comparisonService.rankGroup(namedSeries));
        out.put("group_average", comparisonService.groupAverage(namedSeries));
        List<Map<String, Object>> pairwise = new ArrayList<>();
        for (int i = 0; i < namedSeries.size(); i++) {
            for (int j = i + 1; j < namedSeries.size(); j++) {
                SeriesComparisonService.NamedSeries a = namedSeries.get(i);
                SeriesComparisonService.NamedSeries b = namedSeries.get(j);
                pairwise.add(comparisonService.comparePair(a.label(), a.series(), b.label(), b.series()));
            }
        }
        out.put("pairwise", pairwise);
        return out;
    }

    private List<Map<String, Object>> buildRelationships(
            AnalyticsPlannerService.PlanResult plan,
            AnalyticsSeriesLoader.LoadedSeries target,
            Map<String, Double> targetValues,
            String targetLabel,
            String geoHint,
            List<String> qualityWarnings,
            int relationshipLimit) {
        List<Map<String, Object>> relationships = new ArrayList<>();
        int count = 0;
        for (ForecastPlannerService.PredictorCandidate candidate : plan.relationshipCandidates()) {
            if (count >= relationshipLimit) {
                break;
            }
            Map<String, Object> dim = geoHint == null || geoHint.isBlank() ? Map.of() : Map.of("geo", geoHint);
            AnalyticsSeriesLoader.LoadedSeries exog =
                    seriesLoader.load(candidate.sourceType(), candidate.setId(), candidate.title(), geoHint, Map.of(), dim, "", List.of(), candidate.role());
            if (exog.values().size() < SeriesCompatibilityGuard.MIN_OBSERVATIONS_ANY_CALCULATION) {
                continue;
            }
            SeriesCompatibilityGuard.GuardrailResult compat =
                    compatibilityGuard.checkCompatibility(target.metadata(), exog.metadata());
            if ("not_reliable".equals(compat.status())) {
                relationships.add(Map.of(
                        "concept", candidate.conceptCz(),
                        "series_name", candidate.title(),
                        "status", "skipped",
                        "warnings", compat.warnings(),
                        "causality_note", "Korelace/regrese neimplikuje kauzalitu — jde o technickou vazbu v datech."));
                continue;
            }
            qualityWarnings.addAll(compat.warnings());
            Map<String, Object> rel = comparisonService.comparePair(targetLabel, targetValues, candidate.title(), exog.values());
            rel.put("concept", candidate.conceptCz());
            rel.put("role", candidate.role());
            rel.put("status", "ok");
            rel.put("causality_note", "Korelace/regrese neimplikuje kauzalitu — jde o technickou vazbu v datech.");
            relationships.add(rel);
            count++;
        }
        return relationships;
    }

    private Map<String, Object> buildRealValues(
            AnalyticsPlannerService.PlanResult plan,
            Map<String, Double> targetValues,
            String targetLabel,
            String geoHint,
            Map<String, Object> dimensionFilters,
            List<String> qualityWarnings) {
        ForecastPlannerService.PredictorCandidate inflationCandidate = plan.relationshipCandidates().stream()
                .filter(c -> "inflation".equals(c.role()) || c.conceptCz().toLowerCase().contains("inflac"))
                .findFirst()
                .orElse(null);
        if (inflationCandidate == null) {
            return Map.of(
                    "status", "not_computed",
                    "warnings", List.of("inflation_series_not_found: pro reálné metriky nebyla nalezena inflační řada."));
        }
        Map<String, Object> dim = geoHint == null || geoHint.isBlank() ? Map.of() : Map.of("geo", geoHint);
        AnalyticsSeriesLoader.LoadedSeries inflation =
                seriesLoader.load(
                        inflationCandidate.sourceType(),
                        inflationCandidate.setId(),
                        inflationCandidate.title(),
                        geoHint,
                        Map.of(),
                        dim,
                        "",
                        List.of(),
                        inflationCandidate.role());
        Map<String, Object> real = realValuesService.computeRealMetrics(
                targetValues, targetLabel, inflation.values(), inflationCandidate.title(), compatibilityGuard);
        qualityWarnings.addAll(extractWarnings(real.get("warnings")));
        return real;
    }

    private static Map<String, Object> notReliableResponse(
            String query,
            String sourceType,
            String setId,
            String label,
            AnalyticsPlannerService.PlanResult plan,
            AnalyticsSeriesLoader.LoadedSeries target,
            SeriesCompatibilityGuard.GuardrailResult guard) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analysis_id", UUID.randomUUID().toString());
        out.put("query", query.isBlank() ? label : query);
        out.put("series_id", sourceType + ":" + setId);
        out.put("analysis_type", plan.domainKey().orElse("general"));
        out.put("quality_status", "not_reliable");
        out.put("quality_warnings", guard.warnings());
        out.put("what_would_help", guard.whatWouldHelp());
        out.put("metrics", Map.of());
        out.put("comparisons", List.of());
        out.put("relationships", List.of());
        out.put("anomalies", List.of());
        out.put("forecasts", List.of());
        out.put("scenarios", List.of());
        out.put("target_resolution", Map.of("target_series_name", label, "target_series_id", sourceType + ":" + setId));
        Map<String, Object> planner = new LinkedHashMap<>();
        planner.put("domain", plan.domainKey().orElse(null));
        out.put("planner", planner);
        out.put(
                "executive_summary",
                "Analytický výpočet pro „" + label + "“ nelze spolehlivě provést — "
                        + (guard.warnings().isEmpty() ? "nedostatek dat." : guard.warnings().get(0)));
        out.put("key_numbers", List.of());
        String analyticsSummary = String.valueOf(out.get("executive_summary"));
        String analyticsMethodology =
                "Guardrails zablokovaly vypocet kvuli nedostatecne delce nebo kvalite rady.";
        List<Map<String, String>> methodologySections = List.of(Map.of(
                "title",
                "Kvalita dat",
                "body",
                "Analytika se nespustila, protoze vstupni rada nesplnila minimalni guardrails. "
                        + "Vyberte konkretni dimenzi s delsi historii nebo jinou radu."));
        out.put("chart_annotations", List.of());
        out.put("methodology_sections", methodologySections);
        out.put(
                "narrative",
                Map.of(
                        "executive_summary",
                        analyticsSummary,
                        "main_insight",
                        "Neni k dispozici dost historickych pozorovani pro spolehlivy vypocet metrik.",
                        "forecast_sentence",
                        "",
                        "manager_sentence",
                        "",
                        "methodology_note",
                        analyticsMethodology,
                        "methodology_sections",
                        methodologySections,
                        "watch_next",
                        guard.whatWouldHelp()));
        out.put("methodology_note", "Guardrails zablokovaly výpočet kvůli nedostatečné délce nebo kvalitě řady.");
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractWarnings(Object warningsObj) {
        if (warningsObj instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static boolean isGeoDimension(String value) {
        String key = str(value).toLowerCase();
        return "geo".equals(key) || "country".equals(key) || "ref_area".equals(key);
    }

    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(castMap(map));
            }
        }
        return out;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value != null) {
            String text = String.valueOf(value).trim().toLowerCase();
            if ("true".equals(text) || "1".equals(text) || "yes".equals(text)) {
                return true;
            }
            if ("false".equals(text) || "0".equals(text) || "no".equals(text)) {
                return false;
            }
        }
        return fallback;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }
}
