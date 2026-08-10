package cz.bankintel.service.me;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PdfExtractServiceTest {

    @Test
    void normalizePdfTextStripsZeroWidthChars() {
        String out = PdfExtractService.normalizePdfText("hello\u200bworld");
        assertEquals("helloworld", out);
    }

    @Test
    void normalizePdfTextHandlesEmptyInput() {
        assertEquals("", PdfExtractService.normalizePdfText(null));
        assertEquals("", PdfExtractService.normalizePdfText(""));
    }
}
