package cz.bankintel.service.homepage.resolver;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.domain.entity.UserUploadEntity;
import cz.bankintel.repository.UserUploadRepository;
import cz.bankintel.search.CatalogPreviewOrchestrator;
import cz.bankintel.service.myseries.SavedSeriesResolverService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserUploadChartWidgetResolver {

    private final UserUploadRepository uploadRepository;
    private final SavedSeriesResolverService savedSeriesResolverService;

    public Map<String, Object> resolve(Map<String, Object> cfg) {
        String uploadId = firstNonBlank(
                str(cfg.get("user_upload_id")), str(cfg.get("upload_id")), str(cfg.get("file_upload_id")));
        String ownerUserId = str(cfg.get("owner_user_id"));
        if (uploadId.isBlank()) {
            return Map.of("error", "Soubor není k dispozici (chybí upload).");
        }
        UserUploadEntity upload = uploadRepository.findById(uploadId).orElse(null);
        if (upload == null) {
            return Map.of("error", "Soubor není k dispozici.");
        }
        if (ownerUserId.isBlank()) {
            ownerUserId = upload.getUserId();
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>(cfg);
            payload.put("kind", "user_upload");
            payload.put("user_upload_id", uploadId);
            SavedSeriesResolverService.ResolvedPoints resolved =
                    savedSeriesResolverService.resolvePoints(ownerUserId, payload);
            List<Map<String, Object>> points = resolved.points();
            if (points.isEmpty()) {
                return Map.of("error", "Z nahraného souboru se nepodařilo načíst řadu.");
            }
            String view = str(cfg.get("view")).isBlank() ? "chart" : str(cfg.get("view")).toLowerCase(Locale.ROOT);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("title", upload.getOriginalName());
            out.put("view", view);
            if ("chart".equals(view)) {
                out.put(
                        "rows",
                        points.stream()
                                .map(p -> Map.<String, Object>of("x", p.get("period"), "y", p.get("value")))
                                .toList());
            } else {
                out.put("rows", points);
            }
            return out;
        } catch (Exception ex) {
            return Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Upload preview failed");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).strip() : "";
    }
}
