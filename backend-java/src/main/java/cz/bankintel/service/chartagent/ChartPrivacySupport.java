package cz.bankintel.service.chartagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class ChartPrivacySupport {

    private static final Set<String> PRIVATE_PRIVACY = Set.of("private", "confidential", "strict_private");
    private static final Set<String> PRIVATE_SOURCE_TYPES =
            Set.of("user_upload", "uploaded_data_chart", "user_upload_chart", "file_upload", "private", "company");

    private ChartPrivacySupport() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> chartPrivacyAudit(Map<String, Object> contract) {
        Set<String> privateIds = privateSeriesIds(contract);
        int seriesCount = 0;
        Object seriesObj = contract != null ? contract.get("series") : null;
        if (seriesObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?>) {
                    seriesCount++;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", privateIds.isEmpty() ? "public" : "strict_private");
        out.put("contains_private_series", !privateIds.isEmpty());
        out.put("private_series_count", privateIds.size());
        out.put("public_series_count", Math.max(seriesCount - privateIds.size(), 0));
        out.put("private_series_ids", new ArrayList<>(new TreeSet<>(privateIds)));
        out.put("raw_values_sent_to_external_ai", privateIds.isEmpty() ? null : false);
        out.put(
                "planner_contract",
                privateIds.isEmpty() ? "full_chart_contract_allowed" : "sanitized_metadata_only");
        out.put("local_backend_computation", true);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> sanitizeChartContractForAi(Map<String, Object> contract) {
        Set<String> privateIds = privateSeriesIds(contract);
        if (privateIds.isEmpty()) {
            return deepCopy(contract);
        }
        Map<String, List<String>> periodsBySeries = new LinkedHashMap<>();
        Object dataObj = contract.get("data");
        if (dataObj instanceof List<?> data) {
            for (Object ptObj : data) {
                if (!(ptObj instanceof Map<?, ?> rawPt)) {
                    continue;
                }
                Map<String, Object> pt = (Map<String, Object>) rawPt;
                String sid = ChartContractParser.str(pt.get("series_id"));
                if (sid.isBlank()) {
                    sid = "main";
                }
                String period = ChartContractParser.str(pt.get("period"));
                if (period.isBlank()) {
                    period = ChartContractParser.str(pt.get("period_label"));
                }
                if (!sid.isBlank() && !period.isBlank()) {
                    periodsBySeries.computeIfAbsent(sid, k -> new ArrayList<>()).add(period);
                }
            }
        }

        List<Map<String, Object>> sanitizedSeries = new ArrayList<>();
        Object seriesObj = contract.get("series");
        if (seriesObj instanceof List<?> seriesList) {
            int privateCounter = 0;
            for (int idx = 0; idx < seriesList.size(); idx++) {
                Object item = seriesList.get(idx);
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> series = (Map<String, Object>) raw;
                String sid = ChartContractParser.str(series.get("id"));
                if (sid.isBlank()) {
                    sid = ChartContractParser.str(series.get("key"));
                }
                if (sid.isBlank()) {
                    sid = "series_" + idx;
                }
                boolean isPrivate = privateIds.contains(sid) || isPrivateChartObject(series);
                List<String> periods = new ArrayList<>(new LinkedHashSet<>(periodsBySeries.getOrDefault(sid, List.of())));
                periods.sort(ChartPeriodKeys::compare);
                Map<String, Object> sanitized = new LinkedHashMap<>();
                sanitized.put("id", sid);
                sanitized.put("key", ChartContractParser.str(series.get("key")).isBlank() ? sid : ChartContractParser.str(series.get("key")));
                if (isPrivate) {
                    privateCounter++;
                    sanitized.put("label", "Vlastní řada " + privateCounter);
                } else {
                    sanitized.put(
                            "label",
                            ChartContractParser.str(series.get("label")).isBlank()
                                    ? ChartContractParser.str(series.get("name"))
                                    : ChartContractParser.str(series.get("label")));
                }
                sanitized.put("frequency", ChartContractParser.str(series.get("frequency")));
                sanitized.put("unit", isPrivate && !ChartContractParser.str(series.get("unit")).isBlank() ? "masked" : ChartContractParser.str(series.get("unit")));
                sanitized.put("privacy", isPrivate ? "private" : "public");
                sanitized.put("points_count", periods.size());
                sanitized.put(
                        "period_range",
                        periods.isEmpty() ? List.of() : List.of(periods.getFirst(), periods.getLast()));
                sanitizedSeries.add(sanitized);
            }
        }

        Map<String, Object> meta = map(contract.get("metadata"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chart_id", ChartContractParser.str(contract.get("chart_id")));
        out.put("chart_type", ChartContractParser.str(contract.get("chart_type")));
        out.put("title", "Graf s privátními daty");
        out.put("series", sanitizedSeries);
        out.put("data", List.of());
        Map<String, Object> sanitizedMeta = new LinkedHashMap<>();
        sanitizedMeta.put("data_mode", meta.get("data_mode"));
        sanitizedMeta.put("frequency", meta.get("frequency"));
        sanitizedMeta.put("privacy_mode", "strict_private");
        sanitizedMeta.put("raw_values_omitted", true);
        out.put("metadata", sanitizedMeta);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Set<String> privateSeriesIds(Map<String, Object> contract) {
        Set<String> ids = new LinkedHashSet<>();
        if (contract == null) {
            return ids;
        }
        Object seriesObj = contract.get("series");
        if (seriesObj instanceof List<?> seriesList) {
            for (int idx = 0; idx < seriesList.size(); idx++) {
                Object item = seriesList.get(idx);
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> series = (Map<String, Object>) raw;
                String sid = ChartContractParser.str(series.get("id"));
                if (sid.isBlank()) {
                    sid = ChartContractParser.str(series.get("key"));
                }
                if (sid.isBlank()) {
                    sid = "series_" + idx;
                }
                if (isPrivateChartObject(series)) {
                    ids.add(sid);
                }
            }
        }
        Object dataObj = contract.get("data");
        if (dataObj instanceof List<?> data) {
            for (Object ptObj : data) {
                if (!(ptObj instanceof Map<?, ?> rawPt)) {
                    continue;
                }
                Map<String, Object> pt = (Map<String, Object>) rawPt;
                if (!isPrivateChartObject(pt)) {
                    continue;
                }
                String sid = ChartContractParser.str(pt.get("series_id"));
                if (sid.isBlank()) {
                    sid = "main";
                }
                ids.add(sid);
            }
        }
        Map<String, Object> meta = map(contract.get("metadata"));
        if (isPrivateChartObject(contract) || isPrivateChartObject(meta)) {
            if (ids.isEmpty() && seriesObj instanceof List<?> seriesList) {
                for (int idx = 0; idx < seriesList.size(); idx++) {
                    Object item = seriesList.get(idx);
                    if (item instanceof Map<?, ?> raw) {
                        Map<String, Object> series = (Map<String, Object>) raw;
                        String sid = ChartContractParser.str(series.get("id"));
                        if (sid.isBlank()) {
                            sid = ChartContractParser.str(series.get("key"));
                        }
                        if (sid.isBlank()) {
                            sid = "series_" + idx;
                        }
                        ids.add(sid);
                    }
                }
            }
            if (ids.isEmpty()) {
                ids.add("main");
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static boolean isPrivateChartObject(Object obj) {
        if (!(obj instanceof Map<?, ?> raw)) {
            return false;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        if (PRIVATE_PRIVACY.contains(privacyValue(map).toLowerCase())) {
            return true;
        }
        Map<String, Object> meta = map(map.get("metadata"));
        String sourceType = ChartContractParser.str(map.get("source_type"));
        if (sourceType.isBlank()) {
            sourceType = ChartContractParser.str(map.get("kind"));
        }
        if (sourceType.isBlank()) {
            sourceType = ChartContractParser.str(map.get("engine_type"));
        }
        if (sourceType.isBlank()) {
            sourceType = ChartContractParser.str(map.get("type"));
        }
        if (sourceType.isBlank()) {
            sourceType = ChartContractParser.str(meta.get("source_type"));
        }
        if (sourceType.isBlank()) {
            sourceType = ChartContractParser.str(meta.get("kind"));
        }
        if (PRIVATE_SOURCE_TYPES.contains(sourceType.toLowerCase())) {
            return true;
        }
        return !ChartContractParser.str(map.get("user_upload_id")).isBlank()
                || !ChartContractParser.str(map.get("upload_id")).isBlank()
                || !ChartContractParser.str(map.get("file_upload_id")).isBlank()
                || !ChartContractParser.str(meta.get("user_upload_id")).isBlank();
    }

    @SuppressWarnings("unchecked")
    private static String privacyValue(Map<String, Object> obj) {
        Map<String, Object> meta = map(obj.get("metadata"));
        String value = ChartContractParser.str(obj.get("privacy"));
        if (value.isBlank()) {
            value = ChartContractParser.str(obj.get("privacy_level"));
        }
        if (value.isBlank()) {
            value = ChartContractParser.str(obj.get("privacy_mode"));
        }
        if (value.isBlank()) {
            value = ChartContractParser.str(meta.get("privacy"));
        }
        if (value.isBlank()) {
            value = ChartContractParser.str(meta.get("privacy_level"));
        }
        if (value.isBlank()) {
            value = ChartContractParser.str(meta.get("privacy_mode"));
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> contract) {
        return new LinkedHashMap<>((Map<String, Object>) contract);
    }
}
