package cz.bankintel.service.userdata;

import cz.bankintel.domain.entity.UserUploadedSeriesEntity;
import cz.bankintel.util.IdGenerator;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public final class UserDataParseService {

    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern YEAR_Q = Pattern.compile("(?i)^q([1-4])[-/]?(\\d{4})$");
    private static final Pattern YEAR_Q2 = Pattern.compile("(?i)^(\\d{4})q([1-4])$");
    private static final DataFormatter FORMATTER = new DataFormatter();

    private UserDataParseService() {}

    public record ParseResult(List<UserUploadedSeriesEntity> series, Map<String, Object> meta) {}

    public static ParseResult parseUpload(
            byte[] raw,
            String ext,
            String userId,
            String companyId,
            String uploadId,
            String filename) {
        String normalizedExt = ext.startsWith(".") ? ext.toLowerCase(Locale.ROOT) : ("." + ext).toLowerCase(Locale.ROOT);
        return switch (normalizedExt) {
            case ".csv" -> parseCsv(raw, userId, companyId, uploadId, filename);
            case ".xlsx", ".xlsm" -> parseXlsx(raw, userId, companyId, uploadId, filename);
            case ".pdf" -> parsePdf(raw, userId, companyId, uploadId, filename);
            default -> emptyResult("unsupported_for_auto_parse", List.of("Unsupported file type."));
        };
    }

    public static List<Map<String, Object>> readTabularRows(byte[] raw, String ext) {
        String normalizedExt = ext.startsWith(".") ? ext.toLowerCase(Locale.ROOT) : ("." + ext).toLowerCase(Locale.ROOT);
        return switch (normalizedExt) {
            case ".csv" -> readCsvRows(raw);
            case ".xlsx", ".xlsm" -> readXlsxRows(raw);
            default -> List.of();
        };
    }

    public static Map<String, Object> summarizeSeries(UserUploadedSeriesEntity doc) {
        List<Map<String, Object>> observations = doc.getObservations() != null ? doc.getObservations() : List.of();
        Map<String, Object> latest = observations.isEmpty() ? Map.of() : observations.get(observations.size() - 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", doc.getId());
        out.put("upload_id", doc.getUploadId());
        out.put("source", "user_upload");
        out.put("dataset_id", doc.getDatasetId());
        out.put("title", doc.getTitle());
        out.put("description", doc.getDescription());
        out.put("metric_type", doc.getMetricType());
        out.put("detected_domain", doc.getDetectedDomain() != null ? doc.getDetectedDomain() : "");
        out.put("frequency", doc.getFrequency());
        out.put("unit", doc.getUnit());
        out.put("currency", doc.getCurrency());
        out.put("mapping_confidence", doc.getMappingConfidence());
        out.put("mapping_reason", doc.getMappingReason());
        out.put("privacy_flag", true);
        out.put("priority", "high");
        out.put("observations_count", observations.size());
        out.put("latest_period", latest.get("period"));
        out.put("latest_value", latest.get("value"));
        out.put("tags", doc.getTags() != null ? doc.getTags() : List.of());
        return out;
    }

    public static Map<String, Object> sanitizeUpload(Map<String, Object> upload) {
        Map<String, Object> out = new LinkedHashMap<>(upload);
        out.remove("stored_rel_path");
        return out;
    }

    private static ParseResult parseCsv(byte[] raw, String userId, String companyId, String uploadId, String filename) {
        List<Map<String, Object>> rows = readCsvRows(raw);
        return buildParseResult(rows, userId, companyId, uploadId, filename, "csv");
    }

    private static ParseResult parseXlsx(byte[] raw, String userId, String companyId, String uploadId, String filename) {
        List<Map<String, Object>> rows = readXlsxRows(raw);
        return buildParseResult(rows, userId, companyId, uploadId, filename, "xlsx");
    }

    private static ParseResult parsePdf(byte[] raw, String userId, String companyId, String uploadId, String filename) {
        String preview = extractPdfPreview(raw);
        List<Map<String, Object>> rows = parsePdfTableRows(raw);
        List<UserUploadedSeriesEntity> series = parseMetricRowsTable(
                rows, userId, companyId, uploadId, filename, "PDF upload `" + filename + "`.", null, null);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(
                "detected_tables",
                List.of(Map.of(
                        "format", "pdf",
                        "row_count", rows.size(),
                        "series_count", series.size())));
        meta.put("extracted_text_preview", preview.length() > 1600 ? preview.substring(0, 1600) : preview);
        meta.put("status", series.isEmpty() ? "needs_mapping" : "parsed");
        meta.put(
                "errors",
                series.isEmpty()
                        ? List.of("PDF text/table was extracted, but mapping to reliable series needs manual review.")
                        : List.of());
        return new ParseResult(series, meta);
    }

    private static ParseResult buildParseResult(
            List<Map<String, Object>> rows,
            String userId,
            String companyId,
            String uploadId,
            String filename,
            String format) {
        List<UserUploadedSeriesEntity> series = parseWidePeriodTable(
                rows, userId, companyId, uploadId, filename, format.toUpperCase(Locale.ROOT) + " upload `" + filename + "`.");
        if (series.isEmpty()) {
            series = parseMetricRowsTable(
                    rows,
                    userId,
                    companyId,
                    uploadId,
                    filename,
                    format.toUpperCase(Locale.ROOT) + " upload `" + filename + "`.",
                    null,
                    null);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(
                "detected_tables",
                List.of(Map.of("format", format, "row_count", rows.size(), "series_count", series.size())));
        meta.put("extracted_text_preview", "");
        meta.put("status", series.isEmpty() ? "needs_mapping" : "parsed");
        meta.put(
                "errors",
                series.isEmpty() ? List.of("No reliable time series mapping found in " + format.toUpperCase() + " upload.") : List.of());
        return new ParseResult(series, meta);
    }

    private static ParseResult emptyResult(String status, List<String> errors) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("detected_tables", List.of());
        meta.put("extracted_text_preview", "");
        meta.put("status", status);
        meta.put("errors", errors);
        return new ParseResult(List.of(), meta);
    }

    private static List<UserUploadedSeriesEntity> parseWidePeriodTable(
            List<Map<String, Object>> rows,
            String userId,
            String companyId,
            String uploadId,
            String filename,
            String descriptionPrefix) {
        String periodCol = detectPeriodColumn(rows);
        if (periodCol == null) {
            return List.of();
        }
        Map<String, List<Map<String, Object>>> observationsByMetric = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String period = formatPeriod(row.get(periodCol));
            if (period == null) {
                continue;
            }
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (periodCol.equals(entry.getKey())) {
                    continue;
                }
                Double val = parseNumber(entry.getValue());
                if (val == null) {
                    continue;
                }
                observationsByMetric.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(Map.of("period", period, "value", val));
            }
        }
        List<UserUploadedSeriesEntity> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : observationsByMetric.entrySet()) {
            UserUploadedSeriesEntity doc = seriesDoc(
                    userId,
                    companyId,
                    uploadId,
                    entry.getKey(),
                    descriptionPrefix + " Parsed from period column `" + periodCol + "`.",
                    entry.getValue(),
                    List.of(filename, entry.getKey(), periodCol));
            if (doc != null) {
                out.add(doc);
            }
        }
        return out;
    }

    private static List<UserUploadedSeriesEntity> parseMetricRowsTable(
            List<Map<String, Object>> rows,
            String userId,
            String companyId,
            String uploadId,
            String filename,
            String descriptionPrefix,
            String unitHint,
            String currencyHint) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> fields = new ArrayList<>(rows.getFirst().keySet());
        List<String> periodFields = fields.stream().filter(f -> formatPeriod(f) != null).toList();
        if (periodFields.size() < 2) {
            return List.of();
        }
        List<String> nameCandidates = fields.stream().filter(f -> !periodFields.contains(f)).toList();
        if (nameCandidates.isEmpty()) {
            return List.of();
        }
        String nameField = nameCandidates.getFirst();
        List<UserUploadedSeriesEntity> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String metricTitle = String.valueOf(row.getOrDefault(nameField, "")).strip();
            if (metricTitle.isBlank()) {
                continue;
            }
            List<Map<String, Object>> observations = new ArrayList<>();
            for (String pf : periodFields) {
                Double val = parseNumber(row.get(pf));
                String period = formatPeriod(pf);
                if (val == null || period == null) {
                    continue;
                }
                observations.add(Map.of("period", period, "value", val));
            }
            UserUploadedSeriesEntity doc = seriesDoc(
                    userId,
                    companyId,
                    uploadId,
                    metricTitle,
                    descriptionPrefix + " Parsed from metric rows.",
                    observations,
                    List.of(filename, metricTitle));
            if (doc != null) {
                if (unitHint != null && doc.getUnit() == null) {
                    doc.setUnit(unitHint);
                }
                if (currencyHint != null && doc.getCurrency() == null) {
                    doc.setCurrency(currencyHint);
                }
                out.add(doc);
            }
        }
        return out;
    }

    private static UserUploadedSeriesEntity seriesDoc(
            String userId,
            String companyId,
            String uploadId,
            String title,
            String description,
            List<Map<String, Object>> observations,
            List<String> tags) {
        List<Map<String, Object>> obs = new ArrayList<>();
        for (Map<String, Object> o : observations) {
            Object val = o.get("value");
            if (!(val instanceof Number)) {
                continue;
            }
            String period = String.valueOf(o.get("period")).strip();
            if (period.isBlank()) {
                continue;
            }
            obs.add(Map.of("period", period, "value", ((Number) val).doubleValue()));
        }
        if (obs.size() < 2) {
            return null;
        }
        Map<String, Object> mapping = UserSeriesMapper.classifyMetric(title, description, tags);
        if (((Number) mapping.get("confidence")).doubleValue() < 0.55) {
            return null;
        }
        Instant now = Instant.now();
        String datasetId = IdGenerator.newId();
        List<String> periodList = obs.stream().map(o -> String.valueOf(o.get("period"))).toList();
        UserUploadedSeriesEntity entity = new UserUploadedSeriesEntity();
        entity.setId(datasetId);
        entity.setUserId(userId);
        entity.setCompanyId(companyId);
        entity.setUploadId(uploadId);
        entity.setDatasetId(datasetId);
        entity.setTitle(title.length() > 320 ? title.substring(0, 320) : title);
        entity.setDescription(description.length() > 1000 ? description.substring(0, 1000) : description);
        entity.setMetricType(String.valueOf(mapping.get("metric_type")));
        entity.setUnit("unknown");
        entity.setFrequency(UserSeriesMapper.detectFrequency(periodList));
        entity.setDetectedDomain(String.valueOf(mapping.get("detected_domain")));
        entity.setDetectedDomains(List.of(String.valueOf(mapping.get("detected_domain"))));
        entity.setObservations(obs);
        entity.setPeriods(periodList);
        entity.setTags(tags.stream().map(String::strip).filter(s -> !s.isBlank()).distinct().limit(20).toList());
        entity.setMappingConfidence(((Number) mapping.get("confidence")).doubleValue());
        entity.setMappingReason(String.valueOf(mapping.get("mapping_reason")));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    public static String detectPeriodColumn(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        String bestCol = null;
        int bestScore = 0;
        for (String field : rows.getFirst().keySet()) {
            String fname = fold(field);
            int boost = fname.contains("period") || fname.contains("date") || fname.contains("datum") || fname.contains("rok")
                    ? 2
                    : 0;
            int good = 0;
            int checked = 0;
            for (Map<String, Object> row : rows.stream().limit(30).toList()) {
                if (!row.containsKey(field)) {
                    continue;
                }
                checked++;
                if (formatPeriod(row.get(field)) != null) {
                    good++;
                }
            }
            if (checked == 0) {
                continue;
            }
            int score = good + boost;
            if (good >= Math.max(3, checked / 2) && score > bestScore) {
                bestCol = field;
                bestScore = score;
            }
        }
        return bestCol;
    }

    public static String formatPeriod(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n && n.intValue() >= 1900 && n.intValue() <= 2100) {
            return String.valueOf(n.intValue());
        }
        String raw = String.valueOf(value).strip();
        if (raw.isEmpty()) {
            return null;
        }
        raw = raw.replace('/', '-').replace('.', '-').replace(" ", "");
        Matcher q1 = YEAR_Q.matcher(raw);
        if (q1.matches()) {
            return q1.group(2) + "-Q" + q1.group(1);
        }
        Matcher q2 = YEAR_Q2.matcher(raw);
        if (q2.matches()) {
            return q2.group(1) + "-Q" + q2.group(2);
        }
        raw = raw.toUpperCase(Locale.ROOT);
        if (raw.matches("\\d{4}-Q[1-4]") || raw.matches("\\d{4}-\\d{2}") || raw.matches("\\d{4}")) {
            return raw;
        }
        Matcher m = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").matcher(raw);
        if (m.matches()) {
            return m.group(1) + "-" + String.format(Locale.ROOT, "%02d", Integer.parseInt(m.group(2)));
        }
        return null;
    }

    public static Double parseNumber(Object value) {
        if (value == null || value instanceof Boolean) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        String s = String.valueOf(value).strip();
        if (s.isEmpty()) {
            return null;
        }
        boolean neg = s.startsWith("(") && s.endsWith(")");
        s = s.strip().replace("(", "").replace(")", "").replace("\u00a0", "").replace(" ", "");
        if (s.chars().filter(ch -> ch == ',').count() > 0 && s.chars().filter(ch -> ch == '.').count() > 0) {
            if (s.lastIndexOf(',') > s.lastIndexOf('.')) {
                s = s.replace(".", "").replace(",", ".");
            } else {
                s = s.replace(",", "");
            }
        } else if (s.chars().filter(ch -> ch == ',').count() > 0) {
            s = s.replace(",", ".");
        }
        try {
            double val = Double.parseDouble(s);
            return neg ? -val : val;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Map<String, Object>> readCsvRows(byte[] raw) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(raw), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return rows;
            }
            List<String> headers = splitCsvLine(headerLine);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> cells = splitCsvLine(line);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), i < cells.size() ? cells.get(i) : "");
                }
                rows.add(row);
            }
        } catch (IOException e) {
            return List.of();
        }
        return rows;
    }

    private static List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (ch == ',' && !inQuotes) {
                out.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        out.add(current.toString().trim());
        return out;
    }

    private static List<Map<String, Object>> readXlsxRows(byte[] raw) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(raw))) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null) {
                return rows;
            }
            Row header = sheet.getRow(0);
            if (header == null) {
                return rows;
            }
            List<String> headers = new ArrayList<>();
            for (Cell cell : header) {
                headers.add(FORMATTER.formatCellValue(cell).strip());
            }
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, Object> map = new LinkedHashMap<>();
                boolean any = false;
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.getCell(c);
                    String val = cell == null ? "" : FORMATTER.formatCellValue(cell).strip();
                    if (!val.isBlank()) {
                        any = true;
                    }
                    map.put(headers.get(c), val);
                }
                if (any) {
                    rows.add(map);
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return rows;
    }

    private static String extractPdfPreview(byte[] raw) {
        try (PDDocument doc = PDDocument.load(raw)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(2, doc.getNumberOfPages()));
            return stripper.getText(doc).strip();
        } catch (IOException e) {
            return "";
        }
    }

    private static List<Map<String, Object>> parsePdfTableRows(byte[] raw) {
        String text = extractPdfPreview(raw);
        if (text.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        String[] lines = text.split("\\R");
        if (lines.length < 2) {
            return rows;
        }
        List<String> headers = splitWhitespace(lines[0]);
        if (headers.size() < 2) {
            return rows;
        }
        for (int i = 1; i < lines.length; i++) {
            List<String> cells = splitWhitespace(lines[i]);
            if (cells.size() < 2) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.size() && c < cells.size(); c++) {
                row.put(headers.get(c), cells.get(c));
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<String> splitWhitespace(String line) {
        return List.of(line.trim().split("\\s{2,}|\\t"));
    }

    private static String fold(String text) {
        return WS.matcher(text == null ? "" : text.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }
}
