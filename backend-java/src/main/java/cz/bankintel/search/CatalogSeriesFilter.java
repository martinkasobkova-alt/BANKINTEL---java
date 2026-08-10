package cz.bankintel.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Filtrace řádků preview podle ukazatele / geo pro více řad v jednom datasetu.
 *
 * <p>Port Python {@code catalog_preview_routes._apply_catalog_series_filter}.
 */
public final class CatalogSeriesFilter {

    private static final int ARAD_AUTO_MULTI_LIMIT = 8;
    private static final Set<String> GEO_FIELDS = Set.of("geo", "ref_area", "REF_AREA", "country", "COUNTRY");
    private static final Set<String> MARKET_DATA_SOURCE_TYPES = Set.of("yahoo_finance", "alphavantage");
    private static final List<DimensionPreference> DEFAULT_DIMENSION_PREFERENCES = List.of(
            new DimensionPreference("coicop", List.of("CP00", "TOTAL", "ALL"), List.of("all-items", "overall", "total")),
            new DimensionPreference("icp_item", List.of("PCCI00", "CPI", "TOTAL", "ALL"), List.of("overall", "total")),
            new DimensionPreference("freq", List.of("M", "Q", "A"), List.of("monthly", "quarterly", "annual")),
            new DimensionPreference("unit", List.of("PC", "PCH", "RCH_A", "RCH_M", "I15", "I16", "IX", "INDEX"), List.of()),
            // Eurostat short-term business statistics family (sts_inpr_m/sts_intv_m/sts_inpp_m/...):
            // indic_bt (which measure) and nace_r2 (which NACE activity) must each narrow to a single
            // value BEFORE s_adj is considered below, or requesting the full cross-product for even
            // ONE country exceeds Eurostat's own API payload limit - confirmed live: Eurostat returns
            // HTTP 413 for sts_inpr_m/geo=IT with no indicator selected, so without a default here
            // every one of these datasets fails outright for every country whenever nothing upstream
            // (a curated seed's pre-set query_params) already picked a specific measure/activity.
            // "B_C" (mining & manufacturing) is the broadest total NACE code these datasets publish;
            // PRD/NETTUR/PRC_PRR are each dataset's own single "the measure" code.
            new DimensionPreference("indic_bt", List.of("PRD", "NETTUR", "PRC_PRR"), List.of()),
            new DimensionPreference("nace_r2", List.of("B_C", "C", "B-D"), List.of("mining and quarrying; manufactur")),
            // Availability differs per dataset (e.g. sts_inpp_m has no SCA variant for B_C/PRC_PRR,
            // only CA/NSA) - chooseDefaultDimensionValue already tries each code in order and only
            // returns one actually present in the (by-then narrowed) working set, so listing all
            // three here is safe: whichever the dataset actually has wins.
            new DimensionPreference("s_adj", List.of("SCA", "CA", "NSA"), List.of()));

    private CatalogSeriesFilter() {}

