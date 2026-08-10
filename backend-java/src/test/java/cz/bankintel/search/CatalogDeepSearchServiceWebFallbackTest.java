package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.search.model.GeoIntentSnapshot;
import cz.bankintel.search.model.SearchPlan;
import cz.bankintel.search.scoring.CatalogScoringPipeline;
import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarIndex;
import cz.bankintel.service.research.WebResearchService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Contract tests for the classic-search web-research fallback wired into {@link
 * CatalogDeepSearchService} (see {@code applyWebFallback}). Deliberately never triggers a real
 * OpenAI call - {@link WebResearchService} is mocked - and asserts only the response-contract
 * behaviour: the fields are always present, the web call fires only on {@code no_valid_result},
 * and Manager Explorer discovery ({@code manager_discovery=true}) is skipped because it runs its
 * own web fallback in {@code ExploreSectorService}.
 */
@ExtendWith(MockitoExtension.class)
class CatalogDeepSearchServiceWebFallbackTest {

    @Mock
    private CatalogQueryPlanner queryPlanner;

    @Mock
    private CatalogIndexStore indexStore;

    @Mock
    private CatalogDeepSearchPreviewService previewService;

    @Mock
    private CatalogSearchAnswerService searchAnswerService;

    @Mock
    private CatalogCommoditySearch commoditySearch;

    @Mock
    private CatalogScoringPipeline scoringPipeline;

    @Mock
    private CatalogAiDataResolver aiDataResolver;

    @Mock
    private CatalogStructuredSemanticCompatibilityService structuredCompatibilityService;

    @Mock
    private SearchCatalogSidecarIndex sidecarIndex;

    @Mock
    private WebResearchService webResearchService;

    private CatalogDeepSearchService service;

    @BeforeEach
    void setUp() {
        service = new CatalogDeepSearchService(
                queryPlanner,
                indexStore,
                previewService,
                searchAnswerService,
                commoditySearch,
                scoringPipeline,
                aiDataResolver,
                new CatalogSourceYieldTelemetry(),
                structuredCompatibilityService,
                sidecarIndex,
                webResearchService);
    }

    /**
     * Primes the mocks so the pipeline reaches an empty (no_valid_result) outcome for any query.
     * Stubs are {@code lenient()} because the normal and Manager-discovery code paths exercise a
     * slightly different subset of them (e.g. the commodity-fallback check), and strict Mockito
     * would otherwise flag an unused stub in whichever path skips it.
     */
    private void primeEmptyCatalogResult(String query) {
        SearchPlan plan = new SearchPlan(
                List.of(), List.of(query), List.of(), GeoIntentSnapshot.empty(), "", "", "local", List.of());
        lenient().when(queryPlanner.planTyped(eq(query), any(), anyBoolean())).thenReturn(plan);
        lenient().when(indexStore.ftsDbAvailable()).thenReturn(true);
        lenient().when(commoditySearch.commodityQueryAllowed(query)).thenReturn(false);
        lenient().when(previewService.verifyCandidates(any(), eq(query), any(), anyBoolean()))
                .thenReturn(CatalogDeepSearchPreviewService.PreviewPhaseResult.empty());
        lenient().when(searchAnswerService.composeStory(eq(query), any(), any(), anyBoolean()))
                .thenReturn(Map.of("headline_cz", "Shrnutí hledání", "answer_cz", "Nic nenalezeno"));
    }

    @Test
    void noValidResultTriggersWebFallbackAndMapsFindings() {
        String query = "zcela neexistujici ekonomicky dotaz xyz";
        primeEmptyCatalogResult(query);
        when(webResearchService.researchCatalogFallback(eq(query), any()))
                .thenReturn(Map.of("findings", List.of(
                        Map.of("title", "Podíl Prahy na HDP", "url", "https://czso.cz/x", "source_tier", "official"))));

        Map<String, Object> result = service.deepSearch(Map.of("query", query, "use_ai", false));

        assertEquals("no_valid_result", result.get("status"));
        assertEquals("found", result.get("web_research_status"));
        assertEquals(1, result.get("web_sources_total"));
        assertEquals(
                1,
                ((List<?>) result.get("web_sources")).size(),
                "web_sources must carry the mapped findings");
    }

    @Test
    void managerDiscoverySkipsWebFallback() {
        String query = "zcela neexistujici ekonomicky dotaz xyz";
        primeEmptyCatalogResult(query);

        Map<String, Object> result = service.deepSearch(Map.of(
                "query", query, "use_ai", false, "manager_discovery", true));

        assertEquals("no_valid_result", result.get("status"));
        // Manager Explorer runs its OWN web fallback (ExploreSectorService) - the classic-search one
        // must stay dormant here, leaving the stable default contract untouched.
        assertEquals("not_attempted", result.get("web_research_status"));
        assertEquals(0, result.get("web_sources_total"));
        assertEquals(List.of(), result.get("web_sources"));
        verify(webResearchService, never()).researchCatalogFallback(any(), any());
    }
}
