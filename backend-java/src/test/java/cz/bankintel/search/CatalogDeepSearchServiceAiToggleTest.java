package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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

@ExtendWith(MockitoExtension.class)
class CatalogDeepSearchServiceAiToggleTest {

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

    @Test
    void payloadUseAiFalseIsPassedToPlannerAndReported() {
        SearchPlan localPlan =
                new SearchPlan(List.of(), List.of("inflace"), List.of(), GeoIntentSnapshot.empty(), "", "", "local", List.of());
        when(queryPlanner.planTyped(eq("inflace Cesko"), eq(List.of()), eq(false))).thenReturn(localPlan);
        when(indexStore.ftsDbAvailable()).thenReturn(true);
        when(commoditySearch.commodityQueryAllowed("inflace Cesko")).thenReturn(false);
        // Resolver is gated to the AI path, so use_ai:false must NOT invoke it.
        when(previewService.verifyCandidates(any(), eq("inflace Cesko"), any(), anyBoolean()))
                .thenReturn(CatalogDeepSearchPreviewService.PreviewPhaseResult.empty());
        when(searchAnswerService.composeStory(eq("inflace Cesko"), eq(List.of()), eq(List.of()), eq(true)))
                .thenReturn(Map.of("headline_cz", "Shrnutí hledání", "answer_cz", "Shrnuti hledani"));

        Map<String, Object> result = service.deepSearch(Map.of("query", "inflace Cesko", "use_ai", false));

        assertEquals(false, result.get("ai_requested"));
        assertEquals(false, result.get("ai_active"));
        assertEquals(false, result.get("ai_plan_used"));
        verify(queryPlanner).planTyped("inflace Cesko", List.of(), false);
    }

    @Test
    void explicitSingleSourceDoesNotInjectCommodityFallback() {
        SearchPlan localPlan = new SearchPlan(
                List.of("fred"),
                List.of("cena ropy"),
                List.of("fred", "commodities"),
                GeoIntentSnapshot.empty(),
                "energy",
                "",
                "local",
                List.of("cena ropy"));
        when(queryPlanner.planTyped(eq("cena ropy"), eq(List.of("fred")), eq(false))).thenReturn(localPlan);
        when(indexStore.ftsDbAvailable()).thenReturn(true);
        when(indexStore.sidecarSearchHits(eq("fred"), eq("cena ropy"), anyInt())).thenReturn(List.of());
        when(indexStore.searchHits(eq("fred"), eq("cena ropy"), anyInt())).thenReturn(List.of());
        when(scoringPipeline.scoreAndRankAsMaps(eq("fred"), eq("cena ropy"), eq(List.of()), anyInt()))
                .thenReturn(List.of());
        when(commoditySearch.commodityQueryAllowed("cena ropy")).thenReturn(true);
        when(previewService.verifyCandidates(any(), eq("cena ropy"), any(), anyBoolean()))
                .thenReturn(CatalogDeepSearchPreviewService.PreviewPhaseResult.empty());
        when(searchAnswerService.composeStory(eq("cena ropy"), eq(List.of()), eq(List.of()), eq(true)))
                .thenReturn(Map.of("headline_cz", "Shrnuti", "answer_cz", "Shrnuti"));

        service.deepSearch(Map.of("query", "cena ropy", "sources", List.of("fred"), "use_ai", false));

        verify(commoditySearch, never()).searchHits(any(), anyInt());
    }

    // ETAPA 5: "use_ai_story": false (set by ExploreDiscoveryService's payload, and already-but-until-now
    // silently set by CatalogSearchStreamService.streamDeepSearch) must actually suppress the LLM story
    // call - previously this request field was never read here at all, so attachSearchAnswer() always
    // called the 3-arg composeStory(...), which hardcodes useAi=true regardless of the request.
    @Test
    void payloadUseAiStoryFalseSuppressesLlmStoryComposition() {
        SearchPlan localPlan =
                new SearchPlan(List.of(), List.of("inflace"), List.of(), GeoIntentSnapshot.empty(), "", "", "local", List.of());
        when(queryPlanner.planTyped(eq("inflace Cesko"), eq(List.of()), eq(false))).thenReturn(localPlan);
        when(indexStore.ftsDbAvailable()).thenReturn(true);
        when(commoditySearch.commodityQueryAllowed("inflace Cesko")).thenReturn(false);
        when(previewService.verifyCandidates(any(), eq("inflace Cesko"), any(), anyBoolean()))
                .thenReturn(CatalogDeepSearchPreviewService.PreviewPhaseResult.empty());
        when(searchAnswerService.composeStory(eq("inflace Cesko"), eq(List.of()), eq(List.of()), eq(false)))
                .thenReturn(Map.of("headline_cz", "Shrnutí hledání", "answer_cz", "Deterministicky text"));

        Map<String, Object> result =
                service.deepSearch(Map.of("query", "inflace Cesko", "use_ai", false, "use_ai_story", false));

        assertEquals("Deterministicky text", result.get("answer"));
        verify(searchAnswerService).composeStory(eq("inflace Cesko"), eq(List.of()), eq(List.of()), eq(false));
        verify(searchAnswerService, never()).composeStory(any(), any(), any(), eq(true));
    }
}
