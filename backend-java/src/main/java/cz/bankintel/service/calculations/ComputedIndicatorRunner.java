package cz.bankintel.service.calculations;

import cz.bankintel.domain.entity.ComputedIndicatorEntity;
import cz.bankintel.service.computed.ComputedOperations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ComputedIndicatorRunner {

    private static final Set<String> BASIC_BINARY_OPS = Set.of("ratio", "sum", "diff", "mult", "pct");

    private final SeriesOperandLoader operandLoader;
    private final ComputedAdvancedEngine advancedEngine;

    public RunResult run(ComputedIndicatorEntity doc, String userId) {
        String operation = doc.getOperation() != null ? doc.getOperation().strip() : "";
        if ("multi".equals(operation)) {
            return runMulti(doc, userId);
        }
        if (BASIC_BINARY_OPS.contains(operation)) {
            return runBinary(doc, userId, operation);
        }
        if (ComputedAdvancedEngine.EXTENDED_OPS.contains(operation)) {
            return advancedEngine.run(doc, userId);
        }
        List<String> warnings = List.of("Operace '" + operation + "' zatím není v Java backendu implementována.");
        return new RunResult(List.of(), warnings, Map.of("operation", operation));
    }

    private RunResult runBinary(ComputedIndicatorEntity doc, String userId, String operation) {
        Map<String, Object> left = doc.getLeft() != null ? doc.getLeft() : Map.of();
        Map<String, Object> right = doc.getRight() != null ? doc.getRight() : Map.of();
        Map<String, Double> leftMap = operandLoader.loadSeriesMap(left, userId);
        Map<String, Double> rightMap = operandLoader.loadSeriesMap(right, userId);
        SeriesCalculationEngine.CalculationResult result =
                SeriesCalculationEngine.computeBinaryScaled(leftMap, rightMap, operation, 1.0, 1.0);
        Map<String, Object> diagnostics = Map.of("observations_used", result.rows().size());
        return new RunResult(result.rows(), result.warnings(), diagnostics);
    }

    @SuppressWarnings("unchecked")
    private RunResult runMulti(ComputedIndicatorEntity doc, String userId) {
        List<Map<String, Object>> seriesRefs = doc.getSeries() != null ? doc.getSeries() : List.of();
        if (seriesRefs.isEmpty()) {
            return new RunResult(List.of(), List.of("Složený graf nemá definované řady."), Map.of());
        }
        List<Map<String, Object>> seriesMeta = new ArrayList<>();
        List<Map<String, Double>> maps = new ArrayList<>();
        for (int i = 0; i < seriesRefs.size(); i++) {
            Map<String, Object> ref = seriesRefs.get(i);
            String key = str(ref.get("key"));
            if (key.isBlank()) {
                key = "s" + (i + 1);
            }
            Map<String, Double> values = operandLoader.loadSeriesMap(ref, userId);
            maps.add(values);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("key", key);
            meta.put("source_id", ref.get("source_id"));
            meta.put("indicator_id", ref.get("indicator_id"));
            meta.put("name", firstNonBlank(str(ref.get("name")), operandLoader.operandDisplayName(ref)));
            meta.put("points", values.size());
            meta.put("chart_type", str(ref.get("chart_type")).isBlank() ? "line" : str(ref.get("chart_type")));
            seriesMeta.add(meta);
        }
        Set<String> periods = new TreeSet<>(PeriodAlignment::comparePeriods);
        for (Map<String, Double> map : maps) {
            periods.addAll(map.keySet());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String period : periods) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", period);
            boolean any = false;
            for (int i = 0; i < seriesMeta.size(); i++) {
                String key = str(seriesMeta.get(i).get("key"));
                Double value = maps.get(i).get(period);
                if (value != null) {
                    row.put(key, value);
                    any = true;
                }
            }
            if (any) {
                rows.add(row);
            }
        }
        Map<String, Object> diagnostics = Map.of("multi_series", true, "series", seriesMeta);
        return new RunResult(rows, List.of(), diagnostics);
    }

    public Map<String, Object> toRunResponse(ComputedIndicatorEntity doc, RunResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", doc.getId());
        out.put("name", doc.getName());
        out.put("operation", doc.getOperation());
        out.put("operation_label", ComputedOperations.labelFor(doc.getOperation()));
        out.put("unit", doc.getUnit() != null ? doc.getUnit() : "");
        out.put("left", withName(doc.getLeft()));
        out.put("right", withName(doc.getRight()));
        out.put("series", doc.getSeries() != null ? doc.getSeries() : List.of());
        out.put("rows", result.rows());
        out.put("result_series", result.rows());
        out.put("table", result.rows());
        out.put("warnings", result.warnings());
        out.put("diagnostics", result.diagnostics());
        if ("multi".equals(doc.getOperation())) {
            out.put("multi_series", true);
        }
        return out;
    }

    private Map<String, Object> withName(Map<String, Object> ref) {
        Map<String, Object> out = ref != null ? new LinkedHashMap<>(ref) : new LinkedHashMap<>();
        out.putIfAbsent("name", operandLoader.operandDisplayName(ref));
        return out;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    public record RunResult(List<Map<String, Object>> rows, List<String> warnings, Map<String, Object> diagnostics) {}
}
