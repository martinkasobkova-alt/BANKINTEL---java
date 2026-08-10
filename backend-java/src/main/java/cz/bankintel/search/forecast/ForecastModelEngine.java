package cz.bankintel.search.forecast;

import cz.bankintel.service.timeseries.TimeSeriesMath;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;

/**
 * In-process Java forecasting engine.
 *
 * <p>The engine is deliberately source and domain agnostic. It accepts the normalized payload
 * produced by {@link ForecastDataAssemblerService}, applies data guardrails, compares several
 * transparent models by rolling-origin backtest and returns the stable API contract consumed by
 * the frontend. No network service, Python runtime or LLM participates in numerical results.
 */
@Service
public class ForecastModelEngine {

    private static final int MIN_OBSERVATIONS = 8;
    private static final int MIN_SEASONAL_OBSERVATIONS = 24;
    private static final int MAX_BACKTEST_FOLDS = 8;
    private static final double Z_80 = 1.2815515655446004;
    private static final double TIE_MARGIN = 0.03;

    private static final Map<String, List<String>> DEFAULT_HORIZONS = Map.of(
            "D", List.of("1D", "5D", "1M", "3M", "6M", "12M"),
            "W", List.of("1W", "4W", "12W", "26W", "52W"),
            "M", List.of("1M", "3M", "6M", "12M", "24M"),
            "Q", List.of("1Q", "2Q", "4Q", "8Q"),
            "Y", List.of("1Y", "2Y", "3Y", "5Y"));

    private static final Map<String, Map<String, Integer>> HORIZON_STEPS = Map.of(
            "D", Map.of("1D", 1, "5D", 5, "1M", 30, "3M", 90, "6M", 180, "12M", 365),
            "W", Map.of("1W", 1, "4W", 4, "12W", 12, "26W", 26, "52W", 52),
            "M", Map.of("1M", 1, "3M", 3, "6M", 6, "12M", 12, "24M", 24),
            "Q", Map.of("1Q", 1, "2Q", 2, "4Q", 4, "8Q", 8),
            "Y", Map.of("1Y", 1, "2Y", 2, "3Y", 3, "5Y", 5));

    @SuppressWarnings("unchecked")
    public Map<String, Object> forecast(Map<String, Object> request) {
        Series target = Series.fromMap(asMap(request.get("target")));
        if (target.values().isEmpty()) {
            return notReliable(target, "Cílová řada neobsahuje žádná použitelná pozorování.");
        }
        if (target.values().size() < MIN_OBSERVATIONS) {
            return notReliable(
                    target,
                    "Cílová řada má " + target.values().size() + " pozorování; pro forecast je potřeba alespoň "
                            + MIN_OBSERVATIONS + ".");
        }

        List<String> horizons = validHorizons(target.frequency(), asStringList(request.get("horizons")));
        int maxStep = horizons.stream().mapToInt(h -> stepCount(target.frequency(), h)).max().orElse(1);
        int seasonLength = seasonLength(target.frequency());

        List<String> warnings = qualityWarnings(target.values());
        List<ModelEvaluation> evaluations = evaluateBaselines(target.values(), maxStep, seasonLength);

        List<Map<String, Object>> candidateDiscovery = new ArrayList<>();
        List<Map<String, Object>> selectedFeatures = new ArrayList<>();
        List<Map<String, Object>> rejectedFeatures = new ArrayList<>();
        List<Series> candidates = new ArrayList<>();
        for (Map<String, Object> candidateMap : asMapList(request.get("candidate_exog"))) {
            candidates.add(Series.fromMap(candidateMap));
        }
        evaluateExogenousCandidates(
                target, candidates, maxStep, evaluations, candidateDiscovery, selectedFeatures, rejectedFeatures);

        ModelEvaluation selected = selectBest(evaluations);
        double[] points = selected.forecast();
        double residualStd = selected.backtest().residualStd() > 0
                ? selected.backtest().residualStd()
                : Math.max(0.0, selected.residualStd());

        List<String> futureDates = futureDates(target.lastPeriod(), target.frequency(), maxStep);
        List<Map<String, Object>> forecastPoints = new ArrayList<>();
        double lastValue = target.lastValue();
        for (String horizon : horizons) {
            int step = stepCount(target.frequency(), horizon);
            forecastPoints.add(point(horizon, futureDates.get(step - 1), points[step - 1], residualStd, step, lastValue));
        }

        List<Map<String, Object>> scenarios = statisticalScenarios(
                target.values(), points, residualStd, futureDates, lastValue);
        Map<String, Object> interpretability = interpretability(target, selected, seasonLength, selectedFeatures);
        Map<String, Object> backtest = backtestMap(selected.backtest(), evaluations);
        Map<String, Object> narrativeValues = narrativeValues(forecastPoints, interpretability);
        List<Map<String, Object>> modelAlternatives = modelAlternatives(
                evaluations, selected, horizons, target.frequency(), futureDates, lastValue);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("forecast_id", UUID.randomUUID().toString());
        out.put("target_series", targetSummary(target));
        out.put(
                "input_series",
                Map.of(
                        "target", target.seriesId(),
                        "hist_exog", selectedFeatures.stream().map(f -> f.get("source_series_id")).distinct().toList(),
                        "futr_exog", List.of(),
                        "stat_exog", asMap(request.get("stat_exog"))));
        out.put(
                "data_quality",
                Map.of(
                        "status", warnings.isEmpty() ? "ok" : "warning",
                        "warnings", warnings,
                        "common_observations", target.values().size(),
                        "missing_share", 0.0,
                        "training_start", target.periods().getFirst(),
                        "training_end", target.lastPeriod(),
                        "what_would_help", target.values().size() < MIN_SEASONAL_OBSERVATIONS
                                ? List.of("Delší historie zpřesní sezónnost a interval nejistoty.")
                                : List.of()));
        out.put(
                "model_selection",
                Map.of(
                        "selected_model", selected.name(),
                        "candidate_models", evaluations.stream().map(ModelEvaluation::name).toList(),
                        "reason", selectionReason(selected, target.values().size()),
                        "fallback_used", false,
                        "branch", selected.exogenous() ? "B_java_with_exog" : "A_java_baselines"));
        out.put("model_alternatives", modelAlternatives);
        out.put("backtest", backtest);
        out.put("forecast", forecastPoints);
        out.put("scenarios", scenarios);
        out.put("interpretability", interpretability);
        out.put("narrative_values", narrativeValues);
        out.put("candidate_discovery", candidateDiscovery);
        out.put("selected_features", selectedFeatures);
        out.put("rejected_features", rejectedFeatures);
        return out;
    }

