package cz.bankintel.controller.sources;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.sources.oecd.OecdCatalogService;
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
 * OECD katalog — offline mirror / browse ({@code /api/oecd/catalog}). Port {@code oecd_catalog_routes.py}.
 */
@RestController
@RequestMapping("/api/oecd/catalog")
@RequiredArgsConstructor
public class OecdCatalogController {

    private final OecdCatalogService oecdCatalogService;
    private final AdminAccess adminAccess;

    @GetMapping({"", "/"})
    public Map<String, Object> getCatalog() {
        return oecdCatalogService.getCatalog();
    }

    @GetMapping("/series")
    public Map<String, Object> getSeriesForDataset(
            @RequestParam("dataset") String dataset,
            @RequestParam(value = "refresh", required = false, defaultValue = "false") boolean refresh) {
        return oecdCatalogService.getSeriesForDataset(dataset, refresh);
    }

    @GetMapping("/dataflow/{agency}/{dataflow}/structure")
    public Map<String, Object> getDataflowStructure(
            @PathVariable String agency,
            @PathVariable String dataflow,
            @RequestParam(value = "version", required = false, defaultValue = "+") String version,
            @RequestParam(value = "refresh", required = false, defaultValue = "false") boolean refresh) {
        return oecdCatalogService.getDataflowStructure(agency, dataflow, version, refresh);
    }

    @PostMapping("/refresh")
    public Map<String, Object> refreshCatalog() {
        adminAccess.requireAdmin();
        return oecdCatalogService.refreshCatalog();
    }

    @PostMapping("/add-source")
    public Map<String, Object> addSource(@RequestBody(required = false) Map<String, Object> payload) {
        adminAccess.requireAdmin();
        return oecdCatalogService.addSource(payload != null ? payload : Map.of());
    }
}
