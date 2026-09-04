package cz.bankintel.service.me;

import cz.bankintel.domain.entity.DashboardWidgetEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.DashboardWidgetRepository;
import cz.bankintel.search.CatalogDownloadService;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.export.ExportService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MeDashboardWidgetCatalogExportService {

    private final DashboardWidgetRepository widgetRepository;
    private final CatalogDownloadService catalogDownloadService;
    private final ExportService exportService;
    private final FeatureAccessService featureAccessService;

    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> exportCatalogWidget(UserEntity user, String widgetId, Map<String, Object> payload) {
        requirePersonalDashboard(user);
        requireSubscriber(user);
        exportService.requireExportAccess(user);
        DashboardWidgetEntity widget = widgetRepository
                .findByIdAndUserId(widgetId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Widget nenalezen"));
        Map<String, Object> body = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        Map<String, Object> cfg = widget.getConfig() != null ? widget.getConfig() : Map.of();
        // Zamčená zdrojová data se dosud kontrolovala jen v prohlížeči, takže stažení celé
        // katalogové sady zámek obcházelo. Export stránky do Excelu zamčené widgety přeskakuje
        // (MeDashboardPageExportService), tady to chybělo.
        if (Boolean.TRUE.equals(cfg.get("lock_source_data"))) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Graf má zamčená zdrojová data — stažení celé datové sady není povolené.");
        }
        if (!body.containsKey("source_type") && cfg.get("source_type") != null) {
            body.put("source_type", cfg.get("source_type"));
        }
        if (!body.containsKey("set_id") && cfg.get("set_id") != null) {
            body.put("set_id", cfg.get("set_id"));
        }
        if (!body.containsKey("format")) {
            body.put("format", "xlsx");
        }
        return catalogDownloadService.download(body, user);
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
}
