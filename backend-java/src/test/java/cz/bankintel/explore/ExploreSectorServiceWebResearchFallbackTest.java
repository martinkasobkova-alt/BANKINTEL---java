package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * The Manager Explorer sector report's web-research fallback ({@code
 * WebResearchService#researchSectorContext}) must run ONLY when the internal catalog found
 * nothing at all - confirmed live: "Jakým významem se Praha podílí na HDP Česka?" has no matching
 * ČSÚ indicator at all, so this is what lets that question get an answer. A report that already
 * has real catalog indicators must never pay the extra web_search latency/cost, and must never
 * show web-sourced content for a question the catalog already answered.
 */
class ExploreSectorServiceWebResearchFallbackTest {

    private static ExploreSectorRequest request() {
        return new ExploreSectorRequest(
                "banking", "question", "", List.of(), "", "", null, null, "sector", false, false, false, false,
                List.of(), "strict_private", "auto", null, true);
    }

    private static ExploreSectorService.PreparedAnalysis prep() {
        return new ExploreSectorService.PreparedAnalysis(
                null,
                "banking",
                "question",
                Map.of("manager_question", "question", "geo", Map.of()),
                Map.of("sector", "banking"),
                Map.of(),
                1L);
    }

    private static ExploreSectorService serviceWith(WebResearchService webResearchService) {
        return new ExploreSectorService(
                mock(ExploreGeoCatalog.class),
                mock(ExploreGeoResolver.class),
                mock(ExploreQueryUnderstandingService.class),
                mock(OpenAiClient.class),
                new ObjectMapper(),
                mock(ExploreDiscoveryService.class),
                mock(ExplorePresetPreviewService.class),
                mock(ExploreDiscoveryCache.class),
                mock(Environment.class),
                webResearchService);
    }

    @Test
    void neverCallsWebResearchWhenCatalogAlreadyFoundIndicators() {
        WebResearchService webResearchService = mock(WebResearchService.class);
        ExploreSectorService service = serviceWith(webResearchService);
        ExploreDiscoveryService.IndicatorBundle nonEmptyDiscovery = new ExploreDiscoveryService.IndicatorBundle(
                List.of(Map.of("source", "eurostat", "dataset_id", "x", "indicator_name", "X")),
                List.of(),
                1,
                false,
                10L,
                10L,
                Map.of());

        Map<String, Object> result = service.finalizeAnalysis(prep(), nonEmptyDiscovery, request());

        verify(webResearchService, never()).researchSectorContext(anyString(), anyMap());
        assertEquals("not_attempted", result.get("web_research_status"));
        assertEquals(List.of(), result.get("web_sources"));
    }

    @Test
    void callsWebResearchAndSurfacesFindingsWhenCatalogIsCompletelyEmpty() {
        WebResearchService webResearchService = mock(WebResearchService.class);
        List<Map<String, Object>> findings =
                List.of(Map.of("title", "Podíl Prahy na HDP", "url", "https://ec.europa.eu/x"));
        when(webResearchService.researchSectorContext(anyString(), anyMap()))
                .thenReturn(Map.of("findings", findings, "answer_cz", "Nalezeno."));
        ExploreSectorService service = serviceWith(webResearchService);
        ExploreDiscoveryService.IndicatorBundle emptyDiscovery = new ExploreDiscoveryService.IndicatorBundle(
                List.of(), List.of(), 0, false, 10L, 10L, Map.of());

        Map<String, Object> result = service.finalizeAnalysis(prep(), emptyDiscovery, request());

        verify(webResearchService).researchSectorContext(anyString(), anyMap());
        assertEquals("found", result.get("web_research_status"));
        assertEquals(findings, result.get("web_sources"));
        assertEquals(1, result.get("web_sources_total"));
    }

    @Test
    void marksStatusFailedWithoutThrowingWhenWebResearchErrors() {
        WebResearchService webResearchService = mock(WebResearchService.class);
        when(webResearchService.researchSectorContext(anyString(), anyMap()))
                .thenThrow(new RuntimeException("web_search unavailable"));
        ExploreSectorService service = serviceWith(webResearchService);
        ExploreDiscoveryService.IndicatorBundle emptyDiscovery = new ExploreDiscoveryService.IndicatorBundle(
                List.of(), List.of(), 0, false, 10L, 10L, Map.of());

        Map<String, Object> result = service.finalizeAnalysis(prep(), emptyDiscovery, request());

        assertEquals("failed", result.get("web_research_status"));
        assertEquals(List.of(), result.get("web_sources"));
    }
}
