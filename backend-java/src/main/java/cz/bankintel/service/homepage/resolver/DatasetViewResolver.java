package cz.bankintel.service.homepage.resolver;

import cz.bankintel.domain.entity.DatasetEntity;
import cz.bankintel.domain.entity.RecordEntity;
import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.DatasetRepository;
import cz.bankintel.repository.RecordRepository;
import cz.bankintel.repository.SourceRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.userdata.UserDataParseService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Plné vykreslení dataset widgetů — obdoba Python {@code _resolve_dataset_view}.
 */
@Component
@RequiredArgsConstructor
public class DatasetViewResolver {

    private static final int MAX_ROWS = 5000;
    private static final int PIVOT_MAX_ROWS = 50000;
    private static final int MAX_SERIES = 12;

    private static final Set<String> CHART_DEFAULT_TYPES = Set.of(
            "dataset_view",
            "eurostat_view",
            "csu_view",
            "ecb_view",
            "fred_view",
            "alphavantage_view",
            "worldbank_view",
            "world_bank_data360_view",
            "bis_view",
            "imf_view",
            "oecd_view");

    private final SourceRepository sourceRepository;
    private final DatasetRepository datasetRepository;
    private final RecordRepository recordRepository;
    private final FeatureAccessService featureAccessService;

    public Map<String, Object> resolve(Map<String, Object> cfg, String widgetType, UserEntity user) {
        String view = resolveViewMode(cfg, widgetType);
        int limit = parseLimit(cfg.get("limit"));
        String sourceId = str(cfg.get("source_id"));
        String datasetName = str(cfg.get("dataset_name"));
        String sourceTypeHint = str(cfg.get("source_type"));
        SourceEntity source = null;
        if (!sourceId.isBlank()) {
            source = sourceRepository.findById(sourceId).orElse(null);
            if (source != null) {
                if (datasetName.isBlank()) {
                    datasetName = source.getDatasetName() != null ? source.getDatasetName() : source.getName();
                }
                if (sourceTypeHint.isBlank()) {
                    sourceTypeHint = source.getSourceType();
                }
            }
        }
        if (sourceId.isBlank() && datasetName.isBlank()) {
            return Map.of("error", "Vyber zdroj nebo datovou sadu.");
        }

        boolean pivotConfigured = !str(cfg.get("chart_series_dim")).isBlank();
        List<Map<String, Object>> allRows = loadRows(sourceId, datasetName, pivotConfigured ? 0 : limit, pivotConfigured);
        if (allRows.isEmpty()) {
            return Map.of(
                    "error",
                    "Datová sada nemá záznamy. Spusť synchronizaci zdroje (tlačítko Sync u zdroje).");
        }
        return resolveLoadedRows(allRows, cfg, widgetType, user, datasetName, sourceTypeHint, view, limit);
    }

    public Map<String, Object> resolveFromRows(
            List<Map<String, Object>> allRows, Map<String, Object> cfg, String widgetType, UserEntity user) {
        if (allRows == null || allRows.isEmpty()) {
            return Map.of("view", "chart", "error", "Chybí data pro vykreslení grafu.");
        }
        String datasetName = str(cfg.get("title")).isBlank() ? str(cfg.get("set_id")) : str(cfg.get("title"));
        String view = resolveViewMode(cfg, widgetType);
        int limit = parseLimit(cfg.get("limit"));
        return resolveLoadedRows(allRows, cfg, widgetType, user, datasetName, str(cfg.get("source_type")), view, limit);
    }

