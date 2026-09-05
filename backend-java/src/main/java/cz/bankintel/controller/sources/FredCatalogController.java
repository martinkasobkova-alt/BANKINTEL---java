package cz.bankintel.controller.sources;

import cz.bankintel.domain.dto.SourceDtos.SourceCreateRequest;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.service.sources.SourceService;
import cz.bankintel.sources.fred.FredCatalogService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * FRED katalog — kategorie a řady Fed St. Louis ({@code /api/fred/catalog}).
 * Port {@code fred_catalog_routes.py}.
 */
@RestController
@RequestMapping("/api/fred/catalog")
@RequiredArgsConstructor
public class FredCatalogController {

    private static final Pattern SERIES_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]+$");
    private static final String FRED_API_PUBLIC_ROOT = "https://api.stlouisfed.org/fred";

    private final FredCatalogService fredCatalogService;
    private final SourceService sourceService;
    private final AdminAccess adminAccess;

    @GetMapping({"", "/"})
    public ResponseEntity<Map<String, Object>> getCatalog() {
        if (!fredCatalogService.hasApiKey()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(fredCatalogService.missingKeyPayload());
        }
        try {
            Map<String, Object> tree = fredCatalogService.getRootCatalog(false);
            Map<String, Object> out = new LinkedHashMap<>(tree);
            out.put("fetched_at", fredCatalogService.rootFetchedAtEpochSeconds());
            out.put("cached", true);
            out.put("ttl_seconds", fredCatalogService.rootTtlSeconds());
            out.put("fred_api_configured", true);
            out.put("fred_api_key_configured", true);
            out.put("fred_http_timeout_sec", fredCatalogService.httpTimeoutSeconds());
            return ResponseEntity.ok(out);
        } catch (Exception ex) {
            return upstreamError(ex);
        }
    }

    @GetMapping("/expand")
    public ResponseEntity<Map<String, Object>> expand(
            @RequestParam("category_id") int categoryId,
            @RequestParam(value = "refresh", required = false, defaultValue = "false") boolean refresh) {
        if (!fredCatalogService.hasApiKey()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(fredCatalogService.missingKeyPayload());
        }
        try {
            Map<String, Object> tree = fredCatalogService.expandCategory(categoryId, refresh);
            Map<String, Object> out = new LinkedHashMap<>(tree);
            out.put("fetched_at", System.currentTimeMillis() / 1000.0);
            out.put("cached", !refresh);
            out.put("ttl_seconds", fredCatalogService.expandTtlSeconds());
            out.put("fred_api_key_configured", true);
            out.put("fred_api_configured", true);
            out.put("fred_http_timeout_sec", fredCatalogService.httpTimeoutSeconds());
            return ResponseEntity.ok(out);
        } catch (Exception ex) {
            return upstreamError(ex);
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh() {
        adminAccess.requireAdmin();
        if (!fredCatalogService.hasApiKey()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(fredCatalogService.missingKeyPayload());
        }
        fredCatalogService.invalidateCaches();
        try {
            Map<String, Object> tree = fredCatalogService.getRootCatalog(true);
            Map<String, Object> out = new LinkedHashMap<>(tree);
            out.put("fetched_at", fredCatalogService.rootFetchedAtEpochSeconds());
            out.put("cached", false);
            out.put("ttl_seconds", fredCatalogService.rootTtlSeconds());
            out.put("fred_api_configured", true);
            out.put("fred_api_key_configured", true);
            out.put("fred_http_timeout_sec", fredCatalogService.httpTimeoutSeconds());
            return ResponseEntity.ok(out);
        } catch (Exception ex) {
            return upstreamError(ex);
        }
    }

    @PostMapping("/add-source")
    public ResponseEntity<Map<String, Object>> addSource(@RequestBody Map<String, Object> payload) {
        adminAccess.requireAdmin();
        if (!fredCatalogService.hasApiKey()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(fredCatalogService.missingKeyPayload());
        }

        String code = stringOrBlank(payload.get("set_id"));
        if (code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatný FRED series_id.");
        }
        if (code.contains("||")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Neplatný kód — vyberte konkrétní časovou řadu (ne kategorii).");
        }
        if (!SERIES_ID_PATTERN.matcher(code).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatný FRED series_id.");
        }

        String displayName = stringOrBlank(payload.get("name"));
        if (displayName.isBlank()) {
            displayName = "FRED · " + code;
        }
        Integer refreshInterval = toInteger(payload.get("refresh_interval_minutes"));
        if (refreshInterval == null) {
            refreshInterval = 1440;
        }
        Boolean active = toBoolean(payload.get("active"));
        if (active == null) {
            active = true;
        }

        SourceCreateRequest request = new SourceCreateRequest(
                displayName,
                "fred",
                FRED_API_PUBLIC_ROOT,
                "/series/observations",
                "GET",
                "none",
                null,
                Map.of("Accept", "application/json", "User-Agent", "banking-bi/1.0"),
                Map.of("series_id", code, "file_type", "json"),
                refreshInterval,
                active,
                displayName,
                null);

        Map<String, Object> created = sourceService.createSource(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.get("id"));
        out.put("name", displayName);
        out.put("set_id", code);
        out.put("full_path", code);
        return ResponseEntity.ok(out);
    }

    private ResponseEntity<Map<String, Object>> upstreamError(Exception ex) {
        HttpStatus status = HttpStatus.BAD_GATEWAY;
        if (ex instanceof IOException) {
            status = HttpStatus.BAD_GATEWAY;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", "FRED upstream error");
        // `detail` frontend zobrazuje uživateli — dřív tu končila syrová Java výjimka
        // ("ConnectException: null"). Technický popis zůstává, jen vedle v `technical_detail`.
        payload.put("detail", "Katalog FRED se teď nepodařilo načíst. Zkuste to prosím znovu později.");
        payload.put("technical_detail", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        payload.put("source", "FRED");
        payload.put("upstream_unavailable", true);
        payload.put("configured", fredCatalogService.hasApiKey());
        payload.put("fred_api_key_configured", fredCatalogService.hasApiKey());
        payload.put("fred_api_configured", fredCatalogService.hasApiKey());
        return ResponseEntity.status(status).body(payload);
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
        String raw = String.valueOf(value).trim().toLowerCase();
        if ("true".equals(raw)) {
            return true;
        }
        if ("false".equals(raw)) {
            return false;
        }
        return null;
    }
}
