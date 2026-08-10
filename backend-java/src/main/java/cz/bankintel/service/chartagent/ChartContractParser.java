package cz.bankintel.service.chartagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChartContractParser {

    private ChartContractParser() {}

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> seriesFromContract(Map<String, Object> contract) {
        if (contract == null) {
            return List.of();
        }
        Object dataObj = contract.get("data");
        if (!(dataObj instanceof List<?> data)) {
            return List.of();
        }

        Map<String, Map<String, Object>> metaById = new LinkedHashMap<>();
        Object seriesObj = contract.get("series");
        if (seriesObj instanceof List<?> seriesList) {
            for (int idx = 0; idx < seriesList.size(); idx++) {
                Object item = seriesList.get(idx);
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> s = (Map<String, Object>) raw;
                String sid = str(s.get("id"));
                if (sid.isBlank()) {
                    sid = str(s.get("key"));
                }
                if (sid.isBlank()) {
                    sid = "series_" + idx;
                }
                metaById.put(sid, s);
            }
        }

        Map<String, List<Map<String, Object>>> pointsBySeries = new LinkedHashMap<>();
        for (Object ptObj : data) {
            if (!(ptObj instanceof Map<?, ?> rawPt)) {
                continue;
            }
            Map<String, Object> pt = (Map<String, Object>) rawPt;
            String sid = str(pt.get("series_id"));
            if (sid.isBlank()) {
                sid = "main";
            }
            String period = str(pt.get("period"));
            if (period.isBlank()) {
                period = str(pt.get("period_label"));
            }
            Double value = num(pt.get("value_raw"));
            if (period.isBlank() || value == null) {
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("period", period);
            point.put("period_label", str(pt.get("period_label")).isBlank() ? period : str(pt.get("period_label")));
            point.put("value", value);
            point.put("unit", str(pt.get("unit")));
            point.put("frequency", str(pt.get("frequency")));
            point.put("source", str(pt.get("source")));
            point.put("dataset", str(pt.get("dataset")));
            pointsBySeries.computeIfAbsent(sid, k -> new ArrayList<>()).add(point);
        }

        Map<String, Object> metadata = map(contract.get("metadata"));
        List<Map<String, Object>> out = new ArrayList<>();
        int idx = 0;
        for (Map.Entry<String, List<Map<String, Object>>> entry : pointsBySeries.entrySet()) {
            String sid = entry.getKey();
            List<Map<String, Object>> pts = new ArrayList<>(entry.getValue());
            pts.sort((a, b) -> ChartPeriodKeys.compare(str(a.get("period")), str(b.get("period"))));
            Map<String, Object> meta = metaById.getOrDefault(sid, Map.of());
            String label = str(meta.get("label"));
            if (label.isBlank()) {
                label = str(meta.get("name"));
            }
            if (label.isBlank() && !pts.isEmpty()) {
                label = str(pts.getFirst().get("series_label"));
            }
            if (label.isBlank()) {
                label = sid;
            }
            String freq = str(meta.get("frequency"));
            if (freq.isBlank() && !pts.isEmpty()) {
                freq = str(pts.getFirst().get("frequency"));
            }
            if (freq.isBlank()) {
                freq = str(metadata.get("frequency"));
            }
            Map<String, Object> series = new LinkedHashMap<>();
            series.put("id", sid);
            series.put("key", str(meta.get("key")).isBlank() ? sid : str(meta.get("key")));
            series.put("label", label);
            series.put("unit", str(meta.get("unit")).isBlank() && !pts.isEmpty() ? str(pts.getFirst().get("unit")) : str(meta.get("unit")));
            series.put("frequency", freq);
            series.put("geo", str(meta.get("geo")).isBlank() ? str(meta.get("country")) : str(meta.get("geo")));
            series.put("source", str(meta.get("source")));
            series.put("points", pts);
            series.put("order", idx++);
            out.add(series);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return Map.of();
    }

    public static Double num(Object value) {
        if (value == null || value instanceof Boolean) {
            return null;
        }
        try {
            double out = Double.parseDouble(String.valueOf(value));
            return Double.isFinite(out) ? out : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
