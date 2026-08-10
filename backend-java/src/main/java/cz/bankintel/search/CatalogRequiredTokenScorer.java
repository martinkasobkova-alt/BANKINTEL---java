package cz.bankintel.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Required-token soft-AND scoring — port of
 * {@code Bankoapp-main/backend/services/catalog_search_composite_score.py}.
 */
public final class CatalogRequiredTokenScorer {

    private static final Set<String> SOFT_AND_SKIP = Set.of(
            "v", "ve", "na", "do", "od", "pro", "pri", "u", "a", "i", "o",
            "the", "in", "of", "and", "for", "to");

    private CatalogRequiredTokenScorer() {}

    private static final double FIELD_WEIGHT_NAME = 1.0;
    private static final double FIELD_WEIGHT_OTHER = 0.6;
    private static final double FIELD_WEIGHT_PATH_ONLY = 0.3;

    public record RequiredTokenScore(
            int requiredTokenBonus,
            int requiredTokenHits,
            double hitWeight,
            List<String> requiredTokens) {}

    public record SoftAndAdjustment(int finalScore, boolean collapsed) {}

    public static List<String> extractRequiredTokens(String query) {
        String q = CatalogTextUtils.foldAscii(query == null ? "" : query);
        String[] parts = q.split("[\\s,;/]+");
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String part : parts) {
            String tok = part.strip();
            if (tok.length() < 2 || SOFT_AND_SKIP.contains(tok) || !seen.add(tok)) {
                continue;
            }
            out.add(tok);
        }
        return out;
    }

    public static boolean tokenHit(String hayFolded, String token, Map<String, Object> geoIntent) {
        String t = CatalogTextUtils.foldAscii(token);
        if (t.length() < 2) {
            return false;
        }
        if (CatalogTextUtils.containsTokenOrPhrase(hayFolded, t)) {
            return true;
        }
        for (String rel : CatalogSearchLexicon.relatedSurfaces(t)) {
            String rf = CatalogTextUtils.foldAscii(rel);
            if (rf.length() >= 2 && CatalogTextUtils.containsTokenOrPhrase(hayFolded, rf)) {
                return true;
            }
        }
        List<String> codes = resolvedGeoCodes(geoIntent);
        for (String code : codes) {
            List<String> aliases = aliasTermsForCountry(code);
            if (!isGeoAliasQueryToken(t, aliases, code)) {
                continue;
            }
            for (String alias : aliases) {
                String a = CatalogTextUtils.foldAscii(alias);
                if (a.length() >= 2 && CatalogTextUtils.containsTokenOrPhrase(hayFolded, a)) {
                    return true;
                }
            }
            String lc = code.toLowerCase(Locale.ROOT);
            if (CatalogTextUtils.containsTokenOrPhrase(hayFolded, lc)) {
                return true;
            }
        }
        return false;
    }

    public static RequiredTokenScore scoreRequiredTokens(
            String hayFolded,
            String nameFolded,
            String pathFolded,
            String queryRaw,
            Map<String, Object> geoIntent) {
        List<String> required = extractRequiredTokens(queryRaw);
        List<Double> weights =
                requiredTokenWeights(required, hayFolded, nameFolded, pathFolded, geoIntent);
        int hits = (int) weights.stream().filter(w -> w > 0).count();
        double hitWeight = weights.stream().mapToDouble(Double::doubleValue).sum();
        int bonus = requiredTokenBonus(required.size(), hits, hitWeight);
        return new RequiredTokenScore(bonus, hits, hitWeight, required);
    }

    public static int requiredTokenBonus(int nReq, int requiredTokenHits, double hitWeight) {
        if (nReq == 0) {
            return 0;
        }
        if (nReq == 1) {
            return requiredTokenHits >= 1 ? (int) Math.round(130 * hitWeight) : 0;
        }
        double ratio = hitWeight / nReq;
        int bonus = (int) Math.round(ratio * ratio * 480);
        if (requiredTokenHits == nReq) {
            bonus += 90;
        }
        return bonus;
    }

    public static SoftAndAdjustment applySoftAndCollapses(
            int finalScore,
            int nReq,
            int requiredTokenHits,
            int baseTextScore,
            int synonymBonus) {
        int score = finalScore;
        boolean collapsed = false;
        if (nReq >= 2 && requiredTokenHits <= 1 && baseTextScore > 0) {
            score = Math.max(0, (int) Math.round(score * 0.55) - 40);
            collapsed = true;
        }
        if (nReq >= 2 && requiredTokenHits == 0 && synonymBonus > 0) {
            score = Math.max(0, (int) Math.round(score * 0.35));
            collapsed = true;
        }
        return new SoftAndAdjustment(score, collapsed);
    }

    public static List<String> geoScoringTerms(Map<String, Object> geoIntent) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String code : resolvedGeoCodes(geoIntent)) {
            for (String term : aliasTermsForCountry(code)) {
                if (seen.add(term)) {
                    out.add(term);
                }
            }
        }
        return out;
    }

    public static boolean geoImplicitForSource(String source, Map<String, Object> geoIntent) {
        String src = source == null ? "" : source.strip().toLowerCase(Locale.ROOT);
        if (src.isEmpty()) {
            return false;
        }
        List<String> codes = resolvedGeoCodes(geoIntent);
        if (codes.size() != 1) {
            return false;
        }
        String scope = CatalogGeoIntent.sourceGeoScope(src);
        return switch (scope) {
            case "CZ" -> "CZ".equals(codes.get(0));
            case "US" -> "US".equals(codes.get(0));
            default -> false;
        };
    }

    public static List<String> dropResolvedGeoTokens(List<String> required, String source, Map<String, Object> geoIntent) {
        if (required.isEmpty() || !geoImplicitForSource(source, geoIntent)) {
            return required;
        }
        Set<String> geoToks = geoAliasTokenSet(resolvedGeoCodes(geoIntent));
        List<String> kept = new ArrayList<>();
        for (String token : required) {
            if (!geoToks.contains(CatalogTextUtils.foldAscii(token))) {
                kept.add(token);
            }
        }
        return kept.isEmpty() ? required : kept;
    }

    private static List<Double> requiredTokenWeights(
            List<String> required,
            String hayFolded,
            String nameFolded,
            String pathFolded,
            Map<String, Object> geoIntent) {
        if (required.isEmpty()) {
            return List.of();
        }
        boolean legacy = nameFolded.isEmpty() && pathFolded.isEmpty();
        List<Double> weights = new ArrayList<>();
        for (String tok : required) {
            if (!tokenHit(hayFolded, tok, geoIntent)) {
                weights.add(0.0);
                continue;
            }
            double w;
            if (legacy) {
                w = 1.0;
            } else if (!nameFolded.isEmpty() && tokenHit(nameFolded, tok, geoIntent)) {
                w = FIELD_WEIGHT_NAME;
            } else if (!pathFolded.isEmpty()
                    && tokenHit(pathFolded, tok, geoIntent)
                    && !tokenHit(hayFolded.replace(pathFolded, " "), tok, geoIntent)) {
                w = FIELD_WEIGHT_PATH_ONLY;
            } else {
                w = FIELD_WEIGHT_OTHER;
            }
            if (CatalogSearchLexicon.isGenericToken(tok)) {
                w *= CatalogSearchLexicon.GENERIC_TOKEN_WEIGHT_FACTOR;
            }
            weights.add(w);
        }
        return weights;
    }

    private static List<String> aliasTermsForCountry(String code) {
        return CatalogCountryAliasRegistry.foldedAliasMatchTerms(code);
    }

    private static boolean isGeoAliasQueryToken(String token, List<String> aliases, String code) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String t = CatalogTextUtils.foldAscii(token);
        String lc = code == null ? "" : code.toLowerCase(Locale.ROOT);
        if (t.equals(lc)) {
            return true;
        }
        for (String alias : aliases == null ? List.<String>of() : aliases) {
            String a = CatalogTextUtils.foldAscii(alias);
            if (t.equals(a) || (a.length() >= 4 && t.startsWith(a))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> geoAliasTokenSet(List<String> codes) {
        Set<String> toks = new LinkedHashSet<>();
        for (String code : codes) {
            String cu = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
            if (cu.isEmpty()) {
                continue;
            }
            toks.add(cu.toLowerCase(Locale.ROOT));
            for (String alias : aliasTermsForCountry(cu)) {
                toks.add(alias);
                for (String word : alias.split("\\s+")) {
                    if (word.length() >= 2) {
                        toks.add(word);
                    }
                }
            }
        }
        return toks;
    }

    private static List<String> resolvedGeoCodes(Map<String, Object> geoIntent) {
        List<String> codes = new ArrayList<>();
        String cc1 = String.valueOf(geoIntent == null ? "" : geoIntent.getOrDefault("country_code", "")).strip();
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
}
