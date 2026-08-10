package cz.bankintel.controller.catalog;

import cz.bankintel.sources.catalog.MacroTopicCatalogService;
import cz.bankintel.sources.catalog.MacroTopicsSnapshotService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Macro topics browse ({@code /api/catalog/macro-topics}). Port {@code macro_topic_routes.py}. */
@RestController
@RequestMapping("/api/catalog/macro-topics")
@RequiredArgsConstructor
public class MacroTopicController {

    private final MacroTopicCatalogService catalogService;
    private final MacroTopicsSnapshotService snapshotService;

    @GetMapping({"", "/"})
    public Map<String, Object> overview() {
        Map<String, Object> snapOverview = snapshotService.getOverview();
        if (snapOverview != null) {
            return snapOverview;
        }
        Map<String, Object> live = catalogService.getOverview();
        live.putAll(MacroTopicsSnapshotService.snapshotMeta(null));
        return live;
    }

    @GetMapping("/by-topic/{topicId}")
    public Map<String, Object> byTopic(
            @PathVariable String topicId,
            @RequestParam(value = "country", required = false) String country,
            @RequestParam(value = "limit", defaultValue = "80") int limit) {
        return catalogService.browseByTopic(topicId, country, Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping("/by-country/{countryCode}")
    public Map<String, Object> byCountry(
            @PathVariable String countryCode,
            @RequestParam(value = "topic_id", required = false) String topicId,
            @RequestParam(value = "limit", defaultValue = "80") int limit) {
        return catalogService.browseByCountry(countryCode, topicId, Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping("/comparison-table")
    public ResponseEntity<Map<String, Object>> comparisonTable(
            @RequestParam(value = "only_complete", defaultValue = "false") boolean onlyComplete,
            @RequestParam(value = "min_columns", defaultValue = "2") int minColumns,
            @RequestParam(value = "scope", defaultValue = "eu") String scope,
            @RequestParam(value = "include_values", defaultValue = "false") boolean includeValues) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        if (includeValues) {
            Map<String, Object> table = snapshotService.getComparisonTable(onlyComplete, minColumns, scope, true);
            if (table != null) {
                return ResponseEntity.ok().headers(headers).body(table);
            }
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Srovnavaci tabulka neni v rychlem snapshotu k dispozici.");
        }
        Map<String, Object> table = snapshotService.getComparisonTable(onlyComplete, minColumns, scope, false);
        if (table != null) {
            return ResponseEntity.ok().headers(headers).body(table);
        }
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("scope", scope);
        live.put("country_groups", java.util.List.of());
        live.putAll(MacroTopicsSnapshotService.snapshotMeta(null));
        return ResponseEntity.ok().headers(headers).body(live);
    }

    @GetMapping("/extra-tables")
    public ResponseEntity<Map<String, Object>> extraTables(
            @RequestParam(value = "refresh", defaultValue = "false") boolean refresh) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(snapshotService.getExtraTables(refresh));
    }

    @GetMapping("/series-preview")
    public Map<String, Object> seriesPreview(
            @RequestParam("catalog_id") String catalogId,
            @RequestParam("set_id") String setId,
            @RequestParam("geo") String geo,
            @RequestParam(value = "topic_id", required = false) String topicId) {
        Map<String, Object> preview = snapshotService.getSeriesPreview(catalogId, setId, geo, topicId);
        if (preview != null) {
            return preview;
        }
        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Snapshot makro temat zatim neni k dispozici.");
    }
}