    private Map<String, Object> resolveLoadedRows(
            List<Map<String, Object>> allRows,
            Map<String, Object> cfg,
            String widgetType,
            UserEntity user,
            String datasetName,
            String sourceTypeHint,
            String view,
            int limit) {
        List<Map<String, Object>> rows = new ArrayList<>(allRows);
        rows = filterSeries(rows, cfg);
        rows = filterDimensionFilters(rows, cfg);
        rows = filterDateRange(rows, cfg);

        Set<String> keyUnion = rowKeyUnion(rows);
        String title = str(cfg.get("title")).isBlank() ? datasetName : str(cfg.get("title"));
        boolean forceSingle = "single".equalsIgnoreCase(str(cfg.get("chart_series_mode")));

        if ("chart".equals(view) && !forceSingle) {
            Object compareRaw = cfg.get("chart_compare_with");
            if (compareRaw instanceof List<?> compareList && !compareList.isEmpty()) {
                Map<String, Object> multi = resolveCompare(allRows, cfg, datasetName, title, widgetType, user, compareList);
                if (multi != null) {
                    return multi;
                }
            }
        }

        if ("table".equals(view)) {
            Map<String, Object> out = basePayload(title, datasetName, view, cfg);
            List<Map<String, Object>> tableRows = new ArrayList<>(rows);
            tableRows.sort(Comparator.comparing(r -> String.valueOf(r.getOrDefault("period", r.getOrDefault("date", ""))), Comparator.reverseOrder()));
            if (limit > 0 && tableRows.size() > limit) {
                tableRows = tableRows.subList(0, limit);
            }
            out.put("rows", tableRows);
            out.put("fields", rows.isEmpty() ? List.of() : new ArrayList<>(rows.getFirst().keySet()));
            return out;
        }

        String agg = resolveAgg(cfg, sourceTypeHint);
        String xField = resolveXField(cfg, keyUnion, str(cfg.get("series_field")));
        String yField = resolveYField(cfg, keyUnion);
        String chartSeriesDim = forceSingle ? "" : str(cfg.get("chart_series_dim"));
        String matchedDim = chartSeriesDim.isBlank() ? null : matchField(keyUnion, chartSeriesDim);

        if (matchedDim != null && keyUnion.contains(matchedDim)) {
            return buildPivotChart(rows, cfg, title, datasetName, matchedDim, xField, yField, agg);
        }

        List<Map<String, Object>> chartRows = aggregateChartRows(rows, xField, yField, agg);
        Map<String, Object> out = basePayload(title, datasetName, "chart", cfg);
        out.put("x_field", xField);
        out.put("y_field", yField);
        out.put("agg", agg);
        out.put("rows", chartRows);
        return out;
    }

