package cz.bankintel.sources.imf;

import java.util.Locale;
import java.util.Map;

/** Czech labels for IMF browse entities — port {@code imf_entity_labels.py}. */
final class ImfEntityLabels {

    private static final Map<String, String> REGIONAL = Map.ofEntries(
            Map.entry("G001", "Svet"),
            Map.entry("G110", "Vyspělé ekonomiky"),
            Map.entry("G163", "Eurozona"),
            Map.entry("G200", "Rozvíjející se trhy"),
            Map.entry("G998", "Evropská unie"),
            Map.entry("GX502", "Středně příjmová rozvíjející se Asie"));

    private static final Map<String, String> COUNTRY = Map.ofEntries(
            Map.entry("CZE", "Česko"), Map.entry("CZ", "Česko"),
            Map.entry("SVK", "Slovensko"), Map.entry("SK", "Slovensko"),
            Map.entry("DEU", "Německo"), Map.entry("DE", "Německo"),
            Map.entry("POL", "Polsko"), Map.entry("PL", "Polsko"),
            Map.entry("AUT", "Rakousko"), Map.entry("AT", "Rakousko"),
            Map.entry("FRA", "Francie"), Map.entry("FR", "Francie"),
            Map.entry("USA", "Spojené státy"), Map.entry("US", "Spojené státy"),
            Map.entry("GBR", "Velká Británie"), Map.entry("GB", "Velká Británie"));

    private ImfEntityLabels() {}

    static String entityLabel(String code, String englishName) {
        String c = code != null ? code.trim().toUpperCase(Locale.ROOT) : "";
        if (REGIONAL.containsKey(c)) {
            return REGIONAL.get(c);
        }
        if (COUNTRY.containsKey(c)) {
            return COUNTRY.get(c);
        }
        String en = englishName != null ? englishName.trim() : "";
        return en.isBlank() ? c : en;
    }

    static String formatBrowseEntity(String code, String englishName) {
        String label = entityLabel(code, englishName);
        String bc = ImfApiSupport.browseCountryCode(code);
        if (bc.isBlank() || bc.equals(label)) {
            return label;
        }
        return label + " (" + bc + ")";
    }
}