    private static List<Map<String, Object>> modelAlternatives(
            List<ModelEvaluation> evaluations,
            ModelEvaluation selected,
            List<String> horizons,
            String frequency,
            List<String> futureDates,
            double lastValue) {
        return evaluations.stream()
                .filter(e -> e.backtest().rmse() != null && Double.isFinite(e.backtest().rmse()))
                .sorted(Comparator.comparingDouble(e -> e.backtest().rmse()))
                .limit(6)
                .map(e -> {
                    double residualStd = e.backtest().residualStd() > 0
                            ? e.backtest().residualStd()
                            : Math.max(0.0, e.residualStd());
                    List<Map<String, Object>> points = new ArrayList<>();
                    for (String horizon : horizons) {
                        int step = stepCount(frequency, horizon);
                        points.add(point(
                                horizon,
                                futureDates.get(step - 1),
                                e.forecast()[step - 1],
                                residualStd,
                                step,
                                lastValue));
                    }
                    Map<String, Object> alternative = new LinkedHashMap<>();
                    alternative.put("model", e.name());
                    alternative.put("label", modelLabel(e));
                    alternative.put("description", modelDescription(e));
                    alternative.put("recommended", e.name().equals(selected.name()));
                    alternative.put("mae", e.backtest().mae());
                    alternative.put("rmse", e.backtest().rmse());
                    alternative.put("directional_accuracy", e.backtest().directionalAccuracy());
                    alternative.put("forecast", points);
                    return alternative;
                })
                .toList();
    }

    private static String modelLabel(ModelEvaluation evaluation) {
        if (evaluation.exogenous()) return "Odhad s doprovodným ukazatelem";
        return switch (evaluation.name()) {
            case "naive" -> "Poslední známá hodnota";
            case "moving_average" -> "Vyhlazený vývoj";
            case "linear_trend" -> "Pokračování trendu";
            case "holt_trend" -> "Přizpůsobivý trend";
            case "seasonal_naive" -> "Sezónní opakování";
            case "log_linear_trend" -> "Procentní tempo vývoje";
            default -> "Alternativní odhad";
        };
    }

    private static String modelDescription(ModelEvaluation evaluation) {
        if (evaluation.exogenous()) {
            return "Odhad kombinuje historii vybrané řady s ukazatelem, který v minulosti pomáhal její vývoj vysvětlit.";
        }
        return switch (evaluation.name()) {
            case "naive" -> "Předpokládá, že se hodnota udrží poblíž posledního známého období.";
            case "moving_average" -> "Vyhlazuje krátkodobé výkyvy a navazuje na průměr posledních období.";
            case "linear_trend" -> "Prodlužuje dosavadní dlouhodobý směr vývoje.";
            case "holt_trend" -> "Dává větší váhu novějším obdobím a průběžně přizpůsobuje směr vývoje.";
            case "seasonal_naive" -> "Navazuje na hodnotu ze stejného období předchozí sezóny.";
            case "log_linear_trend" -> "Navazuje na historické procentní tempo růstu nebo poklesu.";
            default -> "Alternativní pohled na další vývoj vybrané řady.";
        };
    }

