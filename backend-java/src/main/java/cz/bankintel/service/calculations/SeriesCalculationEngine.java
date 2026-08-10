package cz.bankintel.service.calculations;

import cz.bankintel.service.computed.ComputedOperations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SeriesCalculationEngine {

    public static final Set<String> BINARY_OPS = Set.of("ratio", "sum", "diff", "mult", "pct");
    public static final Set<String> UNARY_OPS = Set.of("index_100", "yoy_pct", "multi_sum");

    private SeriesCalculationEngine() {}

    public static Double applyBinaryOp(String op, double a, double b) {
        return switch (op) {
            case "sum" -> a + b;
            case "diff" -> a - b;
            case "mult" -> a * b;
            case "ratio" -> b == 0.0 ? null : a / b;
            case "pct" -> b == 0.0 ? null : (a / b) * 100.0;
            default -> null;
        };
    }

    public static CalculationResult computeBinaryScaled(
            Map<String, Double> left, Map<String, Double> right, String op, double leftMultiplier, double rightMultiplier) {
        List<String> warnings = new ArrayList<>();
        List<String> common = PeriodAlignment.sortedCommonPeriods(left, right);
        if (common.isEmpty()) {
            return new CalculationResult(List.of(), List.of("Žádné společné období mezi řadami po zarovnání."));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String period : common) {
            Double leftVal = left.get(period);
            Double rightVal = right.get(period);
            if (leftVal == null || rightVal == null) {
                warnings.add("Období " + period + ": nečíselná hodnota, přeskakuji.");
                continue;
            }
            double av = leftVal * leftMultiplier;
            double bv = rightVal * rightMultiplier;
            Double out = applyBinaryOp(op, av, bv);
            if (out == null) {
                if (("ratio".equals(op) || "pct".equals(op)) && bv == 0.0) {
                    warnings.add("Období " + period + ": dělení nulou (B × násobič=" + bv + ").");
                }
                continue;
            }
            rows.add(Map.of("period", period, "value", out));
        }
        return new CalculationResult(rows, warnings);
    }

    public static CalculationResult computeMultiSum(List<Map<String, Double>> maps, List<Double> multipliers) {
        List<String> warnings = new ArrayList<>();
        if (maps.size() < 2) {
            return new CalculationResult(List.of(), List.of("multi_sum vyžaduje alespoň dvě řady."));
        }
        Set<String> inter = null;
        for (Map<String, Double> map : maps) {
            if (inter == null) {
                inter = new java.util.LinkedHashSet<>(map.keySet());
            } else {
                inter.retainAll(map.keySet());
            }
        }
        if (inter == null || inter.isEmpty()) {
            return new CalculationResult(List.of(), List.of("Žádné společné období pro součet více řad."));
        }
        List<String> periods = new ArrayList<>(inter);
        periods.sort(PeriodAlignment::comparePeriods);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String period : periods) {
            double total = 0.0;
            boolean ok = true;
            for (int i = 0; i < maps.size(); i++) {
                Double raw = maps.get(i).get(period);
                if (raw == null) {
                    ok = false;
                    break;
                }
                total += raw * multipliers.get(i);
            }
            if (ok) {
                rows.add(Map.of("period", period, "value", total));
            }
        }
        return new CalculationResult(rows, warnings);
    }

    public static CalculationResult computeIndex100(Map<String, Double> series, String basePeriod, double multiplier) {
        List<String> warnings = new ArrayList<>();
        Map<String, Double> scaled = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : series.entrySet()) {
            if (entry.getValue() != null && Double.isFinite(entry.getValue())) {
                scaled.put(entry.getKey(), entry.getValue() * multiplier);
            }
        }
        String bp = PeriodAlignment.resolvePeriodAlias(basePeriod, scaled.keySet());
        if (bp == null) {
            return new CalculationResult(
                    List.of(), List.of("Základní období \"" + basePeriod + "\" se nenašlo mezi dostupnými obdobími."));
        }
        Double baseValue = scaled.get(bp);
        if (baseValue == null || baseValue == 0.0) {
            return new CalculationResult(
                    List.of(), List.of("Hodnota v základním období " + bp + " je nulová nebo chybí — nelze indexovat."));
        }
        List<String> periods = new ArrayList<>(scaled.keySet());
        periods.sort(PeriodAlignment::comparePeriods);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String period : periods) {
            rows.add(Map.of("period", period, "value", 100.0 * scaled.get(period) / baseValue));
        }
        return new CalculationResult(rows, warnings);
    }

    public static CalculationResult computeYoyPercent(Map<String, Double> series, double multiplier) {
        List<String> warnings = new ArrayList<>();
        Map<String, Double> scaled = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : series.entrySet()) {
            if (entry.getValue() != null && Double.isFinite(entry.getValue())) {
                scaled.put(entry.getKey(), entry.getValue() * multiplier);
            }
        }
        Set<String> candidates = scaled.keySet();
        List<String> periods = new ArrayList<>(candidates);
        periods.sort(PeriodAlignment::comparePeriods);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String period : periods) {
            String prev = PeriodAlignment.inferYoyPeriodKey(period, candidates);
            if (prev == null || !scaled.containsKey(prev)) {
                continue;
            }
            double v0 = scaled.get(prev);
            double v1 = scaled.get(period);
            if (v0 == 0.0) {
                warnings.add("Období " + period + ": YoY — předchozí období " + prev + " má nulovou hodnotu, přeskakuji.");
                continue;
            }
            rows.add(Map.of("period", period, "value", (v1 - v0) / Math.abs(v0) * 100.0));
        }
        if (rows.isEmpty() && !scaled.isEmpty()) {
            warnings.add(
                    "YoY nelze dopočítat: chybí spárovaná období rok/dopředu (podporována hlavně periodicita čtvrtletí typu YYYYQx).");
        }
        return new CalculationResult(rows, warnings);
    }

    public static String operationLabel(String operation) {
        String label = ComputedOperations.labelFor(operation);
        if (!label.equals(operation)) {
            return label;
        }
        return switch (operation) {
            case "multi_sum" -> "součet více řad (vážený násobiči)";
            case "index_100" -> "indexace = 100 v základním období";
            case "yoy_pct" -> "meziroční změna (YoY %) — čtvrtletní párování";
            default -> operation;
        };
    }

    public static String buildMethodNote(
            String operation,
            List<String> operandLabels,
            List<String> scaleNotes,
            String periodRange,
            String formula,
            List<String> extraParts) {
        List<String> parts = new ArrayList<>();
        parts.add(formula);
        parts.addAll(scaleNotes);
        if (periodRange != null && !periodRange.isBlank()) {
            parts.add("Časové omezení: " + periodRange + ".");
        }
        String labels = operandLabels.isEmpty() ? "(bez názvů řad)" : String.join(", ", operandLabels);
        parts.add("Operace výsledku: " + operationLabel(operation) + " (" + operation + "). vstupy: " + labels + ".");
        if (extraParts != null) {
            parts.addAll(extraParts);
        }
        return String.join(" ", parts);
    }

    public record CalculationResult(List<Map<String, Object>> rows, List<String> warnings) {}
}
