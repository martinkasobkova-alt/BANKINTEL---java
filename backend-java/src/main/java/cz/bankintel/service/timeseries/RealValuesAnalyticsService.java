package cz.bankintel.service.timeseries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Reálné / deflátované metriky (spec section 5): when a CPI/HICP series is available alongside
 * a nominal target, computes real growth and deflated index paths. Never silently deflates
 * without an explicit inflation series — returns warnings instead.
 */
@Service
public class RealValuesAnalyticsService {

    public Map<String, Object> computeRealMetrics(
            Map<String, Double> nominalSeries,
            String nominalLabel,
            Map<String, Double> inflationSeries,
            String inflationLabel,
            SeriesCompatibilityGuard guard) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nominal_series", nominalLabel);
        out.put("inflation_series", inflationLabel);

        SeriesCompatibilityGuard.GuardrailResult compat =
                guard.checkCompatibility(
                        SeriesCompatibilityGuard.SeriesMetadata.of(null, null, null),
                        SeriesCompatibilityGuard.SeriesMetadata.of(null, null, null));
        // Use common period count guard
        List<String> common = cz.bankintel.service.calculations.PeriodAlignment.sortedCommonPeriods(nominalSeries, inflationSeries);
        SeriesCompatibilityGuard.GuardrailResult overlap = guard.checkCommonObservations(common.size());
        if ("not_reliable".equals(overlap.status())) {
            out.put("status", "not_computed");
            out.put("warnings", overlap.warnings());
            out.put("what_would_help", overlap.whatWouldHelp());
            return out;
        }

        Map<String, Double> nominalYoy = TimeSeriesMath.yoyPercent(nominalSeries);
        Map<String, Double> inflationYoy = TimeSeriesMath.yoyPercent(inflationSeries);
        List<String> yoyCommon = intersectPeriods(nominalYoy, inflationYoy);
        if (yoyCommon.isEmpty()) {
            out.put("status", "not_computed");
            out.put("warnings", List.of("insufficient_overlap_for_real_yoy: žádná společná YoY období"));
            return out;
        }
        String lastPeriod = yoyCommon.get(yoyCommon.size() - 1);
        Double nomYoy = nominalYoy.get(lastPeriod);
        Double infYoy = inflationYoy.get(lastPeriod);
        Double realYoy = nomYoy != null && infYoy != null ? exactRealGrowthPct(nomYoy, infYoy) : null;
        out.put("latest_period", lastPeriod);
        out.put("nominal_yoy_pct", nomYoy);
        out.put("inflation_yoy_pct", infYoy);
        out.put("real_yoy_pct", realYoy);
        out.put("real_yoy_formula", "(1 + nominal_yoy) / (1 + inflation_yoy) - 1");
        out.put("approx_real_yoy_pct", nomYoy != null && infYoy != null ? nomYoy - infYoy : null);
        out.put(
                "interpretation_note",
                "Realny rust je pocitan presnym slozenym vzorcem; nominalni YoY minus inflace YoY je pouze aproximace.");

        Map<String, Double> deflatedIndex = deflatedIndexPath(nominalSeries, inflationSeries);
        out.put("deflated_index_last", TimeSeriesMath.lastValue(deflatedIndex));
        out.put("deflated_index_series_tail", tail(deflatedIndex, 6));
        out.put("status", "ok");
        if (!compat.warnings().isEmpty()) {
            out.put("warnings", compat.warnings());
        }
        return out;
    }

    private static double exactRealGrowthPct(double nominalYoyPct, double inflationYoyPct) {
        return ((1.0 + nominalYoyPct / 100.0) / (1.0 + inflationYoyPct / 100.0) - 1.0) * 100.0;
    }

    private static Map<String, Double> deflatedIndexPath(Map<String, Double> nominal, Map<String, Double> inflation) {
        List<String> common = cz.bankintel.service.calculations.PeriodAlignment.sortedCommonPeriods(nominal, inflation);
        if (common.isEmpty()) {
            return Map.of();
        }
        String base = common.get(0);
        double baseNom = nominal.get(base);
        double baseInf = inflation.get(base);
        if (baseInf == 0.0) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (String p : common) {
            double infRatio = inflation.get(p) / baseInf;
            if (infRatio == 0.0) {
                continue;
            }
            out.put(p, nominal.get(p) / infRatio);
        }
        return out;
    }

    private static List<String> intersectPeriods(Map<String, Double> a, Map<String, Double> b) {
        return cz.bankintel.service.calculations.PeriodAlignment.sortedCommonPeriods(a, b);
    }

    private static Map<String, Double> tail(Map<String, Double> series, int n) {
        List<String> periods = TimeSeriesMath.sortedPeriods(series);
        Map<String, Double> out = new LinkedHashMap<>();
        int start = Math.max(0, periods.size() - n);
        for (int i = start; i < periods.size(); i++) {
            String p = periods.get(i);
            out.put(p, series.get(p));
        }
        return out;
    }
}
