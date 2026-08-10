package cz.bankintel.sources.ecb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EcbAvailabilityServiceTest {

    @Mock
    private EcbCuratedCatalog ecbCuratedCatalog;

    @Test
    void parseCuratedSetId_acceptsEcbColonFormat() {
        EcbAvailabilityService.ParsedCurated parsed = EcbAvailabilityService.parseCuratedSetId("ecb:CZ:inflace_celkova");
        assertNotNull(parsed);
        assertEquals("CZ", parsed.country());
        assertEquals("inflace_celkova", parsed.indicatorId());
    }

    @Test
    void parseCuratedSetId_acceptsUnderscoreSidecarFormatWithCountry() {
        lenient().when(ecbCuratedCatalog.indicatorById("icp_inflace_jadrova")).thenReturn(null);
        when(ecbCuratedCatalog.indicatorById("inflace_jadrova")).thenReturn(Map.of("flow", "ICP"));
        EcbAvailabilityService.ParsedCurated parsed = EcbAvailabilityService.parseCuratedSetId(
                "ecb_icp_inflace_jadrova",
                Map.of("country", "CZ", "query_params", Map.of("country", "CZ")),
                ecbCuratedCatalog);
        assertNotNull(parsed);
        assertEquals("CZ", parsed.country());
        assertEquals("inflace_jadrova", parsed.indicatorId());
    }

    @Test
    void parseCuratedSetId_rejectsUnderscoreWithoutCountry() {
        assertNull(EcbAvailabilityService.parseCuratedSetId(
                "ecb_icp_inflace_jadrova", Map.of(), ecbCuratedCatalog));
    }

    @Test
    void resolveUnderscoreIndicatorId_stripsFlowPrefix() {
        lenient().when(ecbCuratedCatalog.indicatorById("icp_inflace_jadrova")).thenReturn(null);
        when(ecbCuratedCatalog.indicatorById("inflace_jadrova")).thenReturn(Map.of("flow", "ICP"));
        assertEquals(
                "inflace_jadrova",
                EcbAvailabilityService.resolveUnderscoreIndicatorId("icp_inflace_jadrova", ecbCuratedCatalog));
    }
}