    public static FilterResult apply(List<Map<String, Object>> records, Map<String, Object> payload) {
        List<Map<String, Object>> dimensionRecords = new ArrayList<>(records);
        List<Map<String, Object>> working = new ArrayList<>(records);
        String selected = stringField(payload, "selected_indicator");
        List<String> selectedMany = parseSelectedMany(payload.get("selected_indicators"));
        if (!selectedMany.isEmpty() && selected.isBlank()) {
            selected = selectedMany.get(0);
        }
        String requestedSelected = selected;
        List<String> requestedSelectedMany = List.copyOf(selectedMany);
        boolean explicitIndicatorSelection = !requestedSelected.isBlank() || !requestedSelectedMany.isEmpty();

        String sourceType = stringField(payload, "source_type").toLowerCase(Locale.ROOT);
        List<DimensionFilterSpec> dimensionFilters = requestedDimensionFilters(payload, sourceType);
        if (!dimensionFilters.isEmpty()) {
            working = applyDimensionFilters(working, dimensionFilters);
        }
        if (selected.isBlank() && selectedMany.isEmpty()) {
            working = autoNarrowDefaultDimensions(working);
        }

        String groupField = MARKET_DATA_SOURCE_TYPES.contains(sourceType) ? null : detectGroupField(working);

        if ("world_bank_data360".equals(sourceType)) {
            if (groupField != null && isGeoDimensionField(groupField)) {
                Set<String> geoVals = collectValues(working, groupField);
                if (valuesAreBooleanLike(geoVals)) {
                    groupField = null;
                }
            } else {
                groupField = null;
            }
        }

        if (groupField == null) {
            return new FilterResult(working, null, List.of(), selected, selectedMany, dimensionRecords);
        }

        final String groupFieldFinal = groupField;
        List<Map<String, Object>> indicators = summarizeIndicators(working, groupFieldFinal, sourceType);
        List<String> requestedGeos = requestedGeoCodes(payload);
        if (isGeoDimensionField(groupFieldFinal) && !requestedGeos.isEmpty()) {
            selected = "";
            selectedMany = List.of();
            explicitIndicatorSelection = false;
        }
        if (selected.isBlank() && !indicators.isEmpty() && "arad".equals(sourceType) && "indicator_id".equals(groupField)) {
            List<String> ids = indicators.stream().map(i -> String.valueOf(i.get("id"))).toList();
            if (ids.size() > 1 && ids.size() <= ARAD_AUTO_MULTI_LIMIT) {
                selectedMany = ids;
                selected = ids.get(0);
            } else {
                selected = String.valueOf(indicators.get(0).get("id"));
            }
        }

        Set<String> validIds =
                new LinkedHashSet<>(indicators.stream().map(i -> String.valueOf(i.get("id"))).filter(id -> !id.isBlank()).toList());
        selectedMany = selectedMany.stream().filter(validIds::contains).toList();
        if (explicitIndicatorSelection && !validIds.isEmpty()) {
            boolean hasRequested = (!requestedSelected.isBlank() && validIds.contains(requestedSelected))
                    || requestedSelectedMany.stream().anyMatch(validIds::contains);
            if (!hasRequested) {
                List<String> requestedMany = requestedSelectedMany.isEmpty() && !requestedSelected.isBlank()
                        ? List.of(requestedSelected)
                        : requestedSelectedMany;
                return new FilterResult(List.of(), groupFieldFinal, indicators, requestedSelected, requestedMany, dimensionRecords);
            }
        }
        if (!selected.isBlank() && !validIds.isEmpty() && !validIds.contains(selected) && selectedMany.isEmpty()) {
            selected = "";
        }
        if (selectedMany.isEmpty() && !selected.isBlank()) {
            selectedMany = List.of(selected);
        }

        boolean multiGeoCompare = isGeoDimensionField(groupFieldFinal) && requestedGeos.size() > 1;
        if (multiGeoCompare) {
            Set<String> allowed = new LinkedHashSet<>();
            for (String geo : requestedGeos) {
                if (validIds.isEmpty() || containsGeoEquivalent(validIds, geo)) {
                    allowed.add(geo);
                }
            }
            if (allowed.isEmpty()) {
                allowed.addAll(requestedGeos);
            }
            Set<String> allowedGeoKeys = new LinkedHashSet<>();
            allowed.forEach(g -> allowedGeoKeys.addAll(geoComparableKeys(g)));
            working = working.stream()
                    .filter(row -> intersectsGeoKeys(allowedGeoKeys, geoComparableKeys(row.get(groupFieldFinal))))
                    .toList();
            selectedMany = requestedGeos.stream().filter(allowed::contains).toList();
            if (selectedMany.isEmpty()) {
                selectedMany = new ArrayList<>(allowed);
            }
            selected = selectedMany.isEmpty() ? selected : selectedMany.get(0);
        } else if (!selectedMany.isEmpty()) {
            Set<String> allowed = new LinkedHashSet<>(selectedMany);
            working = working.stream()
                    .filter(row -> allowed.contains(String.valueOf(row.get(groupFieldFinal)).trim()))
                    .toList();
            selected = selectedMany.get(0);
        }

        return new FilterResult(working, groupFieldFinal, indicators, selected, selectedMany, dimensionRecords);
    }

    private static List<Map<String, Object>> applyDimensionFilters(
            List<Map<String, Object>> records, List<DimensionFilterSpec> filters) {
        List<Map<String, Object>> working = records;
        for (DimensionFilterSpec filter : filters) {
            if (working.isEmpty()) {
                return working;
            }
            String field = findMatchingField(working, filter.field());
            if (field == null) {
                continue;
            }
            List<Map<String, Object>> filtered = working.stream()
                    .filter(row -> filter.matches(row.get(field)))
                    .toList();
            working = filtered;
        }
        return working;
    }

