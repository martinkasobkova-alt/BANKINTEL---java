package cz.bankintel.controller.sources;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.sources.data360.Data360CatalogService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * World Bank Data360 katalog ({@code /api/data360/catalog}). Port {@code data360_catalog_routes.py}.
 */
@RestController
@RequestMapping("/api/data360/catalog")
@RequiredArgsConstructor
public class Data360CatalogController {

    private final Data360CatalogService data360CatalogService;
    private final AdminAccess adminAccess;

    @GetMapping({"", "/"})
    public Map<String, Object> getCatalog(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "top", required = false, defaultValue = "40") int top) {
        int normalizedTop = Math.max(5, Math.min(top, 120));
        return data360CatalogService.getCatalog(q, normalizedTop);
    }

    @GetMapping("/country/{countryCode}/indicators")
    public Map<String, Object> getCountryIndicators(@PathVariable String countryCode) {
        return data360CatalogService.getCountryIndicators(countryCode);
    }

    @GetMapping("/indicators")
    public Map<String, Object> listIndicators(@RequestParam("datasetId") String datasetId) {
        return data360CatalogService.listIndicatorsForDataset(datasetId);
    }

    @PostMapping("/metadata")
    public Map<String, Object> metadata(@RequestBody(required = false) Map<String, Object> body) {
        return data360CatalogService.metadataQuery(body != null ? body : Map.of());
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "top", required = false, defaultValue = "40") int top) {
        adminAccess.requireAdmin();
        int normalizedTop = Math.max(5, Math.min(top, 120));
        return data360CatalogService.refreshCatalog(q, normalizedTop);
    }

    @PostMapping("/add-source")
    public Map<String, Object> addSource(@RequestBody(required = false) Map<String, Object> payload) {
        adminAccess.requireAdmin();
        return data360CatalogService.addSource(payload != null ? payload : Map.of());
    }
}
