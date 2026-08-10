package cz.bankintel.controller;

import cz.bankintel.config.BankIntelProperties;
import cz.bankintel.service.platform.BankIntelMaintenanceService;
import cz.bankintel.service.platform.MirrorDataHealthService;
import cz.bankintel.util.BankIntelEnvVars;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@RestController
@RequestMapping("/api/health")
public class HealthVersionController {

    private static final Instant STARTED = Instant.now();

    private final BankIntelProperties properties;
    private final MirrorDataHealthService mirrorDataHealthService;
    private final BankIntelMaintenanceService maintenanceService;
    private final RequestMappingHandlerMapping requestMappingHandlerMapping;

    public HealthVersionController(
            BankIntelProperties properties,
            MirrorDataHealthService mirrorDataHealthService,
            BankIntelMaintenanceService maintenanceService,
            RequestMappingHandlerMapping requestMappingHandlerMapping) {
        this.properties = properties;
        this.mirrorDataHealthService = mirrorDataHealthService;
        this.maintenanceService = maintenanceService;
        this.requestMappingHandlerMapping = requestMappingHandlerMapping;
    }

    @GetMapping("/version")
    public Map<String, Object> version() {
        String commit = firstNonBlank(
                BankIntelEnvVars.get("RENDER_GIT_COMMIT"),
                BankIntelEnvVars.get("GIT_COMMIT"),
                BankIntelEnvVars.get("SOURCE_VERSION"));

        Map<String, Object> authCookiePolicy = new LinkedHashMap<>();
        authCookiePolicy.put("environment_production", isProductionEnvironment());
        authCookiePolicy.put("samesite", properties.cookie().sameSite());
        authCookiePolicy.put("secure", properties.cookie().secure());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("git_commit", commit.isBlank() ? null : commit);
        out.put("auth_cookie_policy", authCookiePolicy);
        out.put("started_at", STARTED.toString());
        out.put("uptime_seconds", (System.currentTimeMillis() - STARTED.toEpochMilli()) / 1000.0);
        return out;
    }

    @GetMapping("/platform")
    public Map<String, Object> platform() {
        Map<String, Object> out = new LinkedHashMap<>(mirrorDataHealthService.buildHealthReport());
        out.put("maintenance_enabled", maintenanceService.maintenanceEnabled());
        out.put("ok", true);
        return out;
    }

    /**
     * Port of {@code api_health} (backend/server.py, ř. 533): liveness + diagnostika, které
     * katalogové routy jsou v tomto procesu skutečně zaregistrované.
     *
     * <p>Poznámka k portu: {@code pymupdf}/{@code pdfplumber}/{@code text_anchor_bbox} jsou volitelné
     * Python knihovny bez ekvivalentu v Java portu (ten používá vždy dostupný PDFBox bez podpory
     * vektorových tabulek — viz {@link cz.bankintel.service.me.PdfExtractService}), proto se zde
     * hlásí jako nedostupné namísto zavádějícího natvrdo nastaveného {@code true}.
     */
    @GetMapping({"", "/"})
    public Map<String, Object> health() {
        Set<String> paths = registeredPaths();

        Map<String, Object> catalogs = new LinkedHashMap<>();
        catalogs.put("bis", paths.contains("/api/bis/catalog"));
        catalogs.put("imf", paths.contains("/api/imf/catalog"));
        catalogs.put("oecd", paths.contains("/api/oecd/catalog"));
        catalogs.put("csu", paths.contains("/api/csu/catalog"));

        boolean magazinePagePreviewRegistered =
                paths.stream().anyMatch(p -> p.contains("/issues/") && p.endsWith("/page-preview"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("fred_api_key_configured", isFredApiKeyConfigured());
        out.put("pymupdf_available", false);
        out.put("pdfplumber_available", false);
        out.put("text_anchor_bbox_available", false);
        out.put("magazine_page_preview_registered", magazinePagePreviewRegistered);
        out.put("catalog_deep_search_registered", paths.contains("/api/catalog/deep-search"));
        out.put("catalogs", catalogs);
        return out;
    }

    /**
     * Port of {@code health_cors} (backend/server.py, ř. 479): diagnostika CORS — ověří, že
     * middleware vidí Origin a zná počet povolených původů (žádné secrety).
     */
    @GetMapping("/cors")
    public Map<String, Object> healthCors(HttpServletRequest request) {
        String originHeader = request.getHeader("Origin");
        String originReceived = originHeader != null ? originHeader.strip() : "";
        String normalizedOrigin = normalizeCorsOrigin(originHeader);

        List<String> allowedOrigins = properties.cors().originList().stream()
                .map(HealthVersionController::normalizeCorsOrigin)
                .filter(o -> !o.isBlank())
                .distinct()
                .toList();
        boolean originAllowed = !normalizedOrigin.isBlank() && allowedOrigins.contains(normalizedOrigin);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("origin_received", originReceived);
        out.put("allowed_origins", allowedOrigins);
        out.put("origin_allowed", originAllowed);
        return out;
    }

    private Set<String> registeredPaths() {
        Set<String> out = new LinkedHashSet<>();
        for (RequestMappingInfo info : requestMappingHandlerMapping.getHandlerMethods().keySet()) {
            if (info.getPathPatternsCondition() != null) {
                info.getPathPatternsCondition()
                        .getPatterns()
                        .forEach(pattern -> out.add(pattern.getPatternString()));
            } else if (info.getPatternsCondition() != null) {
                out.addAll(info.getPatternsCondition().getPatterns());
            }
        }
        return out;
    }

    private static boolean isFredApiKeyConfigured() {
        String key = BankIntelEnvVars.get("FRED_API_KEY");
        return key != null && !key.isBlank();
    }

    /**
     * Port of {@code normalize_cors_origin_string} (backend/security_config.py): trim BOM/whitespace,
     * strip surrounding quotes/backticks, strip trailing slashes, lowercase.
     */
    private static String normalizeCorsOrigin(String value) {
        if (value == null) {
            return "";
        }
        String s = value.replace("\uFEFF", "").strip();
        s = stripChars(s, " \t\r\n\"'`");
        if (s.isEmpty()) {
            return "";
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private static String stripChars(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isProductionEnvironment() {
        String env = BankIntelEnvVars.get("ENVIRONMENT").toLowerCase();
        return "production".equals(env) || "prod".equals(env);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }
}
