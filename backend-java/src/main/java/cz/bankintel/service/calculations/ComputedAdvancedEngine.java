package cz.bankintel.service.calculations;

import cz.bankintel.domain.entity.ComputedIndicatorEntity;
import cz.bankintel.service.timeseries.TimeSeriesMath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Extended computed-indicator operations — port {@code computed_advanced.py}. The actual number
 * crunching (z-score, drawdown, CAGR, trend, correlation, regression, ...) is delegated to the
 * shared {@link TimeSeriesMath} utility; this class only owns the operation dispatch, the
 * row/warning/diagnostics JSON shape the computed-indicator API contract expects, and the
 * op-specific validation messages.
 */
@Component
@RequiredArgsConstructor
public class ComputedAdvancedEngine {

    public static final Set<String> EXTENDED_OPS = Set.of(
            "pct_points", "log_a", "index_100_first", "index_b100_first", "yoy_pct_auto", "yoy_abs_auto",
            "mom_pct_auto", "qoq_pct_auto", "roll_mean", "cumsum", "volatility_ret", "zscore", "drawdown_pct",
            "cagr_range", "corr_pearson", "regress_ols", "index_vs_b_pct", "real_div_infl", "cum_change_first",
            "pct_rank_hist", "trend_linear_time");

    private final SeriesOperandLoader operandLoader;

    public ComputedIndicatorRunner.RunResult run(ComputedIndicatorEntity doc, String userId) {
        String op = str(doc.getOperation());
        Map<String, Object> left = doc.getLeft() != null ? doc.getLeft() : Map.of();
        Map<String, Object> right = doc.getRight() != null ? doc.getRight() : Map.of();
        Map<String, Object> opts = doc.getOptions() != null ? doc.getOptions() : Map.of();
        Map<String, Double> leftMap = operandLoader.loadSeriesMap(left, userId);
        Map<String, Double> rightMap = hasRightOperand(right) ? operandLoader.loadSeriesMap(right, userId) : Map.of();

        List<String> warnings = new ArrayList<>();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("operation", op);

        List<Map<String, Object>> rows = switch (op) {
            case "pct_points" -> pctPoints(leftMap, rightMap, warnings, diagnostics);
            case "index_vs_b_pct", "real_div_infl" -> indexVsB(leftMap, rightMap, op, warnings, diagnostics);
            case "log_a" -> logA(leftMap, warnings, diagnostics);
            case "index_100_first" -> indexFirst(leftMap, warnings, diagnostics);
            case "index_b100_first" -> indexFirst(rightMap, warnings, diagnostics);
            case "yoy_pct_auto" -> yoyPct(leftMap, warnings, diagnostics);
            case "yoy_abs_auto" -> yoyAbs(leftMap, warnings, diagnostics);
            case "mom_pct_auto", "qoq_pct_auto" -> pctChangeLag(leftMap, 1, diagnostics);
            case "roll_mean" -> rollMean(leftMap, opts, diagnostics);
            case "cumsum" -> cumsum(leftMap, diagnostics);
            case "volatility_ret" -> volatilityRet(leftMap, warnings, diagnostics);
            case "zscore" -> zscore(leftMap, warnings, diagnostics);
            case "drawdown_pct" -> drawdownPct(leftMap, diagnostics);
            case "cagr_range" -> cagrRange(leftMap, opts, warnings, diagnostics);
            case "cum_change_first" -> cumChangeFirst(leftMap, diagnostics);
            case "pct_rank_hist" -> pctRankHist(leftMap, diagnostics);
            case "trend_linear_time" -> trendLinear(leftMap, warnings, diagnostics);
            case "corr_pearson" -> corrPearson(leftMap, rightMap, warnings, diagnostics);
            case "regress_ols" -> regressOls(leftMap, rightMap, warnings, diagnostics);
            default -> List.of();
        };

        if (rows.isEmpty() && warnings.isEmpty()) {
            warnings.add("Operace '" + op + "' není podporována.");
        }
        return new ComputedIndicatorRunner.RunResult(rows, warnings, diagnostics);
    }

    private static List<Map<String, Object>> pctPoints(
            Map<String, Double> left, Map<String, Double> right, List<String> warnings, Map<String, Object> diag) {
        List<String> common = PeriodAlignment.sortedCommonPeriods(left, right);
        diag.put("observations_used", common.size());
        if (common.isEmpty()) {
            warnings.add("Žádná společná období pro řady A a B.");
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String p : common) {
            rows.add(Map.of("period", p, "value", left.get(p) - right.get(p)));
        }
        return rows;
    }

