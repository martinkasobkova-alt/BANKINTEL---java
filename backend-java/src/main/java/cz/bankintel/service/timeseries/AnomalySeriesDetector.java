package cz.bankintel.service.timeseries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Detects generic, economically-agnostic anomalies/signals in a single time series: extremes,
 * z-score/percentile outliers, structural breaks, regime changes, volatility spikes,
 * acceleration/deceleration, long run lengths, and trend-deviation vs mean-reversion setups.
 * Every flag is backed by a concrete, named statistical rule (never a vague "looks unusual") so
 * the resulting JSON can be shown to a user with a precise reason attached — no LLM judgment is
 * used to decide whether something is anomalous.
 */
@Service
public class AnomalySeriesDetector {

    private static final double OUTLIER_Z_THRESHOLD = 2.5;
    private static final double STRUCTURAL_BREAK_Z_THRESHOLD = 3.0;
    private static final double VOLATILITY_SPIKE_RATIO = 1.8;
    private static final double ACCELERATION_RATIO_THRESHOLD = 1.8;
    private static final int RECENT_WINDOW = 12;
    private static final int MIN_OBSERVATIONS_FOR_VOLATILITY = 8;
    private static final int MIN_OBSERVATIONS_FOR_BREAK = 8;

    public record Anomaly(String type, String period, Double value, String description, String severity) {}

    public List<Anomaly> detect(Map<String, Double> series) {
        List<String> periods = TimeSeriesMath.sortedPeriods(series);
        List<Anomaly> out = new ArrayList<>();
        if (periods.size() < 3) {
            return out;
        }

        addExtremes(series, periods, out);
        addLargestMoves(series, periods, out);
        addZScoreOutliers(series, out);
        addStructuralBreak(series, periods, out);
        addRegimeChange(series, periods, out);
        addVolatilitySpike(series, periods, out);
        addAccelerationDeceleration(series, periods, out);
        addLongRunLength(series, periods, out);
        addTrendDeviation(series, periods, out);
        return out;
    }

    private static void addExtremes(Map<String, Double> series, List<String> periods, List<Anomaly> out) {
        double max = TimeSeriesMath.max(series);
        double min = TimeSeriesMath.min(series);
        String lastPeriod = periods.get(periods.size() - 1);
        double lastValue = series.get(lastPeriod);
        if (lastValue >= max) {
            out.add(new Anomaly("historical_high", lastPeriod, lastValue, "Poslední hodnota je nejvyšší v celé dostupné historii řady.", "info"));
        }
        if (lastValue <= min) {
            out.add(new Anomaly("historical_low", lastPeriod, lastValue, "Poslední hodnota je nejnižší v celé dostupné historii řady.", "info"));
        }
    }

    private static void addLargestMoves(Map<String, Double> series, List<String> periods, List<Anomaly> out) {
        int window = Math.min(12, periods.size() - 1);
        if (window < 1) {
            return;
        }
        Map<String, Double> pctMoves = TimeSeriesMath.pctChangeSeries(series, window);
        if (pctMoves.isEmpty()) {
            return;
        }
        String maxPeriod = null;
        String minPeriod = null;
        double maxVal = Double.NEGATIVE_INFINITY;
        double minVal = Double.POSITIVE_INFINITY;
        for (Map.Entry<String, Double> entry : pctMoves.entrySet()) {
            if (entry.getValue() > maxVal) {
                maxVal = entry.getValue();
                maxPeriod = entry.getKey();
            }
            if (entry.getValue() < minVal) {
                minVal = entry.getValue();
                minPeriod = entry.getKey();
            }
        }
        if (maxPeriod != null) {
            out.add(new Anomaly(
                    "largest_rise_" + window + "p",
                    maxPeriod,
                    maxVal,
                    String.format(java.util.Locale.US, "Největší nárůst za %d period (%.1f %%) v celé historii řady.", window, maxVal),
                    "info"));
        }
        if (minPeriod != null) {
            out.add(new Anomaly(
                    "largest_fall_" + window + "p",
                    minPeriod,
                    minVal,
                    String.format(java.util.Locale.US, "Největší pokles za %d period (%.1f %%) v celé historii řady.", window, minVal),
                    "info"));
        }
    }

