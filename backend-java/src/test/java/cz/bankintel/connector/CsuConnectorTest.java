package cz.bankintel.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CsuConnectorTest {

    @Test
    void buildDatasetPostBodyUsesDatastatColumnShape() {
        Map<String, Object> body = CsuConnector.buildDatasetPostBody(List.of("CasR", "Uzemi", "IndicatorType"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) body.get("sloupce");

        assertEquals("IndicatorType", columns.get(0).get("kodDimenze"));
        assertEquals("CasR", columns.get(1).get("kodDimenze"));
        assertEquals("Uzemi", columns.get(2).get("kodDimenze"));
        assertEquals(3, columns.size());
        assertTrue(body.containsKey("radky"));
        assertTrue(body.containsKey("filtryTabulky"));
    }
}
