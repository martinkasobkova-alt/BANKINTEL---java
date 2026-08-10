package cz.bankintel.service.homepage.resolver;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.search.CatalogPreviewService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Widget {@code external_catalog_chart} — live data z katalogu přes preview pipeline. */
@Component
@RequiredArgsConstructor
public class ExternalCatalogChartWidgetResolver {

    private final CatalogPreviewService catalogPreviewService;
    private final DatasetViewResolver datasetViewResolver;

    public Map<String, Object> resolve(Map<String, Object> cfg, UserEntity user) {
        String catalog = str(cfg.get("catalog")).isBlank() ? str(cfg.get("source_type")) : str(cfg.get("catalog"));
        String setId = str(cfg.get("set_id"));
        if (catalog.isBlank() || setId.isBlank()) {
            return Map.of("view", "chart", "error", "Chybí katalog nebo set_id.");
        }
        Map<String, Object> previewBody = new LinkedHashMap<>(cfg);
        previewBody.put("source_type", catalog);
        previewBody.putIfAbsent("catalog", catalog);
        previewBody.putIfAbsent("set_id", setId);
        Map<String, Object> preview = catalogPreviewService.preview(previewBody);
        Object err = preview.get("error");
        if (err != null) {
            return Map.of("view", "chart", "error", String.valueOf(err));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = preview.get("rows") instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list
                : List.of();
        if (rows.isEmpty()) {
            return Map.of("view", "chart", "error", "Pro tuto řadu nebyla nalezena žádná data k zobrazení.");
        }
        Map<String, Object> datasetCfg = new LinkedHashMap<>(cfg);
        datasetCfg.put("view", str(cfg.get("view")).isBlank() ? "chart" : str(cfg.get("view")));
        Map<String, Object> out = primarySnapshot(cfg);
        if (out == null) {
            out = datasetViewResolver.resolveFromRows(rows, datasetCfg, "external_catalog_chart", user);
        }
        out.putIfAbsent("title", cfg.get("title"));
        out.put("catalog", catalog);
        out.put("set_id", setId);
        mergeCatalogComparisons(out, cfg, user, catalog, setId);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> primarySnapshot(Map<String, Object> cfg) {
        if (!(cfg.get("chart_primary_snapshot") instanceof Map<?, ?> rawSnapshot)) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>((Map<String, Object>) rawSnapshot);
        if (!(snapshot.get("rows") instanceof List<?> rows) || rows.isEmpty() || rows.size() > 5000) {
            return null;
        }
        if (chartLines(snapshot, str(snapshot.get("title")), "left").isEmpty()) {
            return null;
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private void mergeCatalogComparisons(
            Map<String, Object> out,
            Map<String, Object> cfg,
            UserEntity user,
            String primaryCatalog,
            String primarySetId) {
        Object rawCompare = cfg.get("chart_compare_with");
        if (!(rawCompare instanceof List<?> requested) || requested.isEmpty() || out.get("error") != null) {
            return;
        }

        List<ChartLine> lines = chartLines(out, str(out.get("title")), "left");
        if (lines.isEmpty()) {
            out.put("compare_requested_count", requested.size());
            out.put("compare_added_count", 0);
            return;
        }

        // Katalogova identita kazde radky v poradi, v jakem se pridavaji do `lines` - primarni
        // radka(y) + kazda uspesne pridana srovnavaci radka. Bez tohohle frontend (AI chat nad
        // grafem) nema jak poznat, ktery set_id/catalog patri k ktere pridane rade po jmenu.
        List<Map<String, Object>> lineIdentities = new ArrayList<>();
        Map<String, Object> primaryIdentity = new LinkedHashMap<>();
        primaryIdentity.put("source_type", primaryCatalog);
        primaryIdentity.put("set_id", primarySetId);
        for (int i = 0; i < lines.size(); i++) {
            lineIdentities.add(primaryIdentity);
        }

        List<String> errors = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(primaryCatalog.toLowerCase() + "|" + primarySetId);
        int added = 0;
        for (Object item : requested) {
            if (!(item instanceof Map<?, ?> rawEntry) || added >= 8) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) rawEntry;
            String catalog = firstPresent(entry, "catalog", "source_type", "source");
            String setId = firstPresent(entry, "set_id", "series_id", "code");
            String identity = catalog.toLowerCase() + "|" + setId;
            if (catalog.isBlank() || setId.isBlank() || !seen.add(identity)) {
                continue;
            }

            Map<String, Object> extraBody = new LinkedHashMap<>(entry);
            extraBody.put("source_type", catalog);
            extraBody.put("catalog", catalog);
            extraBody.put("set_id", setId);
            extraBody.remove("chart_compare_with");
            Map<String, Object> extraPreview = catalogPreviewService.preview(extraBody);
            if (extraPreview.get("error") != null) {
                errors.add(seriesLabel(entry, setId) + ": " + extraPreview.get("error"));
                continue;
            }
            List<Map<String, Object>> extraRows = extraPreview.get("rows") instanceof List<?> list
                    ? (List<Map<String, Object>>) (List<?>) list
                    : List.of();
            if (extraRows.isEmpty()) {
                errors.add(seriesLabel(entry, setId) + ": no data");
                continue;
            }

            Map<String, Object> extraCfg = new LinkedHashMap<>(entry);
            extraCfg.put("source_type", catalog);
            extraCfg.put("catalog", catalog);
            extraCfg.put("set_id", setId);
            extraCfg.put("title", seriesLabel(entry, setId));
            extraCfg.put("view", "chart");
            extraCfg.put("chart_series_mode", "single");
            extraCfg.remove("chart_compare_with");
            Map<String, Object> rendered = datasetViewResolver.resolveFromRows(
                    extraRows, extraCfg, "external_catalog_chart", user);
            if (rendered.get("error") != null) {
                errors.add(seriesLabel(entry, setId) + ": " + rendered.get("error"));
                continue;
            }
            List<ChartLine> extraLines = chartLines(rendered, seriesLabel(entry, setId), str(entry.get("y_axis")));
            if (extraLines.isEmpty() || extraLines.getFirst().points().isEmpty()) {
                errors.add(seriesLabel(entry, setId) + ": no chart points");
                continue;
            }
            lines.add(extraLines.getFirst());
            Map<String, Object> lineIdentity = new LinkedHashMap<>();
            lineIdentity.put("source_type", catalog);
            lineIdentity.put("set_id", setId);
            String indicator = firstPresent(entry, "selected_indicator", "indicator_id", "indicator");
            if (!indicator.isBlank()) {
                lineIdentity.put("selected_indicator", indicator);
            }
            lineIdentities.add(lineIdentity);
            added++;
        }

        out.put("compare_requested_count", requested.size());
        out.put("compare_added_count", added);
        if (!errors.isEmpty()) {
            out.put("compare_errors", errors);
        }
        if (added == 0) {
            return;
        }

        Map<String, Map<String, Object>> rowsByPeriod = new LinkedHashMap<>();
        List<Map<String, Object>> series = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            ChartLine line = lines.get(index);
            String key = "s" + index;
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("key", key);
            meta.put("name", line.label());
            meta.put("label", line.label());
            meta.put("chart_type", line.chartType());
            meta.put("y_axis", line.yAxis());
            if (index < lineIdentities.size()) {
                meta.putAll(lineIdentities.get(index));
            }
            series.add(meta);
            for (Map.Entry<String, Double> point : line.points().entrySet()) {
                rowsByPeriod
                        .computeIfAbsent(point.getKey(), period -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("period", period);
                            return row;
                        })
                        .put(key, point.getValue());
            }
        }
        List<Map<String, Object>> mergedRows = new ArrayList<>(rowsByPeriod.values());
        mergedRows.sort(Comparator.comparing(row -> str(row.get("period"))));
        out.put("multi_series", true);
        out.put("series", series);
        out.put("rows", mergedRows);
    }

    @SuppressWarnings("unchecked")
    private static List<ChartLine> chartLines(Map<String, Object> rendered, String fallbackLabel, String fallbackAxis) {
        List<ChartLine> lines = new ArrayList<>();
        Object rawSeries = rendered.get("series");
        if (Boolean.TRUE.equals(rendered.get("multi_series")) && rawSeries instanceof List<?> seriesList) {
            List<Map<String, Object>> wideRows = rendered.get("rows") instanceof List<?> list
                    ? (List<Map<String, Object>>) (List<?>) list
                    : List.of();
            for (Object item : seriesList) {
                if (!(item instanceof Map<?, ?> rawMeta)) {
                    continue;
                }
                Map<String, Object> meta = (Map<String, Object>) rawMeta;
                String key = str(meta.get("key"));
                List<Map<String, Object>> rows = meta.get("rows") instanceof List<?> list
                        ? (List<Map<String, Object>>) (List<?>) list
                        : wideRows;
                Map<String, Double> points = points(rows, key);
                if (!points.isEmpty()) {
                    lines.add(new ChartLine(
                            defaultText(firstPresent(meta, "label", "name", "title"), fallbackLabel),
                            defaultText(firstPresent(meta, "chart_type", "type"), "line"),
                            "right".equalsIgnoreCase(str(meta.get("y_axis"))) ? "right" : "left",
                            points));
                }
            }
            if (!lines.isEmpty()) {
                return lines;
            }
        }
        List<Map<String, Object>> rows = rendered.get("rows") instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list
                : List.of();
        Map<String, Double> points = points(rows, "");
        if (!points.isEmpty()) {
            lines.add(new ChartLine(
                    fallbackLabel.isBlank() ? "Series" : fallbackLabel,
                    str(rendered.get("chart_type")).isBlank() ? "line" : str(rendered.get("chart_type")),
                    "right".equalsIgnoreCase(fallbackAxis) ? "right" : "left",
                    points));
        }
        return lines;
    }

    private static Map<String, Double> points(List<Map<String, Object>> rows, String valueKey) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String period = firstPresent(row, "period", "x", "date", "time", "TIME_PERIOD");
            Object rawValue = !valueKey.isBlank() && row.containsKey(valueKey)
                    ? row.get(valueKey)
                    : firstValue(row, "y", "value", "amount", "OBS_VALUE");
            Double value = number(rawValue);
            if (!period.isBlank() && value != null) {
                out.put(period, value);
            }
        }
        return out;
    }

    private static String seriesLabel(Map<String, Object> entry, String fallback) {
        return defaultText(firstPresent(entry, "name", "title", "label"), fallback);
    }

    private static String firstPresent(Map<?, ?> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            String text = str(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Object firstValue(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key) && values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    private static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(value).trim().replace(" ", "").replace(',', '.'));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record ChartLine(String label, String chartType, String yAxis, Map<String, Double> points) {}

    private static String str(Object value) {
        return value != null ? String.valueOf(value).strip() : "";
    }
}
