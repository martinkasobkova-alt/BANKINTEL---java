package cz.bankintel.explore;

import java.util.Locale;

/**
 * Sdílený tvar hodnoty pro přepínač „Strict private" / „Anonymní souhrny" - dřív žila
 * normalizační logika jen jako privátní metoda v {@link ExploreSectorService} (jen echoovala
 * hodnotu do kontraktu, nikde nic negatovala). Teď na ni navíc navazuje skutečné maskování AI
 * promptu pro nahraná data (viz {@code ExploreSectionBucketService},
 * {@code ExploreIndicatorRelationshipService}), takže musí být na jednom místě pro obě analýzy
 * (`/explore/sector` i `/explore/summarize`), ne kopie stejné tří-řádkové logiky dvakrát.
 */
public final class ExploreUserDataPrivacy {

    /** AI nedostane z nahraných dat vůbec nic - ani agregát. Výchozí, nejbezpečnější hodnota. */
    public static final String STRICT_PRIVATE = "private_local_only";

    /** AI smí dostat jen agregované signály (trend, meziroční změna) - ne absolutní hodnotu. */
    public static final String SAFE_SUMMARY = "private_safe_summary";

    private ExploreUserDataPrivacy() {}

    public static String normalize(String rawValue) {
        String raw = rawValue == null ? "" : rawValue.trim().toLowerCase(Locale.ROOT);
        return (raw.contains("safe") || raw.contains("anonym")) ? SAFE_SUMMARY : STRICT_PRIVATE;
    }

    public static boolean isStrict(String normalizedValue) {
        return !SAFE_SUMMARY.equals(normalizedValue);
    }
}
