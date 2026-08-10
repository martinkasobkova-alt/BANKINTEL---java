package cz.bankintel.search;

import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.CatalogMapSupport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Final display ranking after preview verification, so live data availability cannot outrank semantic fit. */
final class CatalogDeepSearchFinalRanker {

    private static final int MAX_SEARCH_SCORE_CONTRIBUTION = 300;
    private static final int VERIFIED_PREVIEW_BONUS = 1_200;
    private static final int CATALOG_MATCH_BONUS = 20;
    private static final int FAILED_PREVIEW_PENALTY = 520;
    private static final int NOT_SELECTED_PREVIEW_PENALTY = 220;
    private static final int FULL_SEMANTIC_BONUS = 260;
    private static final int UNIT_SEMANTIC_BONUS = 220;
    private static final int TITLE_GROUP_HIT_BONUS = 115;
    private static final int OTHER_GROUP_HIT_BONUS = 45;
    private static final int NO_GROUP_HIT_PENALTY = 1_340;
    private static final int MISSING_GROUP_PENALTY = 90;
    private static final int DOMAIN_ONLY_WITH_METRIC_PENALTY = 160;
    private static final int METRIC_ONLY_WITH_DOMAIN_PENALTY = 110;
    private static final int PARTIAL_METRIC_DOMAIN_SCORE_CAP = 220;
    private static final int AI_RELEVANT_BONUS = 1_500;
    private static final int AI_RANK_STEP = 25;
    private static final int AI_REJECTED_PENALTY = 600;

    private CatalogDeepSearchFinalRanker() {}

    static RankedBuckets rank(
            String query,
            Map<String, Object> geoIntent,
            List<Map<String, Object>> verified,
            List<Map<String, Object>> possible) {
        CatalogQueryRelevanceProfile profile = CatalogQueryRelevanceProfile.from(query, geoIntent);
        // Blind profile = the profile has topic groups but not a single candidate matches any of them.
        // That means the profile can't judge this query (e.g. no FX concept for "eurusd"), NOT that every
        // result is irrelevant — so we must not let the topic penalties nuke the whole result set.
        boolean profileBlind = profile.groupCount() > 0 && !anyTopicHit(profile, verified, possible);
        List<RankedRow> rows = new ArrayList<>();
        addRows(rows, "verified", verified, query, geoIntent, profile, profileBlind);
        addRows(rows, "possible", possible, query, geoIntent, profile, profileBlind);

        rows.sort(Comparator.comparingInt(RankedRow::finalScore)
                .reversed()
                .thenComparing(Comparator.comparingInt(RankedRow::searchScore).reversed())
                .thenComparing(RankedRow::title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RankedRow::key));

        List<Map<String, Object>> rankedVerified = new ArrayList<>();
        List<Map<String, Object>> rankedPossible = new ArrayList<>();
        int rank = 1;
        for (RankedRow ranked : rows) {
            Map<String, Object> row = ranked.row();
            row.put("final_rank", rank++);
            row.put("final_score", ranked.finalScore());
            if ("verified".equals(ranked.bucket())) {
                rankedVerified.add(row);
            } else {
                rankedPossible.add(row);
            }
        }
        return new RankedBuckets(rankedVerified, rankedPossible);
    }

