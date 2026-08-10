package cz.bankintel.search;

import cz.bankintel.service.export.ExportService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CatalogDownloadService {

    private final CatalogPreviewOrchestrator previewOrchestrator;
    private final ExportService exportService;

    public ResponseEntity<byte[]> download(Map<String, Object> payload, cz.bankintel.domain.entity.UserEntity user) {
        exportService.requireExportAccess(user);
        String fmt = stringOrBlank(payload.get("format"));
        if (fmt.isBlank()) {
            fmt = "csv";
        }
        fmt = fmt.toLowerCase();
        if (!List.of("csv", "xlsx", "json").contains(fmt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format must be one of: csv, xlsx, json");
        }
        List<Map<String, Object>> records = previewOrchestrator.fetchRecords(payload);
        String sourceType = stringOrBlank(payload.get("source_type"));
        String base = stringOrBlank(payload.get("set_id")).replace("/", "-").replace(" ", "_");
        if (base.isBlank()) {
            base = "data";
        }
        String selected = stringOrBlank(payload.get("selected_indicator"));
        String filename = selected.isBlank()
                ? sourceType + "-" + base
                : sourceType + "-" + base + "-" + selected.replaceAll("[^\\w\\-.]+", "_").substring(0, Math.min(80, selected.length()));
        List<String> fields = collectFields(records);

        return switch (fmt) {
            case "json" -> jsonAttachment(filename, records);
            case "xlsx" -> xlsxAttachment(filename, fields, records);
            default -> csvAttachment(filename, fields, records);
        };
    }

    private ResponseEntity<byte[]> jsonAttachment(String filename, List<Map<String, Object>> records) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(toJsonObject(records.get(i)));
        }
        sb.append(']');
        return attachment(filename + ".json", sb.toString().getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON);
    }

    private ResponseEntity<byte[]> xlsxAttachment(String filename, List<String> fields, List<Map<String, Object>> records) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", filename);
        body.put("columns", fields);
        body.put("rows", records);
        byte[] data = exportService.exportWidgetXlsx(body);
        return attachment(
                filename + ".xlsx",
                data,
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    private ResponseEntity<byte[]> csvAttachment(String filename, List<String> fields, List<Map<String, Object>> records) {
        byte[] data = exportService.exportWidgetCsv(Map.of("columns", fields, "rows", records));
        return attachment(filename + ".csv", data, new MediaType("text", "csv", StandardCharsets.UTF_8));
    }

    private static List<String> collectFields(List<Map<String, Object>> records) {
        List<String> fields = new ArrayList<>();
        for (Map<String, Object> row : records) {
            for (String key : row.keySet()) {
                if (!fields.contains(key)) {
                    fields.add(key);
                }
            }
        }
        return fields;
    }

    private static String toJsonObject(Map<String, Object> row) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (i++ > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeJson(entry.getKey())).append("\":");
            Object val = entry.getValue();
            if (val == null) {
                sb.append("null");
            } else if (val instanceof Number || val instanceof Boolean) {
                sb.append(val);
            } else {
                sb.append('"').append(escapeJson(String.valueOf(val))).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static ResponseEntity<byte[]> attachment(String filename, byte[] data, MediaType mediaType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(data);
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
