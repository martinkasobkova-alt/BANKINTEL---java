package cz.bankintel.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public final class ChatDtos {

    private ChatDtos() {}

    public record DirectConversationCreateRequest(
            @NotBlank @Size(max = 128) @JsonProperty("user_id") String userId) {}

    public record GroupConversationCreateRequest(
            @JsonProperty("participant_ids") List<String> participantIds,
            @Size(max = 160) String title,
            @Size(max = 4000) @JsonProperty("initial_message") String initialMessage) {}

    public record ConversationInviteRequest(
            @NotBlank @Size(max = 128) @JsonProperty("user_id") String userId) {}

    public record ConversationPatchRequest(@Size(max = 160) String title) {}

    public record MessageCreateRequest(
            @Size(max = 5000) String text,
            @JsonProperty("attachment_ids") List<String> attachmentIds,
            @JsonProperty("shared_chart") Map<String, Object> sharedChart) {}

    public record ReadReceiptResponse(boolean ok, @JsonProperty("read_at") String readAt) {}

    public record UnreadCountResponse(@JsonProperty("unread_count") long unreadCount) {}
}
