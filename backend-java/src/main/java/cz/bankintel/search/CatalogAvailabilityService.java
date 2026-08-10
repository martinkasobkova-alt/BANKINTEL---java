package cz.bankintel.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.util.BankIntelEnvVars;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Catalog availability index — port {@code catalog_availability.py} / {@code catalog_availability_routes.py}. */
@Service
@RequiredArgsConstructor
public class CatalogAvailabilityService {

    static final List<String> ALL_SOURCES = CatalogSourceRegistry.AVAILABILITY_ALL_SOURCES;

    private static final Set<String> VALID_STATES = Set.of("alive", "dead", "needs_dimension", "unknown");

    private final CatalogSearchProperties searchProperties;
    private final ObjectMapper objectMapper;
    private final AdminAccess adminAccess;

    public void requireAdminOrBuildToken(String token) {
        String cfg = BankIntelEnvVars.get("CATALOG_AVAILABILITY_BUILD_TOKEN");
        if (cfg != null && !cfg.isBlank() && cfg.equals(token != null ? token.strip() : "")) {
            return;
        }
        try {
            adminAccess.requireAdmin();
        } catch (ResponseStatusException ex) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Vyžadováno admin oprávnění nebo platný build token.");
        }
    }

    public Map<String, Object> status(String source) {
        List<String> sources;
        if (source != null && !source.isBlank()) {
            sources = List.of(normalizeSource(source));
        } else {
            sources = ALL_SOURCES;
        }
        Map<String, Map<String, Integer>> perSource = new LinkedHashMap<>();
        for (String src : sources) {
            perSource.put(src, availabilitySummary(src));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filter_enabled", availabilityFilterEnabled());
        out.put("sources", perSource);
        out.put("availability_dir", availabilityDir().toString());
        return out;
    }

    public Map<String, Object> build(String source, int limit, int offset, int sleepMs, String token) {
        requireAdminOrBuildToken(token);
        String src = normalizeSource(source);
        if (!ALL_SOURCES.contains(src)) {
            return Map.of(
                    "ok", false,
                    "error", "neznámý zdroj: " + src,
                    "known", ALL_SOURCES);
        }
        String ts = Instant.now().toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("source", src);
        out.put("limit", limit);
        out.put("offset", offset);
        out.put("generated_at", ts);
        out.put(
                "error",
                "Batch probe builder ještě není portován do Java — použijte Python "
                        + "catalog_availability_builder.py nebo noční job.");
        out.put(
                "limits",
                "Status endpoint čte existující JSON z disku; build vyžaduje HTTP probe per zdroj "
                        + "(port z Python services/catalog_availability_builder.py).");
        out.put("summary", Map.of("alive", 0, "dead", 0, "needs_dimension", 0, "unknown", 0));
        out.put("total_in_index", countIndexed(src));
        return out;
    }

    public Map<String, Integer> availabilitySummary(String source) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String st : VALID_STATES) {
            counts.put(st, 0);
        }
        Map<String, Map<String, Object>> series = loadSourceSeries(normalizeSource(source));
        for (Map<String, Object> rec : series.values()) {
            String st = String.valueOf(rec.getOrDefault("state", "unknown")).strip().toLowerCase();
            counts.merge(VALID_STATES.contains(st) ? st : "unknown", 1, Integer::sum);
        }
        return counts;
    }

    public boolean availabilityFilterEnabled() {
        return BankIntelEnvVars.isTruthy("CATALOG_AVAILABILITY_FILTER_ENABLED");
    }

    public Path availabilityDir() {
        String custom = BankIntelEnvVars.get("CATALOG_AVAILABILITY_DIR");
        if (custom != null && !custom.isBlank()) {
            return Path.of(custom).toAbsolutePath().normalize();
        }
        return searchProperties.indexDir().resolve("catalog_availability");
    }

    private int countIndexed(String source) {
        return loadSourceSeries(source).size();
    }

    private Map<String, Map<String, Object>> loadSourceSeries(String source) {
        Path path = availabilityDir().resolve(source + ".json");
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            Map<String, Object> root = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            Object raw = root.get("series");
            if (!(raw instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> rec) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) rec;
                    out.put(String.valueOf(e.getKey()), cast);
                }
            }
            return out;
        } catch (IOException ex) {
            return Map.of();
        }
    }

    private static String normalizeSource(String source) {
        String src = source != null ? source.strip().toLowerCase() : "";
        return switch (src) {
            case "world_bank_data360" -> "data360";
            case "ecb" -> "ecb2";
            case "oecd4" -> "oecd";
            default -> src;
        };
    }
}