    private static List<ModelEvaluation> evaluateBaselines(
            Map<String, Double> series, int horizon, int seasonLength) {
        double[] values = series.values().stream().mapToDouble(Double::doubleValue).toArray();
        List<ModelSpec> specs = new ArrayList<>();
        specs.add(new ModelSpec("naive", 0, ForecastModelEngine::naive));
        specs.add(new ModelSpec("moving_average", 1, ForecastModelEngine::movingAverage));
        specs.add(new ModelSpec("linear_trend", 2, ForecastModelEngine::linearTrend));
        specs.add(new ModelSpec("holt_trend", 3, ForecastModelEngine::holtTrend));
        if (values.length >= 2 * seasonLength && seasonLength > 1) {
            specs.add(new ModelSpec(
                    "seasonal_naive", 1, (train, h) -> seasonalNaive(train, h, seasonLength)));
        }
        if (Arrays.stream(values).allMatch(v -> v > 0)) {
            specs.add(new ModelSpec("log_linear_trend", 2, ForecastModelEngine::logLinearTrend));
        }

        List<ModelEvaluation> out = new ArrayList<>();
        int validationHorizon = Math.min(horizon, Math.max(1, values.length / 4));
        for (ModelSpec spec : specs) {
            BacktestResult bt = backtest(values, validationHorizon, spec.forecaster());
            double[] forecast = spec.forecaster().apply(values, horizon);
            out.add(new ModelEvaluation(
                    spec.name(), spec.complexity(), forecast, inSampleResidualStd(values, spec.forecaster()), bt, false, null));
        }
        return out;
    }

    private static void evaluateExogenousCandidates(
            Series target,
            List<Series> candidates,
            int horizon,
            List<ModelEvaluation> evaluations,
            List<Map<String, Object>> discovery,
            List<Map<String, Object>> selectedFeatures,
            List<Map<String, Object>> rejectedFeatures) {
        if (target.values().size() < 24) {
            for (Series candidate : candidates) {
                discovery.add(discovery(candidate, 0, 0.0, false, "Cílová řada je pro exogenní model příliš krátká."));
                rejectedFeatures.add(rejected(candidate, "insufficient_target_history"));
            }
            return;
        }

        for (Series candidate : candidates) {
            AlignedPair aligned = align(target, candidate);
            if (aligned.y().length < 12) {
                discovery.add(discovery(candidate, aligned.y().length, 0.0, false, "Nedostatečný časový překryv."));
                rejectedFeatures.add(rejected(candidate, "insufficient_overlap"));
                continue;
            }
            double correlation = pearson(aligned.x(), aligned.y());
            if (!Double.isFinite(correlation) || Math.abs(correlation) < 0.20) {
                discovery.add(discovery(candidate, aligned.y().length, correlation, false, "Slabý historický vztah k cílové řadě."));
                rejectedFeatures.add(rejected(candidate, "weak_historical_relationship"));
                continue;
            }

            RegressionModel model = regression(aligned.x(), aligned.y());
            BiFunction<double[], Integer, double[]> forecaster = (train, h) -> {
                int n = Math.min(train.length, aligned.x().length);
                if (n < 3) return naive(train, h);
                RegressionModel foldModel = regression(
                        Arrays.copyOf(aligned.x(), n), Arrays.copyOf(train, n));
                double[] forecast = new double[h];
                for (int i = 0; i < h; i++) {
                    int futureIndex = n + i;
                    double futureX = futureIndex < aligned.x().length
                            ? aligned.x()[futureIndex]
                            : aligned.x()[n - 1];
                    forecast[i] = foldModel.intercept() + foldModel.slope() * futureX;
                }
                return forecast;
            };
            int validationHorizon = Math.min(horizon, Math.max(1, aligned.y().length / 4));
            BacktestResult bt = backtest(aligned.y(), validationHorizon, forecaster);
            double[] forecast = new double[horizon];
            Arrays.fill(forecast, model.intercept() + model.slope() * aligned.x()[aligned.x().length - 1]);
            String modelName = "exog_regression:" + candidate.seriesId();
            evaluations.add(new ModelEvaluation(
                    modelName, 4, forecast, model.residualStd(), bt, true, candidate));
            discovery.add(discovery(candidate, aligned.y().length, correlation, true, "Použitelný kandidát pro backtest."));
        }

        ModelEvaluation best = selectBest(evaluations);
        if (best.exogenous() && best.driver() != null) {
            selectedFeatures.add(Map.of(
                    "feature_name", best.driver().concept() + "__level__lag0",
                    "source_series_id", best.driver().seriesId(),
                    "concept", best.driver().concept(),
                    "transformation", "level",
                    "lag", 0,
                    "reason", "Vstupní řada zlepšila rolling-origin backtest proti samostatným modelům cílové řady.",
                    "backtest_contribution", 1.0));
        }
    }

