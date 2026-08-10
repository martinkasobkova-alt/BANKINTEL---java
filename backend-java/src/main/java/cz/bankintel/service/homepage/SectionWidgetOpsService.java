package cz.bankintel.service.homepage;

import cz.bankintel.domain.entity.SectionEntity;
import cz.bankintel.domain.entity.SectionWidgetEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.SectionRepository;
import cz.bankintel.repository.SectionWidgetRepository;
import cz.bankintel.security.RoleGuard;
import cz.bankintel.util.IdGenerator;
import java.time.Instant;
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
public class SectionWidgetOpsService {

    private static final Set<String> ALLOWED_WIDTHS =
            Set.of("full", "three-quarters", "two-thirds", "half", "third", "quarter", "sixth", "eighth");

    private final SectionRepository sectionRepository;
    private final SectionWidgetRepository sectionWidgetRepository;
    private final SectionService sectionService;

    @Transactional
    public Map<String, Object> reorderWidgets(String slug, Map<String, Object> payload) {
        SectionEntity section = findBySlugOrThrow(slug);
        Object rawIds = payload.get("widget_ids");
        if (!(rawIds instanceof List<?> ids)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "widget_ids musí být pole řetězců.");
        }
        List<SectionWidgetEntity> widgets =
                sectionWidgetRepository.findBySectionIdOrderBySortOrderAsc(section.getId());
        Map<String, SectionWidgetEntity> byId = new LinkedHashMap<>();
        for (SectionWidgetEntity widget : widgets) {
            byId.put(widget.getId(), widget);
        }
        List<SectionWidgetEntity> reordered = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (Object raw : ids) {
            String id = String.valueOf(raw).strip();
            SectionWidgetEntity widget = byId.get(id);
            if (widget != null && seen.add(id)) {
                reordered.add(widget);
            }
        }
        for (SectionWidgetEntity widget : widgets) {
            if (!seen.contains(widget.getId())) {
                reordered.add(widget);
            }
        }
        mergeLayout(reordered, payload.get("widget_layout"));
        for (int i = 0; i < reordered.size(); i++) {
            SectionWidgetEntity widget = reordered.get(i);
            widget.setSortOrder(i);
            sectionWidgetRepository.save(widget);
        }
        return sectionService.getSectionBySlug(slug);
    }

    @Transactional
    public Map<String, Object> patchWidget(String slug, String widgetId, Map<String, Object> payload) {
        SectionEntity section = findBySlugOrThrow(slug);
        SectionWidgetEntity widget = sectionWidgetRepository
                .findById(widgetId)
                .filter(w -> section.getId().equals(w.getSectionId()))
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
        widget.setUpdatedAt(Instant.now());
        sectionWidgetRepository.save(widget);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", widget.getId());
        out.put("width", widget.getWidth());
        out.put("rowSpan", widget.getRowSpan());
        out.put("title", widget.getTitle());
        out.put("config", widget.getConfig());
        return out;
    }

    @Transactional
    public Map<String, Object> deleteWidget(String slug, String widgetId) {
        SectionEntity section = findBySlugOrThrow(slug);
        SectionWidgetEntity widget = sectionWidgetRepository
                .findById(widgetId)
                .filter(w -> section.getId().equals(w.getSectionId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Widget neexistuje."));
        sectionWidgetRepository.delete(widget);
        return Map.of("ok", true, "deleted_id", widgetId);
    }

    @Transactional
    public Map<String, Object> syncWidgetsFromPayload(SectionEntity section, List<Map<String, Object>> widgetsPayload) {
        List<Map<String, Object>> widgets = widgetsPayload != null ? widgetsPayload : List.of();
        List<Map<String, Object>> sectionPages = section.getSectionPages() != null ? section.getSectionPages() : List.of();
        List<SectionWidgetEntity> existing =
                sectionWidgetRepository.findBySectionIdOrderBySortOrderAsc(section.getId());
        Map<String, SectionWidgetEntity> existingById = new LinkedHashMap<>();
        for (SectionWidgetEntity widget : existing) {
            existingById.put(widget.getId(), widget);
        }

        Set<String> keepIds = new java.util.LinkedHashSet<>();
        for (int i = 0; i < widgets.size(); i++) {
            Map<String, Object> raw = widgets.get(i);
            if (raw == null) {
                continue;
            }
            String id = String.valueOf(raw.getOrDefault("id", "")).strip();
            if (id.isEmpty()) {
                id = IdGenerator.newId();
            }
            keepIds.add(id);
            SectionWidgetEntity entity = existingById.get(id);
            if (entity == null) {
                entity = new SectionWidgetEntity();
                entity.setId(id);
                entity.setSectionId(section.getId());
                entity.setCreatedAt(Instant.now());
            }
            entity.setWidgetType(String.valueOf(raw.getOrDefault("type", "text")));
            entity.setTitle(String.valueOf(raw.getOrDefault("title", "")));
            Object titleEn = raw.get("title_en");
            entity.setTitleEn(titleEn != null ? String.valueOf(titleEn) : null);
            entity.setWidth(String.valueOf(raw.getOrDefault("width", "full")));
            Object rowSpan = raw.get("rowSpan");
            entity.setRowSpan(rowSpan == null ? null : Integer.parseInt(String.valueOf(rowSpan)));
            Object pageId = raw.get("section_page_id");
            entity.setSectionPageId(pageId != null ? String.valueOf(pageId).strip() : null);
            if (raw.get("config") instanceof Map<?, ?> cfg) {
                entity.setConfig(castMap(cfg));
            }
            entity.setSortOrder(i);
            entity.setUpdatedAt(Instant.now());
            sectionWidgetRepository.save(entity);
        }

        for (SectionWidgetEntity old : existing) {
            if (!keepIds.contains(old.getId())) {
                sectionWidgetRepository.delete(old);
            }
        }
        return sectionService.getSectionBySlug(section.getSlug());
    }

    private SectionEntity findBySlugOrThrow(String slug) {
        return sectionRepository
                .findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sekce neexistuje."));
    }

    private static void mergeLayout(List<SectionWidgetEntity> widgets, Object layoutUpdates) {
        if (!(layoutUpdates instanceof Map<?, ?> layout)) {
            return;
        }
        Map<String, SectionWidgetEntity> byId = new LinkedHashMap<>();
        for (SectionWidgetEntity widget : widgets) {
            byId.put(widget.getId(), widget);
        }
        for (Map.Entry<?, ?> entry : layout.entrySet()) {
            SectionWidgetEntity widget = byId.get(String.valueOf(entry.getKey()));
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
