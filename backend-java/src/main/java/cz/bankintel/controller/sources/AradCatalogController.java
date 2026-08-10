package cz.bankintel.controller.sources;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.sources.arad.AradCatalogService;
import cz.bankintel.sources.arad.AradCatalogWriteService;
import cz.bankintel.sources.arad.AradSetIndicatorsService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ARAD katalog — strom sad ČNB ({@code /api/arad/catalog}). Port {@code arad_catalog_routes.py}.
 */
@RestController
@RequestMapping("/api/arad/catalog")
@RequiredArgsConstructor
public class AradCatalogController {

    private final AradCatalogService aradCatalogService;
    private final AradSetIndicatorsService aradSetIndicatorsService;
    private final AradCatalogWriteService aradCatalogWriteService;
    private final AdminAccess adminAccess;

    @GetMapping({"", "/"})
    public Map<String, Object> getCatalog() {
        return aradCatalogService.getCatalogEnvelope();
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        return aradCatalogService.refreshCatalog();
    }

    @GetMapping("/set-indicators")
    public Map<String, Object> setIndicators(@RequestParam("set_id") String setId) {
        return aradSetIndicatorsService.getSetIndicators(setId);
    }

    @PostMapping("/add-source")
    public Map<String, Object> addSource(@RequestBody Map<String, Object> payload) {
        adminAccess.requireAdmin();
        return aradCatalogWriteService.addSource(payload != null ? payload : Map.of());
    }

    @GetMapping("/live-check")
    public Map<String, Object> liveCheck() {
        adminAccess.requireAdmin();
        return aradCatalogService.liveCheck();
    }
}
