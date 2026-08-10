package cz.bankintel.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Composite catalog search score — port of
 * {@code Bankoapp-main/backend/services/catalog_search_composite_score.py}.
 *
 * <p>{@link #scoreBreakdown} keeps the legacy BM25 blend for diagnostics;
 * {@link #scoreWithLikelySources} uses the additive Python {@code compute_composite_search_score} model.
 */
public final class CatalogCompositeScorer {

    /** Weight for lexical (BM25) component in legacy blend; remainder goes to title + metadata. */
    public static final double W_LEXICAL = 0.55;

    private static final double W_TITLE = 0.25;
    private static final double W_METADATA = 0.20;

    private static final List<String> RECENCY_FIELDS =
            List.of("last_update", "last_updated", "observation_end", "period_end", "time");
    private static final Pattern YEAR_PATTERN = Pattern.compile("(19|20)\\d{2}");

    /** {@link #aggregateGeoPenalty} — row/set-id text carries an explicit euro-area/EU aggregate marker. */
    private static final int AGGREGATE_GEO_PENALTY_EXPLICIT_MARKER = 150;

    /** {@link #aggregateGeoPenalty} — row's own country code is an EU aggregate that doesn't match the request. */
    private static final int AGGREGATE_GEO_PENALTY_ROW_IS_EU_AGGREGATE = 130;

    /** {@link #aggregateGeoPenalty} — set id itself encodes a euro-area/"U2" aggregate segment (e.g. {@code .EA20.}). */
    private static final int AGGREGATE_GEO_PENALTY_SET_ID_AGGREGATE_SEGMENT = 120;

    /** {@link #nationalGeoBoost} — source's whole connector is scoped to the single requested country (e.g. ARAD/CZ). */
    private static final int NATIONAL_GEO_BOOST_SOURCE_SCOPE_MATCH = 90;

    /** {@link #nationalGeoBoost} — row's own extracted country code matches the single requested country. */
    private static final int NATIONAL_GEO_BOOST_ROW_COUNTRY_MATCH = 55;

    /** {@link #geoMismatchPenalty} — row has an explicit country code and it's not among the requested ones. */
    private static final int GEO_MISMATCH_PENALTY_ROW_COUNTRY_MISMATCH = 120;

    /** {@link #geoMismatchPenalty} — source is US-scoped (e.g. FRED) but the request isn't for US. */
    private static final int GEO_MISMATCH_PENALTY_US_SCOPE_MISMATCH = 100;

    /** {@link #geoMismatchPenalty} — source is CZ-scoped (e.g. ARAD/ČSÚ) but the request isn't for CZ. */
    private static final int GEO_MISMATCH_PENALTY_CZ_SCOPE_MISMATCH = 80;

    /** {@link #geoMismatchPenalty} — no row-level or source-scope geo signal at all; generic fallback penalty. */
    private static final int GEO_MISMATCH_PENALTY_UNKNOWN_SCOPE_DEFAULT = 45;

    /** Small boost for catalog rows flagged as curated/primary index seeds in the FTS mirror. */
    private static final int CANONICAL_INDEX_CURATED_BONUS = 35;

    private static final int CANONICAL_INDEX_SEED_BONUS = 25;

    /** Well-known equity benchmarks when the query names the entity (e.g. NASDAQ + casual "cena"). */
    private static final int CANONICAL_EQUITY_INDEX_BONUS = 350;

    private CatalogCompositeScorer() {}

    public static int score(
            String source,
            String queryRaw,
            Map<String, Object> row,
            List<String> needles,
            int metadataScore,
            int titleMatchScore) {
        return scoreBreakdown(source, queryRaw, row, needles, metadataScore, titleMatchScore)
                .compositeScore();
    }

    public static ScoreBreakdown scoreBreakdown(
            String source,
            String queryRaw,
            Map<String, Object> row,
            List<String> needles,
            int metadataScore,
            int titleMatchScore) {
        double ftsRank = row == null ? 0.0 : toDouble(row.get("_fts_rank"));
        int lexical = normalizeLexicalScore(ftsRank, titleMatchScore, needles, row, queryRaw);
        int title = Math.max(0, titleMatchScore);
        int meta = Math.max(0, metadataScore);
        int sourceBonus = CatalogSourceRegistry.sourceBonus(source);
        double composite =
                W_LEXICAL * lexical + W_TITLE * title + W_METADATA * meta + sourceBonus * 0.15;
        if (Boolean.TRUE.equals(row != null ? row.get("_sidecar_rescue") : null)) {
            composite += 12;
        }
        return new ScoreBreakdown((int) Math.round(composite), lexical, title, meta, sourceBonus);
    }

    public static int scoreWithLikelySources(
            String source,
            String queryRaw,
            Map<String, Object> row,
            List<String> needles,
            int metadataScore,
            int titleMatchScore,
            List<String> likelySources) {
        Map<String, Object> geoIntent = CatalogGeoIntent.detectGeoIntent(queryRaw);
        return scoreWithLikelySources(
                source,
                queryRaw,
                row,
                needles,
                metadataScore,
                titleMatchScore,
                likelySources,
                geoIntent,
                CatalogGeoIntent.requestedGeoCodes(geoIntent));
    }

    /**
     * Additive composite score — port of Python {@code compute_composite_search_score}.
     */
    public static int scoreWithLikelySources(
            String source,
            String queryRaw,
            Map<String, Object> row,
            List<String> needles,
            int metadataScore,
            int titleMatchScore,
            List<String> likelySources,
            Map<String, Object> geoIntent,
            List<String> requestedGeoCodes) {
        return computeCompositeSearchScore(
                        source, queryRaw, row, needles, likelySources, geoIntent, requestedGeoCodes)
                .finalSearchScore();
    }

    public static CompositeScoreBreakdown computeCompositeSearchScore(
            String source,
            String queryRaw,
            Map<String, Object> row,
            List<String> needles,
            List<String> likelySources,
            Map<String, Object> geoIntent,
            List<String> requestedGeoCodes) {
        String hay = buildHaystack(row);
        String hayF = CatalogTextUtils.foldAscii(hay);
        String name = CatalogTextUtils.rowTitle(row);
        String nameF = CatalogTextUtils.foldAscii(name);
        String pathF = CatalogTextUtils.foldAscii(rowPath(row));
        String setId = rowSetId(row);
        String sidF = CatalogTextUtils.foldAscii(setId);
        String qf = CatalogTextUtils.foldAscii(queryRaw == null ? "" : queryRaw);

        List<String> required = CatalogRequiredTokenScorer.extractRequiredTokens(queryRaw);
        if (CatalogRequiredTokenScorer.geoImplicitForSource(source, geoIntent)) {
            required = CatalogRequiredTokenScorer.dropResolvedGeoTokens(required, source, geoIntent);
        }

        List<String> expansion = expansionNeedles(needles, required);
        List<String> geoTerms = CatalogRequiredTokenScorer.geoScoringTerms(geoIntent);

        int baseTextScore = scoreHaystackNeedles(hay, required);
        if (baseTextScore <= 0 && !required.isEmpty()) {
            baseTextScore = scoreHaystackNeedles(hay, required);
        }
        if (baseTextScore <= 0) {
            List<String> limited = needles == null ? List.of() : needles.subList(0, Math.min(12, needles.size()));
            baseTextScore = scoreHaystackNeedles(hay, limited);
        }
        if (baseTextScore <= 0 && needles != null) {
            baseTextScore = scoreHaystackNeedles(hay, needles);
        }

        CatalogRequiredTokenScorer.RequiredTokenScore reqScore =
                CatalogRequiredTokenScorer.scoreRequiredTokens(hayF, nameF, pathF, queryRaw, geoIntent);
        int synonymBonus = expansion.isEmpty() ? 0 : Math.min(scoreHaystackNeedles(hay, expansion), 95);

        int geoBonus = 0;
        int geoHits = 0;
        if (!CatalogRequiredTokenScorer.geoImplicitForSource(source, geoIntent)) {
            for (String gt : geoTerms) {
                String g = CatalogTextUtils.foldAscii(gt);
                if (g.length() < 2) {
                    continue;
                }
                if (CatalogTextUtils.containsTokenOrPhrase(hayF, g)
                        || hayF.contains("(" + g + ")")
                        || hayF.contains("_" + g + "_")) {
                    geoHits++;
                }
            }
            if (geoHits > 0) {
                geoBonus = 160 + Math.min(geoHits, 4) * 45;
            }
        }

        int exactIdBonus = exactIdBonus(qf, sidF);
        int previewableBonus = isPreviewable(row) ? 20 : 0;
        int canonicalIndexBonus = canonicalIndexBonus(row);
        int canonicalEquityIndexBonus = canonicalEquityIndexBonus(qf, setId);

        CatalogQueryIntent.IntentScoreAdjustments intentAdj =
                CatalogQueryIntent.computeIntentScoreAdjustments(hay, queryRaw, geoIntent);
        int intentBonus = intentAdj.intentBonus();
        int negativePenalty = intentAdj.negativePenalty();

        int sourceBonus = CatalogSourceRegistry.sourceBonus(source);
        sourceBonus += (int) Math.round(CatalogLikelySources.sourceBoostWeight(source, likelySources) * 100);
        int commodityBonus = CatalogSearchLexicon.commodityTitleBonus(queryRaw, nameF);
        int geoMismatchPenalty = geoMismatchPenalty(source, row, requestedGeoCodes);
        int aggregateGeoPenalty = aggregateGeoPenalty(row, requestedGeoCodes, hayF, setId);
        int nationalGeoBoost = nationalGeoBoost(source, row, requestedGeoCodes);
        int recencyAdjustment = recencyAdjustment(row);

        int finalScore = baseTextScore
                + reqScore.requiredTokenBonus()
                + synonymBonus
                + geoBonus
                + exactIdBonus
                + previewableBonus
                + canonicalIndexBonus
                + canonicalEquityIndexBonus
                + intentBonus
                - negativePenalty
                + sourceBonus
                + commodityBonus
                + nationalGeoBoost
                + recencyAdjustment
                - geoMismatchPenalty
                - aggregateGeoPenalty;

        CatalogRequiredTokenScorer.SoftAndAdjustment softAnd = CatalogRequiredTokenScorer.applySoftAndCollapses(
                finalScore,
                reqScore.requiredTokens().size(),
                reqScore.requiredTokenHits(),
                baseTextScore,
                synonymBonus);
        if (canonicalEquityIndexBonus <= 0) {
            finalScore = softAnd.finalScore();
        }

        if (baseTextScore <= 0 && synonymBonus <= 0 && geoBonus <= 0 && exactIdBonus <= 0) {
            int intentNet = intentBonus - negativePenalty;
            if (intentNet >= 180) {
                finalScore = intentNet + reqScore.requiredTokenBonus() + previewableBonus + canonicalIndexBonus
                        + canonicalEquityIndexBonus;
            } else {
                finalScore = 0;
            }
        }

        if (Boolean.TRUE.equals(row != null ? row.get("_sidecar_rescue") : null)) {
            finalScore += 12;
        }
        if (Boolean.TRUE.equals(row != null ? row.get("_entity_rescue") : null)) {
            finalScore += 45;
        }

        return new CompositeScoreBreakdown(
                finalScore,
                baseTextScore,
                reqScore.requiredTokenHits(),
                reqScore.hitWeight(),
                reqScore.requiredTokenBonus(),
                synonymBonus,
                geoBonus,
                exactIdBonus,
                previewableBonus,
                intentBonus,
                negativePenalty,
                sourceBonus,
                intentAdj.activeGroups());
    }

    private static int aggregateGeoPenalty(
            Map<String, Object> row, List<String> requestedGeoCodes, String hayFolded, String setId) {
        if (requestedGeoCodes == null || requestedGeoCodes.isEmpty() || requestedGeoCodes.size() != 1) {
            return 0;
        }
        String wanted = requestedGeoCodes.get(0).toUpperCase(Locale.ROOT);
        if (CatalogGeoIntent.EU_AGGREGATE_GEO_CODES.contains(wanted)) {
            return 0;
        }
        String sidF = CatalogTextUtils.foldAscii(setId);
        // Semantic aggregate markers only (name/code text) — deliberately no per-dataset-code
        // literals here; whether a specific dataset actually covers the requested country is
        // decided by the generic geo extraction below + live dimension verification (preview).
        List<String> aggregateMarkers = List.of(
                "euro area", "eurozone", "eurozona", "ea20", "ea19", "ea ", " u2 ", "eu27", "contributions to euro");
        for (String marker : aggregateMarkers) {
            if (hayFolded.contains(marker) || sidF.contains(marker.replace(" ", ""))) {
                return AGGREGATE_GEO_PENALTY_EXPLICIT_MARKER;
            }
        }
        String rowCc = CatalogGeoIntent.extractRowCountryCode(row);
        if (!rowCc.isBlank() && CatalogGeoIntent.EU_AGGREGATE_GEO_CODES.contains(rowCc) && !wanted.equals(rowCc)) {
            return AGGREGATE_GEO_PENALTY_ROW_IS_EU_AGGREGATE;
        }
        if (sidF.contains(".ea") || sidF.contains("ea20") || sidF.contains(".u2.")) {
            return AGGREGATE_GEO_PENALTY_SET_ID_AGGREGATE_SEGMENT;
        }
        return 0;
    }

    /**
     * Boost rows whose data source/coverage matches the requested country — generic via
     * {@link CatalogGeoIntent#sourceGeoScope(String)} (source-connector coverage, e.g. arad/csu are
     * CZ-only, fred is US-only) and {@link CatalogGeoIntent#extractRowCountryCode(Map)} (per-row geo,
     * works for any ISO2/ISO3 country via the registry). No per-country literals.
     */
    private static int nationalGeoBoost(String source, Map<String, Object> row, List<String> requestedGeoCodes) {
        if (requestedGeoCodes == null || requestedGeoCodes.size() != 1 || row == null) {
            return 0;
        }
        String wanted = requestedGeoCodes.get(0).toUpperCase(Locale.ROOT);
        String scope = CatalogGeoIntent.sourceGeoScope(source);
        if (!scope.isBlank() && !"unknown".equals(scope) && !"GLOBAL".equals(scope) && !"EUROPE".equals(scope)
                && wanted.equals(scope)) {
            return NATIONAL_GEO_BOOST_SOURCE_SCOPE_MATCH;
        }
        String rowCc = CatalogGeoIntent.extractRowCountryCode(row);
        if (!rowCc.isBlank() && wanted.equals(rowCc)) {
            return NATIONAL_GEO_BOOST_ROW_COUNTRY_MATCH;
        }
        return 0;
    }

    /**
     * Generic recency signal — prefer rows with fresher observed period/catalog metadata over
     * long-stale ones, without any dataset-code whitelist. Works off metadata fields present across
     * sources (varying key names) plus a title year-range fallback.
     */
    private static int recencyAdjustment(Map<String, Object> row) {
        Integer year = extractRecentYear(row);
        if (year == null) {
            return 0;
        }
        int age = java.time.Year.now().getValue() - year;
        if (age <= 1) {
            return 20;
        }
        if (age <= 3) {
            return 8;
        }
        if (age >= 12) {
            return -55;
        }
        if (age >= 6) {
            return -25;
        }
        return 0;
    }

    private static Integer extractRecentYear(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        int currentYearCap = java.time.Year.now().getValue() + 1;
        for (String field : RECENCY_FIELDS) {
            Object value = row.get(field);
            if (value == null) {
                continue;
            }
            Integer year = maxYearIn(String.valueOf(value), currentYearCap);
            if (year != null) {
                return year;
            }
        }
        String title = CatalogTextUtils.rowTitle(row);
        return title == null ? null : maxYearIn(title, currentYearCap);
    }

    private static Integer maxYearIn(String text, int currentYearCap) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = YEAR_PATTERN.matcher(text);
        int best = -1;
        while (m.find()) {
            int year = Integer.parseInt(m.group());
            if (year > 1900 && year <= currentYearCap) {
                best = Math.max(best, year);
            }
        }
        return best > 0 ? best : null;
    }

    private static int geoMismatchPenalty(String source, Map<String, Object> row, List<String> requestedGeoCodes) {
        if (requestedGeoCodes == null || requestedGeoCodes.isEmpty() || row == null) {
            return 0;
        }
        String rowCc = CatalogGeoIntent.extractRowCountryCode(row);
        if (!rowCc.isBlank()) {
            return requestedGeoCodes.contains(rowCc) ? 0 : GEO_MISMATCH_PENALTY_ROW_COUNTRY_MISMATCH;
        }
        String scope = CatalogGeoIntent.sourceGeoScope(source);
        if ("US".equals(scope) && !requestedGeoCodes.contains("US")) {
            return GEO_MISMATCH_PENALTY_US_SCOPE_MISMATCH;
        }
        if ("CZ".equals(scope) && !requestedGeoCodes.contains("CZ")) {
            return GEO_MISMATCH_PENALTY_CZ_SCOPE_MISMATCH;
        }
        return GEO_MISMATCH_PENALTY_UNKNOWN_SCOPE_DEFAULT;
    }

    private static List<String> expansionNeedles(List<String> needles, List<String> requiredTokens) {
        Set<String> req = new LinkedHashSet<>();
        for (String token : requiredTokens) {
            req.add(CatalogTextUtils.foldAscii(token));
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (needles != null) {
            for (String needle : needles) {
                String nf = CatalogTextUtils.foldAscii(needle);
                if (nf.length() < 2 || req.contains(nf) || !seen.add(nf)) {
                    continue;
                }
                out.add(needle);
            }
        }
        String joined = String.join(" ", requiredTokens);
        for (String term : CatalogSearchSynonyms.expandTerms(joined.isBlank() ? "" : joined)) {
            String tf = CatalogTextUtils.foldAscii(term);
            if (tf.length() >= 2 && !req.contains(tf) && seen.add(tf)) {
                out.add(term);
            }
            if (out.size() >= 60) {
                break;
            }
        }
        return out.size() > 60 ? out.subList(0, 60) : out;
    }

    static int scoreHaystackNeedles(String hay, List<String> needles) {
        if (hay == null || hay.isBlank() || needles == null || needles.isEmpty()) {
            return 0;
        }
        String h = CatalogTextUtils.foldAscii(hay);
        int best = 0;
        for (String needle : needles) {
            if (needle == null || needle.isBlank()) {
                continue;
            }
            String n = CatalogTextUtils.foldAscii(needle);
            if (n.length() < 2 || !CatalogTextUtils.containsTokenOrPhrase(h, n)) {
                continue;
            }
            int bonus = CatalogTextUtils.startsWithTokenOrPhrase(h, n) ? 50 : 0;
            if (h.contains(n + ".") || CatalogTextUtils.containsWholeTokenOrPhrase(h, n)) {
                bonus += 80;
            }
            best = Math.max(best, n.length() * 12 + bonus);
        }
        return best;
    }

    static int exactIdBonus(String queryFolded, String setIdFolded) {
        if (setIdFolded == null || setIdFolded.length() < 3 || queryFolded == null || queryFolded.isBlank()) {
            return 0;
        }
        String sidCompact = setIdFolded.replace(" ", "");
        String qCompact = queryFolded.replace(" ", "");
        if (setIdFolded.contains(queryFolded) || sidCompact.contains(qCompact)) {
            return 380;
        }
        if (!qCompact.isEmpty() && qCompact.length() >= 3 && sidCompact.contains(qCompact)) {
            return 320;
        }
        return 0;
    }

    /** Convert SQLite BM25 rank (lower/more negative = better) to 0–100 lexical score. */
    static int normalizeLexicalScore(
            double ftsRank,
            int titleMatchScore,
            List<String> needles,
            Map<String, Object> row,
            String queryRaw) {
        if (ftsRank != 0.0) {
            double clamped = Math.max(-25.0, Math.min(0.0, ftsRank));
            return (int) Math.round(100.0 + clamped * 3.2);
        }
        int fallback = titleMatchScore;
        if (row != null) {
            fallback = Math.max(fallback, (int) row.getOrDefault("_match", 0));
        }
        if (fallback <= 0 && needles != null && row != null) {
            String hay = CatalogTextUtils.foldAscii(buildHaystack(row));
            for (String needle : needles) {
                if (needle != null && !needle.isBlank() && CatalogTextUtils.containsTokenOrPhrase(hay, needle)) {
                    fallback = Math.max(fallback, 15 + Math.min(needle.length() * 3, 30));
                }
            }
        }
        if (fallback <= 0 && queryRaw != null && !queryRaw.isBlank() && row != null) {
            String qf = CatalogTextUtils.foldAscii(queryRaw);
            String title = CatalogTextUtils.foldAscii(CatalogTextUtils.rowTitle(row));
            if (CatalogTextUtils.containsTokenOrPhrase(title, qf)) {
                fallback = 25;
            }
        }
        return Math.min(100, Math.max(0, fallback));
    }

    private static String buildHaystack(Map<String, Object> row) {
        if (row == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        append(sb, row.get("name"));
        append(sb, row.get("title"));
        append(sb, row.get("set_id"));
        append(sb, row.get("series_id"));
        append(sb, row.get("indicator_name"));
        append(sb, row.get("full_path"));
        append(sb, row.get("search_blob"));
        append(sb, row.get("_search_blob"));
        return sb.toString();
    }

    private static String rowPath(Map<String, Object> row) {
        if (row == null) {
            return "";
        }
        Object path = row.getOrDefault("full_path", row.get("tree_path"));
        return path == null ? "" : String.valueOf(path);
    }

    private static String rowSetId(Map<String, Object> row) {
        if (row == null) {
            return "";
        }
        for (String key : List.of("set_id", "series_id", "id")) {
            Object value = row.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private static int canonicalIndexBonus(Map<String, Object> row) {
        if (row == null) {
            return 0;
        }
        if (Boolean.TRUE.equals(row.get("curated"))) {
            return CANONICAL_INDEX_CURATED_BONUS;
        }
        Object seed = row.get("index_seed");
        if (seed != null && "curated".equalsIgnoreCase(String.valueOf(seed).trim())) {
            return CANONICAL_INDEX_SEED_BONUS;
        }
        return 0;
    }

    /**
     * Named equity benchmarks (e.g. FRED {@code NASDAQ100}) should win over long-tail "Price Index"
     * products when the user clearly asked for NASDAQ, even if {@code cena} also activates the
     * generic {@code price_inflation} intent group.
     */
    private static int canonicalEquityIndexBonus(String queryFolded, String setId) {
        if (queryFolded == null || setId == null || !queryFolded.contains("nasdaq")) {
            return 0;
        }
        String sid = setId.toUpperCase(Locale.ROOT);
        if ("NASDAQ100".equals(sid) || "NASDAQCOM".equals(sid)) {
            return CANONICAL_EQUITY_INDEX_BONUS;
        }
        return 0;
    }

    private static boolean isPreviewable(Map<String, Object> row) {
        if (row == null) {
            return false;
        }
        Object previewable = row.get("previewable");
        if (previewable instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(previewable));
    }

    private static void append(StringBuilder sb, Object value) {
        if (value != null) {
            sb.append(' ').append(String.valueOf(value));
        }
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    public record ScoreBreakdown(int compositeScore, int lexicalScore, int titleScore, int metadataScore, int sourceBonus) {}

    public record CompositeScoreBreakdown(
            int finalSearchScore,
            int baseTextScore,
            int requiredTokenHits,
            double requiredTokenWeight,
            int requiredTokenBonus,
            int synonymBonus,
            int geoBonus,
            int exactIdBonus,
            int previewableBonus,
            int intentBonus,
            int negativePenalty,
            int sourceBonus,
            List<String> activeIntentGroups) {}
}
