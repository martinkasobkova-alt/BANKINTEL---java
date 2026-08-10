package cz.bankintel.controller.admin;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.service.admin.AdminCatalogIndexAdminService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminCatalogIndexController {

    private final AdminAccess adminAccess;
    private final AdminCatalogIndexAdminService catalogIndexAdminService;

    @GetMapping("/catalog-index/status")
    public Map<String, Object> status() {
        adminAccess.requireAdmin();
        return catalogIndexAdminService.status();
    }

    @PostMapping("/catalog-index/rebuild")
    public Map<String, Object> rebuild(@RequestBody(required = false) Map<String, Object> body) {
        adminAccess.requireAdmin();
        List<String> sources = null;
        if (body != null && body.get("sources") instanceof List<?> list) {
            sources = list.stream().map(String::valueOf).map(String::strip).filter(s -> !s.isBlank()).toList();
        }
        return catalogIndexAdminService.rebuild(sources);
    }

    @GetMapping("/catalog-index/search-test")
    public Map<String, Object> searchTest(
            @RequestParam("q") String query,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String sources,
            @RequestParam(defaultValue = "20") int limit) {
        adminAccess.requireAdmin();
        int bounded = Math.min(Math.max(limit, 1), 100);
        return catalogIndexAdminService.searchTest(query, country, sources, bounded);
    }
}
