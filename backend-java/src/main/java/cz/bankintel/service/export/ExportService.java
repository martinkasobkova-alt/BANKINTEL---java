package cz.bankintel.service.export;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.domain.entity.FormulaEntity;
import cz.bankintel.repository.DatasetRepository;
import cz.bankintel.repository.FormulaRepository;
import cz.bankintel.repository.RecordRepository;
import cz.bankintel.service.access.FeatureAccessService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ExportService {

    private final DatasetRepository datasetRepository;
    private final RecordRepository recordRepository;
    private final FormulaRepository formulaRepository;
    private final FeatureAccessService featureAccessService;

    public void requireExportAccess(UserEntity user) {
        featureAccessService.requireFeature(user, "export_data");
    }

    @Transactional(readOnly = true)
    public void ensureDatasetExists(String datasetId) {
        if (!datasetRepository.existsById(datasetId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset not found");
        }
    }

    @Transactional(readOnly = true)
    public void ensureFormulaExists(String formulaId) {
        if (!formulaRepository.existsById(formulaId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Formula not found");
        }
    }

    @Transactional(readOnly = true)
    public byte[] exportDatasetCsv(String datasetId, int limit) {
        var dataset = datasetRepository
                .findById(datasetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset not found"));
        List<Map<String, Object>> rows = recordRepository
                .findByDatasetIdOrderByCreatedAtDesc(datasetId, PageRequest.of(0, limit))
                .stream()
                .map(r -> r.getData() != null ? r.getData() : Map.<String, Object>of())
                .toList();
        List<String> columns = columnsFromRows(rows);
        if (columns.isEmpty() && dataset.getFields() != null) {
            columns = dataset.getFields();
        }
        return rowsToCsv(columns, rows).getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public byte[] exportDatasetXlsx(String datasetId, int limit) {
        var dataset = datasetRepository
                .findById(datasetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset not found"));
        List<Map<String, Object>> rows = loadDatasetRows(datasetId, limit);
        List<String> columns = columnsFromRows(rows);
        if (columns.isEmpty() && dataset.getFields() != null) {
            columns = dataset.getFields();
        }
        return ExportSpreadsheetWriter.rowsToXlsx(
                "Dataset: " + dataset.getName(),
                columns,
                rows,
                "Records: " + rows.size());
    }

    @Transactional(readOnly = true)
    public byte[] exportDatasetPdf(String datasetId, int limit) {
        var dataset = datasetRepository
                .findById(datasetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset not found"));
        List<Map<String, Object>> rows = loadDatasetRows(datasetId, limit);
        List<String> columns = columnsFromRows(rows);
        if (columns.isEmpty() && dataset.getFields() != null) {
            columns = dataset.getFields();
        }
        return ExportPdfWriter.rowsToPdf(
                "Dataset: " + dataset.getName(),
                columns,
                rows,
                "Showing up to " + limit + " records");
    }

    public byte[] exportWidgetXlsx(Map<String, Object> payload) {
        List<String> columns = stringList(payload.get("columns"));
        List<Map<String, Object>> rows = parseWidgetRows(payload, columns);
        if (columns.isEmpty()) {
            columns = columnsFromRows(rows);
        }
        String title = payload.get("title") != null ? String.valueOf(payload.get("title")) : "Export";
        String subtitle = payload.get("subtitle") != null ? String.valueOf(payload.get("subtitle")) : "";
        return ExportSpreadsheetWriter.rowsToXlsx(title, columns, rows, subtitle);
    }

    public byte[] exportWidgetPdf(Map<String, Object> payload) {
        List<String> columns = stringList(payload.get("columns"));
        List<Map<String, Object>> rows = parseWidgetRows(payload, columns);
        if (columns.isEmpty()) {
            columns = columnsFromRows(rows);
        }
        String title = payload.get("title") != null ? String.valueOf(payload.get("title")) : "Export";
        String subtitle = payload.get("subtitle") != null ? String.valueOf(payload.get("subtitle")) : "";
        byte[] chart = ExportPdfWriter.decodeChartPng(payload.get("chart_image_png"));
        return ExportPdfWriter.rowsToPdf(title, columns, rows, subtitle, chart);
    }

    public byte[] exportChartXlsx(Map<String, Object> payload) {
        Object sheetsObj = payload.get("sheets");
        if (!(sheetsObj instanceof Map<?, ?> sheets) || sheets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing sheets payload");
        }
        Map<String, Object> sheetMap = objectMap(sheets);
        String title = payload.get("title") != null ? String.valueOf(payload.get("title")) : "Chart export";
        return ExportSpreadsheetWriter.chartWorkbookToXlsx(title, sheetMap);
    }

    @Transactional(readOnly = true)
    public byte[] exportFormulaXlsx(FormulaEntity formula, Map<String, Object> computed) {
        List<Map<String, Object>> rows = objectRows(computed.get("rows"));
        List<String> columns = columnsFromRows(rows);
        return ExportSpreadsheetWriter.rowsToXlsx(
                "Formula: " + formula.getName(),
                columns,
                rows,
                formula.getExpression() + " — Total: " + computed.getOrDefault("total", 0));
    }

    @Transactional(readOnly = true)
    public byte[] exportFormulaPdf(FormulaEntity formula, Map<String, Object> computed) {
        List<Map<String, Object>> rows = objectRows(computed.get("rows"));
        List<String> columns = columnsFromRows(rows);
        return ExportPdfWriter.rowsToPdf(
                "Formula: " + formula.getName(),
                columns,
                rows,
                formula.getExpression() + " — Total: " + computed.getOrDefault("total", 0));
    }

    public byte[] exportWidgetCsv(Map<String, Object> payload) {
        List<String> columns = stringList(payload.get("columns"));
        List<?> rawRows = payload.get("rows") instanceof List<?> list ? list : List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object raw : rawRows) {
            if (raw instanceof Map<?, ?> map) {
                rows.add(objectMap(map));
            } else if (raw instanceof List<?> listRow) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                for (int i = 0; i < listRow.size(); i++) {
                    String key = i < columns.size() ? columns.get(i) : "col" + i;
                    row.put(key, listRow.get(i));
                }
                rows.add(row);
            }
        }
        if (columns.isEmpty()) {
            columns = columnsFromRows(rows);
        }
        return rowsToCsv(columns, rows).getBytes(StandardCharsets.UTF_8);
    }

    public String safeFilename(String name) {
        String cleaned = (name != null ? name.strip() : "").replaceAll("[^A-Za-z0-9_\\-.]+", "_");
        if (cleaned.isBlank()) {
            cleaned = "export";
        }
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private List<Map<String, Object>> loadDatasetRows(String datasetId, int limit) {
        return recordRepository
                .findByDatasetIdOrderByCreatedAtDesc(datasetId, PageRequest.of(0, limit))
                .stream()
                .map(r -> r.getData() != null ? r.getData() : Map.<String, Object>of())
                .toList();
    }

    private static List<Map<String, Object>> parseWidgetRows(Map<String, Object> payload, List<String> columns) {
        List<?> rawRows = payload.get("rows") instanceof List<?> list ? list : List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object raw : rawRows) {
            if (raw instanceof Map<?, ?> map) {
                rows.add(objectMap(map));
            } else if (raw instanceof List<?> listRow) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                for (int i = 0; i < listRow.size(); i++) {
                    String key = i < columns.size() ? columns.get(i) : "col" + i;
                    row.put(key, listRow.get(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static List<Map<String, Object>> objectRows(Object rawRows) {
        if (!(rawRows instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : list) {
            if (row instanceof Map<?, ?> map) {
                rows.add(objectMap(map));
            }
        }
        return rows;
    }

    private static Map<String, Object> objectMap(Map<?, ?> raw) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static List<String> columnsFromRows(List<Map<String, Object>> rows) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> columns = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            for (String key : row.keySet()) {
                if (seen.add(key)) {
                    columns.add(key);
                }
            }
        }
        return columns;
    }

    private static String rowsToCsv(List<String> columns, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", columns.stream().map(ExportService::csvEscape).toList())).append('\n');
        for (Map<String, Object> row : rows) {
            List<String> values = new ArrayList<>();
            for (String col : columns) {
                Object value = row.get(col);
                values.add(csvEscape(value != null ? String.valueOf(value) : ""));
            }
            sb.append(String.join(",", values)).append('\n');
        }
        return sb.toString();
    }

    private static String csvEscape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }
}
