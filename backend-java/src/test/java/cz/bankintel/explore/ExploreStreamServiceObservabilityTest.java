package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogSourceRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExploreStreamServiceObservabilityTest {

    @Test
    void finalEventCarriesOneRunAndRegistryDrivenTerminalStatuses() throws Exception {
        ExploreSectorService sectorService = mock(ExploreSectorService.class);
        ExploreDiscoveryService discoveryService = mock(ExploreDiscoveryService.class);
        List<String> sources = normalizedDefaultSources();

        when(sectorService.buildPresetPreview(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("ok", true, "sector_indicators", List.of()));
        ExploreSectorService.PreparedAnalysis prep =
                new ExploreSectorService.PreparedAnalysis(null, "banking_finance", "question", Map.of(), Map.of(), Map.of());
        when(sectorService.prepareAnalysis(any())).thenReturn(prep);
        when(discoveryService.discoverWithLanes(anyString(), anyString(), anyBoolean(), any(), any()))
                .thenAnswer(invocation -> {
                    ExploreDiscoveryService.LaneConsumer consumer = invocation.getArgument(4);
                    for (String source : sources) {
                        consumer.onLane(source, Map.of("count", 1, "phase", "catalog_index"));
                    }
                    return new ExploreDiscoveryService.IndicatorBundle(
                            List.of(), List.of(), sources.size(), true, 3L, 17L);
                });
        when(sectorService.finalizeAnalysis(any(), any(), any())).thenReturn(Map.of(
                "ok", true,
                "cache_hit", true,
                "serving_time_ms", 3L,
                "cached_compute_time_ms", 17L));

        ObjectMapper spyMapper = spy(new ObjectMapper());
        ExploreStreamService streamService = new ExploreStreamService(sectorService, discoveryService, spyMapper);
        String requestId = "manager-release-audit";
        streamService.streamSector(
                "banking_finance", "question", "CZ", "countries", null, null, null, "sector", requestId);

        verify(sectorService, timeout(5_000)).finalizeAnalysis(any(), any(), any());
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(spyMapper, timeout(1_000).atLeastOnce()).writeValueAsString(captor.capture());

        List<JsonNode> packets = new ArrayList<>();
        for (Object value : captor.getAllValues()) {
            packets.add(spyMapper.valueToTree(value));
        }
        JsonNode finalPacket = packets.stream()
                .filter(packet -> "search_finished".equals(packet.path("event").asText()))
                .findFirst()
                .orElseThrow();
        JsonNode payload = finalPacket.path("payload");

        assertEquals(requestId, finalPacket.path("request_id").asText());
        assertEquals(requestId, payload.path("request_id").asText());
        assertFalse(finalPacket.path("discovery_run_id").asText().isBlank());
        assertEquals(
                finalPacket.path("discovery_run_id").asText(),
                payload.path("discovery_run_id").asText());
        assertNotEquals(requestId, payload.path("discovery_run_id").asText());
        assertEquals(1, payload.path("full_discovery_run_count").asInt());
        assertEquals("completed", payload.path("terminal_status").asText());
        assertTrue(payload.path("cache_hit").asBoolean());
        assertEquals(3L, payload.path("serving_time_ms").asLong());
        assertEquals(17L, payload.path("cached_compute_time_ms").asLong());

        Map<String, String> statuses = spyMapper.convertValue(
                payload.path("source_terminal_statuses"),
                spyMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
        assertEquals(Set.copyOf(sources), statuses.keySet());
        assertEquals(Set.of("ok"), Set.copyOf(statuses.values()));
    }

    private static List<String> normalizedDefaultSources() {
        return CatalogSourceRegistry.EXPLORE_DISCOVERY_DEFAULT_SOURCES.stream()
                .map(CatalogSourceRegistry::normalizeSearchSource)
                .distinct()
                .toList();
    }
}
