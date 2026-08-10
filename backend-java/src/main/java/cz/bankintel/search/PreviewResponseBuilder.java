package cz.bankintel.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sestavení JSON odpovědi preview ve tvaru očekávaném frontendem ({@code previewNormalizer.js}).
 */
public final class PreviewResponseBuilder {

    // Puvodnich 1000 bralo VZDY prvnich 1000 zaznamu bez ohledu na razeni - u casove razene rady
    // (napr. akcie, denni FX kurzy) to tise orizlo vse novejsi nez nejstarsich ~1000 bodu (misto
    // pozadovaneho "cele obdobi"/MAX). Zvyseno tak, aby se vesla i vicedekadova denni historie (napr.
    // akcie od 1990 ma ~9200 obchodnich dni) - vypocet statistik nad tolika body uz je O(n), ne O(n^2)
    // (viz oprava describeSeries v chartSeriesStatistics.js), takze velky pocet radku uz neni riziko.
    // (viz sampleRows nize) ted drzi nejnovejsi zaznamy misto nejstarsich, kdyby k orezu presto doslo.
    private static final int PREVIEW_ROW_LIMIT = 25_000;
    private static final int DIMENSION_OPTION_LIMIT = 500;

    private PreviewResponseBuilder() {}

    public static Map<String, Object> buildSuccess(
            Map<String, Object> payload,
            Map<String, Object> source,
            List<Map<String, Object>> records,
            CatalogSeriesFilter.FilterResult filter) {
        List<String> fields = collectFields(records);
        List<Map<String, Object>> columns = fields.stream()
                .map(field -> Map.<String, Object>of("key", field, "label", labelForField(field)))
                .toList();
        List<Map<String, Object>> previewRows = sampleRows(records, PREVIEW_ROW_LIMIT);

        String sourceType = stringField(payload, "source_type");
        String setId = stringField(payload, "set_id");
        String title = stringField(payload, "name");
        if (title.isBlank()) {
            title = stringField(source, "name");
        }

        Map<String, Object> sourceOut = new LinkedHashMap<>();
        sourceOut.put("source_type", sourceType);
        sourceOut.put("set_id", setId);
        sourceOut.put("name", title);

        List<Map<String, Object>> dimensionRecords = filter.dimensionRecords().isEmpty()
                ? records
                : filter.dimensionRecords();
        Map<String, Object> rowAvailableDimensions = buildAvailableDimensions(dimensionRecords);
        Map<String, Object> availableDimensions =
                mergeAvailableDimensions(source.get("_preview_available_dimensions"), rowAvailableDimensions);
        List<Map<String, Object>> selectableDimensions = buildSelectableDimensions(availableDimensions);
        List<Map<String, Object>> extraDimensions = buildExtraDimensions(availableDimensions);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("row_count", records.size());
        metadata.put("filters_applied", filtersApplied(payload, source));
        metadata.put("dimensions", availableDimensions);
        metadata.put("warning", null);
        metadata.put("preview_state", records.isEmpty() ? "no_data" : null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", sourceOut);
        out.put("dataset_id", setId);
        out.put("title", title);
        out.put("rows", previewRows);
        out.put("fields", fields);
        out.put("columns", columns);
        out.put("metadata", metadata);
        out.put("total_count", records.size());
        out.put("truncated", records.size() > PREVIEW_ROW_LIMIT);
        out.put("indicators", filter.indicators());
        out.put("group_field", filter.groupField());
        out.put("selected_indicator", filter.selectedIndicator());
        out.put("selected_indicators", filter.selectedIndicators());
        out.put("extra_dimensions", extraDimensions);
        out.put("selectable_dimensions", selectableDimensions);
        out.put("available_dimensions", availableDimensions);
        out.put("filter_display_labels", Map.of());
        out.put("applied_filters", metadata.get("filters_applied"));
        out.put("dropped_filters", Map.of());
        out.put("warnings", List.of());
        out.put("request_id", source.getOrDefault("_eurostat_request_id", ""));
        out.put("preview_state", records.isEmpty() ? "no_data" : "");
        out.put("sync_state", records.isEmpty() ? "no_data" : "");
        out.put("message", records.isEmpty() ? "Dataset neobsahuje žádná data pro zadané filtry." : "");
        return out;
    }

    public static Map<String, Object> buildError(
            Map<String, Object> payload, Map<String, Object> source, int status, Map<String, Object> errorBody) {
        String sourceType = stringField(payload, "source_type");
        String setId = stringField(payload, "set_id");
        String detail = stringField(errorBody, "detail_cs");
        if (detail.isBlank()) {
            detail = stringField(errorBody, "error");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", Map.of("source_type", sourceType, "set_id", setId, "name", stringField(payload, "name")));
        out.put("dataset_id", setId);
        out.put("title", stringField(payload, "name"));
        out.put("rows", List.of());
        out.put("fields", List.of());
        out.put("columns", List.of());
        out.put("metadata", Map.of("row_count", 0, "warning", detail, "preview_state", "sync_failed"));
        out.put("preview_state", "sync_failed");
        out.put("sync_state", "sync_failed");
        out.put("message", detail.isBlank() ? "Nepodařilo se načíst data z API." : detail);
        out.put("http_status", status);
        out.put("error", errorBody);
        return out;
    }

    private static List<String> collectFields(List<Map<String, Object>> records) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> rawFields = new ArrayList<>();
        int probe = Math.min(records.size(), 200);
        for (int i = 0; i < probe; i++) {
            for (String key : records.get(i).keySet()) {
                if (seen.add(key)) {
                    rawFields.add(key);
                }
            }
        }
        return orderDisplayFields(rawFields);
    }

    private static List<String> orderDisplayFields(List<String> rawFields) {
        Set<String> rawSet = new LinkedHashSet<>(rawFields);
        List<String> fields = new ArrayList<>();
        for (String preferred : List.of(
                "date",
                "time",
                "TIME_PERIOD",
                "period",
                "value",
                "amount",
                "OBS_VALUE",
                "geo",
                "geo_label",
                "REF_AREA",
                "country",
                "COUNTRY",
                "unit",
                "unit_label",
                "freq",
                "freq_label",
                "FREQ",
                "coicop",
                "coicop_label",
                "ICP_ITEM")) {
            addDisplayField(fields, rawSet, preferred);
        }
        for (String field : rawFields) {
            addDisplayField(fields, rawSet, field);
        }
        return fields;
    }

    private static void addDisplayField(List<String> fields, Set<String> rawSet, String field) {
        if (!rawSet.contains(field) || fields.contains(field) || isHiddenDisplayField(field, rawSet)) {
            return;
        }
        fields.add(field);
    }

    private static boolean isHiddenDisplayField(String field, Set<String> rawSet) {
        String upper = field.toUpperCase();
        String lower = field.toLowerCase();
        String key = dimensionFieldKey(field);
        if ("KEY".equals(upper)) {
            return true;
        }
        if (isTechnicalDisplayField(key)) {
            return true;
        }
        if (!"date".equals(lower) && rawSet.contains("date") && isTimeDisplayAlias(key)) {
            return true;
        }
        if (!"value".equals(lower) && rawSet.contains("value") && isMeasureDisplayAlias(key)) {
            return true;
        }
        if ("TIME_PERIOD".equals(upper) && rawSet.contains("date")) {
            return true;
        }
        if (("OBS_VALUE".equals(upper) || "amount".equals(lower) || "value_num".equals(lower))
                && rawSet.contains("value")) {
            return true;
        }
        return "observation_value_raw".equals(lower);
    }

    private static boolean isTechnicalDisplayField(String key) {
        return switch (key) {
            case "key",
                    "source",
                    "source_type",
                    "dataset",
                    "dataset_id",
                    "set_id",
                    "snapshot_id",
                    "title",
                    "name",
                    "indicator_name",
                    "series_name",
                    "full_path",
                    "catalog_path",
                    "catalog_id",
                    "catalog_label",
                    "path" -> true;
            default -> false;
        };
    }

    private static boolean isTimeDisplayAlias(String key) {
        return switch (key) {
            case "datum",
                    "time",
                    "time_period",
                    "period",
                    "year",
                    "rok",
                    "roky",
                    "obdobi",
                    "month",
                    "months",
                    "mesic",
                    "mesice",
                    "quarter",
                    "quarters",
                    "ctvrtleti",
                    "day",
                    "days",
                    "den",
                    "dny" -> true;
            default -> false;
        };
    }

    private static boolean isMeasureDisplayAlias(String key) {
        return switch (key) {
            case "amount",
                    "hodnota",
                    "hodnoty",
                    "obs_value",
                    "raw_value",
                    "value_num",
                    "observation_value_raw",
                    "y" -> true;
            default -> false;
        };
    }

    private static Map<String, Object> buildAvailableDimensions(List<Map<String, Object>> records) {
        Map<String, LinkedHashMap<String, String>> valuesByField = new LinkedHashMap<>();
        for (Map<String, Object> row : records) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String field = entry.getKey();
                if (!isDimensionCandidate(field)) {
                    continue;
                }
                String value = stringValue(entry.getValue());
                if (value.isBlank()) {
                    continue;
                }
                LinkedHashMap<String, String> values =
                        valuesByField.computeIfAbsent(field, ignored -> new LinkedHashMap<>());
                if (values.size() >= DIMENSION_OPTION_LIMIT && !values.containsKey(value)) {
                    continue;
                }
                values.putIfAbsent(value, dimensionValueLabel(row, field, value));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, String>> entry : valuesByField.entrySet()) {
            LinkedHashMap<String, String> values = entry.getValue();
            if (values.isEmpty()) {
                continue;
            }
            List<String> codes = new ArrayList<>(values.keySet());
            List<Map<String, Object>> options = codes.stream()
                    .map(code -> Map.<String, Object>of("code", code, "label", values.getOrDefault(code, code)))
                    .toList();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("label", labelForField(entry.getKey()));
            meta.put("size", values.size());
            meta.put("values", codes);
            meta.put("sample_values", codes);
            meta.put("sample_options", options);
            out.put(entry.getKey(), meta);
        }
        return out;
    }