    private static List<Map<String, Object>> indexVsB(
            Map<String, Double> left,
            Map<String, Double> right,
            String op,
            List<String> warnings,
            Map<String, Object> diag) {
        if ("real_div_infl".equals(op)) {
            warnings.add(
                    "„Reálná hodnota“ je zjednodušeně poměrem A/B×100 — B musí být vhodný deflátor ve slučitelné škále.");
        }
        List<String> common = PeriodAlignment.sortedCommonPeriods(left, right);
        diag.put("observations_used", common.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String p : common) {
            double b = right.get(p);
            if (b == 0.0) {
                warnings.add("Období " + p + ": dělení nulou u řady B, přeskakuji.");
                continue;
            }
            rows.add(Map.of("period", p, "value", left.get(p) / b * 100.0));
        }
        return rows;
    }

    private static List<Map<String, Object>> logA(
            Map<String, Double> left, List<String> warnings, Map<String, Object> diag) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String p : TimeSeriesMath.sortedPeriods(left)) {
            double x = left.get(p);
            if (x <= 0) {
                warnings.add("ln je definován jen pro kladné hodnoty — přeskakuji " + p + ".");
                continue;
            }
            rows.add(Map.of("period", p, "value", Math.log(x)));
        }
        diag.put("observations_used", rows.size());
        return rows;
    }

    private static List<Map<String, Object>> indexFirst(
            Map<String, Double> series, List<String> warnings, Map<String, Object> diag) {
        if (series.isEmpty()) {
            warnings.add("Chybí data pro indexaci.");
            return List.of();
        }
        List<String> periods = TimeSeriesMath.sortedPeriods(series);
        double base = series.get(periods.getFirst());
        if (base == 0.0) {
            warnings.add("Základní hodnota je nulová.");
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String p : periods) {
            rows.add(Map.of("period", p, "value", series.get(p) / base * 100.0));
        }
        diag.put("observations_used", rows.size());
        return rows;
    }

    private static List<Map<String, Object>> yoyPct(
            Map<String, Double> left, List<String> warnings, Map<String, Object> diag) {
        return yoyChange(left, true, warnings, diag);
    }

    private static List<Map<String, Object>> yoyAbs(
            Map<String, Double> left, List<String> warnings, Map<String, Object> diag) {
        return yoyChange(left, false, warnings, diag);
    }

    private static List<Map<String, Object>> yoyChange(
            Map<String, Double> left, boolean pct, List<String> warnings, Map<String, Object> diag) {
        List<String> periods = TimeSeriesMath.sortedPeriods(left);
        TimeSeriesMath.YoyLag lag = TimeSeriesMath.inferPeriodicityLag(periods);
        diag.put("frequency_hint", lag.hint());
        if (lag.lag() == null) {
            warnings.add("Nelze spočítat YoY, protože není známá frekvence řady (měsíc / čtvrtletí / rok).");
            return List.of();
        }
        diag.put("yoy_lag", lag.lag());
        List<Map<String, Object>> rows = pctChangeFromSorted(periods, left, lag.lag(), pct);
        diag.put("observations_used", rows.size());
        return rows;
    }

    private static List<Map<String, Object>> pctChangeLag(
            Map<String, Double> left, int lag, Map<String, Object> diag) {
        List<String> periods = TimeSeriesMath.sortedPeriods(left);
        List<Map<String, Object>> rows = pctChangeFromSorted(periods, left, lag, true);
        diag.put("observations_used", rows.size());
        return rows;
    }

    private static List<Map<String, Object>> rollMean(
            Map<String, Double> left, Map<String, Object> opts, Map<String, Object> diag) {
        int win = Math.max(2, toInt(opts.get("window"), toInt(opts.get("rolling_window"), 12)));
        Map<String, Double> smoothed = TimeSeriesMath.rollingMean(left, win);
        diag.put("rolling_window", win);
        diag.put("observations_used", smoothed.size());
        return toRows(smoothed);
    }

    private static List<Map<String, Object>> cumsum(Map<String, Double> left, Map<String, Object> diag) {
        Map<String, Double> summed = TimeSeriesMath.cumsum(left);
        diag.put("observations_used", summed.size());
        return toRows(summed);
    }

    private static List<Map<String, Object>> volatilityRet(
            Map<String, Double> left, List<String> warnings, Map<String, Object> diag) {
        List<String> periods = TimeSeriesMath.sortedPeriods(left);
        Map<String, Double> pctChanges = TimeSeriesMath.pctChangeSeries(left, 1);
        List<Double> rets = pctChanges.values().stream().map(v -> v / 100.0).toList();
        if (rets.size() < 2) {
            warnings.add("Nedostatek bodů pro výpočet volatility.");
            return List.of();
        }
        double sigma = TimeSeriesMath.populationStdev(rets);
        diag.put("sigma_rel_changes", sigma);
        return List.of(Map.of("period", periods.getLast(), "value", sigma));
    }

    private static List<Map<String, Object>> zscore(
            Map<String, Double> left, List<String> warnings, Map<String, Object> diag) {
        if (left.size() < 2) {
            warnings.add("Nelze počítat z-score — málo hodnot.");
            return List.of();
        }
        Map<String, Double> scored = TimeSeriesMath.zscoreSeries(left);
        if (scored.isEmpty()) {
            warnings.add("Nulová variabilita.");
            return List.of();
        }
        diag.put("observations_used", scored.size());
        return toRows(scored);
    }

    private static List<Map<String, Object>> drawdownPct(Map<String, Double> left, Map<String, Object> diag) {
        Map<String, Double> drawdown = TimeSeriesMath.drawdownPctSeries(left);
        diag.put("observations_used", drawdown.size());
        return toRows(drawdown);
    }

    private static List<Map<String, Object>> cagrRange(
            Map<String, Double> left,
            Map<String, Object> opts,
            List<String> warnings,
            Map<String, Object> diag) {
        List<String> periods = TimeSeriesMath.sortedPeriods(left);
        if (periods.size() < 2) {
            warnings.add("CAGR vyžaduje alespoň dva pozorované body.");
            return List.of();
        }
        String p0 = str(opts.get("start_period"));
        String p1 = str(opts.get("end_period"));
        if (p0.isBlank()) {
            p0 = periods.getFirst();
        }
        if (p1.isBlank()) {
            p1 = periods.getLast();
        }
        Double v0 = left.get(p0);
        Double v1 = left.get(p1);
        if (v0 == null || v1 == null || v0 <= 0 || v1 <= 0) {
            warnings.add("CAGR vyžaduje kladné hodnoty v okrajových obdobích.");
            return List.of();
        }
        int idx0 = periods.indexOf(p0);
        int idx1 = periods.indexOf(p1);
        if (idx0 < 0 || idx1 < 0) {
            warnings.add("CAGR: zadané období není v řadě.");
            return List.of();
        }
        TimeSeriesMath.YoyLag lag = TimeSeriesMath.inferPeriodicityLag(periods);
        String hint = lag.hint() != null ? lag.hint() : "";
        if (!hint.startsWith("měsíc") && !hint.contains("čtvrt") && !hint.contains("roční")) {
            warnings.add(
                    "Nejednoznačná periodicita — CAGR používá hrubý odhad délky období; ověřte výsledek ručně.");
        }
        Double cagr = TimeSeriesMath.cagr(left, p0, p1);
        if (cagr == null) {
            warnings.add("CAGR vyžaduje kladné hodnoty v okrajových obdobích.");
            return List.of();
        }
        int steps = Math.max(idx1 - idx0, 1);
        double years = hint.startsWith("měsíc")
                ? Math.max(steps / 12.0, 1e-9)
                : hint.contains("roční") ? steps : Math.max(steps / 4.0, 1e-9);
        diag.put("years_used", years);
        diag.put("start", p0);
        diag.put("end", p1);
        return List.of(Map.of("period", p0 + "→" + p1, "value", cagr));
    }

    private static List<Map<String, Object>> cumChangeFirst(Map<String, Double> left, Map<String, Object> diag) {
        List<String> periods = TimeSeriesMath.sortedPeriods(left);
        if (periods.isEmpty()) {
            return List.of();
        }
        double base = left.get(periods.getFirst());
        Map<String, Double> deltas = TimeSeriesMath.cumulativeChangeFromFirst(left);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String p : periods) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", p);
            row.put("value", deltas.get(p));
            row.put("a", left.get(p));
            row.put("a_base", base);
            rows.add(row);
        }
        diag.put("observations_used", rows.size());
        diag.put("baseline_value", base);
        return rows;
    }

    private static List<Map<String, Object>> pctRankHist(Map<String, Double> left, Map<String, Object> diag) {
        Map<String, Double> ranks = TimeSeriesMath.percentileRankHistory(left);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String p : TimeSeriesMath.sortedPeriods(left)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", p);
            row.put("value", ranks.get(p));
            row.put("a", left.get(p));
            rows.add(row);
        }
        diag.put("observations_used", rows.size());
        return rows;
    }

    private static List<Map<String, Object>> trendLinear(
            Map<String, Double> left, List<String> warnings, Map<String, Object> diag) {
        List<String> periods = TimeSeriesMath.sortedPeriods(left);
        if (periods.size() < 3) {
            warnings.add("Lineární trend vyžaduje alespoň tři body.");
            return List.of();
        }
        TimeSeriesMath.TrendFit fit = TimeSeriesMath.linearTrend(left);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String p : periods) {
            double fitted = fit.fitted().get(p);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", p);
            row.put("value", fitted);
            row.put("a_actual", left.get(p));
            row.put("residual_ts", left.get(p) - fitted);
            rows.add(row);
        }
        diag.put("trend_fit", Map.of("intercept", fit.intercept(), "slope_per_step", fit.slopePerStep()));
        diag.put("observations_used", rows.size());
        warnings.add(
                "Trend je OLS přes pořadové indexy období (není kalendární čas v letech — pouze řazení řady).");
        return rows;
    }

    private static List<Map<String, Object>> corrPearson(
            Map<String, Double> left,
            Map<String, Double> right,
            List<String> warnings,
            Map<String, Object> diag) {
        List<String> common = PeriodAlignment.sortedCommonPeriods(left, right);
        if (common.size() < 3) {
            warnings.add("Korelace potřebuje alespoň tři společné body.");
            return List.of();
        }
        List<Double> xs = common.stream().map(right::get).toList();
        List<Double> ys = common.stream().map(left::get).toList();
        double r = TimeSeriesMath.pearson(xs, ys);
        diag.put("pearson_r", r);
        diag.put("observations_used", common.size());
        return List.of(Map.of("period", "Souhrn (korelace)", "value", r));
    }

    private static List<Map<String, Object>> regressOls(
            Map<String, Double> left,
            Map<String, Double> right,
            List<String> warnings,
            Map<String, Object> diag) {
        List<String> common = PeriodAlignment.sortedCommonPeriods(left, right);
        if (common.size() < 3) {
            warnings.add("Regrese potřebuje alespoň tři společné body.");
            return List.of();
        }
        List<Double> bs = common.stream().map(right::get).toList();
        List<Double> as = common.stream().map(left::get).toList();
        TimeSeriesMath.RegressionFit fit = TimeSeriesMath.olsSlopeIntercept(bs, as);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < common.size(); i++) {
            double fv = fit.intercept() + fit.slope() * bs.get(i);
            double res = as.get(i) - fv;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", common.get(i));
            row.put("value", fv);
            row.put("a", as.get(i));
            row.put("b", bs.get(i));
            row.put("residual", res);
            rows.add(row);
        }
        diag.put("regression", Map.of("intercept", fit.intercept(), "slope", fit.slope(), "r2", fit.r2(), "n", common.size()));
        diag.put("observations_used", rows.size());
        warnings.add(
                "Sloupec „Výsledek“ ukazuje fitted ŷ z OLS závislost A ~ B (B je regressor).");
        return rows;
    }

    private static List<Map<String, Object>> toRows(Map<String, Double> series) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Double> entry : series.entrySet()) {
            rows.add(Map.of("period", entry.getKey(), "value", entry.getValue()));
        }
        return rows;
    }

    private static List<Map<String, Object>> pctChangeFromSorted(
            List<String> periods, Map<String, Double> values, int lag, boolean pct) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = lag; i < periods.size(); i++) {
            String p = periods.get(i);
            double v = values.get(p);
            double v0 = values.get(periods.get(i - lag));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", p);
            row.put("a", v);
            row.put("a_prev", v0);
            row.put("delta_abs", v - v0);
            if (pct) {
                if (v0 == 0.0) {
                    continue;
                }
                double deltaPct = (v - v0) / Math.abs(v0) * 100.0;
                row.put("value", deltaPct);
                row.put("delta_pct", deltaPct);
            } else {
                row.put("value", v - v0);
                row.put("delta_pct", v0 != 0 ? (v - v0) / Math.abs(v0) * 100.0 : null);
            }
            rows.add(row);
        }
        return rows;
    }

    private static boolean hasRightOperand(Map<String, Object> right) {
        return !str(right.get("source_id")).isBlank() || !str(right.get("saved_series_id")).isBlank();
    }

    private static int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).strip());
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
