package cz.bankintel.service.homepage.resolver;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.service.myseries.SavedSeriesResolverService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SourceRecordsWidgetResolver {

    private final SavedSeriesResolverService savedSeriesResolverService;
    private final DatasetViewResolver datasetViewResolver;

    public Map<String, Object> resolveAradView(Map<String, Object> cfg, String userId) {
        String sourceId = str(cfg.get("source_id"));
        String indicatorId = str(cfg.get("indicator_id"));
        if (sourceId.isBlank() || indicatorId.isBlank()) {
            return Map.of("error", "Vyber zdroj a indikátor.");
        }
        String view = str(cfg.get("view")).isBlank() ? "table" : str(cfg.get("view")).toLowerCase(Locale.ROOT);
        int limit = parseLimit(cfg.get("limit"));
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", "source_indicator");
            payload.put("source_id", sourceId);
            payload.put("indicator_id", indicatorId);
            SavedSeriesResolverService.ResolvedPoints resolved = savedSeriesResolverService.resolvePoints(userId, payload);
            List<Map<String, Object>> points = resolved.points();
            if (points.isEmpty()) {
                return Map.of("error", "Žádná data pro vybraný indikátor.");
            }
            Map<String, Object> meta = resolved.meta() != null ? resolved.meta() : Map.of();
            List<Map<String, Object>> rows = points.stream()
                    .map(p -> Map.<String, Object>of(
                            "period", p.get("period"),
                            "value", p.get("value")))
                    .toList();
            Map<String, Object> common = new LinkedHashMap<>();
            common.put("title", meta.getOrDefault("title", indicatorId));
            common.put("unit", meta.getOrDefault("unit", ""));
            common.put("frequency", meta.getOrDefault("frequency", ""));
            common.put("source_id", sourceId);
            common.put("indicator_id", indicatorId);
            if ("chart".equals(view)) {
                List<Map<String, Object>> chartRows = rows.stream()
                        .skip(limit > 0 ? Math.max(0, rows.size() - limit) : 0)
                        .map(r -> Map.<String, Object>of("x", r.get("period"), "y", r.get("value")))
                        .toList();
                common.put("view", "chart");
                common.put("rows", chartRows);
                return common;
            }
            List<Map<String, Object>> tableRows = new ArrayList<>(rows);
            tableRows.sort(Comparator.comparing(r -> String.valueOf(r.get("period")), Comparator.reverseOrder()));
            if (limit > 0 && tableRows.size() > limit) {
                tableRows = tableRows.subList(0, limit);
            }
            common.put("view", "table");
            common.put("rows", tableRows);
            return common;
        } catch (Exception ex) {
            return Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Nelze načíst řadu.");
        }
    }

    public Map<String, Object> resolveDatasetView(Map<String, Object> cfg, String widgetType) {
        return datasetViewResolver.resolve(cfg, widgetType, null);
    }

    public Map<String, Object> resolveDatasetView(Map<String, Object> cfg, String widgetType, UserEntity user) {
        return datasetViewResolver.resolve(cfg, widgetType, user);
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
