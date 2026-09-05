package cz.bankintel.controller.sources;

import cz.bankintel.domain.dto.SourceDtos.SourceCreateRequest;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.service.sources.SourceService;
import cz.bankintel.sources.bis.BisCatalogService;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * BIS Statistics katalog — dataflows a řady ({@code /api/bis/*}). Port {@code bis_catalog_routes.py}.
 */
@RestController
@RequiredArgsConstructor
public class BisCatalogController {

    private final BisCatalogService bisCatalogService;
    private final SourceService sourceService;
    private final AdminAccess adminAccess;

    @GetMapping({"/api/bis/catalog", "/api/bis/catalog/"})
    public ResponseEntity<Map<String, Object>> getCatalog() {
        try {
            Map<String, Object> tree = bisCatalogService.getCatalog(false);
            Map<String, Object> out = new LinkedHashMap<>(tree);
            out.put(
                    "bis_stats_api_notice_cs",
                    "BIS Stats API poskytuje statistiky centrálních bank přes rozhraní SDMX 2.1 u `stats.bis.org/api/v1`.");
            out.put("fetched_at", bisCatalogService.dataflowFetchedAtEpochSeconds());
            out.put("cached", true);
            out.put("ttl_seconds", bisCatalogService.dataflowTtlSeconds());
            return ResponseEntity.ok(out);
        } catch (Exception ex) {
            return upstreamError(ex, "BIS catalog");
        }
    }

    @GetMapping("/api/bis/catalog/availability-summary")
    public ResponseEntity<Map<String, Object>> getAvailabilitySummary() {
        return ResponseEntity.ok(bisCatalogService.getAvailabilitySummary());
    }

    @GetMapping("/api/bis/catalog/ref-areas")
    public ResponseEntity<Map<String, Object>> getRefAreas() {
        return ResponseEntity.ok(bisCatalogService.getRefAreas());
    }

    @GetMapping("/api/bis/catalog/series")
    public ResponseEntity<Map<String, Object>> getSeries(
            @RequestParam(value = "dataflow", required = false) String dataflow,
            @RequestParam(value = "flow", required = false) String flowAlias,
            @RequestParam(value = "refresh", required = false, defaultValue = "false") boolean refresh,
            @RequestParam(value = "availability_only", required = false, defaultValue = "false") boolean availabilityOnly,
            @RequestParam(value = "ref_areas", required = false) String refAreasRaw) {
        String flow = firstNonBlank(dataflow, flowAlias);
        if (flow.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Chybí parametr dataflow (nebo alias flow), např. ?dataflow=WS_EER");
        }
        Set<String> refAreas = parseRefAreas(refAreasRaw);
        try {
            Map<String, Object> tree = bisCatalogService.getSeriesForDataflow(flow, refresh, availabilityOnly, refAreas);
            Map<String, Object> out = new LinkedHashMap<>(tree);
            String notice =
                    "BIS Stats API (SDMX RESTful) používá cestu `/data/{dataflow}/{key}/all` s odpovědí Generic Data XML. "
                            + "Pro náhled a zdroje se doporučuje řetězec set_id `BIS|dataflow|key`.";
            if (availabilityOnly || !refAreas.isEmpty()) {
                notice += " Aplikován filtr podle dostupnosti / REF_AREA.";
            }
            out.put("bis_stats_api_notice_cs", notice);
            out.put("fetched_at", System.currentTimeMillis() / 1000.0);
            out.put("cached", !refresh);
            out.put("ttl_seconds", bisCatalogService.seriesTtlSeconds());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            return upstreamError(ex, "BIS series");
        }
    }

    @GetMapping("/api/bis/catalog/dataflow/{flow}/structure")
    public ResponseEntity<Map<String, Object>> getStructure(
            @PathVariable("flow") String flow,
            @RequestParam(value = "refresh", required = false, defaultValue = "false") boolean refresh) {
        if (refresh) {
            adminAccess.requireAdmin();
        }
        try {
            return ResponseEntity.ok(bisCatalogService.getDataflowStructure(flow, refresh));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "BIS dotaz byl přerušen.");
        }
    }

    @GetMapping("/api/bis/catalog/dataflow/{flow}/dimensions")
    public ResponseEntity<Map<String, Object>> getDimensions(
            @PathVariable("flow") String flow,
            @RequestParam(value = "refresh", required = false, defaultValue = "false") boolean refresh) {
        if (refresh) {
            adminAccess.requireAdmin();
        }
        try {
            return ResponseEntity.ok(bisCatalogService.getDataflowDimensions(flow, refresh));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "BIS dotaz byl přerušen.");
        }
    }

    @PostMapping("/api/bis/catalog/refresh")
    public ResponseEntity<Map<String, Object>> refreshCatalog() {
        adminAccess.requireAdmin();
        bisCatalogService.invalidateCaches();
        try {
            Map<String, Object> tree = bisCatalogService.getCatalog(true);
            Map<String, Object> out = new LinkedHashMap<>(tree);
            out.put("fetched_at", bisCatalogService.dataflowFetchedAtEpochSeconds());
            out.put("cached", false);
            out.put("ttl_seconds", bisCatalogService.dataflowTtlSeconds());
            return ResponseEntity.ok(out);
        } catch (Exception ex) {
            return upstreamError(ex, "BIS refresh");
        }
    }

    @PostMapping("/api/bis/catalog/add-source")
    public ResponseEntity<Map<String, Object>> addSource(@RequestBody Map<String, Object> payload) {
        adminAccess.requireAdmin();
        Map<String, Object> safePayload = payload != null ? payload : Map.of();
        String setId = stringOrBlank(safePayload.get("set_id"));
        if (setId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí set_id.");
        }
        if (setId.contains("||DATAFLOW")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Datový tok nelze přidat přímo — nejdřív sestavte řadu výběrem dimenzí.");
        }

        BisCatalogService.ParsedSetId parsed;
        try {
            parsed = BisCatalogService.parseSetId(setId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        Map<String, Object> normalizedQp = BisCatalogService.normalizeBisQueryParams(safePayload, "sync");
        if ("all".equalsIgnoreCase(parsed.key())
                && !normalizedQp.containsKey("startPeriod")
                && !normalizedQp.containsKey("endPeriod")
                && !normalizedQp.containsKey("firstNObservations")
                && !normalizedQp.containsKey("lastNObservations")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dotaz BIS s key=all může vrátit příliš velký objem dat. Zadejte období nebo limit.");
        }

        String canonical = BisCatalogService.composeCatalogSetId(parsed.flow(), parsed.key());
        String displayName = stringOrBlank(safePayload.get("name"));
        if (displayName.isBlank()) {
            displayName = "BIS · " + parsed.flow() + " · " + parsed.key();
        }

        Integer refreshInterval = toInteger(safePayload.get("refresh_interval_minutes"));
        if (refreshInterval == null) {
            refreshInterval = 1440;
        }
        Boolean active = toBoolean(safePayload.get("active"));
        if (active == null) {
            active = true;
        }

        String endpoint = "/data/" + encodePathSegment(parsed.flow()) + "/" + encodePathSegment(parsed.key()) + "/all";

        SourceCreateRequest request = new SourceCreateRequest(
                displayName,
                "bis",
                BisCatalogService.BIS_API_ROOT,
                endpoint,
                "GET",
                "none",
                null,
                Map.of("Accept", BisCatalogService.BIS_GENERICDATA_ACCEPT, "User-Agent", "banking-bi/1.0"),
                normalizedQp,
                refreshInterval,
                active,
                displayName,
                null);

        Map<String, Object> created = sourceService.createSource(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.get("id"));
        out.put("name", displayName);
        out.put("set_id", canonical);
        out.put("full_path", canonical);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/api/bis/dataflows")
    public ResponseEntity<Map<String, Object>> proxyDataflows() {
        try {
            return ResponseEntity.ok(bisCatalogService.proxyDataflowsFlat());
        } catch (Exception ex) {
            return upstreamError(ex, "BIS dataflows proxy");
        }
    }

    @GetMapping("/api/bis/dataflow/{flow}/structure")
    public ResponseEntity<Void> proxyStructureAlias(@PathVariable("flow") String flow) {
        String cleanFlow = stringOrBlank(flow);
        if (cleanFlow.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí kód toku.");
        }
        String encodedFlow = encodePathSegment(cleanFlow);
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .header("Location", "/api/bis/catalog/dataflow/" + encodedFlow + "/structure")
                .build();
    }

    @GetMapping("/api/bis/data")
    public ResponseEntity<Map<String, Object>> proxyDataPreview(
            @RequestParam("flow") String flow,
            @RequestParam("key") String key,
            @RequestParam(value = "lastNObservations", required = false, defaultValue = "12") Integer lastNObservations) {
        try {
            return ResponseEntity.ok(bisCatalogService.proxyDataPreview(flow, key, lastNObservations));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            return upstreamError(ex, "BIS data proxy");
        }
    }

    private ResponseEntity<Map<String, Object>> upstreamError(Exception ex, String source) {
        HttpStatus status = HttpStatus.BAD_GATEWAY;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", source + " error");
        // `detail` frontend zobrazuje uživateli — dřív tu končila syrová Java výjimka
        // ("ConnectException: null"). Technický popis zůstává, jen vedle v `technical_detail`.
        payload.put("detail", "Katalog BIS se teď nepodařilo načíst. Zkuste to prosím znovu později.");
        payload.put("technical_detail", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        payload.put("source", "BIS");
        payload.put("upstream_unavailable", true);
        return ResponseEntity.status(status).body(payload);
    }

    private static Set<String> parseRefAreas(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return List.of(raw.split(",")).stream()
                .map(v -> v == null ? "" : v.trim().toUpperCase(Locale.ROOT))
                .filter(v -> !v.isBlank())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            String value = stringOrBlank(candidate);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String raw = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(raw)) {
            return true;
        }
        if ("false".equals(raw)) {
            return false;
        }
        return null;
    }
}
