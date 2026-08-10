package cz.bankintel.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.regex.Pattern;

/**
 * Query intent classification and intent bonus/penalty — port of
 * {@code Bankoapp-main/backend/services/catalog_search_query_intent.py}.
 */
public final class CatalogQueryIntent {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, IntentGroup> INTENT_GROUPS = loadIntentGroups();
    private static final IntentRefinementRules REFINEMENT_RULES = loadIntentRefinementRules();

    private static final Set<String> SKIP = Set.of(
            "v", "ve", "na", "do", "od", "pro", "pri", "u", "a", "i", "o",
            "the", "in", "of", "and", "for", "to");

    /** Token → surface-expansion lexicons, loaded from {@code catalog/intent_lexicon.json}. */
    private static final IntentLexicon LEXICON = loadIntentLexicon();
    private static final Map<String, List<String>> METRIC_LEXICON = LEXICON.metricLexicon();
    private static final Map<String, List<String>> DOMAIN_LEXICON = LEXICON.domainLexicon();

    private CatalogQueryIntent() {}

    public record IntentTerm(String raw, List<String> surfaces) {}

    public record QueryIntent(
            List<IntentTerm> metricTerms,
            List<IntentTerm> domainTerms,
            List<IntentTerm> geoTerms,
            List<String> activeGroups) {}

    public record IntentScoreAdjustments(
            int intentBonus,
            int negativePenalty,
            int metricHits,
            int domainHits,
            int geoIntentHits,
            List<String> activeGroups,
            List<String> metricTokens,
            List<String> domainTokens) {}

    public record IntentGroup(List<String> positive, List<String> negative) {}

    public static QueryIntent classifyQueryIntent(String query) {
        return classifyQueryIntent(query, null);
    }

    public static QueryIntent classifyQueryIntent(String query, Map<String, Object> geoIntent) {
        Map<String, Object> gi = geoIntent == null ? CatalogGeoIntent.detectGeoIntent(query) : geoIntent;
        List<String> tokens = tokenize(query);
        String qf = CatalogTextUtils.foldAscii(query);
        Set<String> geoAliasSurfaces = geoAliasSurfaces(gi);

        List<IntentTerm> metricTerms = new ArrayList<>();
        List<IntentTerm> domainTerms = new ArrayList<>();
        List<IntentTerm> geoTerms = geoTermsFromIntent(gi);
        Set<String> classified = new LinkedHashSet<>();

        for (String tok : tokens) {
            if (geoAliasSurfaces.contains(tok) || classified.contains(tok)) {
                if (geoAliasSurfaces.contains(tok)) {
                    classified.add(tok);
                }
                continue;
            }
            if (METRIC_LEXICON.containsKey(tok)) {
                metricTerms.add(new IntentTerm(tok, surfacesForToken(tok, METRIC_LEXICON)));
                classified.add(tok);
                continue;
            }
            if (DOMAIN_LEXICON.containsKey(tok)) {
                domainTerms.add(new IntentTerm(tok, surfacesForToken(tok, DOMAIN_LEXICON)));
                classified.add(tok);
            }
        }

        List<String> contentTokens = new ArrayList<>();
        for (String tok : tokens) {
            if (!geoAliasSurfaces.contains(tok)) {
                contentTokens.add(tok);
            }
        }
        List<String> active = detectActiveGroups(qf, contentTokens);
        active = refineActiveGroups(qf, contentTokens, active);
        return new QueryIntent(metricTerms, domainTerms, geoTerms, active);
    }

