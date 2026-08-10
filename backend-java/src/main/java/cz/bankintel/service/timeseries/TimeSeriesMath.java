package cz.bankintel.service.timeseries;

import cz.bankintel.service.calculations.PeriodAlignment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stateless, entity-independent time-series math shared by every deterministic calculation
 * consumer in the app ({@code ComputedAdvancedEngine}, {@code ChartAnalyticsEngine}, and the new
 * analytics engine's {@code TimeSeriesMetricsService}/{@code SeriesComparisonService}/{@code
 * AnomalySeriesDetector}). Operates on the same {@code Map<String period, Double value>} shape
 * already used across {@code cz.bankintel.service.calculations} so no new series representation
 * is introduced. Every method is a pure function — no persistence, no entity coupling, no
 * randomness — so results are always exactly reproducible from the same input map.
 */
public final class TimeSeriesMath {

    private TimeSeriesMath() {}

    // ---------------------------------------------------------------------
    // Records
    // ---------------------------------------------------------------------

    public record Distance(double absolute, Double percent) {}

    public record UpDownCounts(int up, int down, int flat) {}

    public record LocalExtremum(String period, double value) {}

    public record DrawdownSummary(String peakPeriod, String troughPeriod, double peakValue, double troughValue, double drawdownPct) {}

    public record RegressionFit(double intercept, double slope, Double r2, int n) {}

    public record TrendFit(double intercept, double slopePerStep, Double r2, Map<String, Double> fitted) {}

    public record LaggedCorrelation(int lag, Double r, int n) {}

    public record YoyLag(Integer lag, String hint) {}

    // ---------------------------------------------------------------------
    // Basic accessors
    // ---------------------------------------------------------------------

    public static List<String> sortedPeriods(Map<String, Double> series) {
        List<String> keys = new ArrayList<>(series.keySet());
        keys.sort(PeriodAlignment::comparePeriods);
        return keys;
    }

    public static Double firstValue(Map<String, Double> series) {
        List<String> periods = sortedPeriods(series);
        return periods.isEmpty() ? null : series.get(periods.get(0));
    }

    public static Double lastValue(Map<String, Double> series) {
        List<String> periods = sortedPeriods(series);
        return periods.isEmpty() ? null : series.get(periods.get(periods.size() - 1));
    }

    public static Double previousValue(Map<String, Double> series) {
        List<String> periods = sortedPeriods(series);
        return periods.size() < 2 ? null : series.get(periods.get(periods.size() - 2));
    }

    public static Distance changeBetween(double from, double to) {
        double abs = to - from;
        Double pct = from != 0.0 ? abs / Math.abs(from) * 100.0 : null;
        return new Distance(abs, pct);
    }

    public static Distance lastVsPreviousChange(Map<String, Double> series) {
        Double last = lastValue(series);
        Double prev = previousValue(series);
        if (last == null || prev == null) {
            return null;
        }
        return changeBetween(prev, last);
    }

    public static double min(Map<String, Double> series) {
        return series.values().stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
    }

    public static double max(Map<String, Double> series) {
        return series.values().stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
    }

