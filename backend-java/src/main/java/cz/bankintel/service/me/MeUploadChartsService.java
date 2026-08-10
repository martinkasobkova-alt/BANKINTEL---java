package cz.bankintel.service.me;

import cz.bankintel.domain.entity.DashboardPageEntity;
import cz.bankintel.domain.entity.DashboardWidgetEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.DashboardPageRepository;
import cz.bankintel.repository.DashboardWidgetRepository;
import cz.bankintel.repository.UserUploadRepository;
import cz.bankintel.service.access.FeatureAccessService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
public class MeUploadChartsService {

    private static final Set<String> UPLOAD_WIDGET_TYPES = Set.of("user_upload_chart", "uploaded_data_chart");

    private final DashboardWidgetRepository widgetRepository;
    private final DashboardPageRepository pageRepository;
    private final UserUploadRepository userUploadRepository;
    private final FeatureAccessService featureAccessService;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUploadCharts(UserEntity user) {
        requirePersonalDashboard(user);
        requireSubscriber(user);
        Map<String, String> pagesById = new LinkedHashMap<>();
        for (DashboardPageEntity page : pageRepository.findByUserIdOrderBySortOrderAsc(user.getId())) {
            pagesById.put(page.getId(), page.getTitle() != null ? page.getTitle() : "Bez názvu");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (DashboardWidgetEntity widget :
                widgetRepository.findByUserIdAndWidgetTypeInOrderByUpdatedAtDesc(user.getId(), UPLOAD_WIDGET_TYPES)) {
            Map<String, Object> cfg = widget.getConfig() != null ? widget.getConfig() : Map.of();
            String uploadId = firstNonBlank(cfg.get("user_upload_id"), cfg.get("upload_id"), cfg.get("file_upload_id"));
            String uploadName = null;
            if (!uploadId.isBlank()) {
                uploadName = userUploadRepository.findByIdAndUserId(uploadId, user.getId()).map(u -> u.getOriginalName()).orElse(null);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", widget.getId());
            row.put("title", widget.getTitle());
            row.put("page_id", widget.getPageId());
            row.put("page_title", pagesById.getOrDefault(widget.getPageId(), ""));
            row.put("upload_id", uploadId.isBlank() ? null : uploadId);
            row.put("upload_name", uploadName);
            row.put("chart_type", cfg.get("chart_type"));
            row.put("updated_at", widget.getUpdatedAt() != null ? widget.getUpdatedAt().toString() : null);
            out.add(row);
        }
        return out;
    }

    private void requirePersonalDashboard(UserEntity user) {
        featureAccessService.requireFeature(user, "personal_dashboard");
    }

    private void requireSubscriber(UserEntity user) {
        if (!FeatureAccessService.isSubscriber(user)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Tato funkce je dostupná pro předplatitele časopisu Bankovnictví.");
        }
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String s = value != null ? String.valueOf(value).trim() : "";
            if (!s.isBlank()) {
                return s;
            }
        }
        return "";
    }
}
