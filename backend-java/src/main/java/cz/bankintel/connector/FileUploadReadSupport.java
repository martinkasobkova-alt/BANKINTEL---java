package cz.bankintel.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.service.userdata.UserDataParseService;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Čtení nahraných souborů pro {@link FileUploadConnector} — obdoba Python
 * {@code connectors/file_upload.py}.
 */
final class FileUploadReadSupport {

    private static final DataFormatter FORMATTER = new DataFormatter();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FileUploadReadSupport() {}

    static List<Map<String, Object>> readRows(byte[] raw, Path path, Map<String, Object> params) throws IOException {
        String kind = detectKind(path, params);
        return switch (kind) {
            case "xlsx", "xlsm" -> readXlsx(raw, params);
            case "csv" -> UserDataParseService.readTabularRows(raw, ".csv");
            case "pdf" -> readPdf(raw, params);
            case "json", "olap" -> readJsonOrOlap(raw);
            default -> throw new IOException("unsupported file kind: " + kind);
        };
    }

    static String detectKind(Path path, Map<String, Object> params) {
        String configured = string(params.get("kind")).toLowerCase(Locale.ROOT);
        if (!configured.isBlank()) {
            return configured;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xlsm")) {
            return "xlsx";
        }
        if (name.endsWith(".pdf")) {
            return "pdf";
        }
        if (name.endsWith(".csv")) {
            return "csv";
        }
        if (name.endsWith(".json")) {
            return "json";
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "xlsx";
    }

    static List<Map<String, Object>> readXlsx(byte[] raw, Map<String, Object> params) throws IOException {
        String manual = string(params.get("excel_records_json"));
        if (!manual.isBlank()) {
            return parseManualJsonRows(manual);
        }
        String sheetName = string(params.get("sheet"));
        int headerRow = parseInt(params.get("header_row"), 1);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(raw))) {
            Sheet sheet = pickSheet(workbook, sheetName);
            if (sheet == null) {
                return rows;
            }
            int headerIdx = Math.max(1, headerRow) - 1;
            Row header = sheet.getRow(headerIdx);
            if (header == null) {
                return rows;
            }
            List<String> headers = new ArrayList<>();
            for (Cell cell : header) {
                headers.add(cellToStr(FORMATTER.formatCellValue(cell), "col_" + (headers.size() + 1)));
            }
            for (int r = headerIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, Object> rec = new LinkedHashMap<>();
                boolean any = false;
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.getCell(c);
                    Object val = coerceValue(cell == null ? null : FORMATTER.formatCellValue(cell));
                    if (val != null && !String.valueOf(val).isBlank()) {
                        any = true;
                    }
                    rec.put(headers.get(c), val);
                }
                if (any) {
                    rows.add(rec);
                }
            }
        }
        return rows;
    }

    private static List<Map<String, Object>> readPdf(byte[] raw, Map<String, Object> params) throws IOException {
        String manual = string(params.get("pdf_manual_text"));
        if (!manual.isBlank()) {
            return parseDelimitedText(manual, params);
        }
        try (PDDocument doc = PDDocument.load(raw)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String pages = string(params.get("pdf_pages"));
            if (!pages.isBlank()) {
                List<Integer> pageNums = parsePageSpec(pages, doc.getNumberOfPages());
                if (!pageNums.isEmpty()) {
                    stripper.setStartPage(pageNums.getFirst() + 1);
                    stripper.setEndPage(pageNums.getLast() + 1);
                }
            }
            return parseDelimitedText(stripper.getText(doc), params);
        }
    }

    private static List<Map<String, Object>> readJsonOrOlap(byte[] raw) throws IOException {
        Object payload = MAPPER.readValue(raw, new TypeReference<>() {});
        if (looksLikeOlapCube(payload)) {
            return readOlapCube(castMap(payload));
        }
        if (payload instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> coerceRow(castMap(item)))
                    .toList();
        }
        if (payload instanceof Map<?, ?> map) {
            Map<String, Object> root = castMap(map);
            for (String key : List.of("data", "records", "items", "rows")) {
                Object rows = root.get(key);
                if (rows instanceof List<?> list) {
                    return list.stream()
                            .filter(Map.class::isInstance)
                            .map(item -> coerceRow(castMap(item)))
                            .toList();
                }
            }
        }
        return List.of();
    }

    private static List<Map<String, Object>> parseManualJsonRows(String manual) throws IOException {
        Object data = MAPPER.readValue(manual, new TypeReference<>() {});
        if (!(data instanceof List<?> list)) {
            throw new IOException("excel_records_json: očekáváno JSON pole");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                rows.add(coerceRow(castMap(map)));
            }
        }
        return rows;
    }

    private static List<Map<String, Object>> readOlapCube(Map<String, Object> payload) {
        Object tablesObj = payload.get("tables");
        if (!(tablesObj instanceof Map<?, ?> tablesRaw)) {
            return List.of();
        }
        Map<String, Object> tables = castMap(tablesRaw);
        Object factsObj = tables.get("fact_values");
        if (!(factsObj instanceof List<?> facts)) {
            return List.of();
        }
        Map<String, Map<String, Object>> dimPeriod = indexRows(tables.get("dim_period"), "period_id");
        Map<String, Map<String, Object>> dimSeries = indexRows(tables.get("dim_series"), "series_id");
        Map<String, Map<String, Object>> dimGeo = indexRows(tables.get("dim_geo"), "geo_id");
        Map<String, Map<String, Object>> dimSource = indexRows(tables.get("dim_source"), "source_id");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object factObj : facts) {
            if (!(factObj instanceof Map<?, ?> factRaw)) {
                continue;
            }
            Map<String, Object> fact = castMap(factRaw);
            String periodId = string(fact.get("period_id"));
            String seriesId = string(fact.get("series_id"));
            String geoId = string(fact.get("geo_id"));
            String sourceId = string(fact.get("source_id"));
            Map<String, Object> p = dimPeriod.getOrDefault(periodId, Map.of());
            Map<String, Object> s = dimSeries.getOrDefault(seriesId, Map.of());
            Map<String, Object> g = dimGeo.getOrDefault(geoId, Map.of());
            Map<String, Object> src = dimSource.getOrDefault(sourceId, Map.of());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", periodId);
            row.put("period_label", p.getOrDefault("period_label", periodId));
            row.put("year", p.get("year"));
            row.put("quarter", p.get("quarter"));
            row.put("month", p.get("month"));
            row.put("series_id", seriesId);
            row.put("series_label", s.getOrDefault("series_label", seriesId));
            row.put("geo", g.getOrDefault("geo_code", geoId));
            row.put("geo_label", g.getOrDefault("geo_label", geoId));
            row.put("source", src.getOrDefault("source", sourceId));
            row.put("dataset", string(s.get("dataset")).isBlank() ? string(src.get("dataset")) : string(s.get("dataset")));
            row.put("value", coerceValue(fact.get("value")));
            row.put("unit", string(fact.get("unit")).isBlank() ? string(s.get("unit")) : string(fact.get("unit")));
            row.put("frequency", string(fact.get("frequency")));
            row.put("transformation", string(fact.get("transformation")));
            out.add(row);
        }
        return out;
    }

    private static Map<String, Map<String, Object>> indexRows(Object rowsObj, String keyField) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (!(rowsObj instanceof List<?> rows)) {
            return out;
        }
        for (Object rowObj : rows) {
            if (!(rowObj instanceof Map<?, ?> rowRaw)) {
                continue;
            }
            Map<String, Object> row = castMap(rowRaw);
            String key = string(row.get(keyField));
            if (!key.isBlank() && !out.containsKey(key)) {
                out.put(key, row);
            }
        }
        return out;
    }

    private static boolean looksLikeOlapCube(Object payload) {
        if (!(payload instanceof Map<?, ?> mapRaw)) {
            return false;
        }
        Map<String, Object> map = castMap(mapRaw);
        Object tablesObj = map.get("tables");
        if (!(tablesObj instanceof Map<?, ?> tablesRaw)) {
            return false;
        }
        Object facts = castMap(tablesRaw).get("fact_values");
        return facts instanceof List<?>;
    }

    private static List<Map<String, Object>> parseDelimitedText(String text, Map<String, Object> params) throws IOException {
        String mode = string(params.get("pdf_text_split")).isBlank() ? "multi_space" : string(params.get("pdf_text_split"));
        String delimiter = switch (mode) {
            case "tab" -> "\t";
            case "custom" -> string(params.get("pdf_custom_delimiter"));
            default -> "\\s{2,}";
        };
        List<Map<String, Object>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))) {
            List<String> lines = reader.lines().filter(line -> !line.isBlank()).toList();
            if (lines.isEmpty()) {
                return rows;
            }
            List<String> headers = splitLine(lines.getFirst(), delimiter, mode);
            if (headers.size() < 2) {
                return rows;
            }
            for (int i = 1; i < lines.size(); i++) {
                List<String> cells = splitLine(lines.get(i), delimiter, mode);
                if (cells.isEmpty()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    row.put(headers.get(c), c < cells.size() ? coerceValue(cells.get(c)) : null);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static List<String> splitLine(String line, String delimiter, String mode) {
        if ("multi_space".equals(mode)) {
            String[] parts = line.trim().split("\\s{2,}");
            List<String> out = new ArrayList<>();
            for (String part : parts) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
            return out;
        }
        String[] parts = line.split(delimiter, -1);
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            out.add(part.trim());
        }
        return out;
    }

    private static Sheet pickSheet(Workbook workbook, String sheetName) {
        if (!sheetName.isBlank()) {
            Sheet named = workbook.getSheet(sheetName);
            if (named != null) {
                return named;
            }
        }
        return workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
    }

    private static List<Integer> parsePageSpec(String spec, int pageCount) {
        List<Integer> out = new ArrayList<>();
        if (spec.isBlank()) {
            for (int i = 0; i < pageCount; i++) {
                out.add(i);
            }
            return out;
        }
        for (String part : spec.replace(";", ",").split(",")) {
            String chunk = part.trim();
            if (chunk.isEmpty()) {
                continue;
            }
            if (chunk.contains("-")) {
                String[] bounds = chunk.split("-", 2);
                int a = Integer.parseInt(bounds[0].trim());
                int b = Integer.parseInt(bounds[1].trim());
                if (a > b) {
                    int tmp = a;
                    a = b;
                    b = tmp;
                }
                for (int n = a; n <= b; n++) {
                    if (n >= 1 && n <= pageCount) {
                        out.add(n - 1);
                    }
                }
            } else if (chunk.chars().allMatch(Character::isDigit)) {
                int n = Integer.parseInt(chunk);
                if (n >= 1 && n <= pageCount) {
                    out.add(n - 1);
                }
            }
        }
        return out.stream().distinct().sorted().toList();
    }

    private static Map<String, Object> coerceRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            out.put(String.valueOf(entry.getKey()), coerceValue(entry.getValue()));
        }
        return out;
    }

    private static Object coerceValue(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Number || val instanceof Boolean) {
            return val;
        }
        String s = String.valueOf(val).trim();
        if (s.isEmpty()) {
            return null;
        }
        String cleaned = s.replace("\u00a0", "").replace(" ", "").replace(",", ".");
        if (cleaned.chars().filter(ch -> ch == '.').count() <= 1
                && cleaned.replace("-", "").replace(".", "").chars().allMatch(Character::isDigit)) {
            try {
                return cleaned.contains(".") ? Double.parseDouble(cleaned) : Long.parseLong(cleaned);
            } catch (NumberFormatException ignored) {
                // keep string
            }
        }
        return s;
    }

    private static String cellToStr(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static int parseInt(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String string(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
