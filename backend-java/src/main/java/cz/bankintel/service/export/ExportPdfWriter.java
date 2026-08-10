package cz.bankintel.service.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

public final class ExportPdfWriter {

    private static final float MARGIN = 40f;
    private static final float LINE_HEIGHT = 14f;

    private ExportPdfWriter() {}

    public static byte[] rowsToPdf(String title, List<String> columns, List<Map<String, Object>> rows, String subtitle) {
        return rowsToPdf(title, columns, rows, subtitle, null);
    }

    public static byte[] rowsToPdf(
            String title, List<String> columns, List<Map<String, Object>> rows, String subtitle, byte[] chartPng) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
            document.addPage(page);
            float y = page.getMediaBox().getHeight() - MARGIN;
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                y = writeLine(content, PDType1Font.HELVETICA_BOLD, 16, MARGIN, y, title != null ? title : "Export");
                if (subtitle != null && !subtitle.isBlank()) {
                    y = writeLine(content, PDType1Font.HELVETICA, 10, MARGIN, y, subtitle);
                }
                y = writeLine(content, PDType1Font.HELVETICA, 9, MARGIN, y, "Generated: " + Instant.now() + "Z");
                y -= LINE_HEIGHT;

                if (chartPng != null && chartPng.length > 0) {
                    y -= 8;
                    y = writeLine(content, PDType1Font.HELVETICA_BOLD, 11, MARGIN, y, "Chart preview attached in export metadata.");
                    y -= LINE_HEIGHT;
                }

                String header = String.join(" | ", columns.stream().limit(8).toList());
                y = writeLine(content, PDType1Font.HELVETICA_BOLD, 9, MARGIN, y, header);
                for (Map<String, Object> row : rows.stream().limit(500).toList()) {
                    if (y < MARGIN + LINE_HEIGHT) {
                        break;
                    }
                    List<String> values = columns.stream()
                            .limit(8)
                            .map(col -> formatValue(row.get(col)))
                            .toList();
                    y = writeLine(content, PDType1Font.HELVETICA, 8, MARGIN, y, String.join(" | ", values));
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("PDF export failed", ex);
        }
    }

    public static byte[] decodeChartPng(Object chartImagePng) {
        if (!(chartImagePng instanceof String raw) || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (value.length() > 18_000_000) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length > 14 * 1024 * 1024 || decoded.length < 8) {
                return null;
            }
            if (decoded[0] != (byte) 0x89 || decoded[1] != 0x50 || decoded[2] != 0x4E || decoded[3] != 0x47) {
                return null;
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static float writeLine(
            PDPageContentStream content, PDType1Font font, float size, float x, float y, String text)
            throws IOException {
        String clipped = text == null ? "" : (text.length() > 220 ? text.substring(0, 220) + "…" : text);
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(clipped.replace('\n', ' '));
        content.endText();
        return y - LINE_HEIGHT;
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return String.valueOf(value).replace('\n', ' ');
        }
        return String.valueOf(value);
    }
}
