package cz.bankintel.controller.homepage;

import cz.bankintel.security.CurrentUser;
import cz.bankintel.security.RoleGuard;
import cz.bankintel.service.homepage.HomepageAiCommentaryService;
import cz.bankintel.service.homepage.HomepageHeadlineKpiService;
import cz.bankintel.service.homepage.HomepageService;
import cz.bankintel.service.homepage.HomepageWidgetOpsService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/homepage")
@RequiredArgsConstructor
public class HomepageController {

    private final HomepageService homepageService;
    private final HomepageWidgetOpsService homepageWidgetOpsService;
    private final HomepageHeadlineKpiService homepageHeadlineKpiService;
    private final HomepageAiCommentaryService homepageAiCommentaryService;
    private final CurrentUser currentUser;
    private final RoleGuard roleGuard;

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        return homepageService.getConfig();
    }

    @PutMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> payload) {
        roleGuard.requireEditor(currentUser.requireUserEntity());
        return homepageService.updateConfig(payload);
    }

    @PostMapping("/config/reset")
    public Map<String, Object> resetConfig() {
        roleGuard.requireAdmin(currentUser.requireUserEntity());
        return homepageService.resetConfig();
    }

    @GetMapping("/render")
    public Map<String, Object> render() {
        return homepageService.render(currentUser.optionalUserEntity());
    }

    @PostMapping("/render-widget")
    public Map<String, Object> renderWidget(@RequestBody Map<String, Object> payload) {
        return homepageWidgetOpsService.renderWidget(payload, currentUser.optionalUserEntity());
    }

    @PostMapping("/preview")
    public Map<String, Object> previewWidget(@RequestBody Map<String, Object> payload) {
        roleGuard.requireEditor(currentUser.requireUserEntity());
        return homepageWidgetOpsService.previewWidget(payload, currentUser.requireUserEntity());
    }

    @PostMapping("/reorder")
    public Map<String, Object> reorderWidgets(@RequestBody Map<String, Object> payload) {
        roleGuard.requireEditor(currentUser.requireUserEntity());
        return homepageWidgetOpsService.reorderWidgets(payload);
    }

    @PatchMapping("/widget/{widgetId}")
    public Map<String, Object> patchWidget(@PathVariable String widgetId, @RequestBody Map<String, Object> payload) {
        roleGuard.requireEditor(currentUser.requireUserEntity());
        return homepageWidgetOpsService.patchWidget(widgetId, payload);
    }

    @DeleteMapping("/widget/{widgetId}")
    public Map<String, Object> deleteWidget(@PathVariable String widgetId) {
        roleGuard.requireEditor(currentUser.requireUserEntity());
        return homepageWidgetOpsService.deleteWidget(widgetId);
    }

    @GetMapping("/kpis-resolved")
    public Map<String, Object> kpisResolved() {
        return homepageHeadlineKpiService.resolveHeadlineKpis();
    }

    @PutMapping("/kpis")
    public Map<String, Object> updateKpis(@RequestBody Map<String, Object> payload) {
        roleGuard.requireEditor(currentUser.requireUserEntity());
        List<Map<String, Object>> kpis = new ArrayList<>();
        Object rawKpis = payload != null ? payload.get("kpis") : null;
        if (rawKpis instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    kpis.add(castMap(map));
                }
            }
        }
        return homepageService.updateHeadlineKpis(kpis);
    }

    @GetMapping("/app-settings")
    public Map<String, Object> getAppSettings() {
        return homepageService.getAppSettings();
    }

    @PatchMapping("/app-settings")
    public Map<String, Object> patchAppSettings(@RequestBody Map<String, Object> payload) {
        roleGuard.requireAdmin(currentUser.requireUserEntity());
        return homepageService.patchAppSettings(payload);
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(name = "q", defaultValue = "") String q) {
        return homepageService.searchWidgets(q);
    }

    @GetMapping("/ai-commentary-status")
    public Map<String, Object> aiCommentaryStatus() {
        return Map.of("enabled", homepageAiCommentaryService.commentaryEnabled());
    }

    @PostMapping("/ai-commentary-reload")
    public Map<String, Object> aiCommentaryReload() {
        roleGuard.requireAdmin(currentUser.requireUserEntity());
        return Map.of("reloaded", true, "enabled", homepageAiCommentaryService.commentaryEnabled());
    }

    @PostMapping("/ai-commentary")
    public Map<String, Object> aiCommentaryPreview(@RequestBody Map<String, Object> payload) {
        roleGuard.requireAdmin(currentUser.requireUserEntity());
        Map<String, Object> preview = homepageWidgetOpsService.previewWidget(payload, currentUser.requireUserEntity());
        Object dataObj = preview.get("data");
        Map<String, Object> data = dataObj instanceof Map<?, ?> m ? castMap(m) : Map.of();
        Map<String, Object> config = preview.get("config") instanceof Map<?, ?> cfg ? castMap(cfg) : Map.of();
        Map<String, Object> verbose = homepageAiCommentaryService.generateVerbose(
                String.valueOf(payload.get("type")),
                String.valueOf(payload.getOrDefault("title", "")),
                data,
                String.valueOf(payload.getOrDefault("prompt", "")),
                config);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", homepageAiCommentaryService.commentaryEnabled());
        out.put("text", verbose.get("text"));
        out.put("reason", verbose.get("reason"));
        out.put("summary", verbose.get("summary"));
        out.put("fallback_used", verbose.get("fallback_used"));
        out.put("preview", preview);
        if (data.get("error") != null) {
            out.put("data_error", data.get("error"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
