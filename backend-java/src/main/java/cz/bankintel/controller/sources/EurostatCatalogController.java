package cz.bankintel.controller.sources;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.sources.eurostat.EurostatCatalogService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Eurostat katalog — TOC strom datasetů ({@code /api/eurostat/catalog}).
 * Port {@code eurostat_catalog_routes.py}.
 */
@RestController
@RequestMapping("/api/eurostat/catalog")
@RequiredArgsConstructor
public class EurostatCatalogController {

    private final EurostatCatalogService eurostatCatalogService;
    private final AdminAccess adminAccess;

    @GetMapping({"", "/"})
    public Map<String, Object> getCatalog() {
        return eurostatCatalogService.getCatalogEnvelope();
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        return eurostatCatalogService.refreshCatalog();
    }

    /**
     * Admin-only, matching the ARAD and Alpha Vantage twins of this endpoint. Creating a source is
     * a write into the shared catalog; this one was the only one of the three left unguarded (ARAD
     * checks in the controller, Alpha Vantage inside its service), which reads as an oversight
     * rather than a decision.
     */
    @PostMapping("/add-source")
    public Map<String, Object> addSource(@RequestBody Map<String, Object> payload) {
        adminAccess.requireAdmin();
        return eurostatCatalogService.addSourceFromCatalog(payload != null ? payload : Map.of());
    }
}
