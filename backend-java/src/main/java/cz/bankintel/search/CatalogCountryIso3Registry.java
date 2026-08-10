package cz.bankintel.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ISO-2 &lt;-&gt; ISO-3 country code registry — loads {@code catalog/iso3_country_codes.json} at startup.
 *
 * <p>General, data-driven number/code lookup (same category as {@link CatalogCountryAliasRegistry}),
 * used so scoring/geo logic never has to hardcode per-country literals (e.g. "MEX", "SVK") — any
 * dataset path embedding a known ISO-3 token (OECD/IMF style {@code /CZE/}, {@code .SVK.}, {@code |USA|})
 * resolves generically to its ISO-2 equivalent.
 */
public final class CatalogCountryIso3Registry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> ISO2_TO_ISO3 = loadIso2ToIso3();
    private static final Map<String, String> ISO3_TO_ISO2 = buildReverse(ISO2_TO_ISO3);

    private CatalogCountryIso3Registry() {}

    public static String iso3For(String iso2) {
        if (iso2 == null || iso2.isBlank()) {
            return "";
        }
        return ISO2_TO_ISO3.getOrDefault(iso2.strip().toUpperCase(Locale.ROOT), "");
    }

    public static String iso2For(String iso3) {
        if (iso3 == null || iso3.isBlank()) {
            return "";
        }
        return ISO3_TO_ISO2.getOrDefault(iso3.strip().toUpperCase(Locale.ROOT), "");
    }

    public static boolean isKnownIso3(String iso3) {
        return iso3 != null && ISO3_TO_ISO2.containsKey(iso3.strip().toUpperCase(Locale.ROOT));
    }

    private static Map<String, String> loadIso2ToIso3() {
        try (InputStream in = CatalogCountryIso3Registry.class.getResourceAsStream("/catalog/iso3_country_codes.json")) {
            if (in == null) {
                return Map.of();
            }
            Map<String, String> raw = MAPPER.readValue(in, new TypeReference<LinkedHashMap<String, String>>() {});
            Map<String, String> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                String iso2 = entry.getKey() == null ? "" : entry.getKey().strip().toUpperCase(Locale.ROOT);
                String iso3 = entry.getValue() == null ? "" : entry.getValue().strip().toUpperCase(Locale.ROOT);
                if (!iso2.isEmpty() && !iso3.isEmpty()) {
                    normalized.put(iso2, iso3);
                }
            }
            return Collections.unmodifiableMap(normalized);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static Map<String, String> buildReverse(Map<String, String> iso2ToIso3) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : iso2ToIso3.entrySet()) {
            out.putIfAbsent(entry.getValue(), entry.getKey());
        }
        return Collections.unmodifiableMap(out);
    }
}
