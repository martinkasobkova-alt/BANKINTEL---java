package cz.bankintel.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cz.bankintel.config.BankIntelProperties;
import cz.bankintel.security.ApiKeyAuthFilter;
import cz.bankintel.security.ApiKeyRateLimitFilter;
import cz.bankintel.security.AuthRateLimitFilter;
import cz.bankintel.security.CsrfFilter;
import cz.bankintel.security.JwtAuthFilter;
import cz.bankintel.security.JwtService;
import cz.bankintel.service.platform.BankIntelMaintenanceService;
import cz.bankintel.service.platform.MirrorDataHealthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test for {@code GET /api/health/cors} normalization (port of {@code
 * normalize_cors_origin_string}, backend/security_config.py): trims BOM/quotes/whitespace and
 * trailing slashes before comparing against the allow-list.
 */
@WebMvcTest(HealthVersionController.class)
@AutoConfigureMockMvc(addFilters = false)
class HealthVersionControllerCorsTest {

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
    void corsNormalizesTrailingSlashAndCaseBeforeComparing() throws Exception {
        when(properties.cors()).thenReturn(new BankIntelProperties.Cors("https://App.Example.com/"));

        mockMvc.perform(get("/api/health/cors").header("Origin", "https://app.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.origin_received").value("https://app.example.com"))
                .andExpect(jsonPath("$.origin_allowed").value(true))
                .andExpect(jsonPath("$.allowed_origins[0]").value("https://app.example.com"));
    }

    @Test
    void corsReportsMissingOriginAsNotAllowed() throws Exception {
        when(properties.cors()).thenReturn(new BankIntelProperties.Cors("https://app.example.com"));

        mockMvc.perform(get("/api/health/cors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin_received").value(""))
                .andExpect(jsonPath("$.origin_allowed").value(false));
    }

    @Test
    void corsReportsDisallowedOriginAsNotAllowed() throws Exception {
        when(properties.cors()).thenReturn(new BankIntelProperties.Cors("https://app.example.com"));

        mockMvc.perform(get("/api/health/cors").header("Origin", "https://evil.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.origin_received").value("https://evil.example.com"))
                .andExpect(jsonPath("$.origin_allowed").value(false));
    }
}
