package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.explore.ExploreDtos.ExploreSectorRequest;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.service.research.WebResearchService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

class ExploreSectorServiceFallbackObservabilityTest {

    @Test
    void generalFallbackPreservesDeepSearchProfileAndReportsItsOwnOutcome() {
        ExploreDiscoveryCache cache = mock(ExploreDiscoveryCache.class);
        when(cache.buildKey(anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn("stable-key");
        when(cache.get("fallback:stable-key"))
                .thenReturn(Optional.of(new ExploreDiscoveryCache.CachedEntry(
                        List.of(Map.of(
                                "source", "eurostat",
                                "dataset_id", "fallback-set",
                                "indicator_name", "Fallback indicator")),
                        List.of(),
                        1,
                        System.currentTimeMillis(),
                        23L)));

        ExploreSectorService service = new ExploreSectorService(
                mock(ExploreGeoCatalog.class),
                mock(ExploreGeoResolver.class),
                mock(ExploreQueryUnderstandingService.class),
                mock(OpenAiClient.class),
                new ObjectMapper(),
                mock(ExploreDiscoveryService.class),
                mock(ExplorePresetPreviewService.class),
                cache,
                mock(Environment.class),
                mock(WebResearchService.class));
        ExploreSectorService.PreparedAnalysis prep = new ExploreSectorService.PreparedAnalysis(
                null,
                "banking",
                "question",
                Map.of(),
                Map.of("sector", "banking"),
                Map.of(),
                11L);
        ExploreDiscoveryService.IndicatorBundle emptyDiscovery = new ExploreDiscoveryService.IndicatorBundle(
                List.of(),
                List.of(),
                0,
                false,
                101L,
                101L,
                Map.of(
                        "retrieval_wall_ms", 77L,
                        "candidate_count_retrieved", 12,
                        "preview_initial_ms", 9L,
                        "validator_ms", 4L));
        ExploreSectorRequest request = new ExploreSectorRequest(
                "banking",
                "question",
                "",
                "",
                "",
                null,
                null,
                "sector",
                false,
                false,
                false,
                false,
                List.of(),
                "strict_private",
                "auto",
                null,
                true);

        Map<String, Object> result = service.finalizeAnalysis(prep, emptyDiscovery, request);
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) result.get("performance_profile");

        assertEquals("catalog_deep_search_with_general_fallback", result.get("discovery_source"));
        assertEquals("catalog_discovery_empty", result.get("discovery_fallback_reason"));
        assertFalse((Boolean) result.get("cache_hit"), "cached fallback must not hide the uncached discovery pass");
        assertEquals(77L, profile.get("retrieval_wall_ms"));
        assertEquals(12, profile.get("candidate_count_retrieved"));
        assertEquals(9L, profile.get("preview_initial_ms"));
        assertEquals(4L, profile.get("validator_ms"));
        assertEquals(true, profile.get("fallback_used"));
        assertEquals(true, profile.get("fallback_cache_hit"));
        assertEquals(1, profile.get("fallback_candidate_count"));
        assertEquals(0, profile.get("fallback_preview_count"));
        assertEquals("not_run", profile.get("fallback_validator_outcome"));
        assertEquals("suggestions_generated", profile.get("fallback_terminal_status"));
        assertEquals(List.of("eurostat"), profile.get("fallback_source_routing"));
    }

    @Test
    void isolatedColdPathBypassesFallbackCacheToo() {
        ExploreDiscoveryCache cache = mock(ExploreDiscoveryCache.class);
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        when(openAiClient.isConfigured()).thenReturn(false);

        ExploreSectorService service = new ExploreSectorService(
                mock(ExploreGeoCatalog.class),
                mock(ExploreGeoResolver.class),
                mock(ExploreQueryUnderstandingService.class),
                openAiClient,
                new ObjectMapper(),
                mock(ExploreDiscoveryService.class),
                mock(ExplorePresetPreviewService.class),
                cache,
                environment,
                mock(WebResearchService.class));
        ExploreSectorService.PreparedAnalysis prep = new ExploreSectorService.PreparedAnalysis(
                null, "banking", "question", Map.of(), Map.of("sector", "banking"), Map.of(), 1L);
        ExploreDiscoveryService.IndicatorBundle emptyDiscovery = new ExploreDiscoveryService.IndicatorBundle(
                List.of(), List.of(), 0, false, 10L, 10L, Map.of());
        ExploreSectorRequest request = new ExploreSectorRequest(
                "banking", "question", "", "", "", null, null, "sector", false, false, true, false,
                List.of(), "strict_private", "auto", null, true);

        Map<String, Object> result = service.finalizeAnalysis(prep, emptyDiscovery, request);

        verify(cache, never()).get(anyString());
        verify(cache, never()).put(anyString(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
        assertFalse((Boolean) result.get("cache_hit"));
    }
}
