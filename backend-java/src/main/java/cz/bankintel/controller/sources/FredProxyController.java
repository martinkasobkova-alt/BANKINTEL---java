package cz.bankintel.controller.sources;

import cz.bankintel.sources.fred.FredCatalogService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fred")
@RequiredArgsConstructor
public class FredProxyController {

    private static final String FRED_EXTERNAL_DOCS = "https://fred.stlouisfed.org/docs/api/api_key.html";

    private final FredCatalogService fredCatalogService;

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam("q") String q, @RequestParam(defaultValue = "200") int limit) {
        return proxy(() -> fredCatalogService.searchSeries(q, limit));
    }

    @GetMapping("/category/{categoryId}/children")
    public ResponseEntity<Map<String, Object>> categoryChildren(@PathVariable int categoryId) {
        return proxy(() -> fredCatalogService.getCategoryChildren(categoryId));
    }

    @GetMapping("/category/{categoryId}/series")
    public ResponseEntity<Map<String, Object>> categorySeries(
            @PathVariable int categoryId,
            @RequestParam(defaultValue = "1000") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return proxy(() -> fredCatalogService.getCategorySeries(categoryId, limit, offset));
    }

    @GetMapping("/series/{seriesId}/observations")
    public ResponseEntity<Map<String, Object>> seriesObservations(
            @PathVariable String seriesId, @RequestParam(defaultValue = "10000") int limit) {
        return proxy(() -> fredCatalogService.getSeriesObservations(seriesId, limit));
    }

    @GetMapping("/_meta")
    public Map<String, Object> meta() {
        return fredCatalogService.proxyMeta();
    }

    private ResponseEntity<Map<String, Object>> proxy(ProxyCall call) {
        if (!fredCatalogService.hasApiKey()) {
            Map<String, Object> body = new LinkedHashMap<>(fredCatalogService.missingKeyPayload());
            body.put("external_docs", FRED_EXTERNAL_DOCS);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        try {
            return ResponseEntity.ok(call.run());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "FRED upstream error");
            body.put("detail", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            body.put("source", "FRED");
            body.put("configured", true);
            body.put("fred_api_key_configured", true);
            body.put("fred_api_configured", true);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        }
    }

    @FunctionalInterface
    private interface ProxyCall {
        Map<String, Object> run() throws Exception;
    }
}
