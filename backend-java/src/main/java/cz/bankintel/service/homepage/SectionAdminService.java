package cz.bankintel.service.homepage;

import cz.bankintel.domain.entity.SectionEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.SectionRepository;
import cz.bankintel.repository.SectionWidgetRepository;
import cz.bankintel.security.RoleGuard;
import cz.bankintel.service.homepage.resolver.SourceRecordsWidgetResolver;
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
public class SectionAdminService {

    private static final Set<String> EDITOR_KEYS =
            Set.of("widgets", "default_chart_type", "default_chart_frequency", "section_pages");

    private final SectionRepository sectionRepository;
    private final SectionWidgetRepository sectionWidgetRepository;
    private final SectionService sectionService;
    private final SectionWidgetOpsService sectionWidgetOpsService;
    private final SourceRecordsWidgetResolver sourceRecordsWidgetResolver;

    @Transactional
    public Map<String, Object> createSection(Map<String, Object> payload) {
        String name = payload.get("name") != null ? String.valueOf(payload.get("name")).trim() : "";
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Název je povinný.");
        }
        String baseSlug = payload.get("slug") != null ? String.valueOf(payload.get("slug")).trim() : slugify(name);
        if (baseSlug.isEmpty()) {
            baseSlug = slugify(name);
        }
        String slug = uniqueSlug(baseSlug, null);

