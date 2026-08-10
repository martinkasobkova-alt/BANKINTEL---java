package cz.bankintel.service.userdata;

import cz.bankintel.domain.dto.UserDataDtos.UserSeriesMapRequest;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.domain.entity.UserUploadEntity;
import cz.bankintel.domain.entity.UserUploadedSeriesEntity;
import cz.bankintel.repository.UserUploadRepository;
import cz.bankintel.repository.UserUploadedSeriesRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.upload.UploadPolicy;
import cz.bankintel.service.upload.UserPrivateStorageService;
import cz.bankintel.util.IdGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class UserDataService {

    private final UserUploadRepository uploadRepository;
    private final UserUploadedSeriesRepository seriesRepository;
    private final UserPrivateStorageService storageService;
    private final FeatureAccessService featureAccessService;

    @Transactional(readOnly = true)
    public Map<String, Object> listUploads(UserEntity user, String companyId) {
        requireCompanyDataAccess(user);
        List<UserUploadEntity> rows = companyId != null && !companyId.isBlank()
                ? uploadRepository.findByUserIdAndCompanyIdOrderByCreatedAtDesc(user.getId(), companyId.strip())
                : uploadRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        return Map.of("uploads", rows.stream().map(this::toPublicUpload).toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUploadDetail(UserEntity user, String uploadId) {
        requireCompanyDataAccess(user);
        UserUploadEntity upload = requireUpload(user.getId(), uploadId);
        List<UserUploadedSeriesEntity> series =
                seriesRepository.findByUserIdAndUploadIdOrderByCreatedAtDesc(user.getId(), uploadId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("upload", toPublicUpload(upload));
        out.put("series", series.stream().map(UserDataParseService::summarizeSeries).toList());
        return out;
    }

    @Transactional
    public Map<String, Object> upload(UserEntity user, MultipartFile file, String companyId) {
        requireCompanyDataAccess(user);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor je prázdný.");
        }
        byte[] raw = readBytes(file);
        if (raw.length > UploadPolicy.COMPANY_DATA_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Soubor je příliš velký.");
        }
        String safeName = UploadPolicy.safeFilename(file.getOriginalFilename());
        String ext = UploadPolicy.extension(safeName);
        if (!UploadPolicy.isCompanyDataAllowedExtension(safeName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Povolené formáty: CSV, XLSX, PDF.");
        }
        if (!UploadPolicy.isCompanyDataAllowedMime(file.getContentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nepovolený MIME typ souboru.");
        }

        String uploadId = IdGenerator.newId();
        String rel = UploadPolicy.relPath(user.getId(), uploadId, ext);
        storageService.store(user.getId(), uploadId, ext, raw);

        Instant now = Instant.now();
        UserUploadEntity entity = new UserUploadEntity();
        entity.setId(uploadId);
        entity.setUserId(user.getId());
        entity.setCompanyId(blankToNull(companyId));
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

        UserDataParseService.ParseResult parsed =
                UserDataParseService.parseUpload(raw, ext, user.getId(), entity.getCompanyId(), uploadId, safeName);
        if (!parsed.series().isEmpty()) {
            seriesRepository.saveAll(parsed.series());
        }

        entity.setStatus(String.valueOf(parsed.meta().getOrDefault("status", "uploaded")));
        entity.setDetectedTables(castMapList(parsed.meta().get("detected_tables")));
        entity.setMappedSeriesCount(parsed.series().size());
        entity.setExtractedTextPreview(String.valueOf(parsed.meta().getOrDefault("extracted_text_preview", "")));
        entity.setErrors(castStringList(parsed.meta().get("errors")));
        uploadRepository.save(entity);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("upload_id", uploadId);
        out.put("status", entity.getStatus());
        out.put("detected_series", parsed.series().stream().map(UserDataParseService::summarizeSeries).toList());
        out.put("mapped_series_count", parsed.series().size());
        out.put("upload", toPublicUpload(entity));
        return out;
    }

    @Transactional
    public Map<String, Object> deleteUpload(UserEntity user, String uploadId) {
        requireCompanyDataAccess(user);
        UserUploadEntity upload = requireUpload(user.getId(), uploadId);
        storageService.delete(user.getId(), upload.getStoredRelPath());
        seriesRepository.deleteByUserIdAndUploadId(user.getId(), uploadId);
        uploadRepository.delete(upload);
        return Map.of("ok", true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listSeries(UserEntity user, String companyId, String uploadId) {
        requireCompanyDataAccess(user);
        List<UserUploadedSeriesEntity> rows;
        if (uploadId != null && !uploadId.isBlank()) {
            rows = seriesRepository.findByUserIdAndUploadIdOrderByCreatedAtDesc(user.getId(), uploadId.strip());
        } else if (companyId != null && !companyId.isBlank()) {
            rows = seriesRepository.findByUserIdAndCompanyIdOrderByCreatedAtDesc(user.getId(), companyId.strip());
        } else {
            rows = seriesRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        }
        return Map.of("series", rows.stream().map(UserDataParseService::summarizeSeries).toList());
    }

    @Transactional
    public Map<String, Object> mapSeries(UserEntity user, String seriesId, UserSeriesMapRequest body) {
        requireCompanyDataAccess(user);
        UserUploadedSeriesEntity doc = seriesRepository
                .findByIdAndUserId(seriesId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Řada nenalezena."));
        if (body.title() != null) {
            doc.setTitle(body.title().strip());
        }
        if (body.unit() != null) {
            doc.setUnit(body.unit());
        }
        if (body.currency() != null) {
            doc.setCurrency(body.currency());
        }
        if (body.frequency() != null) {
            doc.setFrequency(body.frequency());
        }
        if (body.sectorId() != null) {
            doc.setSectorId(body.sectorId());
        }
        if (body.tags() != null) {
            doc.setTags(body.tags().stream().map(String::strip).filter(s -> !s.isBlank()).limit(20).toList());
        }
        if (body.metricType() != null && !body.metricType().isBlank()) {
            Map<String, Object> mapping = UserSeriesMapper.overrideMetricMapping(body.metricType());
            doc.setMetricType(String.valueOf(mapping.get("metric_type")));
            doc.setDetectedDomain(String.valueOf(mapping.get("detected_domain")));
            doc.setDetectedDomains(List.of(String.valueOf(mapping.get("detected_domain"))));
            doc.setMappingConfidence(((Number) mapping.get("confidence")).doubleValue());
            doc.setMappingReason(String.valueOf(mapping.get("mapping_reason")));
        } else if (body.title() != null) {
            Map<String, Object> mapping = UserSeriesMapper.classifyMetric(body.title(), doc.getDescription(), doc.getTags());
            doc.setMetricType(String.valueOf(mapping.get("metric_type")));
            doc.setDetectedDomain(String.valueOf(mapping.get("detected_domain")));
            doc.setDetectedDomains(List.of(String.valueOf(mapping.get("detected_domain"))));
            doc.setMappingConfidence(((Number) mapping.get("confidence")).doubleValue());
            doc.setMappingReason(String.valueOf(mapping.get("mapping_reason")));
        }
        doc.setUpdatedAt(Instant.now());
        seriesRepository.save(doc);
        return Map.of("series", UserDataParseService.summarizeSeries(doc));
    }

    private void requireCompanyDataAccess(UserEntity user) {
        featureAccessService.requireFeature(user, "company_data_analysis");
    }

    private UserUploadEntity requireUpload(String userId, String uploadId) {
        return uploadRepository
                .findByIdAndUserId(uploadId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload nenalezen."));
    }

    private Map<String, Object> toPublicUpload(UserUploadEntity entity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entity.getId());
        out.put("user_id", entity.getUserId());
        out.put("company_id", entity.getCompanyId());
        out.put("filename", entity.getFilename() != null ? entity.getFilename() : entity.getOriginalName());
        out.put("original_name", entity.getOriginalName());
        out.put("file_type", entity.getFileType());
        out.put("mime_type", entity.getMimeType());
        out.put("status", entity.getStatus());
        out.put("detected_tables", entity.getDetectedTables() != null ? entity.getDetectedTables() : List.of());
        out.put("mapped_series_count", entity.getMappedSeriesCount());
        out.put("extracted_text_preview", entity.getExtractedTextPreview());
        out.put("errors", entity.getErrors() != null ? entity.getErrors() : List.of());
        out.put("size", entity.getSizeBytes());
        out.put("created_at", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        out.put("updated_at", entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        return out;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor se nepodařilo načíst.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static List<String> castStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
