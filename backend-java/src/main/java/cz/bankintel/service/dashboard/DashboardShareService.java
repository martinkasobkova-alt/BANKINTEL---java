package cz.bankintel.service.dashboard;

import cz.bankintel.domain.dto.DashboardShareDtos.WidgetComparePreviewRequest;
import cz.bankintel.domain.entity.DashboardPageEntity;
import cz.bankintel.domain.entity.DashboardWidgetEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.DashboardPageRepository;
import cz.bankintel.repository.DashboardWidgetRepository;
import cz.bankintel.repository.UserRepository;
import cz.bankintel.service.dashboard.DashboardPageAccessService.ViewRole;
import cz.bankintel.service.me.PersonalWidgetRenderService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class DashboardShareService {

    private static final Set<String> ALLOWED_COMPARE_KEYS = Set.of(
            "source_id",
            "set_id",
            "indicator_id",
            "selected_indicator",
            "selected_indicators",
            "label",
            "name",
            "chart_type",
            "y_axis",
            "catalog",
            "source_type",
            "frequency",
            "unit",
            "query_params",
            "country",
            "geo",
            "country_label",
            "dataset_id");
    private static final int MAX_COMPARE_ENTRIES = 4;

    private final DashboardPageRepository pageRepository;
    private final DashboardWidgetRepository widgetRepository;
    private final UserRepository userRepository;
    private final DashboardPageAccessService pageAccessService;
    private final PersonalWidgetRenderService personalWidgetRenderService;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPublic(String q, int limit) {
        int capped = Math.min(Math.max(limit, 1), 100);
        List<DashboardPageEntity> rows;
        if (q != null && !q.strip().isEmpty()) {
            rows = pageRepository.findPublicByTitleContainingOrderByUpdatedAtDesc(
                    q.strip(), PageRequest.of(0, capped));
        } else {
            rows = pageRepository.findByAccessModeOrderByUpdatedAtDesc("public", PageRequest.of(0, capped));
        }
        return toPublicPageDocs(rows);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSharedWithMe(UserEntity user) {
        List<DashboardPageEntity> rows =
                pageRepository.findSharedCandidatePages(user.getId(), PageRequest.of(0, 100));
        String uid = user.getId();
        List<DashboardPageEntity> filtered = new ArrayList<>();
        for (DashboardPageEntity page : rows) {
            if ("invite_only".equals(pageAccessService.normalizeAccessMode(page.getAccessMode()))) {
                Set<String> allowed = allowedUserIds(page);
                if (!allowed.contains(uid)) {
                    continue;
                }
            }
            filtered.add(page);
        }
        return toPublicPageDocs(filtered);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> viewByToken(String token, UserEntity optionalUser) {
        String tok = token != null ? token.strip() : "";
        if (tok.length() < 8) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Odkaz není platný.");
        }
        DashboardPageEntity page = pageRepository
                .findByShareTokenAndShareEnabledTrue(tok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Odkaz není platný nebo byl zrušen."));
        ViewRole role = pageAccessService.resolvePageViewRole(page, optionalUser, tok);
        if (role == ViewRole.DENIED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Odkaz není platný nebo byl zrušen.");
        }
        UserEntity owner = requireOwner(page);
        Map<String, Object> data = renderPageForViewer(page, owner);
        data.put("view_role", role.name().toLowerCase());
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> viewByPageId(String pageId, UserEntity user) {
        DashboardPageEntity page = pageRepository
                .findById(pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stránka nenalezena."));
        ViewRole role = pageAccessService.resolvePageViewRole(page, user);
        if (role == ViewRole.DENIED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stránka nenalezena.");
        }
        UserEntity owner = requireOwner(page);
        Map<String, Object> data = renderPageForViewer(page, owner);
        data.put("view_role", role.name().toLowerCase());
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> embedWidget(String token, String widgetId) {
        String tok = token != null ? token.strip() : "";
        String wid = widgetId != null ? widgetId.strip() : "";
        if (tok.length() < 8 || wid.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Embed není dostupný.");
        }
        DashboardPageEntity page = pageRepository
                .findByShareToken(tok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Embed není dostupný nebo byl vypnut."));
        if (!page.isAllowEmbed()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Embed není dostupný nebo byl vypnut.");
        }
        UserEntity owner = requireOwner(page);
        DashboardWidgetEntity widget = widgetRepository
                .findByIdAndUserIdAndPageId(wid, owner.getId(), page.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Graf nenalezen."));
        Map<String, Object> rendered = personalWidgetRenderService.renderWithSnapshot(widget, owner, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("embed", true);
        out.put(
                "page",
                Map.of(
                        "title", page.getTitle() != null ? page.getTitle() : "",
                        "owner_name", ownerDisplayName(owner)));
        out.put("widget", rendered);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> widgetComparePreview(WidgetComparePreviewRequest body, UserEntity optionalUser) {
        String token = body.token() != null ? body.token().strip() : "";
        String pageId = body.pageId() != null ? body.pageId().strip() : "";
        String widgetId = body.widgetId() != null ? body.widgetId().strip() : "";
        List<Map<String, Object>> compareWith = sanitizeCompareWith(body.compareWith());
        if (widgetId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí widget_id.");
        }

        DashboardPageEntity page;
        ViewRole role;
        if (!token.isEmpty()) {
            if (token.length() < 8) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Odkaz není platný.");
            }
            page = pageRepository.findByShareTokenAndShareEnabledTrue(token).orElse(null);
            role = page != null ? pageAccessService.resolvePageViewRole(page, optionalUser, token) : ViewRole.DENIED;
        } else if (!pageId.isEmpty()) {
            page = pageRepository.findById(pageId).orElse(null);
            role = page != null ? pageAccessService.resolvePageViewRole(page, optionalUser) : ViewRole.DENIED;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí token nebo page_id.");
        }

        if (page == null || role == ViewRole.DENIED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stránka nenalezena.");
        }
        if (!page.isAllowViewerCompare()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Vlastník pro tuto stránku nepovolil porovnávání diváky.");
        }
        UserEntity owner = requireOwner(page);
        DashboardWidgetEntity widget = widgetRepository
                .findByIdAndUserIdAndPageId(widgetId, owner.getId(), page.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Widget nenalezen."));

        Map<String, Object> cfg = new HashMap<>(widget.getConfig() != null ? widget.getConfig() : Map.of());
        if (!compareWith.isEmpty()) {
            cfg.put("chart_compare_with", compareWith);
        } else {
            cfg.remove("chart_compare_with");
        }
        return personalWidgetRenderService.renderTransient(widget, owner, cfg);
    }

    private Map<String, Object> renderPageForViewer(DashboardPageEntity page, UserEntity owner) {
        List<DashboardWidgetEntity> widgets =
                widgetRepository.findByUserIdAndPageIdOrderBySortOrderAsc(owner.getId(), page.getId());
        List<Map<String, Object>> rendered = widgets.stream()
                .map(w -> personalWidgetRenderService.renderWithSnapshot(w, owner, false))
                .toList();

        Map<String, Object> pageMap = new LinkedHashMap<>();
        pageMap.put("id", page.getId());
        pageMap.put("title", page.getTitle() != null ? page.getTitle() : "");
        pageMap.put("slug", page.getSlug() != null ? page.getSlug() : "");
        pageMap.put("access_mode", pageAccessService.normalizeAccessMode(page.getAccessMode()));
        pageMap.put("owner_name", ownerDisplayName(owner));
        pageMap.put("allow_viewer_compare", page.isAllowViewerCompare());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", pageMap);
        out.put("widgets", rendered);
        out.put("viewer_mode", true);
        return out;
    }

    private List<Map<String, Object>> toPublicPageDocs(List<DashboardPageEntity> rows) {
        Set<String> ownerIds = new HashSet<>();
        for (DashboardPageEntity row : rows) {
            if (row.getUserId() != null && !row.getUserId().isBlank()) {
                ownerIds.add(row.getUserId());
            }
        }
        Map<String, UserEntity> owners = new HashMap<>();
        if (!ownerIds.isEmpty()) {
            for (UserEntity user : userRepository.findAllById(ownerIds)) {
                owners.put(user.getId(), user);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (DashboardPageEntity row : rows) {
            UserEntity owner = owners.get(row.getUserId());
            out.add(publicPageDoc(row, owner));
        }
        return out;
    }

    private static Map<String, Object> publicPageDoc(DashboardPageEntity page, UserEntity owner) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", page.getId());
        doc.put("title", page.getTitle() != null ? page.getTitle() : "");
        doc.put("slug", page.getSlug() != null ? page.getSlug() : "");
        doc.put("owner_name", owner != null ? ownerDisplayName(owner) : "");
        doc.put("updated_at", instantToString(page.getUpdatedAt()));
        return doc;
    }

    private UserEntity requireOwner(DashboardPageEntity page) {
        return userRepository
                .findById(page.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stránka nenalezena."));
    }

    private static String ownerDisplayName(UserEntity owner) {
        if (owner.getName() != null && !owner.getName().isBlank()) {
            return owner.getName();
        }
        if (owner.getEmail() != null && !owner.getEmail().isBlank()) {
            return owner.getEmail();
        }
        return "Uživatel";
    }

    private static Set<String> allowedUserIds(DashboardPageEntity page) {
        Set<String> allowed = new HashSet<>();
        List<String> raw = page.getAllowedUserIds();
        if (raw == null) {
            return allowed;
        }
        for (String id : raw) {
            if (id != null && !id.strip().isEmpty()) {
                allowed.add(id.strip());
            }
        }
        return allowed;
    }

    static List<Map<String, Object>> sanitizeCompareWith(List<Map<String, Object>> raw) {
        if (raw == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int count = 0;
        for (Map<String, Object> item : raw) {
            if (count >= MAX_COMPARE_ENTRIES || item == null) {
                break;
            }
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : item.entrySet()) {
                String key = entry.getKey();
                if (!ALLOWED_COMPARE_KEYS.contains(key)) {
                    continue;
                }
                Object value = entry.getValue();
                if (value instanceof String s) {
                    clean.put(key, s.length() > 300 ? s.substring(0, 300) : s);
                } else if (value instanceof Number || value instanceof Boolean) {
                    clean.put(key, value);
                } else if (value instanceof Map<?, ?> map) {
                    Map<String, Object> nested = new LinkedHashMap<>();
                    int nestedCount = 0;
                    for (Map.Entry<?, ?> nestedEntry : map.entrySet()) {
                        if (nestedCount >= 30) {
                            break;
                        }
                        String nestedKey = String.valueOf(nestedEntry.getKey());
                        if (nestedKey.length() > 60) {
                            nestedKey = nestedKey.substring(0, 60);
                        }
                        Object nestedVal = nestedEntry.getValue();
                        if (nestedVal instanceof String s) {
                            nested.put(nestedKey, s.length() > 200 ? s.substring(0, 200) : s);
                        } else {
                            nested.put(nestedKey, nestedVal);
                        }
                        nestedCount++;
                    }
                    clean.put(key, nested);
                } else if (value instanceof List<?> list) {
                    List<Object> sanitized = new ArrayList<>();
                    int listCount = 0;
                    for (Object element : list) {
                        if (listCount >= 30) {
                            break;
                        }
                        if (element instanceof String s) {
                            sanitized.add(s.length() > 200 ? s.substring(0, 200) : s);
                        } else {
                            sanitized.add(element);
                        }
                        listCount++;
                    }
                    clean.put(key, sanitized);
                }
            }
            if (!clean.isEmpty()) {
                out.add(clean);
                count++;
            }
        }
        return out;
    }

    private static String instantToString(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
