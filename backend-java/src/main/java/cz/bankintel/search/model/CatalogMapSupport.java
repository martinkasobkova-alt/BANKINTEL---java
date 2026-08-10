package cz.bankintel.search.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared map/string helpers for catalog search (replaces duplicated {@code str}/{@code toDouble} methods). */
public final class CatalogMapSupport {

    private CatalogMapSupport() {}

    public static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    public static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = str(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    public static String firstNonBlank(Map<String, Object> payload, String... keys) {
        if (payload == null) {
            return "";
        }
        for (String key : keys) {
            String text = str(payload.get(key));
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    public static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    public static int toInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (map == null) {
            return out;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(v -> str(v).toLowerCase(Locale.ROOT)).filter(s -> !s.isBlank()).toList();
    }

    public static List<Map<String, Object>> toMaps(List<CatalogHit> hits) {
        return hits.stream().map(CatalogHit::toMap).toList();
    }

    public static List<Map<String, Object>> candidatesToMaps(List<CatalogCandidate> candidates) {
        return candidates.stream().map(CatalogCandidate::toMap).toList();
    }
}
