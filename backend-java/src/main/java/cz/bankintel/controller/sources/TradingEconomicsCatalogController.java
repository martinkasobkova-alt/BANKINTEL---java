package cz.bankintel.controller.sources;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.sources.tradingeconomics.TradingEconomicsCatalogService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** TE katalog ({@code /api/tradingeconomics/catalog/*}). Port {@code tradingeconomics_catalog_routes.py}. */
@RestController
@RequestMapping("/api/tradingeconomics/catalog")
@RequiredArgsConstructor
public class TradingEconomicsCatalogController {

    private final TradingEconomicsCatalogService catalogService;
    private final AdminAccess adminAccess;

    @GetMapping({"", "/"})
    public Map<String, Object> catalog() {
        return catalogService.getCatalog();
    }

    @GetMapping("/country/{countryName}")
    public Map<String, Object> countryCatalog(@PathVariable String countryName) {
        return catalogService.getCountryCatalog(countryName);
    }

    @PostMapping("/add-source")
    public Map<String, Object> addSource(@RequestBody Map<String, Object> payload) {
        adminAccess.requireAdmin();
        return catalogService.addSource(payload);
    }
}
