package cz.bankintel.service.me;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.util.BankIntelEnvVars;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * PDF table/chart extraction — port {@code pdf_extraction.py} / {@code chart_from_pdf.py}.
 *
 * <p>Limits (honest): PDFBox has no vector-table parser like pdfplumber; table mode uses
 * text-line heuristics. Chart extraction requires OpenAI vision (not wired here).
 */
@Service
@RequiredArgsConstructor
public class PdfExtractService {

    private static final int MAX_BYTES = 40 * 1024 * 1024;
    private static final int MAX_PREVIEW_ROWS = 200;
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");

    private final FeatureAccessService featureAccessService;

    public Map<String, Object> extractTableFromPdf(
            UserEntity user, MultipartFile file, int page, int headerRow, int tableIndex) {
        requireUploadAccess(user);
        byte[] raw = readAndValidatePdf(file);

        try (PDDocument doc = PDDocument.load(raw)) {
            int totalPages = doc.getNumberOfPages();
            if (totalPages == 0) {
                return tableError("PDF neobsahuje žádné stránky.", totalPages, page);
            }

            List<Integer> pageIndices = resolvePageIndices(page, totalPages);
            Object pageLabel = page == 0 ? "all" : pageIndices.getFirst() + 1;

            PDFTextStripper stripper = new PDFTextStripper();
            List<List<String>> lines = new ArrayList<>();
            for (int pi : pageIndices) {
                stripper.setStartPage(pi + 1);
                stripper.setEndPage(pi + 1);
                String text = normalizePdfText(stripper.getText(doc));
                for (String line : text.split("\\R")) {
                    List<String> cells = splitLine(line);
                    if (!cells.isEmpty()) {
                        lines.add(cells);
                    }
                }
            }

            if (lines.isEmpty()) {
                Map<String, Object> out = tableEmpty(totalPages, pageLabel);
                out.put(
                        "limits",
                        "PDFBox text režim — vektorové tabulky (pdfplumber) nejsou v Java portu; "
                                + "zkuste PDF s textovými řádky oddělenými mezerami.");
                return out;
            }

            int hr = Math.max(1, headerRow);
            int maxCols = lines.stream().mapToInt(List::size).max().orElse(0);
            List<String> columns;
            List<List<String>> body;
            if (hr - 1 < lines.size()) {
                columns = padHeaders(toCells(lines.get(hr - 1)), maxCols);
                body = new ArrayList<>(lines.subList(hr, lines.size()));
            } else {
                columns = new ArrayList<>();
                for (int i = 0; i < maxCols; i++) {
                    columns.add("col_" + (i + 1));
                }
                body = lines;
            }

            if (tableIndex >= 0) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("error", "table_index > 0 není v PDFBox text režimu podporován — použijte -1 (vše).");
                out.put("total_pages", totalPages);
                out.put("extracted_from_page", pageLabel);
                out.put("limits", "Více tabulek na stránce vyžaduje pdfplumber (Python backend).");
                return out;
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            for (List<String> rowCells : body) {
                if (rowCells.stream().allMatch(String::isBlank)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 0; c < columns.size(); c++) {
                    row.put(columns.get(c), c < rowCells.size() ? rowCells.get(c) : "");
                }
                rows.add(row);
            }

            boolean truncated = rows.size() > MAX_PREVIEW_ROWS;
            if (truncated) {
                rows = new ArrayList<>(rows.subList(0, MAX_PREVIEW_ROWS));
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("columns", columns);
            out.put("rows", rows);
            out.put("total_rows", rows.size());
            out.put("total_pages", totalPages);
            out.put("extracted_from_page", pageLabel);
            out.put("truncated", truncated);
            out.put("mode_used", "text");
            out.put(
                    "limits",
                    "PDFBox text režim (bez pdfplumber). Sloupce z řádků oddělených ≥2 mezerami; "
                            + "max " + MAX_PREVIEW_ROWS + " řádků ve výstupu.");
            return out;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF se nepodařilo načíst: " + ex.getMessage());
        }
    }

    public Map<String, Object> extractChartFromPdf(UserEntity user, MultipartFile file, int page) {
        requireUploadAccess(user);
        byte[] raw = readAndValidatePdf(file);

        if (!aiVisionEnabled()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", "OPENAI_API_KEY není nastaven — extrakce grafů z PDF (AI vision) není dostupná.");
            out.put("code", "OPENAI_VISION_UNAVAILABLE");
            out.put(
                    "limits",
                    "Plná extrakce grafu vyžaduje OpenAI vision API (Python: chart_from_pdf.py). "
                            + "Java port zatím vrací jen metadata PDF.");
            try (PDDocument doc = PDDocument.load(raw)) {
                out.put("total_pages", doc.getNumberOfPages());
            } catch (IOException ignored) {
                out.put("total_pages", 0);
            }
            return out;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", "AI vision extrakce grafu z PDF ještě není implementována v Java backendu.");
        out.put("code", "CHART_VISION_NOT_PORTED");
        out.put(
                "limits",
                "OPENAI_API_KEY je nastaven, ale volání vision API z Java ještě není portováno "
                        + "(Python backend má plnou implementaci).");
        try (PDDocument doc = PDDocument.load(raw)) {
            int totalPages = doc.getNumberOfPages();
            int clamped = Math.max(1, Math.min(page, totalPages));
            out.put("total_pages", totalPages);
            out.put("extracted_from_page", clamped);
        } catch (IOException ex) {
            out.put("total_pages", 0);
            out.put("error", "PDF se nepodařilo načíst: " + ex.getMessage());
        }
        return out;
    }

    private static boolean aiVisionEnabled() {
        String key = BankIntelEnvVars.get("OPENAI_API_KEY");
        return key != null && !key.isBlank();
    }

    private void requireUploadAccess(UserEntity user) {
        featureAccessService.requireFeature(user, "upload_custom_data");
        if (!FeatureAccessService.isSubscriber(user)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Tato funkce je dostupná pro předplatitele časopisu Bankovnictví.");
        }
    }

    private static byte[] readAndValidatePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor je prázdný.");
        }
        String fname = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (!fname.endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Očekáván soubor PDF.");
        }
        try {
            byte[] raw = file.getBytes();
            if (raw.length > MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Soubor je příliš velký (max 40 MB).");
            }
            if (raw.length < 5 || !looksLikePdf(raw)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor není platné PDF.");
            }
            return raw;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor se nepodařilo načíst.");
        }
    }

