package cz.bankintel.service.timeseries;

import cz.bankintel.service.calculations.PeriodAlignment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Cross-series comparisons: pairwise gap/spread/ratio/correlation/elasticity/beta plus
 * group-level benchmark-average/ranking/leader-laggard/dispersion. All numeric work is delegated
 * to {@link TimeSeriesMath}; this service only assembles the JSON-ready comparison shapes and
 * decides which periods are actually comparable (never silently comparing across a period a
 * series doesn't have data for).
 */
@Service
public class SeriesComparisonService {

    public record NamedSeries(String label, Map<String, Double> series) {}

    /** Full pairwise comparison of {@code a} against {@code b} — used for both direct
     * two-series comparisons and target-vs-named-benchmark comparisons. */
    public Map<String, Object> comparePair(String labelA, Map<String, Double> a, String labelB, Map<String, Double> b) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label_a", labelA);
        out.put("label_b", labelB);
        List<String> common = PeriodAlignment.sortedCommonPeriods(a, b);
        out.put("common_observations", common.size());
        if (common.isEmpty()) {
            out.put("warning", "Žádná společná období mezi „" + labelA + "“ a „" + labelB + "“ — porovnání nelze spočítat.");
            return out;
        }

        Map<String, Double> spread = new LinkedHashMap<>();
        Map<String, Double> ratio = new LinkedHashMap<>();
        Map<String, Double> gapPct = new LinkedHashMap<>();
        for (String p : common) {
            double av = a.get(p);
            double bv = b.get(p);
            spread.put(p, av - bv);
            if (bv != 0.0) {
                ratio.put(p, av / bv);
                gapPct.put(p, (av - bv) / Math.abs(bv) * 100.0);
            }
        }
        out.put("spread", spread);
        out.put("ratio", ratio);
        out.put("gap_pct", gapPct);

        String lastPeriod = common.get(common.size() - 1);
        out.put("latest_period", lastPeriod);
        out.put("latest_spread", spread.get(lastPeriod));
        out.put("latest_gap_pct", gapPct.get(lastPeriod));

        Map<String, Double> aCommon = restrictTo(a, common);
        Map<String, Double> bCommon = restrictTo(b, common);
        out.put("correlation", TimeSeriesMath.correlation(aCommon, bCommon));
        out.put("beta_vs_b", TimeSeriesMath.betaVsBenchmark(aCommon, bCommon));
        out.put("elasticity_a_vs_b", TimeSeriesMath.regressionElasticity(aCommon, bCommon));

        int[] lags = {0, 1, 3, 6, 9, 12, 18, 24};
        List<Map<String, Object>> laggedGrid = new ArrayList<>();
        for (TimeSeriesMath.LaggedCorrelation lc : TimeSeriesMath.laggedCorrelationGrid(aCommon, bCommon, lags)) {
            laggedGrid.add(laggedCorrelationRow(lc));
        }
        out.put("lagged_correlation_grid", laggedGrid);
        TimeSeriesMath.LaggedCorrelation best = TimeSeriesMath.bestLag(aCommon, bCommon, lags);
        out.put("best_lag", best != null ? laggedCorrelationRow(best) : null);

        // Cumulative divergence: running sum of the gap-% series — how much the two series have
        // drifted apart in total over the observed span, not just at the latest point.
        double cumulative = 0.0;
        for (String p : common) {
            Double g = gapPct.get(p);
            if (g != null) {
                cumulative += g;
            }
        }
        out.put("cumulative_divergence_pct", cumulative);
        return out;
    }

    /** Period-by-period average across a named group of series (e.g. "V4 average") — the group
     * membership itself is resolved by the caller/planner, not this service. */
    public Map<String, Double> groupAverage(List<NamedSeries> group) {
        Map<String, List<Double>> byPeriod = new LinkedHashMap<>();
        for (NamedSeries member : group) {
            for (Map.Entry<String, Double> entry : member.series().entrySet()) {
                byPeriod.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
            }
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> entry : byPeriod.entrySet()) {
            out.put(entry.getKey(), entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
        }
        return out;
    }

    /** Cross-sectional ranking of a group of series at their latest shared period, plus a
     * previous-period rank for a simple rank-change indicator (leader/laggard, rising/falling). */
    public Map<String, Object> rankGroup(List<NamedSeries> group) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (group.size() < 2) {
            out.put("warning", "Ranking vyžaduje alespoň dvě řady ve skupině.");
            return out;
        }
        List<String> common = null;
        for (NamedSeries member : group) {
            List<String> periods = TimeSeriesMath.sortedPeriods(member.series());
            common = common == null ? periods : intersectPreserveOrder(common, periods);
        }
        if (common == null || common.isEmpty()) {
            out.put("warning", "Skupina nemá žádné společné období pro ranking.");
            return out;
        }
        String latest = common.get(common.size() - 1);
        String previous = common.size() >= 2 ? common.get(common.size() - 2) : null;

        List<Map<String, Object>> ranked = new ArrayList<>();
        for (NamedSeries member : group) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", member.label());
            row.put("value", member.series().get(latest));
            row.put("previous_value", previous != null ? member.series().get(previous) : null);
            ranked.add(row);
        }
        ranked.sort(Comparator.comparingDouble(r -> -((Number) r.get("value")).doubleValue()));
        for (int i = 0; i < ranked.size(); i++) {
            ranked.get(i).put("rank", i + 1);
        }
        if (previous != null) {
            List<Map<String, Object>> prevRanked = new ArrayList<>(ranked);
            prevRanked.sort(Comparator.comparingDouble(r -> {
                Object v = r.get("previous_value");
                return v instanceof Number n ? -n.doubleValue() : Double.POSITIVE_INFINITY;
            }));
            Map<String, Integer> prevRank = new LinkedHashMap<>();
            for (int i = 0; i < prevRanked.size(); i++) {
                prevRank.put((String) prevRanked.get(i).get("label"), i + 1);
            }
            for (Map<String, Object> row : ranked) {
                Integer prevR = prevRank.get((String) row.get("label"));
                Integer curR = (Integer) row.get("rank");
                row.put("rank_change", prevR != null ? prevR - curR : null);
            }
        }

        out.put("period", latest);
        out.put("previous_period", previous);
        out.put("ranking", ranked);
        out.put("leader", ranked.get(0));
        out.put("laggard", ranked.get(ranked.size() - 1));

        List<Double> values = ranked.stream().map(r -> ((Number) r.get("value")).doubleValue()).toList();
        Map<String, Object> dispersion = new LinkedHashMap<>();
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double stdev = TimeSeriesMath.populationStdev(values);
        dispersion.put("mean", mean);
        dispersion.put("stdev", stdev);
        dispersion.put("coefficient_of_variation", mean != 0.0 ? stdev / Math.abs(mean) : null);
        dispersion.put("min", values.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN));
        dispersion.put("max", values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN));
        dispersion.put("range", values.stream().mapToDouble(Double::doubleValue).max().orElse(0)
                - values.stream().mapToDouble(Double::doubleValue).min().orElse(0));
        out.put("dispersion", dispersion);
        return out;
    }

    private static Map<String, Object> laggedCorrelationRow(TimeSeriesMath.LaggedCorrelation lc) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lag", lc.lag());
        row.put("r", lc.r());
        row.put("n", lc.n());
        return row;
    }

    private static Map<String, Double> restrictTo(Map<String, Double> series, List<String> periods) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String p : periods) {
            Double v = series.get(p);
            if (v != null) {
                out.put(p, v);
            }
        }
        return out;
    }

    private static List<String> intersectPreserveOrder(List<String> a, List<String> b) {
        java.util.Set<String> bSet = new java.util.HashSet<>(b);
        List<String> out = new ArrayList<>();
        for (String p : a) {
            if (bSet.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }
}
