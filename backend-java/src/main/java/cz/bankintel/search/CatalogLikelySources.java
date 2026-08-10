package cz.bankintel.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Heuristic likely catalog sources — port of
 * {@code Bankoapp-main/backend/services/catalog_likely_sources.py} (Wave 2 rules).
 *
 * <p>Data-driven: per-query rules are loaded from {@code catalog/likely_sources_rules.json} at
 * startup (same pattern as {@link CatalogQueryIntent}'s {@code intent_groups.json}), so this
 * class stays a thin loader + matching algorithm.
 */
public final class CatalogLikelySources {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Wave 2 LIKELY_RULES: banking, FX, inflation, unemployment (+ defaults) — loaded from JSON. */
    public static final List<Rule> LIKELY_RULES = loadLikelyRules();

    public static final List<String> DEFAULT_SOURCES_ORDER = CatalogSourceRegistry.LIKELY_SOURCES_DEFAULT_ORDER;

    private static final Map<String, String> ALIASES = Map.of(
            "imf_data", "imf",
            "world_bank", "data360",
            "world_bank_data360", "data360",
            "ecb", "ecb2",
            "oecd", "oecd4");

    public record Rule(List<String> keys, List<String> sources) {}

    private CatalogLikelySources() {}

    /** Port of {@code infer_likely_catalog_sources}. */
    public static List<String> inferLikelyCatalogSources(String userQuery, List<String> expandTopics) {
        String combined = combinedQueryText(userQuery, expandTopics);
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        addRuleMatchedSources(out, seen, combined);

        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(userQuery);
        for (String d : DEFAULT_SOURCES_ORDER) {
            if (seen.add(d)) {
                out.add(d);
            }
        }
        List<String> filtered = CatalogGeoIntent.filterCatalogSourceList(out, geo);
        filtered = CatalogGeoIntent.boostSourcesForGeoIntent(filtered, geo);
        if (filtered.size() > 14) {
            return filtered.subList(0, 14);
        }
        return filtered;
    }

    public static List<String> inferLikelyCatalogSources(String userQuery) {
        return inferLikelyCatalogSources(userQuery, null);
    }

    /**
     * Returns only sources from rules that actually matched the query/expanded terms.
     * Unlike {@link #inferLikelyCatalogSources(String, List)}, this intentionally omits
     * the default UI-like fallback order, so callers can distinguish strong intent
     * routing evidence from "nothing matched, use defaults".
     */
    public static List<String> inferRuleMatchedCatalogSources(String userQuery, List<String> expandTopics) {
        String combined = combinedQueryText(userQuery, expandTopics);
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addRuleMatchedSources(out, seen, combined);
        if (out.isEmpty()) {
            return List.of();
        }
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(userQuery);
        List<String> filtered = CatalogGeoIntent.filterCatalogSourceList(out, geo);
        filtered = CatalogGeoIntent.boostSourcesForGeoIntent(filtered, geo);
        return filtered.size() > 14 ? filtered.subList(0, 14) : filtered;
    }

    public static List<String> inferRuleMatchedCatalogSources(String userQuery) {
        return inferRuleMatchedCatalogSources(userQuery, null);
    }

    /** Bonus 0–0.18 by position in likely list — port of {@code source_boost_weight}. */
    public static double sourceBoostWeight(String catalogId, List<String> likelySourcesNorm) {
        String cid = catalogId == null ? "" : catalogId.strip().toLowerCase(Locale.ROOT);
        int idx = likelySourcesNorm.indexOf(cid);
        if (idx < 0) {
            return 0.0;
        }
        return Math.max(0.05, 0.18 - idx * 0.012);
    }

    private static String ql(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String combinedQueryText(String userQuery, List<String> expandTopics) {
        String ql = ql(userQuery);
        String extra = ql(String.join(" ", expandTopics == null ? List.of() : expandTopics));
        return " " + ql + " " + extra + " ";
    }

    private static void addRuleMatchedSources(List<String> out, Set<String> seen, String combined) {
        for (Rule rule : LIKELY_RULES) {
            if (rule.keys().stream().anyMatch(key -> combinedMatchesKey(combined, key))) {
                for (String src : rule.sources()) {
                    String sid = normSourceId(src);
                    if (sid != null && seen.add(sid)) {
                        out.add(sid);
                    }
                }
            }
        }
    }

    private static String normSourceId(String raw) {
        String s = ql(raw).strip().replace(".", "").replace(",", "");
        if (s.isEmpty()) {
            return null;
        }
        s = ALIASES.getOrDefault(s, s);
        return CatalogSourceRegistry.normalizeSearchSource(s);
    }

    private static boolean combinedMatchesKey(String combined, String key) {
        String ks = key == null ? "" : key.strip();
        String kl = ks.toLowerCase(Locale.ROOT);
        if (kl.startsWith("^") || ks.contains("\\b") || ks.contains("\\d") || ks.contains("(?:") || ks.startsWith("[")) {
            try {
                return Pattern.compile(ks, Pattern.CASE_INSENSITIVE).matcher(combined).find();
            } catch (Exception ex) {
                return false;
            }
        }
        return combined.contains(ks) || combined.contains(kl);
    }

    private static List<Rule> loadLikelyRules() {
        try (InputStream in = CatalogLikelySources.class.getResourceAsStream("/catalog/likely_sources_rules.json")) {
            if (in == null) {
                return List.of();
            }
            List<Map<String, Object>> raw =
                    MAPPER.readValue(in, new TypeReference<List<Map<String, Object>>>() {});
            List<Rule> out = new ArrayList<>();
            for (Map<String, Object> entry : raw) {
                List<String> matchTerms = stringList(entry.get("match_terms"));
                List<String> sources = stringList(entry.get("sources"));
                out.add(new Rule(matchTerms, sources));
            }
            return List.copyOf(out);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return List.copyOf(out);
    }
}
