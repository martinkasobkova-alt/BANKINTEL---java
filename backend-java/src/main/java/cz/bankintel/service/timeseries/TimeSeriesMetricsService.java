package cz.bankintel.service.timeseries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Computes the full "basic metrics" (spec section 1) + "indexation" (spec section 3) bundle for a
 * single time series, purely from {@link TimeSeriesMath} primitives — no LLM, no numeric
 * shortcuts. Every value in the returned map is either a plain number/string or {@code null}
 * (when the underlying calculation legitimately cannot be computed, e.g. too few observations),
 * never a fabricated placeholder.
 */
@Service
public class TimeSeriesMetricsService {

    private static final int[] TREND_WINDOWS = {3, 6, 12, 24, 60};
    private static final int MIN_VOLATILITY_OBSERVATIONS = 4;

    public Map<String, Object> computeMetrics(Map<String, Double> series, String basePeriod) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> periods = TimeSeriesMath.sortedPeriods(series);
        out.put("observations_used", periods.size());
        if (periods.isEmpty()) {
            out.put("warning", "Řada je prázdná — metriky nelze spočítat.");
            return out;
        }

        out.put("current", currentValueBlock(series, periods));
        out.put("history", historyBlock(series));
        out.put("distribution", distributionBlock(series));
        out.put("change", changeBlock(series));
        out.put("volatility", volatilityBlock(series, periods));
        out.put("drawdown", drawdownBlock(series));
        out.put("trend", trendBlock(series, periods));
        out.put("cagr_full_period", periods.size() >= 2 ? TimeSeriesMath.cagr(series, null, null) : null);
        out.put("indexation", indexationBlock(series, basePeriod));
        return out;
    }

    private static Map<String, Object> currentValueBlock(Map<String, Double> series, List<String> periods) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("last_period", periods.get(periods.size() - 1));
        out.put("last_value", TimeSeriesMath.lastValue(series));
        out.put("previous_period", periods.size() >= 2 ? periods.get(periods.size() - 2) : null);
        out.put("previous_value", TimeSeriesMath.previousValue(series));
        out.put("first_period", periods.get(0));
        out.put("first_value", TimeSeriesMath.firstValue(series));
        return out;
    }

    private static Map<String, Object> historyBlock(Map<String, Double> series) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("min", TimeSeriesMath.min(series));
        out.put("max", TimeSeriesMath.max(series));
        out.put("mean", TimeSeriesMath.mean(series));
        out.put("median", TimeSeriesMath.median(series));
        double standardDeviation = TimeSeriesMath.populationStdev(series.values());
        out.put("standard_deviation", standardDeviation);
        out.put("variance", standardDeviation * standardDeviation);
        TimeSeriesMath.lastLocalMax(series).ifPresent(e -> out.put("last_local_max", periodValue(e)));
        TimeSeriesMath.lastLocalMin(series).ifPresent(e -> out.put("last_local_min", periodValue(e)));
        return out;
    }

    private static Map<String, Object> distributionBlock(Map<String, Double> series) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("percentile_of_last_value", TimeSeriesMath.percentileOfLastValue(series));
        out.put("distance_from_max", distance(TimeSeriesMath.distanceFromMax(series)));
        out.put("distance_from_min", distance(TimeSeriesMath.distanceFromMin(series)));
        out.put("distance_from_mean", distance(TimeSeriesMath.distanceFromMean(series)));
        out.put("distance_from_mean_std_devs", TimeSeriesMath.distanceFromMeanInStdDevs(series));
        out.put("coefficient_of_variation", TimeSeriesMath.coefficientOfVariation(series));
        return out;
    }

    private static Map<String, Object> changeBlock(Map<String, Double> series) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("last_vs_previous", distance(TimeSeriesMath.lastVsPreviousChange(series)));
        Map<String, Double> yoyPct = TimeSeriesMath.yoyPercent(series);
        Map<String, Double> yoyAbs = TimeSeriesMath.yoyAbsolute(series);
        out.put("yoy_pct_last", TimeSeriesMath.lastValue(yoyPct));
        out.put("yoy_abs_last", TimeSeriesMath.lastValue(yoyAbs));
        TimeSeriesMath.UpDownCounts counts = TimeSeriesMath.countUpDownPeriods(series);
        out.put(
                "up_down_periods",
                Map.of("up", counts.up(), "down", counts.down(), "flat", counts.flat()));
        return out;
    }

    private static Map<String, Object> volatilityBlock(Map<String, Double> series, List<String> periods) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (periods.size() < MIN_VOLATILITY_OBSERVATIONS) {
            out.put("value", null);
            out.put("warning", "Nedostatek bodů pro výpočet volatility (min " + MIN_VOLATILITY_OBSERVATIONS + ").");
            return out;
        }
        Map<String, Double> pctChanges = TimeSeriesMath.pctChangeSeries(series, 1);
        List<Double> rets = pctChanges.values().stream().map(v -> v / 100.0).toList();
        out.put("value", rets.size() >= 2 ? TimeSeriesMath.populationStdev(rets) : null);
        out.put("basis", "population stdev of period-over-period relative changes");
        return out;
    }

    private static Map<String, Object> drawdownBlock(Map<String, Double> series) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<TimeSeriesMath.DrawdownSummary> dd = TimeSeriesMath.maxDrawdown(series);
        if (dd.isEmpty()) {
            out.put("max_drawdown_pct", null);
            return out;
        }
        TimeSeriesMath.DrawdownSummary summary = dd.get();
        out.put("max_drawdown_pct", summary.drawdownPct());
        out.put("peak_period", summary.peakPeriod());
        out.put("peak_value", summary.peakValue());
        out.put("trough_period", summary.troughPeriod());
        out.put("trough_value", summary.troughValue());
        return out;
    }

    private static Map<String, Object> trendBlock(Map<String, Double> series, List<String> periods) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (periods.size() < 3) {
            out.put("warning", "Trend vyžaduje alespoň tři body.");
            return out;
        }
        TimeSeriesMath.TrendFit fullFit = TimeSeriesMath.linearTrend(series);
        out.put("slope_per_step", fullFit.slopePerStep());
        out.put("intercept", fullFit.intercept());
        out.put("r2", fullFit.r2());
        out.put("strength_score", TimeSeriesMath.trendStrengthScore(series));
        out.put("multi_window_slope_per_step", TimeSeriesMath.multiWindowTrendSlopes(series, TREND_WINDOWS));
        TimeSeriesMath.logLinearTrend(series)
                .ifPresent(
                        logFit -> out.put(
                                "log_linear",
                                Map.of("implied_growth_rate_per_step_pct", (Math.exp(logFit.slopePerStep()) - 1.0) * 100.0, "r2", logFit.r2())));
        return out;
    }

    private static Map<String, Object> indexationBlock(Map<String, Double> series, String basePeriod) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index_100_at_first_period", TimeSeriesMath.indexTo(series, null, 100.0));
        if (basePeriod != null && !basePeriod.isBlank()) {
            out.put("index_100_at_base_period", TimeSeriesMath.indexTo(series, basePeriod, 100.0));
            out.put("base_period_used", basePeriod);
        }
        out.put("cumulative_change_from_first", TimeSeriesMath.cumulativeChangeFromFirst(series));
        return out;
    }

    private static Map<String, Object> periodValue(TimeSeriesMath.LocalExtremum extremum) {
        return Map.of("period", extremum.period(), "value", extremum.value());
    }

    private static Map<String, Object> distance(TimeSeriesMath.Distance d) {
        if (d == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("absolute", d.absolute());
        out.put("percent", d.percent());
        return out;
    }
}
