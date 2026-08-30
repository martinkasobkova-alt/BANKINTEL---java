package cz.bankintel.explore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the ETAPA 2 fix: {@code /explore/sector/stream} used to run discovery
 * TWICE per request - once via {@code discoverWithLanes} (for SSE progress, its result discarded)
 * and once more via {@code analyzeSector -> discover} for the real answer - live-measured at
 * ~157s combined for a single user query (see docs/archive/MANAGER_EXPLORER_AUDIT_V2.md section 1.1). This
 * test drives a real {@link ExploreStreamService} against mocked collaborators and asserts
 * discovery runs exactly once.
 */
class ExploreStreamServiceDiscoveryPassTest {

    @Test
    void streamSectorRunsDiscoveryExactlyOnceAndNeverCallsAnalyzeSector() {
        ExploreSectorService sectorService = mock(ExploreSectorService.class);
        ExploreDiscoveryService discoveryService = mock(ExploreDiscoveryService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(sectorService.buildPresetPreview(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("ok", true, "sector_indicators", List.of()));

        ExploreSectorService.PreparedAnalysis prep =
                new ExploreSectorService.PreparedAnalysis(null, "banking_finance", "otazka", Map.of(), Map.of(), Map.of());
        when(sectorService.prepareAnalysis(any())).thenReturn(prep);

        ExploreDiscoveryService.IndicatorBundle bundle =
                new ExploreDiscoveryService.IndicatorBundle(List.of(Map.of("source", "arad")), List.of(), 1, false, 0L);
        when(discoveryService.discoverWithLanes(anyString(), anyString(), anyBoolean(), any(), any())).thenReturn(bundle);

        when(sectorService.finalizeAnalysis(any(), any(), any())).thenReturn(Map.of("ok", true));

        ExploreStreamService streamService = new ExploreStreamService(sectorService, discoveryService, objectMapper);

        // runStream() executes on CompletableFuture.runAsync's own executor - streamSector()
        // returns before it finishes, so Mockito.timeout(...) polls verify() until the async work
        // catches up instead of racing a fixed sleep.
        streamService.streamSector("banking_finance", "otazka", "CZ", "countries", null, null, null, "sector");

        verify(discoveryService, timeout(5_000)).discoverWithLanes(anyString(), anyString(), anyBoolean(), any(), any());
        verify(discoveryService, never()).discover(any(), any(), anyBoolean());
        verify(sectorService, never()).analyzeSector(any());
    }
}
