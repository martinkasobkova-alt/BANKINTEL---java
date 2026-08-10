package cz.bankintel.service.chat;

import cz.bankintel.domain.dto.ChatDtos.ConversationInviteRequest;
import cz.bankintel.domain.dto.ChatDtos.ConversationPatchRequest;
import cz.bankintel.domain.dto.ChatDtos.DirectConversationCreateRequest;
import cz.bankintel.domain.dto.ChatDtos.GroupConversationCreateRequest;
import cz.bankintel.domain.entity.ChatConversationEntity;
import cz.bankintel.domain.entity.ChatMessageEntity;
import cz.bankintel.domain.entity.ChatParticipantEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.ChatConversationRepository;
import cz.bankintel.repository.ChatMessageRepository;
import cz.bankintel.repository.ChatParticipantRepository;
import cz.bankintel.repository.UserRepository;
import cz.bankintel.util.IdGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ChatConversationService {

    private final ChatConversationRepository conversationRepository;
    private final ChatParticipantRepository participantRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ChatUserLookupService userLookupService;
    private final ChatPayloadMapper payloadMapper;
    private final ChatAccessGuard accessGuard;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listConversations(UserEntity actor) {
        List<ChatConversationEntity> conversations = conversationRepository.findAllForUser(actor.getId());
        if (conversations.isEmpty()) {
            return List.of();
        }
        List<String> convIds = conversations.stream().map(ChatConversationEntity::getId).toList();
        List<ChatParticipantEntity> allParticipants = participantRepository.findAllByConversationIdIn(convIds);
        Map<String, List<ChatParticipantEntity>> participantsByConv =
                allParticipants.stream().collect(Collectors.groupingBy(ChatParticipantEntity::getConversationId));
        Map<String, Instant> lastReadByConv = new LinkedHashMap<>();
        for (ChatParticipantEntity p : allParticipants) {
            if (actor.getId().equals(p.getUserId())) {
                lastReadByConv.put(p.getConversationId(), p.getLastReadAt());
            }
        }
        List<String> allUserIds =
                allParticipants.stream().map(ChatParticipantEntity::getUserId).distinct().toList();
        Map<String, Map<String, Object>> userMap = userLookupService.loadUserMap(allUserIds);

        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatConversationEntity conv : conversations) {
            long unread =
                    messageRepository.countUnread(
                            conv.getId(), actor.getId(), lastReadByConv.get(conv.getId()));
            out.add(
                    payloadMapper.conversationPayload(
                            conv,
                            participantsByConv.getOrDefault(conv.getId(), List.of()),
                            unread,
                            userMap));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> createOrGetDirect(UserEntity actor, DirectConversationCreateRequest body) {
        String targetId = body.userId() == null ? "" : body.userId().strip();
        if (targetId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí user_id.");
        }
        if (targetId.equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nelze založit přímý chat se sebou.");
        }
        if (!userRepository.existsById(targetId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Uživatel nebyl nalezen.");
        }
        return conversationRepository
                .findDirectBetweenUsers(actor.getId(), targetId)
                .map(conv -> toConversationResponse(conv, actor.getId(), 0))
                .orElseGet(() -> createDirectConversation(actor, targetId));
    }

    @Transactional
    public Map<String, Object> createGroup(UserEntity actor, GroupConversationCreateRequest body) {
        List<String> participantIds = userLookupService.sortedUniqueIds(body.participantIds());
        participantIds = new ArrayList<>(participantIds);
        if (!participantIds.contains(actor.getId())) {
            participantIds.add(actor.getId());
        }
        participantIds = participantIds.stream().distinct().sorted().toList();
        if (participantIds.size() < 2) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Skupinová konverzace vyžaduje alespoň dva účastníky.");
        }
        List<UserEntity> found = userRepository.findAllById(participantIds);
        if (found.size() != participantIds.size()) {
            List<String> foundIds = found.stream().map(UserEntity::getId).toList();
            List<String> missing =
                    participantIds.stream().filter(id -> !foundIds.contains(id)).limit(3).toList();
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Někteří účastníci neexistují: " + String.join(", ", missing));
        }

        Instant now = Instant.now();
        String initialMessage = body.initialMessage() == null ? "" : body.initialMessage().strip();
        String title = body.title() == null ? null : body.title().strip();
        if (title != null && title.isEmpty()) {
            title = null;
        }

        ChatConversationEntity conv = new ChatConversationEntity();
        conv.setId(IdGenerator.newId());
        conv.setType("group");
        conv.setTitle(title);
        conv.setCreatedBy(actor.getId());
        conv.setCreatedAt(now);
        conv.setUpdatedAt(now);
        conv.setLastMessagePreview(initialMessage.length() > 220 ? initialMessage.substring(0, 220) : initialMessage);
        conv.setLastMessageAt(initialMessage.isEmpty() ? null : now);
        conversationRepository.save(conv);

        saveParticipants(conv.getId(), participantIds, actor.getId(), now);

        if (!initialMessage.isEmpty()) {
            ChatMessageEntity msg = new ChatMessageEntity();
            msg.setId(IdGenerator.newId());
            msg.setConversationId(conv.getId());
            msg.setSenderId(actor.getId());
            msg.setText(initialMessage);
            msg.setCreatedAt(now);
            messageRepository.save(msg);
        }

        return toConversationResponse(conv, actor.getId(), 0);
    }

    @Transactional
    public Map<String, Object> inviteUser(UserEntity actor, String conversationId, ConversationInviteRequest body) {
        accessGuard.requireMember(conversationId, actor.getId());
        String targetId = body.userId() == null ? "" : body.userId().strip();
        if (targetId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí user_id.");
        }
        if (!userRepository.existsById(targetId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Uživatel nebyl nalezen.");
        }
        List<ChatParticipantEntity> current = participantRepository.findAllByConversationId(conversationId);
        List<String> nextIds =
                current.stream().map(ChatParticipantEntity::getUserId).collect(Collectors.toCollection(ArrayList::new));
        if (!nextIds.contains(targetId)) {
            nextIds.add(targetId);
        }
        nextIds = nextIds.stream().distinct().sorted().toList();

        Instant now = Instant.now();
        ChatConversationEntity conv =
                conversationRepository
                        .findById(conversationId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Konverzace nenalezena."));
        conv.setType("group");
        conv.setUpdatedAt(now);
        conversationRepository.save(conv);

        participantRepository.deleteAll(current);
        saveParticipants(conversationId, nextIds, null, now);

        return toConversationResponse(conv, actor.getId(), 0);
    }

    @Transactional
    public Map<String, Object> patchTitle(UserEntity actor, String conversationId, ConversationPatchRequest body) {
        accessGuard.requireMember(conversationId, actor.getId());
        Instant now = Instant.now();
        ChatConversationEntity conv =
                conversationRepository
                        .findById(conversationId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Konverzace nenalezena."));
        String title = body.title();
        if (title != null) {
            title = title.strip();
            if (title.isEmpty()) {
                title = null;
            }
        }
        conv.setTitle(title);
        conv.setType("group");
        conv.setUpdatedAt(now);
        conversationRepository.save(conv);
        return toConversationResponse(conv, actor.getId(), 0);
    }

    @Transactional
    public Map<String, Object> removeParticipant(UserEntity actor, String conversationId, String participantUserId) {
        accessGuard.requireMember(conversationId, actor.getId());
        String targetId = participantUserId == null ? "" : participantUserId.strip();
        if (targetId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí user_id účastníka.");
        }
        if (targetId.equals(actor.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Sami sebe odeberte přes budoucí funkci opustit konverzaci.");
        }
        List<ChatParticipantEntity> current = participantRepository.findAllByConversationId(conversationId);
        List<String> currentIds = current.stream().map(ChatParticipantEntity::getUserId).toList();
        if (!currentIds.contains(targetId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Uživatel není členem konverzace.");
        }
        if (currentIds.size() <= 2) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "V konverzaci musí zůstat alespoň dva účastníci.");
        }

        Instant now = Instant.now();
        List<String> nextIds = currentIds.stream().filter(id -> !id.equals(targetId)).sorted().toList();
        ChatConversationEntity conv =
                conversationRepository
                        .findById(conversationId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Konverzace nenalezena."));
        conv.setType("group");
        conv.setUpdatedAt(now);
        conversationRepository.save(conv);

        participantRepository.deleteAll(current);
        saveParticipants(conversationId, nextIds, null, now);

        return toConversationResponse(conv, actor.getId(), 0);
    }

    private Map<String, Object> createDirectConversation(UserEntity actor, String targetId) {
        Instant now = Instant.now();
        ChatConversationEntity conv = new ChatConversationEntity();
        conv.setId(IdGenerator.newId());
        conv.setType("direct");
        conv.setCreatedBy(actor.getId());
        conv.setCreatedAt(now);
        conv.setUpdatedAt(now);
        conv.setLastMessagePreview("");
        conversationRepository.save(conv);

        List<String> ids = List.of(actor.getId(), targetId).stream().sorted().toList();
        saveParticipants(conv.getId(), ids, actor.getId(), now);
        return toConversationResponse(conv, actor.getId(), 0);
    }

    private void saveParticipants(
            String conversationId, List<String> userIds, String readNowUserId, Instant now) {
        for (String uid : userIds) {
            ChatParticipantEntity p = new ChatParticipantEntity();
            p.setConversationId(conversationId);
            p.setUserId(uid);
            p.setJoinedAt(now);
            if (readNowUserId != null && readNowUserId.equals(uid)) {
                p.setLastReadAt(now);
            }
            participantRepository.save(p);
        }
    }

    private Map<String, Object> toConversationResponse(
            ChatConversationEntity conv, String actorUserId, long unreadCount) {
        List<ChatParticipantEntity> participants = participantRepository.findAllByConversationId(conv.getId());
        List<String> userIds = participants.stream().map(ChatParticipantEntity::getUserId).toList();
        Map<String, Map<String, Object>> userMap = userLookupService.loadUserMap(userIds);
        if (unreadCount == 0) {
            Instant lastRead =
                    participants.stream()
                            .filter(p -> actorUserId.equals(p.getUserId()))
                            .map(ChatParticipantEntity::getLastReadAt)
                            .findFirst()
                            .orElse(null);
            unreadCount = messageRepository.countUnread(conv.getId(), actorUserId, lastRead);
        }
        return payloadMapper.conversationPayload(conv, participants, unreadCount, userMap);
    }
}
