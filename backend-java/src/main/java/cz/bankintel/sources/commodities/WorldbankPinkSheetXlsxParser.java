package cz.bankintel.sources.commodities;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Parsování World Bank CMO Pink Sheet / Forecasts XLSX — port {@code worldbank_commodities.py}. */
final class WorldbankPinkSheetXlsxParser {

    private static final Set<String> MISSING_MARKERS =
            Set.of("…", "..", "—", "-", "n/a", "na", "#n/a");
    private static final Pattern PERIOD_PATTERN = Pattern.compile("^(\\d{4})M(\\d{2})$");
    private static final Pattern YEAR_HEADER = Pattern.compile("^\\d{4}");
    private static final Pattern UPDATED_ON = Pattern.compile("Updated on\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final DataFormatter FORMATTER = new DataFormatter();

    private WorldbankPinkSheetXlsxParser() {}

    static Map<String, Object> parsePinkSheetMonthlyXlsx(byte[] content) throws IOException {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet ws = wb.getSheet("Monthly Prices");
            if (ws == null) {
                throw new IOException("Pink Sheet XLS: chybí list Monthly Prices.");
            }
            List<List<String>> rows = readRows(ws);
            if (rows.size() < 8) {
                throw new IOException("Pink Sheet XLS: neočekávaná struktura (Monthly Prices).");
            }

            List<String> names = new ArrayList<>(rows.get(4).subList(1, rows.get(4).size()));
            List<String> units = new ArrayList<>(rows.get(5).subList(1, rows.get(5).size()));
            List<String> maybeCodes = new ArrayList<>(rows.get(6).subList(1, rows.get(6).size()));
            String updatedRaw = rows.get(3).isEmpty() ? "" : rows.get(3).get(0);
            Matcher updatedMatch = UPDATED_ON.matcher(updatedRaw);
            String updatedOn = updatedMatch.find() ? updatedMatch.group(1).trim() : updatedRaw.trim();

            int firstDataRow = 7;
            boolean hasCodeRow = parseWbPeriod(cellAt(rows, 6, 0))[0] == null;
            List<String> rawCodes;
            if (hasCodeRow) {
                rawCodes = maybeCodes;
            } else {
                firstDataRow = 6;
                rawCodes = new ArrayList<>();
                for (String name : names) {
                    rawCodes.add(slugify(name));
                }
            }

            List<String> codes = new ArrayList<>();
            Map<String, Integer> seenCodes = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                String nameRaw = i < names.size() ? names.get(i) : "";
                String codeRaw = i < rawCodes.size() ? rawCodes.get(i) : "";
                String code = codeRaw != null ? codeRaw.trim() : "";
                if (code.isBlank() || MISSING_MARKERS.contains(code.toLowerCase(Locale.ROOT))) {
                    code = slugify(nameRaw);
                }
                seenCodes.put(code, seenCodes.getOrDefault(code, 0) + 1);
                if (seenCodes.get(code) > 1) {
                    code = code + "_" + seenCodes.get(code);
                }
                codes.add(code);
            }

            List<Map<String, Object>> seriesList = new ArrayList<>();
            Map<String, List<Map<String, Object>>> observations = new LinkedHashMap<>();

            for (int idx = 0; idx < codes.size(); idx++) {
                String code = codes.get(idx).trim();
                if (code.isBlank() || MISSING_MARKERS.contains(code)) {
                    continue;
                }
                String name = idx < names.size() ? stringOrBlank(names.get(idx)) : code;
                if (name.isBlank()) {
                    name = code;
                }
                String unit = idx < units.size() ? stringOrBlank(units.get(idx)) : "";
                String category = categoryForCode(code, name);
                String sid = code;

                Map<String, Object> series = new LinkedHashMap<>();
                series.put("set_id", sid);
                series.put("code", code);
                series.put("name", name);
                series.put("unit", unit);
                series.put("category", category);
                series.put("kind", "actual");
                series.put("source", "worldbank_pink_sheet");
                series.put("full_path", "World Bank Pink Sheet > " + category + " > " + name);
                seriesList.add(series);

                List<Map<String, Object>> obs = new ArrayList<>();
                int colI = idx + 1;
                for (int r = firstDataRow; r < rows.size(); r++) {
                    List<String> row = rows.get(r);
                    if (colI >= row.size()) {
                        break;
                    }
                    String[] period = parseWbPeriod(cellAt(rows, r, 0));
                    Double val = coerceValue(cellAt(rows, r, colI));
                    if (period[0] == null || val == null) {
                        continue;
                    }
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("date", period[0]);
                    point.put("period", period[1]);
                    point.put("value", val);
                    obs.add(point);
                }
                observations.put(sid, obs);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version", 1);
            out.put("generated_at", java.time.Instant.now().toString());
            out.put("updated_on_label", updatedOn);
            out.put("series_count", seriesList.size());
            out.put("series", seriesList);
            out.put("observations", observations);
            return out;
        }
    }