    private Map<String, Object> buildPivotChart(
            List<Map<String, Object>> rows,
            Map<String, Object> cfg,
            String title,
            String datasetName,
            String dimField,
            String xField,
            String yField,
            String agg) {
        Set<String> dimValues = new TreeSet<>();
        for (Map<String, Object> row : rows) {
            Object val = row.get(dimField);
            if (val != null && !String.valueOf(val).isBlank()) {
                dimValues.add(String.valueOf(val).trim());
            }
        }
        List<Map<String, Object>> series = new ArrayList<>();
        int idx = 0;
        for (String dimValue : dimValues) {
            if (idx >= MAX_SERIES) {
                break;
            }
            List<Map<String, Object>> dimRows = rows.stream()
                    .filter(r -> dimValue.equals(String.valueOf(r.get(dimField)).trim()))
                    .toList();
            List<Map<String, Object>> chartRows = aggregateChartRows(dimRows, xField, yField, agg);
            if (chartRows.isEmpty()) {
                continue;
            }
            Map<String, Object> seriesEntry = new LinkedHashMap<>();
            seriesEntry.put("key", "s" + idx);
            seriesEntry.put("name", dimValue);
            seriesEntry.put("label", dimValue);
            seriesEntry.put("chart_type", str(cfg.get("chart_type")).isBlank() ? "line" : str(cfg.get("chart_type")));
            seriesEntry.put("y_axis", "left");
            seriesEntry.put("rows", chartRows);
            series.add(seriesEntry);
            idx++;
        }
        Map<String, Object> out = basePayload(title, datasetName, "chart", cfg);
        out.put("multi_series", true);
        out.put("chart_series_dim", dimField);
        out.put("x_field", xField);
        out.put("y_field", yField);
        out.put("agg", agg);
        out.put("series", series);
        out.put("rows", series.isEmpty() ? List.of() : series.getFirst().get("rows"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveCompare(
            List<Map<String, Object>> allRows,
            Map<String, Object> cfg,
            String datasetName,
            String title,
            String widgetType,
            UserEntity user,
            List<?> compareList) {
        String mainSf = str(cfg.get("series_field"));
        String mainSv = str(cfg.get("series_value"));
        String mainSid = str(cfg.get("source_id"));
        if (mainSf.isBlank() || mainSv.isBlank() || mainSid.isBlank()) {
            return null;
        }
        List<Map<String, Object>> extras = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : compareList) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) raw;
            String sid = str(entry.get("source_id")).isBlank() ? mainSid : str(entry.get("source_id"));
            if (!mainSid.equals(sid)) {
                continue;
            }
            String sv = str(entry.get("series_value"));
            if (sv.isBlank() || sv.equals(mainSv) || !seen.add(sv)) {
                continue;
            }
            extras.add(entry);
            if (extras.size() >= 8) {
                break;
            }
        }
        if (extras.isEmpty()) {
            return null;
        }
        if (!featureAccessService.canAccessFeature(user, "composite_charts")) {
            Map<String, Object> locked = basePayload(title, datasetName, "chart", cfg);
            locked.put("multi_series", true);
            locked.put("feature_lock", "composite_charts");
            locked.put(
                    "lock_message",
                    "Složené grafy a kombinace více datových řad jsou dostupné pro předplatitele Bankovnictví Online.");
            locked.put("rows", List.of());
            locked.put("series", List.of());
            return locked;
        }

        List<Map<String, Object>> series = new ArrayList<>();
        series.add(buildCompareSeries(cfg, allRows, mainSf, mainSv, str(cfg.get("title")).isBlank() ? title : str(cfg.get("title")), 0));
        int idx = 1;
        for (Map<String, Object> extra : extras) {
            String sf = str(extra.get("series_field")).isBlank() ? mainSf : str(extra.get("series_field"));
            String sv = str(extra.get("series_value"));
            String label = str(extra.get("label")).isBlank() ? sv : str(extra.get("label"));
            series.add(buildCompareSeries(extra, allRows, sf, sv, label, idx++));
        }
        Map<String, Object> out = basePayload(title, datasetName, "chart", cfg);
        out.put("multi_series", true);
        out.put("series", series);
        out.put("rows", series.isEmpty() ? List.of() : ((List<?>) series.getFirst().get("rows")));
        return out;
    }

    private Map<String, Object> buildCompareSeries(
            Map<String, Object> cfg,
            List<Map<String, Object>> allRows,
            String seriesField,
            String seriesValue,
            String label,
            int index) {
        Map<String, Object> jobCfg = new LinkedHashMap<>(cfg);
        jobCfg.put("series_field", seriesField);
        jobCfg.put("series_value", seriesValue);
        List<Map<String, Object>> rows = filterSeries(new ArrayList<>(allRows), jobCfg);
        rows = filterDimensionFilters(rows, cfg);
        Set<String> keys = rowKeyUnion(rows);
        String xField = resolveXField(cfg, keys, seriesField);
        String yField = resolveYField(cfg, keys);
        String agg = resolveAgg(cfg, str(cfg.get("source_type")));
        List<Map<String, Object>> chartRows = aggregateChartRows(rows, xField, yField, agg);
        Map<String, Object> seriesEntry = new LinkedHashMap<>();
        seriesEntry.put("key", "s" + index);
        seriesEntry.put("name", label);
        seriesEntry.put("label", label);
        seriesEntry.put(
                "chart_type",
                str(cfg.get("chart_type")).isBlank() ? "line" : str(cfg.get("chart_type")).toLowerCase(Locale.ROOT));
        seriesEntry.put("y_axis", "right".equalsIgnoreCase(str(cfg.get("y_axis"))) ? "right" : "left");
        seriesEntry.put("rows", chartRows);
        return seriesEntry;
    }

