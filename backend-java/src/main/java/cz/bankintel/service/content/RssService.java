package cz.bankintel.service.content;

import cz.bankintel.domain.dto.ContentDtos;
import cz.bankintel.domain.dto.ContentDtos.RssFeedCreateRequest;
import cz.bankintel.domain.dto.ContentDtos.RssFeedPatchRequest;
import cz.bankintel.domain.dto.ContentDtos.RssFeedResponse;
import cz.bankintel.domain.dto.ContentDtos.RssItemResponse;
import cz.bankintel.domain.entity.RssFeedEntity;
import cz.bankintel.domain.entity.RssItemEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.RssFeedRepository;
import cz.bankintel.repository.RssItemRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.util.IdGenerator;
import cz.bankintel.util.RoleUtils;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class RssService {

    private static final String RSS_FEATURE = "rss_monitoring";
    private static final String MSG_RSS_FEATURE_UNAVAILABLE =
            "RSS monitoring není pro váš účet dostupný.";

    private final RssFeedRepository feedRepository;
    private final RssItemRepository itemRepository;
    private final FeatureAccessService featureAccessService;

    public List<RssFeedResponse> listFeeds(UserEntity user) {
        requireRssAccess(user);
        List<RssFeedEntity> feeds =
                RoleUtils.isAdminRole(user.getRole()) ? feedRepository.findAllForAdmin() : feedRepository.findVisibleForUser(user.getId());
        return feeds.stream().map(this::toFeedResponse).toList();
    }

    @Transactional
    public RssFeedResponse createFeed(UserEntity user, RssFeedCreateRequest body) {
        requireRssAccess(user);
        if ("global".equals(body.scope()) && !RoleUtils.isAdminRole(user.getRole())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Globální RSS zdroje může spravovat pouze administrátor.");
        }
        if (body.autoTranslate() && !RoleUtils.isAdminRole(user.getRole())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Automatický překlad může zapnout pouze administrátor.");
        }
        if (body.publishToArticles() && !RoleUtils.isAdminRole(user.getRole())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Zakládání návrhů zpráv může zapnout pouze administrátor.");
        }

        Instant now = Instant.now();
        RssFeedEntity entity = new RssFeedEntity();
        entity.setId(IdGenerator.newId());
        entity.setOwnerUserId("global".equals(body.scope()) ? null : user.getId());
        entity.setScope(body.scope());
        entity.setName(body.name().trim());
        entity.setUrl(body.url().trim());
        entity.setSourceType(body.sourceType() != null ? body.sourceType() : "rss");
        entity.setCategory(body.category() == null ? "" : body.category().trim());
        entity.setEnabled(body.enabled());
        entity.setRefreshIntervalMinutes(body.refreshIntervalMinutes() > 0 ? body.refreshIntervalMinutes() : 60);
        entity.setAutoTranslate(body.autoTranslate());
        entity.setPublishToArticles(body.publishToArticles());
        entity.setLastSyncMessage("");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toFeedResponse(feedRepository.save(entity));
    }

    @Transactional
    public RssFeedResponse patchFeed(UserEntity user, String feedId, RssFeedPatchRequest body) {
        requireRssAccess(user);
        RssFeedEntity feed = findFeed(feedId);
        assertCanManageFeed(user, feed);

        if (body.name() != null) {
            feed.setName(body.name().trim());
        }
        if (body.category() != null) {
            feed.setCategory(body.category().trim());
        }
        if (body.enabled() != null) {
            feed.setEnabled(body.enabled());
        }
        if (body.refreshIntervalMinutes() != null) {
            feed.setRefreshIntervalMinutes(body.refreshIntervalMinutes());
        }
        if (body.autoTranslate() != null) {
            if (body.autoTranslate() && !RoleUtils.isAdminRole(user.getRole())) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Automatický překlad může zapnout pouze administrátor.");
            }
            feed.setAutoTranslate(body.autoTranslate());
        }
        if (body.publishToArticles() != null) {
            if (body.publishToArticles() && !RoleUtils.isAdminRole(user.getRole())) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Zakládání návrhů zpráv může zapnout pouze administrátor.");
            }
            feed.setPublishToArticles(body.publishToArticles());
        }
        if (body.sourceType() != null) {
            feed.setSourceType(body.sourceType());
        }
        if (body.url() != null) {
            feed.setUrl(body.url().trim());
        }
        return toFeedResponse(feedRepository.save(feed));
    }

    @Transactional
    public void deleteFeed(UserEntity user, String feedId) {
        requireRssAccess(user);
        RssFeedEntity feed = findFeed(feedId);
        assertCanManageFeed(user, feed);
        itemRepository.deleteByFeedId(feedId);
        feedRepository.delete(feed);
    }

    public List<RssItemResponse> listItems(
            UserEntity user, String feedId, String category, String q, int limit, Integer days) {
        requireRssAccess(user);
        List<String> allowed = enabledReadableFeedIds(user);
        if (allowed.isEmpty()) {
            return List.of();
        }

        List<String> feedFilter = new ArrayList<>(allowed);
        if (RoleUtils.isAdminRole(user.getRole())) {
            feedFilter = feedRepository.findAll().stream().map(RssFeedEntity::getId).toList();
        }
        if (feedId != null && !feedId.isBlank()) {
            if (!RoleUtils.isAdminRole(user.getRole()) && !allowed.contains(feedId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, MSG_RSS_FEATURE_UNAVAILABLE);
            }
            if (!feedFilter.contains(feedId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Feed nenalezen");
            }
            feedFilter = List.of(feedId);
        }

        // PostgreSQL neumí u `:param IS NULL` odvodit typ nenaplněného parametru a celý dotaz
        // shodí na „could not determine data type". Protože `category` i `search` jsou při běžném
        // volání prázdné, padalo /api/rss/items VŽDYCKY — výpis položek tedy nefungoval nikomu.
        // Místo null se proto posílají hodnoty, které dotaz už umí vyhodnotit jako „bez filtru".
        Instant cutoff = days != null ? Instant.now().minus(days, ChronoUnit.DAYS) : Instant.EPOCH;
        int cappedLimit = Math.min(Math.max(limit, 1), 100);
        String cat = category == null || category.isBlank() ? "" : category.trim();
        String search = q == null || q.isBlank() ? "" : q.trim();
        return itemRepository
                .findFiltered(feedFilter, cat, cutoff, search, PageRequest.of(0, cappedLimit))
                .stream()
                .map(this::toItemResponse)
                .toList();
    }

    private void requireRssAccess(UserEntity user) {
        if (RoleUtils.isAdminRole(user.getRole())) {
            return;
        }
        if (!featureAccessService.canAccessFeature(user, RSS_FEATURE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, MSG_RSS_FEATURE_UNAVAILABLE);
        }
    }

    private List<String> enabledReadableFeedIds(UserEntity user) {
        if (RoleUtils.isAdminRole(user.getRole())) {
            return feedRepository.findAll().stream().map(RssFeedEntity::getId).toList();
        }
        return feedRepository.findEnabledReadableIds(user.getId());
    }

    private void assertCanManageFeed(UserEntity user, RssFeedEntity feed) {
        if (RoleUtils.isAdminRole(user.getRole())) {
            return;
        }
        if ("global".equals(feed.getScope())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nemáte oprávnění upravovat tento zdroj.");
        }
        if (!user.getId().equals(feed.getOwnerUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nemáte oprávnění upravovat tento zdroj.");
        }
    }

    private RssFeedEntity findFeed(String feedId) {
        return feedRepository
                .findById(feedId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Feed nenalezen"));
    }

    private RssFeedResponse toFeedResponse(RssFeedEntity entity) {
        return new RssFeedResponse(
                entity.getId(),
                entity.getOwnerUserId(),
                entity.getScope(),
                entity.getName(),
                entity.getUrl(),
                entity.getSourceType(),
                entity.getCategory(),
                entity.isEnabled(),
                entity.getRefreshIntervalMinutes(),
                entity.isAutoTranslate(),
                entity.isPublishToArticles(),
                formatInstant(entity.getLastSyncAt()),
                entity.getLastSyncStatus(),
                entity.getLastSyncMessage(),
                formatInstant(entity.getCreatedAt()),
                formatInstant(entity.getUpdatedAt()));
    }

    private RssItemResponse toItemResponse(RssItemEntity entity) {
        return new RssItemResponse(
                entity.getId(),
                entity.getFeedId(),
                entity.getOwnerUserId(),
                entity.getTitle(),
                entity.getSummary(),
                entity.getLink(),
                entity.getGuid(),
                entity.getAuthor(),
                entity.getSourceName(),
                entity.getCategory(),
                entity.getTitleCs(),
                entity.getSummaryCs(),
                entity.getDraftArticleId(),
                formatInstant(entity.getPublishedAt()),
                formatInstant(entity.getCreatedAt()),
                formatInstant(entity.getUpdatedAt()));
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
