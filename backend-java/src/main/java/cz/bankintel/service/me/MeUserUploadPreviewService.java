package cz.bankintel.service.me;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.domain.entity.UserUploadEntity;
import cz.bankintel.repository.UserUploadRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.upload.UploadPolicy;
import cz.bankintel.service.upload.UserPrivateStorageService;
import cz.bankintel.service.userdata.UserDataParseService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MeUserUploadPreviewService {

    private final UserUploadRepository uploadRepository;
    private final UserPrivateStorageService storageService;
    private final FeatureAccessService featureAccessService;

    @Transactional(readOnly = true)
    public Map<String, Object> preview(UserEntity user, String uploadId, int maxRows) {
        requireUploadAccess(user);
        int rowsLimit = Math.max(1, Math.min(maxRows, 20_000));
        UserUploadEntity doc = uploadRepository
                .findByIdAndUserId(uploadId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Soubor nenalezen"));

        String rel = doc.getStoredRelPath() != null ? doc.getStoredRelPath() : "";
        String ext = UploadPolicy.extension(rel.isBlank() ? doc.getOriginalName() : rel);
        if (!UploadPolicy.isMeAllowedExtension(doc.getOriginalName())) {
            return Map.of("columns", List.of(), "sample_rows", List.of());
        }

        byte[] raw;
        try {
            raw = storageService.read(user.getId(), doc.getStoredRelPath());
        } catch (ResponseStatusException ex) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("columns", List.of());
            out.put("sample_rows", List.of());
            out.put("error", "Soubor není k dispozici.");
            return out;
        }

        try {
            List<Map<String, Object>> allRows = UserDataParseService.readTabularRows(raw, ext);
            if (allRows.isEmpty()) {
                return Map.of("columns", List.of(), "sample_rows", List.of());
            }
            List<String> columns = collectColumns(allRows);
            List<Map<String, Object>> sample = allRows.stream().limit(rowsLimit).toList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("columns", columns);
            out.put("sample_rows", sample);
            out.put("total_rows", allRows.size());
            return out;
        } catch (Exception ex) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("columns", List.of());
            out.put("sample_rows", List.of());
            out.put("error", ex.getMessage() != null ? ex.getMessage().substring(0, Math.min(300, ex.getMessage().length())) : "Preview failed");
            return out;
        }
    }

    private static List<String> collectColumns(List<Map<String, Object>> rows) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> columns = new ArrayList<>();
        for (Map<String, Object> row : rows.stream().limit(50).toList()) {
            for (String key : row.keySet()) {
                String trimmed = key == null ? "" : key.strip();
                if (!trimmed.isBlank() && seen.add(trimmed)) {
                    columns.add(trimmed);
                }
            }
        }
        return columns;
    }

    private void requireUploadAccess(UserEntity user) {
        featureAccessService.requireFeature(user, "upload_custom_data");
        if (!FeatureAccessService.isSubscriber(user)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Tato funkce je dostupná pro předplatitele časopisu Bankovnictví.");
        }
    }
}