    public static IntentScoreAdjustments computeIntentScoreAdjustments(
            String hay, String queryRaw, Map<String, Object> geoIntent) {
        QueryIntent intent = classifyQueryIntent(queryRaw, geoIntent);
        String hayF = CatalogTextUtils.foldAscii(hay);

        int metricHits = countTermHits(hayF, intent.metricTerms());
        int domainHits = countTermHits(hayF, intent.domainTerms());
        int geoHits = countTermHits(hayF, intent.geoTerms());

        int nMetric = intent.metricTerms().size();
        int nDomain = intent.domainTerms().size();
        boolean expectsBoth = nMetric > 0 && nDomain > 0;

        int intentBonus = 0;
        if (expectsBoth) {
            if (metricHits > 0 && domainHits > 0) {
                intentBonus = 340 + metricHits * 55 + domainHits * 55;
            } else if (domainHits > 0 && metricHits == 0) {
                intentBonus = -220 - domainHits * 40;
            } else if (metricHits > 0 && domainHits == 0) {
                intentBonus = 60 + metricHits * 25;
            }
        } else if (nMetric > 0) {
            if (metricHits > 0) {
                intentBonus = 120 + metricHits * 35;
            } else if (domainHits > 0 && metricHits == 0) {
                intentBonus = -80;
            }
        } else if (nDomain > 0) {
            if (domainHits > 0) {
                intentBonus = 40 + domainHits * 20;
            }
        }

        Set<String> negGroupsHit = new LinkedHashSet<>();
        for (String group : intent.activeGroups()) {
            if (groupNegativeHit(hayF, group)) {
                negGroupsHit.add(group);
            }
        }
        int negativePenalty = 160 * negGroupsHit.size();

        if (nMetric > 0 && metricHits == 0 && domainHits > 0 && !expectsBoth) {
            negativePenalty += 90;
        }
        negativePenalty = Math.min(negativePenalty, 240);

        List<String> metricTokens = intent.metricTerms().stream().map(IntentTerm::raw).toList();
        List<String> domainTokens = intent.domainTerms().stream().map(IntentTerm::raw).toList();
        return new IntentScoreAdjustments(
                intentBonus,
                negativePenalty,
                metricHits,
                domainHits,
                geoHits,
                List.copyOf(intent.activeGroups()),
                metricTokens,
                domainTokens);
    }