    private List<Map<String, Object>> loadRows(String sourceId, String datasetName, int limit, boolean pivot) {
        int cap = pivot ? PIVOT_MAX_ROWS : (limit > 0 ? Math.min(limit, MAX_ROWS) : MAX_ROWS);
        DatasetEntity dataset = datasetRepository.findByName(datasetName).orElse(null);
        if (dataset != null) {
            return recordRepository
                    .findByDatasetIdOrderByCreatedAtDesc(dataset.getId(), PageRequest.of(0, cap))
                    .stream()
                    .map(r -> r.getData() != null ? r.getData() : Map.<String, Object>of())
                    .toList();
        }
        if (!sourceId.isBlank()) {
            return recordRepository
                    .findAll((root, query, cb) -> cb.equal(root.get("sourceId"), sourceId), PageRequest.of(0, cap))
                    .getContent()
                    .stream()
                    .map(RecordEntity::getData)
                    .filter(d -> d != null)
                    .toList();
        }
        return List.of();
    }

    private static List<Map<String, Object>> filterSeries(List<Map<String, Object>> rows, Map<String, Object> cfg) {
        String seriesField = str(cfg.get("series_field"));
        String seriesValue = str(cfg.get("series_value"));
        if (seriesField.isBlank() || seriesValue.isBlank()) {
            return rows;
        }
        Set<String> keys = rowKeyUnion(rows);
        String field = matchField(keys, seriesField);
        if (field == null) {
            return rows;
        }
        return rows.stream()
                .filter(r -> seriesValue.equals(String.valueOf(r.get(field)).trim()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> filterDimensionFilters(List<Map<String, Object>> rows, Map<String, Object> cfg) {
        Object raw = cfg.get("dimension_filters");
        if (!(raw instanceof Map<?, ?> filters) || rows.isEmpty()) {
            return rows;
        }
        String pivotDim = str(cfg.get("chart_series_dim"));
        Set<String> keys = rowKeyUnion(rows);
        List<Map<String, Object>> out = new ArrayList<>(rows);
        for (Map.Entry<?, ?> entry : filters.entrySet()) {
            String fldRaw = String.valueOf(entry.getKey());
            String val = String.valueOf(entry.getValue()).trim();
            if (val.isBlank()) {
                continue;
            }
            String fld = matchField(keys, fldRaw);
            if (fld == null) {
                continue;
            }
            if (!pivotDim.isBlank()) {
                String pivotField = matchField(keys, pivotDim);
                if (fld.equals(pivotField)) {
                    continue;
                }
            }
            out = out.stream().filter(r -> val.equals(String.valueOf(r.get(fld)).trim())).toList();
            keys = rowKeyUnion(out);
        }
        return out;
    }

    private static List<Map<String, Object>> filterDateRange(List<Map<String, Object>> rows, Map<String, Object> cfg) {
        String from = str(cfg.get("date_from"));
        String to = str(cfg.get("date_to"));
        if (from.isBlank() && to.isBlank()) {
            return rows;
        }
        Set<String> keys = rowKeyUnion(rows);
        String rangeField = matchField(keys, str(cfg.get("x_field")));
        if (rangeField == null) {
            rangeField = firstExisting(keys, "date", "period", "time", "TIME_PERIOD");
        }
        if (rangeField == null) {
            return rows;
        }
        String finalRangeField = rangeField;
        return rows.stream()
                .filter(r -> {
                    Object xv = r.get(finalRangeField);
                    if (xv == null) {
                        return true;
                    }
                    String xs = String.valueOf(xv);
                    if (!from.isBlank() && xs.compareTo(from) < 0) {
                        return false;
                    }
                    return to.isBlank() || xs.compareTo(to) <= 0;
                })
                .toList();
    }

    private static List<Map<String, Object>> aggregateChartRows(
            List<Map<String, Object>> rows, String xField, String yField, String agg) {
        Map<String, List<Double>> buckets = new TreeMap<>(Comparator.naturalOrder());
        for (Map<String, Object> row : rows) {
            Object x = row.get(xField);
            Double y = UserDataParseService.parseNumber(row.get(yField));
            if (x == null || y == null) {
                continue;
            }
            buckets.computeIfAbsent(String.valueOf(x), k -> new ArrayList<>()).add(y);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Double>> entry : buckets.entrySet()) {
            List<Double> vals = entry.getValue();
            if (vals.isEmpty()) {
                continue;
            }
            double v = switch (agg) {
                case "avg" -> vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                case "max" -> vals.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                case "min" -> vals.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                case "count" -> vals.size();
                case "last" -> vals.getLast();
                default -> vals.stream().mapToDouble(Double::doubleValue).sum();
            };
            out.add(Map.of("x", entry.getKey(), "y", v));
        }
        return out;
    }

    private static String resolveViewMode(Map<String, Object> cfg, String widgetType) {
        String raw = str(cfg.get("view")).toLowerCase(Locale.ROOT);
        if (CHART_DEFAULT_TYPES.contains(widgetType != null ? widgetType : "")) {
            return "table".equals(raw) ? "table" : "chart";
        }
        if ("chart".equals(raw) || "table".equals(raw)) {
            return raw;
        }
        return widgetType != null && widgetType.contains("chart") ? "chart" : "table";
    }

    private static String resolveAgg(Map<String, Object> cfg, String sourceTypeHint) {
        String raw = str(cfg.get("agg")).isBlank() ? "sum" : str(cfg.get("agg")).toLowerCase(Locale.ROOT);
        if ("csu".equalsIgnoreCase(sourceTypeHint) && "sum".equals(raw)) {
            return "avg";
        }
        return raw;
    }

    private static String resolveXField(Map<String, Object> cfg, Set<String> keys, String seriesField) {
        String configured = str(cfg.get("x_field"));
        String x = configured.isBlank() ? null : matchField(keys, configured);
        if (x != null && seriesField != null && x.equalsIgnoreCase(seriesField)) {
            x = null;
        }
        if (x != null) {
            return x;
        }
        String guessed = firstExisting(keys, "date", "period", "time", "TIME_PERIOD", "x");
        return guessed != null ? guessed : keys.stream().findFirst().orElse("x");
    }

    private static String resolveYField(Map<String, Object> cfg, Set<String> keys) {
        String configured = str(cfg.get("y_field"));
        String y = configured.isBlank() ? null : matchField(keys, configured);
        if (y != null) {
            return y;
        }
        String guessed = firstExisting(keys, "value", "amount", "y", "OBS_VALUE", "Hodnota");
        return guessed != null ? guessed : keys.stream().skip(1).findFirst().orElse("y");
    }

    private static Map<String, Object> basePayload(String title, String datasetName, String view, Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", title);
        out.put("dataset", datasetName);
        out.put("view", view);
        out.put("chart_type", str(cfg.get("chart_type")).isBlank() ? "line" : str(cfg.get("chart_type")));
        if (!str(cfg.get("unit")).isBlank()) {
            out.put("unit", str(cfg.get("unit")));
        }
        if (!str(cfg.get("frequency")).isBlank()) {
            out.put("frequency", str(cfg.get("frequency")));
        }
        return out;
    }

    private static Set<String> rowKeyUnion(List<Map<String, Object>> rows) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> row : rows.stream().limit(200).toList()) {
            keys.addAll(row.keySet());
        }
        return keys;
    }

    private static String matchField(Set<String> keys, String preferred) {
        if (preferred == null || preferred.isBlank()) {
            return null;
        }
        if (keys.contains(preferred)) {
            return preferred;
        }
        for (String key : keys) {
            if (key.equalsIgnoreCase(preferred)) {
                return key;
            }
        }
        return preferred;
    }

    private static String firstExisting(Set<String> keys, String... candidates) {
        for (String candidate : candidates) {
            String matched = matchField(keys, candidate);
            if (matched != null && keys.contains(matched)) {
                return matched;
            }
        }
        return null;
    }

    private static int parseLimit(Object raw) {
        if (raw == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(raw).strip()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).strip() : "";
    }
}
