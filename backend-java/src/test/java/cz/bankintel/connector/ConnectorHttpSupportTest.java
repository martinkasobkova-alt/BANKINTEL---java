package cz.bankintel.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConnectorHttpSupportTest {

    @Test
    void buildUriDoesNotAppendEmptyQuestionMark() {
        URI uri = ConnectorHttpSupport.buildUri("https://example.test/data?format=csvdata", Map.of());

        assertEquals("https://example.test/data?format=csvdata", uri.toString());
    }

    @Test
    void buildUriExtendsExistingQueryWithAmpersand() {
        URI uri = ConnectorHttpSupport.buildUri(
                "https://example.test/data?format=csvdata",
                Map.of("lastNObservations", "1"));

        assertEquals("https://example.test/data?format=csvdata&lastNObservations=1", uri.toString());
    }
}
