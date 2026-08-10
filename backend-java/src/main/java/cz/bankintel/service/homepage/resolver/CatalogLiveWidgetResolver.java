package cz.bankintel.service.homepage.resolver;

import cz.bankintel.search.CatalogPreviewOrchestrator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CatalogLiveWidgetResolver {

    private static final Set<String> DATASET_VIEWS = Set.of(
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

    private final CatalogPreviewOrchestrator previewOrchestrator;

    public boolean supports(String widgetType) {
        return widgetType != null && DATASET_VIEWS.contains(widgetType);
    }

    public Map<String, Object> tryLivePreview(Map<String, Object> cfg, String widgetType) {
        String sourceType = inferSourceType(cfg, widgetType);
        String setId = firstNonBlank(str(cfg.get("set_id")), str(cfg.get("dataset_id")), str(cfg.get("indicator_id")));
        if (sourceType.isBlank() || setId.isBlank()) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>(cfg);
        payload.put("source_type", sourceType);
        payload.put("set_id", setId);
        Map<String, Object> preview = previewOrchestrator.preview(payload);
        if (preview.containsKey("error") || "unsupported".equals(preview.get("preview_state"))) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) preview.getOrDefault("rows", List.of());
        if (rows.isEmpty()) {
            return Map.of();
        }
        String view = str(cfg.get("view")).isBlank() ? "chart" : str(cfg.get("view")).toLowerCase(Locale.ROOT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", preview.getOrDefault("title", setId));
        out.put("view", view);
        out.put("live_preview", true);
        if ("chart".equals(view)) {
            out.put(
                    "rows",
                    rows.stream()
                            .limit(500)
                            .map(this::toChartRow)
                            .filter(r -> !r.isEmpty())
                            .toList());
        } else {
            out.put("rows", rows.stream().limit(200).toList());
        }
        return out;
    }

    private Map<String, Object> toChartRow(Map<String, Object> row) {
        Object period = row.get("period");
        if (period == null) {
            period = row.get("date");
        }
        Object value = row.get("value");
        if (value == null) {
            value = row.get("obs_value");
        }
        if (period == null || value == null) {
            return Map.of();
        }
        return Map.of("x", String.valueOf(period), "y", value);
    }

    private static String inferSourceType(Map<String, Object> cfg, String widgetType) {
        String fromCfg = str(cfg.get("source_type"));
        if (!fromCfg.isBlank()) {
            return fromCfg.toLowerCase(Locale.ROOT);
        }
        if (widgetType == null) {
            return "";
        }
        if (widgetType.endsWith("_view")) {
            return widgetType.substring(0, widgetType.length() - 5).toLowerCase(Locale.ROOT);
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

    private static String str(Object value) {
        return value != null ? String.valueOf(value).strip() : "";
    }
}
