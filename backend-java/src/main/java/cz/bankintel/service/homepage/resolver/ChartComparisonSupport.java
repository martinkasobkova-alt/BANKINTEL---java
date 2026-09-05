package cz.bankintel.service.homepage.resolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sdílené sestavení "Srovnat s řadou" výsledku - společné pro {@link
 * ExternalCatalogChartWidgetResolver} (katalogový primární graf) a {@link
 * UserUploadChartWidgetResolver} (primární graf z vlastních dat). Obě strany si samy řeší, JAK
 * se která konkrétní srovnávací položka natáhne (katalogový preview vs. znovupoužití vlastního
 * resolveru) - tahle třída jen skládá výsledné {@link ChartLine}y do společného {@code
 * multi_series}/{@code series}/{@code rows} tvaru, který frontend očekává.
 */
final class ChartComparisonSupport {

    private ChartComparisonSupport() {}

    record ChartLine(String label, String chartType, String yAxis, Map<String, Double> points) {}

    @SuppressWarnings("unchecked")
    static List<ChartLine> chartLines(Map<String, Object> rendered, String fallbackLabel, String fallbackAxis) {
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

    static Map<String, Double> points(List<Map<String, Object>> rows, String valueKey) {
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

    static String seriesLabel(Map<String, Object> entry, String fallback) {
        return defaultText(firstPresent(entry, "name", "title", "label"), fallback);
    }

    static String firstPresent(Map<?, ?> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            String text = str(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    static String defaultText(String value, String fallback) {
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

    static Double number(Object value) {
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

    /**
     * Poskládá {@code multi_series}/{@code series}/{@code rows} z hotových {@link ChartLine}
     * (primární + úspěšně přidané srovnávací), do {@code out}. {@code lineIdentities} musí mít
     * stejnou délku a pořadí jako {@code lines}.
     */
    static void applyMergedSeries(
            Map<String, Object> out, List<ChartLine> lines, List<Map<String, Object>> lineIdentities) {
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

    private static String str(Object value) {
        return value != null ? String.valueOf(value).strip() : "";
    }
}