    private static void addZScoreOutliers(Map<String, Double> series, List<Anomaly> out) {
        if (series.size() < MIN_OBSERVATIONS_FOR_VOLATILITY) {
            return;
        }
        Map<String, Double> z = TimeSeriesMath.zscoreSeries(series);
        for (String p : TimeSeriesMath.sortedPeriods(series)) {
            Double zv = z.get(p);
            if (zv != null && Math.abs(zv) > OUTLIER_Z_THRESHOLD) {
                out.add(new Anomaly(
                        "zscore_outlier",
                        p,
                        series.get(p),
                        String.format(java.util.Locale.US, "Hodnota se odchyluje od historického průměru o %.1f směrodatné odchylky.", zv),
                        Math.abs(zv) > OUTLIER_Z_THRESHOLD * 1.5 ? "high" : "medium"));
            }
        }
    }

    private static void addStructuralBreak(Map<String, Double> series, List<String> periods, List<Anomaly> out) {
        if (periods.size() < MIN_OBSERVATIONS_FOR_BREAK) {
            return;
        }
        Double z = TimeSeriesMath.structuralBreakZScore(series);
        if (z != null && z > STRUCTURAL_BREAK_Z_THRESHOLD) {
            out.add(new Anomaly(
                    "possible_structural_break",
                    periods.get(periods.size() / 2),
                    z,
                    String.format(
                            java.util.Locale.US,
                            "Heuristický test (ne formální Chow/CUSUM) indikuje posun průměru mezi první a druhou polovinou řady (z=%.1f).",
                            z),
                    "medium"));
        }
    }

    private static void addRegimeChange(Map<String, Double> series, List<String> periods, List<Anomaly> out) {
        if (periods.size() < 2 * RECENT_WINDOW) {
            return;
        }
        List<String> recentPeriods = periods.subList(periods.size() - RECENT_WINDOW, periods.size());
        List<String> priorPeriods = periods.subList(periods.size() - 2 * RECENT_WINDOW, periods.size() - RECENT_WINDOW);
        double recentMean = recentPeriods.stream().mapToDouble(series::get).average().orElse(0);
        double priorMean = priorPeriods.stream().mapToDouble(series::get).average().orElse(0);
        double priorStdev = TimeSeriesMath.populationStdev(priorPeriods.stream().map(series::get).toList());
        if (priorStdev == 0.0) {
            return;
        }
        double shift = Math.abs(recentMean - priorMean) / priorStdev;
        if (shift > 1.5) {
            out.add(new Anomaly(
                    "regime_change",
                    periods.get(periods.size() - 1),
                    recentMean,
                    String.format(
                            java.util.Locale.US,
                            "Průměr posledních %d období (%.2f) se výrazně liší od předchozích %d období (%.2f).",
                            RECENT_WINDOW, recentMean, RECENT_WINDOW, priorMean),
                    shift > 2.5 ? "high" : "medium"));
        }
    }

    private static void addVolatilitySpike(Map<String, Double> series, List<String> periods, List<Anomaly> out) {
        if (periods.size() < 2 * RECENT_WINDOW) {
            return;
        }
        Map<String, Double> pctChanges = TimeSeriesMath.pctChangeSeries(series, 1);
        List<String> changePeriods = TimeSeriesMath.sortedPeriods(pctChanges);
        if (changePeriods.size() < 2 * RECENT_WINDOW) {
            return;
        }
        List<Double> recent = changePeriods.subList(changePeriods.size() - RECENT_WINDOW, changePeriods.size())
                .stream().map(pctChanges::get).toList();
        List<Double> full = changePeriods.stream().map(pctChanges::get).toList();
        double recentVol = TimeSeriesMath.populationStdev(recent);
        double fullVol = TimeSeriesMath.populationStdev(full);
        if (fullVol > 0 && recentVol / fullVol > VOLATILITY_SPIKE_RATIO) {
            out.add(new Anomaly(
                    "volatility_spike",
                    periods.get(periods.size() - 1),
                    recentVol,
                    String.format(
                            java.util.Locale.US,
                            "Volatilita posledních %d období je %.1fx vyšší než dlouhodobý průměr.",
                            RECENT_WINDOW, recentVol / fullVol),
                    "medium"));
        }
    }

