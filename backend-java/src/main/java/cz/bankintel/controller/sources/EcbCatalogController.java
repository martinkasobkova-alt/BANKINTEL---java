package cz.bankintel.controller.sources;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.sources.ecb.EcbCatalogService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ECB katalog — kurátorovaný browse strom a live data ({@code /api/ecb/*}).
 *
 * <p>Port {@code ecb_catalog_routes.py}. Služba: {@link cz.bankintel.sources.ecb.EcbCatalogService}.
 */
@RestController
@RequestMapping("/api/ecb")
@RequiredArgsConstructor
public class EcbCatalogController {

    private final EcbCatalogService ecbCatalogService;
    private final AdminAccess adminAccess;

    @GetMapping("/browse-tree")
    public Map<String, Object> getBrowseTree() {
        return ecbCatalogService.getBrowseTree();
    }

    @GetMapping("/browse-tree/country/{country}")
    public Map<String, Object> getCountryBrowseNode(@PathVariable String country) {
        try {
            return ecbCatalogService.getCountryBrowseNode(country);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/browse-tree/country/{country}/discovery-series")
    public Map<String, Object> getCountryDiscoverySeries(
            @PathVariable String country,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "500") int limit) {
        try {
            return ecbCatalogService.getCountryDiscoverySeries(country, offset, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/countries")
    public Map<String, Object> listCountries(
            @RequestParam(value = "euro_only", defaultValue = "false") boolean euroOnly,
            @RequestParam(value = "eu_only", defaultValue = "false") boolean euOnly) {
        return ecbCatalogService.listCountries(euroOnly, euOnly);
    }

    @GetMapping("/kategorie")
    public Map<String, Object> listCategories() {
        return ecbCatalogService.listCategories();
    }

    @GetMapping("/country/{country}")
    public Map<String, Object> getCountryIndicators(
            @PathVariable String country,
            @RequestParam(value = "kategorie", required = false) String kategorie) {
        try {
            return ecbCatalogService.getCountryIndicators(country, kategorie);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/country/{country}/data/{indicator}")
    public Map<String, Object> getCountryData(
            @PathVariable String country,
            @PathVariable String indicator,
            @RequestParam(value = "od", required = false) String start,
            @RequestParam(value = "do", required = false) String end) {
        try {
            return ecbCatalogService.getCountryData(country, indicator, start, end);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/country/{country}/snapshot")
    public Map<String, Object> getCountrySnapshot(
            @PathVariable String country,
            @RequestParam(value = "od", defaultValue = "2020-01") String start) {
        try {
            return ecbCatalogService.getCountrySnapshot(country, start);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/porovnani")
    public Map<String, Object> compareCountries(
            @RequestParam("zeme") String zeme,
            @RequestParam("ukazatel") String ukazatel,
            @RequestParam(value = "od", required = false) String start,
            @RequestParam(value = "do", required = false) String end) {
        return ecbCatalogService.compareCountries(zeme, ukazatel, start, end);
    }

    @PostMapping("/add-source")
    public Map<String, Object> addSource(@RequestBody Map<String, Object> payload) {
        adminAccess.requireAdmin();
        try {
            return ecbCatalogService.addSource(payload != null ? payload : Map.of());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }
}
