package cz.bankintel.controller.sources;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.security.AuthRateLimitFilter;
import cz.bankintel.security.CsrfFilter;
import cz.bankintel.security.JwtAuthFilter;
import cz.bankintel.security.JwtService;
import cz.bankintel.sources.arad.AradCatalogService;
import cz.bankintel.sources.arad.AradCatalogWriteService;
import cz.bankintel.sources.arad.AradSetIndicatorsService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Regression test for the ported {@code GET /api/arad/catalog/live-check} endpoint. */
@WebMvcTest(AradCatalogController.class)
@AutoConfigureMockMvc(addFilters = false)
class AradCatalogControllerLiveCheckTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AradCatalogService aradCatalogService;

    @MockitoBean
    private AradSetIndicatorsService aradSetIndicatorsService;

    @MockitoBean
    private AradCatalogWriteService aradCatalogWriteService;

    @MockitoBean
    private AdminAccess adminAccess;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private AuthRateLimitFilter authRateLimitFilter;

    @MockitoBean
    private CsrfFilter csrfFilter;

    @Test
    void liveCheckRequiresAdminAndDelegatesToService() throws Exception {
        when(adminAccess.requireAdmin()).thenReturn(new UserEntity());
        when(aradCatalogService.liveCheck())
                .thenReturn(Map.of(
                        "ok", true,
                        "source", "arad",
                        "status", 200,
                        "elapsed_ms", 42L,
                        "error", ""));

        mockMvc.perform(get("/api/arad/catalog/live-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.source").value("arad"));
    }
}
