package cz.bankintel.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cz.bankintel.config.BankIntelProperties;
import cz.bankintel.service.platform.BankIntelMaintenanceService;
import cz.bankintel.service.platform.MirrorDataHealthService;
import cz.bankintel.security.ApiKeyAuthFilter;
import cz.bankintel.security.ApiKeyRateLimitFilter;
import cz.bankintel.security.AuthRateLimitFilter;
import cz.bankintel.security.CsrfFilter;
import cz.bankintel.security.JwtAuthFilter;
import cz.bankintel.security.JwtService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthVersionController.class)
@AutoConfigureMockMvc(addFilters = false)
class HealthVersionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BankIntelProperties properties;

    @MockitoBean
    private MirrorDataHealthService mirrorDataHealthService;

    @MockitoBean
    private BankIntelMaintenanceService maintenanceService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private AuthRateLimitFilter authRateLimitFilter;

    @MockitoBean
    private CsrfFilter csrfFilter;

    @MockitoBean
    private ApiKeyAuthFilter apiKeyAuthFilter;

    @MockitoBean
    private ApiKeyRateLimitFilter apiKeyRateLimitFilter;

    @Test
    void versionReturnsOkAndStartedAt() throws Exception {
        when(properties.cookie()).thenReturn(new BankIntelProperties.Cookie(false, "Lax", ""));

        mockMvc.perform(get("/api/health/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.started_at").exists())
                .andExpect(jsonPath("$.auth_cookie_policy.samesite").value("Lax"));
    }

    @Test
    void platformMergesMirrorHealth() throws Exception {
        when(mirrorDataHealthService.buildHealthReport()).thenReturn(Map.of("postgres", "ok"));
        when(maintenanceService.maintenanceEnabled()).thenReturn(false);

        mockMvc.perform(get("/api/health/platform"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.postgres").value("ok"))
                .andExpect(jsonPath("$.maintenance_enabled").value(false));
    }
}
