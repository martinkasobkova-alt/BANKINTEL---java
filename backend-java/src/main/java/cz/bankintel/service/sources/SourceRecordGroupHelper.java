package cz.bankintel.service.sources;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class SourceRecordGroupHelper {

    private static final List<String> GROUP_FIELDS =
            List.of("indicator_id", "indicator", "series_id", "series", "code");

    private static final List<List<String>> GROUP_FIELD_ALIASES = List.of(
            List.of("ukazatel"),
            List.of("series_id"),
            List.of("series"),
            List.of("code"),
            List.of("geo"),
            List.of("ref_area"),
            List.of("country"),
            List.of("uzemi_stat", "uzemi_statu", "uzemi"),
            List.of("uzemi_kraj", "kraj", "region"));

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private SourceRecordGroupHelper() {}

    static String detectGroupField(List<Map<String, Object>> sampleRows) {
        for (String field : GROUP_FIELDS) {
            if (hasDistinctValues(sampleRows, field)) {
                return field;
            }
        }
        if (sampleRows.isEmpty() || !(sampleRows.getFirst() instanceof Map)) {
            return null;
        }
        Map<String, String> normToOriginal = new LinkedHashMap<>();
        for (String key : sampleRows.getFirst().keySet()) {
            String nk = normalizeKey(key);
            if (!nk.isEmpty()) {
                normToOriginal.putIfAbsent(nk, key);
            }
        }
        for (List<String> aliases : GROUP_FIELD_ALIASES) {
            for (String alias : aliases) {
                String field = normToOriginal.get(alias);
                if (field == null) {
                    continue;
                }
                Set<String> values = new LinkedHashSet<>();
                for (Map<String, Object> row : sampleRows) {
                    Object raw = row.get(field);
                    if (raw != null && !String.valueOf(raw).trim().isEmpty()) {
                        values.add(String.valueOf(raw));
                    }
                }
                if (values.size() > 1) {
                    return field;
                }
            }
        }
        return null;
    }

    static List<Map<String, Object>> buildIndicators(String groupField, List<Map<String, Object>> rows) {
        if (groupField == null || groupField.isBlank()) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object raw = row.get(groupField);
            if (raw == null || String.valueOf(raw).trim().isEmpty()) {
                continue;
            }
            String id = String.valueOf(raw);
            counts.merge(id, 1, Integer::sum);
        }
        List<Map<String, Object>> indicators = new ArrayList<>();
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(500)
                .forEach(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", entry.getKey());
                    item.put("name", entry.getKey());
                    item.put("count", entry.getValue());
                    indicators.add(item);
                });
        return indicators;
    }

    static List<String> parseExplicitIndicatorIds(String indicatorId, String indicatorIds, int cap) {
        String raw = indicatorIds != null ? indicatorIds.trim() : "";
        if (!raw.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
                if (out.size() >= cap) {
                    break;
                }
            }
            return out;
        }
        String one = indicatorId != null ? indicatorId.trim() : "";
        return one.isEmpty() ? List.of() : List.of(one);
    }

    static Map<String, Object> parseDimensionFilters(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(trimmed, Map.class);
                return parsed != null ? parsed : Map.of();
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String part : trimmed.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return out;
    }

    static boolean rowMatchesFilters(Map<String, Object> row, Map<String, Object> dimFilters, String groupField, List<String> multiIds, String singleId) {
        if (groupField != null && !groupField.isBlank()) {
            Object groupVal = row.get(groupField);
            String groupStr = groupVal != null ? String.valueOf(groupVal) : "";
            if (singleId != null && !singleId.isBlank() && !singleId.equals(groupStr)) {
                return false;
            }
            if (multiIds != null && !multiIds.isEmpty() && !multiIds.contains(groupStr)) {
                return false;
            }
        }
        for (Map.Entry<String, Object> entry : dimFilters.entrySet()) {
            Object rowVal = pickField(row, entry.getKey());
            if (entry.getValue() instanceof List<?> list) {
                if (!list.stream().map(String::valueOf).anyMatch(v -> v.equals(String.valueOf(rowVal)))) {
                    return false;
                }
            } else {
                String expected = String.valueOf(entry.getValue());
                if (rowVal == null || !expected.equals(String.valueOf(rowVal))) {
                    return false;
                }
            }
        }
        return true;
    }

    static List<Map<String, Object>> sampleEven(List<Map<String, Object>> rows, int maxN) {
        int n = Math.max(1, maxN);
        if (rows.size() <= n) {
            return new ArrayList<>(rows);
        }
        List<Map<String, Object>> out = new ArrayList<>(n);
        double step = (rows.size() - 1.0) / Math.max(1, n - 1);
        for (int i = 0; i < n; i++) {
            out.add(rows.get((int) Math.round(i * step)));
        }
        return out;
    }

    static List<String> collectFields(List<Map<String, Object>> rows) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            seen.addAll(row.keySet());
        }
        return new ArrayList<>(seen);
    }

    static List<Map<String, String>> columnsFromFields(List<String> fields) {
        List<Map<String, String>> out = new ArrayList<>();
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            out.add(Map.of("key", field, "label", field));
        }
        return out;
    }

    private static boolean hasDistinctValues(List<Map<String, Object>> rows, String field) {
        for (Map<String, Object> row : rows) {
            Object raw = row.get(field);
            if (raw instanceof String s && !s.isBlank()) {
                return true;
            }
            if (raw instanceof Number) {
                return true;
            }
        }
        return false;
    }

    private static Object pickField(Map<String, Object> row, String preferred) {
        if (row.containsKey(preferred)) {
            return row.get(preferred);
        }
        for (String key : row.keySet()) {
            if (key.equalsIgnoreCase(preferred)) {
                return row.get(key);
            }
        }
        return null;
    }

    private static String normalizeKey(String name) {
        String raw = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return "";
        }
        raw = Normalizer.normalize(raw, Normalizer.Form.NFKD);
        raw = raw.replaceAll("\\p{M}", "");
        raw = NON_ALNUM.matcher(raw).replaceAll("_").replaceAll("^_|_$", "");
        return raw;
    }
}
