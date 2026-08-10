package cz.bankintel.service.sources;

import cz.bankintel.service.userdata.UserDataParseService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SourceFileMetaService {

    private final AdminSourceUploadStorage uploadStorage;

    public Map<String, Object> fileMeta(String path, String sheet, int maxPreviewRows) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing path");
        }
        int cap = Math.max(1, Math.min(maxPreviewRows, 15000));
        try {
            Path resolved = uploadStorage.resolve(path.trim());
            Map<String, Object> meta = inspect(resolved, sheet, cap);
            return Map.of("path", path.trim(), "meta", meta);
        } catch (IOException ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (msg.contains("not found")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "uploaded file not found");
            }
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Náhled souboru se nepodařil načíst. Zkuste to prosím znovu.");
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Náhled souboru se nepodařil načíst. Zkuste to prosím znovu.");
        }
    }

    private Map<String, Object> inspect(Path path, String sheetName, int cap) throws IOException {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        byte[] raw = Files.readAllBytes(path);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("path", path.getFileName().toString());

        if (filename.endsWith(".xlsx") || filename.endsWith(".xlsm")) {
            try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(raw))) {
                List<String> sheets = new ArrayList<>();
                for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                    sheets.add(workbook.getSheetName(i));
                }
                meta.put("kind", "xlsx");
                meta.put("sheets", sheets);
                Sheet sheet = sheetName != null && !sheetName.isBlank()
                        ? workbook.getSheet(sheetName)
                        : workbook.getSheetAt(0);
                if (sheet == null && workbook.getNumberOfSheets() > 0) {
                    sheet = workbook.getSheetAt(0);
                }
            }
            List<Map<String, Object>> rows = UserDataParseService.readTabularRows(raw, ".xlsx");
            meta.put("preview_rows", rows.stream().limit(cap).toList());
            meta.put("columns", rows.isEmpty() ? List.of() : new ArrayList<>(rows.getFirst().keySet()));
            meta.put("row_count_estimate", rows.size());
            return meta;
        }

        if (filename.endsWith(".csv")) {
            List<Map<String, Object>> rows = UserDataParseService.readTabularRows(raw, ".csv");
            meta.put("kind", "csv");
            meta.put("preview_rows", rows.stream().limit(cap).toList());
            meta.put("columns", rows.isEmpty() ? List.of() : new ArrayList<>(rows.getFirst().keySet()));
            meta.put("row_count_estimate", rows.size());
            return meta;
        }

        meta.put("kind", filename);
        meta.put("preview_rows", List.of());
        meta.put("columns", List.of());
        return meta;
    }
}
