package cz.bankintel.controller.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.security.AuthRateLimitFilter;
import cz.bankintel.security.CsrfFilter;
import cz.bankintel.security.JwtAuthFilter;
import cz.bankintel.security.JwtService;
import cz.bankintel.service.admin.AdminSubscriberService;
import cz.bankintel.service.admin.BugReportAdminService;
import cz.bankintel.service.audit.AuditLogService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Regression test for the ported {@code DELETE /api/admin/bug-reports/{reportId}} endpoint. */
@WebMvcTest(AdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminControllerDeleteBugReportTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminAccess adminAccess;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private AdminSubscriberService adminSubscriberService;

    @MockitoBean
    private BugReportAdminService bugReportAdminService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private AuthRateLimitFilter authRateLimitFilter;

    @MockitoBean
    private CsrfFilter csrfFilter;

    @Test
    void deleteBugReportRequiresAdminAndReturnsOk() throws Exception {
        when(adminAccess.requireAdmin()).thenReturn(new UserEntity());
        when(bugReportAdminService.deleteReport(anyString(), any(), any(), any())).thenReturn(Map.of("ok", true));

        mockMvc.perform(delete("/api/admin/bug-reports/bug-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }
}
