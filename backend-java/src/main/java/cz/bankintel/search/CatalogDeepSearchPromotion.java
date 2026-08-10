package cz.bankintel.search;

import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.CatalogMapSupport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Keep strong catalog matches visible as candidates when live preview cannot verify data. */
public final class CatalogDeepSearchPromotion {

    private static final int DEFAULT_TOP_N = 3;
    private static final int STRONG_SCORE_THRESHOLD = 25;

    private CatalogDeepSearchPromotion() {}

    public static void promoteCatalogMatches(
            String query, Map<String, Object> geoIntent, List<Map<String, Object>> possible, List<Map<String, Object>> verified) {
        promoteCatalogMatches(query, geoIntent, possible, verified, DEFAULT_TOP_N);
    }

    public static void promoteCatalogMatches(
            String query,
            Map<String, Object> geoIntent,
            List<Map<String, Object>> possible,
            List<Map<String, Object>> verified,
            int topN) {
        if (verified != null && !verified.isEmpty() || possible == null || possible.isEmpty()) {
            return;
        }

        List<String> needles = CatalogTextUtils.needlesFromQuery(query);
        List<String> requestedCodes = CatalogGeoIntent.requestedGeoCodes(geoIntent);
        boolean foreignGeo = !requestedCodes.isEmpty() && requestedCodes.stream().anyMatch(cc -> !"CZ".equals(cc));
        List<String> primaryTopics = CatalogSearchLexicon.primaryTopicTokens(query, geoIntent);

        List<Map<String, Object>> ranked = new ArrayList<>(possible);
        ranked.sort(Comparator.comparingInt((Map<String, Object> row) -> promotionRank(row, query, primaryTopics))
                .reversed()
                .thenComparingInt(row -> -CatalogMapSupport.toInt(row.get(CatalogKeys.SEARCH_SCORE), 0))
                .thenComparing(row -> CatalogMapSupport.str(row.get(CatalogKeys.PREVIEW_STATUS))));

        List<Map<String, Object>> promoted = new ArrayList<>();
        List<Map<String, Object>> remaining = new ArrayList<>();

        for (Map<String, Object> row : ranked) {
            if (promoted.size() >= topN) {
                remaining.add(row);
                continue;
            }
            if (!qualifiesForCatalogMatch(row, query, needles, requestedCodes, foreignGeo, primaryTopics)) {
                remaining.add(row);
                continue;
            }
            promoted.add(markCatalogMatch(row));
        }

        verified.addAll(promoted);
        possible.clear();
        possible.addAll(remaining);
    }

    private static int promotionRank(Map<String, Object> row, String query, List<String> primaryTopics) {
        int score = CatalogMapSupport.toInt(row.get(CatalogKeys.SEARCH_SCORE), 0);
        String titleF = CatalogTextUtils.foldAscii(CatalogMapSupport.str(row.get(CatalogKeys.TITLE)));
        score += CatalogSearchLexicon.commodityTitleBonus(query, titleF) / 2;
        if (matchesPrimaryTopic(titleF, primaryTopics)) {
            score += 40;
        } else if (matchesOnlyGenericNeedles(titleF, query)) {
            score -= 80;
        }
        if ("verified".equals(CatalogMapSupport.str(row.get(CatalogKeys.PREVIEW_STATUS)))) {
            score += 200;
        }
        return score;
    }

    static boolean qualifiesForCatalogMatch(
            Map<String, Object> row,
            String query,
            List<String> needles,
            List<String> requestedCodes,
            boolean foreignGeo,
            List<String> primaryTopics) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        String previewStatus = CatalogMapSupport.str(row.get(CatalogKeys.PREVIEW_STATUS));
        int score = CatalogMapSupport.toInt(row.get(CatalogKeys.SEARCH_SCORE), 0);
        if ("not_selected_for_preview".equals(previewStatus) && score < STRONG_SCORE_THRESHOLD) {
            return false;
        }

        if (foreignGeo && !geoEligible(row, requestedCodes)) {
            return false;
        }

