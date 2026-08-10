package cz.bankintel.controller.homepage;

import cz.bankintel.security.CurrentUser;
import cz.bankintel.security.RoleGuard;
import cz.bankintel.service.homepage.SectionAdminService;
import cz.bankintel.service.homepage.SectionService;
import cz.bankintel.service.homepage.SectionWidgetOpsService;
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
@RequestMapping("/api/sections")
@RequiredArgsConstructor
public class SectionsController {

    private final SectionService sectionService;
    private final SectionAdminService sectionAdminService;
    private final SectionWidgetOpsService sectionWidgetOpsService;
    private final CurrentUser currentUser;
    private final RoleGuard roleGuard;

    @GetMapping({"", "/"})
    public List<Map<String, Object>> listSections() {
        return sectionService.listSections();
    }

    @PostMapping("/reorder")
    public Map<String, Object> reorderSections(@RequestBody Map<String, Object> payload) {
        roleGuard.requireAdmin(currentUser.requireUserEntity());
        return sectionAdminService.reorderSections(payload);
    }

    @PostMapping({"", "/"})
    public Map<String, Object> createSection(@RequestBody Map<String, Object> payload) {
        roleGuard.requireAdmin(currentUser.requireUserEntity());
        return sectionAdminService.createSection(payload);
    }

    @DeleteMapping("/{sectionId}")
    public Map<String, Object> deleteSection(@PathVariable String sectionId) {
        roleGuard.requireAdmin(currentUser.requireUserEntity());
        return sectionAdminService.deleteSection(sectionId);
    }

    @GetMapping("/{slug}")
    public Map<String, Object> getSection(@PathVariable String slug) {
        return sectionService.getSectionBySlug(slug);
    }

    @PatchMapping("/{sectionId}")
    public Map<String, Object> patchSection(@PathVariable String sectionId, @RequestBody Map<String, Object> payload) {
        roleGuard.requireEditor(currentUser.requireUserEntity());
        return sectionAdminService.patchSection(sectionId, payload, currentUser.requireUserEntity());
    }

    @PostMapping("/{slug}/reorder")
    public Map<String, Object> reorderSectionWidgets(@PathVariable String slug, @RequestBody Map<String, Object> payload) {
        roleGuard.requireEditor(currentUser.requireUserEntity());
        return sectionWidgetOpsService.reorderWidgets(slug, payload);
    }

    @PatchMapping("/{slug}/widget/{widgetId}")
    public Map<String, Object> patchSectionWidget(
            @PathVariable String slug, @PathVariable String widgetId, @RequestBody Map<String, Object> payload) {
        roleGuard.requireEditor(currentUser.requireUserEntity());
        return sectionWidgetOpsService.patchWidget(slug, widgetId, payload);
    }

    @DeleteMapping("/{slug}/widget/{widgetId}")
    public Map<String, Object> deleteSectionWidget(@PathVariable String slug, @PathVariable String widgetId) {
        roleGuard.requireEditor(currentUser.requireUserEntity());
        return sectionWidgetOpsService.deleteWidget(slug, widgetId);
    }

    @GetMapping("/{slug}/render")
    public Map<String, Object> renderSection(
            @PathVariable String slug, @RequestParam(required = false) String page) {
        return sectionService.renderSection(slug, page, currentUser.optionalUserEntity());
    }

    @GetMapping("/{slug}/kpis-resolved")
    public Map<String, Object> kpisResolved(@PathVariable String slug) {
        return sectionAdminService.resolveKpis(slug);
    }

    @PutMapping("/{slug}/kpis")
    public Map<String, Object> updateKpis(@PathVariable String slug, @RequestBody Map<String, Object> payload) {
        roleGuard.requireEditor(currentUser.requireUserEntity());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kpis =
                payload.get("kpis") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        return sectionAdminService.updateKpis(slug, kpis);
    }
}
