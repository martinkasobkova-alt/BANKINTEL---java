package cz.bankintel.explore.manager;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Simplified port of {@code services/manager_series_runtime_rules.py}. */
public final class ManagerSeriesRuntimeRules {

    private static final Set<String> GLOBAL_GEO = Set.of("GLOBAL", "WORLD", "OECD", "WB", "U2", "U6");
    private static final Set<String> EU_GEO = Set.of("EU", "EA");
    private static final Set<String> EUROPEAN_CODES =
            Set.of("CZ", "DE", "AT", "PL", "SK", "FR", "IT", "ES", "NL", "BE", "HU", "RO", "SE", "DK", "FI", "PT", "IE", "GR");

    private static final Pattern NARROW_QUESTION =
            Pattern.compile(
                    "(produkt|product|regionáln|regional|cost driver|náklad|naklad|demograf|finanční detail|"
                            + "financial detail|usa benchmark|benchmark usa|specialist|úzk|uzk|detailně|detailne)",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern BROAD_QUESTION =
            Pattern.compile(
                    "(srovn|porovn|napříč|napric|across|region|kontinent|continent|evropa|europe|širší|sirs|"
                            + "komplex|overview|přehled|prehled|multi)",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ManagerSeriesRuntimeRules() {}

    public static String normalizeSourceId(Object source) {
        String src = source != null ? String.valueOf(source).trim().toLowerCase(Locale.ROOT) : "";
        if ("world_bank".equals(src) || "world_bank_data360".equals(src)) {
            return "worldbank";
        }
        if ("oecd_local".equals(src)) {
            return "oecd4";
        }
        return src;
    }

    public static String normalizeCountryCode(Object code) {
        String raw = code != null ? String.valueOf(code).trim().toUpperCase(Locale.ROOT) : "";
        if (raw.isBlank()) {
            return "";
        }
        return switch (raw) {
            case "USA", "UNITED STATES", "U.S.", "US" -> "US";
            case "CZE", "CZECHIA", "CZECH REPUBLIC", "CESKO", "ČESKO", "CZ" -> "CZ";
            default -> {
                if (raw.startsWith("EU27") || "EU".equals(raw) || "EUROPEAN UNION".equals(raw)) {
                    yield "EU";
                }
                if (Set.of("EA", "EMU", "EURO AREA", "EUROZONE", "EUROAREA", "EZ", "U2", "U6").contains(raw)) {
                    yield "EA";
                }
                if (GLOBAL_GEO.contains(raw)) {
                    yield raw;
                }
                yield raw.length() == 2 ? raw : raw;
            }
        };
    }

    public static String determineRuntimeRegion(List<String> countryCodes, String primaryCountryCode, Map<String, Object> geo) {
        if (geo != null && "none".equalsIgnoreCase(String.valueOf(geo.getOrDefault("mode", "")))) {
            return "WORLD";
        }
        String primary = normalizeCountryCode(primaryCountryCode);
        if (primary.isBlank() && countryCodes != null && !countryCodes.isEmpty()) {
            primary = normalizeCountryCode(countryCodes.getFirst());
        }
        if ("CZ".equals(primary)) {
            return "CZ";
        }
        if ("US".equals(primary)) {
            return "US";
        }
        if (EUROPEAN_CODES.contains(primary) || EU_GEO.contains(primary)) {
            return "EUROPE";
        }
        if (countryCodes != null) {
            for (String code : countryCodes) {
                String norm = normalizeCountryCode(code);
                if (EUROPEAN_CODES.contains(norm) || EU_GEO.contains(norm)) {
                    return "EUROPE";
                }
            }
        }
        return "ROW";
    }

    public static boolean questionIsNarrow(String question) {
        return question != null && NARROW_QUESTION.matcher(question).find();
    }

    public static boolean questionIsBroad(String question, Map<String, Object> geo) {
        if (geo != null && "continent".equalsIgnoreCase(String.valueOf(geo.getOrDefault("mode", "")))) {
            return true;
        }
        return question != null && BROAD_QUESTION.matcher(question).find();
    }

    public static Set<String> allowedSegmentTiers(boolean narrow, boolean broad) {
        if (narrow) {
            return Set.of("minimal");
        }
        if (broad) {
            return Set.of("must_have", "medium", "minimal");
        }
        return Set.of("must_have", "medium");
    }

    public static boolean macroTierAllowed(String tier, boolean narrow, boolean specialistWatchlist) {
        String t = tier != null ? tier.trim().toLowerCase(Locale.ROOT) : "";
        if (specialistWatchlist && "minimal_or_specialist_macro".equals(t)) {
            return true;
        }
        if (narrow) {
            return Set.of("must_have_macro_core", "must_have_for_high_impact_sectors").contains(t);
        }
        return Set.of(
                        "must_have_macro_core",
                        "must_have_for_high_impact_sectors",
                        "medium_macro_context")
                .contains(t);
    }

    @SuppressWarnings("unchecked")
    public static boolean seriesAllowedForManagerContext(
            Map<String, Object> entry,
            List<String> countryCodes,
            String primaryCountryCode,
            Map<String, Object> geo) {
        if (entry == null || entry.isEmpty()) {
            return false;
        }
        String mode = geo != null ? String.valueOf(geo.getOrDefault("mode", "none")).toLowerCase(Locale.ROOT) : "none";
        if ("none".equals(mode)) {
            return true;
        }
        List<String> rowGeo = listOfStrings(entry.get("geo"));
        if (rowGeo.isEmpty()) {
            rowGeo = listOfStrings(entry.get("countries"));
        }
        if (rowGeo.isEmpty()) {
            return true;
        }
        boolean rowGlobal = rowGeo.stream().anyMatch(g -> GLOBAL_GEO.contains(normalizeCountryCode(g)));
        if (rowGlobal) {
            return true;
        }
        if ("continent".equals(mode) || countryCodes == null || countryCodes.isEmpty()) {
            return true;
        }
        Set<String> wanted = countryCodes.stream()
                .map(ManagerSeriesRuntimeRules::normalizeCountryCode)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        wanted.add(normalizeCountryCode(primaryCountryCode));
        wanted.remove("");
        for (String geoToken : rowGeo) {
            String norm = normalizeCountryCode(geoToken);
            if (wanted.contains(norm)) {
                return true;
            }
            if (EU_GEO.contains(norm) && wanted.stream().anyMatch(EUROPEAN_CODES::contains)) {
                return true;
            }
            if ("EUROPE".equalsIgnoreCase(geoToken) && wanted.stream().anyMatch(EUROPEAN_CODES::contains)) {
                return true;
            }
        }
        return false;
    }

    public static String seriesDedupeKey(Map<String, Object> entry) {
        String src = normalizeSourceId(entry.get("source") != null ? entry.get("source") : entry.get("source_type"));
        String did = stringOrBlank(entry.get("dataset_id"));
        if (did.isBlank()) {
            did = stringOrBlank(entry.get("series_id"));
        }
        if (did.isBlank()) {
            did = stringOrBlank(entry.get("set_id"));
        }
        StringBuilder sb = new StringBuilder(src).append("|").append(did);
        Object qpObj = entry.get("query_params");
        if (!(qpObj instanceof Map<?, ?> qp)) {
            qpObj = entry.get("filters_used");
        }
        if (qpObj instanceof Map<?, ?> qp) {
            for (String key : List.of("geo", "REF_AREA", "ref_area", "country", "imf_country", "imf_indicator")) {
                Object val = qp.get(key);
                if (val == null) {
                    continue;
                }
                sb.append("|").append(key).append("=");
                if (val instanceof Collection<?> coll) {
                    sb.append(String.join(",", coll.stream().map(String::valueOf).toList()));
                } else {
                    sb.append(val);
                }
            }
        }
        return sb.toString();
    }

    public static int tierRank(String tier, Map<String, Integer> order) {
        return order.getOrDefault(tier != null ? tier.trim().toLowerCase(Locale.ROOT) : "", 99);
    }

    private static List<String> listOfStrings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(v -> String.valueOf(v).trim()).filter(s -> !s.isBlank()).toList();
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
