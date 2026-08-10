package cz.bankintel.service.homepage;

import cz.bankintel.domain.entity.HomepageWidgetEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.HomepageWidgetRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class HomepageWidgetOpsService {

    private static final String CONFIG_ID = "main";
    private static final Set<String> ALLOWED_WIDTHS =
            Set.of("full", "three-quarters", "two-thirds", "half", "third", "quarter", "sixth", "eighth");

    private final HomepageWidgetRepository widgetRepository;
    private final HomepageService homepageService;
    private final WidgetRenderService widgetRenderService;
    private final HomepageAiCommentaryService homepageAiCommentaryService;

    @Transactional(readOnly = true)
    public Map<String, Object> renderWidget(Map<String, Object> payload, UserEntity user) {
        Map<String, Object> rendered = widgetRenderService.renderTransientWidget(payload, user);
        boolean skipAi = payload == null || !Boolean.FALSE.equals(payload.get("skip_ai"));
        if (!skipAi && !hasDataError(rendered)) {
            homepageAiCommentaryService.attachBatch(List.of(rendered), 25.0);
        }
        return rendered;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> previewWidget(Map<String, Object> payload, UserEntity user) {
        Map<String, Object> rendered = widgetRenderService.renderTransientWidget(payload, user);
        rendered.put("id", rendered.getOrDefault("id", "preview"));
        if (Boolean.TRUE.equals(payload.get("with_ai_commentary")) && !hasDataError(rendered)) {
            homepageAiCommentaryService.attachBatch(List.of(rendered), null);
        }
        return rendered;
    }

    private static boolean hasDataError(Map<String, Object> rendered) {
        Object data = rendered.get("data");
        return data instanceof Map<?, ?> map && map.get("error") != null;
    }

    @Transactional
    public Map<String, Object> reorderWidgets(Map<String, Object> payload) {
        Object rawIds = payload.get("widget_ids");
        if (!(rawIds instanceof List<?> ids)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "widget_ids musí být pole řetězců.");
        }
        List<HomepageWidgetEntity> widgets = widgetRepository.findByConfigIdOrderBySortOrderAsc(CONFIG_ID);
        Map<String, HomepageWidgetEntity> byId = new LinkedHashMap<>();
        for (HomepageWidgetEntity widget : widgets) {
            byId.put(widget.getId(), widget);
        }
        List<HomepageWidgetEntity> reordered = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (Object raw : ids) {
            String id = String.valueOf(raw).strip();
            HomepageWidgetEntity widget = byId.get(id);
            if (widget != null && seen.add(id)) {
                reordered.add(widget);
            }
        }
        for (HomepageWidgetEntity widget : widgets) {
            if (!seen.contains(widget.getId())) {
                reordered.add(widget);
            }
        }
        mergeLayout(reordered, payload.get("widget_layout"));
        for (int i = 0; i < reordered.size(); i++) {
            HomepageWidgetEntity widget = reordered.get(i);
            widget.setSortOrder(i);
            widgetRepository.save(widget);
        }
        return homepageService.getConfig();
    }

    @Transactional
    public Map<String, Object> patchWidget(String widgetId, Map<String, Object> payload) {
        HomepageWidgetEntity widget = widgetRepository
                .findById(widgetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Widget neexistuje."));
        if (payload.containsKey("width")) {
            String width = String.valueOf(payload.get("width")).strip();
            if (!ALLOWED_WIDTHS.contains(width)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatná šířka widgetu.");
            }
            widget.setWidth(width);
        }
        if (payload.containsKey("rowSpan")) {
            Object rs = payload.get("rowSpan");
            if (rs == null) {
                widget.setRowSpan(null);
            } else {
                int val = Integer.parseInt(String.valueOf(rs));
                if (val < 1 || val > 10) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Výška musí být 1–10.");
                }
                widget.setRowSpan(val);
            }
        }
        if (payload.containsKey("title")) {
            widget.setTitle(String.valueOf(payload.get("title")).substring(0, Math.min(200, String.valueOf(payload.get("title")).length())));
        }
        if (payload.containsKey("config")) {
            if (!(payload.get("config") instanceof Map<?, ?> patchCfg)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "config musí být objekt.");
            }
            Map<String, Object> merged = new LinkedHashMap<>(widget.getConfig() != null ? widget.getConfig() : Map.of());
            merged.putAll(castMap(patchCfg));
            widget.setConfig(merged);
        }
        widgetRepository.save(widget);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", widget.getId());
        out.put("width", widget.getWidth());
        out.put("rowSpan", widget.getRowSpan());
        out.put("title", widget.getTitle());
        out.put("config", widget.getConfig());
        return out;
    }

    @Transactional
    public Map<String, Object> deleteWidget(String widgetId) {
        if (!widgetRepository.existsById(widgetId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Widget neexistuje.");
        }
        widgetRepository.deleteById(widgetId);
        return Map.of("ok", true, "deleted_id", widgetId);
    }

    private static void mergeLayout(List<HomepageWidgetEntity> widgets, Object layoutUpdates) {
        if (!(layoutUpdates instanceof Map<?, ?> layout)) {
            return;
        }
        Map<String, HomepageWidgetEntity> byId = new LinkedHashMap<>();
        for (HomepageWidgetEntity widget : widgets) {
            byId.put(widget.getId(), widget);
        }
        for (Map.Entry<?, ?> entry : layout.entrySet()) {
            HomepageWidgetEntity widget = byId.get(String.valueOf(entry.getKey()));
            if (widget == null || !(entry.getValue() instanceof Map<?, ?> coords)) {
                continue;
            }
            Map<String, Object> cfg = new LinkedHashMap<>(widget.getConfig() != null ? widget.getConfig() : Map.of());
            for (String key : List.of("grid_column_start", "grid_column_end", "grid_row_start", "grid_row_end")) {
                if (coords.containsKey(key)) {
                    cfg.put(key, coords.get(key));
                }
            }
            widget.setConfig(cfg);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
