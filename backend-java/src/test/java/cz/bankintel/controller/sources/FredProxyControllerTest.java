package cz.bankintel.controller.sources;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cz.bankintel.sources.fred.FredCatalogService;
import cz.bankintel.security.AuthRateLimitFilter;
import cz.bankintel.security.CsrfFilter;
import cz.bankintel.security.JwtAuthFilter;
import cz.bankintel.security.JwtService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FredProxyController.class)
@AutoConfigureMockMvc(addFilters = false)
class FredProxyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FredCatalogService fredCatalogService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private AuthRateLimitFilter authRateLimitFilter;

    @MockitoBean
    private CsrfFilter csrfFilter;

    @Test
    void metaEndpointReturnsMappedEndpoints() throws Exception {
        when(fredCatalogService.proxyMeta())
                .thenReturn(Map.of(
                        "external_root", "https://api.stlouisfed.org/fred",
                        "fred_api_key_configured", false,
                        "mapped_endpoints", List.of("GET /api/fred/search")));

        mockMvc.perform(get("/api/fred/_meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.external_root").exists())
                .andExpect(jsonPath("$.mapped_endpoints").isArray());
    }

    @Test
    void searchReturns503WhenKeyMissing() throws Exception {
        when(fredCatalogService.hasApiKey()).thenReturn(false);
        when(fredCatalogService.missingKeyPayload())
                .thenReturn(Map.of("error", "FRED_API_KEY is missing on backend", "code", "FRED_API_KEY_MISSING"));

        mockMvc.perform(get("/api/fred/search").param("q", "gdp"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FRED_API_KEY_MISSING"));
    }

    @Test
    void categoryChildrenProxiesWhenConfigured() throws Exception {
        when(fredCatalogService.hasApiKey()).thenReturn(true);
        when(fredCatalogService.getCategoryChildren(anyInt())).thenReturn(Map.of("categories", List.of()));

        mockMvc.perform(get("/api/fred/category/10/children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").isArray());
    }

    @Test
    void seriesObservationsRequiresSeriesId() throws Exception {
        when(fredCatalogService.hasApiKey()).thenReturn(true);
        when(fredCatalogService.getSeriesObservations(anyString(), anyInt()))
                .thenReturn(Map.of("observations", List.of()));

        mockMvc.perform(get("/api/fred/series/GDP/observations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observations").isArray());
    }
}
