package cz.bankintel.sources.arad;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AradIndicatorHttpSupportTest {

    @Test
    void decodesCzechCsvFallbackAsWindows1250WhenUtf8IsNotDeclared() {
        byte[] bytes = "Počet podílových listů".getBytes(Charset.forName("windows-1250"));

        String decoded = AradIndicatorHttpSupport.decodeBody(bytes, "text/csv");

        assertEquals("Počet podílových listů", decoded);
    }

    @Test
    void keepsDeclaredUtf8AndCompactsLongIndicatorName() {
        String fullName = "Fondy:Čtvrtletní, Čistý zisk celkem (v tis. Kč)";

        String decoded = AradIndicatorHttpSupport.decodeBody(fullName.getBytes(StandardCharsets.UTF_8), "text/csv; charset=UTF-8");
        Map<String, Object> indicator = AradIndicatorHttpSupport.serializeIndicator(
                Map.of("indicator_id", "\"DIFUKAEUQFKI6\"", "indicator_name", "\"" + decoded + "\""));

        assertEquals("DIFUKAEUQFKI6", indicator.get("indicator_id"));
        assertEquals("Čistý zisk celkem (v tis. Kč)", indicator.get("name"));
        assertEquals(fullName, indicator.get("full_name"));
    }
}
