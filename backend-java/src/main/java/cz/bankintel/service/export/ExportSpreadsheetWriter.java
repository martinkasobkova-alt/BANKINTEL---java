package cz.bankintel.service.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class ExportSpreadsheetWriter {

    private ExportSpreadsheetWriter() {}

    public static byte[] rowsToXlsx(String title, List<String> columns, List<Map<String, Object>> rows, String subtitle) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sanitizeSheetTitle(title));
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            CellStyle titleStyle = workbook.createCellStyle();
            titleStyle.setFont(titleFont);

            Row titleRow = sheet.createRow(0);
            titleRow.createCell(0).setCellValue(title != null ? title : "Export");
            titleRow.getCell(0).setCellStyle(titleStyle);

            int metaRow = 1;
            if (subtitle != null && !subtitle.isBlank()) {
                sheet.createRow(metaRow++).createCell(0).setCellValue(subtitle);
            }
            sheet.createRow(metaRow).createCell(0).setCellValue("Generated: " + Instant.now() + "Z");

            int start = metaRow + 2;
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row header = sheet.createRow(start);
            for (int c = 0; c < columns.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(columns.get(c));
                cell.setCellStyle(headerStyle);
            }

            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(start + 1 + r);
                Map<String, Object> data = rows.get(r);
                for (int c = 0; c < columns.size(); c++) {
                    writeCell(row.createCell(c), data.get(columns.get(c)));
                }
            }

            for (int c = 0; c < columns.size(); c++) {
                sheet.setColumnWidth(c, Math.min(12000, Math.max(3500, columns.get(c).length() * 256 + 1500)));
            }
            sheet.createFreezePane(0, start + 1);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Excel export failed", ex);
        }
    }

    public static byte[] chartWorkbookToXlsx(String title, Map<String, Object> sheets) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<String> order = List.of("Data_Wide", "Data_Long", "Metadata", "Transformations", "Sources");
            boolean any = false;
            for (String sheetName : order) {
                Object specObj = sheets.get(sheetName);
                if (!(specObj instanceof Map<?, ?> spec)) {
                    continue;
                }
                any = true;
                Object columnsObj = spec.get("columns");
                Object rowsObj = spec.get("rows");
                @SuppressWarnings("unchecked")
                List<String> columns = columnsObj instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList()
                        : List.of();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = rowsObj instanceof List<?> list
                        ? (List<Map<String, Object>>) list
                        : List.of();
                Sheet sheet = workbook.createSheet(sanitizeSheetTitle(sheetName));
                sheet.createRow(0).createCell(0).setCellValue(title != null ? title : "Chart export");
                sheet.createRow(1).createCell(0).setCellValue("Generated: " + Instant.now() + "Z");
                int start = 3;
                Row header = sheet.createRow(start);
                for (int c = 0; c < columns.size(); c++) {
                    header.createCell(c).setCellValue(columns.get(c));
                }
                for (int r = 0; r < rows.size(); r++) {
                    Row row = sheet.createRow(start + 1 + r);
                    Map<String, Object> data = rows.get(r);
                    for (int c = 0; c < columns.size(); c++) {
                        writeCell(row.createCell(c), data.get(columns.get(c)));
                    }
                }
            }
            if (!any) {
                workbook.createSheet("Export").createRow(0).createCell(0).setCellValue(title != null ? title : "Chart export");
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Chart Excel export failed", ex);
        }
    }

    private static void writeCell(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
            return;
        }
        if (value instanceof Number number && !(value instanceof Boolean)) {
            cell.setCellValue(number.doubleValue());
            CellStyle style = cell.getSheet().getWorkbook().createCellStyle();
            style.setAlignment(HorizontalAlignment.RIGHT);
            cell.setCellStyle(style);
            return;
        }
        cell.setCellValue(guardFormulaInjection(String.valueOf(value)));
    }

    private static String guardFormulaInjection(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            return "'" + value;
        }
        return value;
    }

    private static String sanitizeSheetTitle(String title) {
        String cleaned = (title != null ? title : "Export").replace(':', '-').replace('\\', '-').replace('/', '-')
                .replace('?', '-').replace('*', '-').replace('[', '-').replace(']', '-');
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }
}
