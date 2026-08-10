package cz.bankintel.sources.ecb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EcbCuratedCatalog {

    public static final String ECB_BASE_URL = "https://data-api.ecb.europa.eu/service/data";
    public static final String ECB_BROWSE_ROOT = "ECB · země a ukazatele";

    private final ObjectMapper objectMapper;

    @Getter
    private Map<String, Map<String, Object>> countries = Map.of();

    @Getter
    private Map<String, String> categories = Map.of();

    @Getter
    private Map<String, Map<String, Object>> indicators = Map.of();

    @Getter
    private Map<String, String> indicatorAliases = Map.of();

    @Getter
    private List<String> snapshotKeys = List.of();

    private Map<String, String> stbsGeoForCountry = Map.of();
    private Map<String, String> sdmxRefArea = Map.of();
    private Map<String, String> bsiCounterpartArea = Map.of();
    private Set<String> nonHicpRefAreas = Set.of();
    private Set<String> euMemberCodes = Set.of();
    private Set<String> euAggregateCodes = Set.of("U2", "U6");
    private Set<String> euNationalMemberCodes = Set.of();

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource("data/ecb_curated_catalog.json").getInputStream()) {
            Map<String, Object> raw = objectMapper.readValue(in, new TypeReference<>() {});
            countries = castCountryMap(raw.get("countries"));
            categories = castStringMap(raw.get("categories"));
            indicators = castIndicatorMap(raw.get("indicators"));
            indicatorAliases = normalizeIndicatorAliases(castStringMap(raw.get("indicator_aliases")));
            Object snap = raw.get("snapshot_keys");
            if (snap instanceof List<?> list) {
                snapshotKeys = list.stream().map(String::valueOf).toList();
            }
            stbsGeoForCountry = castStringMap(raw.get("stbs_geo_for_country"));
            sdmxRefArea = castStringMap(raw.get("sdmx_ref_area"));
            bsiCounterpartArea = castStringMap(raw.get("bsi_counterpart_area"));
            if (raw.get("non_hicp_ref_areas") instanceof List<?> nonHicp) {
                nonHicpRefAreas = new HashSet<>();
                for (Object item : nonHicp) {
                    nonHicpRefAreas.add(String.valueOf(item).trim().toUpperCase(Locale.ROOT));
                }
            }
            euMemberCodes = new HashSet<>();
            for (Map.Entry<String, Map<String, Object>> entry : countries.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue().get("eu"))) {
                    euMemberCodes.add(entry.getKey());
                }
            }
            euNationalMemberCodes = new HashSet<>(euMemberCodes);
            euNationalMemberCodes.removeAll(euAggregateCodes);
        } catch (Exception ex) {
            log.warn("ECB curated catalog load failed: {}", ex.getMessage());
        }
    }

    public String validateCountryCode(String code) {
        String c = code != null ? code.trim().toUpperCase(Locale.ROOT) : "";
        if (!countries.containsKey(c)) {
            throw new IllegalArgumentException("Neznámý kód země '" + c + "'.");
        }
        return c;
    }

    public Map<String, Object> countryInfo(String code) {
        return countries.getOrDefault(code, Map.of());
    }

    public Map<String, Object> indicatorById(String indicatorId) {
        Map<String, Object> rec = indicators.get(canonicalIndicatorId(indicatorId));
        return rec != null ? new LinkedHashMap<>(rec) : null;
    }

    public String canonicalIndicatorId(String indicatorId) {
        String id = stringOrBlank(indicatorId);
        if (id.isBlank()) {
            return "";
        }
        String direct = canonicalIndicatorIdFromCandidate(id);
        if (!direct.isBlank()) {
            return direct;
        }
        if (id.toLowerCase(Locale.ROOT).startsWith("ecb_")) {
            String candidate = id.substring(4);
            String resolved = canonicalIndicatorIdFromCandidate(candidate);
            if (!resolved.isBlank()) {
                return resolved;
            }
            int idx = candidate.indexOf('_');
            while (idx >= 0 && idx < candidate.length() - 1) {
                resolved = canonicalIndicatorIdFromCandidate(candidate.substring(idx + 1));
                if (!resolved.isBlank()) {
                    return resolved;
                }
                idx = candidate.indexOf('_', idx + 1);
            }
        }
        return "";
    }

    public String composeCuratedSetId(String country, String indicatorId) {
        String c = validateCountryCode(country);
        String ind = stringOrBlank(indicatorId);
        if (ind.isBlank()) {
            throw new IllegalArgumentException("Chybí id ukazatele.");
        }
        return "ecb:" + c + ":" + ind;
    }

    public String sdmxRefArea(String country) {
        String c = validateCountryCode(country);
        return sdmxRefArea.getOrDefault(c, c);
    }

    public String bsiCounterpartArea(String country) {
        String c = validateCountryCode(country);
        return bsiCounterpartArea.getOrDefault(c, "U2");
    }

    public SdmxKey sdmxKeyForCountry(Map<String, Object> indicator, String country) {
        String c = validateCountryCode(country);
        String flow = stringOrBlank(indicator.get("flow"));
        String keyTpl = stringOrBlank(indicator.get("key"));
        if (keyTpl.isBlank() && indicator.get("derive_yoy_from") != null) {
            Map<String, Object> base = indicatorById(String.valueOf(indicator.get("derive_yoy_from")));
            if (base != null) {
                return sdmxKeyForCountry(base, c);
            }
        }
        String ref = sdmxRefArea(c);
        String cur = stringOrBlank(countryInfo(c).get("cur"));
        if (cur.isBlank()) {
            cur = "EUR";
        }
        String geo = stbsGeoForCountry.getOrDefault(c, c);
        String key = keyTpl
                .replace("{C2}", c + "2")
                .replace("{C}", ref)
                .replace("{CUR}", cur)
                .replace("{G}", geo)
                .replace("{BSI_CP}", bsiCounterpartArea(c));
        return new SdmxKey(flow, key);
    }

    public boolean structuralOk(String country, String indicatorId, Map<String, Object> ind) {
        if (Boolean.TRUE.equals(ind.get("hidden"))) {
            return false;
        }
        Object allowed = ind.get("countries");
        if (allowed instanceof List<?> list && !list.isEmpty()) {
            boolean match = false;
            for (Object item : list) {
                if (country.equals(String.valueOf(item).trim().toUpperCase(Locale.ROOT))) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                return false;
            }
        }
        String flow = stringOrBlank(ind.get("flow"));
        if ("STBS".equals(flow) && !stbsGeoForCountry.containsKey(country)) {
            return false;
        }
        if ("RTD".equals(flow) && !"U2".equals(country)) {
            return false;
        }
        if ("ICP".equals(flow) && nonHicpRefAreas.contains(country)) {
            return false;
        }
        if ("vynosy_10let".equals(indicatorId) && Set.of("US", "JP", "CH", "NO", "GB").contains(country)) {
            return false;
        }
        return true;
    }

    public record SdmxKey(String flow, String key) {}

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> castCountryMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() instanceof Map<?, ?> value) {
                out.put(String.valueOf(entry.getKey()), (Map<String, Object>) value);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> castIndicatorMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() instanceof Map<?, ?> value) {
                out.put(String.valueOf(entry.getKey()), (Map<String, Object>) value);
            }
        }
        return out;
    }

    private static Map<String, String> castStringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                out.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return out;
    }

    private Map<String, String> normalizeIndicatorAliases(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String alias = stringOrBlank(entry.getKey());
            String target = stringOrBlank(entry.getValue());
            if (alias.isBlank() || target.isBlank()) {
                continue;
            }
            if (indicators.containsKey(target)) {
                out.put(alias, target);
            }
        }
        return out;
    }

    private String canonicalIndicatorIdFromCandidate(String candidate) {
        String id = stringOrBlank(candidate);
        if (id.isBlank()) {
            return "";
        }
        if (indicators.containsKey(id)) {
            return id;
        }
        return indicatorAliases.getOrDefault(id, "");
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    public List<String> sortedCountryCodes() {
        List<String> codes = new ArrayList<>(countries.keySet());
        codes.sort(Comparator
                .comparing((String c) -> stringOrBlank(countries.get(c).get("name")).toLowerCase(Locale.ROOT))
                .thenComparing(c -> c));
        return codes;
    }

    public Map<String, String> categoryNameByKey() {
        return categories;
    }

    public Set<String> euMemberCodes() {
        return euMemberCodes;
    }

    public Set<String> euNationalMemberCodes() {
        return euNationalMemberCodes;
    }

    public boolean harmonizedIndicator(Map<String, Object> ind) {
        return ind.get("countries") == null;
    }
}
