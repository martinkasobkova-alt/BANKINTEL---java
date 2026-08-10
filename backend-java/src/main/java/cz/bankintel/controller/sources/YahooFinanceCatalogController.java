package cz.bankintel.controller.sources;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.sources.yahoo.YahooFinanceCatalogService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/yahoo_finance")
@RequiredArgsConstructor
public class YahooFinanceCatalogController {

    private final YahooFinanceCatalogService yahooFinanceCatalogService;
    private final AdminAccess adminAccess;

    @GetMapping("/catalog")
    public Map<String, Object> getCatalog() {
        return yahooFinanceCatalogService.getCatalog();
    }

    @GetMapping("/preview")
    public Map<String, Object> preview(
            @RequestParam("set_id") String setId,
            @RequestParam(value = "start_date", defaultValue = "1990-01-01") String startDate,
            @RequestParam(value = "end_date", required = false) String endDate,
            @RequestParam(value = "interval", defaultValue = "1d") String interval) {
        return yahooFinanceCatalogService.preview(setId, startDate, endDate, interval);
    }

    @PostMapping("/catalog/add-source")
    public Map<String, Object> addSource(@RequestBody Map<String, Object> payload) {
        adminAccess.requireAdmin();
        return yahooFinanceCatalogService.addSource(payload != null ? payload : Map.of());
    }
}
