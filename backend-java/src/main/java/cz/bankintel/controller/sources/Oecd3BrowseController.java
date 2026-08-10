package cz.bankintel.controller.sources;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Deprecated — používejte pouze {@code /api/oecd4}. */
@RestController
@RequestMapping("/api/oecd3")
public class Oecd3BrowseController {

    @GetMapping("/browse-tree")
    public Map<String, Object> browseTree() {
        throw gone();
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        throw gone();
    }

    @GetMapping("/dataflow/{agency}/{dataflow}/probe")
    public Map<String, Object> probeDataflow(
            @PathVariable String agency,
            @PathVariable String dataflow,
            @RequestParam(defaultValue = "+") String version,
            @RequestParam(defaultValue = "CZE") String ref_area,
            @RequestParam(defaultValue = "false") boolean refresh) {
        throw gone();
    }

    private static ResponseStatusException gone() {
        return new ResponseStatusException(
                HttpStatus.GONE, "OECD3 browse byl vypnut. Použijte /api/oecd4/browse-tree.");
    }
}
