package cz.bankintel.controller.sources;

import cz.bankintel.sources.imf.Imf2BrowseService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/imf2")
@RequiredArgsConstructor
public class Imf2BrowseController {

    private final Imf2BrowseService imf2BrowseService;

    @GetMapping("/browse-tree")
    public Map<String, Object> browseTree() {
        return imf2BrowseService.getBrowseTree();
    }

    @GetMapping("/browse-tree/country/{country}")
    public Map<String, Object> countryNode(
            @PathVariable String country,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "1500") int limit) {
        return imf2BrowseService.getCountryBrowseNode(country, offset, limit);
    }
}
