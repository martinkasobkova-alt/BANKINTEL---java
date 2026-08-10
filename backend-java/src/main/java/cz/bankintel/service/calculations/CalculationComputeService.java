package cz.bankintel.service.calculations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CalculationComputeService {

    private final SeriesOperandLoader operandLoader;

    @SuppressWarnings("unchecked")
    public Map<String, Object> compute(Map<String, Object> body, String userId) {
        String operation = str(body.get("operation"));
        List<Map<String, Object>> operands = listOfMaps(body.get("operands"));
        if (operands.isEmpty()) {
            throw new IllegalArgumentException("Chybí `operands`.");
        }
        String basePeriod = str(body.get("base_period"));
        if (basePeriod.isBlank()) {
            basePeriod = null;
        }

        List<String> opLabels = new ArrayList<>();
        for (Map<String, Object> operand : operands) {
            opLabels.add(operandLoader.operandDisplayName(operand));
        }

        List<String> scaleNotes = new ArrayList<>();
        if (operands.size() >= 2) {
            Map<String, Object> suggestion = ScaleSuggestionService.suggestScaleFactors(
                    toDouble(operands.get(0).get("declared_scale")),
                    toDouble(operands.get(1).get("declared_scale")));
            String note = str(suggestion.get("note_cs"));
            if (!note.isBlank()) {
                scaleNotes.add("(Automatický návrh škály, neaplikuji se bez ručního násobiče:) " + note);
            }
        }

        SeriesCalculationEngine.CalculationResult result;
        String note;
        if (SeriesCalculationEngine.BINARY_OPS.contains(operation)) {
            if (operands.size() != 2) {
                throw new IllegalArgumentException("Binární operace vyžaduje přesně 2 řady v `operands`.");
            }
            Map<String, Double> left = operandLoader.loadSeriesMap(operands.get(0), userId);
            Map<String, Double> right = operandLoader.loadSeriesMap(operands.get(1), userId);
            double ml = multiplier(operands.get(0));
            double mr = multiplier(operands.get(1));
            result = SeriesCalculationEngine.computeBinaryScaled(left, right, operation, ml, mr);
            String periodRange = PeriodAlignment.periodSpanNote(result.rows().stream().map(r -> str(r.get("period"))).toList());
            note = SeriesCalculationEngine.buildMethodNote(
                    operation,
                    opLabels,
                    concat(
                            List.of("Ruční násobiče: A×" + ml + ", B×" + mr + "."),
                            scaleNotes),
                    periodRange,
                    "Výpočet: převod vstupů násobiči, zarovnání na průnik období, pak "
                            + SeriesCalculationEngine.operationLabel(operation)
                            + ".",
                    List.of());
        } else if ("multi_sum".equals(operation)) {
            if (operands.size() < 2) {
                throw new IllegalArgumentException("multi_sum vyžaduje alespoň 2 řady.");
            }
            List<Map<String, Double>> maps = new ArrayList<>();
            List<Double> mults = new ArrayList<>();
            for (Map<String, Object> operand : operands) {
                maps.add(operandLoader.loadSeriesMap(operand, userId));
                mults.add(multiplier(operand));
            }
            result = SeriesCalculationEngine.computeMultiSum(maps, mults);
            StringBuilder formula = new StringBuilder("Výpočet součtu: ");
            for (int i = 0; i < mults.size(); i++) {
                if (i > 0) {
                    formula.append('+');
                }
                formula.append("(řada").append(i + 1).append("×").append(mults.get(i)).append(')');
            }
            formula.append(", zarovnání na průnik období.");
            note = SeriesCalculationEngine.buildMethodNote(
                    operation,
                    opLabels,
                    scaleNotes,
                    PeriodAlignment.periodSpanNote(result.rows().stream().map(r -> str(r.get("period"))).toList()),
                    formula.toString(),
                    List.of());
        } else if ("index_100".equals(operation)) {
            if (operands.size() != 1) {
                throw new IllegalArgumentException("index_100 vyžaduje právě jednu řadu.");
            }
            if (basePeriod == null) {
                throw new IllegalArgumentException("Pro index_100 zadejte `base_period` (např. \"2020Q1\").");
            }
            Map<String, Double> series = operandLoader.loadSeriesMap(operands.getFirst(), userId);
            double ml = multiplier(operands.getFirst());
            result = SeriesCalculationEngine.computeIndex100(series, basePeriod, ml);
            note = SeriesCalculationEngine.buildMethodNote(
                    operation,
                    opLabels,
                    concat(List.of("Ruční násobič vstupu: ×" + ml + "."), scaleNotes),
                    PeriodAlignment.periodSpanNote(result.rows().stream().map(r -> str(r.get("period"))).toList()),
                    "Index % = 100 × (hodnota / hodnota v " + basePeriod + ") po použití násobiče.",
                    List.of());
        } else if ("yoy_pct".equals(operation)) {
            if (operands.size() != 1) {
                throw new IllegalArgumentException("yoy_pct vyžaduje právě jednu řadu.");
            }
            Map<String, Double> series = operandLoader.loadSeriesMap(operands.getFirst(), userId);
            double ml = multiplier(operands.getFirst());
            result = SeriesCalculationEngine.computeYoyPercent(series, ml);
            note = SeriesCalculationEngine.buildMethodNote(
                    operation,
                    opLabels,
                    concat(List.of("Ruční násobič vstupu: ×" + ml + "."), scaleNotes),
                    PeriodAlignment.periodSpanNote(result.rows().stream().map(r -> str(r.get("period"))).toList()),
                    "YoY % = 100 × (Vt − Vt-1rok) / |Vt-1rok| při dostupnosti páru období.",
                    List.of());
        } else {
            throw new IllegalArgumentException("Neznámá operace: " + operation);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("operation", operation);
        metadata.put("operation_label", SeriesCalculationEngine.operationLabel(operation));
        metadata.put("input_series_labels", opLabels);
        metadata.put("chart_recommendation", "line");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result_series", result.rows());
        out.put("metadata", metadata);
        out.put("warnings", result.warnings());
        out.put("method_note", note);
        out.put(
                "formula_machine_readable",
                Map.of("op", operation, "operands_meta", operands, "base_period", basePeriod != null ? basePeriod : ""));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return out;
    }

    private static double multiplier(Map<String, Object> operand) {
        Object value = operand.get("manual_multiplier");
        if (value == null) {
            return 1.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 1.0;
        }
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> out = new ArrayList<>(first);
        out.addAll(second);
        return out;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
