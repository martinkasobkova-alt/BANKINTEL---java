package cz.bankintel.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full-context integration test for the newly ported {@code GET /api}, {@code GET /api/health} and
 * {@code GET /api/health/cors} endpoints (server.py, ř. 474/479/533) — verifies real route
 * registration diagnostics across the whole application, not just a single controller slice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(
        properties = {
            "CATALOG_SEARCH_WARMUP_ON_STARTUP=0",
            "DEV_SEED=false",
            "bankintel.dev.seed=false"
        })
class HealthEndpointsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rootEndpointReturnsNameAndStatus() throws Exception {
        mockMvc.perform(get("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("BankIntel BI"))
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void healthReportsRegisteredCatalogRoutesAndLibraryAvailability() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.catalogs.bis").value(true))
                .andExpect(jsonPath("$.catalogs.imf").value(true))
                .andExpect(jsonPath("$.catalogs.oecd").value(true))
                .andExpect(jsonPath("$.catalogs.csu").value(true))
                .andExpect(jsonPath("$.catalog_deep_search_registered").value(true))
                .andExpect(jsonPath("$.magazine_page_preview_registered").value(true))
                .andExpect(jsonPath("$.pymupdf_available").value(false))
                .andExpect(jsonPath("$.pdfplumber_available").value(false))
                .andExpect(jsonPath("$.text_anchor_bbox_available").value(false));
    }

    @Test
    void healthCorsEchoesOriginAndReportsAllowedOrigins() throws Exception {
        mockMvc.perform(get("/api/health/cors").header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.origin_received").value("http://localhost:5173"))
                .andExpect(jsonPath("$.origin_allowed").value(true))
                .andExpect(jsonPath("$.allowed_origins").isArray());
    }

    // Note: a disallowed-Origin case isn't exercised here — Spring Security's CORS filter
    // rejects such requests with 403 before they reach the controller (unlike FastAPI's
    // permissive CORSMiddleware). That normalization branch is covered without the security
    // filter chain in HealthVersionControllerCorsTest#corsReportsMissingOriginAsNotAllowed.

    @Test
    void existingHealthVersionAndPlatformEndpointsStillWork() throws Exception {
        mockMvc.perform(get("/api/health/version")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mockMvc.perform(get("/health")).andExpect(status().isOk());
    }
}
