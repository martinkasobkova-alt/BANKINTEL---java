package cz.bankintel.service.me;

import cz.bankintel.domain.entity.DashboardWidgetEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.service.homepage.WidgetRenderService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PersonalWidgetRenderService {

    private final WidgetRenderService widgetRenderService;

    public Map<String, Object> renderWithSnapshot(
            DashboardWidgetEntity widget, UserEntity owner, boolean forceRefresh) {
        Map<String, Object> base = baseWidgetMap(widget);
        if (!forceRefresh && widget.getDataSnapshot() != null) {
            return buildPayload(base, widget.getDataSnapshot(), snapshotMeta(widget));
        }
        Map<String, Object> data =
                widgetRenderService.resolve(widget.getWidgetType(), widget.getConfig(), owner);
        return buildPayload(base, data, liveMeta());
    }

    public Map<String, Object> renderTransient(
            DashboardWidgetEntity widget, UserEntity owner, Map<String, Object> configOverride) {
        Map<String, Object> base = baseWidgetMap(widget);
        base.put("config", configOverride != null ? configOverride : Map.of());
        Map<String, Object> data = widgetRenderService.resolve(widget.getWidgetType(), configOverride, owner);
        Map<String, Object> rendered = buildPayload(base, data, liveMeta());
        rendered.put("viewer_compare_preview", true);
        return rendered;
    }

    private static Map<String, Object> baseWidgetMap(DashboardWidgetEntity widget) {
        Map<String, Object> cfg = widget.getConfig() != null ? widget.getConfig() : Map.of();
        String engineType = widget.getWidgetType();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", widget.getId());
        map.put("type", engineType);
        map.put("title", widget.getTitle() != null ? widget.getTitle() : "");
        map.put("width", widget.getWidth() != null ? widget.getWidth() : "full");
        map.put("rowSpan", widget.getRowSpan());
        map.put("config", cfg);
        return map;
    }

    private static Map<String, Object> buildPayload(
            Map<String, Object> base, Object data, Map<String, Object> meta) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put("data", meta.getOrDefault("data", data));
        out.put("from_snapshot", Boolean.TRUE.equals(meta.get("from_snapshot")));
        out.put("last_fetched_at", meta.get("last_fetched_at"));
        out.put("is_stale", Boolean.TRUE.equals(meta.get("is_stale")));
        out.put("snapshot_status", meta.get("snapshot_status"));
        if (meta.containsKey("refresh_error")) {
            out.put("refresh_error", meta.get("refresh_error"));
        }
        return out;
    }

    private static Map<String, Object> snapshotMeta(DashboardWidgetEntity widget) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("data", widget.getDataSnapshot());
        meta.put("from_snapshot", true);
        meta.put("last_fetched_at", instantToString(widget.getLastFetchedAt()));
        meta.put("is_stale", false);
        meta.put("snapshot_status", widget.getSnapshotStatus() != null ? widget.getSnapshotStatus() : "cached");
        return meta;
    }

    private static Map<String, Object> liveMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("from_snapshot", false);
        meta.put("is_stale", false);
        return meta;
    }

    private static String instantToString(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
