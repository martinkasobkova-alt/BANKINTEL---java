package cz.bankintel.controller.sync;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.service.sync.SyncQueryService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {

    private final AdminAccess adminAccess;
    private final SyncQueryService syncQueryService;

    @GetMapping("/logs")
    public List<Map<String, Object>> listLogs(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(name = "source_id", required = false) String sourceId) {
        adminAccess.requireAdmin();
        int capped = Math.max(1, Math.min(limit, 500));
        return syncQueryService.listLogs(capped, sourceId);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        adminAccess.requireAdmin();
        return syncQueryService.health();
    }
}