    private static ModelEvaluation selectBest(List<ModelEvaluation> evaluations) {
        List<ModelEvaluation> scored = evaluations.stream()
                .filter(e -> e.backtest().rmse() != null && Double.isFinite(e.backtest().rmse()))
                .sorted(Comparator.comparingDouble(e -> e.backtest().rmse()))
                .toList();
        if (scored.isEmpty()) return evaluations.getFirst();
        double bestRmse = scored.getFirst().backtest().rmse();
        return scored.stream()
                .filter(e -> bestRmse == 0
                        ? e.backtest().rmse() <= 1e-12
                        : (e.backtest().rmse() - bestRmse) / bestRmse <= TIE_MARGIN)
                .min(Comparator.comparingInt(ModelEvaluation::complexity))
                .orElse(scored.getFirst());
    }

    private static BacktestResult backtest(
            double[] values, int horizon, BiFunction<double[], Integer, double[]> forecaster) {
        int minTrain = Math.max(MIN_OBSERVATIONS, Math.min(values.length - 1, Math.max(8, values.length / 3)));
        int lastOrigin = values.length - horizon;
        if (lastOrigin <= minTrain) return new BacktestResult(null, null, null, null, null, 0, 0.0);
        int available = lastOrigin - minTrain;
        int stride = Math.max(1, available / MAX_BACKTEST_FOLDS);
        List<Integer> origins = new ArrayList<>();
        for (int origin = minTrain; origin < lastOrigin; origin += stride) origins.add(origin);
        if (origins.size() > MAX_BACKTEST_FOLDS) {
            origins = origins.subList(origins.size() - MAX_BACKTEST_FOLDS, origins.size());
        }

        List<Double> errors = new ArrayList<>();
        List<Double> pctErrors = new ArrayList<>();
        List<Double> smape = new ArrayList<>();
        int directionHits = 0;
        int directionTotal = 0;
        int folds = 0;
        for (int origin : origins) {
            double[] train = Arrays.copyOf(values, origin);
            int testLength = Math.min(horizon, values.length - origin);
            double[] predicted;
            try {
                predicted = forecaster.apply(train, testLength);
            } catch (RuntimeException ex) {
                continue;
            }
            if (predicted.length < testLength) continue;
            folds++;
            for (int i = 0; i < testLength; i++) {
                double actual = values[origin + i];
                double error = actual - predicted[i];
                errors.add(error);
                if (actual != 0) pctErrors.add(Math.abs(error / actual));
                double denom = Math.abs(actual) + Math.abs(predicted[i]);
                if (denom != 0) smape.add(2 * Math.abs(error) / denom);
            }
            double actualDirection = Math.signum(values[origin + testLength - 1] - train[train.length - 1]);
            double predictedDirection = Math.signum(predicted[testLength - 1] - train[train.length - 1]);
            if (actualDirection != 0) {
                directionTotal++;
                if (actualDirection == predictedDirection) directionHits++;
            }
        }
        if (errors.isEmpty()) return new BacktestResult(null, null, null, null, null, 0, 0.0);
        double mae = errors.stream().mapToDouble(Math::abs).average().orElse(Double.NaN);
        double rmse = Math.sqrt(errors.stream().mapToDouble(e -> e * e).average().orElse(Double.NaN));
        Double mape = pctErrors.isEmpty() ? null : pctErrors.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 100;
        Double sMape = smape.isEmpty() ? null : smape.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 100;
        Double direction = directionTotal == 0 ? null : (double) directionHits / directionTotal;
        return new BacktestResult(mae, rmse, mape, sMape, direction, folds, sampleStd(errors));
    }

    private static double[] naive(double[] values, int horizon) {
        double[] out = new double[horizon];
        Arrays.fill(out, values[values.length - 1]);
        return out;
    }

    private static double[] seasonalNaive(double[] values, int horizon, int seasonLength) {
        if (values.length < seasonLength || seasonLength <= 1) return naive(values, horizon);
        double[] out = new double[horizon];
        for (int i = 0; i < horizon; i++) out[i] = values[values.length - seasonLength + (i % seasonLength)];
        return out;
    }

    private static double[] movingAverage(double[] values, int horizon) {
        int window = Math.min(values.length, 12);
        double average = Arrays.stream(values, values.length - window, values.length).average().orElse(values[values.length - 1]);
        double[] out = new double[horizon];
        Arrays.fill(out, average);
        return out;
    }

    private static double[] linearTrend(double[] values, int horizon) {
        RegressionModel fit = regression(index(values.length), values);
        double[] out = new double[horizon];
        for (int i = 0; i < horizon; i++) out[i] = fit.intercept() + fit.slope() * (values.length + i);
        return out;
    }

