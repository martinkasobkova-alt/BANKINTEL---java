package cz.bankintel.controller.admin;

import cz.bankintel.explore.manager.refresh.AdminManagerEurostatRefreshService;
import cz.bankintel.security.AdminAccess;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only manual trigger for the Java-native Eurostat {@code manager_series_cache} refresh
 * (see {@code ManagerEurostatCacheRefreshService}). A full run can take 1-3 hours, so this
 * starts the job and returns a {@code run_id} immediately; poll the status endpoint for
 * progress/result. Phase 1/2 of the rollout use this endpoint manually - the scheduled nightly
 * cron (Phase 3) is a separate, later addition to {@code BankIntelScheduler}.
 */
@RestController
@RequestMapping("/api/admin/manager-series-cache")
@RequiredArgsConstructor
public class AdminManagerEurostatRefreshController {

    private final AdminAccess adminAccess;
    private final AdminManagerEurostatRefreshService adminRefreshService;

    @PostMapping("/refresh-eurostat")
    @SuppressWarnings("unchecked")
    public Map<String, Object> refreshEurostat(@RequestBody(required = false) Map<String, Object> body) {
        adminAccess.requireAdmin();
        Map<String, Object> request = body != null ? body : Map.of();
        List<String> segments = stringList(request.get("segments"));
        List<String> geo = stringList(request.get("geo"));
        boolean dryRun = !Boolean.FALSE.equals(request.get("dry_run"));
        String targetCollection = request.get("target_collection") != null ? String.valueOf(request.get("target_collection")) : null;
        String runId = adminRefreshService.startRefresh(segments, geo, dryRun, targetCollection);
        return Map.of("ok", true, "run_id", runId, "dry_run", dryRun);
    }

    @GetMapping("/refresh-eurostat/{runId}")
    public Map<String, Object> refreshStatus(@PathVariable String runId) {
        adminAccess.requireAdmin();
        return adminRefreshService.status(runId);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return (List<String>) list.stream().map(String::valueOf).toList();
    }
}
