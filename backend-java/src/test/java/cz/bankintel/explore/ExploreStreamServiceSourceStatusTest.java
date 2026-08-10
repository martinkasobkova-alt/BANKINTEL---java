package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Regression tests for ETAPA 3: the SSE stream must report the REAL per-source lane outcome
 * (ok / empty / timeout), not a hardcoded "ok" regardless of what happened - and must emit
 * "source_started" for the sources that are genuinely running, and "source_timeout" for any
 * requested source that never produced a "source_finished" (cancelled by the shared lane wall
 * budget in CatalogDeepSearchService - see MANAGER_EXPLORER_AUDIT_V2.md section 4.1).
 */
class ExploreStreamServiceSourceStatusTest {

    @Test
    void reportsRealOkAndEmptyStatusInsteadOfHardcodedOk() throws Exception {
        ExploreSectorService sectorService = mock(ExploreSectorService.class);
        ExploreDiscoveryService discoveryService = mock(ExploreDiscoveryService.class);
        ExploreDiscoveryService.IndicatorBundle bundle =
                new ExploreDiscoveryService.IndicatorBundle(List.of(), List.of(), 0, false, 0L);

        List<String> sources = normalizedDefaultSources();
        String sourceWithHits = sources.get(0);
        String sourceWithZeroHits = sources.get(1);

        when(discoveryService.discoverWithLanes(anyString(), anyString(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    ExploreDiscoveryService.LaneConsumer consumer = inv.getArgument(4);
                    consumer.onLane(sourceWithHits, Map.of("count", 5, "phase", "catalog_index"));
                    consumer.onLane(sourceWithZeroHits, Map.of("count", 0, "phase", "catalog_index"));
                    return bundle;
                });
        when(sectorService.buildPresetPreview(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("ok", true, "sector_indicators", List.of()));
        ExploreSectorService.PreparedAnalysis prep =
                new ExploreSectorService.PreparedAnalysis(null, "banking_finance", "otazka", Map.of(), Map.of(), Map.of());
        when(sectorService.prepareAnalysis(any())).thenReturn(prep);
        when(sectorService.finalizeAnalysis(any(), any(), any())).thenReturn(Map.of("ok", true));

        ObjectMapper spyMapper = spy(new ObjectMapper());
        ExploreStreamService streamService = new ExploreStreamService(sectorService, discoveryService, spyMapper);
        streamService.streamSector("banking_finance", "otazka", "CZ", "countries", null, null, null, "sector");

        verify(sectorService, timeout(5_000)).finalizeAnalysis(any(), any(), any());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(spyMapper, timeout(1_000).atLeastOnce()).writeValueAsString(captor.capture());
        ObjectMapper reader = new ObjectMapper();
        List<JsonNode> events = new ArrayList<>();
        for (Object value : captor.getAllValues()) {
            events.add(reader.valueToTree(value));
        }

        JsonNode okEvent = findEvent(events, "source_finished", sourceWithHits);
        assertEquals("ok", okEvent.path("status").asText());
        assertEquals(5, okEvent.path("candidates").asInt());

        JsonNode emptyEvent = findEvent(events, "source_finished", sourceWithZeroHits);
        assertEquals("empty", emptyEvent.path("status").asText(), "zero candidates must be reported as empty, not ok");
        assertEquals(0, emptyEvent.path("candidates").asInt());

        // Every requested source must get a genuine "source_started" up front.
        for (String source : sources) {
            assertTrue(
                    events.stream().anyMatch(e -> "source_started".equals(e.path("event").asText())
                            && source.equals(e.path("source").asText())),
                    "expected source_started for " + source);
        }
    }

    @Test
    void ignoresPerCandidatePreviewPhaseEventsInsteadOfOverwritingARealResult() throws Exception {
        // CatalogDeepSearchService's lane callback fires for THREE phases: "catalog_index" (real
        // per-source lane completion, has "count"), "catalog_sidecar_timeout_fallback" (same,
        // via the sidecar path), and "preview" (fires once PER CANDIDATE during preview
        // verification, no "count" field at all - defaults to 0). Live-verified: without
        // filtering by phase, a source that legitimately found 18 candidates got its
        // "source_finished" overwritten moments later by a "preview" event reporting 0, making a
        // fully successful lane look empty in the UI.
        ExploreSectorService sectorService = mock(ExploreSectorService.class);
        ExploreDiscoveryService discoveryService = mock(ExploreDiscoveryService.class);
        ExploreDiscoveryService.IndicatorBundle bundle =
                new ExploreDiscoveryService.IndicatorBundle(List.of(), List.of(), 0, false, 0L);

        List<String> sources = normalizedDefaultSources();
        String source = sources.get(0);

        when(discoveryService.discoverWithLanes(anyString(), anyString(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    ExploreDiscoveryService.LaneConsumer consumer = inv.getArgument(4);
                    consumer.onLane(source, Map.of("count", 18, "phase", "catalog_index"));
                    // Per-candidate preview events for the same source, arriving afterwards.
                    consumer.onLane(source, Map.of("phase", "preview", "set_id", "SOME_SET_ID"));
                    consumer.onLane(source, Map.of("phase", "preview", "set_id", "ANOTHER_SET_ID"));
                    return bundle;
                });
        when(sectorService.buildPresetPreview(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("ok", true, "sector_indicators", List.of()));
        ExploreSectorService.PreparedAnalysis prep =
                new ExploreSectorService.PreparedAnalysis(null, "banking_finance", "otazka", Map.of(), Map.of(), Map.of());
        when(sectorService.prepareAnalysis(any())).thenReturn(prep);
        when(sectorService.finalizeAnalysis(any(), any(), any())).thenReturn(Map.of("ok", true));

        ObjectMapper spyMapper = spy(new ObjectMapper());
        ExploreStreamService streamService = new ExploreStreamService(sectorService, discoveryService, spyMapper);
        streamService.streamSector("banking_finance", "otazka", "CZ", "countries", null, null, null, "sector");

        verify(sectorService, timeout(5_000)).finalizeAnalysis(any(), any(), any());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(spyMapper, timeout(1_000).atLeastOnce()).writeValueAsString(captor.capture());
        ObjectMapper reader = new ObjectMapper();
        List<JsonNode> events = new ArrayList<>();
        for (Object value : captor.getAllValues()) {
            events.add(reader.valueToTree(value));
        }

        long finishedEventsForSource = events.stream()
                .filter(e -> "source_finished".equals(e.path("event").asText()) && source.equals(e.path("source").asText()))
                .count();
        assertEquals(1, finishedEventsForSource, "preview-phase events must not produce extra source_finished events");

        JsonNode onlyEvent = findEvent(events, "source_finished", source);
        assertEquals("ok", onlyEvent.path("status").asText());
        assertEquals(18, onlyEvent.path("candidates").asInt());
    }

    @Test
    void reportsSourceTimeoutForSourcesThatNeverFinish() throws Exception {
        ExploreSectorService sectorService = mock(ExploreSectorService.class);
        ExploreDiscoveryService discoveryService = mock(ExploreDiscoveryService.class);
        ExploreDiscoveryService.IndicatorBundle bundle =
                new ExploreDiscoveryService.IndicatorBundle(List.of(), List.of(), 0, false, 0L);

        List<String> sources = normalizedDefaultSources();
        String neverFinishes = sources.get(sources.size() - 1);

        when(discoveryService.discoverWithLanes(anyString(), anyString(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    ExploreDiscoveryService.LaneConsumer consumer = inv.getArgument(4);
                    // Every source except the last one finishes - simulating the shared lane wall
                    // budget cancelling one slow lane's future before it ever calls back.
                    for (String source : sources) {
                        if (!source.equals(neverFinishes)) {
                            consumer.onLane(source, Map.of("count", 1, "phase", "catalog_index"));
                        }
                    }
                    return bundle;
                });
        when(sectorService.buildPresetPreview(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("ok", true, "sector_indicators", List.of()));
        ExploreSectorService.PreparedAnalysis prep =
                new ExploreSectorService.PreparedAnalysis(null, "banking_finance", "otazka", Map.of(), Map.of(), Map.of());
        when(sectorService.prepareAnalysis(any())).thenReturn(prep);
        when(sectorService.finalizeAnalysis(any(), any(), any())).thenReturn(Map.of("ok", true));

        ObjectMapper spyMapper = spy(new ObjectMapper());
        ExploreStreamService streamService = new ExploreStreamService(sectorService, discoveryService, spyMapper);
        streamService.streamSector("banking_finance", "otazka", "CZ", "countries", null, null, null, "sector");

        verify(sectorService, timeout(5_000)).finalizeAnalysis(any(), any(), any());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(spyMapper, timeout(1_000).atLeastOnce()).writeValueAsString(captor.capture());
        ObjectMapper reader = new ObjectMapper();
        List<JsonNode> events = new ArrayList<>();
        for (Object value : captor.getAllValues()) {
            events.add(reader.valueToTree(value));
        }

        JsonNode timeoutEvent = findEvent(events, "source_timeout", neverFinishes);
        assertEquals("timeout", timeoutEvent.path("status").asText());

        // A source that DID finish must not also be reported as timed out.
        String finishedSource = sources.get(0);
        assertTrue(
                events.stream().noneMatch(e -> "source_timeout".equals(e.path("event").asText())
                        && finishedSource.equals(e.path("source").asText())),
                "a finished source must not also be reported as source_timeout");
    }

    @Test
    void reportsSkippedInsteadOfTimeoutForCzOnlySourcesOnForeignGeo() throws Exception {
        ExploreSectorService sectorService = mock(ExploreSectorService.class);
        ExploreDiscoveryService discoveryService = mock(ExploreDiscoveryService.class);
        ExploreDiscoveryService.IndicatorBundle bundle = new ExploreDiscoveryService.IndicatorBundle(
                List.of(),
                List.of(),
                0,
                false,
                0L,
                0L,
                Map.of("planned_sources", List.of("eurostat", "imf", "fred", "ecb2", "oecd4", "data360", "worldbank")));

        when(discoveryService.discoverWithLanes(anyString(), anyString(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    ExploreDiscoveryService.LaneConsumer consumer = inv.getArgument(4);
                    for (String source : List.of("eurostat", "imf", "fred", "ecb2", "oecd4", "data360", "worldbank")) {
                        consumer.onLane(source, Map.of("count", 2, "phase", "catalog_index"));
                    }
                    return bundle;
                });
        when(sectorService.buildPresetPreview(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("ok", true, "sector_indicators", List.of()));
        ExploreSectorService.PreparedAnalysis prep = new ExploreSectorService.PreparedAnalysis(
                null,
                "manufacturing",
                "vyroba rakousko",
                Map.of("geo", Map.of("country_codes", List.of("AT"))),
                Map.of(),
                Map.of("country", "AT"));
        when(sectorService.prepareAnalysis(any())).thenReturn(prep);
        when(sectorService.finalizeAnalysis(any(), any(), any())).thenReturn(Map.of("ok", true));

        ObjectMapper spyMapper = spy(new ObjectMapper());
        ExploreStreamService streamService = new ExploreStreamService(sectorService, discoveryService, spyMapper);
        streamService.streamSector("manufacturing", "vyroba rakousko", "AT", "countries", null, null, null, "sector");

        verify(sectorService, timeout(5_000)).finalizeAnalysis(any(), any(), any());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(spyMapper, timeout(1_000).atLeastOnce()).writeValueAsString(captor.capture());
        ObjectMapper reader = new ObjectMapper();
        List<JsonNode> events = new ArrayList<>();
        for (Object value : captor.getAllValues()) {
            events.add(reader.valueToTree(value));
        }

        JsonNode aradSkipped = findEvent(events, "source_skipped", "arad");
        assertEquals("skipped", aradSkipped.path("status").asText());
        assertEquals("cz_only_source", aradSkipped.path("reason").asText());
        assertTrue(
                events.stream().noneMatch(e -> "source_timeout".equals(e.path("event").asText())
                        && "arad".equals(e.path("source").asText())),
                "CZ-only ARAD on Austria must not be painted as timeout");
    }

    /**
     * Mirrors the normalization ExploreStreamService itself applies before dispatch (e.g. "ecb"
     * -&gt; "ecb2") - EXPLORE_DISCOVERY_DEFAULT_SOURCES uses the registry alias, but
     * searchLane's onLane callback reports whatever the query planner actually dispatched, which
     * is already normalized. Asserting against the raw (un-normalized) list would spuriously fail
     * for "ecb" even though the fix behaves correctly - this WAS a real false positive, live
     * verified against /explore/sector/stream before this normalization was added.
     */
    private static List<String> normalizedDefaultSources() {
        return CatalogSourceRegistry.EXPLORE_DISCOVERY_DEFAULT_SOURCES.stream()
                .map(CatalogSourceRegistry::normalizeSearchSource)
                .distinct()
                .toList();
    }

    private static JsonNode findEvent(List<JsonNode> events, String eventName, String source) {
        return events.stream()
                .filter(e -> eventName.equals(e.path("event").asText()) && source.equals(e.path("source").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + eventName + " event found for source " + source));
    }
}
