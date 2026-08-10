package cz.bankintel.controller.admin;

import cz.bankintel.domain.dto.AdminDtos.SetSubscriberRegistrationCodeRequest;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.service.admin.AdminSubscriberService;
import cz.bankintel.service.admin.BugReportAdminService;
import cz.bankintel.service.audit.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminAccess adminAccess;
    private final AuditLogService auditLogService;
    private final AdminSubscriberService adminSubscriberService;
    private final BugReportAdminService bugReportAdminService;

    @GetMapping("/audit-logs")
    public List<Map<String, Object>> listAuditLogs(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actorUserId,
            @RequestParam(required = false) String targetType) {
        adminAccess.requireAdmin();
        int bounded = Math.min(Math.max(limit, 1), 200);
        return auditLogService.listLogs(bounded, action, actorUserId, targetType);
    }

    @GetMapping("/subscriber-registration-code/status")
    public Map<String, Object> subscriberCodeStatus() {
        adminAccess.requireAdmin();
        return adminSubscriberService.getCodeStatus();
    }

    @PutMapping("/subscriber-registration-code")
    public Map<String, Object> putSubscriberCode(
            @Valid @RequestBody SetSubscriberRegistrationCodeRequest request, HttpServletRequest httpRequest) {
        var admin = adminAccess.requireAdmin();
        return adminSubscriberService.setRegistrationCode(
                request.registrationCode(), admin, httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"));
    }

    @GetMapping("/bug-reports")
    public List<Map<String, Object>> listBugReports(
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit) {
        adminAccess.requireAdmin();
        int bounded = Math.min(Math.max(limit, 1), 200);
        return bugReportAdminService.listReports(status, bounded);
    }

    @GetMapping("/bug-reports/{reportId}/screenshot")
    public ResponseEntity<byte[]> getBugReportScreenshot(@PathVariable String reportId) {
        adminAccess.requireAdmin();
        var file = bugReportAdminService.loadScreenshot(reportId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }

    @PatchMapping("/bug-reports/{reportId}")
    public Map<String, Object> patchBugReport(
            @PathVariable String reportId,
            @Valid @RequestBody cz.bankintel.domain.dto.AdminDtos.BugReportAdminPatch body,
            HttpServletRequest httpRequest) {
        var admin = adminAccess.requireAdmin();
        return bugReportAdminService.patchReport(
                reportId, body, admin, httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"));
    }

    @DeleteMapping("/bug-reports/{reportId}")
    public Map<String, Object> deleteBugReport(@PathVariable String reportId, HttpServletRequest httpRequest) {
        var admin = adminAccess.requireAdmin();
        return bugReportAdminService.deleteReport(
                reportId, admin, httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"));
    }
}
