package cz.bankintel.controller.sources;

import cz.bankintel.sources.alphavantage.AlphaVantageCatalogService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alphavantage/catalog")
@RequiredArgsConstructor
public class AlphaVantageCatalogController {

    private final AlphaVantageCatalogService alphaVantageCatalogService;

    @GetMapping({"", "/"})
    public Map<String, Object> getCatalog() {
        return alphaVantageCatalogService.getCatalog();
    }

    @PostMapping("/add-source")
    public Map<String, Object> addSource(@RequestBody Map<String, Object> payload) {
        return alphaVantageCatalogService.addSource(payload != null ? payload : Map.of());
    }

    @PostMapping("/cache/invalidate")
    public Map<String, Object> invalidateCache(@RequestBody(required = false) Map<String, Object> payload) {
        return alphaVantageCatalogService.invalidateCache(payload);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok()
                .header(
                        "X-Alphavantage-Api-Configured",
                        alphaVantageCatalogService.alphavantageApiKeyConfigured() ? "true" : "false")
                .body(Map.of(
                        "configured", alphaVantageCatalogService.alphavantageApiKeyConfigured(),
                        "alphavantage_api_key_configured", alphaVantageCatalogService.alphavantageApiKeyConfigured()));
    }
}
