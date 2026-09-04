package cz.bankintel.service.me;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import cz.bankintel.domain.dto.AuthDtos.MeResponse;
import cz.bankintel.domain.dto.MeDtos;
import cz.bankintel.domain.dto.MeDtos.ChangePasswordRequest;
import cz.bankintel.domain.dto.MeDtos.DashboardPageCreateRequest;
import cz.bankintel.domain.dto.MeDtos.DashboardPagePatchRequest;
import cz.bankintel.domain.dto.MeDtos.DashboardWidgetCreateRequest;
import cz.bankintel.domain.dto.MeDtos.DashboardWidgetPatchRequest;
import cz.bankintel.domain.dto.MeDtos.NavOrderPutRequest;
import cz.bankintel.domain.dto.MeDtos.PreferencesPatchRequest;
import cz.bankintel.domain.dto.MeDtos.ProfilePatchRequest;
import cz.bankintel.domain.dto.MeDtos.ReorderPagesRequest;
import cz.bankintel.domain.dto.MeDtos.ReorderWidgetsRequest;
import cz.bankintel.domain.entity.DashboardPageEntity;
import cz.bankintel.domain.entity.DashboardWidgetEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.DashboardPageRepository;
import cz.bankintel.repository.DashboardWidgetRepository;
import cz.bankintel.repository.UserRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.auth.UserMapper;
import cz.bankintel.util.IdGenerator;
import cz.bankintel.util.SlugUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MeDashboardService {

    private static final Set<String> DISALLOWED_WIDGET_TYPES = Set.of("ad");
    private static final Set<String> GRID_KEYS =
            Set.of("grid_column_start", "grid_column_end", "grid_row_start", "grid_row_end");

    private static final List<String> DEFAULT_ADMIN_NAV = List.of(
            "nav-users",
            "nav-feature-access",
            "nav-rss-feeds",
            "nav-articles",
            "nav-bug-reports",
            "nav-data-admin",
            "nav-sources",
            "nav-ads",
            "nav-sync-logs");

    private static final Set<String> ALLOWED_ADMIN_NAV_IDS = Set.copyOf(DEFAULT_ADMIN_NAV);

    private static final List<String> DEFAULT_USER_NAV = List.of(
            "nav-user-catalog-search",
            "nav-user-stock-search",
            "nav-user-manager-explorer",
            "nav-user-pdf-archive",
            "nav-user-podcasts",
            "nav-user-articles",
            "nav-user-messages",
            "nav-user-my-data",
            "nav-user-subscription",
            "nav-user-my-dashboard",
            "nav-user-rss-feeds",
            "nav-user-report-bug",
            "nav-user-settings");

    private static final Set<String> ALLOWED_USER_NAV_IDS = Set.copyOf(DEFAULT_USER_NAV);

    private final UserRepository userRepository;
    private final DashboardPageRepository pageRepository;
    private final DashboardWidgetRepository widgetRepository;
    private final FeatureAccessService featureAccessService;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPages(UserEntity user) {
        requireSubscriberDashboard(user);
        return pageRepository.findByUserIdOrderBySortOrderAsc(user.getId()).stream()
                .map(p -> serializePage(p, true))
                .toList();
    }

    @Transactional
    public Map<String, Object> createPage(UserEntity user, DashboardPageCreateRequest body) {
        requireSubscriberDashboard(user);
        if (!canAddPage(user)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Více osobních stránek není v aktuálním plánu povoleno.");
        }
        long count = pageRepository.countByUserId(user.getId());
        String pageId = IdGenerator.newId();
        String slug = uniqueSlug(user.getId(), body.title(), null);
        DashboardPageEntity page = new DashboardPageEntity();
        page.setId(pageId);
        page.setUserId(user.getId());
        page.setTitle(body.title().strip());
        page.setSlug(slug);
        page.setSortOrder((int) count);
        page.setDefaultPage(count == 0);
        page.setAccessMode("owner_only");
        page.setAllowedUserIds(new ArrayList<>());
        page.setShareEnabled(false);
        page = pageRepository.save(page);
        if (count == 0) {
            user.setDefaultDashboardPageId(pageId);
            userRepository.save(user);
        }
        return serializePage(page, true);
    }

    @Transactional
    public Map<String, Object> patchPage(UserEntity user, String pageId, DashboardPagePatchRequest body) {
        requireSubscriberDashboard(user);
        DashboardPageEntity page = requireOwnedPage(user.getId(), pageId);
        if (body.title() != null) {
            page.setTitle(body.title().strip());
            page.setSlug(uniqueSlug(user.getId(), body.title(), pageId));
        }
        if (body.order() != null) {
            page.setSortOrder(body.order());
        }
        if (Boolean.TRUE.equals(body.isDefault())) {
            clearDefaultPages(user.getId());
            page.setDefaultPage(true);
            user.setDefaultDashboardPageId(pageId);
            userRepository.save(user);
        } else if (Boolean.FALSE.equals(body.isDefault())) {
            page.setDefaultPage(false);
        }
        if (body.accessMode() != null) {
            String mode = normalizeAccessMode(body.accessMode());
            page.setAccessMode(mode);
            if ("owner_only".equals(mode)) {
                page.setShareEnabled(false);
                page.setAllowedUserIds(new ArrayList<>());
            } else if ("public".equals(mode)) {
                page.setShareEnabled(false);
            }
        }
        if (body.allowedUserIds() != null) {
            List<String> allowed = body.allowedUserIds().stream()
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .toList();
            page.setAllowedUserIds(new ArrayList<>(allowed));
        }
        if (body.shareEnabled() != null) {
            page.setShareEnabled(body.shareEnabled());
            if (body.shareEnabled()) {
                String mode = normalizeAccessMode(page.getAccessMode());
                if (!"invite_only".equals(mode)) {
                    page.setAccessMode("invite_only");
                }
                if (page.getShareToken() == null || page.getShareToken().isBlank()) {
                    page.setShareToken(generateShareToken());
                }
            }
        }
        if (Boolean.TRUE.equals(body.regenerateShareToken())) {
            page.setShareToken(generateShareToken());
            page.setShareEnabled(true);
        }
        if (Boolean.TRUE.equals(body.allowEmbed()) && (page.getShareToken() == null || page.getShareToken().isBlank())) {
            page.setShareToken(generateShareToken());
        }
        if (body.allowViewerCompare() != null) {
            page.setAllowViewerCompare(body.allowViewerCompare());
        }
        if (body.allowEmbed() != null) {
            page.setAllowEmbed(body.allowEmbed());
        }
        page = pageRepository.save(page);
        return serializePage(page, true);
    }

    @Transactional
    public void deletePage(UserEntity user, String pageId) {
        requireSubscriberDashboard(user);
        requireOwnedPage(user.getId(), pageId);
        widgetRepository.deleteByUserIdAndPageId(user.getId(), pageId);
        pageRepository.deleteById(pageId);
        if (pageId.equals(user.getDefaultDashboardPageId())) {
            user.setDefaultDashboardPageId(null);
            user.setOpenPersonalDashboardOnLogin(false);
            userRepository.save(user);
        }
    }

    @Transactional
    public void reorderPages(UserEntity user, ReorderPagesRequest body) {
        requireSubscriberDashboard(user);
        for (int i = 0; i < body.pageIds().size(); i++) {
            String pid = body.pageIds().get(i);
            DashboardPageEntity page = pageRepository
                    .findByIdAndUserId(pid, user.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatné pořadí stránek"));
            page.setSortOrder(i);
            pageRepository.save(page);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDefaultDashboard(UserEntity user) {
        requireSubscriberDashboard(user);
        DashboardPageEntity page = pageRepository
                .findFirstByUserIdAndDefaultPageTrue(user.getId())
                .or(() -> pageRepository.findByUserIdOrderBySortOrderAsc(user.getId()).stream()
                        .findFirst())
                .orElse(null);
        if (page == null) {
            // Map.of() throws NPE on a null value — "page" must stay null in the JSON when the user has no pages.
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("page", null);
            empty.put("widgets", List.of());
            return empty;
        }
        List<Map<String, Object>> widgets = widgetRepository
                .findByUserIdAndPageIdOrderBySortOrderAsc(user.getId(), page.getId())
                .stream()
                .map(this::serializeWidget)
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", serializePage(page, true));
        out.put("widgets", widgets);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listWidgets(UserEntity user, String pageId) {
        requireSubscriberDashboard(user);
        requireOwnedPage(user.getId(), pageId);
        return widgetRepository.findByUserIdAndPageIdOrderBySortOrderAsc(user.getId(), pageId).stream()
                .map(this::serializeWidget)
                .toList();
    }

    @Transactional
    public Map<String, Object> createWidget(UserEntity user, String pageId, DashboardWidgetCreateRequest body) {
        if (DISALLOWED_WIDGET_TYPES.contains(body.type())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Tento typ widgetu není v osobním dashboardu povolen.");
        }
        requireSubscriberDashboard(user);
        requireSaveWidget(user);
        requireOwnedPage(user.getId(), pageId);
        requireComputedWidgets(user, body.type(), body.config());
        Map<String, Object> config = body.config() != null ? new HashMap<>(body.config()) : new HashMap<>();
        if ("rss_monitoring".equals(body.type()) && !featureAccessService.canAccessFeature(user, "rss_monitoring")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RSS monitoring není v aktuálním plánu dostupný.");
        }
        long count = widgetRepository.countByUserIdAndPageId(user.getId(), pageId);
        DashboardWidgetEntity widget = new DashboardWidgetEntity();
        widget.setId(IdGenerator.newId());
        widget.setUserId(user.getId());
        widget.setPageId(pageId);
        widget.setWidgetType(body.type());
        widget.setTitle(body.title() != null ? body.title() : "");
        widget.setDescription(body.description() != null ? body.description() : "");
        widget.setConfig(config);
        widget.setWidth(body.width() != null && !body.width().isBlank() ? body.width() : "full");
        widget.setSortOrder((int) count);
        widget = widgetRepository.save(widget);
        return serializeWidget(widget);
    }

    /**
     * Stores externally-supplied chart data directly as the widget's snapshot — for
     * {@code api_push_chart} widgets pushed through {@code /api/connect/v1}, there is nothing to
     * live-fetch, the push itself is the data. Reuses the same ownership/entitlement gates
     * {@link #createWidget} and {@link #patchWidget} already enforce.
     */
    @Transactional
    public Map<String, Object> pushWidgetData(UserEntity user, String widgetId, Object data) {
        requireSubscriberDashboard(user);
        requireSaveWidget(user);
        DashboardWidgetEntity widget = requireOwnedWidget(user.getId(), widgetId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("data", data);
        widget.setDataSnapshot(snapshot);
        widget.setLastFetchedAt(Instant.now());
        widget.setSnapshotStatus("ready");
        widget = widgetRepository.save(widget);
        return serializeWidget(widget);
    }

    @Transactional
    public Map<String, Object> patchWidget(UserEntity user, String widgetId, DashboardWidgetPatchRequest body) {
        requireSubscriberDashboard(user);
        requireSaveWidget(user);
        DashboardWidgetEntity widget = requireOwnedWidget(user.getId(), widgetId);
        if (DISALLOWED_WIDGET_TYPES.contains(widget.getWidgetType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatný typ");
        }
        if (body.getTitle() != null) {
            widget.setTitle(body.getTitle());
        }
        if (body.getDescription() != null) {
            widget.setDescription(body.getDescription());
        }
        if (body.getConfig() != null) {
            Map<String, Object> merged = mergeConfig(widget.getConfig(), body.getConfig());
            if ("computed_chart".equals(widget.getWidgetType())) {
                requireComputedWidgets(user, "computed_chart", merged);
            }
            if ("rss_monitoring".equals(widget.getWidgetType())
                    && !featureAccessService.canAccessFeature(user, "rss_monitoring")) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "RSS monitoring není v aktuálním plánu dostupný.");
            }
            widget.setConfig(merged);
        }
        if (body.getOrder() != null) {
            widget.setSortOrder(body.getOrder());
        }
        if (body.getWidth() != null) {
            widget.setWidth(body.getWidth());
        }
        if (body.isRowSpanPresent()) {
            Integer rs = body.getRowSpan();
            if (rs == null) {
                widget.setRowSpan(null);
            } else {
                if (rs < 1 || rs > 10) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "rowSpan musí být v rozsahu 1–10.");
                }
                widget.setRowSpan(rs);
            }
        }
        if (body.getPageId() != null) {
            String targetPageId = body.getPageId().strip();
            if (targetPageId.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page_id nesmí být prázdné.");
            }
            if (!targetPageId.equals(widget.getPageId())) {
                requireOwnedPage(user.getId(), targetPageId);
                long nextOrder = widgetRepository.countByUserIdAndPageId(user.getId(), targetPageId);
                widget.setPageId(targetPageId);
                widget.setSortOrder((int) nextOrder);
                Map<String, Object> cfg = new HashMap<>(widget.getConfig());
                for (String gridKey : GRID_KEYS) {
                    cfg.remove(gridKey);
                }
                widget.setConfig(cfg);
            }
        }
        widget = widgetRepository.save(widget);
        return serializeWidget(widget);
    }

    @Transactional
    public void deleteWidget(UserEntity user, String widgetId) {
        requireSubscriberDashboard(user);
        requireSaveWidget(user);
        requireOwnedWidget(user.getId(), widgetId);
        widgetRepository.deleteById(widgetId);
    }

    @Transactional
    public void reorderWidgets(UserEntity user, ReorderWidgetsRequest body) {
        requireSubscriberDashboard(user);
        requireSaveWidget(user);
        requireOwnedPage(user.getId(), body.pageId());
        Map<String, Map<String, Object>> layout =
                body.widgetLayout() != null ? body.widgetLayout() : Map.of();
        for (int i = 0; i < body.widgetIds().size(); i++) {
            String wid = body.widgetIds().get(i);
            DashboardWidgetEntity widget = widgetRepository
                    .findByIdAndUserIdAndPageId(wid, user.getId(), body.pageId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatné pořadí widgetů"));
            widget.setSortOrder(i);
            Map<String, Object> rawLayout = layout.get(wid);
            if (rawLayout != null) {
                Map<String, Object> cfg = new HashMap<>(widget.getConfig());
                for (String key : GRID_KEYS) {
                    if (!rawLayout.containsKey(key)) {
                        continue;
                    }
                    Object val = rawLayout.get(key);
                    if (val instanceof Number n) {
                        int gridVal = n.intValue();
                        if (gridVal >= 1 && gridVal <= 500) {
                            cfg.put(key, gridVal);
                        }
                    }
                }
                widget.setConfig(cfg);
            }
            widgetRepository.save(widget);
        }
    }

    @Transactional(readOnly = true)
    public PreferencesResponse getPreferences(UserEntity user) {
        UserEntity fresh = userRepository.findById(user.getId()).orElseThrow();
        return new PreferencesResponse(fresh.isOpenPersonalDashboardOnLogin(), fresh.getDefaultDashboardPageId());
    }

    @Transactional
    public MeResponse patchPreferences(UserEntity user, PreferencesPatchRequest body) {
        requireSubscriberDashboard(user);
        boolean changed = false;
        if (body.openPersonalDashboardOnLogin() != null) {
            user.setOpenPersonalDashboardOnLogin(body.openPersonalDashboardOnLogin());
            changed = true;
        }
        if (body.defaultDashboardPageId() != null) {
            String pid = body.defaultDashboardPageId();
            if (pid != null && !pid.isBlank()) {
                requireOwnedPage(user.getId(), pid);
                clearDefaultPages(user.getId());
                DashboardPageEntity page = pageRepository.findByIdAndUserId(pid, user.getId()).orElseThrow();
                page.setDefaultPage(true);
                pageRepository.save(page);
            }
            user.setDefaultDashboardPageId(pid);
            changed = true;
        }
        if (changed) {
            user = userRepository.save(user);
        }
        return userMapper.toMeResponse(userRepository.findById(user.getId()).orElseThrow());
    }

    @Transactional
    public MeResponse patchProfile(UserEntity user, ProfilePatchRequest body) {
        boolean changed = false;
        if (body.name() != null) {
            String name = body.name().strip();
            if (name.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Jméno je povinné.");
            }
            user.setName(name);
            changed = true;
        }
        if (body.company() != null) {
            String company = body.company().strip();
            user.setCompany(company.isEmpty() ? null : company);
            changed = true;
        }
        if (body.phone() != null) {
            String phone = body.phone().strip();
            user.setPhone(phone.isEmpty() ? null : phone);
            changed = true;
        }
        if (changed) {
            userRepository.save(user);
        }
        return userMapper.toMeResponse(userRepository.findById(user.getId()).orElseThrow());
    }

    @Transactional
    public void changePassword(UserEntity user, ChangePasswordRequest body) {
        UserEntity fresh = userRepository.findById(user.getId()).orElseThrow(() -> unauthorized("User not found"));
        if (!verifyPassword(body.currentPassword(), fresh.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Současné heslo není správné.");
        }
        if (verifyPassword(body.newPassword(), fresh.getPasswordHash())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Nové heslo musí být odlišné od současného.");
        }
        fresh.setPasswordHash(passwordEncoder.encode(body.newPassword()));
        userRepository.save(fresh);
    }

    @Transactional(readOnly = true)
    public NavOrderResponse getAdminNavOrder(UserEntity user) {
        requireAdmin(user);
        return new NavOrderResponse(normalizeAdminNavOrder(user.getAdminNavOrder()));
    }

    @Transactional
    public NavOrderResponse putAdminNavOrder(UserEntity user, NavOrderPutRequest body) {
        requireAdmin(user);
        for (String item : body.order()) {
            if (!ALLOWED_ADMIN_NAV_IDS.contains(item.strip())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatná položka menu: " + item);
            }
        }
        List<String> normalized = normalizeAdminNavOrder(body.order());
        user.setAdminNavOrder(normalized);
        userRepository.save(user);
        return new NavOrderResponse(normalized);
    }

    @Transactional(readOnly = true)
    public NavOrderResponse getUserNavOrder(UserEntity user) {
        return new NavOrderResponse(normalizeUserNavOrder(user.getUserNavOrder()));
    }

    @Transactional
    public NavOrderResponse putUserNavOrder(UserEntity user, NavOrderPutRequest body) {
        for (String item : body.order()) {
            if (!ALLOWED_USER_NAV_IDS.contains(item.strip())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatná položka menu: " + item);
            }
        }
        List<String> normalized = normalizeUserNavOrder(body.order());
        user.setUserNavOrder(normalized);
        userRepository.save(user);
        return new NavOrderResponse(normalized);
    }

    private boolean canAddPage(UserEntity user) {
        long count = pageRepository.countByUserId(user.getId());
        if (count == 0) {
            return true;
        }
        return featureAccessService.canAccessFeature(user, "multiple_dashboards");
    }

    private void requireSaveWidget(UserEntity user) {
        featureAccessService.requireFeature(user, "save_widget");
    }

    private void requireComputedWidgets(UserEntity user, String widgetType, Map<String, Object> config) {
        if ("computed_chart".equals(widgetType)) {
            featureAccessService.requireFeature(user, "saved_calculations");
        } else if ("uploaded_data_chart".equals(widgetType) || "user_upload_chart".equals(widgetType)) {
            featureAccessService.requireFeature(user, "upload_custom_data");
        }
    }

    private void requireSubscriberDashboard(UserEntity user) {
        featureAccessService.requireFeature(user, "personal_dashboard");
        if (!FeatureAccessService.isSubscriber(user)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Tato funkce je dostupná pro předplatitele časopisu Bankovnictví.");
        }
    }

    private void requireAdmin(UserEntity user) {
        if (!"admin".equalsIgnoreCase(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Jen pro administrátory.");
        }
    }

    private DashboardPageEntity requireOwnedPage(String userId, String pageId) {
        return pageRepository
                .findByIdAndUserId(pageId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stránka nenalezena"));
    }

    private DashboardWidgetEntity requireOwnedWidget(String userId, String widgetId) {
        return widgetRepository
                .findByIdAndUserId(widgetId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Widget nenalezen"));
    }

    private void clearDefaultPages(String userId) {
        for (DashboardPageEntity page : pageRepository.findByUserIdOrderBySortOrderAsc(userId)) {
            if (page.isDefaultPage()) {
                page.setDefaultPage(false);
                pageRepository.save(page);
            }
        }
    }

    private String uniqueSlug(String userId, String title, String excludeId) {
        String base = SlugUtils.slugify(title, "stranka");
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        Set<String> existing = new HashSet<>();
        List<DashboardPageEntity> pages = excludeId == null
                ? pageRepository.findByUserIdOrderBySortOrderAsc(userId)
                : pageRepository.findByUserIdAndIdNot(userId, excludeId);
        for (DashboardPageEntity page : pages) {
            existing.add(page.getSlug() != null ? page.getSlug() : "");
        }
        String slug = base;
        int n = 0;
        while (existing.contains(slug)) {
            n++;
            slug = base + "-" + n;
        }
        return slug;
    }

    private static String normalizeAccessMode(String raw) {
        String v = raw != null ? raw.strip().toLowerCase() : "";
        if ("invite_only".equals(v) || "invite".equals(v) || "restricted".equals(v)) {
            return "invite_only";
        }
        if ("public".equals(v)) {
            return "public";
        }
        return "owner_only";
    }

    private static String generateShareToken() {
        return IdGenerator.newToken();
    }

    private static Map<String, Object> mergeConfig(Map<String, Object> existing, Map<String, Object> patch) {
        Map<String, Object> merged = new HashMap<>();
        if (existing != null) {
            merged.putAll(existing);
        }
        if (patch != null) {
            merged.putAll(patch);
        }
        return merged;
    }

    private static List<String> normalizeAdminNavOrder(List<String> raw) {
        return normalizeNavOrder(raw, DEFAULT_ADMIN_NAV, ALLOWED_ADMIN_NAV_IDS);
    }

    private static List<String> normalizeUserNavOrder(List<String> raw) {
        return normalizeNavOrder(raw, DEFAULT_USER_NAV, ALLOWED_USER_NAV_IDS);
    }

    private static List<String> normalizeNavOrder(List<String> raw, List<String> defaults, Set<String> allowed) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>(defaults);
        }
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String item : raw) {
            String s = item != null ? item.strip() : "";
            if (allowed.contains(s) && seen.add(s)) {
                out.add(s);
            }
        }
        for (String item : defaults) {
            if (!seen.contains(item)) {
                out.add(item);
            }
        }
        return out;
    }

    private boolean verifyPassword(String plain, String hash) {
        return hash != null && hash.startsWith("$2") && passwordEncoder.matches(plain, hash);
    }

    private static ResponseStatusException unauthorized(String detail) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, detail);
    }

    public Map<String, Object> serializePage(DashboardPageEntity page, boolean forOwner) {
        String mode = normalizeAccessMode(page.getAccessMode());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", page.getId());
        map.put("user_id", page.getUserId());
        map.put("title", page.getTitle() != null ? page.getTitle() : "");
        map.put("slug", page.getSlug() != null ? page.getSlug() : "");
        map.put("order", page.getSortOrder());
        map.put("is_default", page.isDefaultPage());
        map.put("created_at", instantToString(page.getCreatedAt()));
        map.put("updated_at", instantToString(page.getUpdatedAt()));
        map.put("access_mode", mode);
        map.put("allowed_user_ids", forOwner ? nullToEmpty(page.getAllowedUserIds()) : List.of());
        map.put("share_enabled", forOwner && page.isShareEnabled());
        map.put("allow_viewer_compare", page.isAllowViewerCompare());
        map.put("allow_embed", page.isAllowEmbed());
        String shareToken = page.getShareToken() != null ? page.getShareToken().strip() : "";
        if (forOwner
                && !shareToken.isEmpty()
                && (("invite_only".equals(mode) && page.isShareEnabled()) || page.isAllowEmbed())) {
            map.put("share_token", shareToken);
        }
        return map;
    }

    public Map<String, Object> serializeWidget(DashboardWidgetEntity widget) {
        Map<String, Object> cfg = widget.getConfig() != null ? widget.getConfig() : Map.of();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", widget.getId());
        map.put("user_id", widget.getUserId());
        map.put("page_id", widget.getPageId());
        map.put("type", widget.getWidgetType());
        map.put("engine_type", widget.getWidgetType());
        map.put("title", widget.getTitle() != null ? widget.getTitle() : "");
        map.put("description", widget.getDescription() != null ? widget.getDescription() : "");
        map.put("config", cfg);
        map.put("lock_source_data", Boolean.TRUE.equals(cfg.get("lock_source_data")));
        map.put("width", widget.getWidth() != null ? widget.getWidth() : "full");
        map.put("rowSpan", widget.getRowSpan());
        map.put("order", widget.getSortOrder());
        map.put("created_at", instantToString(widget.getCreatedAt()));
        map.put("updated_at", instantToString(widget.getUpdatedAt()));
        if (widget.getDataSnapshot() != null) {
            map.put("data_snapshot", widget.getDataSnapshot());
            map.put("last_fetched_at", instantToString(widget.getLastFetchedAt()));
            map.put("expires_at", instantToString(widget.getExpiresAt()));
            map.put("cache_key", widget.getCacheKey());
            map.put("snapshot_status", widget.getSnapshotStatus());
        }
        return map;
    }

    private static List<String> nullToEmpty(List<String> list) {
        return list != null ? list : List.of();
    }

    private static String instantToString(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreferencesResponse(
            @JsonProperty("open_personal_dashboard_on_login") boolean openPersonalDashboardOnLogin,
            @JsonProperty("default_dashboard_page_id") String defaultDashboardPageId) {}

    public record NavOrderResponse(List<String> order) {}
}