    private static double[] logLinearTrend(double[] values, int horizon) {
        double[] logs = Arrays.stream(values).map(Math::log).toArray();
        return Arrays.stream(linearTrend(logs, horizon)).map(Math::exp).toArray();
    }

    private static double[] holtTrend(double[] values, int horizon) {
        double bestSse = Double.POSITIVE_INFINITY;
        double bestLevel = values[values.length - 1];
        double bestTrend = 0.0;
        double[] grid = {0.1, 0.3, 0.5, 0.7, 0.9};
        for (double alpha : grid) {
            for (double beta : grid) {
                double level = values[0];
                double trend = values.length > 1 ? values[1] - values[0] : 0.0;
                double sse = 0.0;
                for (int i = 1; i < values.length; i++) {
                    double fitted = level + trend;
                    sse += Math.pow(values[i] - fitted, 2);
                    double previousLevel = level;
                    level = alpha * values[i] + (1 - alpha) * (level + trend);
                    trend = beta * (level - previousLevel) + (1 - beta) * trend;
                }
                if (sse < bestSse) {
                    bestSse = sse;
                    bestLevel = level;
                    bestTrend = trend;
                }
            }
        }
        double[] out = new double[horizon];
        for (int i = 0; i < horizon; i++) out[i] = bestLevel + (i + 1) * bestTrend;
        return out;
    }

    private static double inSampleResidualStd(double[] values, BiFunction<double[], Integer, double[]> forecaster) {
        if (values.length < 3) return 0.0;
        List<Double> errors = new ArrayList<>();
        for (int origin = 2; origin < values.length; origin++) {
            double prediction = forecaster.apply(Arrays.copyOf(values, origin), 1)[0];
            errors.add(values[origin] - prediction);
        }
        return sampleStd(errors);
    }

    private static Map<String, Object> point(
            String horizon, String date, double value, double residualStd, int step, double lastValue) {
        double spread = Z_80 * residualStd * Math.sqrt(Math.max(1, step));
        double change = value - lastValue;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("horizon", horizon);
        out.put("date", date);
        out.put("p10", round(value - spread, 4));
        out.put("p50", round(value, 4));
        out.put("p90", round(value + spread, 4));
        out.put("point", round(value, 4));
        out.put("change_vs_last", round(change, 4));
        out.put("change_pct_vs_last", lastValue == 0 ? null : round(change / lastValue * 100, 2));
        return out;
    }

    private static List<Map<String, Object>> statisticalScenarios(
            Map<String, Double> history,
            double[] baseline,
            double residualStd,
            List<String> dates,
            double lastValue) {
        double[] historicalValues = history.values().stream().mapToDouble(Double::doubleValue).toArray();
        double[] trend = linearTrend(historicalValues, baseline.length);
        double average = Arrays.stream(historicalValues).average().orElse(lastValue);
        double[] meanReversion = new double[baseline.length];
        double decay = Math.pow(0.5, 1.0 / Math.max(1.0, baseline.length / 2.0));
        for (int i = 0; i < meanReversion.length; i++) {
            meanReversion[i] = average + (lastValue - average) * Math.pow(decay, i + 1);
        }
        double[] optimistic = new double[baseline.length];
        double[] pessimistic = new double[baseline.length];
        for (int i = 0; i < baseline.length; i++) {
            double spread = Z_80 * residualStd * Math.sqrt(i + 1);
            optimistic[i] = baseline[i] + spread;
            pessimistic[i] = baseline[i] - spread;
        }
        return List.of(
                scenario("baseline", "pokračování vybraného modelu bez dodatečného šoku", baseline, residualStd, dates, lastValue),
                scenario("continuation_of_trend", "lineární pokračování historického trendu", trend, residualStd, dates, lastValue),
                scenario("mean_reversion", "postupný návrat k historickému průměru", meanReversion, residualStd, dates, lastValue),
                scenario("optimistic", "horní dráha intervalu nejistoty", optimistic, residualStd, dates, lastValue),
                scenario("pessimistic", "dolní dráha intervalu nejistoty", pessimistic, residualStd, dates, lastValue));
    }

