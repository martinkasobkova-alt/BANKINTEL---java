package cz.bankintel.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public final class MagazineDtos {

    private MagazineDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MagazineResponse(
            String id,
            String title,
            String slug,
            String description,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt,
            @JsonProperty("issue_count") int issueCount,
            @JsonProperty("ready_issue_count") int readyIssueCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MagazineIssueResponse(
            String id,
            @JsonProperty("magazine_id") String magazineId,
            @JsonProperty("issue_label") String issueLabel,
            String title,
            String description,
            @JsonProperty("cover_image_url") String coverImageUrl,
            @JsonProperty("published_at") String publishedAt,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("ingest_status") String ingestStatus,
            @JsonProperty("ingest_error") String ingestError,
            @JsonProperty("page_count") int pageCount,
            @JsonProperty("chunk_count") int chunkCount,
            int size) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MagazineIssueDetailResponse(
            String id,
            @JsonProperty("magazine_id") String magazineId,
            @JsonProperty("issue_label") String issueLabel,
            String title,
            String description,
            @JsonProperty("cover_image_url") String coverImageUrl,
            @JsonProperty("published_at") String publishedAt,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("ingest_status") String ingestStatus,
            @JsonProperty("ingest_error") String ingestError,
            @JsonProperty("page_count") int pageCount,
            @JsonProperty("chunk_count") int chunkCount,
            int size,
            Map<String, String> magazine) {}

    public record MagazineCreateRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 120) String slug,
            @Size(max = 4000) String description) {}

    public record MagazineIssueUpdateRequest(
            @Size(max = 240) String title,
            @JsonProperty("published_at") @Size(max = 80) String publishedAt,
            @Size(max = 4000) String description,
            @JsonProperty("cover_image_url") @Size(max = 2048) String coverImageUrl) {}

    public record MagazineAiSearchRequest(
            @NotBlank @Size(min = 2, max = 500) String query,
            @JsonProperty("magazine_id") @Size(max = 36) String magazineId,
            @JsonProperty("issue_id") @Size(max = 36) String issueId,
            @Min(1) @Max(30) int limit) {
        public MagazineAiSearchRequest {
            if (limit == 0) {
                limit = 8;
            }
        }
    }

    public record MagazineAiChatRequest(
            @NotBlank @Size(min = 2, max = 1200) String query,
            @JsonProperty("magazine_id") @Size(max = 36) String magazineId,
            @JsonProperty("issue_id") @Size(max = 36) String issueId,
            @Min(1) Integer page,
            @JsonProperty("top_k") @Min(3) @Max(30) int topK,
            @JsonProperty("conversation_history") List<Map<String, String>> conversationHistory) {
        public MagazineAiChatRequest {
            if (topK == 0) {
                topK = 8;
            }
            if (conversationHistory == null) {
                conversationHistory = List.of();
            }
        }
    }

    public record MagazinePdfLinkCreateRequest(
            @Min(1) @Max(9999) int page,
            @NotBlank @Size(max = 240) String label,
            @JsonProperty("anchor_text") @Size(max = 2000) String anchorText,
            @JsonProperty("link_kind") @Size(max = 16) String linkKind,
            List<Double> bbox,
            @JsonProperty("target_kind") @Size(max = 16) String targetKind,
            @JsonProperty("target_title") @Size(max = 240) String targetTitle,
            @JsonProperty("source_type") @Size(max = 80) String sourceType,
            @JsonProperty("set_id") @Size(max = 240) String setId,
            @JsonProperty("link_url") @Size(max = 4000) String linkUrl) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MagazinePdfLinkResponse(
            String id,
            @JsonProperty("issue_id") String issueId,
            @JsonProperty("magazine_id") String magazineId,
            int page,
            String label,
            @JsonProperty("anchor_text") String anchorText,
            @JsonProperty("link_kind") String linkKind,
            List<Double> bbox,
            @JsonProperty("target_kind") String targetKind,
            @JsonProperty("target_title") String targetTitle,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("set_id") String setId,
            @JsonProperty("link_url") String linkUrl,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchHitIssue(
            String id,
            @JsonProperty("issue_label") String issueLabel,
            String title,
            @JsonProperty("magazine_id") String magazineId,
            @JsonProperty("magazine_title") String magazineTitle) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchHit(
            @JsonProperty("chunk_id") String chunkId,
            double score,
            int page,
            String snippet,
            @JsonProperty("text_full") String textFull,
            SearchHitIssue issue) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchResponse(
            String query,
            @JsonProperty("magazine_id") String magazineId,
            List<SearchHit> hits,
            String message) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AiChatCitation(
            String id,
            @JsonProperty("issue_id") String issueId,
            @JsonProperty("issue_label") String issueLabel,
            @JsonProperty("magazine_id") String magazineId,
            @JsonProperty("magazine_title") String magazineTitle,
            int page,
            String snippet) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AiChatResponse(String answer, List<AiChatCitation> citations, String message) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IssueLinksResponse(
            @JsonProperty("issue_id") String issueId,
            Integer page,
            List<MagazinePdfLinkResponse> links) {}

    public static Map<String, Boolean> okMap() {
        return Map.of("ok", true);
    }
}