    static Map<String, Object> parseCmoForecastsXlsx(byte[] content) throws IOException {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet ws = wb.getSheet("Forecast");
            if (ws == null) {
                throw new IOException("CMO Forecasts XLS: chybí list Forecast.");
            }
            List<List<String>> rows = readRows(ws);
            if (rows.size() < 10) {
                throw new IOException("CMO Forecasts XLS: neočekávaná struktura.");
            }

            List<String> header = rows.get(3);
            List<int[]> yearCols = new ArrayList<>();
            for (int colIdx = 0; colIdx < header.size(); colIdx++) {
                String cell = cellAt(rows, 3, colIdx);
                if (cell.isBlank()) {
                    if (!yearCols.isEmpty()) {
                        break;
                    }
                    continue;
                }
                if (YEAR_HEADER.matcher(cell).find()) {
                    yearCols.add(new int[] {colIdx, Integer.parseInt(cell.replace("f", "").trim())});
                } else if (!yearCols.isEmpty()) {
                    break;
                }
            }
            if (yearCols.isEmpty()) {
                throw new IOException("CMO Forecasts XLS: chybí sloupce s roky.");
            }
            int firstYearCol = yearCols.get(0)[0];

            List<String> pathStack = new ArrayList<>();
            List<Map<String, Object>> items = new ArrayList<>();

            for (int r = 5; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                String name = rowLabel(row, firstYearCol);
                if (name.isBlank()) {
                    continue;
                }
                String low = name.toLowerCase(Locale.ROOT).trim();
                if (low.startsWith("indexes") || low.startsWith("prices")) {
                    continue;
                }

                String unit = rowUnit(row, firstYearCol);
                boolean hasValues = false;
                for (int[] yc : yearCols) {
                    if (yc[0] < row.size() && coerceValue(row.get(yc[0])) != null) {
                        hasValues = true;
                        break;
                    }
                }

                if (unit.isBlank() && !hasValues) {
                    String clean = name.replace(" 3/", "")
                            .replace(" 4/", "")
                            .replace(" 5/", "")
                            .replace(" 1/", "")
                            .trim();
                    int depth = 0;
                    for (int i = 0; i < firstYearCol && i < row.size(); i++) {
                        if (name.equals(stringOrBlank(row.get(i)))) {
                            depth = i;
                            break;
                        }
                    }
                    while (pathStack.size() > depth) {
                        pathStack.remove(pathStack.size() - 1);
                    }
                    if (depth >= pathStack.size()) {
                        pathStack.add(clean);
                    } else {
                        pathStack.set(depth, clean);
                    }
                    continue;
                }
                if (unit.isBlank()) {
                    continue;
                }

                List<Map<String, Object>> values = new ArrayList<>();
                for (int[] yc : yearCols) {
                    if (yc[0] >= row.size()) {
                        continue;
                    }
                    Double v = coerceValue(row.get(yc[0]));
                    if (v == null) {
                        continue;
                    }
                    Map<String, Object> val = new LinkedHashMap<>();
                    val.put("year", yc[1]);
                    val.put("value", v);
                    values.add(val);
                }
                if (values.isEmpty()) {
                    continue;
                }

                List<String> pathParts = new ArrayList<>();
                pathParts.add("World Bank CMO Forecast");
                pathParts.addAll(pathStack.stream().filter(p -> !p.isBlank()).toList());
                pathParts.add(name);
                String fullPath = String.join(" > ", pathParts);
                String category = pathStack.isEmpty() ? "Forecasts" : pathStack.get(0);
                String subgroup = pathStack.size() > 1 ? pathStack.get(pathStack.size() - 1) : null;

                String sid = "FCST|" + slugify(name);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("set_id", sid);
                item.put("code", sid);
                item.put("name", name);
                item.put("unit", unit);
                item.put("category", category);
                if (subgroup != null) {
                    item.put("subcategory", subgroup);
                }
                item.put("kind", "forecast");
                item.put("source", "worldbank_cmo_forecast");
                item.put("full_path", fullPath);
                item.put("values", values);
                items.add(item);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version", 1);
            out.put("generated_at", java.time.Instant.now().toString());
            out.put("item_count", items.size());
            out.put("items", items);
            return out;
        }
    }

    private static List<List<String>> readRows(Sheet sheet) {
        List<List<String>> rows = new ArrayList<>();
        for (Row row : sheet) {
            List<String> cells = new ArrayList<>();
            int last = row.getLastCellNum();
            if (last < 0) {
                rows.add(List.of());
                continue;
            }
            for (int c = 0; c < last; c++) {
                Cell cell = row.getCell(c);
                cells.add(cell == null ? "" : FORMATTER.formatCellValue(cell).trim());
            }
            rows.add(cells);
        }
        return rows;
    }

