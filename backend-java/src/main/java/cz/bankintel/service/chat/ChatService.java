package cz.bankintel.service.chat;

import cz.bankintel.domain.dto.ChatDtos.ConversationInviteRequest;
import cz.bankintel.domain.dto.ChatDtos.ConversationPatchRequest;
import cz.bankintel.domain.dto.ChatDtos.DirectConversationCreateRequest;
import cz.bankintel.domain.dto.ChatDtos.GroupConversationCreateRequest;
import cz.bankintel.domain.dto.ChatDtos.MessageCreateRequest;
import cz.bankintel.domain.dto.ChatDtos.UnreadCountResponse;
import cz.bankintel.domain.entity.ChatAttachmentEntity;
import cz.bankintel.domain.entity.UserEntity;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatConversationService conversationService;
    private final ChatMessageService messageService;
    private final ChatUserLookupService userLookupService;
    private final ChatAttachmentStorageService attachmentStorageService;
    private final ChatPayloadMapper payloadMapper;

    public List<Map<String, Object>> searchUsers(UserEntity actor, String query, int limit) {
        return userLookupService.searchUsers(actor.getId(), query, limit);
    }

    public List<Map<String, Object>> listConversations(UserEntity actor) {
        return conversationService.listConversations(actor);
    }

    public UnreadCountResponse unreadCount(UserEntity actor) {
        return new UnreadCountResponse(messageService.totalUnreadCount(actor.getId()));
    }

    public Map<String, Object> createDirect(UserEntity actor, DirectConversationCreateRequest body) {
        return conversationService.createOrGetDirect(actor, body);
    }

    public Map<String, Object> createGroup(UserEntity actor, GroupConversationCreateRequest body) {
        return conversationService.createGroup(actor, body);
    }

    public Map<String, Object> inviteUser(UserEntity actor, String conversationId, ConversationInviteRequest body) {
        return conversationService.inviteUser(actor, conversationId, body);
    }

    public Map<String, Object> patchConversation(
            UserEntity actor, String conversationId, ConversationPatchRequest body) {
        return conversationService.patchTitle(actor, conversationId, body);
    }

    public Map<String, Object> removeParticipant(
            UserEntity actor, String conversationId, String participantUserId) {
        return conversationService.removeParticipant(actor, conversationId, participantUserId);
    }

    public List<Map<String, Object>> listMessages(
            UserEntity actor, String conversationId, int limit, String before) {
        return messageService.listMessages(actor, conversationId, limit, before);
    }

    public Map<String, Object> sendMessage(UserEntity actor, String conversationId, MessageCreateRequest body) {
        return messageService.sendMessage(actor, conversationId, body);
    }

    public Map<String, Object> markRead(UserEntity actor, String conversationId) {
        return messageService.markRead(actor, conversationId);
    }

    public Map<String, Object> uploadAttachment(UserEntity actor, String conversationId, MultipartFile file) {
        ChatAttachmentEntity entity = attachmentStorageService.upload(conversationId, actor.getId(), file);
        return payloadMapper.attachmentSummary(entity);
    }

    public ResponseEntity<Resource> downloadAttachment(UserEntity actor, String attachmentId) {
        return attachmentStorageService.download(attachmentId, actor.getId());
    }
}