    private static Map<String, Object> scenario(
            String name,
            String assumption,
            double[] values,
            double residualStd,
            List<String> dates,
            double lastValue) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            points.add(point("+" + (i + 1), dates.get(i), values[i], residualStd, i + 1, lastValue));
        }
        return Map.of(
                "scenario_name", name,
                "assumption", assumption,
                "applicable", true,
                "forecast", points,
                "impact_vs_baseline", List.of());
    }

    private static Map<String, Object> interpretability(
            Series target, ModelEvaluation selected, int seasonLength, List<Map<String, Object>> selectedFeatures) {
        TimeSeriesMath.TrendFit trend = TimeSeriesMath.linearTrend(target.values());
        double relativeTrend = trend.slopePerStep() * target.values().size()
                / Math.max(1e-9, Math.abs(TimeSeriesMath.mean(target.values())));
        String trendText = Math.abs(relativeTrend) < 0.02
                ? "Historická řada je bez výrazného dlouhodobého trendu."
                : "Historická řada vykazuje " + (trend.slopePerStep() > 0 ? "rostoucí" : "klesající")
                        + " trend (sklon " + round(trend.slopePerStep(), 4) + " na období).";
        String seasonality = target.values().size() >= 2 * seasonLength && seasonLength > 1
                ? "Historie umožňuje vyhodnocovat sezónní modely; jejich přínos byl ověřen backtestem."
                : "Historie nestačí k bezpečnému odhadu sezónnosti nebo řada sezónnost nemá.";
        List<String> drivers = selectedFeatures.stream().map(f -> String.valueOf(f.get("concept"))).distinct().toList();
        String exogenous = drivers.isEmpty()
                ? "Vybraný model používá pouze vlastní historii cílové řady."
                : "Backtest potvrdil přínos vstupní řady: " + String.join(", ", drivers) + ".";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("top_drivers", drivers);
        out.put("driver_importance", drivers.stream()
                .map(d -> Map.of("driver", d, "importance", 1.0 / drivers.size(), "direction", "unknown"))
                .toList());
        out.put("attention_summary", "Vybraný model: " + selected.name() + ". Rozhodl rolling-origin backtest v Javě.");
        out.put("trend_component", trendText);
        out.put("seasonality_component", seasonality);
        out.put("exogenous_component", exogenous);
        return out;
    }

    private static Map<String, Object> backtestMap(
            BacktestResult selected, List<ModelEvaluation> evaluations) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mae", selected.mae());
        out.put("rmse", selected.rmse());
        out.put("mape", selected.mape());
        out.put("smape", selected.smape());
        out.put("directional_accuracy", selected.directionalAccuracy());
        out.put("n_folds", selected.folds());
        out.put("baseline_comparison", evaluations.stream()
                .map(ForecastModelEngine::baselineComparison)
                .toList());
        return out;
    }

    private static Map<String, Object> baselineComparison(ModelEvaluation evaluation) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", evaluation.name());
        out.put("mae", evaluation.backtest().mae());
        out.put("rmse", evaluation.backtest().rmse());
        out.put("smape", evaluation.backtest().smape());
        return out;
    }

    private static Map<String, Object> narrativeValues(
            List<Map<String, Object>> forecastPoints, Map<String, Object> interpretability) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (forecastPoints.isEmpty()) return out;
        Map<String, Object> last = forecastPoints.getLast();
        out.put("horizon_label", last.get("horizon"));
        out.put("p10", last.get("p10"));
        out.put("p50", last.get("p50"));
        out.put("p90", last.get("p90"));
        out.put("change_pct", last.get("change_pct_vs_last"));
        List<String> drivers = asStringList(interpretability.get("top_drivers"));
        if (!drivers.isEmpty()) out.put("top_driver_1", drivers.get(0));
        if (drivers.size() > 1) out.put("top_driver_2", drivers.get(1));
        if (drivers.size() > 2) out.put("top_driver_3", drivers.get(2));
        return out;
    }

    private static Map<String, Object> notReliable(Series target, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("forecast_id", UUID.randomUUID().toString());
        out.put("target_series", targetSummary(target));
        out.put("input_series", Map.of("target", target.seriesId(), "hist_exog", List.of(), "futr_exog", List.of(), "stat_exog", Map.of()));
        out.put("data_quality", Map.of(
                "status", "not_reliable",
                "warnings", List.of("insufficient_target_data"),
                "common_observations", target.values().size(),
                "missing_share", 0.0,
                "what_would_help", List.of(reason)));
        out.put("model_selection", Map.of(
                "selected_model", "none",
                "candidate_models", List.of(),
                "reason", reason,
                "fallback_used", false,
                "branch", "D_insufficient_data"));
        out.put("model_alternatives", List.of());
        out.put("backtest", Map.of());
        out.put("forecast", List.of());
        out.put("scenarios", List.of());
        out.put("interpretability", Map.of(
                "top_drivers", List.of(),
                "driver_importance", List.of(),
                "attention_summary", "Forecast nebyl proveden.",
                "trend_component", "",
                "seasonality_component", "",
                "exogenous_component", ""));
        out.put("narrative_values", Map.of());
        out.put("candidate_discovery", List.of());
        out.put("selected_features", List.of());
        out.put("rejected_features", List.of());
        return out;
    }

    private static Map<String, Object> targetSummary(Series target) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series_id", target.seriesId());
        out.put("name", target.name());
        out.put("source", target.source());
        out.put("geo", target.geo());
        out.put("frequency", target.frequency());
        out.put("unit", target.unit());
        out.put("last_date", target.values().isEmpty() ? null : target.lastPeriod());
        out.put("last_value", target.values().isEmpty() ? null : target.lastValue());
        return out;
    }

    private static List<String> qualityWarnings(Map<String, Double> series) {
        List<String> warnings = new ArrayList<>();
        double mean = TimeSeriesMath.mean(series);
        double stdev = TimeSeriesMath.populationStdev(series.values());
        if (stdev > 0) {
            long outliers = series.values().stream().filter(v -> Math.abs((v - mean) / stdev) > 4.0).count();
            if (outliers > 0) warnings.add("Řada obsahuje " + outliers + " neobvykle odlehlých hodnot.");
        }
        if (series.size() >= MIN_SEASONAL_OBSERVATIONS) {
            List<Double> values = new ArrayList<>(series.values());
            int mid = values.size() / 2;
            double first = values.subList(0, mid).stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double second = values.subList(mid, values.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double se = TimeSeriesMath.sampleStdev(values)
                    * Math.sqrt(1.0 / mid + 1.0 / (values.size() - mid));
            if (se > 0 && Math.abs(first - second) / se > 3.0) {
                warnings.add("V historii je možný strukturální zlom; interval forecastu berte obezřetně.");
            }
        }
        return warnings;
    }

    private static String selectionReason(ModelEvaluation selected, int observations) {
        String prefix = selected.exogenous()
                ? "Exogenní kandidát prokázal lepší přesnost než samostatné modely cílové řady. "
                : "Z transparentních modelů cílové řady byl vybrán nejlepší výsledek. ";
        return prefix + "Model '" + selected.name() + "' zvítězil v rolling-origin backtestu na "
                + observations + " pozorováních podle RMSE.";
    }

    private static Map<String, Object> discovery(
            Series candidate, int overlap, double correlation, boolean usable, String warning) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("concept", candidate.concept());
        out.put("series_id", candidate.seriesId());
        out.put("series_name", candidate.name());
        out.put("source", candidate.source());
        out.put("geo", candidate.geo());
        out.put("frequency", candidate.frequency());
        out.put("unit", candidate.unit());
        out.put("quality_score", Math.min(100.0, overlap * 4.0));
        out.put("economic_relevance_score", Double.isFinite(correlation) ? round(Math.abs(correlation), 3) : 0.0);
        out.put("usable", usable);
        out.put("available_as", usable ? "hist_exog" : null);
        out.put("warnings", warning.isBlank() ? List.of() : List.of(warning));
        return out;
    }

    private static Map<String, Object> rejected(Series candidate, String reason) {
        return Map.of("series_id", candidate.seriesId(), "concept", candidate.concept(), "reason", reason);
    }

    private static AlignedPair align(Series target, Series candidate) {
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (String period : target.periods()) {
            Double x = candidate.values().get(period);
            Double y = target.values().get(period);
            if (x != null && y != null) {
                xs.add(x);
                ys.add(y);
            }
        }
        return new AlignedPair(
                xs.stream().mapToDouble(Double::doubleValue).toArray(),
                ys.stream().mapToDouble(Double::doubleValue).toArray());
    }

    private static RegressionModel regression(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        double meanX = Arrays.stream(x, 0, n).average().orElse(0);
        double meanY = Arrays.stream(y, 0, n).average().orElse(0);
        double numerator = 0;
        double denominator = 0;
        for (int i = 0; i < n; i++) {
            numerator += (x[i] - meanX) * (y[i] - meanY);
            denominator += Math.pow(x[i] - meanX, 2);
        }
        double slope = denominator == 0 ? 0 : numerator / denominator;
        double intercept = meanY - slope * meanX;
        List<Double> residuals = new ArrayList<>();
        for (int i = 0; i < n; i++) residuals.add(y[i] - (intercept + slope * x[i]));
        return new RegressionModel(intercept, slope, sampleStd(residuals));
    }

    private static double pearson(double[] x, double[] y) {
        if (x.length < 3 || y.length < 3) return Double.NaN;
        return TimeSeriesMath.pearson(
                Arrays.stream(x).boxed().toList(), Arrays.stream(y).boxed().toList());
    }

    private static double[] index(int length) {
        double[] values = new double[length];
        for (int i = 0; i < length; i++) values[i] = i;
        return values;
    }

    private static double sampleStd(List<Double> values) {
        return values.size() < 2 ? 0.0 : TimeSeriesMath.sampleStdev(values);
    }

    private static int seasonLength(String frequency) {
        return switch (frequency) {
            case "D" -> 7;
            case "W" -> 52;
            case "M" -> 12;
            case "Q" -> 4;
            default -> 1;
        };
    }

    private static List<String> validHorizons(String frequency, List<String> requested) {
        Map<String, Integer> valid = HORIZON_STEPS.getOrDefault(frequency, HORIZON_STEPS.get("M"));
        List<String> filtered = requested.stream().filter(valid::containsKey).toList();
        return filtered.isEmpty() ? DEFAULT_HORIZONS.getOrDefault(frequency, DEFAULT_HORIZONS.get("M")) : filtered;
    }

    private static int stepCount(String frequency, String horizon) {
        return HORIZON_STEPS.getOrDefault(frequency, HORIZON_STEPS.get("M")).getOrDefault(horizon, 1);
    }

    private static List<String> futureDates(String lastPeriod, String frequency, int steps) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= steps; i++) out.add(shiftPeriod(lastPeriod, frequency, i));
        return out;
    }

    private static String shiftPeriod(String raw, String frequency, int steps) {
        try {
            return switch (frequency) {
                case "Y" -> String.valueOf(Integer.parseInt(raw.substring(0, 4)) + steps);
                case "Q" -> {
                    String normalized = raw.toUpperCase(Locale.ROOT).replace("_", "-");
                    int year = Integer.parseInt(normalized.substring(0, 4));
                    int q = Integer.parseInt(normalized.substring(normalized.indexOf('Q') + 1));
                    int index = year * 4 + q - 1 + steps;
                    yield (index / 4) + "-Q" + (index % 4 + 1);
                }
                case "M" -> YearMonth.parse(raw.substring(0, 7)).plusMonths(steps).toString();
                case "W" -> LocalDate.parse(raw.substring(0, 10)).plusWeeks(steps).toString();
                default -> LocalDate.parse(raw.substring(0, 10)).plusDays(steps).toString();
            };
        } catch (RuntimeException ex) {
            return raw + "+" + steps;
        }
    }

    private static String canonicalPeriod(String raw, String frequency) {
        if (raw == null) return "";
        String value = raw.trim();
        try {
            return switch (frequency) {
                case "Y" -> value.substring(0, 4);
                case "Q" -> {
                    String upper = value.toUpperCase(Locale.ROOT);
                    if (upper.contains("Q")) yield upper.substring(0, 4) + "-Q" + upper.substring(upper.indexOf('Q') + 1, upper.indexOf('Q') + 2);
                    YearMonth ym = YearMonth.parse(value.substring(0, 7));
                    yield ym.getYear() + "-Q" + ((ym.getMonthValue() - 1) / 3 + 1);
                }
                case "M" -> value.substring(0, 7);
                case "W", "D" -> value.substring(0, Math.min(10, value.length()));
                default -> value;
            };
        } catch (RuntimeException ex) {
            return value;
        }
    }

    private static double round(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object value) {
        return value instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList()
                : List.of();
    }

    private static List<String> asStringList(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value == null) return null;
        try {
            return Double.parseDouble(String.valueOf(value).replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private record Series(
            String seriesId,
            String name,
            String source,
            String geo,
            String unit,
            String frequency,
            String concept,
            Map<String, Double> values) {
        static Series fromMap(Map<String, Object> map) {
            String requestedFrequency = str(map.get("frequency")).toUpperCase(Locale.ROOT);
            final String frequency = HORIZON_STEPS.containsKey(requestedFrequency) ? requestedFrequency : "M";
            Map<String, Double> values = new LinkedHashMap<>();
            List<Map<String, Object>> observations = asMapList(map.get("observations"));
            observations.stream()
                    .sorted(Comparator.comparing(o -> str(o.get("date"))))
                    .forEach(observation -> {
                        Double value = number(observation.get("value"));
                        String period = canonicalPeriod(str(observation.get("date")), frequency);
                        if (value != null && !period.isBlank() && Double.isFinite(value)) values.put(period, value);
                    });
            return new Series(
                    str(map.get("series_id")),
                    str(map.get("name")),
                    str(map.get("source")),
                    str(map.get("geo")),
                    str(map.get("unit")),
                    frequency,
                    !str(map.get("concept")).isBlank() ? str(map.get("concept")) : str(map.get("role")),
                    values);
        }

        List<String> periods() {
            return new ArrayList<>(values.keySet());
        }

        String lastPeriod() {
            return periods().getLast();
        }

        double lastValue() {
            return values.get(lastPeriod());
        }
    }

    private record ModelSpec(
            String name, int complexity, BiFunction<double[], Integer, double[]> forecaster) {}

    private record ModelEvaluation(
            String name,
            int complexity,
            double[] forecast,
            double residualStd,
            BacktestResult backtest,
            boolean exogenous,
            Series driver) {}

    private record BacktestResult(
            Double mae,
            Double rmse,
            Double mape,
            Double smape,
            Double directionalAccuracy,
            int folds,
            double residualStd) {}

    private record RegressionModel(double intercept, double slope, double residualStd) {}

    private record AlignedPair(double[] x, double[] y) {}
}
