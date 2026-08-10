package cz.bankintel.service.chat;

import cz.bankintel.domain.dto.ChatDtos.MessageCreateRequest;
import cz.bankintel.domain.entity.ChatAttachmentEntity;
import cz.bankintel.domain.entity.ChatConversationEntity;
import cz.bankintel.domain.entity.ChatMessageEntity;
import cz.bankintel.domain.entity.ChatParticipantEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.ChatAttachmentRepository;
import cz.bankintel.repository.ChatConversationRepository;
import cz.bankintel.repository.ChatMessageRepository;
import cz.bankintel.repository.ChatParticipantRepository;
import cz.bankintel.util.IdGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ChatMessageService {

    private final ChatMessageRepository messageRepository;
    private final ChatConversationRepository conversationRepository;
    private final ChatParticipantRepository participantRepository;
    private final ChatAttachmentRepository attachmentRepository;
    private final ChatUserLookupService userLookupService;
    private final ChatPayloadMapper payloadMapper;
    private final ChatAccessGuard accessGuard;

    @Transactional(readOnly = true)
    public long totalUnreadCount(String userId) {
        return messageRepository.countTotalUnreadForUser(userId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMessages(
            UserEntity actor, String conversationId, int limit, String beforeRaw) {
        accessGuard.requireMember(conversationId, actor.getId());
        Instant before = parseBefore(beforeRaw);
        int capped = Math.min(Math.max(limit, 1), 200);
        List<ChatMessageEntity> rows =
                messageRepository.findPage(conversationId, before, PageRequest.of(0, capped));
        List<ChatMessageEntity> ordered = new ArrayList<>(rows);
        java.util.Collections.reverse(ordered);

        List<String> senderIds =
                ordered.stream().map(ChatMessageEntity::getSenderId).filter(Objects::nonNull).distinct().toList();
        List<ChatParticipantEntity> participants = participantRepository.findAllByConversationId(conversationId);
        List<String> participantIds = participants.stream().map(ChatParticipantEntity::getUserId).toList();
        List<String> lookupIds = new ArrayList<>(senderIds);
        lookupIds.addAll(participantIds);
        Map<String, Map<String, Object>> userMap = userLookupService.loadUserMap(lookupIds);

        List<String> allAttachmentIds = new ArrayList<>();
        for (ChatMessageEntity msg : ordered) {
            if (msg.getAttachmentIds() != null) {
                allAttachmentIds.addAll(msg.getAttachmentIds());
            }
        }
        Map<String, ChatAttachmentEntity> attachmentMap = loadAttachmentMap(allAttachmentIds);

        return ordered.stream()
                .map(msg -> payloadMapper.messagePayload(msg, userMap, attachmentMap))
                .toList();
    }

    @Transactional
    public Map<String, Object> sendMessage(UserEntity actor, String conversationId, MessageCreateRequest body) {
        ChatConversationEntity conv = accessGuard.requireMember(conversationId, actor.getId());
        String text = body.text() == null ? "" : body.text().strip();
        List<String> attachmentIds = normalizeAttachmentIds(body.attachmentIds());
        Map<String, Object> sharedChart = normalizeSharedChart(body.sharedChart());
        if (text.isEmpty() && attachmentIds.isEmpty() && sharedChart == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Zpráva musí obsahovat text nebo přílohu.");
        }

        List<ChatAttachmentEntity> attachments = List.of();
        if (!attachmentIds.isEmpty()) {
            attachments = attachmentRepository.findAllByIdInAndConversationId(attachmentIds, conversationId);
            if (attachments.size() != attachmentIds.size()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Některé přílohy neexistují nebo nepatří do této konverzace.");
            }
        }

        Instant now = Instant.now();
        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setId(IdGenerator.newId());
        msg.setConversationId(conversationId);
        msg.setSenderId(actor.getId());
        msg.setText(text);
        msg.setAttachmentIds(attachmentIds);
        msg.setSharedChart(sharedChart);
        msg.setCreatedAt(now);
        messageRepository.save(msg);

        String preview = buildPreview(text, sharedChart, attachmentIds.size());
        conv.setUpdatedAt(now);
        conv.setLastMessageAt(now);
        conv.setLastMessagePreview(preview.length() > 220 ? preview.substring(0, 220) : preview);
        conversationRepository.save(conv);

        ChatParticipantEntity self =
                participantRepository
                        .findById(new cz.bankintel.domain.entity.ChatParticipantId(conversationId, actor.getId()))
                        .orElse(null);
        if (self != null) {
            self.setLastReadAt(now);
            participantRepository.save(self);
        }

        return payloadMapper.sentMessagePayload(msg, actor, attachments);
    }

    @Transactional
    public Map<String, Object> markRead(UserEntity actor, String conversationId) {
        accessGuard.requireMember(conversationId, actor.getId());
        Instant now = Instant.now();
        ChatParticipantEntity participant =
                participantRepository
                        .findById(new cz.bankintel.domain.entity.ChatParticipantId(conversationId, actor.getId()))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Konverzace nenalezena."));
        participant.setLastReadAt(now);
        participantRepository.save(participant);

        ChatConversationEntity conv =
                conversationRepository
                        .findById(conversationId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Konverzace nenalezena."));
        conv.setUpdatedAt(now);
        conversationRepository.save(conv);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("read_at", now.toString());
        return out;
    }

    private static Instant parseBefore(String beforeRaw) {
        if (beforeRaw == null || beforeRaw.isBlank()) {
            return null;
        }
        try {
            String normalized = beforeRaw.strip().replace("Z", "+00:00");
            return Instant.parse(normalized);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametr 'before' musí být ISO datetime.");
        }
    }

    private static List<String> normalizeAttachmentIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String id = item.strip();
            if (id.isEmpty() || seen.contains(id)) {
                continue;
            }
            seen.add(id);
            out.add(id);
            if (out.size() >= 12) {
                break;
            }
        }
        return out;
    }

    private static Map<String, Object> normalizeSharedChart(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String title = truncateString(raw.get("title"), 220);
        String sourceType = truncateString(raw.get("source_type"), 80);
        String setId = truncateString(raw.get("set_id"), 220);
        String linkUrl = truncateString(raw.get("link_url"), 1800);
        if (title.isEmpty() && setId.isEmpty() && linkUrl.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", title);
        out.put("source_type", sourceType);
        out.put("set_id", setId);
        out.put("link_url", linkUrl);
        return out;
    }

    private static String truncateString(Object value, int max) {
        String text = value == null ? "" : String.valueOf(value).strip();
        if (text.length() > max) {
            return text.substring(0, max);
        }
        return text;
    }

    private static String buildPreview(String text, Map<String, Object> sharedChart, int attachmentCount) {
        if (!text.isEmpty()) {
            return text;
        }
        if (sharedChart != null) {
            Object title = sharedChart.get("title");
            Object setId = sharedChart.get("set_id");
            String label =
                    title != null && !String.valueOf(title).isBlank()
                            ? String.valueOf(title)
                            : (setId != null && !String.valueOf(setId).isBlank()
                                    ? String.valueOf(setId)
                                    : "Graf");
            return "Sdílený graf: " + label;
        }
        return "Příloha (" + attachmentCount + ")";
    }

    private Map<String, ChatAttachmentEntity> loadAttachmentMap(List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return Map.of();
        }
        List<String> distinct = attachmentIds.stream().filter(Objects::nonNull).distinct().toList();
        return attachmentRepository.findAllById(distinct).stream()
                .collect(Collectors.toMap(ChatAttachmentEntity::getId, a -> a, (a, b) -> a, LinkedHashMap::new));
    }
}
