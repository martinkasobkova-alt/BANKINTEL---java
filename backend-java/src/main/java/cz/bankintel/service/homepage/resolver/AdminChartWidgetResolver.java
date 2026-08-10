package cz.bankintel.service.homepage.resolver;

import cz.bankintel.domain.entity.DatasetEntity;
import cz.bankintel.domain.entity.FormulaEntity;
import cz.bankintel.domain.entity.RecordEntity;
import cz.bankintel.domain.entity.SyncLogEntity;
import cz.bankintel.repository.DatasetRepository;
import cz.bankintel.repository.FormulaRepository;
import cz.bankintel.repository.RecordRepository;
import cz.bankintel.repository.SyncLogRepository;
import cz.bankintel.service.formula.FormulaService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminChartWidgetResolver {

    private final FormulaRepository formulaRepository;
    private final FormulaService formulaService;
    private final DatasetRepository datasetRepository;
    private final RecordRepository recordRepository;
    private final SyncLogRepository syncLogRepository;

    public Map<String, Object> resolve(String type, Map<String, Object> cfg) {
        return switch (type) {
            case "chart_net_result" -> chartNetResult(cfg);
            case "chart_dataset_distribution" -> chartDatasetDistribution();
            case "table_recent_syncs" -> tableRecentSyncs(cfg);
            case "dataset_table" -> datasetTable(cfg);
            case "dataset_chart" -> datasetChart(cfg);
            case "formula_chart" -> formulaChart(cfg);
            default -> Map.of("error", "Nepodporovaný typ widgetu: " + type);
        };
    }

    private Map<String, Object> chartNetResult(Map<String, Object> cfg) {
        String name = str(cfg.get("formula_name")).isBlank() ? "Net Result" : str(cfg.get("formula_name"));
        return formulaRepository
                .findByName(name)
                .map(f -> {
                    Map<String, Object> computed = formulaService.computeFormula(f);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rows = (List<Map<String, Object>>) computed.getOrDefault("rows", List.of());
                    List<Map<String, Object>> tail = rows.size() > 30 ? rows.subList(rows.size() - 30, rows.size()) : rows;
                    return Map.<String, Object>of("rows", tail, "total", computed.getOrDefault("total", 0.0));
                })
                .orElse(Map.of("rows", List.of(), "total", 0.0));
    }

    private Map<String, Object> chartDatasetDistribution() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DatasetEntity ds : datasetRepository.findAllByOrderByNameAsc()) {
            long count = recordRepository.countByDatasetId(ds.getId());
            out.add(Map.of("name", ds.getName(), "count", count));
        }
        return Map.of("rows", out);
    }

    private Map<String, Object> tableRecentSyncs(Map<String, Object> cfg) {
        int limit = parseLimit(cfg.get("limit"), 8);
        List<Map<String, Object>> rows = syncLogRepository.findAllByOrderByStartedAtDesc(PageRequest.of(0, limit)).stream()
                .map(this::syncLogRow)
                .toList();
        return Map.of("rows", rows);
    }

    private Map<String, Object> datasetTable(Map<String, Object> cfg) {
        String dsName = str(cfg.get("dataset_name"));
        int limit = parseLimit(cfg.get("limit"), 50);
        if (dsName.isBlank()) {
            return Map.of("rows", List.of(), "fields", List.of());
        }
        DatasetEntity ds = datasetRepository.findByName(dsName).orElse(null);
        if (ds == null) {
            return Map.of("rows", List.of(), "fields", List.of(), "error", "Dataset '" + dsName + "' nenalezen");
        }
        List<Map<String, Object>> rows = recordRepository
                .findByDatasetIdOrderByCreatedAtDesc(ds.getId(), PageRequest.of(0, limit))
                .stream()
                .map(RecordEntity::getData)
                .toList();
        List<String> fields = ds.getFields() != null ? ds.getFields() : List.of();
        return Map.of("rows", rows, "fields", fields, "dataset", dsName);
    }

    private Map<String, Object> datasetChart(Map<String, Object> cfg) {
        Map<String, Object> table = datasetTable(cfg);
        if (table.get("error") != null) {
            return table;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) table.getOrDefault("rows", List.of());
        String x = str(cfg.get("x_field")).isBlank() ? "date" : str(cfg.get("x_field"));
        String y = str(cfg.get("y_field")).isBlank() ? "amount" : str(cfg.get("y_field"));
        List<Map<String, Object>> chartRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object xv = row.get(x);
            Object yv = row.get(y);
            if (xv != null && yv != null) {
                chartRows.add(Map.of("x", String.valueOf(xv), "y", yv));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", chartRows);
        out.put("dataset", table.get("dataset"));
        out.put("x_field", x);
        out.put("y_field", y);
        out.put("agg", str(cfg.get("agg")).isBlank() ? "sum" : str(cfg.get("agg")));
        return out;
    }

    private Map<String, Object> formulaChart(Map<String, Object> cfg) {
        String fname = str(cfg.get("formula_name"));
        if (fname.isBlank()) {
            return Map.of("rows", List.of(), "total", 0.0);
        }
        FormulaEntity formula = formulaRepository.findByName(fname).orElse(null);
        if (formula == null) {
            return Map.of("rows", List.of(), "error", "Vzorec '" + fname + "' nenalezen");
        }
        return formulaService.computeFormula(formula);
    }

    private Map<String, Object> syncLogRow(SyncLogEntity log) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source_name", log.getSourceName());
        out.put("status", log.getStatus());
        out.put("started_at", log.getStartedAt() != null ? log.getStartedAt().toString() : null);
        out.put("message", log.getMessage());
        out.put("records_ingested", log.getRecordsIngested());
        return out;
    }

    private static int parseLimit(Object raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(raw).strip()));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).strip() : "";
    }
}
