package cz.bankintel.sources.ecb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EcbAvailabilityService {

    private final ObjectMapper objectMapper;
    private final EcbCuratedCatalog catalog;

    private Map<String, Boolean> pairs;
    private Map<String, Boolean> templates;
    private Map<String, Object> meta = Map.of();
    private boolean strictProbedPolicy;

    @PostConstruct
    void load() {
        pairs = null;
        templates = Map.of();
        meta = Map.of();
        strictProbedPolicy = false;
        try (InputStream in = new ClassPathResource("data/ecb_availability.json").getInputStream()) {
            Map<String, Object> raw = objectMapper.readValue(in, new TypeReference<>() {});
            meta = new LinkedHashMap<>();
            for (String key : List.of("version", "generated_at", "policy")) {
                if (raw.get(key) != null) {
                    meta.put(key, raw.get(key));
                }
            }
            if (raw.get("templates") instanceof Map<?, ?> tpl) {
                templates = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : tpl.entrySet()) {
                    templates.put(String.valueOf(entry.getKey()), Boolean.TRUE.equals(entry.getValue()));
                }
            }
            if (raw.get("pairs") instanceof Map<?, ?> pairMap) {
                pairs = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : pairMap.entrySet()) {
                    pairs.put(String.valueOf(entry.getKey()), Boolean.TRUE.equals(entry.getValue()));
                }
            }
            strictProbedPolicy = isStrictProbedPolicy();
        } catch (Exception ex) {
            log.warn("ECB availability load failed: {}", ex.getMessage());
        }
    }

    public String availabilityRevision() {
        Object rev = meta.get("generated_at");
        if (rev == null) {
            rev = meta.get("version");
        }
        return rev != null ? String.valueOf(rev) : "";
    }

    public boolean filteringEnabled() {
        return strictProbedPolicy && pairs != null && !pairs.isEmpty();
    }

    public boolean isPairAvailable(String country, String indicatorId) {
        String c = catalog.validateCountryCode(country);
        String indId = stringOrBlank(indicatorId);
        Map<String, Object> ind = catalog.indicatorById(indId);
        if (ind == null || !catalog.structuralOk(c, indId, ind)) {
            return false;
        }
        Target target = resolveTarget(indId, ind);
        if (target == null || !catalog.structuralOk(c, target.baseId(), target.baseInd())) {
            return false;
        }
        if (!templateOk(target.baseId())) {
            return false;
        }
        if (strictProbedPolicy && pairs != null) {
            return Boolean.TRUE.equals(pairs.get(pairKey(c, indId)));
        }
        if (catalog.harmonizedIndicator(ind) && catalog.euNationalMemberCodes().contains(c)) {
            return true;
        }
        if (pairs != null) {
            Boolean hit = pairs.get(pairKey(c, indId));
            if (hit != null) {
                return hit;
            }
        }
        return false;
    }

    public Map<String, Map<String, Object>> availableIndicators(String country) {
        String c = catalog.validateCountryCode(country);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : catalog.getIndicators().entrySet()) {
            if (isPairAvailable(c, entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    public boolean isCuratedSetAvailable(String setId) {
        ParsedCurated parsed = parseCuratedSetId(setId);
        if (parsed == null) {
            return false;
        }
        return isPairAvailable(parsed.country(), parsed.indicatorId());
    }

    public record ParsedCurated(String country, String indicatorId) {}

    public static boolean isCuratedSetIdFormat(String setId) {
        return parseCuratedSetId(setId) != null;
    }

    public static ParsedCurated parseCuratedSetId(String setId) {
        return parseCuratedSetId(setId, null, null);
    }

    public static ParsedCurated parseCuratedSetId(String setId, Map<String, Object> params) {
        return parseCuratedSetId(setId, params, null);
    }

    public static ParsedCurated parseCuratedSetId(String setId, Map<String, Object> params, EcbCuratedCatalog catalog) {
        String s = setId != null ? setId.trim() : "";
        if (s.toLowerCase(Locale.ROOT).startsWith("ecb:")) {
            String[] parts = s.split(":", 3);
            if (parts.length != 3) {
                return null;
            }
            String country = parts[1].trim().toUpperCase(Locale.ROOT);
            String indicatorId = parts[2].trim();
            if (country.isBlank() || indicatorId.isBlank()) {
                return null;
            }
            return new ParsedCurated(country, indicatorId);
        }
        if (!s.toLowerCase(Locale.ROOT).startsWith("ecb_") || catalog == null) {
            return null;
        }
        String indicatorId = resolveUnderscoreIndicatorId(s.substring(4), catalog);
        if (indicatorId.isBlank()) {
            return null;
        }
        String country = countryFromParams(params);
        if (country.isBlank()) {
            return null;
        }
        return new ParsedCurated(country, indicatorId);
    }

    static String resolveUnderscoreIndicatorId(String rest, EcbCuratedCatalog catalog) {
        String candidate = rest != null ? rest.trim() : "";
        if (candidate.isBlank()) {
            return "";
        }
        String canonical = canonicalIndicatorId(candidate, catalog);
        if (!canonical.isBlank()) {
            return canonical;
        }
        int idx = candidate.indexOf('_');
        while (idx >= 0 && idx < candidate.length() - 1) {
            String suffix = candidate.substring(idx + 1);
            canonical = canonicalIndicatorId(suffix, catalog);
            if (!canonical.isBlank()) {
                return canonical;
            }
            idx = candidate.indexOf('_', idx + 1);
        }
        return "";
    }

    private static String canonicalIndicatorId(String indicatorId, EcbCuratedCatalog catalog) {
        String canonical = stringOrBlank(catalog.canonicalIndicatorId(indicatorId));
        if (!canonical.isBlank()) {
            return canonical;
        }
        return catalog.indicatorById(indicatorId) != null ? indicatorId : "";
    }

    private static String countryFromParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        Object qpObj = params.get("query_params");
        Map<?, ?> qp = qpObj instanceof Map<?, ?> map ? map : Map.of();
        for (String key : List.of("country", "ecb_country", "territory")) {
            String fromQp = stringOrBlank(qp.get(key));
            if (!fromQp.isBlank()) {
                return fromQp.toUpperCase(Locale.ROOT);
            }
            String fromParams = stringOrBlank(params.get(key));
            if (!fromParams.isBlank()) {
                return fromParams.toUpperCase(Locale.ROOT);
            }
        }
        return "";
    }

    private boolean isStrictProbedPolicy() {
        String policy = stringOrBlank(meta.get("policy"));
        if ("probed_pairs_full_matrix".equals(policy)) {
            return true;
        }
        try {
            return Integer.parseInt(stringOrBlank(meta.get("version"))) >= 4;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private boolean templateOk(String baseId) {
        if (strictProbedPolicy && pairs != null) {
            String suffix = ":" + baseId;
            for (Map.Entry<String, Boolean> entry : pairs.entrySet()) {
                if (entry.getKey().endsWith(suffix) && Boolean.TRUE.equals(entry.getValue())) {
                    return true;
                }
            }
        }
        if (templates.containsKey(baseId)) {
            return Boolean.TRUE.equals(templates.get(baseId));
        }
        return pairs == null;
    }

    private Target resolveTarget(String indicatorId, Map<String, Object> ind) {
        String derive = stringOrBlank(ind.get("derive_yoy_from"));
        if (!derive.isBlank()) {
            Map<String, Object> base = catalog.indicatorById(derive);
            if (base == null) {
                return null;
            }
            return new Target(derive, base);
        }
        return new Target(indicatorId, ind);
    }

    private static String pairKey(String country, String indicatorId) {
        return country + ":" + indicatorId;
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private record Target(String baseId, Map<String, Object> baseInd) {}
}
