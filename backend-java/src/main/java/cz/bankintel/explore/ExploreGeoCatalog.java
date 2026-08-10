package cz.bankintel.explore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExploreGeoCatalog {

    private static final List<Map<String, String>> COUNTRY_HINTS = List.of(
            Map.of("code", "CZ", "label_cs", "Česko"),
            Map.of("code", "DE", "label_cs", "Německo"),
            Map.of("code", "AT", "label_cs", "Rakousko"),
            Map.of("code", "PL", "label_cs", "Polsko"),
            Map.of("code", "SK", "label_cs", "Slovensko"),
            Map.of("code", "FR", "label_cs", "Francie"),
            Map.of("code", "US", "label_cs", "USA"),
            Map.of("code", "JP", "label_cs", "Japonsko"));

    private static final List<Map<String, String>> GEO_MODES = List.of(
            Map.of("id", "none", "label_cs", "Svět (globální kontext)"),
            Map.of("id", "countries", "label_cs", "Konkrétní země (jedna nebo více)"),
            Map.of("id", "continent", "label_cs", "Kontinent / region"));

    private final ObjectMapper objectMapper;

    private List<Map<String, Object>> managerSectors = List.of();
    private List<Map<String, Object>> continents = List.of();
    private List<Map<String, Object>> allCountries = List.of();
    private List<Map<String, Object>> countryGroups = List.of();

    @PostConstruct
    void load() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/explore/geo-catalog.json")) {
            if (in == null) {
                return;
            }
            Map<String, Object> raw = objectMapper.readValue(in, new TypeReference<>() {});
            managerSectors = castList(raw.get("manager_sectors"));
            continents = castList(raw.get("continents"));
            allCountries = castList(raw.get("all_countries"));
            countryGroups = castList(raw.get("country_groups"));
        }
    }

    public Map<String, Object> geoOptions() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("manager_sectors", managerSectors);
        out.put("geo_modes", GEO_MODES);
        out.put("continents", continents);
        out.put("all_countries", allCountries);
        out.put("country_groups", countryGroups);
        out.put("country_hints", COUNTRY_HINTS);
        return out;
    }

    public static List<Map<String, String>> countryHints() {
        return COUNTRY_HINTS;
    }

    /** Raw (id, label_cs) catalog segments - used to ground the query-understanding LLM prompt so
     * it can map a generic/colloquial term ("továrna") to a real segment instead of guessing
     * blind at Czech terminology it was never shown. */
    public List<Map<String, Object>> managerSectors() {
        return managerSectors;
    }

    public Map<String, Object> findSectorByIdOrLabel(String value) {
        String needle = normalize(value);
        if (needle.isBlank()) {
            return Map.of();
        }
        for (Map<String, Object> sector : managerSectors) {
            String id = normalize(String.valueOf(sector.getOrDefault("id", "")));
            String label = normalize(String.valueOf(sector.getOrDefault("label_cs", "")));
            if (needle.equals(id) || needle.equals(label) || label.contains(needle) || needle.contains(label)) {
                return sector;
            }
        }
        return Map.of();
    }

    public String countryLabel(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String upper = code.trim().toUpperCase();
        for (Map<String, Object> row : allCountries) {
            if (upper.equals(String.valueOf(row.getOrDefault("code", "")).toUpperCase())) {
                return String.valueOf(row.getOrDefault("label_cs", code));
            }
        }
        return code;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
