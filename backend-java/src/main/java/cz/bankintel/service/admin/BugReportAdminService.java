package cz.bankintel.service.admin;

import cz.bankintel.domain.dto.AdminDtos.BugReportAdminPatch;
import cz.bankintel.domain.entity.BugReportEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.BugReportRepository;
import cz.bankintel.service.audit.AuditLogService;
import cz.bankintel.service.bugreport.BugReportScreenshotStorage;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class BugReportAdminService {

    private final BugReportRepository bugReportRepository;
    private final AuditLogService auditLogService;
    private final BugReportScreenshotStorage screenshotStorage;

    @Transactional(readOnly = true)
    public ScreenshotFile loadScreenshot(String reportId) {
        BugReportEntity report = bugReportRepository
                .findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenalezeno"));
        Map<String, Object> screenshot = report.getScreenshot();
        if (screenshot == null || screenshot.get("stored_path") == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Screenshot není k dispozici.");
        }
        try {
            var path = screenshotStorage.resolveStoredPath(String.valueOf(screenshot.get("stored_path")));
            byte[] bytes = Files.readAllBytes(path);
            String contentType = screenshot.get("content_type") != null
                    ? String.valueOf(screenshot.get("content_type"))
                    : "application/octet-stream";
            return new ScreenshotFile(bytes, contentType);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Screenshot není k dispozici.");
        }
    }

    public record ScreenshotFile(byte[] bytes, String contentType) {}

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listReports(String statusFilter, int limit) {
        List<BugReportEntity> reports;
        if ("open".equals(statusFilter) || "resolved".equals(statusFilter)) {
            reports = bugReportRepository.findByStatusOrderByCreatedAtDesc(statusFilter, PageRequest.of(0, limit));
        } else {
            reports = bugReportRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit));
        }
        return reports.stream().map(this::toListItem).toList();
    }

    @Transactional
    public Map<String, Object> patchReport(
            String reportId, BugReportAdminPatch body, UserEntity admin, String ip, String userAgent) {
        BugReportEntity before = bugReportRepository
                .findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenalezeno"));
        String prev = before.getStatus();
        if (!"open".equals(body.status()) && !"resolved".equals(body.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatný status.");
        }
        before.setStatus(body.status());
        if ("resolved".equals(body.status())) {
            before.setResolvedAt(Instant.now());
            before.setResolvedBy(Map.of("id", admin.getId(), "email", admin.getEmail()));
        } else {
            before.setResolvedAt(null);
            before.setResolvedBy(null);
        }
        bugReportRepository.save(before);

        boolean hadScreenshot = hasScreenshot(before);
        if ("resolved".equals(body.status()) && !"resolved".equals(prev)) {
            auditLogService.logEvent(
                    "bug_report_resolved",
                    admin,
                    "bug_report",
                    reportId,
                    Map.of(
                            "bug_report_id", reportId,
                            "title", truncate(before.getTitle(), 200),
                            "previous_status", prev != null ? prev : "",
                            "new_status", "resolved",
                            "had_screenshot", hadScreenshot),
                    ip,
                    userAgent);
        } else if ("open".equals(body.status()) && "resolved".equals(prev)) {
            auditLogService.logEvent(
                    "bug_report_reopened",
                    admin,
                    "bug_report",
                    reportId,
                    Map.of(
                            "bug_report_id", reportId,
                            "title", truncate(before.getTitle(), 200),
                            "previous_status", "resolved",
                            "new_status", "open",
                            "had_screenshot", hadScreenshot),
                    ip,
                    userAgent);
        }
        return toListItem(before);
    }

    @Transactional
    public Map<String, Object> deleteReport(String reportId, UserEntity admin, String ip, String userAgent) {
        BugReportEntity before = bugReportRepository
                .findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenalezeno"));
        boolean hadScreenshot = hasScreenshot(before);
        if (hadScreenshot) {
            screenshotStorage.deleteScreenshotFile(String.valueOf(before.getScreenshot().get("stored_path")));
        }
        bugReportRepository.delete(before);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("bug_report_id", reportId);
        metadata.put("title", truncate(before.getTitle(), 200));
        metadata.put("previous_status", before.getStatus());
        metadata.put("new_status", null);
        metadata.put("had_screenshot", hadScreenshot);
        auditLogService.logEvent("bug_report_deleted", admin, "bug_report", reportId, metadata, ip, userAgent);

        return Map.of("ok", true);
    }

    private Map<String, Object> toListItem(BugReportEntity report) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", report.getId());
        out.put("title", report.getTitle());
        out.put("description", report.getDescription());
        out.put("contact_email", report.getContactEmail());
        out.put("page_url", report.getPageUrl());
        out.put("user_agent", report.getUserAgent());
        out.put("viewport", report.getViewport());
        out.put("route", report.getRoute());
        out.put("user_id", report.getUserId());
        out.put("user_email", report.getUserEmail());
        out.put("user_role", report.getUserRole());
        out.put("status", report.getStatus());
        out.put("priority", report.getPriority());
        out.put("created_at", report.getCreatedAt() != null ? report.getCreatedAt().toString() : null);
        out.put("resolved_at", report.getResolvedAt() != null ? report.getResolvedAt().toString() : null);
        out.put("resolved_by", report.getResolvedBy());

        Map<String, Object> screenshot = report.getScreenshot();
        if (screenshot != null && screenshot.get("stored_path") != null) {
            out.put("has_screenshot", true);
            Map<String, Object> pub = new LinkedHashMap<>();
            pub.put("original_name", screenshot.get("original_name"));
            pub.put("content_type", screenshot.get("content_type"));
            pub.put("size", screenshot.get("size"));
            out.put("screenshot", pub);
        } else {
            out.put("has_screenshot", false);
            out.put("screenshot", null);
        }
        return out;
    }

    private static boolean hasScreenshot(BugReportEntity report) {
        Map<String, Object> screenshot = report.getScreenshot();
        return screenshot != null && screenshot.get("stored_path") != null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
