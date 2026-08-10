package cz.bankintel.controller.sources;

import cz.bankintel.sources.data360.Data360CatalogService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** World Bank katalog — alias nad Data360 ({@code /api/worldbank/catalog}). */
@RestController
@RequestMapping("/api/worldbank/catalog")
@RequiredArgsConstructor
public class WorldBankCatalogController {

    private final Data360CatalogService data360CatalogService;

    @GetMapping("/country/{countryCode}/indicators")
    public Map<String, Object> getCountryIndicators(@PathVariable String countryCode) {
        return data360CatalogService.getCountryIndicators(countryCode);
    }
}
