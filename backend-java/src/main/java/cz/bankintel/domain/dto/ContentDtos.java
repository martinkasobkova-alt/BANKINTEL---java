package cz.bankintel.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public final class ContentDtos {

    private ContentDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArticleCategoryResponse(
            String id,
            String name,
            String slug,
            @JsonProperty("order") int order,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt) {}

    public record ArticleCategoryCreateRequest(@NotBlank @Size(max = 120) String name) {}

    public record ArticleCategoryUpdateRequest(@Size(min = 1, max = 120) String name) {}

    public record ArticleCategoriesReorderBody(
            @NotEmpty @Size(max = 64) @JsonProperty("category_ids") List<String> categoryIds) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArticleListResponse(
            String id,
            String slug,
            String title,
            String summary,
            @JsonProperty("cover_image_url") String coverImageUrl,
            boolean published,
            @JsonProperty("published_at") String publishedAt,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt,
            @JsonProperty("author_name") String authorName,
            @JsonProperty("category_id") String categoryId,
            @JsonProperty("category_name") String categoryName,
            @JsonProperty("category_slug") String categorySlug) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArticleDetailResponse(
            String id,
            String slug,
            String title,
            String summary,
            String body,
            @JsonProperty("cover_image_url") String coverImageUrl,
            boolean published,
            @JsonProperty("published_at") String publishedAt,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt,
            @JsonProperty("author_name") String authorName,
            @JsonProperty("category_id") String categoryId,
            @JsonProperty("category_name") String categoryName,
            @JsonProperty("category_slug") String categorySlug) {}

    public record ArticleCreateRequest(
            @NotBlank @Size(max = 240) String title,
            @Size(max = 120) String slug,
            @Size(max = 600) String summary,
            @NotBlank @Size(max = 120_000) String body,
            @JsonProperty("cover_image_url") @Size(max = 2048) String coverImageUrl,
            @JsonProperty("category_id") @Size(max = 64) String categoryId,
            boolean published) {}

    public record ArticleUpdateRequest(
            @Size(min = 1, max = 240) String title,
            @Size(max = 120) String slug,
            @Size(max = 600) String summary,
            @Size(max = 120_000) String body,
            @JsonProperty("cover_image_url") @Size(max = 2048) String coverImageUrl,
            @JsonProperty("category_id") @Size(max = 64) String categoryId,
            Boolean published) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RssFeedResponse(
            String id,
            @JsonProperty("owner_user_id") String ownerUserId,
            String scope,
            String name,
            String url,
            @JsonProperty("source_type") String sourceType,
            String category,
            boolean enabled,
            @JsonProperty("refresh_interval_minutes") int refreshIntervalMinutes,
            @JsonProperty("auto_translate") boolean autoTranslate,
            @JsonProperty("publish_to_articles") boolean publishToArticles,
            @JsonProperty("last_sync_at") String lastSyncAt,
            @JsonProperty("last_sync_status") String lastSyncStatus,
            @JsonProperty("last_sync_message") String lastSyncMessage,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt) {}

    public record RssFeedCreateRequest(
            @NotBlank @Pattern(regexp = "global|user") String scope,
            @NotBlank @Size(max = 500) String name,
            @NotBlank @Size(max = 4000) String url,
            @Size(max = 200) String category,
            boolean enabled,
            @JsonProperty("refresh_interval_minutes") @Min(5) @Max(1440) int refreshIntervalMinutes,
            @JsonProperty("auto_translate") boolean autoTranslate,
            @JsonProperty("publish_to_articles") boolean publishToArticles,
            @JsonProperty("source_type") @Pattern(regexp = "rss|html_scrape") String sourceType) {}

    public record RssFeedPatchRequest(
            @Size(min = 1, max = 500) String name,
            @Size(min = 1, max = 4000) String url,
            @Size(max = 200) String category,
            Boolean enabled,
            @JsonProperty("refresh_interval_minutes") @Min(5) @Max(1440) Integer refreshIntervalMinutes,
            @JsonProperty("auto_translate") Boolean autoTranslate,
            @JsonProperty("publish_to_articles") Boolean publishToArticles,
            @JsonProperty("source_type") @Pattern(regexp = "rss|html_scrape") String sourceType) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RssItemResponse(
            String id,
            @JsonProperty("feed_id") String feedId,
            @JsonProperty("owner_user_id") String ownerUserId,
            String title,
            String summary,
            String link,
            String guid,
            String author,
            @JsonProperty("source_name") String sourceName,
            String category,
            @JsonProperty("title_cs") String titleCs,
            @JsonProperty("summary_cs") String summaryCs,
            @JsonProperty("draft_article_id") String draftArticleId,
            @JsonProperty("published_at") String publishedAt,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PodcastShowResponse(
            String id,
            String title,
            String description,
            @JsonProperty("sort_order") int sortOrder,
            @JsonProperty("episode_count") int episodeCount,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt) {}

    public record PodcastShowCreateRequest(
            @NotBlank String title,
            String description,
            @JsonProperty("sort_order") int sortOrder) {}

    public record PodcastShowUpdateRequest(String title, String description, @JsonProperty("sort_order") Integer sortOrder) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PodcastEpisodeResponse(
            String id,
            String title,
            String summary,
            @JsonProperty("audio_url") String audioUrl,
            @JsonProperty("external_url") String externalUrl,
            @JsonProperty("page_url") String pageUrl,
            @JsonProperty("show_id") String showId,
            @JsonProperty("show_title") String showTitle,
            @JsonProperty("feed_title") String feedTitle,
            String author,
            @JsonProperty("published_at") String publishedAt,
            String source,
            @JsonProperty("play_mode") String playMode,
            @JsonProperty("can_manage") boolean canManage,
            @JsonProperty("cover_image_url") String coverImageUrl) {}

    public record PodcastEpisodeListResponse(@JsonProperty("items") List<PodcastEpisodeResponse> items) {}

    public record PodcastShowListResponse(@JsonProperty("items") List<PodcastShowResponse> items) {}

    public record OkResponse(boolean ok) {}

    public static Map<String, Boolean> okMap() {
        return Map.of("ok", true);
    }
}
