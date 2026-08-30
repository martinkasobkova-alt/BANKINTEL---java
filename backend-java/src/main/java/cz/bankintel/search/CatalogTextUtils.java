package cz.bankintel.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class CatalogTextUtils {

    private static final Pattern FTS_ESCAPE = Pattern.compile("[\"'\\\\]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TOKEN_BOUNDARY = Pattern.compile("[^\\p{L}\\p{N}]+");

    private CatalogTextUtils() {}

    public static String foldAscii(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKD);
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.getType(ch) != Character.NON_SPACING_MARK) {
                sb.append(ch);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    public static String ftsEscapeToken(String token) {
        if (token == null) {
            return "";
        }
        return WHITESPACE.matcher(FTS_ESCAPE.matcher(token).replaceAll(" ")).replaceAll(" ").trim();
    }

    public static String normalizeTokenBoundaries(String text) {
        String folded = foldAscii(text);
        if (folded.isBlank()) {
            return "";
        }
        return WHITESPACE.matcher(TOKEN_BOUNDARY.matcher(folded).replaceAll(" ")).replaceAll(" ").trim();
    }

    public static boolean containsWholeTokenOrPhrase(String haystack, String needle) {
        String n = normalizeTokenBoundaries(needle);
        if (n.length() < 2) {
            return false;
        }
        String h = normalizeTokenBoundaries(haystack);
        if (h.isBlank()) {
            return false;
        }
        return (" " + h + " ").contains(" " + n + " ");
    }

    public static boolean startsWithTokenOrPhrase(String haystack, String needle) {
        String n = normalizeTokenBoundaries(needle);
        if (n.length() < 2) {
            return false;
        }
        String h = normalizeTokenBoundaries(haystack);
        return h.equals(n) || h.startsWith(n + " ");
    }

    public static boolean containsTokenOrPhrase(String haystack, String needle) {
        String n = normalizeTokenBoundaries(needle);
        if (n.length() < 2) {
            return false;
        }
        if (containsWholeTokenOrPhrase(haystack, n)) {
            return true;
        }
        if (n.length() <= 3 || n.contains(" ")) {
            return false;
        }
        return foldAscii(haystack).contains(n);
    }

    /** Uses {@link CatalogSearchSynonyms#expandSearchQueries(String)} — ref catalog_search_synonyms.py. */
    public static String buildFtsMatch(List<String> needles, String queryRaw) {
        String topicQuery = CatalogGeoIntent.topicQueryWithoutGeo(queryRaw);
        if (topicQuery.isBlank()) {
            topicQuery = queryRaw == null ? "" : queryRaw.trim();
        }

        List<String> wordTokens = new ArrayList<>();
        List<String> phraseTokens = new ArrayList<>();
        Set<String> seenWords = new LinkedHashSet<>();
        Set<String> seenPhrases = new LinkedHashSet<>();

        List<String> parts = new ArrayList<>();
        if (needles != null) {
            for (String needle : needles) {
                if (CatalogGeoIntent.looksLikeGeoToken(needle)) {
                    continue;
                }
                parts.add(needle);
            }
        }
        for (String expanded : CatalogSearchSynonyms.expandSearchQueries(topicQuery)) {
            parts.add(expanded);
        }
        if (!topicQuery.isBlank()) {
            parts.add(topicQuery);
        }

        for (String raw : parts) {
            String phrase = ftsEscapeToken(raw);
            if (phrase.length() < 2 || CatalogGeoIntent.looksLikeGeoToken(phrase)) {
                continue;
            }
            String phraseKey = foldAscii(phrase);
            if (phrase.contains(" ") && seenPhrases.add(phraseKey) && phraseTokens.size() < 3) {
                phraseTokens.add("\"" + phrase + "\"");
            }
            for (String word : phrase.split("\\s+")) {
                String token = ftsEscapeToken(word);
                if (token.length() < 2 || CatalogGeoIntent.looksLikeGeoToken(token)) {
                    continue;
                }
                String wordKey = foldAscii(token);
                if (seenWords.add(wordKey) && wordTokens.size() < 8) {
                    wordTokens.add("\"" + token + "\"");
                }
            }
            if (wordTokens.size() >= 8 && phraseTokens.size() >= 3) {
                break;
            }
        }

        List<String> tokens = new ArrayList<>(wordTokens);
        for (String phrase : phraseTokens) {
            if (!tokens.contains(phrase)) {
                tokens.add(phrase);
            }
        }

        if (tokens.isEmpty()) {
            String q = ftsEscapeToken(topicQuery);
            if (q.length() >= 2) {
                for (String word : q.split("\\s+")) {
                    String token = ftsEscapeToken(word);
                    if (token.length() >= 2 && !CatalogGeoIntent.looksLikeGeoToken(token)) {
                        tokens.add("\"" + token + "\"");
                    }
                }
            }
        }
        if (tokens.isEmpty()) {
            // Čistě zeměpisný dotaz ("Germany", "Madarsko"): všechny tokeny vypadly jako geo
            // a MATCH by vyšel prázdný — zdroj vrátil 0 výsledků a nespustil se ani LIKE
            // fallback. Když nezbylo nic jiného, hledáme aspoň podle názvu země.
            for (String word : ftsEscapeToken(topicQuery).split("\\s+")) {
                String token = ftsEscapeToken(word);
                if (token.length() >= 2) {
                    tokens.add("\"" + token + "\"");
                }
            }
        }
        return tokens.isEmpty() ? "\"\"" : String.join(" OR ", tokens);
    }

    /**
     * Přidá k základnímu MATCH výrazu povinnou skupinu názvů požadované země.
     *
     * <p>Geo záměr se dosud uplatňoval až po vytažení kandidátů z FTS. U velkého zdroje
     * (FRED má 260 tis. řad) se ale nejdřív vytáhne jen omezený počet řádků v pořadí, v jakém
     * jsou v indexu — u dotazu „GDP Germany" to byly samé americké řady, které geo filtr pak
     * všechny zahodil, a uživatel dostal nulu, přestože německé řady v indexu jsou. Tenhle
     * výraz zatlačí zemi přímo do FTS dotazu, takže se vytáhnou rovnou správné řádky.
     *
     * @return {@code null}, když dotaz žádnou konkrétní zemi neobsahuje
     */
    public static String buildGeoAnchoredFtsMatch(String baseMatchExpr, String queryRaw) {
        if (baseMatchExpr == null || baseMatchExpr.isBlank() || "\"\"".equals(baseMatchExpr)) {
            return null;
        }
        List<String> codes = CatalogGeoIntent.requestedGeoCodes(CatalogGeoIntent.detectGeoIntent(queryRaw));
        if (codes.isEmpty()) {
            return null;
        }
        List<String> geoTokens = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String code : codes) {
            for (String alias : CatalogCountryAliasRegistry.foldedAliasTerms(code)) {
                String token = ftsEscapeToken(alias);
                if (token.length() < 2 || token.contains(" ")) {
                    continue;
                }
                if (seen.add(token.toLowerCase(Locale.ROOT)) && geoTokens.size() < 12) {
                    geoTokens.add("\"" + token + "\"");
                }
            }
        }
        if (geoTokens.isEmpty()) {
            return null;
        }
        return "(" + baseMatchExpr + ") AND (" + String.join(" OR ", geoTokens) + ")";
    }

    public static String buildFtsSuggestMatch(String queryRaw, List<String> extraPhrases) {
        List<String> groups = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addSuggestGroup(groups, seen, queryRaw);
        if (extraPhrases != null) {
            for (String phrase : extraPhrases) {
                if (groups.size() >= 8) {
                    break;
                }
                addSuggestGroup(groups, seen, phrase);
            }
        }
        return groups.isEmpty() ? "\"\"" : String.join(" OR ", groups);
    }

    /** Nejkratší rozepsané slovo, které ještě rozšiřujeme na FTS5 prefix (`"infl"*`). */
    private static final int MIN_SUGGEST_PREFIX_LENGTH = 3;

    private static void addSuggestGroup(List<String> groups, Set<String> seen, String phrase) {
        List<String> rawTokens = new ArrayList<>();
        for (String raw : (phrase == null ? "" : phrase).split("\\s+")) {
            String token = ftsEscapeToken(raw);
            if (token.length() >= 2) {
                rawTokens.add(token);
            }
            if (rawTokens.size() >= 6) {
                break;
            }
        }
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < rawTokens.size(); i++) {
            String token = rawTokens.get(i);
            // Poslední slovo uživatel typicky ještě dopisuje — bez prefixu vracel došeptávač
            // na "infl" nula návrhů a napovídal až po dopsání celého slova "inflace".
            boolean isLast = i == rawTokens.size() - 1;
            boolean prefixable = isLast && token.length() >= MIN_SUGGEST_PREFIX_LENGTH && !token.contains(" ");
            tokens.add("\"" + token + "\"" + (prefixable ? "*" : ""));
        }
        if (tokens.isEmpty()) {
            return;
        }
        String group = "(" + String.join(" ", tokens) + ")";
        if (seen.add(group.toLowerCase(Locale.ROOT))) {
            groups.add(group);
        }
    }

    /** Expanded needles via {@link CatalogSearchSynonyms#expandTerms(String)} — ref catalog_search_synonyms.py. */
    public static List<String> needlesFromQuery(String query) {
        List<String> needles = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String q = CatalogGeoIntent.topicQueryWithoutGeo(query);
        if (q.isBlank()) {
            q = query == null ? "" : query.trim();
        }
        if (q.isEmpty()) {
            return needles;
        }
        for (String term : CatalogSearchSynonyms.expandTerms(q)) {
            for (String tok : term.split("\\s+")) {
                if (CatalogGeoIntent.looksLikeGeoToken(tok)) {
                    continue;
                }
                String folded = CatalogSearchSynonyms.foldCs(tok);
                if (folded.length() >= 3 || (folded.length() == 2 && folded.chars().anyMatch(Character::isDigit))) {
                    if (seen.add(folded)) {
                        needles.add(folded);
                    }
                }
                if (needles.size() >= 16) {
                    return needles;
                }
            }
        }
        return needles;
    }

    public static int titleMatchScore(String titleFold, List<String> needlesLc) {
        if (titleFold == null || titleFold.isBlank()) {
            return 0;
        }
        int score = 0;
        for (String needle : needlesLc) {
            if (needle == null || needle.isBlank()) {
                continue;
            }
            if (startsWithTokenOrPhrase(titleFold, needle)) {
                score += 100;
            } else if (containsWholeTokenOrPhrase(titleFold, needle)) {
                score += 40;
            } else if (normalizeTokenBoundaries(needle).length() > 3 && foldAscii(titleFold).contains(foldAscii(needle))) {
                score += 15;
            }
        }
        return score;
    }

    public static String rowTitle(java.util.Map<String, Object> row) {
        Object name = row.get("name");
        if (name == null) {
            name = row.get("title");
        }
        if (name == null) {
            name = row.get("indicator_name");
        }
        if (name == null) {
            name = row.get("set_id");
        }
        return name == null ? "" : String.valueOf(name).trim();
    }
}