    private static boolean anyTopicHit(
            CatalogQueryRelevanceProfile profile,
            List<Map<String, Object>> verified,
            List<Map<String, Object>> possible) {
        for (List<Map<String, Object>> bucket : List.of(
                verified == null ? List.<Map<String, Object>>of() : verified,
                possible == null ? List.<Map<String, Object>>of() : possible)) {
            for (Map<String, Object> row : bucket) {
                if (profile.match(CatalogSemanticRowText.title(row), CatalogSemanticRowText.haystack(row)).totalHits() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addRows(
            List<RankedRow> out,
            String bucket,
            List<Map<String, Object>> rows,
            String query,
            Map<String, Object> geoIntent,
            CatalogQueryRelevanceProfile profile,
            boolean profileBlind) {
        for (Map<String, Object> raw : rows == null ? List.<Map<String, Object>>of() : rows) {
            Map<String, Object> row = new LinkedHashMap<>(raw);
            CatalogQueryRelevanceProfile.SemanticFit fit =
                    profile.match(CatalogSemanticRowText.title(row), CatalogSemanticRowText.haystack(row));
            CatalogQueryIntent.IntentScoreAdjustments intentAdj =
                    CatalogQueryIntent.computeIntentScoreAdjustments(
                            CatalogSemanticRowText.haystack(row), query, geoIntent);
            int searchScore = CatalogMapSupport.toInt(row.get(CatalogKeys.SEARCH_SCORE), 0);
            int finalScore = finalScore(row, query, searchScore, fit, profile, intentAdj, profileBlind);
            applyDiagnostics(row, query, profile, fit, intentAdj, profileBlind);
            out.add(new RankedRow(bucket, row, searchScore, finalScore, CatalogSemanticRowText.title(row), key(row)));
        }
    }

    private static int finalScore(
            Map<String, Object> row,
            String query,
            int searchScore,
            CatalogQueryRelevanceProfile.SemanticFit fit,
            CatalogQueryRelevanceProfile profile,
            CatalogQueryIntent.IntentScoreAdjustments intentAdj,
            boolean profileBlind) {
        int score = Math.min(MAX_SEARCH_SCORE_CONTRIBUTION, Math.max(0, searchScore));
        String structuredStatus = CatalogMapSupport.str(row.get(CatalogKeys.STRUCTURED_SEMANTIC_STATUS));
        if ("match".equalsIgnoreCase(structuredStatus)) {
            score += 900;
        } else if ("partial".equalsIgnoreCase(structuredStatus)) {
            score += 180;
        } else if ("mismatch".equalsIgnoreCase(structuredStatus)) {
            score -= 2_400;
        }
        score += CatalogResultSpecificityScorer.adjustment(query, row);
        score += fit.titleHits() * TITLE_GROUP_HIT_BONUS;
        score += Math.max(0, fit.totalHits() - fit.titleHits()) * OTHER_GROUP_HIT_BONUS;
        score += profile.titleProximityBonus(CatalogSemanticRowText.title(row));
        if (profile.groupCount() > 0 && fit.totalHits() >= profile.groupCount()) {
            score += FULL_SEMANTIC_BONUS;
            if (profile.metricGroupCount() > 0
                    && profile.match("", CatalogSemanticRowText.unitHaystack(row)).metricHits() > 0) {
                score += UNIT_SEMANTIC_BONUS;
            }
        }
        if (hasVerifiedDataPreview(row)) {
            score += VERIFIED_PREVIEW_BONUS;
        } else if ("catalog_match".equals(CatalogMapSupport.str(row.get(CatalogKeys.PREVIEW_STATUS)))) {
            score += CATALOG_MATCH_BONUS;
        } else if ("unverified".equals(CatalogMapSupport.str(row.get(CatalogKeys.PREVIEW_STATUS)))) {
            score -= FAILED_PREVIEW_PENALTY;
        } else if ("not_selected_for_preview".equals(CatalogMapSupport.str(row.get(CatalogKeys.PREVIEW_STATUS)))) {
            score -= NOT_SELECTED_PREVIEW_PENALTY;
        }
        if (!profileBlind && profile.groupCount() > 0 && fit.totalHits() == 0) {
            score -= NO_GROUP_HIT_PENALTY;
        } else if (!profileBlind && profile.groupCount() >= 2 && fit.totalHits() < profile.groupCount()) {
            int missing = profile.groupCount() - fit.totalHits();
            score -= missing * MISSING_GROUP_PENALTY;
            if (profile.metricGroupCount() > 0 && profile.domainGroupCount() > 0) {
                if (fit.domainHits() > 0 && fit.metricHits() == 0) {
                    score -= DOMAIN_ONLY_WITH_METRIC_PENALTY;
                } else if (fit.metricHits() > 0 && fit.domainHits() == 0) {
                    score -= METRIC_ONLY_WITH_DOMAIN_PENALTY;
                }
                score = Math.min(score, PARTIAL_METRIC_DOMAIN_SCORE_CAP);
            }
        }
        if (intentAdj.negativePenalty() > 0 && !CatalogAiDataResolver.isAiRelevant(row)) {
            score -= intentAdj.negativePenalty() * 5;
            score = Math.min(score, 180);
        }
        // AI-over-data verdict dominates: a series the resolver judged relevant wins regardless of the
        // keyword-topic heuristics; one it evaluated and rejected is pushed down.
        if (CatalogAiDataResolver.isAiRelevant(row)) {
            int aiRank = CatalogMapSupport.toInt(row.get("_ai_rank"), 1);
            score += Math.max(200, AI_RELEVANT_BONUS - (aiRank - 1) * AI_RANK_STEP);
        } else if (CatalogAiDataResolver.isAiRejected(row)) {
            score -= AI_REJECTED_PENALTY;
        }
        return Math.max(0, score);
    }

    private static void applyDiagnostics(
            Map<String, Object> row,
            String query,
            CatalogQueryRelevanceProfile profile,
            CatalogQueryRelevanceProfile.SemanticFit fit,
            CatalogQueryIntent.IntentScoreAdjustments intentAdj,
            boolean profileBlind) {
        List<String> topicTokens = profile.labels();
        row.put("topic_tokens", topicTokens);
        row.put("semantic_group_hits", fit.hitLabels());
        row.put("topic_hit_count", fit.totalHits());
        row.put("topic_match", profileBlind || topicTokens.isEmpty() || fit.totalHits() > 0);
        boolean negativeIntentMatch = intentAdj.negativePenalty() > 0;
        row.put("intent_negative_penalty", intentAdj.negativePenalty());
        row.put("negative_intent_match", negativeIntentMatch);
        row.put("metric_match", !negativeIntentMatch && metricMatch(profile, fit));
        row.put("domain_match", !negativeIntentMatch && domainMatch(profile, fit));
        row.put(
                "semantic_match_level",
                negativeIntentMatch
                        ? "negative_mismatch"
                        : profileBlind ? "blind_profile" : semanticLevel(profile.groupCount(), fit.totalHits()));
        if (!profileBlind && !topicTokens.isEmpty() && fit.totalHits() == 0) {
            row.putIfAbsent("demotion_reason", "topic_mismatch_required_terms");
            row.put(
                    CatalogKeys.WHY_RELEVANT,
                    "Kandidat ma slabou tematickou shodu s dotazem \"" + query + "\"; ponechan pouze jako nizka relevance.");
        } else if (negativeIntentMatch) {
            row.putIfAbsent("demotion_reason", "intent_negative_match");
        } else if (profile.metricGroupCount() > 0 && fit.metricHits() == 0 && fit.domainHits() > 0) {
            row.putIfAbsent("demotion_reason", "semantic_metric_mismatch");
        } else if (profile.domainGroupCount() > 0 && fit.domainHits() == 0 && fit.metricHits() > 0) {
            row.putIfAbsent("demotion_reason", "semantic_domain_mismatch");
        }
    }

    private static boolean metricMatch(
            CatalogQueryRelevanceProfile profile, CatalogQueryRelevanceProfile.SemanticFit fit) {
        if (profile.groupCount() == 0) {
            return true;
        }
        if (profile.metricGroupCount() == 0) {
            return fit.totalHits() >= profile.groupCount();
        }
        return fit.metricHits() >= profile.metricGroupCount();
    }

    private static boolean domainMatch(
            CatalogQueryRelevanceProfile profile, CatalogQueryRelevanceProfile.SemanticFit fit) {
        if (profile.groupCount() == 0 || profile.domainGroupCount() == 0) {
            return true;
        }
        return fit.domainHits() >= profile.domainGroupCount();
    }

    private static String semanticLevel(int topicCount, int hits) {
        if (topicCount == 0 || hits >= topicCount) {
            return "exact";
        }
        return hits > 0 ? "partial" : "mismatch";
    }

    static boolean hasVerifiedDataPreview(Map<String, Object> row) {
        String previewStatus = CatalogMapSupport.str(row.get(CatalogKeys.PREVIEW_STATUS));
        String resultTier = CatalogMapSupport.str(row.get(CatalogKeys.RESULT_TIER));
        if ("catalog_match".equalsIgnoreCase(previewStatus) || "catalog_match".equalsIgnoreCase(resultTier)) {
            return false;
        }
        boolean statusVerified =
                "verified".equalsIgnoreCase(previewStatus)
                        || "verified".equalsIgnoreCase(CatalogMapSupport.str(row.get(CatalogKeys.STATUS)))
                        || "verified".equalsIgnoreCase(resultTier);
        boolean previewAvailable = parseBoolean(row.get(CatalogKeys.PREVIEW_AVAILABLE));
        int rowCount = CatalogMapSupport.toInt(row.get("preview_row_count"), -1);
        return statusVerified && (previewAvailable || rowCount > 0);
    }

    static boolean isSemanticallyActionable(Map<String, Object> row) {
        String structuredStatus = CatalogMapSupport.str(row.get(CatalogKeys.STRUCTURED_SEMANTIC_STATUS));
        if ("mismatch".equalsIgnoreCase(structuredStatus)) {
            return false;
        }
        if ("match".equalsIgnoreCase(structuredStatus)) {
            return true;
        }
        String level = CatalogMapSupport.str(row.get("semantic_match_level"));
        if ("mismatch".equalsIgnoreCase(level)) {
            return false;
        }
        if (row.containsKey("topic_match") && !parseBoolean(row.get("topic_match"))) {
            return false;
        }
        if (row.containsKey("metric_match") && !parseBoolean(row.get("metric_match"))) {
            return false;
        }
        if (row.containsKey("domain_match") && !parseBoolean(row.get("domain_match"))) {
            return false;
        }
        if (parseBoolean(row.get("negative_intent_match"))) {
            return false;
        }
        return true;
    }

    private static boolean parseBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = CatalogMapSupport.str(value).toLowerCase(Locale.ROOT);
        return text.equals("true") || text.equals("1") || text.equals("yes");
    }

    private static String key(Map<String, Object> row) {
        String src = CatalogMapSupport.str(row.getOrDefault(CatalogKeys.SOURCE_TYPE, row.get(CatalogKeys.CATALOG_ID)))
                .toLowerCase(Locale.ROOT);
        String setId = CatalogMapSupport.str(row.get(CatalogKeys.SET_ID)).toLowerCase(Locale.ROOT);
        return src + "|" + setId;
    }

    record RankedBuckets(List<Map<String, Object>> verified, List<Map<String, Object>> possible) {}

    private record RankedRow(
            String bucket, Map<String, Object> row, int searchScore, int finalScore, String title, String key) {}
}
