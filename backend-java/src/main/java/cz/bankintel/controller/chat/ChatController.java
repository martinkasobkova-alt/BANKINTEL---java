package cz.bankintel.controller.chat;

import cz.bankintel.domain.dto.ChatDtos.ConversationInviteRequest;
import cz.bankintel.domain.dto.ChatDtos.ConversationPatchRequest;
import cz.bankintel.domain.dto.ChatDtos.DirectConversationCreateRequest;
import cz.bankintel.domain.dto.ChatDtos.GroupConversationCreateRequest;
import cz.bankintel.domain.dto.ChatDtos.MessageCreateRequest;
import cz.bankintel.domain.dto.ChatDtos.UnreadCountResponse;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.service.chat.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final CurrentUser currentUser;
    private final ChatService chatService;

    @GetMapping("/users")
    public List<Map<String, Object>> searchUsers(
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "20") int limit) {
        return chatService.searchUsers(currentUser.requireUserEntity(), q, limit);
    }

    @GetMapping("/conversations")
    public List<Map<String, Object>> listConversations() {
        return chatService.listConversations(currentUser.requireUserEntity());
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount() {
        return chatService.unreadCount(currentUser.requireUserEntity());
    }

    @PostMapping("/conversations/direct")
    public Map<String, Object> createDirect(@Valid @RequestBody DirectConversationCreateRequest body) {
        return chatService.createDirect(currentUser.requireUserEntity(), body);
    }

    @PostMapping("/conversations/group")
    public Map<String, Object> createGroup(@Valid @RequestBody GroupConversationCreateRequest body) {
        return chatService.createGroup(currentUser.requireUserEntity(), body);
    }

    @PostMapping("/conversations/{conversationId}/participants")
    public Map<String, Object> inviteUser(
            @PathVariable String conversationId, @Valid @RequestBody ConversationInviteRequest body) {
        return chatService.inviteUser(currentUser.requireUserEntity(), conversationId, body);
    }

    @PatchMapping("/conversations/{conversationId}")
    public Map<String, Object> patchConversation(
            @PathVariable String conversationId, @RequestBody ConversationPatchRequest body) {
        return chatService.patchConversation(currentUser.requireUserEntity(), conversationId, body);
    }

    @DeleteMapping("/conversations/{conversationId}/participants/{participantUserId}")
    public Map<String, Object> removeParticipant(
            @PathVariable String conversationId, @PathVariable String participantUserId) {
        return chatService.removeParticipant(
                currentUser.requireUserEntity(), conversationId, participantUserId);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<Map<String, Object>> listMessages(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before) {
        return chatService.listMessages(currentUser.requireUserEntity(), conversationId, limit, before);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public Map<String, Object> sendMessage(
            @PathVariable String conversationId, @RequestBody MessageCreateRequest body) {
        return chatService.sendMessage(currentUser.requireUserEntity(), conversationId, body);
    }

    @PostMapping("/conversations/{conversationId}/read")
    public Map<String, Object> markRead(@PathVariable String conversationId) {
        return chatService.markRead(currentUser.requireUserEntity(), conversationId);
    }

    @PostMapping("/conversations/{conversationId}/attachments")
    public Map<String, Object> uploadAttachment(
            @PathVariable String conversationId, @RequestParam("file") MultipartFile file) {
        return chatService.uploadAttachment(currentUser.requireUserEntity(), conversationId, file);
    }

    @GetMapping("/attachments/{attachmentId}/download")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable String attachmentId) {
        return chatService.downloadAttachment(currentUser.requireUserEntity(), attachmentId);
    }
}
