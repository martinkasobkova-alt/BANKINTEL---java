package cz.bankintel.service.chat;

import cz.bankintel.config.BankIntelProperties;
import cz.bankintel.domain.entity.ChatAttachmentEntity;
import cz.bankintel.repository.ChatAttachmentRepository;
import cz.bankintel.util.IdGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ChatAttachmentStorageService {

    private final BankIntelProperties properties;
    private final ChatAttachmentRepository attachmentRepository;
    private final ChatAccessGuard accessGuard;

    public ChatAttachmentEntity upload(String conversationId, String uploaderId, MultipartFile file) {
        accessGuard.requireMember(conversationId, uploaderId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor je prázdný.");
        }
        String fileName = ChatAttachmentPolicy.safeFilename(file.getOriginalFilename());
        if (!ChatAttachmentPolicy.isAllowedExtension(fileName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nepodporovaný typ souboru.");
        }
        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor se nepodařilo načíst.");
        }
        if (raw.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor je prázdný.");
        }
        if (raw.length > ChatAttachmentPolicy.MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Soubor je příliš velký (max 25 MB).");
        }

        String attachmentId = IdGenerator.newId();
        Path target = resolveStoragePath(conversationId, attachmentId, fileName);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, raw);
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Přílohu se nepodařilo uložit: " + e.getMessage());
        }

        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        } else if (contentType.length() > 180) {
            contentType = contentType.substring(0, 180);
        }

        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setId(attachmentId);
        entity.setConversationId(conversationId);
        entity.setUploaderId(uploaderId);
        entity.setFileName(fileName);
        entity.setContentType(contentType);
        entity.setSize(raw.length);
        entity.setStoragePath(target.toString());
        entity.setCreatedAt(Instant.now());
        return attachmentRepository.save(entity);
    }

    public ResponseEntity<Resource> download(String attachmentId, String userId) {
        ChatAttachmentEntity att =
                attachmentRepository
                        .findById(attachmentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Příloha nebyla nalezena."));
        accessGuard.requireMember(att.getConversationId(), userId);
        Path path = Path.of(att.getStoragePath());
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Příloha není dostupná.");
        }
        String fileName = ChatAttachmentPolicy.safeFilename(att.getFileName());
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(att.getContentType()))
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .body(resource);
    }

    private Path resolveStoragePath(String conversationId, String attachmentId, String fileName) {
        return baseDir().resolve(conversationId).resolve(attachmentId).resolve(fileName);
    }

    private Path baseDir() {
        String configured = properties.chat().attachmentDir();
        if (configured == null || configured.isBlank()) {
            return Path.of(System.getProperty("java.io.tmpdir"), "bankintel-chat-attachments");
        }
        return Path.of(configured.strip());
    }
}
