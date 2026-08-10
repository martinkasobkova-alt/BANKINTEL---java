package cz.bankintel.controller.admin;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.service.admin.AdminSyncJobsService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminSyncJobsController {

    private final AdminAccess adminAccess;
    private final AdminSyncJobsService adminSyncJobsService;

    @GetMapping("/sync-jobs")
    public Map<String, Object> listJobs() {
        adminAccess.requireAdmin();
        return adminSyncJobsService.listJobs();
    }

    @PostMapping("/sync-jobs/{jobId}/reset")
    public Map<String, Object> resetJob(@PathVariable String jobId) {
        adminAccess.requireAdmin();
        return adminSyncJobsService.resetJob(jobId);
    }

    @PostMapping("/sync-jobs/{jobId}/run-sync")
    public Map<String, Object> runSyncNow(@PathVariable String jobId) {
        adminAccess.requireAdmin();
        return adminSyncJobsService.runSyncNow(jobId);
    }
}