        SectionEntity section = new SectionEntity();
        section.setId(IdGenerator.newId());
        section.setName(name);
        section.setSlug(slug);
        section.setIcon(payload.get("icon") != null ? String.valueOf(payload.get("icon")) : "Folder");
        section.setSubtitle(payload.get("subtitle") != null ? String.valueOf(payload.get("subtitle")) : "");
        section.setSortOrder(nextSortOrder());
        section.setDefaultChartType(
                payload.get("default_chart_type") != null ? String.valueOf(payload.get("default_chart_type")) : "line");
        Object freq = payload.get("default_chart_frequency");
        section.setDefaultChartFrequency(freq != null ? String.valueOf(freq) : null);
        if (payload.get("section_pages") instanceof List<?> pages) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pageMaps = (List<Map<String, Object>>) pages;
            section.setSectionPages(pageMaps);
        }
        sectionRepository.save(section);

        if (payload.get("widgets") instanceof List<?> widgets) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> widgetMaps = (List<Map<String, Object>>) widgets;
            sectionWidgetOpsService.syncWidgetsFromPayload(section, widgetMaps);
        }
        return sectionService.getSectionBySlug(slug);
    }

    @Transactional
    public Map<String, Object> deleteSection(String sectionId) {
        SectionEntity section = findSection(sectionId);
        sectionWidgetRepository.deleteBySectionId(section.getId());
        sectionRepository.delete(section);
        return Map.of("ok", true, "deleted_id", section.getId());
    }

    @Transactional
    public Map<String, Object> reorderSections(Map<String, Object> payload) {
        Object raw = payload.get("section_ids");
        if (!(raw instanceof List<?> ids)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "section_ids musí být pole.");
        }
        List<SectionEntity> sections = sectionRepository.findAllByOrderBySortOrderAsc();
        Map<String, SectionEntity> byId = new LinkedHashMap<>();
        for (SectionEntity section : sections) {
            byId.put(section.getId(), section);
        }
        List<SectionEntity> reordered = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (Object item : ids) {
            String id = String.valueOf(item).strip();
            SectionEntity section = byId.get(id);
            if (section != null && seen.add(id)) {
                reordered.add(section);
            }
        }
        for (SectionEntity section : sections) {
            if (!seen.contains(section.getId())) {
                reordered.add(section);
            }
        }
        Instant now = Instant.now();
        for (int i = 0; i < reordered.size(); i++) {
            SectionEntity section = reordered.get(i);
            section.setSortOrder((i + 1) * 10);
            section.setUpdatedAt(now);
            sectionRepository.save(section);
        }
        List<String> finalIds = reordered.stream().map(SectionEntity::getId).toList();
        return Map.of("section_ids", finalIds);
    }

    @Transactional
    public Map<String, Object> patchSection(String sectionIdOrSlug, Map<String, Object> payload, UserEntity user) {
        SectionEntity section = findSection(sectionIdOrSlug);
        if (!RoleGuard.isAdminRole(user.getRole())) {
            for (String key : payload.keySet()) {
                if (!EDITOR_KEYS.contains(key)) {
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "Editor může měnit pouze widgety a nastavení grafů v sekci.");
                }
            }
        }

        if (payload.containsKey("name")) {
            section.setName(String.valueOf(payload.get("name")));
        }
        if (payload.containsKey("name_en")) {
            section.setNameEn(String.valueOf(payload.get("name_en")));
        }
        if (payload.containsKey("icon")) {
            section.setIcon(String.valueOf(payload.get("icon")));
        }
        if (payload.containsKey("subtitle")) {
            section.setSubtitle(String.valueOf(payload.get("subtitle")));
        }
        if (payload.containsKey("subtitle_en")) {
            section.setSubtitleEn(String.valueOf(payload.get("subtitle_en")));
        }
        if (payload.containsKey("order")) {
            section.setSortOrder(Integer.parseInt(String.valueOf(payload.get("order"))));
        }
        if (payload.containsKey("default_chart_type")) {
            section.setDefaultChartType(String.valueOf(payload.get("default_chart_type")));
        }
        if (payload.containsKey("default_chart_frequency")) {
            Object val = payload.get("default_chart_frequency");
            section.setDefaultChartFrequency(val != null ? String.valueOf(val) : null);
        }
        if (payload.containsKey("section_pages") && payload.get("section_pages") instanceof List<?> pages) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> normalized = (List<Map<String, Object>>) pages;
            section.setSectionPages(normalized);
        }
        section.setUpdatedAt(Instant.now());
        sectionRepository.save(section);

        if (payload.containsKey("widgets") && payload.get("widgets") instanceof List<?> widgets) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> widgetMaps = (List<Map<String, Object>>) widgets;
            return sectionWidgetOpsService.syncWidgetsFromPayload(section, widgetMaps);
        }
        return sectionService.getSectionBySlug(section.getSlug());
    }

    @Transactional
    public Map<String, Object> updateKpis(String slug, List<Map<String, Object>> kpis) {
        SectionEntity section = sectionRepository
                .findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sekce neexistuje."));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> raw : kpis != null ? kpis : List.<Map<String, Object>>of()) {
            Map<String, Object> item = new LinkedHashMap<>(raw);
            if (!item.containsKey("id") || String.valueOf(item.get("id")).isBlank()) {
                item.put("id", IdGenerator.newId());
            }
            out.add(item);
        }
        section.setHeadlineKpis(out);
        section.setUpdatedAt(Instant.now());
        sectionRepository.save(section);
        return Map.of("ok", true, "kpis", out);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveKpis(String slug) {
        SectionEntity section = sectionRepository
                .findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sekce neexistuje."));
        List<Map<String, Object>> kpis = section.getHeadlineKpis() != null ? section.getHeadlineKpis() : List.of();
        List<Map<String, Object>> resolved = new ArrayList<>();
        for (Map<String, Object> kpi : kpis) {
            resolved.add(resolveKpi(kpi));
        }
        return Map.of("kpis", resolved);
    }

    private Map<String, Object> resolveKpi(Map<String, Object> kpi) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("id", kpi.getOrDefault("id", ""));
        base.put("title", kpi.getOrDefault("title", ""));
        base.put("value", null);
        base.put("unit", "");
        base.put("period", null);
        base.put("prev_value", null);
        base.put("prev_period", null);
        base.put("trend", "neutral");
        String type = String.valueOf(kpi.getOrDefault("type", "")).strip();
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = kpi.get("config") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if ("arad_view".equals(type)) {
            Map<String, Object> data = sourceRecordsWidgetResolver.resolveAradView(cfg, null);
            if (data.containsKey("error")) {
                base.put("error", data.get("error"));
                return base;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.getOrDefault("rows", List.of());
            if (rows.isEmpty()) {
                base.put("error", "Žádná data.");
                return base;
            }
            Map<String, Object> last = rows.get(rows.size() - 1);
            Object value = last.get("value");
            if (value == null) {
                value = last.get("y");
            }
            base.put("value", value);
            base.put("period", last.getOrDefault("period", last.get("x")));
            base.put("unit", data.getOrDefault("unit", ""));
            if (rows.size() > 1) {
                Map<String, Object> prev = rows.get(rows.size() - 2);
                Object prevValue = prev.get("value");
                if (prevValue == null) {
                    prevValue = prev.get("y");
                }
                base.put("prev_value", prevValue);
                base.put("prev_period", prev.getOrDefault("period", prev.get("x")));
                if (value instanceof Number n && prevValue instanceof Number p) {
                    base.put("trend", n.doubleValue() >= p.doubleValue() ? "up" : "down");
                }
            }
        }
        return base;
    }

    private int nextSortOrder() {
        return sectionRepository.findAllByOrderBySortOrderAsc().stream()
                        .mapToInt(SectionEntity::getSortOrder)
                        .max()
                        .orElse(0)
                + 10;
    }

    private String uniqueSlug(String base, String ignoreId) {
        String slug = slugify(base);
        int i = 2;
        while (true) {
            var existing = sectionRepository.findBySlug(slug);
            if (existing.isEmpty() || (ignoreId != null && ignoreId.equals(existing.get().getId()))) {
                return slug;
            }
            slug = slugify(base) + "-" + i++;
        }
    }

    private static String slugify(String name) {
        String s = name != null ? name.trim().toLowerCase() : "";
        s = s.replace('á', 'a')
                .replace('č', 'c')
                .replace('ď', 'd')
                .replace('é', 'e')
                .replace('ě', 'e')
                .replace('í', 'i')
                .replace('ň', 'n')
                .replace('ó', 'o')
                .replace('ř', 'r')
                .replace('š', 's')
                .replace('ť', 't')
                .replace('ú', 'u')
                .replace('ů', 'u')
                .replace('ý', 'y')
                .replace('ž', 'z')
                .replace(' ', '-')
                .replace('_', '-')
                .replace('/', '-');
        s = s.replaceAll("[^a-z0-9-]+", "").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "sekce" : s;
    }

    private SectionEntity findSection(String sectionIdOrSlug) {
        return sectionRepository
                .findById(sectionIdOrSlug)
                .or(() -> sectionRepository.findBySlug(sectionIdOrSlug))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sekce neexistuje."));
    }
}
