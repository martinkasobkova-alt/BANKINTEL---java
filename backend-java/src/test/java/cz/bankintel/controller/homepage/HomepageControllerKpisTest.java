package cz.bankintel.controller.homepage;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.security.AuthRateLimitFilter;
import cz.bankintel.security.CsrfFilter;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.security.JwtAuthFilter;
import cz.bankintel.security.JwtService;
import cz.bankintel.security.RoleGuard;
import cz.bankintel.service.homepage.HomepageAiCommentaryService;
import cz.bankintel.service.homepage.HomepageHeadlineKpiService;
import cz.bankintel.service.homepage.HomepageService;
import cz.bankintel.service.homepage.HomepageWidgetOpsService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Regression test for the ported {@code PUT /api/homepage/kpis} endpoint (homepage_routes.py, ř. 1128). */
@WebMvcTest(HomepageController.class)
@AutoConfigureMockMvc(addFilters = false)
class HomepageControllerKpisTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HomepageService homepageService;

    @MockitoBean
    private HomepageWidgetOpsService homepageWidgetOpsService;

    @MockitoBean
    private HomepageHeadlineKpiService homepageHeadlineKpiService;

    @MockitoBean
    private HomepageAiCommentaryService homepageAiCommentaryService;

    @MockitoBean
    private CurrentUser currentUser;

    @MockitoBean
    private RoleGuard roleGuard;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private AuthRateLimitFilter authRateLimitFilter;

    @MockitoBean
    private CsrfFilter csrfFilter;

    @Test
    void updateKpisRequiresEditorAndDelegatesToService() throws Exception {
        UserEntity editor = new UserEntity();
        editor.setId("user-1");
        editor.setRole("editor");
        when(currentUser.requireUserEntity()).thenReturn(editor);
        when(homepageService.updateHeadlineKpis(anyList()))
                .thenReturn(Map.of(
                        "ok", true,
                        "kpis", List.of(Map.of("id", "kpi-1", "title", "HDP", "type", "arad_view", "config", Map.of()))));

        String body =
                """
                {"kpis": [{"title": "HDP", "type": "arad_view", "config": {}}]}
                """;

        mockMvc.perform(put("/api/homepage/kpis").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.kpis[0].id").value("kpi-1"));
    }
}
