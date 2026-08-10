package cz.bankintel.controller.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cz.bankintel.search.CatalogClassicSearchService;
import cz.bankintel.search.CatalogDeepSearchService;
import cz.bankintel.search.CatalogDownloadService;
import cz.bankintel.search.CatalogFollowupService;
import cz.bankintel.search.CatalogMultiSearchService;
import cz.bankintel.search.CatalogPreviewService;
import cz.bankintel.search.CatalogRelatedSeriesService;
import cz.bankintel.search.CatalogSearchStreamService;
import cz.bankintel.search.CatalogSeriesExplainService;
import cz.bankintel.search.CatalogSourceRouteService;
import cz.bankintel.search.CatalogStatusService;
import cz.bankintel.search.CatalogSuggestService;
import cz.bankintel.search.CatalogWarmupService;
import cz.bankintel.search.ChartDataQualityService;
import cz.bankintel.search.analytics.CatalogAnalyticsService;
import cz.bankintel.search.forecast.CatalogForecastService;
import cz.bankintel.search.v2.evaluation.SearchV2Evaluator;
import cz.bankintel.search.v2.observability.SearchV2ShadowStore;
import cz.bankintel.search.v2.observability.SearchV2TraceStore;
import cz.bankintel.search.v2.orchestration.SearchV2FeatureFlags;
import cz.bankintel.search.v2.orchestration.SearchV2Service;
import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarIndex;
import cz.bankintel.search.v2.vector.SearchVectorIndexBuilder;
import cz.bankintel.security.AuthRateLimitFilter;
import cz.bankintel.security.CsrfFilter;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.security.JwtAuthFilter;
import cz.bankintel.security.JwtService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CatalogController.class)
@AutoConfigureMockMvc(addFilters = false)
class CatalogControllerDeepSearchValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogSuggestService catalogSuggestService;
    @MockitoBean
    private CatalogClassicSearchService catalogClassicSearchService;
    @MockitoBean
    private CatalogPreviewService catalogPreviewService;
    @MockitoBean
    private CatalogStatusService catalogStatusService;
    @MockitoBean
    private CatalogDeepSearchService catalogDeepSearchService;
    @MockitoBean
    private CatalogMultiSearchService catalogMultiSearchService;
    @MockitoBean
    private CatalogSourceRouteService catalogSourceRouteService;
    @MockitoBean
    private CatalogSearchStreamService catalogSearchStreamService;
    @MockitoBean
    private CatalogFollowupService catalogFollowupService;
    @MockitoBean
    private CatalogWarmupService catalogWarmupService;
    @MockitoBean
    private ChartDataQualityService chartDataQualityService;
    @MockitoBean
    private CatalogDownloadService catalogDownloadService;
    @MockitoBean
    private CatalogSeriesExplainService catalogSeriesExplainService;
    @MockitoBean
    private CatalogRelatedSeriesService catalogRelatedSeriesService;
    @MockitoBean
    private CatalogForecastService catalogForecastService;
    @MockitoBean
    private CatalogAnalyticsService catalogAnalyticsService;
    @MockitoBean
    private SearchV2FeatureFlags searchV2FeatureFlags;
    @MockitoBean
    private SearchV2Service searchV2Service;
    @MockitoBean
    private SearchV2Evaluator searchV2Evaluator;
    @MockitoBean
    private SearchV2TraceStore searchV2TraceStore;
    @MockitoBean
    private SearchV2ShadowStore searchV2ShadowStore;
    @MockitoBean
    private SearchCatalogSidecarIndex searchCatalogSidecarIndex;
    @MockitoBean
    private SearchVectorIndexBuilder searchVectorIndexBuilder;
    @MockitoBean
    private CurrentUser currentUser;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private AuthRateLimitFilter authRateLimitFilter;

    @MockitoBean
    private CsrfFilter csrfFilter;

    @Test
    void deepSearchStreamRejectsShortQuery() throws Exception {
        mockMvc.perform(get("/api/catalog/deep-search/stream").param("q", "a"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deepSearchPostReturnsServicePayloadForShortQuery() throws Exception {
        when(catalogDeepSearchService.deepSearch(any())).thenReturn(new java.util.LinkedHashMap<>(Map.of("message", "Dotaz je příliš krátký.")));
        when(catalogFollowupService.bootstrapConversation(any(), any(), any(), any()))
                .thenReturn(new java.util.LinkedHashMap<>());

        mockMvc.perform(
                        post("/api/catalog/deep-search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"q\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Dotaz je příliš krátký."));
    }

    @Test
    void multiSearchStreamRejectsEmptySources() throws Exception {
        mockMvc.perform(get("/api/catalog/search/multi/stream").param("q", "inflation"))
                .andExpect(status().isUnprocessableEntity());
    }
}
