package cz.bankintel.controller.sources;

import cz.bankintel.domain.dto.SourceDtos.SourceCreateRequest;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.service.sources.SourceService;
import cz.bankintel.sources.commodities.WorldbankCommoditiesService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Komodity — Pink Sheet, CMO prognózy ({@code /api/commodities/*}). Port {@code commodities_routes.py}. */
@RestController
@RequestMapping("/api/commodities")
@RequiredArgsConstructor
public class CommoditiesController {

    private final WorldbankCommoditiesService commoditiesService;
    private final SourceService sourceService;
    private final AdminAccess adminAccess;

    @GetMapping("/hub")
    public Map<String, Object> hub() {
        commoditiesService.ensureCommoditiesCache();
        return commoditiesService.buildHubPayload();
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        commoditiesService.ensureCommoditiesCache();
        return commoditiesService.buildCombinedCatalogTree();
    }

    @GetMapping("/pink-sheet")
    public Map<String, Object> pinkSheet() {
        commoditiesService.ensureCommoditiesCache();
        return commoditiesService.buildPinkSheetTree();
    }

    @GetMapping("/forecasts")
    public Map<String, Object> forecasts() {
        commoditiesService.ensureCommoditiesCache();
        Map<String, Object> tree = commoditiesService.buildForecastsTree();
        Map<String, Object> data = commoditiesService.loadForecasts();
        Map<String, Object> out = new LinkedHashMap<>(tree);
        out.put("items", data.getOrDefault("items", java.util.List.of()));
        return out;
    }

    @GetMapping("/preview")
    public Map<String, Object> preview(
            @RequestParam("set_id") String setId,
            @RequestParam(value = "kind", defaultValue = "actual") String kind) {
        commoditiesService.ensureCommoditiesCache();
        Map<String, Object> payload = commoditiesService.buildPreview(setId, kind);
        if (payload == null) {
            String k = stringOrBlank(kind).toLowerCase(Locale.ROOT);
            String sid = stringOrBlank(setId);
            if ("forecast".equals(k) || sid.startsWith("FCST|")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Prognóza nebyla nalezena.");
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pink Sheet řada nebyla nalezena.");
        }
        return payload;
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody(required = false) Map<String, Object> payload) {
        adminAccess.requireAdmin();
        Map<String, Object> safe = payload != null ? payload : Map.of();
        String pinkUrl = stringOrBlank(safe.get("pink_sheet_url"));
        String fcUrl = stringOrBlank(safe.get("forecasts_url"));
        Map<String, Object> meta = commoditiesService.refreshAllFromUrls(
                pinkUrl.isBlank() ? null : pinkUrl, fcUrl.isBlank() ? null : fcUrl);
        return Map.of("ok", true, "meta", meta);
    }

    @PostMapping("/add-source")
    public Map<String, Object> addSource(@RequestBody Map<String, Object> payload) {
        adminAccess.requireAdmin();
        commoditiesService.ensureCommoditiesCache();
        Map<String, Object> safe = payload != null ? payload : Map.of();
        String sid = stringOrBlank(safe.get("set_id"));
        if (sid.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí set_id.");
        }
        String kind = stringOrBlank(safe.get("kind"));
        if (kind.isBlank()) {
            kind = sid.startsWith("FCST|") ? "forecast" : "actual";
        }
        kind = kind.toLowerCase(Locale.ROOT);

        String display;
        Map<String, Object> qp = new LinkedHashMap<>();
        if ("forecast".equals(kind) || sid.startsWith("FCST|")) {
            Map<String, Object> item = commoditiesService.forecastItemById(sid);
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CMO prognóza nebyla nalezena — nejdřív obnovte cache.");
            }
            display = firstNonBlank(stringOrBlank(safe.get("name")), "CMO Forecast · " + item.get("name"));
            qp.put("kind", "forecast");
            qp.put("pink_sheet_code", sid);
            qp.put("commodity_code", sid);
        } else {
            Map<String, Object> meta = commoditiesService.pinkSheetSeriesById(sid);
            if (meta == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pink Sheet řada nebyla nalezena — nejdřív obnovte cache.");
            }
            display = firstNonBlank(stringOrBlank(safe.get("name")), "Pink Sheet · " + meta.get("name"));
            qp.put("kind", "actual");
            qp.put("pink_sheet_code", sid);
            qp.put("commodity_code", sid);
        }

        SourceCreateRequest request = new SourceCreateRequest(
                display,
                "worldbank_pink_sheet",
                "",
                "",
                "GET",
                "none",
                null,
                Map.of(),
                qp,
                toInteger(safe.get("refresh_interval_minutes"), 10080),
                toBoolean(safe.get("active"), true),
                display,
                null);

        Map<String, Object> created = sourceService.createSource(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.get("id"));
        out.put("name", display);
        out.put("set_id", sid);
        out.put("kind", kind);
        return out;
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static Integer toInteger(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static Boolean toBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        String raw = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(raw)) {
            return true;
        }
        if ("false".equals(raw)) {
            return false;
        }
        return fallback;
    }
}
