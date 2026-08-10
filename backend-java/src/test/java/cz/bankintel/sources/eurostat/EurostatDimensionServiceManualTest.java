package cz.bankintel.sources.eurostat;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EurostatDimensionServiceManualTest {

    @Test
    void resolvePreviewQueryParams_liveNetwork() {
        EurostatDimensionService service = new EurostatDimensionService(new ObjectMapper(), new EurostatRateLimiter());
        Map<String, Object> qp = service.resolvePreviewQueryParams("prc_hicp_midx", "CZ");
        assertFalse(qp.isEmpty(), "resolved params: " + qp);
        assertFalse(String.valueOf(qp.get("coicop")).isBlank());
    }
}
