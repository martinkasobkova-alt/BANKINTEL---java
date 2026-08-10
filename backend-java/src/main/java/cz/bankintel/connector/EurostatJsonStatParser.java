package cz.bankintel.connector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Převod Eurostat JSON-stat 2.0 na flat seznam řádků (dimenze + value).
 *
 * <p>Port Python {@code connectors/eurostat._flatten_jsonstat}.
 */
public final class EurostatJsonStatParser {

    private static final Pattern MONTH = Pattern.compile("^(\\d{4})[-M]?(\\d{2})$");
    private static final Pattern QUARTER = Pattern.compile("^(\\d{4})-?Q([1-4])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("^(\\d{4})$");

    private EurostatJsonStatParser() {}

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> flatten(Map<String, Object> raw, int maxRows) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Object data = raw.get("data");
        if (data instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(ConnectorHttpSupport.stringMap(map));
                    if (maxRows > 0 && out.size() >= maxRows) {
                        break;
                    }
                }
            }
            return out;
        }

        List<String> dimIds = toStringList(raw.get("id"));
        List<Integer> sizes = toIntList(raw.get("size"));
        Object dimensionsRaw = raw.get("dimension");
        Object values = raw.get("value");
        if (dimIds.isEmpty() || sizes.isEmpty() || values == null) {
            return List.of();
        }

        Map<String, Object> dimensions =
                dimensionsRaw instanceof Map<?, ?> map ? ConnectorHttpSupport.stringMap(map) : Map.of();
        List<IndexedDimension> indexedDims = new ArrayList<>();
        for (String dimId : dimIds) {
            Object dimRaw = dimensions.get(dimId);
            Map<String, Object> dimMap = dimRaw instanceof Map<?, ?> m ? ConnectorHttpSupport.stringMap(m) : Map.of();
            Object categoryRaw = dimMap.get("category");
            Map<String, Object> category =
                    categoryRaw instanceof Map<?, ?> m ? ConnectorHttpSupport.stringMap(m) : Map.of();
            Map<String, Object> indexMap =
                    category.get("index") instanceof Map<?, ?> m ? ConnectorHttpSupport.stringMap(m) : Map.of();
            Map<String, Object> labelMap =
                    category.get("label") instanceof Map<?, ?> m ? ConnectorHttpSupport.stringMap(m) : Map.of();

            List<String> keys = new ArrayList<>();
            if (indexMap.isEmpty() && category.get("index") instanceof List<?> indexList) {
                for (Object key : indexList) {
                    keys.add(String.valueOf(key));
                }
            } else {
                keys.addAll(indexMap.keySet());
                keys.sort(Comparator.comparingInt(key -> parseIndex(indexMap.get(key))));
            }
            List<String> labels = new ArrayList<>();
            for (String key : keys) {
                Object label = labelMap.get(key);
                labels.add(label != null ? String.valueOf(label) : key);
            }
            indexedDims.add(new IndexedDimension(dimId, keys, labels));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        if (values instanceof Map<?, ?> valueMap) {
            for (Map.Entry<?, ?> entry : valueMap.entrySet()) {
                addRow(rows, entry.getKey(), entry.getValue(), sizes, indexedDims, maxRows);
                if (maxRows > 0 && rows.size() >= maxRows) {
                    break;
                }
            }
        } else if (values instanceof List<?> valueList) {
            for (int i = 0; i < valueList.size(); i++) {
                addRow(rows, i, valueList.get(i), sizes, indexedDims, maxRows);
                if (maxRows > 0 && rows.size() >= maxRows) {
                    break;
                }
            }
        }

        rows.sort(Comparator.comparing(row -> timeSortKey(row.get("time") != null ? row.get("time") : row.get("date"))));
        return rows;
    }

    private static void addRow(
            List<Map<String, Object>> rows,
            Object rawIdx,
            Object rawVal,
            List<Integer> sizes,
            List<IndexedDimension> indexedDims,
            int maxRows) {
        if (rawVal == null) {
            return;
        }
        int idx;
        try {
            idx = Integer.parseInt(String.valueOf(rawIdx));
        } catch (NumberFormatException ex) {
            return;
        }
        Map<String, Object> row = rowFromIndex(idx, sizes, indexedDims);
        try {
            double num = Double.parseDouble(String.valueOf(rawVal).replace(",", "."));
            row.put("value", num);
            row.put("amount", num);
        } catch (NumberFormatException ex) {
            row.put("value", rawVal);
        }
        rows.add(row);
    }

    private static Map<String, Object> rowFromIndex(int idx, List<Integer> sizes, List<IndexedDimension> indexedDims) {
        List<Integer> coords = new ArrayList<>();
        int rem = idx;
        for (int i = sizes.size() - 1; i >= 0; i--) {
            int size = sizes.get(i);
            coords.add(rem % size);
            rem /= size;
        }
        java.util.Collections.reverse(coords);

        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < indexedDims.size() && i < coords.size(); i++) {
            IndexedDimension dim = indexedDims.get(i);
            int pos = coords.get(i);
            if (pos >= 0 && pos < dim.keys().size()) {
                row.put(dim.id(), dim.keys().get(pos));
                row.put(dim.id() + "_label", dim.labels().get(pos));
            }
        }
        if (row.containsKey("time") && !row.containsKey("date")) {
            row.put("date", row.get("time"));
        }
        return row;
    }

    private static List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            out.add(String.valueOf(item));
        }
        return out;
    }

    private static List<Integer> toIntList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (Object item : list) {
            try {
                out.add(Integer.parseInt(String.valueOf(item)));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }

    private static int parseIndex(Object raw) {
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String timeSortKey(Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        Matcher month = MONTH.matcher(text);
        if (month.matches()) {
            return String.format("%04d-%02d-00-%s", Integer.parseInt(month.group(1)), Integer.parseInt(month.group(2)), text);
        }
        Matcher quarter = QUARTER.matcher(text);
        if (quarter.matches()) {
            return String.format(
                    "%04d-%02d-00-%s", Integer.parseInt(quarter.group(1)), Integer.parseInt(quarter.group(2)) * 3, text);
        }
        Matcher year = YEAR.matcher(text);
        if (year.matches()) {
            return String.format("%04d-00-00-%s", Integer.parseInt(year.group(1)), text);
        }
        return "9999-99-99-" + text;
    }

    private record IndexedDimension(String id, List<String> keys, List<String> labels) {}
}
