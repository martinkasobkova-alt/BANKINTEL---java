package cz.bankintel.service.homepage;
import cz.bankintel.util.BankIntelEnvVars;

import cz.bankintel.domain.entity.SectionEntity;
import cz.bankintel.domain.entity.SectionWidgetEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.SectionRepository;
import cz.bankintel.repository.SectionWidgetRepository;
import cz.bankintel.security.RoleGuard;
import cz.bankintel.service.homepage.HomepageAiCommentaryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SectionService {

    private static final Pattern SLUG_RE = Pattern.compile("[^a-z0-9-]+");

    private final SectionRepository sectionRepository;
    private final SectionWidgetRepository sectionWidgetRepository;
    private final WidgetRenderService widgetRenderService;
    private final HomepageAiCommentaryService homepageAiCommentaryService;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSections() {
        return sectionRepository.findAllByOrderBySortOrderAsc().stream()
                .map(this::serializeSectionSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSectionBySlug(String slug) {
        SectionEntity section = findBySlugOrThrow(slug);
        List<SectionWidgetEntity> widgets = sectionWidgetRepository.findBySectionIdOrderBySortOrderAsc(section.getId());
        List<Map<String, Object>> normalizedPages = normalizeSectionPages(section.getSectionPages());
        List<Map<String, Object>> widgetMaps = widgets.stream().map(this::serializeSectionWidget).toList();
        normalizeWidgetSectionPages(widgetMaps, normalizedPages);

        Map<String, Object> out = serializeSectionFull(section);
        out.put("section_pages", normalizedPages);
        out.put("widgets", widgetMaps);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> renderSection(String slug, String pageSlug, UserEntity user) {
        SectionEntity section = findBySlugOrThrow(slug);
        List<Map<String, Object>> sectionPages = normalizeSectionPages(section.getSectionPages());
        List<SectionWidgetEntity> rawWidgets =
                sectionWidgetRepository.findBySectionIdOrderBySortOrderAsc(section.getId());

        boolean canPreviewHidden = user != null && RoleGuard.isContentManager(user.getRole());
        List<Map<String, Object>> visiblePages =
                canPreviewHidden ? sectionPages : sectionPages.stream().filter(SectionService::isPageVisible).toList();

        String activePageSlug = pageSlug != null && !pageSlug.isBlank() ? slugify(pageSlug) : "";
        Map<String, Object> activePage = null;
        if (!activePageSlug.isEmpty()) {
            activePage = sectionPages.stream()
                    .filter(p -> activePageSlug.equals(String.valueOf(p.get("slug"))))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Podstránka sekce neexistuje."));
            if (!canPreviewHidden && !isPageVisible(activePage)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Podstránka sekce není veřejně dostupná.");
            }
        }

        String activePageId = activePage != null ? String.valueOf(activePage.get("id")).trim() : "";
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (SectionWidgetEntity w : rawWidgets) {
            String widgetPageId = w.getSectionPageId() != null ? w.getSectionPageId().trim() : "";
            boolean include = activePageId.isEmpty()
                    ? widgetPageId.isEmpty()
                    : activePageId.equals(widgetPageId);
            if (!include) {
                continue;
            }
            rendered.add(widgetRenderService.buildRenderedWidget(
                    w.getId(),
                    w.getWidgetType(),
                    w.getTitle(),
                    w.getWidth(),
                    w.getRowSpan(),
                    w.getConfig(),
                    user));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", section.getId());
        out.put("slug", section.getSlug());
        out.put("name", section.getName());
        out.put("subtitle", section.getSubtitle());
        out.put("icon", section.getIcon());
        out.put("updated_at", instantToString(section.getUpdatedAt()));
        out.put("default_chart_type", section.getDefaultChartType());
        out.put("default_chart_frequency", section.getDefaultChartFrequency());
        out.put("section_pages", visiblePages);
        out.put("active_page_slug", activePageSlug);
        out.put("widgets", rendered);
        if ("1".equals(BankIntelEnvVars.get("BANKO_HOME_RENDER_AI"))) {
            homepageAiCommentaryService.attachBatch(rendered, 25.0);
        }
        return out;
    }

    private SectionEntity findBySlugOrThrow(String slug) {
        return sectionRepository
                .findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sekce neexistuje."));
    }

    private Map<String, Object> serializeSectionSummary(SectionEntity section) {
        int widgetCount = sectionWidgetRepository.findBySectionIdOrderBySortOrderAsc(section.getId()).size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", section.getId());
        out.put("slug", section.getSlug());
        out.put("name", section.getName());
        if (section.getNameEn() != null) {
            out.put("name_en", section.getNameEn());
        }
        out.put("icon", section.getIcon() != null ? section.getIcon() : "Folder");
        out.put("order", section.getSortOrder());
        out.put("widget_count", widgetCount);
        return out;
    }

    private Map<String, Object> serializeSectionFull(SectionEntity section) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", section.getId());
        out.put("slug", section.getSlug());
        out.put("name", section.getName());
        if (section.getNameEn() != null) {
            out.put("name_en", section.getNameEn());
        }
        out.put("icon", section.getIcon());
        out.put("subtitle", section.getSubtitle());
        if (section.getSubtitleEn() != null) {
            out.put("subtitle_en", section.getSubtitleEn());
        }
        out.put("order", section.getSortOrder());
        out.put("default_chart_type", section.getDefaultChartType());
        out.put("default_chart_frequency", section.getDefaultChartFrequency());
        out.put("headline_kpis", section.getHeadlineKpis() != null ? section.getHeadlineKpis() : List.of());
        out.put("created_at", instantToString(section.getCreatedAt()));
        out.put("updated_at", instantToString(section.getUpdatedAt()));
        return out;
    }

    private Map<String, Object> serializeSectionWidget(SectionWidgetEntity widget) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", widget.getId());
        out.put("type", widget.getWidgetType());
        out.put("title", widget.getTitle());
        if (widget.getTitleEn() != null) {
            out.put("title_en", widget.getTitleEn());
        }
        out.put("width", widget.getWidth());
        out.put("rowSpan", widget.getRowSpan());
        out.put("config", widget.getConfig() != null ? widget.getConfig() : Map.of());
        if (widget.getSectionPageId() != null) {
            out.put("section_page_id", widget.getSectionPageId());
        }
        return out;
    }

    private static boolean isPageVisible(Map<String, Object> page) {
        Object visible = page.get("is_visible");
        return visible == null || Boolean.TRUE.equals(visible);
    }

    private static void normalizeWidgetSectionPages(
            List<Map<String, Object>> widgets, List<Map<String, Object>> sectionPages) {
        var pageIds = sectionPages.stream()
                .map(p -> String.valueOf(p.get("id")).trim())
                .filter(id -> !id.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        for (Map<String, Object> widget : widgets) {
            Object raw = widget.get("section_page_id");
            String pageId = raw != null ? String.valueOf(raw).trim() : "";
            if (pageId.isEmpty() || !pageIds.contains(pageId)) {
                widget.remove("section_page_id");
            } else {
                widget.put("section_page_id", pageId);
            }
        }
    }

    private static List<Map<String, Object>> normalizeSectionPages(List<Map<String, Object>> rawPages) {
        List<Map<String, Object>> pages = rawPages != null ? rawPages : List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        var seenIds = new java.util.HashSet<String>();
        var seenSlugs = new java.util.HashSet<String>();
        int idx = 0;
        for (Map<String, Object> raw : pages) {
            if (raw == null) {
                continue;
            }
            String title = String.valueOf(raw.getOrDefault("title", "")).trim();
            if (title.isEmpty()) {
                title = "Podstránka " + (idx + 1);
            }
            String baseSlug = slugify(String.valueOf(raw.getOrDefault("slug", title)));
            String slug = baseSlug;
            int dedupe = 2;
            while (seenSlugs.contains(slug)) {
                slug = baseSlug + "-" + dedupe++;
            }
            String pid = String.valueOf(raw.getOrDefault("id", "")).trim();
            if (pid.isEmpty()) {
                pid = cz.bankintel.util.IdGenerator.newId();
            }
            while (seenIds.contains(pid)) {
                pid = cz.bankintel.util.IdGenerator.newId();
            }
            seenIds.add(pid);
            seenSlugs.add(slug);
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("id", pid);
            page.put("title", title);
            page.put("slug", slug);
            page.put("order", toInt(raw.get("order"), (idx + 1) * 10));
            page.put("is_visible", raw.get("is_visible") == null || Boolean.TRUE.equals(raw.get("is_visible")));
            Object titleEn = raw.get("title_en");
            if (titleEn != null && !String.valueOf(titleEn).trim().isEmpty()) {
                page.put("title_en", String.valueOf(titleEn).trim().substring(0, Math.min(200, String.valueOf(titleEn).trim().length())));
            }
            out.add(page);
            idx++;
        }
        out.sort((a, b) -> {
            int ao = toInt(a.get("order"), 0);
            int bo = toInt(b.get("order"), 0);
            if (ao != bo) {
                return Integer.compare(ao, bo);
            }
            return String.valueOf(a.get("title")).compareTo(String.valueOf(b.get("title")));
        });
        for (int i = 0; i < out.size(); i++) {
            out.get(i).put("order", (i + 1) * 10);
        }
        return out;
    }

    private static String slugify(String name) {
        String s = name != null ? name.trim().toLowerCase(Locale.ROOT) : "";
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
        s = SLUG_RE.matcher(s).replaceAll("").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "sekce" : s;
    }

    private static int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static String instantToString(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