    private static List<String> tokenize(String query) {
        String q = CatalogTextUtils.foldAscii(query == null ? "" : query);
        String[] parts = q.split("[\\s,;/]+");
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String part : parts) {
            String tok = part.strip();
            if (tok.length() < 2 || SKIP.contains(tok) || !seen.add(tok)) {
                continue;
            }
            out.add(tok);
        }
        return out;
    }

    private static List<String> surfacesForToken(String token, Map<String, List<String>> lexicon) {
        String t = CatalogTextUtils.foldAscii(token);
        Set<String> seen = new LinkedHashSet<>();
        seen.add(t);
        List<String> surfaces = new ArrayList<>();
        surfaces.add(t);
        for (String surface : lexicon.getOrDefault(t, List.of())) {
            String sf = CatalogTextUtils.foldAscii(surface);
            if (sf.length() >= 2 && seen.add(sf)) {
                surfaces.add(sf);
            }
        }
        return surfaces;
    }

    private static Set<String> geoAliasSurfaces(Map<String, Object> geoIntent) {
        Set<String> surfaces = new LinkedHashSet<>();
        for (IntentTerm term : geoTermsFromIntent(geoIntent)) {
            surfaces.addAll(term.surfaces());
        }
        return surfaces;
    }

    private static List<IntentTerm> geoTermsFromIntent(Map<String, Object> geoIntent) {
        List<String> codes = resolvedGeoCodes(geoIntent);
        Set<String> seen = new LinkedHashSet<>();
        List<String> surfaces = new ArrayList<>();
        for (String code : codes) {
            if (seen.add(code.toLowerCase(Locale.ROOT))) {
                surfaces.add(code.toLowerCase(Locale.ROOT));
            }
            for (String alias : CatalogCountryAliasRegistry.aliasesFor(code)) {
                String a = CatalogTextUtils.foldAscii(alias);
                if (a.length() >= 2 && !a.endsWith("*") && seen.add(a)) {
                    surfaces.add(a);
                }
            }
        }
        if (surfaces.isEmpty()) {
            return List.of();
        }
        return List.of(new IntentTerm("geo", List.copyOf(surfaces)));
    }

    private static List<String> resolvedGeoCodes(Map<String, Object> geoIntent) {
        List<String> codes = new ArrayList<>();
        Object singleCode = geoIntent == null ? null : geoIntent.get("country_code");
        String cc1 = singleCode == null ? "" : String.valueOf(singleCode).strip();
        if (!cc1.isEmpty()) {
            codes.add(cc1.toUpperCase(Locale.ROOT));
        }
        Object raw = geoIntent == null ? null : geoIntent.get("country_codes");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String cu = String.valueOf(item).strip().toUpperCase(Locale.ROOT);
                if (!cu.isEmpty() && !codes.contains(cu)) {
                    codes.add(cu);
                }
            }
        }
        return codes;
    }

    private static List<String> detectActiveGroups(String queryFolded, List<String> tokens) {
        List<String> active = new ArrayList<>();
        String blob = " " + queryFolded + " ";
        for (Map.Entry<String, IntentGroup> entry : INTENT_GROUPS.entrySet()) {
            for (String pos : entry.getValue().positive()) {
                String pf = CatalogTextUtils.foldAscii(pos);
                if (pf.length() < 2) {
                    continue;
                }
                if (pf.equals(queryFolded)
                        || blob.contains(" " + pf + " ")
                        || tokens.stream().anyMatch(t -> matchesPositiveToken(pf, t))) {
                    active.add(entry.getKey());
                    break;
                }
            }
        }
        return active;
    }

    private static boolean matchesPositiveToken(String pf, String t) {
        return pf.equals(t) || (!pf.contains(" ") && (pf.startsWith(t) || t.startsWith(pf)));
    }

    private static List<String> refineActiveGroups(String queryFolded, List<String> tokens, List<String> active) {
        LinkedHashSet<String> out = new LinkedHashSet<>(active);
        for (IntentRefinementRule rule : REFINEMENT_RULES.rules()) {
            if (!refinementRuleMatches(rule, queryFolded, tokens, out)) {
                continue;
            }
            out.removeAll(rule.remove());
            out.addAll(rule.add());
        }
        return new ArrayList<>(out);
    }

    private static boolean refinementRuleMatches(
            IntentRefinementRule rule, String queryFolded, List<String> tokens, Set<String> active) {
        if (!active.containsAll(rule.whenActive())) {
            return false;
        }
        for (String group : rule.unlessActive()) {
            if (active.contains(group)) {
                return false;
            }
        }
        if (!rule.whenAnyTerms().isEmpty()
                && rule.whenAnyTerms().stream().noneMatch(set -> termSetHit(set, queryFolded, tokens))) {
            return false;
        }
        for (String set : rule.whenAllTerms()) {
            if (!termSetHit(set, queryFolded, tokens)) {
                return false;
            }
        }
        for (String set : rule.unlessAnyTerms()) {
            if (termSetHit(set, queryFolded, tokens)) {
                return false;
            }
        }
        return true;
    }

    private static boolean termSetHit(String setName, String queryFolded, List<String> tokens) {
        List<String> terms = REFINEMENT_RULES.termSets().getOrDefault(setName, List.of(setName));
        for (String raw : terms) {
            String term = CatalogTextUtils.foldAscii(raw);
            if (term.length() < 2) {
                continue;
            }
            String trimmed = term.trim();
            if (queryFolded.contains(term) || (!trimmed.isEmpty() && queryFolded.contains(trimmed))) {
                return true;
            }
            if (!trimmed.contains(" ") && tokens.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static int countTermHits(String hayF, List<IntentTerm> terms) {
        if (hayF.isEmpty() || terms.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (IntentTerm term : terms) {
            if (term.surfaces().stream().anyMatch(surface -> hayContainsSurface(hayF, surface))) {
                hits++;
            }
        }
        return hits;
    }

    private static boolean hayContainsSurface(String hayF, String surface) {
        String s = CatalogTextUtils.foldAscii(surface);
        if (s.length() < 2) {
            return false;
        }
        String h = " " + hayF + " ";
        return hayF.contains(s) || h.contains(" " + s + " ");
    }

    private static boolean hayContainsWord(String hayF, String surface) {
        String s = CatalogTextUtils.foldAscii(surface);
        if (s.length() < 3) {
            return false;
        }
        return Pattern.compile("(?<!\\w)" + Pattern.quote(s)).matcher(hayF).find();
    }

    private static boolean groupNegativeHit(String hayF, String groupName) {
        IntentGroup group = INTENT_GROUPS.get(groupName);
        if (group == null) {
            return false;
        }
        for (String neg : group.negative()) {
            String nf = CatalogTextUtils.foldAscii(neg);
            if (nf.length() >= 3
                    && CatalogTextUtils.containsTokenOrPhrase(hayF, nf)
                    && !negativeIsPartOfPositivePhrase(hayF, nf, group.positive())) {
                return true;
            }
        }
        return false;
    }

    private static boolean negativeIsPartOfPositivePhrase(String hayF, String negativeFolded, List<String> positives) {
        String negativeNorm = CatalogTextUtils.normalizeTokenBoundaries(negativeFolded);
        if (negativeNorm.length() < 3) {
            return false;
        }
        for (String positive : positives) {
            String positiveNorm = CatalogTextUtils.normalizeTokenBoundaries(positive);
            if (positiveNorm.equals(negativeNorm) || !positiveNorm.contains(" ")) {
                continue;
            }
            if (!CatalogTextUtils.containsWholeTokenOrPhrase(positiveNorm, negativeNorm)) {
                continue;
            }
            if (CatalogTextUtils.containsWholeTokenOrPhrase(hayF, positiveNorm)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, IntentGroup> loadIntentGroups() {
        try (InputStream in = CatalogQueryIntent.class.getResourceAsStream("/catalog/intent_groups.json")) {
            if (in == null) {
                return Map.of();
            }
            Map<String, Map<String, List<String>>> raw =
                    MAPPER.readValue(in, new TypeReference<LinkedHashMap<String, Map<String, List<String>>>>() {});
            Map<String, IntentGroup> out = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, List<String>>> entry : raw.entrySet()) {
                Map<String, List<String>> group = entry.getValue();
                out.put(
                        entry.getKey(),
                        new IntentGroup(
                                List.copyOf(group.getOrDefault("positive", List.of())),
                                List.copyOf(group.getOrDefault("negative", List.of()))));
            }
            return Collections.unmodifiableMap(out);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    /** Data-driven replacement for topic-specific refinement branches. */
    private record IntentRefinementRules(Map<String, List<String>> termSets, List<IntentRefinementRule> rules) {
        static IntentRefinementRules empty() {
            return new IntentRefinementRules(Map.of(), List.of());
        }
    }

    private record IntentRefinementRule(
            String description,
            List<String> whenActive,
            List<String> unlessActive,
            List<String> whenAnyTerms,
            List<String> whenAllTerms,
            List<String> unlessAnyTerms,
            List<String> add,
            List<String> remove) {}

    private static IntentRefinementRules loadIntentRefinementRules() {
        try (InputStream in = CatalogQueryIntent.class.getResourceAsStream("/catalog/intent_refinement_rules.json")) {
            if (in == null) {
                return IntentRefinementRules.empty();
            }
            JsonNode root = MAPPER.readTree(in);
            Map<String, List<String>> termSets = readStringListMap(root.path("term_sets"));
            List<IntentRefinementRule> rules = new ArrayList<>();
            JsonNode rawRules = root.path("rules");
            if (rawRules.isArray()) {
                for (JsonNode node : rawRules) {
                    rules.add(new IntentRefinementRule(
                            node.path("description").asText(""),
                            readStringList(node.path("when_active")),
                            readStringList(node.path("unless_active")),
                            readStringList(node.path("when_any_terms")),
                            readStringList(node.path("when_all_terms")),
                            readStringList(node.path("unless_any_terms")),
                            readStringList(node.path("add")),
                            readStringList(node.path("remove"))));
                }
            }
            return new IntentRefinementRules(termSets, List.copyOf(rules));
        } catch (Exception ex) {
            return IntentRefinementRules.empty();
        }
    }

    private static Map<String, List<String>> readStringListMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            List<String> values = readStringList(entry.getValue());
            if (!values.isEmpty()) {
                out.put(entry.getKey(), values);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    /** Deserialized shape of {@code catalog/intent_lexicon.json}. */
    private record IntentLexicon(Map<String, List<String>> metricLexicon, Map<String, List<String>> domainLexicon) {
        static IntentLexicon empty() {
            return new IntentLexicon(Map.of(), Map.of());
        }
    }

    private static IntentLexicon loadIntentLexicon() {
        try (InputStream in = CatalogQueryIntent.class.getResourceAsStream("/catalog/intent_lexicon.json")) {
            if (in == null) {
                return IntentLexicon.empty();
            }
            Map<String, Map<String, List<String>>> raw =
                    MAPPER.readValue(in, new TypeReference<LinkedHashMap<String, Map<String, List<String>>>>() {});
            Map<String, List<String>> metric = normalizeLexicon(raw.get("metric_lexicon"));
            Map<String, List<String>> domain = normalizeLexicon(raw.get("domain_lexicon"));
            return new IntentLexicon(metric, domain);
        } catch (Exception ex) {
            return IntentLexicon.empty();
        }
    }

    private static Map<String, List<String>> normalizeLexicon(Map<String, List<String>> raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
            out.put(entry.getKey(), entry.getValue() == null ? List.of() : List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }
}
