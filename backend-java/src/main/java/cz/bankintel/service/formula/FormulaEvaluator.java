package cz.bankintel.service.formula;

import cz.bankintel.domain.entity.DatasetEntity;
import cz.bankintel.domain.entity.FormulaEntity;
import cz.bankintel.domain.entity.RecordEntity;
import cz.bankintel.repository.DatasetRepository;
import cz.bankintel.repository.RecordRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FormulaEvaluator {

    private static final Pattern REF_PATTERN = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\b");

    private final DatasetRepository datasetRepository;
    private final RecordRepository recordRepository;

    public Map<String, Object> compute(FormulaEntity formula) {
        String expression = formula.getExpression() != null ? formula.getExpression().strip() : "";
        List<String> groupBy = formula.getGroupBy() != null && !formula.getGroupBy().isEmpty()
                ? formula.getGroupBy()
                : List.of("date");
        List<String> warnings = new ArrayList<>();
        List<DatasetRef> refs = extractRefs(expression);
        if (refs.isEmpty()) {
            return Map.of("rows", List.of(), "total", 0.0, "warnings", List.of("expression has no dataset references"));
        }

        Map<String, DatasetEntity> datasetsByName = new LinkedHashMap<>();
        for (DatasetRef ref : refs) {
            if (datasetsByName.containsKey(ref.dataset())) {
                continue;
            }
            datasetRepository
                    .findByName(ref.dataset())
                    .ifPresentOrElse(
                            ds -> datasetsByName.put(ref.dataset(), ds),
                            () -> warnings.add("dataset '" + ref.dataset() + "' not found"));
        }

        Map<List<String>, Map<String, Double>> aggregated = new TreeMap<>(this::compareGroupKeys);
        for (DatasetRef ref : refs) {
            DatasetEntity dataset = datasetsByName.get(ref.dataset());
            if (dataset == null || dataset.getId() == null) {
                continue;
            }
            List<RecordEntity> records = recordRepository
                    .findByDatasetIdOrderByCreatedAtDesc(dataset.getId(), PageRequest.of(0, 5000))
                    .getContent();
            for (RecordEntity record : records) {
                Map<String, Object> data = record.getData() != null ? record.getData() : Map.of();
                List<String> key = new ArrayList<>();
                for (String gb : groupBy) {
                    key.add(str(data.get(gb)));
                }
                double num = parseNumber(data.get(ref.field()));
                aggregated.computeIfAbsent(key, k -> new LinkedHashMap<>()).merge(ref.key(), num, Double::sum);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        double total = 0.0;
        for (Map.Entry<List<String>, Map<String, Double>> entry : aggregated.entrySet()) {
            double result;
            try {
                result = evaluateExpression(expression, entry.getValue());
            } catch (Exception ex) {
                warnings.add("eval error for " + entry.getKey() + ": " + ex.getMessage());
                result = 0.0;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < groupBy.size(); i++) {
                row.put(groupBy.get(i), entry.getKey().get(i));
            }
            for (Map.Entry<String, Double> component : entry.getValue().entrySet()) {
                row.put(component.getKey(), round2(component.getValue()));
            }
            row.put("result", round2(result));
            rows.add(row);
            total += result;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", round2(total));
        out.put("group_by", groupBy);
        out.put("warnings", warnings);
        out.put("referenced_datasets", refs.stream().map(DatasetRef::dataset).distinct().sorted().toList());
        return out;
    }

    private double evaluateExpression(String expression, Map<String, Double> values) {
        String replaced = expression;
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            replaced = replaced.replace(entry.getKey(), Double.toString(entry.getValue()));
        }
        return SimpleExpressionEvaluator.evaluate(replaced);
    }

    private static List<DatasetRef> extractRefs(String expression) {
        List<DatasetRef> refs = new ArrayList<>();
        Matcher matcher = REF_PATTERN.matcher(expression);
        while (matcher.find()) {
            refs.add(new DatasetRef(matcher.group(1) + "." + matcher.group(2), matcher.group(1), matcher.group(2)));
        }
        return refs;
    }

    private int compareGroupKeys(List<String> a, List<String> b) {
        int len = Math.max(a.size(), b.size());
        for (int i = 0; i < len; i++) {
            String av = i < a.size() ? a.get(i) : "";
            String bv = i < b.size() ? b.get(i) : "";
            int cmp = av.compareToIgnoreCase(bv);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double parseNumber(Object value) {
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private record DatasetRef(String key, String dataset, String field) {}
}
