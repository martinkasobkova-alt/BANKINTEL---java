package cz.bankintel.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.domain.entity.BugReportEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.BugReportRepository;
import cz.bankintel.service.audit.AuditLogService;
import cz.bankintel.service.bugreport.BugReportScreenshotStorage;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * Regression test for the ported {@code DELETE /api/admin/bug-reports/{reportId}} behaviour
 * (admin_bug_reports_routes.py, ř. 147): permanently deletes the report and its screenshot file.
 */
@ExtendWith(MockitoExtension.class)
class BugReportAdminServiceDeleteTest {

    @Mock
    private BugReportRepository bugReportRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private BugReportScreenshotStorage screenshotStorage;

    private BugReportAdminService service;

    private BugReportAdminService newService() {
        return new BugReportAdminService(bugReportRepository, auditLogService, screenshotStorage);
    }

    @Test
    void deleteReportRemovesScreenshotAndLogsAudit() {
        service = newService();
        BugReportEntity report = new BugReportEntity();
        report.setId("bug-1");
        report.setTitle("Chyba v grafu");
        report.setStatus("open");
        report.setScreenshot(Map.of("stored_path", "bug_report_uploads/abc.png"));
        when(bugReportRepository.findById("bug-1")).thenReturn(Optional.of(report));
        UserEntity admin = new UserEntity();
        admin.setId("admin-1");
        admin.setRole("admin");

        Map<String, Object> result = service.deleteReport("bug-1", admin, "127.0.0.1", "test-agent");

        assertEquals(true, result.get("ok"));
        verify(screenshotStorage).deleteScreenshotFile("bug_report_uploads/abc.png");
        verify(bugReportRepository).delete(report);

        ArgumentCaptor<Map<String, Object>> metadataCaptor = mapCaptor();
        verify(auditLogService)
                .logEvent(
                        eq("bug_report_deleted"),
                        eq(admin),
                        eq("bug_report"),
                        eq("bug-1"),
                        metadataCaptor.capture(),
                        eq("127.0.0.1"),
                        eq("test-agent"));
        assertEquals(true, metadataCaptor.getValue().get("had_screenshot"));
        assertEquals(null, metadataCaptor.getValue().get("new_status"));
    }

    @Test
    void deleteReportSkipsScreenshotDeleteWhenNoneStored() {
        service = newService();
        BugReportEntity report = new BugReportEntity();
        report.setId("bug-2");
        report.setTitle("Bez screenshotu");
        report.setStatus("resolved");
        when(bugReportRepository.findById("bug-2")).thenReturn(Optional.of(report));
        UserEntity admin = new UserEntity();
        admin.setId("admin-1");

        service.deleteReport("bug-2", admin, "127.0.0.1", "test-agent");

        verify(screenshotStorage, never()).deleteScreenshotFile(anyString());
        verify(bugReportRepository).delete(report);
    }

    @Test
    void deleteReportThrows404WhenMissing() {
        service = newService();
        when(bugReportRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(
                ResponseStatusException.class,
                () -> service.deleteReport("missing", new UserEntity(), "127.0.0.1", "ua"));
        verify(auditLogService, never()).logEvent(anyString(), any(), anyString(), anyString(), anyMap(), anyString(), anyString());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass((Class) Map.class);
    }
}
