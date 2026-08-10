package cz.bankintel.service.chat;

import cz.bankintel.domain.entity.ChatAttachmentEntity;
import cz.bankintel.domain.entity.ChatConversationEntity;
import cz.bankintel.domain.entity.ChatMessageEntity;
import cz.bankintel.domain.entity.ChatParticipantEntity;
import cz.bankintel.domain.entity.UserEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatPayloadMapper {

    private final ChatUserLookupService userLookupService;

    public Map<String, Object> conversationPayload(
            ChatConversationEntity conv,
            List<ChatParticipantEntity> participants,
            long unreadCount,
            Map<String, Map<String, Object>> userMap) {
        List<String> participantIds =
                participants.stream().map(ChatParticipantEntity::getUserId).sorted().toList();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", conv.getId());
        row.put("type", conv.getType() != null ? conv.getType() : "group");
        row.put("title", conv.getTitle());
        row.put("participant_ids", participantIds);
        row.put(
                "participants",
                participantIds.stream()
                        .map(uid -> userMap.getOrDefault(uid, userLookupService.fallbackUser(uid)))
                        .toList());
        row.put("last_message_preview", conv.getLastMessagePreview() != null ? conv.getLastMessagePreview() : "");
        row.put("last_message_at", instantToString(conv.getLastMessageAt()));
        row.put("unread_count", unreadCount);
        row.put("updated_at", instantToString(conv.getUpdatedAt()));
        row.put("created_at", instantToString(conv.getCreatedAt()));
        row.put("created_by", conv.getCreatedBy());
        return row;
    }

    public Map<String, Object> messagePayload(
            ChatMessageEntity msg,
            Map<String, Map<String, Object>> userMap,
            Map<String, ChatAttachmentEntity> attachmentMap) {
        String senderId = msg.getSenderId() != null ? msg.getSenderId() : "";
        List<String> attachmentIds =
                msg.getAttachmentIds() == null
                        ? List.of()
                        : msg.getAttachmentIds().stream().filter(Objects::nonNull).map(String::valueOf).toList();
        List<Map<String, Object>> attachments = new ArrayList<>();
        for (String aid : attachmentIds) {
            ChatAttachmentEntity att = attachmentMap.get(aid);
            if (att != null) {
                attachments.add(attachmentSummary(att));
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", msg.getId());
        row.put("conversation_id", msg.getConversationId());
        row.put("sender_id", senderId);
        row.put("sender", userMap.getOrDefault(senderId, userLookupService.fallbackUser(senderId)));
        row.put("text", msg.getText() != null ? msg.getText() : "");
        row.put("attachment_ids", attachmentIds);
        row.put("attachments", attachments);
        row.put("shared_chart", msg.getSharedChart() instanceof Map<?, ?> ? msg.getSharedChart() : null);
        row.put("created_at", instantToString(msg.getCreatedAt()));
        return row;
    }

    public Map<String, Object> attachmentSummary(ChatAttachmentEntity att) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", att.getId());
        row.put("file_name", att.getFileName());
        row.put("content_type", att.getContentType());
        row.put("size", att.getSize());
        row.put("created_at", instantToString(att.getCreatedAt()));
        return row;
    }

    public Map<String, Object> sentMessagePayload(
            ChatMessageEntity msg,
            UserEntity sender,
            List<ChatAttachmentEntity> attachments) {
        Map<String, Map<String, Object>> userMap = Map.of(sender.getId(), userLookupService.toSummary(sender));
        Map<String, ChatAttachmentEntity> attachmentMap = new LinkedHashMap<>();
        for (ChatAttachmentEntity att : attachments) {
            attachmentMap.put(att.getId(), att);
        }
        return messagePayload(msg, userMap, attachmentMap);
    }

    private static String instantToString(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
