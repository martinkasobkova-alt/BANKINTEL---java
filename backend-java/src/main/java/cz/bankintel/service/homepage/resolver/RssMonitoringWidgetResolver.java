package cz.bankintel.service.homepage.resolver;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.RssFeedRepository;
import cz.bankintel.repository.RssItemRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.content.RssService;
import cz.bankintel.util.RoleUtils;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Widget {@code rss_monitoring} — položky z RSS feedů uživatele. */
@Component
@RequiredArgsConstructor
public class RssMonitoringWidgetResolver {

    private static final String RSS_FEATURE = "rss_monitoring";

    private final RssFeedRepository feedRepository;
    private final RssItemRepository itemRepository;
    private final FeatureAccessService featureAccessService;

    public Map<String, Object> resolve(Map<String, Object> cfg, UserEntity user) {
        if (user == null) {
            return Map.of("error", "RSS monitoring vyžaduje přihlášení.");
        }
        if (!RoleUtils.isAdminRole(user.getRole()) && !featureAccessService.canAccessFeature(user, RSS_FEATURE)) {
            return Map.of(
                    "error",
                    "RSS monitoring není pro váš účet dostupný.",
                    "feature_lock",
                    RSS_FEATURE);
        }
        List<String> allowed = enabledFeedIds(user);
        List<String> selected = parseSelectedFeeds(cfg, allowed);
        int itemLimit = parseItemLimit(cfg.get("item_limit"));
        Integer days = parseDays(cfg.get("days"));
        Instant cutoff = days != null ? Instant.now().minus(days, ChronoUnit.DAYS) : null;
        String search = str(cfg.get("q")).isBlank() ? str(cfg.get("query")) : str(cfg.get("q"));
        String category = str(cfg.get("categories")).isBlank() ? null : str(cfg.get("categories"));

        List<Map<String, Object>> items = itemRepository
                .findFiltered(selected, category, cutoff, search.isBlank() ? null : search, PageRequest.of(0, itemLimit))
                .stream()
                .map(item -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", item.getId());
                    row.put("feed_id", item.getFeedId());
                    row.put("title", item.getTitle());
                    row.put("summary", item.getSummary());
                    row.put("link", item.getLink());
                    row.put("published_at", item.getPublishedAt() != null ? item.getPublishedAt().toString() : null);
                    row.put("source_name", item.getSourceName());
                    row.put("category", item.getCategory());
                    return row;
                })
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("view", "rss");
        out.put("items", items);
        out.put("item_count", items.size());
        out.put("selected_feed_ids", selected);
        return out;
    }

    private List<String> enabledFeedIds(UserEntity user) {
        if (RoleUtils.isAdminRole(user.getRole())) {
            return feedRepository.findAll().stream().map(f -> f.getId()).toList();
        }
        return feedRepository.findEnabledReadableIds(user.getId());
    }

    private static List<String> parseSelectedFeeds(Map<String, Object> cfg, List<String> allowed) {
        Object raw = cfg.get("selected_feed_ids");
        if (!(raw instanceof List<?> list)) {
            return allowed;
        }
        List<String> selected = new ArrayList<>();
        for (Object item : list) {
            String id = String.valueOf(item).trim();
            if (!id.isBlank() && allowed.contains(id)) {
                selected.add(id);
            }
        }
        if (selected.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatný nebo prázdný výběr RSS zdrojů.");
        }
        return selected;
    }

    private static int parseItemLimit(Object raw) {
        try {
            int val = raw == null ? 20 : Integer.parseInt(String.valueOf(raw));
            return Math.max(1, Math.min(val, 50));
        } catch (NumberFormatException ex) {
            return 20;
        }
    }

    private static Integer parseDays(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(raw)));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).strip() : "";
    }
}