        if (matchesOnlyGenericNeedles(CatalogTextUtils.foldAscii(CatalogMapSupport.str(row.get(CatalogKeys.TITLE))), query)) {
            return false;
        }

        return strongTopicMatch(row, query, needles, score, primaryTopics);
    }

    private static boolean geoEligible(Map<String, Object> row, List<String> requestedCodes) {
        String rowCc = CatalogGeoIntent.extractRowCountryCode(row);
        if (!rowCc.isBlank()) {
            return requestedCodes.contains(rowCc);
        }
        String source = CatalogMapSupport.str(row.get(CatalogKeys.SOURCE_TYPE)).toLowerCase(Locale.ROOT);
        String scope = CatalogGeoIntent.sourceGeoScope(source);
        if ("CZ".equals(scope)) {
            return false;
        }
        if ("US".equals(scope)) {
            return requestedCodes.contains("US");
        }
        return "GLOBAL".equals(scope) || "EUROPE".equals(scope) || "unknown".equals(scope);
    }

    private static boolean strongTopicMatch(
            Map<String, Object> row, String query, List<String> needles, int score, List<String> primaryTopics) {
        String titleFold = CatalogTextUtils.foldAscii(CatalogMapSupport.str(row.get(CatalogKeys.TITLE)));
        if (CatalogSearchLexicon.commodityQuery(query)) {
            if (CatalogSearchLexicon.commodityTitleBonus(query, titleFold) <= 0) {
                return false;
            }
        }
        if (!primaryTopics.isEmpty()) {
            return matchesPrimaryTopic(titleFold, primaryTopics);
        }
        if (score >= 35 && CatalogTextUtils.titleMatchScore(titleFold, needles) > 0) {
            return true;
        }
        String queryFold = CatalogTextUtils.foldAscii(query);
        if (!queryFold.isBlank() && titleFold.contains(queryFold)) {
            return true;
        }
        for (String needle : needles) {
            String folded = CatalogTextUtils.foldAscii(needle);
            if (folded.length() >= 4
                    && !CatalogSearchLexicon.isGenericToken(folded)
                    && titleFold.contains(folded)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPrimaryTopic(String titleFold, List<String> primaryTopics) {
        if (primaryTopics.isEmpty()) {
            return false;
        }
        for (String topic : primaryTopics) {
            if (topic.length() < 3) {
                continue;
            }
            if (titleFold.contains(topic)) {
                return true;
            }
            for (String rel : CatalogSearchLexicon.relatedSurfaces(topic)) {
                String rf = CatalogTextUtils.foldAscii(rel);
                if (rf.length() >= 3 && titleFold.contains(rf)) {
                    return true;
                }
            }
            for (String rel : CatalogSearchLexicon.commoditySurfacesForStem(topic)) {
                String rf = CatalogTextUtils.foldAscii(rel);
                if (rf.length() >= 3 && titleFold.contains(rf)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesOnlyGenericNeedles(String titleFold, String query) {
        List<String> needles = CatalogTextUtils.needlesFromQuery(query);
        boolean anyHit = false;
        for (String needle : needles) {
            String folded = CatalogTextUtils.foldAscii(needle);
            if (folded.length() < 3 || !titleFold.contains(folded)) {
                continue;
            }
            anyHit = true;
            if (!CatalogSearchLexicon.isGenericToken(folded)) {
                return false;
            }
        }
        return anyHit;
    }

    private static Map<String, Object> markCatalogMatch(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.put(CatalogKeys.STATUS, "candidate");
        copy.put(CatalogKeys.RESULT_TIER, "catalog_match");
        copy.put(CatalogKeys.PREVIEW_STATUS, "catalog_match");
        copy.put(CatalogKeys.PREVIEW_AVAILABLE, false);
        copy.put(
                "reason",
                "Silna shoda v katalogu, ale live nahled se nepodaril; rada zustava jen katalogovy kandidat.");
        copy.put("verify_note", copy.get("reason"));
        return copy;
    }
}
