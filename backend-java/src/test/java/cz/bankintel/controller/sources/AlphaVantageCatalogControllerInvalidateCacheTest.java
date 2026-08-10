package cz.bankintel.controller.sources;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cz.bankintel.security.AuthRateLimitFilter;
import cz.bankintel.security.CsrfFilter;
import cz.bankintel.security.JwtAuthFilter;
import cz.bankintel.security.JwtService;
import cz.bankintel.sources.alphavantage.AlphaVantageCatalogService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Regression test for the ported {@code POST /api/alphavantage/catalog/cache/invalidate} endpoint. */
@WebMvcTest(AlphaVantageCatalogController.class)
@AutoConfigureMockMvc(addFilters = false)
class AlphaVantageCatalogControllerInvalidateCacheTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlphaVantageCatalogService alphaVantageCatalogService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private AuthRateLimitFilter authRateLimitFilter;

    @MockitoBean
    private CsrfFilter csrfFilter;

    @Test
    void invalidateCacheDelegatesToServiceAndReturnsPayload() throws Exception {
        when(alphaVantageCatalogService.invalidateCache(any()))
                .thenReturn(Map.of("ok", true, "symbol", "AAPL", "function", "TIME_SERIES_DAILY"));

        mockMvc.perform(
                        post("/api/alphavantage/catalog/cache/invalidate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"symbol\":\"aapl\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.symbol").value("AAPL"));
    }

    @Test
    void invalidateCacheAllowsEmptyBody() throws Exception {
        when(alphaVantageCatalogService.invalidateCache(any())).thenReturn(Map.of("ok", true, "symbol", "*"));

        mockMvc.perform(post("/api/alphavantage/catalog/cache/invalidate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("*"));
    }
}
