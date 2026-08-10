package cz.bankintel.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Data-driven search lexicons loaded from {@code classpath:catalog/*.json}. */
public final class CatalogSearchLexicon {

    private static final Logger log = LoggerFactory.getLogger(CatalogSearchLexicon.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> GENERIC_TOKENS = loadGenericTokens();
    private static final Map<String, List<String>> REQUIRED_TOKEN_RELATED = loadRequiredTokenRelated();
    private static final CommodityLexicon COMMODITY = loadCommodityLexicon();

    public static final double GENERIC_TOKEN_WEIGHT_FACTOR = 0.25;

    private CatalogSearchLexicon() {}

    public static Set<String> genericTokens() {
        return GENERIC_TOKENS;
    }

    public static boolean isGenericToken(String token) {
        return GENERIC_TOKENS.contains(CatalogTextUtils.foldAscii(token));
    }

    public static Map<String, List<String>> requiredTokenRelated() {
        return REQUIRED_TOKEN_RELATED;
    }

    public static List<String> relatedSurfaces(String token) {
        return REQUIRED_TOKEN_RELATED.getOrDefault(CatalogTextUtils.foldAscii(token), List.of());
    }

    public static boolean commodityQuery(String query) {
        String folded = CatalogTextUtils.foldAscii(query);
        if (folded.isBlank()) {
            return false;
        }
        for (String marker : nullToEmpty(COMMODITY.queryMarkers())) {
            if (folded.contains(marker)) {
                return true;
            }
        }
        boolean priceish = folded.contains("price") || folded.contains("cena") || folded.contains("ceny");
        boolean energyish = folded.contains("gas") || folded.contains("plyn") || folded.contains("oil")
                || folded.contains("ropa") || folded.contains("energy") || folded.contains("energie")
                || folded.contains("commodit") || folded.contains("komodit");
        return priceish && energyish;
    }

    /** English commodity terms implied by query stems (no full-phrase hardcoding). */
    public static List<String> commodityEnglishTerms(String query) {
        String folded = CatalogTextUtils.foldAscii(query);
        Set<String> out = new LinkedHashSet<>();
        Map<String, List<String>> stems = nullToEmptyMap(COMMODITY.stemTranslations());
        for (String tok : folded.split("[\\s,;/]+")) {
            if (tok.length() < 2) {
                continue;
            }
            List<String> mapped = stems.get(tok);
            if (mapped != null) {
                out.addAll(mapped);
            }
        }
        for (Map.Entry<String, List<String>> entry : stems.entrySet()) {
            if (entry.getKey().length() >= 3 && folded.contains(entry.getKey())) {
                out.addAll(entry.getValue());
            }
        }
        for (String marker : nullToEmpty(COMMODITY.queryMarkers())) {
            if (marker.length() >= 4 && folded.contains(marker) && !nullToEmptyMap(COMMODITY.stemTranslations()).containsKey(marker)) {
                out.add(marker);
            }
        }
        return new ArrayList<>(out);
    }

    /** Bonus when row title strongly matches commodity topic from query. */
    public static int commodityTitleBonus(String query, String titleFolded) {
        if (titleFolded == null || titleFolded.isBlank() || !commodityQuery(query)) {
            return 0;
        }
        List<String> enTerms = commodityEnglishTerms(query);
        if (enTerms.isEmpty()) {
            return 0;
        }
        int best = 0;
        for (List<String> group : nullToEmptyMap(COMMODITY.titleMatchGroups()).values()) {
            int hits = 0;
            for (String kw : group) {
                String kf = CatalogTextUtils.foldAscii(kw);
                if (kf.length() >= 4 && titleFolded.contains(kf)) {
                    hits++;
                }
            }
            if (hits > 0) {
                best = Math.max(best, 80 + hits * 35);
            }
        }
        for (String term : enTerms) {
            String tf = CatalogTextUtils.foldAscii(term);
            if (tf.length() < 4 || !commodityTermMatchesTitle(tf, titleFolded)) {
                continue;
            }
            best = Math.max(best, 60 + tf.length() * 4);
        }
        return best;
    }

    /** Avoid false positives like „crude steelmaking" for oil queries. */
    private static boolean commodityTermMatchesTitle(String termFolded, String titleFolded) {
        if ("crude".equals(termFolded)) {
            return titleFolded.contains("crude oil")
                    || titleFolded.contains("petroleum")
                    || titleFolded.contains("brent")
                    || titleFolded.contains("wti")
                    || titleFolded.contains("dubai");
        }
        if ("oil".equals(termFolded)) {
            return titleFolded.contains(" oil") || titleFolded.startsWith("oil") || titleFolded.contains("oil price");
        }
        return titleFolded.contains(termFolded);
    }

    /** Deterministic index probe terms — English phrases for FTS recall. */
    public static List<String> commoditySurfacesForStem(String stem) {
        String key = CatalogTextUtils.foldAscii(stem);
        return nullToEmptyMap(COMMODITY.stemTranslations()).getOrDefault(key, List.of());
    }

    public static List<String> buildIndexProbeTerms(String query, int maxTerms) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String expanded : CatalogSearchSynonyms.expandSearchQueries(query)) {
            String ef = CatalogTextUtils.foldAscii(expanded);
            if (ef.length() >= 2 && seen.add(ef)) {
                out.add(expanded);
            }
        }
        for (String en : commodityEnglishTerms(query)) {
            if (seen.add(CatalogTextUtils.foldAscii(en))) {
                out.add(en);
            }
        }
        for (String needle : CatalogTextUtils.needlesFromQuery(query)) {
            String nf = CatalogTextUtils.foldAscii(needle);
            if (nf.length() >= 3 && !isGenericToken(nf) && seen.add(nf)) {
                out.add(needle);
            }
        }
        return out.stream().limit(maxTerms).toList();
    }

    public static List<String> primaryTopicTokens(String query, Map<String, Object> geoIntent) {
        List<String> required = CatalogRequiredTokenScorer.extractRequiredTokens(query);
        Set<String> geoToks = geoTokenSet(geoIntent);
        List<String> out = new ArrayList<>();
        for (String tok : required) {
            String f = CatalogTextUtils.foldAscii(tok);
            if (f.length() < 3 || isGenericToken(f) || isGeoToken(f, geoToks)) {
                continue;
            }
            out.add(f);
        }
        return out;
    }

    private static Set<String> geoTokenSet(Map<String, Object> geoIntent) {
        Set<String> toks = new LinkedHashSet<>();
        for (String term : CatalogRequiredTokenScorer.geoScoringTerms(geoIntent)) {
            toks.add(CatalogTextUtils.foldAscii(term));
            for (String w : term.split("\\s+")) {
                if (w.length() >= 2) {
                    toks.add(CatalogTextUtils.foldAscii(w));
                }
            }
        }
        return toks;
    }

    private static boolean isGeoToken(String token, Set<String> geoTokens) {
        if (token == null || token.isBlank() || geoTokens == null || geoTokens.isEmpty()) {
            return false;
        }
        if (geoTokens.contains(token)) {
            return true;
        }
        for (String geoToken : geoTokens) {
            if (geoToken.length() >= 4 && token.startsWith(geoToken)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> loadGenericTokens() {
        try (InputStream in = resource("/catalog/generic_tokens.json")) {
            if (in == null) {
                return Set.of("index", "rate", "ratio", "price", "cena", "ceny");
            }
            List<String> raw = MAPPER.readValue(in, new TypeReference<>() {});
            Set<String> out = new LinkedHashSet<>();
            for (String item : raw) {
                out.add(CatalogTextUtils.foldAscii(item));
            }
            return Collections.unmodifiableSet(out);
        } catch (Exception ex) {
            log.warn("generic_tokens.json load failed: {}", ex.getMessage());
            return Set.of("index", "rate", "ratio", "price", "cena", "ceny");
        }
    }

    private static Map<String, List<String>> loadRequiredTokenRelated() {
        try (InputStream in = resource("/catalog/required_token_related.json")) {
            if (in == null) {
                return Map.of();
            }
            Map<String, List<String>> raw = MAPPER.readValue(in, new TypeReference<>() {});
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : raw.entrySet()) {
                out.put(CatalogTextUtils.foldAscii(e.getKey()), e.getValue());
            }
            return Collections.unmodifiableMap(out);
        } catch (Exception ex) {
            log.warn("required_token_related.json load failed: {}", ex.getMessage());
            return Map.of();
        }
    }

    private static CommodityLexicon loadCommodityLexicon() {
        try (InputStream in = resource("/catalog/commodity_lexicon.json")) {
            if (in == null) {
                return CommodityLexicon.empty();
            }
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(in);
            List<String> markers = readStringListNode(root.get("query_markers"));
            Map<String, List<String>> stems = readStringMapNode(root.get("stem_translations"));
            Map<String, List<String>> groups = readStringMapNode(root.get("title_match_groups"));
            return new CommodityLexicon(markers, stems, groups);
        } catch (Exception ex) {
            log.warn("commodity_lexicon.json load failed: {}", ex.getMessage());
            return CommodityLexicon.empty();
        }
    }

    private static List<String> readStringListNode(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> {
            String text = item.asText("").trim();
            if (!text.isBlank()) {
                out.add(text);
            }
        });
        return out;
    }

    private static Map<String, List<String>> readStringMapNode(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> out.put(entry.getKey(), readStringListNode(entry.getValue())));
        return out;
    }

    private static List<String> nullToEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }

    private static Map<String, List<String>> nullToEmptyMap(Map<String, List<String>> map) {
        return map == null ? Map.of() : map;
    }

    private static InputStream resource(String path) {
        return CatalogSearchLexicon.class.getResourceAsStream(path);
    }

    public record CommodityLexicon(
            List<String> queryMarkers,
            Map<String, List<String>> stemTranslations,
            Map<String, List<String>> titleMatchGroups) {
        static CommodityLexicon empty() {
            return new CommodityLexicon(List.of(), Map.of(), Map.of());
        }
    }
}
