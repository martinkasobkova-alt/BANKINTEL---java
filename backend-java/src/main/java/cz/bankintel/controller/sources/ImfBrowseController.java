package cz.bankintel.controller.sources;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.sources.imf.ImfBrowseService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** IMF country-first browse ({@code /api/imf/*}). Port {@code imf_browser_routes.py}. */
@RestController
@RequestMapping("/api/imf")
@RequiredArgsConstructor
public class ImfBrowseController {

    private final ImfBrowseService browseService;
    private final AdminAccess adminAccess;

    @GetMapping("/countries")
    public Map<String, Object> countries() {
        return browseService.getCountries();
    }

    @GetMapping("/browse-tree")
    public Map<String, Object> browseTree() {
        return browseService.getBrowseTree();
    }

    @GetMapping("/browse-tree/country/{country}")
    public Map<String, Object> countryBrowseNode(@PathVariable String country) {
        return browseService.getCountryBrowseNode(country);
    }

    @GetMapping("/country/{country}")
    public Map<String, Object> countryIndicators(
            @PathVariable String country,
            @RequestParam(value = "kategorie", required = false) String kategorie) {
        return browseService.getCountryIndicators(country, kategorie);
    }

    @GetMapping("/indikator/{flow}/{indicator}")
    public Map<String, Object> indicatorCountries(@PathVariable String flow, @PathVariable String indicator) {
        return browseService.getIndicatorCountries(flow, indicator);
    }

    @GetMapping("/country/{country}/data/{flow}/{indicator}")
    public Map<String, Object> countrySeriesData(
            @PathVariable String country,
            @PathVariable String flow,
            @PathVariable String indicator,
            @RequestParam(value = "od", required = false) String od,
            @RequestParam(value = "do", required = false) String doParam,
            @RequestParam(value = "frekvence", required = false) String frekvence) {
        return browseService.getCountrySeriesData(country, flow, indicator, od, doParam, frekvence);
    }

    @GetMapping("/porovnani")
    public Map<String, Object> compareCountries(
            @RequestParam("zeme") String zeme,
            @RequestParam("flow") String flow,
            @RequestParam("indicator") String indicator,
            @RequestParam(value = "od", required = false) String od,
            @RequestParam(value = "do", required = false) String doParam) {
        return browseService.compareCountries(zeme, flow, indicator, od, doParam);
    }

    @PostMapping("/add-source")
    public Map<String, Object> addSource(@RequestBody(required = false) Map<String, Object> payload) {
        adminAccess.requireAdmin();
        return browseService.addSource(payload != null ? payload : Map.of());
    }
}
