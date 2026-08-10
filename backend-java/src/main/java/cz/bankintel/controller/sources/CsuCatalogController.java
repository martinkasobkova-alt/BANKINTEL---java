package cz.bankintel.controller.sources;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.sources.csu.CsuCatalogService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ČSÚ DataStat katalog — výběry a sady ({@code /api/csu/catalog}). Port {@code csu_catalog_routes.py}.
 */
@RestController
@RequestMapping("/api/csu/catalog")
@RequiredArgsConstructor
public class CsuCatalogController {

    private final CsuCatalogService csuCatalogService;
    private final AdminAccess adminAccess;

    @GetMapping({"", "/"})
    public Map<String, Object> getCatalog() {
        return csuCatalogService.getCatalogEnvelope();
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        adminAccess.requireAdmin();
        return csuCatalogService.refreshCatalog();
    }

    @PostMapping("/add-source")
    public Map<String, Object> addSource(@RequestBody(required = false) Map<String, Object> payload) {
        adminAccess.requireAdmin();
        return csuCatalogService.addSourceFromCatalog(payload != null ? payload : Map.of());
    }
}