    private static boolean looksLikePdf(byte[] raw) {
        return raw[0] == '%' && raw[1] == 'P' && raw[2] == 'D' && raw[3] == 'F';
    }

    private static List<Integer> resolvePageIndices(int page, int totalPages) {
        if (page == 0) {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < totalPages; i++) {
                all.add(i);
            }
            return all;
        }
        int idx = Math.max(0, Math.min(page - 1, totalPages - 1));
        return List.of(idx);
    }

    private static List<String> splitLine(String line) {
        String trimmed = line != null ? line.strip() : "";
        if (trimmed.isEmpty()) {
            return List.of();
        }
        String[] parts = MULTI_SPACE.split(trimmed);
        List<String> cells = new ArrayList<>();
        for (String p : parts) {
            if (!p.isBlank()) {
                cells.add(p.strip());
            }
        }
        if (cells.size() <= 1 && trimmed.contains("\t")) {
            cells.clear();
            for (String p : trimmed.split("\t")) {
                if (!p.isBlank()) {
                    cells.add(p.strip());
                }
            }
        }
        return cells;
    }

    private static List<String> toCells(List<String> row) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < row.size(); i++) {
            String v = row.get(i);
            out.add(v != null && !v.isBlank() ? v.strip() : "col_" + (i + 1));
        }
        return out;
    }

    private static List<String> padHeaders(List<String> headers, int maxCols) {
        List<String> out = new ArrayList<>(headers);
        while (out.size() < maxCols) {
            out.add("col_" + (out.size() + 1));
        }
        return out;
    }

    static String normalizePdfText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String t = text.replace("\u00c3\u00a1", "\u00e1").replace("\u00c3\u00a9", "\u00e9");
        return t.replaceAll("[\\u200b\\u200c\\u200d\\ufeff]", "");
    }

    private static Map<String, Object> tableError(String message, int totalPages, int page) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", message);
        out.put("columns", List.of());
        out.put("rows", List.of());
        out.put("total_rows", 0);
        out.put("total_pages", totalPages);
        out.put("extracted_from_page", page);
        out.put("truncated", false);
        out.put("mode_used", "text");
        return out;
    }

    private static Map<String, Object> tableEmpty(int totalPages, Object pageLabel) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", "Na zvolené stránce nebyl nalezen extrahovatelný text.");
        out.put("columns", List.of());
        out.put("rows", List.of());
        out.put("total_rows", 0);
        out.put("total_pages", totalPages);
        out.put("extracted_from_page", pageLabel);
        out.put("truncated", false);
        out.put("mode_used", "text");
        return out;
    }
}
