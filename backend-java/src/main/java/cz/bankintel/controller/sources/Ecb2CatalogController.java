package cz.bankintel.controller.sources;

import cz.bankintel.sources.ecb.Ecb2CatalogService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ECB2 discovery katalog — flow/series podle země ({@code /api/ecb2/*}).
 * Port {@code ecb2_catalog_routes.py}.
 */
@RestController
@RequestMapping("/api/ecb2")
@RequiredArgsConstructor
public class Ecb2CatalogController {

    private final Ecb2CatalogService ecb2CatalogService;

    @GetMapping("/browse-tree")
    public Map<String, Object> getBrowseTree() {
        return ecb2CatalogService.getBrowseTree();
    }

    @GetMapping("/browse-tree/country/{country}")
    public Map<String, Object> getCountryBrowseNode(@PathVariable String country) {
        try {
            return ecb2CatalogService.getCountryBrowseNode(country);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/browse-tree/country/{country}/flow/{flowRef}")
    public Map<String, Object> getCountryFlowBrowseNode(
            @PathVariable String country, @PathVariable String flowRef) {
        try {
            return ecb2CatalogService.getCountryFlowBrowseNode(country, flowRef);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/browse-tree/country/{country}/flow/{flowRef}/letter/{letter}")
    public Map<String, Object> getCountryFlowLetterBrowseNode(
            @PathVariable String country,
            @PathVariable String flowRef,
            @PathVariable String letter,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "500") int limit) {
        try {
            return ecb2CatalogService.getCountryFlowLetterBrowseNode(country, flowRef, letter, offset, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/browse-tree/country/{country}/letter/{letter}")
    public Map<String, Object> legacyCountryLetterRoute() {
        return ecb2CatalogService.legacyCountryLetterRoute();
    }
}