    private static List<Map<String, Object>> autoNarrowDefaultDimensions(List<Map<String, Object>> records) {
        List<Map<String, Object>> working = records;
        for (DimensionPreference preference : DEFAULT_DIMENSION_PREFERENCES) {
            String field = findField(working, preference.field());
            if (field == null) {
                continue;
            }
            Set<String> values = collectValues(working, field);
            if (values.size() <= 1) {
                continue;
            }
            String selected = chooseDefaultDimensionValue(working, field, preference);
            if (selected.isBlank()) {
                continue;
            }
            List<Map<String, Object>> filtered = working.stream()
                    .filter(row -> selected.equalsIgnoreCase(String.valueOf(row.get(field)).trim()))
                    .toList();
            if (filtered.size() >= 2) {
                working = filtered;
            }
        }
        return working;
    }

    private static String chooseDefaultDimensionValue(
            List<Map<String, Object>> records, String field, DimensionPreference preference) {
        Set<String> values = collectValues(records, field);
        for (String preferred : preference.codes()) {
            for (String value : values) {
                if (preferred.equalsIgnoreCase(value)) {
                    return value;
                }
            }
        }
        String labelField = field + "_label";
        for (String labelNeedle : preference.labelNeedles()) {
            String foldedNeedle = CatalogTextUtils.foldAscii(labelNeedle);
            for (Map<String, Object> row : records) {
                String value = String.valueOf(row.get(field)).trim();
                String label = CatalogTextUtils.foldAscii(String.valueOf(row.getOrDefault(labelField, "")));
                if (!value.isBlank() && !foldedNeedle.isBlank() && label.contains(foldedNeedle)) {
                    return value;
                }
            }
        }
        return "";
    }

    private static String findField(List<Map<String, Object>> records, String preferredLower) {
        if (records.isEmpty()) {
            return null;
        }
        for (String key : records.getFirst().keySet()) {
            if (preferredLower.equalsIgnoreCase(key)) {
                return key;
            }
        }
        return null;
    }

    private static String findMatchingField(List<Map<String, Object>> records, String requested) {
        if (records.isEmpty() || requested == null || requested.isBlank()) {
            return null;
        }
        String normalizedRequested = CatalogTextUtils.foldAscii(requested).trim();
        for (Map<String, Object> row : records) {
            for (String key : row.keySet()) {
                if (key.equals(requested) || key.equalsIgnoreCase(requested)) {
                    return key;
                }
                if (CatalogTextUtils.foldAscii(key).trim().equals(normalizedRequested)) {
                    return key;
                }
            }
        }
        return null;
    }

