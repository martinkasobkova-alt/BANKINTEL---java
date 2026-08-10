package cz.bankintel.service.timeseries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Trend and rolling metrics bundle (spec section 2): moving averages, rolling YoY, rolling
 * volatility, multi-horizon slopes, trend acceleration and direction-change flags.
 */
@Service
public class TrendAnalyticsService {

    private static final int[] ROLLING_WINDOWS = {3, 6, 12};
    private static final int ROLLING_VOLATILITY_WINDOW = 12;

    public Map<String, Object> computeTrendMetrics(Map<String, Double> series, String frequency) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> periods = TimeSeriesMath.sortedPeriods(series);
        out.put("observations_used", periods.size());
        if (periods.size() < 3) {
            out.put("warning", "Trendové metriky vyžadují alespoň tři pozorování.");
            return out;
        }

        Map<String, Object> rollingMeans = new LinkedHashMap<>();
        for (int window : windowsForFrequency(frequency)) {
            Map<String, Double> rm = TimeSeriesMath.rollingMean(series, window);
            rollingMeans.put("rolling_mean_" + window + "p", lastEntry(rm));
            rollingMeans.put("rolling_mean_" + window + "p_series_tail", tailMap(rm, 6));
        }
        out.put("rolling_means", rollingMeans);

        int yoyLag = yoyLagForFrequency(frequency);
        Map<String, Double> rollingYoy = TimeSeriesMath.pctChangeSeries(series, yoyLag);
        out.put("rolling_yoy_pct_last", TimeSeriesMath.lastValue(rollingYoy));
        out.put("rolling_yoy_pct_series_tail", tailMap(rollingYoy, 6));

        Map<String, Double> pct1 = TimeSeriesMath.pctChangeSeries(series, 1);
        Map<String, Double> rollingVol = rollingVolatility(pct1, ROLLING_VOLATILITY_WINDOW);
        out.put("rolling_volatility_last", TimeSeriesMath.lastValue(rollingVol));
        out.put("rolling_volatility_series_tail", tailMap(rollingVol, 6));

        out.put("short_term_trend_slope", TimeSeriesMath.trendSlope(series, Math.min(6, periods.size())));
        out.put("medium_term_trend_slope", TimeSeriesMath.trendSlope(series, Math.min(12, periods.size())));
        out.put("long_term_trend_slope", TimeSeriesMath.trendSlope(series, Math.min(24, periods.size())));

        Double shortSlope = TimeSeriesMath.trendSlope(series, Math.min(6, periods.size()));
        Double longSlope = TimeSeriesMath.trendSlope(series, Math.min(24, periods.size()));
        out.put("trend_acceleration", acceleration(shortSlope, longSlope));
        out.put("trend_direction_change", directionChange(shortSlope, longSlope));

        TimeSeriesMath.TrendFit fit = TimeSeriesMath.linearTrend(series);
        out.put("linear_trend", Map.of(
                "slope_per_step", fit.slopePerStep(),
                "r2", fit.r2(),
                "strength_score", TimeSeriesMath.trendStrengthScore(series)));
        TimeSeriesMath.logLinearTrend(series)
                .ifPresent(logFit -> out.put(
                        "log_linear_trend",
                        Map.of("implied_growth_rate_per_step_pct", (Math.exp(logFit.slopePerStep()) - 1.0) * 100.0, "r2", logFit.r2())));

        return out;
    }

    private static int[] windowsForFrequency(String frequency) {
        if ("Q".equalsIgnoreCase(frequency)) {
            return new int[] {2, 4, 8};
        }
        if ("Y".equalsIgnoreCase(frequency) || "A".equalsIgnoreCase(frequency)) {
            return new int[] {2, 3, 5};
        }
        return ROLLING_WINDOWS;
    }

    private static int yoyLagForFrequency(String frequency) {
        return switch (frequency == null ? "M" : frequency.toUpperCase()) {
            case "Q" -> 4;
            case "Y", "A" -> 1;
            case "W" -> 52;
            case "D" -> 252;
            default -> 12;
        };
    }

    private static Map<String, Double> rollingVolatility(Map<String, Double> pctChanges, int window) {
        List<String> periods = TimeSeriesMath.sortedPeriods(pctChanges);
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = window - 1; i < periods.size(); i++) {
            List<Double> slice = new java.util.ArrayList<>();
            for (int j = i - window + 1; j <= i; j++) {
                slice.add(pctChanges.get(periods.get(j)) / 100.0);
            }
            out.put(periods.get(i), slice.size() >= 2 ? TimeSeriesMath.populationStdev(slice) : null);
        }
        return out;
    }

    private static Map<String, Object> lastEntry(Map<String, Double> series) {
        List<String> periods = TimeSeriesMath.sortedPeriods(series);
        if (periods.isEmpty()) {
            return Map.of("period", null, "value", null);
        }
        String last = periods.get(periods.size() - 1);
        return Map.of("period", last, "value", series.get(last));
    }

    private static Map<String, Double> tailMap(Map<String, Double> series, int n) {
        List<String> periods = TimeSeriesMath.sortedPeriods(series);
        Map<String, Double> out = new LinkedHashMap<>();
        int start = Math.max(0, periods.size() - n);
        for (int i = start; i < periods.size(); i++) {
            String p = periods.get(i);
            out.put(p, series.get(p));
        }
        return out;
    }

    private static String acceleration(Double shortSlope, Double longSlope) {
        if (shortSlope == null || longSlope == null || longSlope == 0.0) {
            return "unknown";
        }
        double ratio = Math.abs(shortSlope) / Math.abs(longSlope);
        if (Math.signum(shortSlope) != Math.signum(longSlope)) {
            return "decelerating_or_reversal";
        }
        if (ratio > 1.3) {
            return "accelerating";
        }
        if (ratio < 0.7) {
            return "decelerating";
        }
        return "stable";
    }

    private static boolean directionChange(Double shortSlope, Double longSlope) {
        if (shortSlope == null || longSlope == null) {
            return false;
        }
        return Math.signum(shortSlope) != Math.signum(longSlope) && Math.abs(shortSlope) > 0;
    }
}
