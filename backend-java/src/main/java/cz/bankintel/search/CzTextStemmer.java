package cz.bankintel.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight Czech stemmer for search — port of {@code Bankoapp-main/backend/services/cz_text.py}.
 */
public final class CzTextStemmer {

    private static final List<String> CASE_SUFFIXES = buildCaseSuffixes();

    private CzTextStemmer() {}

    /** ASCII fold — same semantics as {@link CatalogTextUtils#foldAscii(String)}. */
    public static String foldAscii(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD);
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.getType(ch) != Character.NON_SPACING_MARK) {
                sb.append(ch);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Strip the longest Czech case suffix while keeping stem length &gt;= {@code minStem}.
     * Port of {@code cz_stem} in cz_text.py.
     */
    public static String czStem(String word, int minStem) {
        String w = foldAscii(word);
        if (w.length() <= minStem) {
            return w;
        }
        for (String suffix : CASE_SUFFIXES) {
            if (w.length() - suffix.length() >= minStem && w.endsWith(suffix)) {
                return w.substring(0, w.length() - suffix.length());
            }
        }
        return w;
    }

    public static String czStem(String word) {
        return czStem(word, 3);
    }

    /** Default floor before a stem is trusted as an FTS5 prefix - see {@link #ftsPrefixStem}. */
    public static final int DEFAULT_MIN_FTS_PREFIX_LEN = 5;

    /**
     * The stem to widen an FTS5 token query with (as a bareword prefix, {@code stem + "*"}), or blank
     * if widening isn't worthwhile. Shared by every FTS match-expression builder in the codebase
     * ({@code CatalogIndexStore}, {@code SearchCatalogSidecarIndex}) so "does this token deserve a
     * prefix widening" is answered identically everywhere, not reimplemented per call site.
     *
     * <p>Blank in two cases: (1) the stem is too short to be a safe prefix - a 3-char stem-length
     * floor (see {@link #czStem(String, int)}) would fan a prefix query out to an unrelated wide
     * swath of the index, so this uses a separate, higher floor ({@code minPrefixLen}); (2) stemming
     * removed nothing (the token is already its own stem, e.g. a ticker, dataset code, or a word with
     * no case suffix to strip) - widening would be a no-op duplicate of the exact clause.
     */
    public static String ftsPrefixStem(String token, int minPrefixLen) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String folded = foldAscii(token);
        String stem = czStem(folded);
        if (stem.length() < minPrefixLen || stem.equals(folded)) {
            return "";
        }
        return stem;
    }

    public static String ftsPrefixStem(String token) {
        return ftsPrefixStem(token, DEFAULT_MIN_FTS_PREFIX_LEN);
    }

    /** Tokenize (after ASCII fold) and stem each token — port of {@code stem_tokens}. */
    public static List<String> stemTokens(String text, int minStem) {
        String folded = foldAscii(text);
        List<String> out = new ArrayList<>();
        for (String tok : folded.split("\\s+")) {
            if (!tok.isBlank()) {
                out.add(czStem(tok, minStem));
            }
        }
        return out;
    }

    public static List<String> stemTokens(String text) {
        return stemTokens(text, 3);
    }

    private static List<String> buildCaseSuffixes() {
        Set<String> suffixes = new LinkedHashSet<>();
        suffixes.add("atech");
        suffixes.add("etem");
        suffixes.add("atum");
        for (String s : List.of(
                "ech", "ich", "ych", "eho", "emi", "emu", "ete", "eti",
                "iho", "imi", "imu", "ach", "ata", "aty", "ama", "ami",
                "ove", "ovi", "ymi",
                "em", "es", "im", "um", "at", "am", "os", "us", "ym",
                "mi", "ou", "ho", "mu",
                "a", "e", "i", "o", "u", "y")) {
            suffixes.add(s);
        }
        return suffixes.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }
}
