package cz.bankintel.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Simplified port of Python metadata score channel + soft-AND token check. */
public final class CatalogMetadataScorer {

    private static final int CAP_METADATA_TOTAL = 80;
    private static final int CAP_KEYWORDS = 55;
    private static final int CAP_INTENT = 40;
    private static final int CAP_LABEL = 55;
    private static final Set<String> STOPWORDS = Set.of(
            "v", "ve", "na", "do", "od", "pro", "pri", "u", "a", "i", "o", "the", "in", "of", "and", "for", "to");

    private CatalogMetadataScorer() {}

    public static int scoreRow(
            String source,
            String queryRaw,
            Map<String, Object> row,
            Map<String, Object> metadata,
            List<String> queryIntentTags) {
        if (metadata == null || metadata.isEmpty()) {
            return softAndPenalty(queryRaw, row);
        }
        String qFold = CatalogTextUtils.foldAscii(queryRaw);
        List<String> req = requiredTokens(queryRaw);
        int positive = 0;

        for (String field : List.of("human_label_cs", "human_label_en")) {
            String label = str(metadata.get(field));
            if (label.isBlank()) {
                continue;
            }
            if (phraseMatchesQuery(label, qFold, req)) {
                positive = Math.max(positive, Math.min(CAP_LABEL, 35 + Math.min(label.length(), 40)));
            }
        }

        int kwScore = 0;
        int kwHits = 0;
        for (String field : List.of("search_keywords_cs", "search_keywords_en")) {
            for (String kw : stringList(metadata.get(field))) {
                if (kwHits >= 3) {
                    break;
                }
                if (phraseMatchesQuery(kw, qFold, req)) {
                    kwScore += 18;
                    kwHits++;
                }
            }
        }
        positive += Math.min(kwScore, CAP_KEYWORDS);

        Set<String> active = new LinkedHashSet<>();
        for (String tag : queryIntentTags) {
            if (tag != null && !tag.isBlank()) {
                active.add(tag.toLowerCase(Locale.ROOT));
            }
        }
        int intentScore = 0;
        for (String tag : stringList(metadata.get("intent_tags"))) {
            if (active.contains(tag.toLowerCase(Locale.ROOT))) {
                intentScore += 20;
            }
        }
        positive += Math.min(intentScore, CAP_INTENT);

        int negative = 0;
        for (String nk : stringList(metadata.get("negative_keywords"))) {
            if (tokenInText(nk, qFold)) {
                negative += 80;
            }
        }

        int metadataTotal = Math.max(0, Math.min(CAP_METADATA_TOTAL, positive) - Math.min(negative, 120));
        int softAnd = softAndPenalty(queryRaw, row);
        return metadataTotal * 3 + softAnd;
    }

    /** Penalize rows missing required query tokens in title/search blob. */
    public static int softAndPenalty(String queryRaw, Map<String, Object> row) {
        List<String> req = requiredTokens(queryRaw);
        if (req.size() < 2) {
            return 0;
        }
        String hay = CatalogTextUtils.foldAscii(buildHaystack(row));
        int missing = 0;
        for (String token : req) {
            if (token.length() < 3) {
                continue;
            }
            if (!CatalogTextUtils.containsTokenOrPhrase(hay, token)) {
                missing++;
            }
        }
        if (missing == 0) {
            return 40;
        }
        return -missing * 35;
    }

    public static List<String> requiredTokens(String queryRaw) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String topic = CatalogGeoIntent.topicQueryWithoutGeo(queryRaw);
        if (topic.isBlank()) {
            topic = queryRaw == null ? "" : queryRaw.trim();
        }
        for (String part : CatalogTextUtils.foldAscii(topic).split("[\\s,;/]+")) {
            String tok = part.trim();
            if (tok.length() < 2 || STOPWORDS.contains(tok) || CatalogGeoIntent.looksLikeGeoToken(tok) || !seen.add(tok)) {
                continue;
            }
            out.add(tok);
        }
        return out;
    }

    private static boolean phraseMatchesQuery(String phrase, String qFold, List<String> req) {
        String pf = CatalogTextUtils.foldAscii(phrase);
        if (pf.isBlank()) {
            return false;
        }
        if (CatalogTextUtils.containsWholeTokenOrPhrase(qFold, pf)) {
            return true;
        }
        String[] words = pf.split("\\s+");
        List<String> content = new ArrayList<>();
        for (String w : words) {
            if (w.length() >= 3) {
                content.add(w);
            }
        }
        if (content.isEmpty()) {
            return false;
        }
        int hits = 0;
        for (String w : content) {
            if (CatalogTextUtils.containsTokenOrPhrase(qFold, w) || req.contains(w)) {
                hits++;
            }
        }
        return hits >= Math.max(1, (content.size() + 1) / 2);
    }

    private static boolean tokenInText(String token, String textFolded) {
        String t = CatalogTextUtils.foldAscii(token);
        if (t.length() < 2) {
            return false;
        }
        return CatalogTextUtils.containsTokenOrPhrase(textFolded, t);
    }

    private static String buildHaystack(Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        append(sb, row.get("name"));
        append(sb, row.get("title"));
        append(sb, row.get("indicator_name"));
        append(sb, row.get("full_path"));
        append(sb, row.get("search_blob"));
        append(sb, row.get("_search_blob"));
        Object nested = row.get("row");
        if (nested instanceof Map<?, ?> map) {
            append(sb, map.get("name"));
            append(sb, map.get("title"));
            append(sb, map.get("indicator_name"));
            append(sb, map.get("search_blob"));
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value) {
        if (value != null) {
            sb.append(' ').append(String.valueOf(value));
        }
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof String s) {
            if (!s.isBlank()) {
                out.add(s);
            }
        } else if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    String text = String.valueOf(item).trim();
                    if (!text.isBlank()) {
                        out.add(text);
                    }
                }
            }
        }
        return out;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
