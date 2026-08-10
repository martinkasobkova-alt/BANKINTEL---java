package cz.bankintel.service.me;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.domain.entity.UserUploadEntity;
import cz.bankintel.repository.UserUploadRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.upload.UploadPolicy;
import cz.bankintel.service.upload.UserPrivateStorageService;
import cz.bankintel.util.IdGenerator;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MeUserUploadService {

    private final UserUploadRepository uploadRepository;
    private final UserPrivateStorageService storageService;
    private final FeatureAccessService featureAccessService;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUploads(UserEntity user) {
        requireUploadAccess(user);
        return uploadRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::toMeListItem)
                .toList();
    }

    @Transactional
    public Map<String, Object> upload(UserEntity user, MultipartFile file) {
        requireUploadAccess(user);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor je prázdný.");
        }
        byte[] raw = readBytes(file);
        if (raw.length > UploadPolicy.ME_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Soubor je příliš velký");
        }
        String safeName = UploadPolicy.safeFilename(file.getOriginalFilename());
        String ext = UploadPolicy.extension(safeName);
        if (!UploadPolicy.isMeAllowedExtension(safeName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Povolené formáty: CSV, XLSX");
        }

        String uploadId = IdGenerator.newId();
        String rel = UploadPolicy.relPath(user.getId(), uploadId, ext);
        storageService.store(user.getId(), uploadId, ext, raw);

        Instant now = Instant.now();
        UserUploadEntity entity = new UserUploadEntity();
        entity.setId(uploadId);
        entity.setUserId(user.getId());
        entity.setOriginalName(safeName);
        entity.setFilename(safeName);
        entity.setFileType(ext.startsWith(".") ? ext.substring(1) : ext);
        entity.setMimeType(file.getContentType() != null ? file.getContentType() : "");
        entity.setStatus("uploaded");
        entity.setStoredRelPath(rel);
        entity.setSizeBytes(raw.length);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        uploadRepository.save(entity);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", uploadId);
        out.put("original_name", safeName);
        out.put("size", raw.length);
        out.put("created_at", now.toString());
        return out;
    }

    @Transactional
    public Map<String, Object> deleteUpload(UserEntity user, String uploadId) {
        requireUploadAccess(user);
        UserUploadEntity doc = uploadRepository
                .findByIdAndUserId(uploadId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Soubor nenalezen"));
        storageService.delete(user.getId(), doc.getStoredRelPath());
        uploadRepository.delete(doc);
        return Map.of("ok", true);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Resource> download(UserEntity user, String uploadId) {
        requireUploadAccess(user);
        UserUploadEntity doc = uploadRepository
                .findByIdAndUserId(uploadId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Soubor nenalezen"));
        byte[] raw = storageService.read(user.getId(), doc.getStoredRelPath());
        if (raw.length == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Soubor je prázdný nebo nedostupný.");
        }
        String fileName = UploadPolicy.safeFilename(doc.getOriginalName());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .body(new FileSystemResource(storageService.resolveStoredPath(user.getId(), doc.getStoredRelPath())));
    }

    private void requireUploadAccess(UserEntity user) {
        featureAccessService.requireFeature(user, "upload_custom_data");
        if (!FeatureAccessService.isSubscriber(user)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Tato funkce je dostupná pro předplatitele časopisu Bankovnictví.");
        }
    }

    private Map<String, Object> toMeListItem(UserUploadEntity entity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entity.getId());
        out.put("original_name", entity.getOriginalName());
        out.put("size", entity.getSizeBytes());
        out.put("created_at", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        return out;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor se nepodařilo načíst.");
        }
    }
}