    private static Map<String, Object> mergeAvailableDimensions(
            Object rawMetadataDimensions, Map<String, Object> rowDimensions) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (rawMetadataDimensions instanceof Map<?, ?> rawMap) {
            for (Map.Entry<String, Object> entry : castMap(rawMap).entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> rawMeta) {
                    out.put(entry.getKey(), normalizeDimensionMeta(entry.getKey(), castMap(rawMeta)));
                }
            }
        }
        for (Map.Entry<String, Object> entry : rowDimensions.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> rawMeta)) {
                continue;
            }
            Map<String, Object> incoming = normalizeDimensionMeta(entry.getKey(), castMap(rawMeta));
            Object existing = out.get(entry.getKey());
            if (existing instanceof Map<?, ?> existingMeta) {
                out.put(entry.getKey(), mergeDimensionMeta(entry.getKey(), castMap(existingMeta), incoming));
            } else {
                out.put(entry.getKey(), incoming);
            }
        }
        return out;
    }

    private static Map<String, Object> normalizeDimensionMeta(String field, Map<String, Object> meta) {
        Map<String, Object> out = new LinkedHashMap<>(meta);
        List<String> values = dimensionValues(meta);
        LinkedHashMap<String, String> labels = dimensionLabels(meta);
        for (String value : values) {
            labels.putIfAbsent(value, value);
        }
        List<Map<String, Object>> options = values.stream()
                .map(code -> Map.<String, Object>of("code", code, "label", labels.getOrDefault(code, code)))
                .toList();
        out.put("label", stringValue(out.getOrDefault("label", labelForField(field))));
        out.put("size", values.size());
        out.put("values", values);
        out.put("sample_values", values);
        out.put("sample_options", options);
        out.put("value_labels", labels);
        return out;
    }

    private static Map<String, Object> mergeDimensionMeta(
            String field, Map<String, Object> base, Map<String, Object> incoming) {
        LinkedHashMap<String, String> labels = dimensionLabels(base);
        labels.putAll(dimensionLabels(incoming));
        List<String> values = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String value : dimensionValues(base)) {
            if (seen.add(value)) {
                values.add(value);
            }
        }
        for (String value : dimensionValues(incoming)) {
            if (seen.add(value)) {
                values.add(value);
            }
        }
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.put("label", stringValue(base.getOrDefault("label", incoming.getOrDefault("label", labelForField(field)))));
        merged.put("values", values);
        merged.put("sample_values", values);
        merged.put("sample_options", values.stream()
                .map(code -> Map.<String, Object>of("code", code, "label", labels.getOrDefault(code, code)))
                .toList());
        merged.put("value_labels", labels);
        merged.put("size", values.size());
        return merged;
    }

    private static List<String> dimensionValues(Map<String, Object> meta) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Object rawValues = meta.get("values");
        if (!(rawValues instanceof List<?> list)) {
            rawValues = meta.get("sample_values");
        }
        if (rawValues instanceof List<?> list) {
            for (Object value : list) {
                String code = stringValue(value);
                if (!code.isBlank() && seen.add(code)) {
                    out.add(code);
                }
            }
        }
        Object rawOptions = meta.get("sample_options");
        if (!(rawOptions instanceof List<?> optionList)) {
            rawOptions = meta.get("options");
        }
        if (rawOptions instanceof List<?> optionList) {
            for (Object item : optionList) {
                if (!(item instanceof Map<?, ?> rawOption)) {
                    continue;
                }
                Map<String, Object> option = castMap(rawOption);
                String code = firstNonBlank(
                        stringValue(option.get("code")),
                        stringValue(option.get("id")),
                        stringValue(option.get("value")));
                if (!code.isBlank() && seen.add(code)) {
                    out.add(code);
                }
            }
        }
        return out;
    }

    private static LinkedHashMap<String, String> dimensionLabels(Map<String, Object> meta) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        Object rawLabels = meta.get("value_labels");
        if (rawLabels instanceof Map<?, ?> labelMap) {
            for (Map.Entry<?, ?> entry : labelMap.entrySet()) {
                String code = stringValue(entry.getKey());
                String label = stringValue(entry.getValue());
                if (!code.isBlank() && !label.isBlank()) {
                    out.put(code, label);
                }
            }
        }
        Object rawOptions = meta.get("sample_options");
        if (!(rawOptions instanceof List<?> optionList)) {
            rawOptions = meta.get("options");
        }
        if (rawOptions instanceof List<?> optionList) {
            for (Object item : optionList) {
                if (!(item instanceof Map<?, ?> rawOption)) {
                    continue;
                }
                Map<String, Object> option = castMap(rawOption);
                String code = firstNonBlank(
                        stringValue(option.get("code")),
                        stringValue(option.get("id")),
                        stringValue(option.get("value")));
                String label = firstNonBlank(
                        stringValue(option.get("label")),
                        stringValue(option.get("name")),
                        code);
                if (!code.isBlank()) {
                    out.put(code, label);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buildSelectableDimensions(Map<String, Object> availableDimensions) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Object> entry : availableDimensions.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> rawMeta)) {
                continue;
            }
            Map<String, Object> meta = (Map<String, Object>) rawMeta;
            Object rawValues = meta.get("values");
            if (!(rawValues instanceof List<?> values) || values.size() < 2) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", entry.getKey());
            row.put("label", meta.getOrDefault("label", entry.getKey()));
            row.put("values", rawValues);
            row.put("options", meta.getOrDefault("sample_options", List.of()));
            out.add(row);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buildExtraDimensions(Map<String, Object> availableDimensions) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Object> entry : availableDimensions.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> rawMeta)) {
                continue;
            }
            Map<String, Object> meta = (Map<String, Object>) rawMeta;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", entry.getKey());
            row.put("label", meta.getOrDefault("label", entry.getKey()));
            row.put("values", meta.getOrDefault("values", List.of()));
            out.add(row);
        }
        return out;
    }

    private static String dimensionValueLabel(Map<String, Object> row, String field, String fallback) {
        for (String candidate : labelFieldCandidates(field)) {
            String label = lookupStringIgnoreCase(row, candidate);
            if (!label.isBlank()) {
                return label;
            }
        }
        return fallback;
    }

    private static List<String> labelFieldCandidates(String field) {
        String lower = dimensionFieldKey(field);
        List<String> out = new ArrayList<>();
        out.add(field + "_label");
        out.add(lower + "_label");
        if (lower.equals("geo") || lower.equals("ref_area") || lower.equals("country")) {
            out.add("geo_label");
            out.add("ref_area_label");
            out.add("country_label");
        }
        if (lower.equals("freq")) {
            out.add("freq_label");
            out.add("FREQ_label");
        }
        if (lower.equals("unit")) {
            out.add("unit_label");
        }
        if (lower.equals("coicop") || lower.equals("icp_item")) {
            out.add("coicop_label");
            out.add("icp_item_label");
        }
        if (lower.equals("indicator_id")) {
            out.add("indicator_name");
            out.add("indicator_label");
        }
        return out;
    }

    private static String lookupStringIgnoreCase(Map<String, Object> row, String key) {
        Object direct = row.get(key);
        if (direct != null) {
            return stringValue(direct);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return stringValue(entry.getValue());
            }
        }
        return "";
    }

    private static boolean isDimensionCandidate(String field) {
        String lower = field.toLowerCase();
        String key = dimensionFieldKey(field);
        if (key.isBlank()) {
            return false;
        }
        if (isSkippedDimensionKey(key)) {
            return false;
        }
        if (lower.equals("date")
                || lower.equals("datum")
                || lower.equals("time")
                || lower.equals("time_period")
                || lower.equals("period")
                || lower.equals("year")
                || lower.equals("rok")
                || lower.equals("roky")
                || lower.equals("obdobi")
                || lower.equals("období")
                || lower.equals("value")
                || lower.equals("amount")
                || lower.equals("obs_value")
                || lower.equals("raw_value")
                || lower.equals("value_num")
                || lower.equals("observation_value_raw")
                || lower.equals("y")
                || lower.equals("key")
                || lower.equals("source")
                || lower.equals("source_type")
                || lower.equals("dataset")
                || lower.equals("dataset_id")
                || lower.equals("title")
                || lower.equals("name")
                || lower.equals("series_id")
                || lower.equals("variable")) {
            return false;
        }
        return !key.endsWith("_label")
                && !key.endsWith("_date")
                && !key.endsWith("_period")
                && !key.endsWith("_time")
                && !key.endsWith("_value");
    }

    private static boolean isSkippedDimensionKey(String key) {
        return switch (key) {
            case "date",
                    "datum",
                    "time",
                    "time_period",
                    "period",
                    "year",
                    "rok",
                    "roky",
                    "obdobi",
                    "month",
                    "months",
                    "mesic",
                    "mesice",
                    "quarter",
                    "quarters",
                    "ctvrtleti",
                    "day",
                    "days",
                    "den",
                    "dny",
                    "value",
                    "amount",
                    "hodnota",
                    "hodnoty",
                    "obs_value",
                    "raw_value",
                    "value_num",
                    "observation_value_raw",
                    "y",
                    "key",
                    "source",
                    "source_type",
                    "dataset",
                    "dataset_id",
                    "set_id",
                    "snapshot_id",
                    "title",
                    "name",
                    "indicator_name",
                    "series_id",
                    "series_name",
                    "variable",
                    "full_path",
                    "catalog_path",
                    "catalog_id",
                    "catalog_label",
                    "path",
                    "open",
                    "high",
                    "low",
                    "close",
                    "adj_close",
                    "volume",
                    "ticker" -> true;
            default -> false;
        };
    }

    private static String labelForField(String field) {
        return switch (dimensionFieldKey(field)) {
            case "date", "datum", "time", "time_period", "period", "year", "rok", "roky", "obdobi",
                    "month", "months", "mesic", "mesice", "quarter", "quarters", "ctvrtleti" -> "Obdobi";
            case "value", "obs_value", "amount", "hodnota", "hodnoty" -> "Hodnota";
            case "geo", "ref_area", "country" -> "Zeme";
            case "geo_label", "ref_area_label", "country_label" -> "Nazev zeme";
            case "unit" -> "Jednotka";
            case "unit_label" -> "Popis jednotky";
            case "freq" -> "Frekvence";
            case "freq_label" -> "Popis frekvence";
            case "coicop", "icp_item" -> "Polozka";
            case "coicop_label" -> "Popis polozky";
            case "indicator_id", "indicator" -> "Ukazatel";
            default -> field;
        };
    }

    private static Map<String, Object> filtersApplied(Map<String, Object> payload, Map<String, Object> source) {
        Object sourceFilters = source.get("_preview_filters_applied");
        if (sourceFilters instanceof Map<?, ?> sourceMap) {
            return castMap(sourceMap);
        }
        Object rawFilters = payload.get("dimension_filters");
        if (rawFilters instanceof Map<?, ?> filterMap) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : castMap(filterMap).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !String.valueOf(entry.getValue()).isBlank()) {
                    out.put(entry.getKey(), entry.getValue());
                }
            }
            return out;
        }
        return Map.of();
    }

    private static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static String dimensionFieldKey(String field) {
        return CatalogTextUtils.foldAscii(field).trim();
    }

    private static List<Map<String, Object>> sampleRows(List<Map<String, Object>> records, int limit) {
        if (records.size() <= limit) {
            return records;
        }
        // Kdyby limit i tak nestacil, radsi zahod nejstarsi zaznamy nez nejnovejsi - u casove
        // razene rady (ceny akcii, denni kurzy...) uzivatele zajima spis nedavna historie nez
        // nejstarsi dostupny zaznam.
        return records.subList(records.size() - limit, records.size());
    }

    private static String stringField(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
