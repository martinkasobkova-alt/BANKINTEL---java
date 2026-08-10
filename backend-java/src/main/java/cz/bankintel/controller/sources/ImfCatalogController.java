package cz.bankintel.controller.sources;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.sources.imf.ImfCatalogService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IMF katalog — bootstrap strom indikátorů ({@code /api/imf/catalog}). Port {@code imf_catalog_routes.py}.
 */
@RestController
@RequestMapping("/api/imf/catalog")
@RequiredArgsConstructor
public class ImfCatalogController {

    private final ImfCatalogService imfCatalogService;
    private final AdminAccess adminAccess;

    @GetMapping({"", "/"})
    public Map<String, Object> getCatalogBootstrap() {
        return imfCatalogService.getBootstrapCatalog();
    }

    @GetMapping("/full")
    public Map<String, Object> getCatalogFull() {
        return imfCatalogService.getFullCatalog(false);
    }

    @PostMapping("/refresh")
    public Map<String, Object> refreshCatalog() {
        adminAccess.requireAdmin();
        return imfCatalogService.getFullCatalog(true);
    }

    @GetMapping("/dataset/{datasetId}")
    public Map<String, Object> getCatalogDataset(@PathVariable String datasetId) {
        Map<String, Object> payload = imfCatalogService.getDatasetStructure(datasetId);
        payload = new java.util.LinkedHashMap<>(payload);
        payload.put("fetched_at", System.currentTimeMillis() / 1000.0);
        return payload;
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody(required = false) Map<String, Object> payload) {
        return imfCatalogService.validateSeries(payload != null ? payload : Map.of());
    }

    @PostMapping("/add-source")
    public Map<String, Object> addSource(@RequestBody(required = false) Map<String, Object> payload) {
        adminAccess.requireAdmin();
        return imfCatalogService.addSource(payload != null ? payload : Map.of());
    }
}