    private static void addAccelerationDeceleration(Map<String, Double> series, List<String> periods, List<Anomaly> out) {
        Double shortSlope = TimeSeriesMath.trendSlope(series, Math.min(6, periods.size()));
        Double longSlope = TimeSeriesMath.trendSlope(series, Math.min(24, periods.size()));
        if (shortSlope == null || longSlope == null || longSlope == 0.0) {
            return;
        }
        double ratio = Math.abs(shortSlope) / Math.abs(longSlope);
        if (ratio > ACCELERATION_RATIO_THRESHOLD && Math.signum(shortSlope) == Math.signum(longSlope)) {
            out.add(new Anomaly(
                    "sharp_acceleration",
                    periods.get(periods.size() - 1),
                    shortSlope,
                    String.format(java.util.Locale.US, "Krátkodobý trend zrychluje — sklon posledních period je %.1fx silnější než dlouhodobý sklon.", ratio),
                    "medium"));
        } else if (Math.signum(shortSlope) != Math.signum(longSlope) && Math.abs(shortSlope) > 0) {
            out.add(new Anomaly(
                    "trend_reversal",
                    periods.get(periods.size() - 1),
                    shortSlope,
                    "Krátkodobý trend obrátil směr oproti dlouhodobému trendu.",
                    "medium"));
        }
    }

    private static void addLongRunLength(Map<String, Double> series, List<String> periods, List<Anomaly> out) {
        int longestUp = 0;
        int longestDown = 0;
        int curUp = 0;
        int curDown = 0;
        int trailingUp = 0;
        int trailingDown = 0;
        for (int i = 1; i < periods.size(); i++) {
            double delta = series.get(periods.get(i)) - series.get(periods.get(i - 1));
            if (delta > 0) {
                curUp++;
                curDown = 0;
            } else if (delta < 0) {
                curDown++;
                curUp = 0;
            } else {
                curUp = 0;
                curDown = 0;
            }
            longestUp = Math.max(longestUp, curUp);
            longestDown = Math.max(longestDown, curDown);
            if (i == periods.size() - 1) {
                trailingUp = curUp;
                trailingDown = curDown;
            }
        }
        int significantRun = Math.max(6, periods.size() / 4);
        if (trailingUp >= significantRun) {
            out.add(new Anomaly(
                    "long_run_up",
                    periods.get(periods.size() - 1),
                    (double) trailingUp,
                    "Řada roste " + trailingUp + " period v řadě bez přerušení.",
                    "info"));
        }
        if (trailingDown >= significantRun) {
            out.add(new Anomaly(
                    "long_run_down",
                    periods.get(periods.size() - 1),
                    (double) trailingDown,
                    "Řada klesá " + trailingDown + " period v řadě bez přerušení.",
                    "info"));
        }
    }

    private static void addTrendDeviation(Map<String, Double> series, List<String> periods, List<Anomaly> out) {
        if (periods.size() < 6) {
            return;
        }
        TimeSeriesMath.TrendFit fit = TimeSeriesMath.linearTrend(series);
        String lastPeriod = periods.get(periods.size() - 1);
        double lastValue = series.get(lastPeriod);
        double fittedLast = fit.fitted().get(lastPeriod);
        List<Double> residuals = new ArrayList<>();
        for (String p : periods) {
            residuals.add(series.get(p) - fit.fitted().get(p));
        }
        double residualStdev = TimeSeriesMath.populationStdev(residuals);
        if (residualStdev == 0.0) {
            return;
        }
        double deviationZ = (lastValue - fittedLast) / residualStdev;
        if (Math.abs(deviationZ) > 2.0) {
            String flag = deviationZ > 0 ? "above_trend" : "below_trend";
            out.add(new Anomaly(
                    "trend_deviation_" + flag,
                    lastPeriod,
                    deviationZ,
                    String.format(
                            java.util.Locale.US,
                            "Poslední hodnota je %.1f směrodatné odchylky %s dlouhodobého trendu — možný signál pro mean-reversion.",
                            Math.abs(deviationZ), deviationZ > 0 ? "nad" : "pod"),
                    Math.abs(deviationZ) > 3.0 ? "high" : "medium"));
        }
    }

    public static Map<String, Object> toMap(Anomaly a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", a.type());
        out.put("period", a.period());
        out.put("value", a.value());
        out.put("description", a.description());
        out.put("severity", a.severity());
        return out;
    }
}