    public static double mean(Map<String, Double> series) {
        return series.values().stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    public static double median(Map<String, Double> series) {
        return median(series.values());
    }

    private static double median(Collection<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int n = sorted.size();
        if (n == 0) {
            return Double.NaN;
        }
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    public static double populationStdev(Collection<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
        return Math.sqrt(var);
    }

    public static double sampleStdev(Collection<Double> values) {
        int n = values.size();
        if (n < 2) {
            return 0.0;
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sumSq = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum();
        return Math.sqrt(sumSq / (n - 1));
    }

    /** Percentile rank (0-100) of {@code value} within the full distribution of {@code series}. */
    public static double percentileOfValue(Map<String, Double> series, double value) {
        List<Double> values = new ArrayList<>(series.values());
        if (values.size() < 2) {
            return 100.0;
        }
        long lesser = values.stream().filter(v -> v < value).count();
        return (double) lesser / (values.size() - 1) * 100.0;
    }

    public static Double percentileOfLastValue(Map<String, Double> series) {
        Double last = lastValue(series);
        return last == null ? null : percentileOfValue(series, last);
    }

    public static Distance distanceFromMax(Map<String, Double> series) {
        Double last = lastValue(series);
        if (last == null || series.isEmpty()) {
            return null;
        }
        return changeBetween(max(series), last);
    }

    public static Distance distanceFromMin(Map<String, Double> series) {
        Double last = lastValue(series);
        if (last == null || series.isEmpty()) {
            return null;
        }
        return changeBetween(min(series), last);
    }

    public static Distance distanceFromMean(Map<String, Double> series) {
        Double last = lastValue(series);
        if (last == null || series.isEmpty()) {
            return null;
        }
        return changeBetween(mean(series), last);
    }

    /** How many standard deviations the last value sits away from the series' own historical mean. */
    public static Double distanceFromMeanInStdDevs(Map<String, Double> series) {
        Double last = lastValue(series);
        if (last == null) {
            return null;
        }
        double sigma = populationStdev(series.values());
        if (sigma == 0.0) {
            return 0.0;
        }
        return (last - mean(series)) / sigma;
    }

    public static Double coefficientOfVariation(Map<String, Double> series) {
        double m = mean(series);
        if (m == 0.0 || Double.isNaN(m)) {
            return null;
        }
        return populationStdev(series.values()) / Math.abs(m);
    }

    public static UpDownCounts countUpDownPeriods(Map<String, Double> series) {
        List<String> periods = sortedPeriods(series);
        int up = 0;
        int down = 0;
        int flat = 0;
        for (int i = 1; i < periods.size(); i++) {
            double delta = series.get(periods.get(i)) - series.get(periods.get(i - 1));
            if (delta > 0) {
                up++;
            } else if (delta < 0) {
                down++;
            } else {
                flat++;
            }
        }
        return new UpDownCounts(up, down, flat);
    }

    public static Optional<LocalExtremum> lastLocalMax(Map<String, Double> series) {
        return lastLocalExtremum(series, true);
    }

    public static Optional<LocalExtremum> lastLocalMin(Map<String, Double> series) {
        return lastLocalExtremum(series, false);
    }

    private static Optional<LocalExtremum> lastLocalExtremum(Map<String, Double> series, boolean max) {
        List<String> periods = sortedPeriods(series);
        for (int i = periods.size() - 2; i >= 1; i--) {
            double prev = series.get(periods.get(i - 1));
            double cur = series.get(periods.get(i));
            double next = series.get(periods.get(i + 1));
            boolean isExtremum = max ? (cur >= prev && cur >= next) : (cur <= prev && cur <= next);
            if (isExtremum) {
                return Optional.of(new LocalExtremum(periods.get(i), cur));
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------------
    // Series -> series transformations
    // ---------------------------------------------------------------------

    public static Map<String, Double> rollingMean(Map<String, Double> series, int window) {
        int win = Math.max(2, window);
        List<String> periods = sortedPeriods(series);
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = win - 1; i < periods.size(); i++) {
            double sum = 0;
            for (int j = i - win + 1; j <= i; j++) {
                sum += series.get(periods.get(j));
            }
            out.put(periods.get(i), sum / win);
        }
        return out;
    }

    public static Map<String, Double> cumsum(Map<String, Double> series) {
        double sum = 0;
        Map<String, Double> out = new LinkedHashMap<>();
        for (String p : sortedPeriods(series)) {
            sum += series.get(p);
            out.put(p, sum);
        }
        return out;
    }

    public static Map<String, Double> zscoreSeries(Map<String, Double> series) {
        List<Double> values = new ArrayList<>(series.values());
        Map<String, Double> out = new LinkedHashMap<>();
        if (values.size() < 2) {
            return out;
        }
        double mu = mean(series);
        double sigma = populationStdev(values);
        if (sigma == 0.0) {
            return out;
        }
        for (String p : sortedPeriods(series)) {
            out.put(p, (series.get(p) - mu) / sigma);
        }
        return out;
    }

    /** Percent below the running maximum-to-date at every period (always <= 0). */
    public static Map<String, Double> drawdownPctSeries(Map<String, Double> series) {
        double runningMax = Double.NEGATIVE_INFINITY;
        Map<String, Double> out = new LinkedHashMap<>();
        for (String p : sortedPeriods(series)) {
            double v = series.get(p);
            runningMax = Math.max(runningMax, v);
            if (runningMax <= 0) {
                continue;
            }
            out.put(p, (v / runningMax - 1.0) * 100.0);
        }
        return out;
    }

    /** Percent above the running minimum-to-date at every period (always >= 0) — the "recovery" mirror of drawdown. */
    public static Map<String, Double> recoveryPctSeries(Map<String, Double> series) {
        double runningMin = Double.POSITIVE_INFINITY;
        Map<String, Double> out = new LinkedHashMap<>();
        for (String p : sortedPeriods(series)) {
            double v = series.get(p);
            runningMin = Math.min(runningMin, v);
            if (runningMin == 0.0 || !Double.isFinite(runningMin)) {
                continue;
            }
            out.put(p, (v / runningMin - 1.0) * 100.0 * (runningMin > 0 ? 1.0 : -1.0));
        }
        return out;
    }

    public static Optional<DrawdownSummary> maxDrawdown(Map<String, Double> series) {
        List<String> periods = sortedPeriods(series);
        double runningMax = Double.NEGATIVE_INFINITY;
        String peakPeriod = null;
        DrawdownSummary worst = null;
        for (String p : periods) {
            double v = series.get(p);
            if (v > runningMax) {
                runningMax = v;
                peakPeriod = p;
            }
            if (runningMax <= 0) {
                continue;
            }
            double ddPct = (v / runningMax - 1.0) * 100.0;
            if (worst == null || ddPct < worst.drawdownPct()) {
                worst = new DrawdownSummary(peakPeriod, p, runningMax, v, ddPct);
            }
        }
        return Optional.ofNullable(worst);
    }

    public static Map<String, Double> cumulativeChangeFromFirst(Map<String, Double> series) {
        List<String> periods = sortedPeriods(series);
        Map<String, Double> out = new LinkedHashMap<>();
        if (periods.isEmpty()) {
            return out;
        }
        double base = series.get(periods.get(0));
        for (String p : periods) {
            out.put(p, series.get(p) - base);
        }
        return out;
    }

    public static Map<String, Double> percentileRankHistory(Map<String, Double> series) {
        List<String> periods = sortedPeriods(series);
        List<Double> values = periods.stream().map(series::get).toList();
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < periods.size(); i++) {
            List<Double> hist = values.subList(0, i + 1);
            double v = values.get(i);
            double pr = 100.0;
            if (hist.size() >= 2) {
                long lesser = hist.stream().filter(x -> x < v).count();
                pr = (double) lesser / Math.max(hist.size() - 1, 1) * 100.0;
            }
            out.put(periods.get(i), pr);
        }
        return out;
    }

    public static Map<String, Double> indexTo(Map<String, Double> series, String basePeriod, double baseIndexValue) {
        List<String> periods = sortedPeriods(series);
        Map<String, Double> out = new LinkedHashMap<>();
        if (periods.isEmpty()) {
            return out;
        }
        String resolved = basePeriod != null ? PeriodAlignment.resolvePeriodAlias(basePeriod, series.keySet()) : periods.get(0);
        if (resolved == null) {
            resolved = periods.get(0);
        }
        Double base = series.get(resolved);
        if (base == null || base == 0.0) {
            return out;
        }
        for (String p : periods) {
            out.put(p, series.get(p) / base * baseIndexValue);
        }
        return out;
    }

    public static Map<String, Double> pctChangeSeries(Map<String, Double> series, int lag) {
        List<String> periods = sortedPeriods(series);
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = lag; i < periods.size(); i++) {
            double v0 = series.get(periods.get(i - lag));
            double v1 = series.get(periods.get(i));
            if (v0 == 0.0) {
                continue;
            }
            out.put(periods.get(i), (v1 - v0) / Math.abs(v0) * 100.0);
        }
        return out;
    }

    public static Map<String, Double> absChangeSeries(Map<String, Double> series, int lag) {
        List<String> periods = sortedPeriods(series);
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = lag; i < periods.size(); i++) {
            out.put(periods.get(i), series.get(periods.get(i)) - series.get(periods.get(i - lag)));
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Periodicity / YoY
    // ---------------------------------------------------------------------

    private static final java.util.regex.Pattern RE_YM = java.util.regex.Pattern.compile("^\\d{4}-\\d{2}");
    private static final java.util.regex.Pattern RE_YQ = java.util.regex.Pattern.compile("^\\d{4}\\s*[Qq][1-4]");
    private static final java.util.regex.Pattern RE_YEAR = java.util.regex.Pattern.compile("^\\d{4}$");
    private static final java.util.regex.Pattern RE_SEMI =
            java.util.regex.Pattern.compile("\\d{4}.*[HhBb][12]|\\d{4}.*[Ss][12]", java.util.regex.Pattern.CASE_INSENSITIVE);

    public static YoyLag inferPeriodicityLag(List<String> periods) {
        if (periods.isEmpty()) {
            return new YoyLag(null, null);
        }
        int ym = 0;
        int yq = 0;
        int semi = 0;
        int yrs = 0;
        for (String raw : periods) {
            String s = String.valueOf(raw).strip();
            if (RE_YM.matcher(s).matches()) {
                ym++;
            }
            if (RE_YQ.matcher(s.replace(" ", "")).find()) {
                yq++;
            }
            if (RE_SEMI.matcher(s).find()) {
                semi++;
            }
            if (RE_YEAR.matcher(s).matches()) {
                yrs++;
            }
        }
        int total = periods.size();
        if (ym >= Math.max(8, (int) (total * 0.45))) {
            return new YoyLag(12, "měsíční posloupnost (≈ lag 12 kroků)");
        }
        if (yq >= Math.max(6, (int) (total * 0.35))) {
            return new YoyLag(4, "čtvrtletní posloupnost (≈ lag 4 kroky)");
        }
        if (semi >= Math.max(5, (int) (total * 0.30))) {
            return new YoyLag(2, "pololetní posloupnost (≈ lag 2 kroky)");
        }
        if (yrs >= Math.max(4, (int) (total * 0.45))) {
            return new YoyLag(1, "roční posloupnost");
        }
        if (PeriodAlignment.inferYoyPeriodKey(periods.get(periods.size() - 1), new java.util.LinkedHashSet<>(periods)) != null) {
            return new YoyLag(4, "čtvrtletní formát (YYYYQx)");
        }
        return new YoyLag(null, null);
    }

    public static Map<String, Double> yoyPercent(Map<String, Double> series) {
        return yoyChange(series, true);
    }

    public static Map<String, Double> yoyAbsolute(Map<String, Double> series) {
        return yoyChange(series, false);
    }

    private static Map<String, Double> yoyChange(Map<String, Double> series, boolean pct) {
        List<String> periods = sortedPeriods(series);
        YoyLag lag = inferPeriodicityLag(periods);
        if (lag.lag() == null) {
            return new LinkedHashMap<>();
        }
        return pct ? pctChangeSeries(series, lag.lag()) : absChangeSeries(series, lag.lag());
    }

    public static Double cagr(Map<String, Double> series, String startPeriod, String endPeriod) {
        List<String> periods = sortedPeriods(series);
        if (periods.size() < 2) {
            return null;
        }
        String p0 = startPeriod != null ? PeriodAlignment.resolvePeriodAlias(startPeriod, series.keySet()) : periods.get(0);
        String p1 = endPeriod != null ? PeriodAlignment.resolvePeriodAlias(endPeriod, series.keySet()) : periods.get(periods.size() - 1);
        if (p0 == null || p1 == null) {
            return null;
        }
        Double v0 = series.get(p0);
        Double v1 = series.get(p1);
        if (v0 == null || v1 == null || v0 <= 0 || v1 <= 0) {
            return null;
        }
        int idx0 = periods.indexOf(p0);
        int idx1 = periods.indexOf(p1);
        if (idx0 < 0 || idx1 < 0) {
            return null;
        }
        int steps = Math.max(idx1 - idx0, 1);
        YoyLag lag = inferPeriodicityLag(periods);
        String hint = lag.hint() != null ? lag.hint() : "";
        double years;
        if (hint.startsWith("měsíč")) {
            years = Math.max(steps / 12.0, 1e-9);
        } else if (hint.contains("čtvrt")) {
            years = Math.max(steps / 4.0, 1e-9);
        } else if (hint.contains("roční")) {
            years = steps;
        } else {
            years = Math.max(steps / 4.0, 1e-9);
        }
        return (Math.pow(v1 / v0, 1.0 / years) - 1.0) * 100.0;
    }

    /**
     * Lightweight structural-break screen (no scipy/statsmodels-grade Chow/CUSUM test): splits the
     * series in half and compares the mean shift against the pooled standard error — the same
     * heuristic {@code forecast-service/app/guardrails.py}'s {@code _structural_break_warning}
     * uses, ported here so both the forecast guardrails and the new analytics
     * anomaly/compatibility layers share one implementation instead of three. Returns {@code
     * null} when the series is too short (< 8 observations) to split meaningfully.
     */
    public static Double structuralBreakZScore(Map<String, Double> series) {
        List<String> periods = sortedPeriods(series);
        int n = periods.size();
        if (n < 8) {
            return null;
        }
        int mid = n / 2;
        List<Double> first = periods.subList(0, mid).stream().map(series::get).toList();
        List<Double> second = periods.subList(mid, n).stream().map(series::get).toList();
        double pooledStd = sampleStdev(series.values());
        if (pooledStd == 0.0) {
            return null;
        }
        double se = pooledStd * Math.sqrt(1.0 / first.size() + 1.0 / second.size());
        if (se == 0.0) {
            return null;
        }
        double meanFirst = first.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double meanSecond = second.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return Math.abs(meanFirst - meanSecond) / se;
    }

    // ---------------------------------------------------------------------
    // Trend
    // ---------------------------------------------------------------------

    public static RegressionFit olsSlopeIntercept(List<Double> xs, List<Double> ys) {
        int n = xs.size();
        double mx = xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double my = ys.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            num += (xs.get(i) - mx) * (ys.get(i) - my);
            den += Math.pow(xs.get(i) - mx, 2);
        }
        if (den == 0) {
            return new RegressionFit(my, 0.0, null, n);
        }
        double slope = num / den;
        double intercept = my - slope * mx;
        double ssTot = ys.stream().mapToDouble(y -> Math.pow(y - my, 2)).sum();
        double ssRes = 0;
        for (int i = 0; i < n; i++) {
            double fitted = intercept + slope * xs.get(i);
            ssRes += Math.pow(ys.get(i) - fitted, 2);
        }
        Double r2 = ssTot > 0 ? 1 - ssRes / ssTot : null;
        return new RegressionFit(intercept, slope, r2, n);
    }

    public static TrendFit linearTrend(Map<String, Double> series) {
        List<String> periods = sortedPeriods(series);
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (int i = 0; i < periods.size(); i++) {
            xs.add((double) i);
            ys.add(series.get(periods.get(i)));
        }
        RegressionFit fit = olsSlopeIntercept(xs, ys);
        Map<String, Double> fitted = new LinkedHashMap<>();
        for (int i = 0; i < periods.size(); i++) {
            fitted.put(periods.get(i), fit.intercept() + fit.slope() * xs.get(i));
        }
        return new TrendFit(fit.intercept(), fit.slope(), fit.r2(), fitted);
    }

    /** Log-space linear trend (constant growth *rate* rather than constant absolute increment) —
     * only defined for a strictly-positive series; returns empty otherwise (never fabricates a
     * meaningless log of a non-positive value). */
    public static Optional<TrendFit> logLinearTrend(Map<String, Double> series) {
        if (series.values().stream().anyMatch(v -> v == null || v <= 0)) {
            return Optional.empty();
        }
        List<String> periods = sortedPeriods(series);
        Map<String, Double> logSeries = new LinkedHashMap<>();
        for (String p : periods) {
            logSeries.put(p, Math.log(series.get(p)));
        }
        TrendFit logFit = linearTrend(logSeries);
        Map<String, Double> fitted = new LinkedHashMap<>();
        logFit.fitted().forEach((p, v) -> fitted.put(p, Math.exp(v)));
        return Optional.of(new TrendFit(Math.exp(logFit.intercept()), logFit.slopePerStep(), logFit.r2(), fitted));
    }

    /** Trailing OLS slope-per-step over the last {@code windowSteps} observations, or {@code null}
     * if the series is shorter than the window (never fabricates a trend from insufficient data). */
    public static Double trendSlope(Map<String, Double> series, int windowSteps) {
        List<String> periods = sortedPeriods(series);
        if (periods.size() < windowSteps || windowSteps < 2) {
            return null;
        }
        List<String> tail = periods.subList(periods.size() - windowSteps, periods.size());
        Map<String, Double> window = new LinkedHashMap<>();
        for (String p : tail) {
            window.put(p, series.get(p));
        }
        return linearTrend(window).slopePerStep();
    }

    public static Map<Integer, Double> multiWindowTrendSlopes(Map<String, Double> series, int[] windows) {
        Map<Integer, Double> out = new LinkedHashMap<>();
        for (int w : windows) {
            Double slope = trendSlope(series, w);
            if (slope != null) {
                out.put(w, slope);
            }
        }
        return out;
    }

    /** How "trend-like" the full series is — R² of the OLS fit against ordinal time, 0 (no trend) to 1 (perfect trend). */
    public static double trendStrengthScore(Map<String, Double> series) {
        if (series.size() < 3) {
            return 0.0;
        }
        Double r2 = linearTrend(series).r2();
        return r2 == null ? 0.0 : Math.max(0.0, Math.min(1.0, r2));
    }

    // ---------------------------------------------------------------------
    // Correlation
    // ---------------------------------------------------------------------

    public static double pearson(List<Double> xs, List<Double> ys) {
        double mx = xs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double my = ys.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double num = 0;
        double denx = 0;
        double deny = 0;
        for (int i = 0; i < xs.size(); i++) {
            num += (xs.get(i) - mx) * (ys.get(i) - my);
            denx += Math.pow(xs.get(i) - mx, 2);
            deny += Math.pow(ys.get(i) - my, 2);
        }
        if (denx <= 0 || deny <= 0) {
            return Double.NaN;
        }
        return num / Math.sqrt(denx * deny);
    }

    public static Double correlation(Map<String, Double> a, Map<String, Double> b) {
        List<String> common = PeriodAlignment.sortedCommonPeriods(a, b);
        if (common.size() < 3) {
            return null;
        }
        List<Double> xs = common.stream().map(a::get).toList();
        List<Double> ys = common.stream().map(b::get).toList();
        double r = pearson(xs, ys);
        return Double.isNaN(r) ? null : r;
    }

    /**
     * Correlation of {@code a(t)} against {@code b(t-lag)} for each lag in {@code lags} — a
     * positive lag means {@code b} leads {@code a} by that many steps. Used to find how many
     * periods a candidate driver typically leads/lags the series it is compared against.
     */
    public static List<LaggedCorrelation> laggedCorrelationGrid(Map<String, Double> a, Map<String, Double> b, int[] lags) {
        List<String> periodsA = sortedPeriods(a);
        List<LaggedCorrelation> out = new ArrayList<>();
        for (int lag : lags) {
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            for (String period : periodsA) {
                Double aVal = a.get(period);
                String shiftedPeriod = shiftPeriodIndex(periodsA, period, -lag);
                Double bVal = shiftedPeriod != null ? b.get(shiftedPeriod) : null;
                if (aVal != null && bVal != null) {
                    xs.add(aVal);
                    ys.add(bVal);
                }
            }
            if (xs.size() < 3) {
                out.add(new LaggedCorrelation(lag, null, xs.size()));
                continue;
            }
            double r = pearson(xs, ys);
            out.add(new LaggedCorrelation(lag, Double.isNaN(r) ? null : r, xs.size()));
        }
        return out;
    }

    private static String shiftPeriodIndex(List<String> orderedPeriods, String period, int stepsBack) {
        int idx = orderedPeriods.indexOf(period);
        int target = idx + stepsBack;
        return target >= 0 && target < orderedPeriods.size() ? orderedPeriods.get(target) : null;
    }

    /** The lag (from {@code lags}) with the strongest absolute correlation — ties broken by preferring the smallest |lag|. */
    public static LaggedCorrelation bestLag(Map<String, Double> a, Map<String, Double> b, int[] lags) {
        List<LaggedCorrelation> grid = laggedCorrelationGrid(a, b, lags);
        LaggedCorrelation best = null;
        for (LaggedCorrelation candidate : grid) {
            if (candidate.r() == null) {
                continue;
            }
            if (best == null
                    || Math.abs(candidate.r()) > Math.abs(best.r())
                    || (Math.abs(candidate.r()) == Math.abs(best.r()) && Math.abs(candidate.lag()) < Math.abs(best.lag()))) {
                best = candidate;
            }
        }
        return best;
    }

    public static RegressionFit regressOls(Map<String, Double> y, Map<String, Double> x) {
        List<String> common = PeriodAlignment.sortedCommonPeriods(y, x);
        if (common.size() < 3) {
            return null;
        }
        List<Double> xs = common.stream().map(x::get).toList();
        List<Double> ys = common.stream().map(y::get).toList();
        return olsSlopeIntercept(xs, ys);
    }

    // ---------------------------------------------------------------------
    // Economic relationship helpers
    // ---------------------------------------------------------------------

    /** Simple two-point elasticity: total %ΔY over the common span divided by total %ΔX — the
     * textbook "point elasticity" definition, using the first/last common observations. */
    public static Double simpleElasticity(Map<String, Double> y, Map<String, Double> x) {
        List<String> common = PeriodAlignment.sortedCommonPeriods(y, x);
        if (common.size() < 2) {
            return null;
        }
        double y0 = y.get(common.get(0));
        double y1 = y.get(common.get(common.size() - 1));
        double x0 = x.get(common.get(0));
        double x1 = x.get(common.get(common.size() - 1));
        if (x0 == 0.0 || y0 == 0.0 || x1 == x0) {
            return null;
        }
        double pctY = (y1 - y0) / Math.abs(y0);
        double pctX = (x1 - x0) / Math.abs(x0);
        if (pctX == 0.0) {
            return null;
        }
        return pctY / pctX;
    }

    /** Regression-based elasticity: OLS slope of Y's period-over-period % change on X's — more
     * robust than the two-point version when the relationship is noisy but still consistent. */
    public static Double regressionElasticity(Map<String, Double> y, Map<String, Double> x) {
        Map<String, Double> yPct = pctChangeSeries(y, 1);
        Map<String, Double> xPct = pctChangeSeries(x, 1);
        RegressionFit fit = regressOls(yPct, xPct);
        return fit == null ? null : fit.slope();
    }

    /** OLS slope of {@code series}'s period-over-period returns on {@code benchmark}'s returns —
     * the standard "beta" sensitivity measure, generalized beyond equities to any two series. */
    public static Double betaVsBenchmark(Map<String, Double> series, Map<String, Double> benchmark) {
        Map<String, Double> seriesReturns = pctChangeSeries(series, 1);
        Map<String, Double> benchmarkReturns = pctChangeSeries(benchmark, 1);
        RegressionFit fit = regressOls(seriesReturns, benchmarkReturns);
        return fit == null ? null : fit.slope();
    }
}
