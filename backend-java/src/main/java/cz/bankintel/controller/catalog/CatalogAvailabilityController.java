package cz.bankintel.controller.catalog;

import cz.bankintel.search.CatalogAvailabilityService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog/availability")
@RequiredArgsConstructor
public class CatalogAvailabilityController {

    private final CatalogAvailabilityService catalogAvailabilityService;

    @PostMapping("/build")
    public Map<String, Object> build(
            @RequestParam String source,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(name = "sleep_ms", defaultValue = "0") int sleepMs,
            @RequestParam(defaultValue = "") String token) {
        return catalogAvailabilityService.build(source, limit, offset, sleepMs, token);
    }

    @GetMapping("/status")
    public Map<String, Object> status(
            @RequestParam(defaultValue = "") String source, @RequestParam(defaultValue = "") String token) {
        catalogAvailabilityService.requireAdminOrBuildToken(token);
        return catalogAvailabilityService.status(source);
    }
}
