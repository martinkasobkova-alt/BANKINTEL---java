package cz.bankintel.service.me;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.domain.entity.UserUploadEntity;
import cz.bankintel.repository.UserUploadRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.homepage.resolver.UserUploadChartWidgetResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MeUserUploadChartPreviewService {

    private final UserUploadRepository uploadRepository;
    private final UserUploadChartWidgetResolver userUploadChartWidgetResolver;
    private final FeatureAccessService featureAccessService;

    @Transactional(readOnly = true)
    public Map<String, Object> chartPreview(UserEntity user, String uploadId, Map<String, Object> config) {
        requireUploadAccess(user);
        UserUploadEntity doc = uploadRepository
                .findByIdAndUserId(uploadId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Soubor nenalezen"));

        Map<String, Object> cfg = new LinkedHashMap<>(config != null ? config : Map.of());
        cfg.put("user_upload_id", uploadId);
        cfg.put("upload_id", uploadId);
        cfg.remove("_public_surface");

        Map<String, Object> data = userUploadChartWidgetResolver.resolve(cfg);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", "upload-preview-" + uploadId);
        out.put("type", "user_upload_chart");
        out.put("title", doc.getOriginalName() != null ? doc.getOriginalName() : "Vlastní data");
        out.put("width", "full");
        out.put("config", cfg);
        out.put("data", data);
        return out;
    }

    private void requireUploadAccess(UserEntity user) {
        featureAccessService.requireFeature(user, "upload_custom_data");
        if (!FeatureAccessService.isSubscriber(user)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Tato funkce je dostupná pro předplatitele časopisu Bankovnictví.");
        }
    }
}
