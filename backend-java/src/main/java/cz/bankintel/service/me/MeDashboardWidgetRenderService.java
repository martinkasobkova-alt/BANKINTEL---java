package cz.bankintel.service.me;

import cz.bankintel.domain.entity.DashboardWidgetEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.DashboardWidgetRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.homepage.WidgetRenderService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MeDashboardWidgetRenderService {

    private final DashboardWidgetRepository widgetRepository;
    private final WidgetRenderService widgetRenderService;
    private final FeatureAccessService featureAccessService;

    @Transactional(readOnly = true)
    public Map<String, Object> renderWidget(UserEntity user, String widgetId, boolean forceRefresh) {
        requirePersonalDashboard(user);
        DashboardWidgetEntity widget = widgetRepository
                .findByIdAndUserId(widgetId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Widget nenalezen"));

        if (!forceRefresh && widget.getDataSnapshot() != null && !widget.getDataSnapshot().isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", widget.getId());
            out.put("type", widget.getWidgetType());
            out.put("title", widget.getTitle());
            out.put("width", widget.getWidth());
            out.put("rowSpan", widget.getRowSpan());
            out.put("config", widget.getConfig());
            out.put("data", widget.getDataSnapshot());
            out.put("render_meta", Map.of("source", "snapshot"));
            return out;
        }

        Map<String, Object> rendered = widgetRenderService.buildRenderedWidget(
                widget.getId(),
                widget.getWidgetType(),
                widget.getTitle(),
                widget.getWidth(),
                widget.getRowSpan(),
                widget.getConfig(),
                user);
        rendered.put("render_meta", Map.of("source", "live"));
        return rendered;
    }

    private static final int MAX_WIDGET_BATCH = 24;

    @Transactional(readOnly = true)
    public Map<String, Object> renderWidgets(
            UserEntity user, List<String> ids, List<String> forceRefreshIds) {
        requirePersonalDashboard(user);
        List<String> ordered = dedupeIds(ids);
        if (ordered.isEmpty()) {
            return Map.of("widgets", List.of());
        }
        if (ordered.size() > MAX_WIDGET_BATCH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Maximálně " + MAX_WIDGET_BATCH + " widgetů najednou.");
        }
        java.util.Set<String> refreshSet =
                forceRefreshIds != null
                        ? forceRefreshIds.stream().map(String::strip).filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.toSet())
                        : java.util.Set.of();

        List<Map<String, Object>> widgets = new ArrayList<>();
        for (String wid : ordered) {
            widgets.add(renderOneWidget(user, wid, refreshSet.contains(wid)));
        }
        return Map.of("widgets", widgets);
    }

    private Map<String, Object> renderOneWidget(UserEntity user, String widgetId, boolean forceRefresh) {
        DashboardWidgetEntity widget = widgetRepository.findByIdAndUserId(widgetId, user.getId()).orElse(null);
        if (widget == null) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("id", widgetId);
            missing.put("type", "markdown");
            missing.put("title", "");
            missing.put("width", "full");
            missing.put("config", Map.of());
            missing.put("data", Map.of("error", "Widget nenalezen.", "content", ""));
            missing.put("from_snapshot", false);
            return missing;
        }
        try {
            Map<String, Object> rendered = renderWidget(user, widgetId, forceRefresh);
            rendered.put("from_snapshot", "snapshot".equals(String.valueOf(
                    rendered.get("render_meta") instanceof Map<?, ?> m ? m.get("source") : "")));
            return rendered;
        } catch (ResponseStatusException ex) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("id", widgetId);
            err.put("type", widget.getWidgetType());
            err.put("title", widget.getTitle());
            // ResponseStatusException.getReason() can be null; Map.of() would NPE in that case.
            Map<String, Object> errorData = new LinkedHashMap<>();
            errorData.put("error", ex.getReason());
            err.put("data", errorData);
            err.put("from_snapshot", false);
            return err;
        }
    }

    private static List<String> dedupeIds(List<String> ids) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        if (ids == null) {
            return out;
        }
        for (String raw : ids) {
            String wid = raw != null ? raw.strip() : "";
            if (!wid.isBlank() && seen.add(wid)) {
                out.add(wid);
            }
        }
        return out;
    }

    private void requirePersonalDashboard(UserEntity user) {
        featureAccessService.requireFeature(user, "personal_dashboard");
        if (!FeatureAccessService.isSubscriber(user)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Tato funkce je dostupná pro předplatitele časopisu Bankovnictví.");
        }
    }
}
