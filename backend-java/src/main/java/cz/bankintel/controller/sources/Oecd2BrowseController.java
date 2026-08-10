package cz.bankintel.controller.sources;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Deprecated — používejte pouze {@code /api/oecd4}. */
@RestController
@RequestMapping("/api/oecd2")
public class Oecd2BrowseController {

    @GetMapping("/browse-tree")
    public Map<String, Object> browseTree() {
        throw gone();
    }

    @GetMapping("/browse-tree/country/{country}")
    public Map<String, Object> countryNode(
            @PathVariable String country,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "1500") int limit) {
        throw gone();
    }

    private static ResponseStatusException gone() {
        return new ResponseStatusException(
                HttpStatus.GONE, "OECD2 browse byl vypnut. Použijte /api/oecd4/browse-tree.");
    }
}
