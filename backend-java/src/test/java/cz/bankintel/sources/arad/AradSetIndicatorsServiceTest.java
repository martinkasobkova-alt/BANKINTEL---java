package cz.bankintel.sources.arad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.service.sources.SourceAradIndicatorService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AradSetIndicatorsServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void liveResponseKeepsBlankSourceIdAsNullWithoutFailing() throws Exception {
        SourceAradIndicatorService sourceService = mock(SourceAradIndicatorService.class);
        AradIndicatorHttpSupport httpSupport = mock(AradIndicatorHttpSupport.class);
        when(sourceService.findSourceIdForSetId("1110")).thenReturn("");
        when(httpSupport.apiKeyConfigured()).thenReturn(true);
        when(httpSupport.fetchIndicators("1110"))
                .thenReturn(List.of(Map.of("indicator_id", "DIFUKAEUQFKI1", "indicator_name", "Readable indicator")));

        AradSetIndicatorsService service = new AradSetIndicatorsService(sourceService, httpSupport);

        Map<String, Object> response = service.getSetIndicators("1110");

        assertEquals("1110", response.get("set_id"));
        assertNull(response.get("source_id"));
        assertEquals(false, response.get("from_cache"));
        List<Map<String, Object>> indicators = (List<Map<String, Object>>) response.get("indicators");
        assertEquals("DIFUKAEUQFKI1", indicators.getFirst().get("indicator_id"));
        assertEquals("Readable indicator", indicators.getFirst().get("name"));

        Map<String, Object> cached = service.getSetIndicators("1110");
        assertTrue(cached == response);
        verify(httpSupport).fetchIndicators("1110");
    }
}
