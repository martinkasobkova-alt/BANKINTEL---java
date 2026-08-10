package cz.bankintel.controller.sources;

import cz.bankintel.sources.eurostat.EurostatDimensionService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/eurostat")
@RequiredArgsConstructor
public class EurostatDimensionController {

    private final EurostatDimensionService eurostatDimensionService;

    @PostMapping("/datasets/{datasetId}/dimension-availability")
    public Map<String, Object> dimensionAvailability(
            @PathVariable String datasetId, @RequestBody(required = false) Map<String, Object> body) {
        return eurostatDimensionService.dimensionAvailability(datasetId, body != null ? body : Map.of());
    }

    @PostMapping("/datasets/{datasetId}/dimension-defaults")
    public Map<String, Object> dimensionDefaults(
            @PathVariable String datasetId, @RequestBody(required = false) Map<String, Object> body) {
        return eurostatDimensionService.dimensionDefaults(datasetId, body != null ? body : Map.of());
    }
}
