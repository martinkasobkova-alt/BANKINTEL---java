package cz.bankintel.controller.content;

import cz.bankintel.domain.dto.ContentDtos;
import cz.bankintel.domain.dto.ContentDtos.RssFeedCreateRequest;
import cz.bankintel.domain.dto.ContentDtos.RssFeedPatchRequest;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.service.content.RssFeedSyncService;
import cz.bankintel.service.content.RssService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/rss")
@RequiredArgsConstructor
public class RssController {

    private final RssService rssService;
    private final RssFeedSyncService rssFeedSyncService;
    private final CurrentUser currentUser;

    @GetMapping("/feeds")
    public List<ContentDtos.RssFeedResponse> listFeeds() {
        return rssService.listFeeds(currentUser.requireUserEntity());
    }

    @PostMapping("/feeds")
    public ContentDtos.RssFeedResponse createFeed(@Valid @RequestBody RssFeedCreateRequest body) {
        return rssService.createFeed(currentUser.requireUserEntity(), body);
    }

    @PatchMapping("/feeds/{feedId}")
    public ContentDtos.RssFeedResponse patchFeed(
            @PathVariable String feedId, @Valid @RequestBody RssFeedPatchRequest body) {
        return rssService.patchFeed(currentUser.requireUserEntity(), feedId, body);
    }

    @DeleteMapping("/feeds/{feedId}")
    public Map<String, Boolean> deleteFeed(@PathVariable String feedId) {
        rssService.deleteFeed(currentUser.requireUserEntity(), feedId);
        return ContentDtos.okMap();
    }

    @PostMapping("/feeds/{feedId}/validate")
    public Map<String, Object> validateFeed(@PathVariable String feedId) {
        return rssFeedSyncService.validateFeed(currentUser.requireUserEntity(), feedId);
    }

    @PostMapping("/feeds/{feedId}/sync")
    public Map<String, Object> syncFeed(@PathVariable String feedId) {
        return rssFeedSyncService.syncFeed(currentUser.requireUserEntity(), feedId);
    }

    @GetMapping("/items")
    public List<ContentDtos.RssItemResponse> listItems(
            @RequestParam(name = "feed_id", required = false) String feedId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Integer days) {
        return rssService.listItems(currentUser.requireUserEntity(), feedId, category, q, limit, days);
    }
}
