package cz.bankintel.service.chat;

import cz.bankintel.domain.entity.ChatConversationEntity;
import cz.bankintel.repository.ChatParticipantRepository;
import cz.bankintel.repository.ChatConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class ChatAccessGuard {

    private final ChatConversationRepository conversationRepository;
    private final ChatParticipantRepository participantRepository;

    public ChatConversationEntity requireMember(String conversationId, String userId) {
        ChatConversationEntity conv =
                conversationRepository
                        .findById(conversationId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Konverzace nenalezena."));
        if (!participantRepository.existsByConversationIdAndUserId(conversationId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Do této konverzace nemáte přístup.");
        }
        return conv;
    }
}
