package cz.bankintel.controller.dashboard;

import cz.bankintel.domain.dto.DashboardShareDtos.WidgetComparePreviewRequest;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.service.dashboard.DashboardShareService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard-share")
@RequiredArgsConstructor
public class DashboardShareController {

    private final CurrentUser currentUser;
    private final DashboardShareService dashboardShareService;

    @GetMapping("/public")
    public List<Map<String, Object>> listPublic(
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "40") int limit) {
        return dashboardShareService.listPublic(q, limit);
    }

    @GetMapping("/shared-with-me")
    public List<Map<String, Object>> sharedWithMe() {
        return dashboardShareService.listSharedWithMe(currentUser.requireUserEntity());
    }

    @GetMapping("/token/{token}")
    public Map<String, Object> viewByToken(@PathVariable String token) {
        return dashboardShareService.viewByToken(token, currentUser.optionalUserEntity());
    }

    /**
     * Stránka v režimu „Veřejný" se slibuje slovy „Kdokoli může stránku otevřít" — takže se tu
     * nesmí vyžadovat přihlášení. Rozhodnutí, kdo smí dovnitř, dělá až
     * DashboardPageAccessService.resolvePageViewRole, které veřejný režim pouští i bez uživatele;
     * requireUserEntity() ho ale předbíhalo a nepřihlášený dostal 401 ještě před ním.
     */
    @GetMapping("/page/{pageId}")
    public Map<String, Object> viewByPageId(@PathVariable String pageId) {
        return dashboardShareService.viewByPageId(pageId, currentUser.optionalUserEntity());
    }

    @GetMapping("/embed/{token}/{widgetId}")
    public Map<String, Object> embedWidget(@PathVariable String token, @PathVariable String widgetId) {
        return dashboardShareService.embedWidget(token, widgetId);
    }

    @PostMapping("/widget-compare-preview")
    public Map<String, Object> widgetComparePreview(@RequestBody WidgetComparePreviewRequest body) {
        return dashboardShareService.widgetComparePreview(body, currentUser.optionalUserEntity());
    }
}
