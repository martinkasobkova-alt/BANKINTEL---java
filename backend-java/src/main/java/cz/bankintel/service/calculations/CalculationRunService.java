package cz.bankintel.service.calculations;

import cz.bankintel.domain.entity.ComputedIndicatorEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CalculationRunService {

    private static final java.util.Set<String> BASIC_BINARY_OPS =
            java.util.Set.of("ratio", "sum", "diff", "mult", "pct");

    private final ComputedIndicatorRunner computedIndicatorRunner;
    private final SeriesOperandLoader operandLoader;

    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> payload, String userId) {
        String operation = str(payload.get("operation"));
        String mode = str(payload.get("mode")).isBlank() ? "compute" : str(payload.get("mode")).toLowerCase();
        Map<String, Object> left = map(payload.get("left"));
        Map<String, Object> right = map(payload.get("right"));
        List<Map<String, Object>> series = listOfMaps(payload.get("series"));

        ComputedIndicatorEntity doc = new ComputedIndicatorEntity();
        doc.setName("api-run");
        doc.setOperation(operation);
        doc.setLeft(left);
        doc.setRight(right);
        doc.setSeries(series);
        doc.setUnit(str(payload.get("unit")));
        doc.setOptions(map(payload.get("options")));

        if ("composite".equals(mode) || "multi".equals(operation)) {
            ComputedIndicatorRunner.RunResult multi = computedIndicatorRunner.run(doc, userId);
            Map<String, Object> meta = multi.diagnostics();
            return Map.of(
                    "result_series", multi.rows(),
                    "table", multi.rows(),
                    "metadata", meta,
                    "warnings", multi.warnings(),
                    "diagnostics", multi.diagnostics());
        }

        if (BASIC_BINARY_OPS.contains(operation)) {
            ComputedIndicatorRunner.RunResult result = computedIndicatorRunner.run(doc, userId);
            Map<String, Object> diagnostics = new LinkedHashMap<>(result.diagnostics());
            diagnostics.putIfAbsent("observations_used", result.rows().size());
            List<String> warnings = new ArrayList<>(result.warnings());
            warnings.addAll(pairWarnings(left, right, userId));
            return Map.of(
                    "result_series", result.rows(),
                    "table", result.rows(),
                    "metadata", Map.of("unit", str(payload.get("unit"))),
                    "warnings", warnings,
                    "diagnostics", diagnostics);
        }

        ComputedIndicatorRunner.RunResult fallback = computedIndicatorRunner.run(doc, userId);
        return Map.of(
                "result_series", fallback.rows(),
                "table", fallback.rows(),
                "metadata", Map.of("unit", str(payload.get("unit"))),
                "warnings", fallback.warnings(),
                "diagnostics", fallback.diagnostics());
    }

    private List<String> pairWarnings(Map<String, Object> left, Map<String, Object> right, String userId) {
        List<String> warnings = new ArrayList<>();
        Map<String, Double> leftMap = operandLoader.loadSeriesMap(left, userId);
        Map<String, Double> rightMap = operandLoader.loadSeriesMap(right, userId);
        if (leftMap.isEmpty()) {
            warnings.add("Levá řada nemá načtená data.");
        }
        if (rightMap.isEmpty()) {
            warnings.add("Pravá řada nemá načtená data.");
        }
        if (!leftMap.isEmpty() && !rightMap.isEmpty() && PeriodAlignment.sortedCommonPeriods(leftMap, rightMap).isEmpty()) {
            warnings.add("Levá a pravá řada nemají společná období.");
        }
        return warnings;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add(new LinkedHashMap<>((Map<String, Object>) m));
            }
        }
        return out;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
