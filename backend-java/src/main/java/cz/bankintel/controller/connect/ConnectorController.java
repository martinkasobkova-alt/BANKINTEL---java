package cz.bankintel.controller.connect;

import cz.bankintel.domain.dto.ApiKeyDtos.ConnectorWidgetCreateRequest;
import cz.bankintel.domain.dto.ApiKeyDtos.ConnectorWidgetPushRequest;
import cz.bankintel.domain.dto.MeDtos.DashboardWidgetCreateRequest;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.service.dashboard.DashboardShareService;
import cz.bankintel.service.me.MeDashboardService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The external-facing "API connector" surface. Write endpoints are API-key authenticated (see
 * {@link cz.bankintel.security.ApiKeyAuthFilter}) and reuse {@link MeDashboardService}'s existing,
 * already-validated widget persistence at the Java level — this controller only adds the scope check
 * and the "external push" widget shape on top. The read endpoint reuses the same share-token model the
 * browser embed feature already uses ({@link DashboardShareService#embedWidget}), trimmed to a stable,
 * data-only response shape instead of the full renderable-widget payload the embed viewer needs.
 */
@RestController
@RequestMapping("/api/connect/v1")
@RequiredArgsConstructor
public class ConnectorController {

    private final CurrentUser currentUser;
    private final MeDashboardService meDashboardService;
    private final DashboardShareService dashboardShareService;

    @PreAuthorize("hasAuthority('SCOPE_dashboard:write')")
    @PostMapping("/dashboards/{pageId}/widgets")
    public Map<String, Object> createWidget(
            @PathVariable String pageId, @RequestBody ConnectorWidgetCreateRequest body) {
        var user = currentUser.requireUserEntity();
        var createRequest = new DashboardWidgetCreateRequest(
                "api_push_chart", body.title(), body.description(), Map.of(), body.width());
        Map<String, Object> widget = meDashboardService.createWidget(user, pageId, createRequest);
        if (body.data() != null) {
            widget = meDashboardService.pushWidgetData(user, String.valueOf(widget.get("id")), body.data());
        }
        return widget;
    }

    @PreAuthorize("hasAuthority('SCOPE_dashboard:write')")
    @PutMapping("/widgets/{widgetId}/data")
    public Map<String, Object> pushWidgetData(
            @PathVariable String widgetId, @RequestBody ConnectorWidgetPushRequest body) {
        return meDashboardService.pushWidgetData(currentUser.requireUserEntity(), widgetId, body.data());
    }

    /**
     * Unauthenticated on purpose, same as the browser embed endpoint it wraps — possession of the
     * page's share token (a high-entropy bearer secret) is the credential, not an API key.
     */
    @GetMapping("/widgets/{token}/{widgetId}")
    public Map<String, Object> readWidget(@PathVariable String token, @PathVariable String widgetId) {
        Map<String, Object> full = dashboardShareService.embedWidget(token, widgetId);
        @SuppressWarnings("unchecked")
        Map<String, Object> widget = (Map<String, Object>) full.get("widget");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", widget.get("title"));
        out.put("type", widget.get("type"));
        out.put("data", widget.get("data"));
        out.put("last_updated_at", widget.get("last_fetched_at"));
        return out;
    }
}