    private static String detectGroupField(List<Map<String, Object>> records) {
        if (records.isEmpty()) {
            return null;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Set<String>> distinct = new LinkedHashMap<>();
        for (Map<String, Object> row : records) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getValue() == null || String.valueOf(entry.getValue()).isBlank()) {
                    continue;
                }
                String key = entry.getKey();
                if (isSkipField(key)) {
                    continue;
                }
                counts.merge(key, 1, Integer::sum);
                distinct.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(String.valueOf(entry.getValue()).trim());
            }
        }
        String field = fieldWithMultipleValues(distinct, "indicator_id");
        if (field != null) {
            return field;
        }
        for (String candidate : List.of("series_id", "variable", "coicop", "ICP_ITEM", "icp_item", "indicator", "measure")) {
            field = fieldWithMultipleValues(distinct, candidate);
            if (field != null) {
                return field;
            }
        }
        for (String candidate : List.of("geo", "REF_AREA", "ref_area", "country")) {
            field = fieldWithMultipleValues(distinct, candidate);
            if (field != null) {
                return field;
            }
        }
        for (String candidate : List.of("unit", "freq")) {
            field = fieldWithMultipleValues(distinct, candidate);
            if (field != null) {
                return field;
            }
        }
        for (String candidate : counts.keySet()) {
            Integer count = counts.get(candidate);
            if (count != null && count > 1 && fieldWithMultipleValues(distinct, candidate) != null) {
                return candidate;
            }
        }
        return null;
    }

    private static String fieldWithMultipleValues(Map<String, Set<String>> distinct, String key) {
        Set<String> values = distinct.get(key);
        String actualKey = key;
        if (values == null) {
            for (Map.Entry<String, Set<String>> entry : distinct.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    values = entry.getValue();
                    actualKey = entry.getKey();
                    break;
                }
            }
        }
        return values != null && values.size() > 1 ? actualKey : null;
    }

    private static List<Map<String, Object>> summarizeIndicators(
            List<Map<String, Object>> records, String groupField, String sourceType) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : records) {
            String id = String.valueOf(row.get(groupField)).trim();
            if (id.isBlank()) {
                continue;
            }
            byId.computeIfAbsent(id, key -> {
                Map<String, Object> indicator = new LinkedHashMap<>();
                indicator.put("id", key);
                indicator.put("name", key);
                indicator.put("count", 0);
                return indicator;
            });
            Map<String, Object> indicator = byId.get(id);
            indicator.put("count", ((Number) indicator.get("count")).intValue() + 1);
            String label = groupValueLabel(row, groupField, id, sourceType);
            if (!label.isBlank() && !label.equals(id)) {
                indicator.put("name", label);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static String groupValueLabel(
            Map<String, Object> row, String groupField, String fallback, String sourceType) {
        for (String candidate : groupLabelFieldCandidates(groupField, sourceType)) {
            String label = lookupStringIgnoreCase(row, candidate);
            if (!label.isBlank() && !label.equals(fallback)) {
                return label;
            }
        }
        return fallback;
    }

    private static List<String> groupLabelFieldCandidates(String field, String sourceType) {
        String raw = field == null ? "" : field.trim();
        String lower = normalizeDimensionField(raw);
        List<String> out = new ArrayList<>();
        if (!raw.isBlank()) {
            out.add(raw + "_label");
            out.add(lower + "_label");
            out.add(raw + "_name");
            out.add(lower + "_name");
        }
        if (isIndicatorLikeDimension(lower) || "arad".equals(sourceType)) {
            out.add("indicator_name");
            out.add("indicator_label");
            out.add("series_name");
            out.add("series_label");
            out.add("variable_name");
            out.add("variable_label");
        }
        return out;
    }

    private static boolean isIndicatorLikeDimension(String key) {
        return key.contains("indicator")
                || key.contains("ukazatel")
                || key.contains("series")
                || key.contains("measure")
                || key.contains("metric")
                || key.contains("instrument")
                || key.contains("variable");
    }

    private static List<String> requestedGeoCodes(Map<String, Object> payload) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Object rawQp = payload.get("query_params");
        if (rawQp instanceof Map<?, ?> qp) {
            addGeoTokens(out, seen, qp.get("geo"));
            addGeoTokens(out, seen, qp.get("REF_AREA"));
        }
        Object rawDimensionFilters = payload.get("dimension_filters");
        if (rawDimensionFilters instanceof Map<?, ?> dimFilters) {
            for (Map.Entry<?, ?> entry : dimFilters.entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).trim();
                if (isGeoDimensionField(key)) {
                    addGeoTokens(out, seen, entry.getValue());
                }
            }
        }
        addGeoTokens(out, seen, payload.get("country"));
        return out;
    }

    private static List<DimensionFilterSpec> requestedDimensionFilters(Map<String, Object> payload, String sourceType) {
        Map<String, DimensionFilterSpec> specsByField = new LinkedHashMap<>();
        Object rawQp = payload.get("query_params");
        if ("csu".equals(sourceType) && rawQp instanceof Map<?, ?> qp) {
            Object rawCsuFilters = qp.get("csu_filters");
            if (rawCsuFilters instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> rawMap) {
                        Map<String, Object> map = castMap(rawMap);
                        String field = firstNonBlank(
                                stringField(map, "field"),
                                stringField(map, "name"),
                                stringField(map, "dimension"),
                                stringField(map, "kod"));
                        if (field.isBlank()) {
                            continue;
                        }
                        List<Object> exactRaw = new ArrayList<>();
                        exactRaw.add(map.get("exact"));
                        exactRaw.add(map.get("value"));
                        List<Object> containsRaw = new ArrayList<>();
                        containsRaw.add(map.get("contains"));
                        DimensionFilterSpec spec = DimensionFilterSpec.fromRaw(field, exactRaw, containsRaw);
                        if (!spec.isEmpty()) {
                            specsByField.put(normalizeDimensionField(field), spec);
                        }
                    }
                }
            }
        }

        Object rawDimensionFilters = payload.get("dimension_filters");
        if (rawDimensionFilters instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = castMap(rawMap);
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String field = entry.getKey() != null ? entry.getKey().trim() : "";
                if (field.isBlank() || "indicator_id".equalsIgnoreCase(field)) {
                    continue;
                }
                DimensionFilterSpec spec = DimensionFilterSpec.exact(field, entry.getValue());
                if (!spec.isEmpty()) {
                    specsByField.put(normalizeDimensionField(field), spec);
                }
            }
        }
        return new ArrayList<>(specsByField.values());
    }

    private static void addGeoTokens(List<String> out, Set<String> seen, Object raw) {
        if (raw == null) {
            return;
        }
        Iterable<?> values = raw instanceof Iterable<?> iterable ? iterable : List.of(raw);
        for (Object item : values) {
            String text = String.valueOf(item).replace("[", "").replace("]", "");
            for (String token : text.split("[,+]")) {
                String code = token.trim().toUpperCase(Locale.ROOT);
                if (!code.isBlank() && seen.add(code)) {
                    out.add(code);
                }
            }
        }
    }

    private static Set<String> collectValues(List<Map<String, Object>> records, String field) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> row : records) {
            Object value = row.get(field);
            if (value != null && !String.valueOf(value).isBlank()) {
                out.add(String.valueOf(value).trim());
            }
        }
        return out;
    }

    private static boolean valuesAreBooleanLike(Set<String> values) {
        if (values.isEmpty()) {
            return false;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return normalized.size() <= 2 && normalized.stream().allMatch(v -> "0".equals(v) || "1".equals(v) || "true".equals(v) || "false".equals(v));
    }

    private static boolean isGeoDimensionField(String field) {
        String normalized = field == null ? "" : field.trim().toLowerCase(Locale.ROOT);
        return "geo".equals(normalized) || "ref_area".equals(normalized) || "country".equals(normalized);
    }

    private static boolean containsGeoEquivalent(Set<String> values, String requested) {
        Set<String> requestedKeys = geoComparableKeys(requested);
        for (String value : values) {
            if (intersectsGeoKeys(requestedKeys, geoComparableKeys(value))) {
                return true;
            }
        }
        return false;
    }

    private static boolean geoValuesEquivalent(String left, String right) {
        return intersectsGeoKeys(geoComparableKeys(left), geoComparableKeys(right));
    }

    private static Set<String> geoComparableKeys(Object raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        String text = String.valueOf(raw).replace("[", "").replace("]", "");
        for (String token : text.split("[,+]")) {
            String code = token.trim().toUpperCase(Locale.ROOT);
            if (code.isBlank()) {
                continue;
            }
            out.add(code);
            if (code.length() == 2) {
                String iso3 = CatalogCountryIso3Registry.iso3For(code);
                if (!iso3.isBlank()) {
                    out.add(iso3);
                }
            } else if (code.length() == 3) {
                String iso2 = CatalogCountryIso3Registry.iso2For(code);
                if (!iso2.isBlank()) {
                    out.add(iso2);
                }
            }
        }
        return out;
    }

    private static boolean intersectsGeoKeys(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        for (String key : left) {
            if (right.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSkipField(String field) {
        String lower = field.toLowerCase(Locale.ROOT);
        String key = CatalogTextUtils.foldAscii(field).trim();
        return key.equals("date")
                || key.equals("datum")
                || key.equals("time")
                || key.equals("period")
                || key.equals("time_period")
                || key.equals("year")
                || key.equals("rok")
                || key.equals("roky")
                || key.equals("obdobi")
                || key.equals("month")
                || key.equals("months")
                || key.equals("mesic")
                || key.equals("mesice")
                || key.equals("quarter")
                || key.equals("quarters")
                || key.equals("ctvrtleti")
                || key.equals("day")
                || key.equals("days")
                || key.equals("den")
                || key.equals("dny")
                || key.equals("value")
                || key.equals("amount")
                || key.equals("hodnota")
                || key.equals("hodnoty")
                || key.equals("obs_value")
                || key.equals("raw_value")
                || key.equals("value_num")
                || key.equals("observation_value_raw")
                || key.equals("y")
                || key.equals("key")
                || key.equals("source")
                || key.equals("source_type")
                || key.equals("dataset")
                || key.equals("dataset_id")
                || key.equals("set_id")
                || key.equals("snapshot_id")
                || key.equals("title")
                || key.equals("name")
                || key.equals("indicator_name")
                || key.equals("series_name")
                || key.equals("full_path")
                || key.equals("catalog_path")
                || key.equals("catalog_id")
                || key.equals("catalog_label")
                || key.equals("path")
                || lower.equals("date")
                || lower.equals("datum")
                || lower.equals("time")
                || lower.equals("period")
                || lower.equals("time_period")
                || lower.equals("year")
                || lower.equals("rok")
                || lower.equals("roky")
                || lower.equals("obdobi")
                || lower.equals("období")
                || lower.equals("value")
                || lower.equals("amount")
                || lower.equals("obs_value")
                || lower.equals("raw_value")
                || lower.equals("y")
                || lower.endsWith("_date")
                || lower.endsWith("_period")
                || lower.endsWith("_time")
                || lower.endsWith("_value")
                || key.endsWith("_label")
                || key.endsWith("_date")
                || key.endsWith("_period")
                || key.endsWith("_time")
                || key.endsWith("_value");
    }

    private static List<String> parseSelectedMany(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            String value = String.valueOf(item).trim();
            if (!value.isBlank() && seen.add(value)) {
                out.add(value);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static String stringField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String lookupStringIgnoreCase(Map<String, Object> map, String key) {
        if (map == null || key == null || key.isBlank()) {
            return "";
        }
        Object direct = map.get(key);
        if (direct != null) {
            return String.valueOf(direct).trim();
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                Object value = entry.getValue();
                return value == null ? "" : String.valueOf(value).trim();
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String normalizeDimensionField(String field) {
        return CatalogTextUtils.foldAscii(field).trim().toLowerCase(Locale.ROOT);
    }

    private record DimensionPreference(String field, List<String> codes, List<String> labelNeedles) {}

    private record DimensionFilterSpec(String field, List<String> exactValues, List<String> containsValues) {
        static DimensionFilterSpec exact(String field, Object rawValue) {
            return new DimensionFilterSpec(field, normalizeValues(rawValue), List.of());
        }

        static DimensionFilterSpec fromRaw(String field, List<Object> exactRawValues, List<Object> containsRawValues) {
            List<String> exact = new ArrayList<>();
            for (Object raw : exactRawValues) {
                exact.addAll(normalizeValues(raw));
            }
            List<String> contains = new ArrayList<>();
            for (Object raw : containsRawValues) {
                contains.addAll(normalizeValues(raw));
            }
            return new DimensionFilterSpec(field, distinct(exact), distinct(contains));
        }

        boolean isEmpty() {
            return exactValues.isEmpty() && containsValues.isEmpty();
        }

        boolean matches(Object rawValue) {
            String value = rawValue == null ? "" : String.valueOf(rawValue).trim();
            if (value.isBlank()) {
                return false;
            }
            String folded = CatalogTextUtils.foldAscii(value).trim();
            if (!exactValues.isEmpty()) {
                for (String exact : exactValues) {
                    if (isGeoDimensionField(field) && geoValuesEquivalent(value, exact)) {
                        return true;
                    }
                    if (value.equals(exact) || value.equalsIgnoreCase(exact) || folded.equals(CatalogTextUtils.foldAscii(exact).trim())) {
                        return true;
                    }
                }
                return false;
            }
            for (String contains : containsValues) {
                String needle = CatalogTextUtils.foldAscii(contains).trim();
                if (!needle.isBlank() && folded.contains(needle)) {
                    return true;
                }
            }
            return containsValues.isEmpty();
        }

        private static List<String> normalizeValues(Object raw) {
            if (raw == null) {
                return List.of();
            }
            if (raw instanceof Iterable<?> iterable) {
                List<String> out = new ArrayList<>();
                for (Object item : iterable) {
                    String value = String.valueOf(item == null ? "" : item).trim();
                    if (!value.isBlank()) {
                        out.add(value);
                    }
                }
                return distinct(out);
            }
            String value = String.valueOf(raw).trim();
            if (value.isBlank()) {
                return List.of();
            }
            return List.of(value);
        }

        private static List<String> distinct(List<String> values) {
            List<String> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String value : values) {
                String key = CatalogTextUtils.foldAscii(value).trim().toLowerCase(Locale.ROOT);
                if (!key.isBlank() && seen.add(key)) {
                    out.add(value);
                }
            }
            return out;
        }
    }

    public record FilterResult(
            List<Map<String, Object>> records,
            String groupField,
            List<Map<String, Object>> indicators,
            String selectedIndicator,
            List<String> selectedIndicators,
            List<Map<String, Object>> dimensionRecords) {

        public FilterResult(
                List<Map<String, Object>> records,
                String groupField,
                List<Map<String, Object>> indicators,
                String selectedIndicator,
                List<String> selectedIndicators) {
            this(records, groupField, indicators, selectedIndicator, selectedIndicators, records);
        }
    }
}