    private static String cellAt(List<List<String>> rows, int rowIdx, int colIdx) {
        if (rowIdx < 0 || rowIdx >= rows.size()) {
            return "";
        }
        List<String> row = rows.get(rowIdx);
        if (colIdx < 0 || colIdx >= row.size()) {
            return "";
        }
        return stringOrBlank(row.get(colIdx));
    }

    private static String rowLabel(List<String> row, int firstYearCol) {
        for (int i = 0; i < firstYearCol && i < row.size(); i++) {
            String s = stringOrBlank(row.get(i));
            if (!s.isBlank() && !s.toLowerCase(Locale.ROOT).startsWith("table")) {
                return s;
            }
        }
        return "";
    }

    private static String rowUnit(List<String> row, int firstYearCol) {
        for (int i = 0; i < firstYearCol && i < row.size(); i++) {
            String s = stringOrBlank(row.get(i));
            if (s.startsWith("$/") || (s.startsWith("(") && s.contains("="))) {
                return s;
            }
        }
        return "";
    }

    static String categoryForCode(String code, String name) {
        String c = stringOrBlank(code).toUpperCase(Locale.ROOT);
        String n = stringOrBlank(name).toLowerCase(Locale.ROOT);
        if (c.startsWith("CRUDE_") || c.startsWith("COAL_") || c.startsWith("NGAS") || "INATGAS".equals(c)) {
            return "Energy";
        }
        if (c.startsWith("I") && c.chars().allMatch(Character::isLetter)) {
            return "Indexes";
        }
        if (Set.of("GOLD", "SILVER", "PLATINUM").contains(c) || n.contains("precious")) {
            return "Precious metals";
        }
        if (Set.of("ALUMINUM", "COPPER", "LEAD", "TIN", "NICKEL", "ZINC", "IRON_ORE").contains(c)) {
            return "Metals and minerals";
        }
        if (Set.of("PHOSROCK", "DAP", "TSP", "UREA_EE_BULK", "POTASH").contains(c)) {
            return "Fertilizers";
        }
        if (containsAny(c, "COCOA", "COFFEE", "TEA_") || n.contains("beverage")) {
            return "Beverages";
        }
        if (containsAny(c, "OIL", "SOYBEAN", "PALM", "GRNUT", "COCONUT", "RAPESEED", "SUNFLOWER", "FISH_MEAL", "PLMKRNL")) {
            return "Oils and meals";
        }
        if (containsAny(c, "WHEAT", "MAIZE", "RICE", "BARLEY", "SORGHUM")) {
            return "Grains";
        }
        if (containsAny(c, "BANANA", "ORANGE", "BEEF", "CHICKEN", "LAMB", "SHRIMP", "SUGAR", "TOBAC")) {
            return "Food";
        }
        if (containsAny(c, "LOGS", "SAWNWD", "PLYWOOD", "COTTON", "RUBBER")) {
            return "Raw materials";
        }
        return "Other";
    }

    private static boolean containsAny(String code, String... keys) {
        for (String key : keys) {
            if (code.contains(key)) {
                return true;
            }
        }
        return false;
    }

    static String slugify(String text) {
        String s = stringOrBlank(text).replaceAll("[^a-zA-Z0-9]+", "_").replaceAll("^_+|_+$", "").toUpperCase(Locale.ROOT);
        if (s.length() > 80) {
            s = s.substring(0, 80);
        }
        return s.isBlank() ? "SERIES" : s;
    }

    static String[] parseWbPeriod(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[] {null, null};
        }
        Matcher m = PERIOD_PATTERN.matcher(raw.trim());
        if (!m.matches()) {
            return new String[] {null, null};
        }
        int y = Integer.parseInt(m.group(1));
        int mo = Integer.parseInt(m.group(2));
        if (mo < 1 || mo > 12) {
            return new String[] {null, null};
        }
        return new String[] {String.format(Locale.ROOT, "%04d-%02d-01", y, mo), raw.trim()};
    }

    static Double coerceValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (MISSING_MARKERS.contains(s.toLowerCase(Locale.ROOT)) || MISSING_MARKERS.contains(s)) {
            return null;
        }
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String unitLabelCs(String unit) {
        String u = stringOrBlank(unit);
        if ("$/mt".equals(u)) {
            return "USD za metrickou tunu (US dolar / metrická tuna)";
        }
        if ("$/bbl".equals(u)) {
            return "USD za barel";
        }
        if ("$/mmbtu".equals(u)) {
            return "USD za milion BTU";
        }
        if ("($/mt)".equals(u)) {
            return "USD za metrickou tunu";
        }
        if ("2010=100".equals(u) || u.contains("2010=100")) {
            return "index (2010 = 100), nominální USD";
        }
        return u;
    }

    private static String stringOrBlank(String value) {
        return value != null ? value.trim() : "";
    }
}
