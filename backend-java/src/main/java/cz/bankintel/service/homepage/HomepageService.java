package cz.bankintel.service.homepage;

import cz.bankintel.domain.entity.HomepageConfigEntity;
import cz.bankintel.domain.entity.HomepageWidgetEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.AppSettingsRepository;
import cz.bankintel.repository.HomepageConfigRepository;
import cz.bankintel.repository.HomepageWidgetRepository;
import cz.bankintel.repository.SectionRepository;
import cz.bankintel.repository.SectionWidgetRepository;
import cz.bankintel.util.IdGenerator;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class HomepageService {

    private static final String CONFIG_ID = "main";
    private static final String GLOBAL_SETTINGS_ID = "global";
    private static final Set<String> ALLOWED_APPEARANCE_IDS = Set.of(
            "blue",
            "soft-blue-ivory",
            "nude-rose-gold",
            "lavender-pearl",
            "mint-sand",
            "navy-champagne",
            "blush-cream",
            "sage-beige",
            "slate-ice-blue",
            "dusty-pink-taupe",
            "lilac-silver",
            "graphite-pearl",
            "onyx-copper");

    private final HomepageConfigRepository configRepository;
    private final HomepageWidgetRepository widgetRepository;
    private final SectionRepository sectionRepository;
    private final SectionWidgetRepository sectionWidgetRepository;
    private final AppSettingsRepository appSettingsRepository;
    private final WidgetRenderService widgetRenderService;

    @Transactional(readOnly = true)
    public Map<String, Object> getConfig() {
        HomepageConfigEntity config = loadOrCreateConfig();
        return serializeConfig(config, widgetRepository.findByConfigIdOrderBySortOrderAsc(CONFIG_ID));
    }

    @Transactional
    public Map<String, Object> updateConfig(Map<String, Object> payload) {
        HomepageConfigEntity config = loadOrCreateConfig();
        applyConfigFields(config, payload);
        configRepository.save(config);

        if (payload.containsKey("widgets")) {
            widgetRepository.deleteByConfigId(CONFIG_ID);
            saveWidgetsFromPayload(payload.get("widgets"));
        }

        return getConfig();
    }

    @Transactional
    public Map<String, Object> resetConfig() {
        widgetRepository.deleteByConfigId(CONFIG_ID);
        configRepository.deleteById(CONFIG_ID);
        return getConfig();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> render(UserEntity user) {
        HomepageConfigEntity config = loadOrCreateConfig();
        List<HomepageWidgetEntity> widgets = widgetRepository.findByConfigIdOrderBySortOrderAsc(CONFIG_ID);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", config.getTitle());
        out.put("subtitle", config.getSubtitle());
        out.put("updated_at", instantToString(config.getUpdatedAt()));
        out.put("default_chart_type", config.getDefaultChartType());
        out.put("default_chart_frequency", config.getDefaultChartFrequency());
        out.put(
                "widgets",
                widgets.stream()
                        .map(w -> widgetRenderService.buildRenderedWidget(
                                w.getId(),
                                w.getWidgetType(),
                                w.getTitle(),
                                w.getWidth(),
                                w.getRowSpan(),
                                w.getConfig(),
                                user))
                        .toList());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAppSettings() {
        var entity = appSettingsRepository
                .findById(GLOBAL_SETTINGS_ID)
                .orElseGet(() -> {
                    var created = new cz.bankintel.domain.entity.AppSettingsEntity();
                    created.setId(GLOBAL_SETTINGS_ID);
                    created.setSettingsJson(Map.of("default_appearance_id", "blue"));
                    return appSettingsRepository.save(created);
                });
        Map<String, Object> json = entity.getSettingsJson() != null ? entity.getSettingsJson() : Map.of();
        Object appearance = json.get("default_appearance_id");
        return Map.of("default_appearance_id", appearance != null ? appearance : "blue");
    }

    @Transactional
    public Map<String, Object> patchAppSettings(Map<String, Object> payload) {
        if (!payload.containsKey("default_appearance_id")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nic ke změně.");
        }
        String aid = String.valueOf(payload.get("default_appearance_id") != null ? payload.get("default_appearance_id") : "")
                .trim();
        if (!ALLOWED_APPEARANCE_IDS.contains(aid)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatné ID barevného schématu.");
        }
        var entity = appSettingsRepository
                .findById(GLOBAL_SETTINGS_ID)
                .orElseGet(() -> {
                    var created = new cz.bankintel.domain.entity.AppSettingsEntity();
                    created.setId(GLOBAL_SETTINGS_ID);
                    return created;
                });
        Map<String, Object> json = new LinkedHashMap<>(entity.getSettingsJson() != null ? entity.getSettingsJson() : Map.of());
        json.put("default_appearance_id", aid);
        entity.setSettingsJson(json);
        entity.setUpdatedAt(Instant.now());
        appSettingsRepository.save(entity);
        return getAppSettings();
    }

    @Transactional
    public Map<String, Object> updateHeadlineKpis(List<Map<String, Object>> kpis) {
        HomepageConfigEntity config = loadOrCreateConfig();
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> kpi : kpis != null ? kpis : List.<Map<String, Object>>of()) {
            normalized.add(buildHeadlineKpi(kpi));
        }
        config.setHeadlineKpis(normalized);
        config.setUpdatedAt(Instant.now());
        configRepository.save(config);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("kpis", normalized);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> searchWidgets(String query) {
        String q = query != null ? query.trim() : "";
        if (q.length() < 2) {
            return Map.of("query", q, "results", List.of());
        }

        List<Map<String, Object>> results = new ArrayList<>();
        HomepageConfigEntity config = loadOrCreateConfig();
        for (HomepageWidgetEntity w : widgetRepository.findByConfigIdOrderBySortOrderAsc(CONFIG_ID)) {
            Map<String, Object> doc = widgetSearchDoc(
                    w.getId(),
                    w.getTitle(),
                    w.getWidgetType(),
                    w.getConfig(),
                    config.getTitle(),
                    "/",
                    "Přehled",
                    "",
                    "",
                    "");
            if (matchesQuery(String.valueOf(doc.get("_haystack")), q)) {
                doc.remove("_haystack");
                results.add(doc);
            }
        }

        for (var section : sectionRepository.findAllByOrderBySortOrderAsc()) {
            String slug = section.getSlug() != null ? section.getSlug().trim() : "";
            String sectionName = section.getName() != null ? section.getName().trim() : "Sekce";
            String basePath = slug.isEmpty() ? "" : "/s/" + slug;
            Map<String, Map<String, String>> pagesById = sectionPagesById(section.getSectionPages());
            String indexTitles = pagesById.values().stream()
                    .map(p -> p.get("title"))
                    .filter(t -> t != null && !t.isBlank())
                    .sorted()
                    .reduce((a, b) -> a + " " + b)
                    .orElse("");

            for (var w : sectionWidgetRepository.findBySectionIdOrderBySortOrderAsc(section.getId())) {
                String pageId = w.getSectionPageId() != null ? w.getSectionPageId().trim() : "";
                Map<String, String> sub = pageId.isEmpty() ? null : pagesById.get(pageId);
                String subSlug = sub != null ? sub.getOrDefault("slug", "") : "";
                String subTitle = sub != null ? sub.getOrDefault("title", "") : "";
                String pagePath = !subSlug.isEmpty() && !basePath.isEmpty() ? basePath + "/" + subSlug : basePath;
                Map<String, Object> doc = widgetSearchDoc(
                        w.getId(),
                        w.getTitle(),
                        w.getWidgetType(),
                        w.getConfig(),
                        sectionName,
                        pagePath,
                        sectionName,
                        subTitle,
                        subSlug,
                        indexTitles);
                if (matchesQuery(String.valueOf(doc.get("_haystack")), q)) {
                    doc.remove("_haystack");
                    results.add(doc);
                }
            }
        }

        return Map.of("query", q, "results", results.size() > 40 ? results.subList(0, 40) : results);
    }

    private HomepageConfigEntity loadOrCreateConfig() {
        return configRepository.findById(CONFIG_ID).orElseGet(() -> {
            HomepageConfigEntity created = new HomepageConfigEntity();
            created.setId(CONFIG_ID);
            return configRepository.save(created);
        });
    }

    private void applyConfigFields(HomepageConfigEntity config, Map<String, Object> payload) {
        if (payload.containsKey("title")) {
            config.setTitle(String.valueOf(payload.get("title")));
        }
        if (payload.containsKey("title_en")) {
            config.setTitleEn(asNullableString(payload.get("title_en")));
        }
        if (payload.containsKey("subtitle")) {
            config.setSubtitle(String.valueOf(payload.get("subtitle")));
        }
        if (payload.containsKey("subtitle_en")) {
            config.setSubtitleEn(asNullableString(payload.get("subtitle_en")));
        }
        if (payload.containsKey("default_chart_type")) {
            config.setDefaultChartType(String.valueOf(payload.get("default_chart_type")));
        }
        if (payload.containsKey("default_chart_frequency")) {
            config.setDefaultChartFrequency(asNullableString(payload.get("default_chart_frequency")));
        }
        if (payload.containsKey("headline_kpis") && payload.get("headline_kpis") instanceof List<?> kpis) {
            config.setHeadlineKpis(castMapList(kpis));
        }
        config.setUpdatedAt(Instant.now());
    }

    @SuppressWarnings("unchecked")
    private void saveWidgetsFromPayload(Object rawWidgets) {
        if (!(rawWidgets instanceof List<?> list)) {
            return;
        }
        int order = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> w = (Map<String, Object>) raw;
            HomepageWidgetEntity entity = new HomepageWidgetEntity();
            String id = asNullableString(w.get("id"));
            entity.setId(id != null && !id.isBlank() ? id : IdGenerator.newId());
            entity.setConfigId(CONFIG_ID);
            entity.setWidgetType(String.valueOf(w.get("type")));
            entity.setTitle(asNullableString(w.get("title")) != null ? asNullableString(w.get("title")) : "");
            entity.setTitleEn(asNullableString(w.get("title_en")));
            entity.setWidth(asNullableString(w.get("width")) != null ? asNullableString(w.get("width")) : "full");
            Object rowSpan = w.containsKey("rowSpan") ? w.get("rowSpan") : w.get("row_span");
            entity.setRowSpan(rowSpan instanceof Number n ? n.intValue() : null);
            Object cfg = w.get("config");
            entity.setConfig(cfg instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
            entity.setSortOrder(order++);
            widgetRepository.save(entity);
        }
    }

    private Map<String, Object> serializeConfig(HomepageConfigEntity config, List<HomepageWidgetEntity> widgets) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", config.getId());
        out.put("title", config.getTitle());
        if (config.getTitleEn() != null) {
            out.put("title_en", config.getTitleEn());
        }
        out.put("subtitle", config.getSubtitle());
        if (config.getSubtitleEn() != null) {
            out.put("subtitle_en", config.getSubtitleEn());
        }
        out.put("default_chart_type", config.getDefaultChartType());
        out.put("default_chart_frequency", config.getDefaultChartFrequency());
        out.put("headline_kpis", config.getHeadlineKpis() != null ? config.getHeadlineKpis() : List.of());
        out.put("updated_at", instantToString(config.getUpdatedAt()));
        out.put("widgets", widgets.stream().map(this::serializeWidget).toList());
        return out;
    }

    private Map<String, Object> serializeWidget(HomepageWidgetEntity widget) {
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
        return out;
    }

    private static Map<String, Object> widgetSearchDoc(
            String id,
            String title,
            String type,
            Map<String, Object> config,
            String pageTitle,
            String pagePath,
            String section,
            String subpageTitle,
            String subpageSlug,
            String sectionPagesIndexText) {
        Map<String, Object> cfg = config != null ? config : Map.of();
        String resolvedTitle = title != null && !title.isBlank()
                ? title
                : String.valueOf(cfg.getOrDefault("title", "Bez názvu"));
        String haystack = String.join(
                " ",
                resolvedTitle,
                type != null ? type : "",
                searchText(cfg),
                pageTitle != null ? pageTitle : "",
                section != null ? section : "",
                subpageTitle != null ? subpageTitle : "",
                subpageSlug != null ? subpageSlug : "",
                sectionPagesIndexText != null ? sectionPagesIndexText : "");
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", id);
        doc.put("title", resolvedTitle);
        doc.put("type", type);
        doc.put("view", cfg.getOrDefault("view", cfg.getOrDefault("chart_type", "")));
        doc.put("page_title", pageTitle);
        doc.put("section", section);
        doc.put("subpage_title", subpageTitle.isBlank() ? null : subpageTitle);
        doc.put("subpage_slug", subpageSlug.isBlank() ? null : subpageSlug);
        doc.put("path", pagePath);
        doc.put("_haystack", haystack);
        return doc;
    }

    private static Map<String, Map<String, String>> sectionPagesById(List<Map<String, Object>> sectionPages) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (sectionPages == null) {
            return out;
        }
        for (Map<String, Object> page : sectionPages) {
            if (page == null) {
                continue;
            }
            String pid = String.valueOf(page.getOrDefault("id", "")).trim();
            if (pid.isEmpty()) {
                continue;
            }
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("slug", String.valueOf(page.getOrDefault("slug", "")).trim().toLowerCase(Locale.ROOT));
            meta.put("title", String.valueOf(page.getOrDefault("title", "")).trim());
            out.put(pid, meta);
        }
        return out;
    }

    private static String searchText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            for (Object v : map.values()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(searchText(v));
            }
            return sb.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object v : list) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(searchText(v));
            }
            return sb.toString();
        }
        return "";
    }

    private static final Pattern WS = Pattern.compile("\\s+");

    private static String foldSearch(String s) {
        String raw = s != null ? s.trim().toLowerCase(Locale.ROOT) : "";
        String decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD);
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < decomposed.length(); i++) {
            char ch = decomposed.charAt(i);
            if (Character.getType(ch) != Character.NON_SPACING_MARK) {
                ascii.append(ch);
            }
        }
        return WS.matcher(ascii.toString()).replaceAll(" ").trim();
    }

    private static List<String> queryTokens(String q) {
        List<String> tokens = new ArrayList<>();
        for (String part : foldSearch(q).split("\\s+")) {
            if (part.length() >= 2) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private static boolean matchesQuery(String haystack, String q) {
        List<String> tokens = queryTokens(q);
        if (tokens.isEmpty()) {
            return false;
        }
        String hay = foldSearch(haystack);
        return tokens.stream().allMatch(tok -> hay.contains(tok));
    }

    /**
     * Port of {@code HeadlineKpi} pydantic defaults + {@code normalize_kpi_localized}
     * (backend/models.py, backend/services/localized_cms.py): fills in id/type/config
     * defaults and caps title/title_en at 200 chars.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildHeadlineKpi(Map<String, Object> raw) {
        Map<String, Object> src = raw != null ? raw : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        String id = asNullableString(src.get("id"));
        out.put("id", id != null ? id : IdGenerator.newId());
        String title = src.get("title") != null ? String.valueOf(src.get("title")) : "";
        out.put("title", title.length() > 200 ? title.substring(0, 200) : title);
        String titleEn = asNullableString(src.get("title_en"));
        if (titleEn != null) {
            out.put("title_en", titleEn.length() > 200 ? titleEn.substring(0, 200) : titleEn);
        }
        String type = asNullableString(src.get("type"));
        out.put("type", type != null ? type : "arad_view");
        Object cfg = src.get("config");
        out.put("config", cfg instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castMapList(List<?> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static String asNullableString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static String instantToString(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
