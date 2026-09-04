package cz.bankintel.controller.magazine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cz.bankintel.domain.dto.MagazineDtos.MagazineResponse;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.security.ApiKeyAuthFilter;
import cz.bankintel.security.ApiKeyRateLimitFilter;
import cz.bankintel.security.AuthRateLimitFilter;
import cz.bankintel.security.CsrfFilter;
import cz.bankintel.security.JwtAuthFilter;
import cz.bankintel.security.JwtService;
import cz.bankintel.service.magazine.MagazineAiService;
import cz.bankintel.service.magazine.MagazineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Regression test for the ported {@code PATCH /api/magazines/{magazineId}} endpoint (magazines_routes.py, ř. 628). */
@WebMvcTest(MagazinesController.class)
@AutoConfigureMockMvc(addFilters = false)
class MagazinesControllerPatchTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MagazineService magazineService;

    @MockitoBean
    private MagazineAiService magazineAiService;

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

    @MockitoBean
    private ApiKeyAuthFilter apiKeyAuthFilter;

    @MockitoBean
    private ApiKeyRateLimitFilter apiKeyRateLimitFilter;

    @Test
    void patchMagazineRequiresAdminAndReturnsUpdatedMetadata() throws Exception {
        when(adminAccess.requireAdmin()).thenReturn(new UserEntity());
        when(magazineService.patchMagazine(eq("mag-1"), any()))
                .thenReturn(new MagazineResponse(
                        "mag-1", "Nový název", "novy-nazev", "Nový popis", "2024-01-01T00:00:00Z",
                        "2024-01-02T00:00:00Z", 3, 2));

        String body =
                """
                {"title": "Nový název", "slug": "novy-nazev", "description": "Nový popis"}
                """;

        mockMvc.perform(patch("/api/magazines/mag-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("mag-1"))
                .andExpect(jsonPath("$.title").value("Nový název"))
                .andExpect(jsonPath("$.slug").value("novy-nazev"));

        verify(adminAccess).requireAdmin();
    }

    @Test
    void patchMagazineRejectsBlankTitle() throws Exception {
        String body = """
                {"title": "", "description": "x"}
                """;

        mockMvc.perform(patch("/api/magazines/mag-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
