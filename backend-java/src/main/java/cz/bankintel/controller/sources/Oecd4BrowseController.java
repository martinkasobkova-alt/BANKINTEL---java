package cz.bankintel.controller.sources;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.sources.oecd.OecdCatalogService;
import cz.bankintel.sources.oecd4.Oecd4BrowseService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/oecd4")
@RequiredArgsConstructor
public class Oecd4BrowseController {

    private final Oecd4BrowseService oecd4BrowseService;
    private final OecdCatalogService oecdCatalogService;
    private final AdminAccess adminAccess;

    @GetMapping("/browse-tree")
    public Map<String, Object> browseTree() {
        return oecd4BrowseService.getBrowseTree();
    }

    @GetMapping("/browse-tree/dataset/{key}")
    public Map<String, Object> datasetNode(@PathVariable String key) {
        return oecd4BrowseService.getDatasetNode(key);
    }

    @GetMapping("/browse-tree/dataset/{key}/country/{refArea}")
    public Map<String, Object> countryNode(@PathVariable String key, @PathVariable String refArea) {
        return oecd4BrowseService.getCountryNode(key, refArea);
    }

    @PostMapping("/add-source")
    public Map<String, Object> addSource(@RequestBody(required = false) Map<String, Object> payload) {
        adminAccess.requireAdmin();
        Map<String, Object> result = oecdCatalogService.addSource(payload != null ? payload : Map.of());
        result.put("provider", "oecd4");
        return result;
    }
}
