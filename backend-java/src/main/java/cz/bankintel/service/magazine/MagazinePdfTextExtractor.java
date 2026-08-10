package cz.bankintel.service.magazine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class MagazinePdfTextExtractor {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MAX_CHARS_PER_PAGE = 12_000;

    public record PageText(int page, String text) {}

    public List<PageText> extractPages(byte[] pdfBytes) {
        List<PageText> out = new ArrayList<>();
        if (pdfBytes == null || pdfBytes.length == 0) {
            return out;
        }
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = document.getNumberOfPages();
            for (int i = 1; i <= pageCount; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(document);
                text = normalizePdfText(text).trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (text.length() > MAX_CHARS_PER_PAGE) {
                    text = text.substring(0, MAX_CHARS_PER_PAGE);
                }
                out.add(new PageText(i, text));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Nepodařilo se načíst text z PDF: " + e.getMessage(), e);
        }
        return out;
    }

    public byte[] extractSinglePagePdf(byte[] pdfBytes, int page) {
        try (PDDocument source = PDDocument.load(pdfBytes);
                PDDocument single = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            int pageCount = source.getNumberOfPages();
            if (pageCount == 0) {
                return pdfBytes;
            }
            int pageIndex = Math.max(0, Math.min(page - 1, pageCount - 1));
            single.importPage(source.getPage(pageIndex));
            single.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            return pdfBytes;
        }
    }

    public byte[] renderPagePreviewPng(byte[] pdfBytes, int page, int targetWidth) throws IOException {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw new IOException("PDF nemá žádné stránky.");
            }
            int pageIndex = Math.max(0, Math.min(page - 1, pageCount - 1));
            PDFRenderer renderer = new PDFRenderer(document);
            float pageWidth = document.getPage(pageIndex).getMediaBox().getWidth();
            float scale = pageWidth > 0 ? targetWidth / pageWidth : 1.5f;
            if (scale < 0.5f) {
                scale = 0.5f;
            }
            if (scale > 4f) {
                scale = 4f;
            }
            var image = renderer.renderImage(pageIndex, scale);
            var baos = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        }
    }

    static String normalizePdfText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String t = text.replace('\u00a0', ' ');
        t = WHITESPACE.matcher(t).replaceAll(" ");
        return t.trim();
    }
}
