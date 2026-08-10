package cz.bankintel.service.chartagent;

import cz.bankintel.service.timeseries.TimeSeriesMath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Chart chat-agent analytics (drawdown, Sharpe/Calmar, trend line, cross-series gap/correlation)
 * over the {@code {points:[{period,value}]}} chart-contract shape. The actual math (population
 * stdev, Pearson correlation, CAGR-style annualized growth) is delegated to the shared {@link
 * TimeSeriesMath} utility via {@link #valueMap} — this class only owns the chart-contract
 * parsing/shaping, not a second copy of the formulas.
 */
public final class ChartAnalyticsEngine {

    private static final Map<String, Double> PERIODS_PER_YEAR = Map.of(
            "D", 252.0, "B", 252.0, "W", 52.0, "M", 12.0, "Q", 4.0, "S", 2.0, "H", 2.0, "Y", 1.0, "A", 1.0);

    private ChartAnalyticsEngine() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> drawdownSeries(Map<String, Object> series) {
        double high = Double.NEGATIVE_INFINITY;
        String highPeriod = "";
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> maxDd = null;
        Object pointsObj = series.get("points");
        if (!(pointsObj instanceof List<?> points)) {
            return Map.of("rows", List.of(), "max_drawdown", Map.of());
        }
        for (Object ptObj : points) {
            if (!(ptObj instanceof Map<?, ?> rawPt)) {
                continue;
            }
            Map<String, Object> pt = (Map<String, Object>) rawPt;
            Double val = ChartContractParser.num(pt.get("value"));
            if (val == null) {
                continue;
            }
            if (val > high) {
                high = val;
                highPeriod = ChartContractParser.str(pt.get("period"));
            }
            if (high <= 0) {
                continue;
            }
            double dd = (val / high) - 1.0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", pt.get("period"));
            row.put("value", dd * 100.0);
            row.put("peak_period", highPeriod);
            row.put("trough_period", pt.get("period"));
            rows.add(row);
            if (maxDd == null || dd < (Double) maxDd.get("drawdown")) {
                maxDd = new LinkedHashMap<>();
                maxDd.put("drawdown", dd);
                maxDd.put("drawdown_pct", dd * 100.0);
                maxDd.put("peak_period", highPeriod);
                maxDd.put("trough_period", pt.get("period"));
                maxDd.put("peak_value", high);
                maxDd.put("trough_value", val);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("max_drawdown", maxDd != null ? maxDd : Map.of());
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> sharpeRatio(Map<String, Object> series, double riskFreeRateAnnual) {
        List<Map<String, Object>> returns = returns(series);
        if (returns.size() < 2) {
            return null;
        }
        List<Double> rets = returns.stream().map(r -> (Double) r.get("value")).toList();
        double mean = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double sigma = TimeSeriesMath.populationStdev(rets);
        if (sigma == 0.0) {
            return null;
        }
        double perYear = annualizationFromFrequency(ChartContractParser.str(series.get("frequency")));
        double annualReturn = mean * perYear;
        double annualVol = sigma * Math.sqrt(perYear);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", (annualReturn - riskFreeRateAnnual) / annualVol);
        out.put("annual_return", annualReturn);
        out.put("annual_volatility", annualVol);
        out.put("risk_free_rate_annual", riskFreeRateAnnual);
        out.put("observations_used", rets.size());
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> calmarRatio(Map<String, Object> series) {
        Map<String, Object> cagr = cagr(series);
        Map<String, Object> dd = drawdownSeries(series);
        Object maxObj = dd.get("max_drawdown");
        if (cagr == null || !(maxObj instanceof Map<?, ?> maxRaw)) {
            return null;
        }
        Map<String, Object> maxDd = (Map<String, Object>) maxRaw;
        Object drawdownObj = maxDd.get("drawdown");
        if (drawdownObj == null) {
            return null;
        }
        double maxDdAbs = Math.abs(((Number) drawdownObj).doubleValue());
        if (maxDdAbs == 0.0) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", ((Number) cagr.get("value")).doubleValue() / maxDdAbs);
        out.put("cagr", cagr);
        out.put("max_drawdown", maxDd);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> trendLine(Map<String, Object> series) {
        Object pointsObj = series.get("points");
        if (!(pointsObj instanceof List<?> points) || points.size() < 2) {
            return null;
        }
        List<Map<String, Object>> usable = new ArrayList<>();
        for (Object ptObj : points) {
            if (ptObj instanceof Map<?, ?> rawPt) {
                Map<String, Object> pt = (Map<String, Object>) rawPt;
                if (ChartContractParser.num(pt.get("value")) != null) {
                    usable.add(pt);
                }
            }
        }
        if (usable.size() < 2) {
            return null;
        }
        int n = usable.size();
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            xs.add((double) i);
            ys.add(ChartContractParser.num(usable.get(i).get("value")));
        }
        // x is always the distinct ordinal sequence 0..n-1 here, so the OLS denominator can never
        // be degenerate (n >= 2 already guaranteed above) — no den==0 guard needed.
        TimeSeriesMath.RegressionFit fit = TimeSeriesMath.olsSlopeIntercept(xs, ys);
        Map<String, Object> start = Map.of("x", usable.getFirst().get("period"), "y", fit.intercept());
        Map<String, Object> end = Map.of("x", usable.getLast().get("period"), "y", fit.intercept() + fit.slope() * (n - 1));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slope_per_step", fit.slope());
        out.put("intercept", fit.intercept());
        out.put("start", start);
        out.put("end", end);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> maxSeriesGap(Map<String, Object> seriesA, Map<String, Object> seriesB) {
        Map<String, Double> mapA = valueMap(seriesA);
        Map<String, Double> mapB = valueMap(seriesB);
        Set<String> common = new TreeSet<>(ChartPeriodKeys::compare);
        common.addAll(mapA.keySet());
        common.retainAll(mapB.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> best = null;
        for (String period : common) {
            double aVal = mapA.get(period);
            double bVal = mapB.get(period);
            double diff = aVal - bVal;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", period);
            row.put("series_a", seriesA.get("id"));
            row.put("series_b", seriesB.get("id"));
            row.put("label_a", seriesA.get("label"));
            row.put("label_b", seriesB.get("label"));
            row.put("value_a", aVal);
            row.put("value_b", bVal);
            row.put("difference", diff);
            row.put("abs_difference", Math.abs(diff));
            rows.add(row);
            if (best == null || Math.abs(diff) > ((Number) best.get("abs_difference")).doubleValue()) {
                best = row;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series_a", seriesA.get("id"));
        out.put("series_b", seriesB.get("id"));
        out.put("label_a", seriesA.get("label"));
        out.put("label_b", seriesB.get("label"));
        out.put("observations_used", common.size());
        out.put("rows", rows);
        if (best != null) {
            out.put("max_gap", best);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> correlationMatrix(List<Map<String, Object>> seriesList) {
        List<Map<String, Double>> maps = seriesList.stream().map(ChartAnalyticsEngine::valueMap).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < seriesList.size(); i++) {
            for (int j = i + 1; j < seriesList.size(); j++) {
                Set<String> common = new TreeSet<>(ChartPeriodKeys::compare);
                common.addAll(maps.get(i).keySet());
                common.retainAll(maps.get(j).keySet());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("a", seriesList.get(i).get("id"));
                row.put("b", seriesList.get(j).get("id"));
                row.put("label_a", seriesList.get(i).get("label"));
                row.put("label_b", seriesList.get(j).get("label"));
                row.put("n", common.size());
                if (common.size() < 3) {
                    row.put("r", null);
                } else {
                    List<Double> xs = common.stream().map(maps.get(i)::get).toList();
                    List<Double> ys = common.stream().map(maps.get(j)::get).toList();
                    double r = TimeSeriesMath.pearson(xs, ys);
                    row.put("r", Double.isNaN(r) ? null : r);
                }
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparingDouble(r -> {
            Object val = r.get("r");
            return val instanceof Number n ? -Math.abs(n.doubleValue()) : -1.0;
        }));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("relationships", rows);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildCubePreview(Map<String, Object> contract) {
        List<Map<String, Object>> factRows = new ArrayList<>();
        Object dataObj = contract.get("data");
        if (dataObj instanceof List<?> data) {
            for (Object ptObj : data) {
                if (!(ptObj instanceof Map<?, ?> rawPt)) {
                    continue;
                }
                Map<String, Object> pt = (Map<String, Object>) rawPt;
                Double value = ChartContractParser.num(pt.get("value_raw"));
                if (value == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("period", ChartContractParser.str(pt.get("period")));
                row.put("period_label", ChartContractParser.str(pt.get("period_label")).isBlank()
                        ? ChartContractParser.str(pt.get("period"))
                        : ChartContractParser.str(pt.get("period_label")));
                row.put("series_id", ChartContractParser.str(pt.get("series_id")));
                row.put("series_label", ChartContractParser.str(pt.get("series_label")));
                row.put("value", value);
                row.put("unit", ChartContractParser.str(pt.get("unit")));
                row.put("frequency", ChartContractParser.str(pt.get("frequency")));
                row.put("source", ChartContractParser.str(pt.get("source")));
                row.put("dataset", ChartContractParser.str(pt.get("dataset")));
                row.put("transformation", ChartContractParser.str(pt.get("transformation")).isBlank() ? "none" : ChartContractParser.str(pt.get("transformation")));
                row.put("geo", ChartContractParser.str(pt.get("geo")));
                row.put("geo_label", ChartContractParser.str(pt.get("geo_label")));
                factRows.add(row);
            }
        }
        Set<String> time = new TreeSet<>(ChartPeriodKeys::compare);
        Set<String> series = new TreeSet<>();
        Set<String> source = new TreeSet<>();
        Set<String> unit = new TreeSet<>();
        Set<String> frequency = new TreeSet<>();
        for (Map<String, Object> row : factRows) {
            String period = ChartContractParser.str(row.get("period"));
            if (!period.isBlank()) {
                time.add(period);
            }
            series.add(ChartContractParser.str(row.get("series_label")).isBlank()
                    ? ChartContractParser.str(row.get("series_id"))
                    : ChartContractParser.str(row.get("series_label")));
            String src = ChartContractParser.str(row.get("source"));
            if (!src.isBlank()) {
                source.add(src);
            }
            String u = ChartContractParser.str(row.get("unit"));
            if (!u.isBlank()) {
                unit.add(u);
            }
            String f = ChartContractParser.str(row.get("frequency"));
            if (!f.isBlank()) {
                frequency.add(f);
            }
        }
        Map<String, Object> dimensions = new LinkedHashMap<>();
        dimensions.put("time", new ArrayList<>(time));
        dimensions.put("series", new ArrayList<>(series));
        dimensions.put("source", new ArrayList<>(source));
        dimensions.put("unit", new ArrayList<>(unit));
        dimensions.put("frequency", new ArrayList<>(frequency));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fact_rows", factRows);
        out.put("dimensions", dimensions);
        out.put(
                "pivot_suggestions",
                List.of(
                        Map.of("rows", List.of("period"), "columns", List.of("series_label"), "values", "value", "aggregation", "last"),
                        Map.of("rows", List.of("source", "series_label"), "columns", List.of("period"), "values", "value", "aggregation", "avg")));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cagr(Map<String, Object> series) {
        Object pointsObj = series.get("points");
        if (!(pointsObj instanceof List<?> points) || points.size() < 2) {
            return null;
        }
        List<Map<String, Object>> usable = new ArrayList<>();
        for (Object ptObj : points) {
            if (ptObj instanceof Map<?, ?> rawPt) {
                Map<String, Object> pt = (Map<String, Object>) rawPt;
                if (ChartContractParser.num(pt.get("value")) != null) {
                    usable.add(pt);
                }
            }
        }
        if (usable.size() < 2) {
            return null;
        }
        double v0 = ChartContractParser.num(usable.getFirst().get("value"));
        double v1 = ChartContractParser.num(usable.getLast().get("value"));
        if (v0 <= 0 || v1 <= 0) {
            return null;
        }
        double perYear = annualizationFromFrequency(ChartContractParser.str(series.get("frequency")));
        double years = Math.max((usable.size() - 1) / perYear, 1e-9);
        double value = Math.pow(v1 / v0, 1.0 / years) - 1.0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", value);
        out.put("value_pct", value * 100.0);
        out.put("start_period", usable.getFirst().get("period"));
        out.put("end_period", usable.getLast().get("period"));
        out.put("years_used", years);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> returns(Map<String, Object> series) {
        Object pointsObj = series.get("points");
        if (!(pointsObj instanceof List<?> points)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> prevPt = null;
        Double prevVal = null;
        for (Object ptObj : points) {
            if (!(ptObj instanceof Map<?, ?> rawPt)) {
                continue;
            }
            Map<String, Object> pt = (Map<String, Object>) rawPt;
            Double cur = ChartContractParser.num(pt.get("value"));
            if (prevVal != null && cur != null && prevVal != 0.0) {
                out.add(Map.of("period", pt.get("period"), "value", (cur / prevVal) - 1.0));
            }
            prevPt = pt;
            prevVal = cur;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> valueMap(Map<String, Object> series) {
        Map<String, Double> out = new LinkedHashMap<>();
        Object pointsObj = series.get("points");
        if (!(pointsObj instanceof List<?> points)) {
            return out;
        }
        for (Object ptObj : points) {
            if (!(ptObj instanceof Map<?, ?> rawPt)) {
                continue;
            }
            Map<String, Object> pt = (Map<String, Object>) rawPt;
            Double val = ChartContractParser.num(pt.get("value"));
            if (val != null) {
                out.put(ChartContractParser.str(pt.get("period")), val);
            }
        }
        return out;
    }

    private static double annualizationFromFrequency(String freq) {
        return PERIODS_PER_YEAR.getOrDefault(freq.strip().toUpperCase(), 1.0);
    }
}
