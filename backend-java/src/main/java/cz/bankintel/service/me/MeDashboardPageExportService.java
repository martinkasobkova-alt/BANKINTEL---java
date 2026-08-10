package cz.bankintel.service.me;

import cz.bankintel.domain.entity.DashboardPageEntity;
import cz.bankintel.domain.entity.DashboardWidgetEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.DashboardPageRepository;
import cz.bankintel.repository.DashboardWidgetRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.export.ExportService;
import cz.bankintel.service.export.ExportSpreadsheetWriter;
import cz.bankintel.service.homepage.WidgetRenderService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MeDashboardPageExportService {

    private final DashboardPageRepository pageRepository;
    private final DashboardWidgetRepository widgetRepository;
    private final WidgetRenderService widgetRenderService;
    private final ExportService exportService;
    private final FeatureAccessService featureAccessService;

    @Transactional(readOnly = true)
    public byte[] exportPageXlsx(UserEntity user, String pageId) {
        requirePersonalDashboard(user);
        DashboardPageEntity page = pageRepository
                .findByIdAndUserId(pageId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stránka nenalezena"));
        List<DashboardWidgetEntity> widgets =
                widgetRepository.findByUserIdAndPageIdOrderBySortOrderAsc(user.getId(), pageId);
        Map<String, Object> sheets = new LinkedHashMap<>();
        int sheetIdx = 0;
        for (DashboardWidgetEntity widget : widgets) {
            if (widgetLocksSourceData(widget)) {
                continue;
            }
            Map<String, Object> rendered = widgetRenderService.buildRenderedWidget(
                    widget.getId(),
                    widget.getWidgetType(),
                    widget.getTitle(),
                    widget.getWidth(),
                    widget.getRowSpan(),
                    widget.getConfig(),
                    user);
            Map<String, Object> tabular = tabularExport(rendered);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) tabular.getOrDefault("rows", List.of());
            if (rows.isEmpty()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<String> columns = (List<String>) tabular.getOrDefault("columns", List.of());
            String title = widget.getTitle() != null && !widget.getTitle().isBlank()
                    ? widget.getTitle()
                    : "Graf " + (sheetIdx + 1);
            sheets.put(title, Map.of("columns", columns, "rows", rows));
            sheetIdx++;
        }
        if (sheetIdx == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Žádná exportovatelná data — grafy mohou mít zamčená zdrojová data nebo nemají tabulku.");
        }
        String safeTitle = exportService.safeFilename(page.getTitle());
        return ExportSpreadsheetWriter.chartWorkbookToXlsx(safeTitle, sheets);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tabularExport(Map<String, Object> rendered) {
        Object dataObj = rendered.get("data");
        if (!(dataObj instanceof Map<?, ?> data)) {
            return Map.of("columns", List.of(), "rows", List.of());
        }
        Object rowsObj = data.get("rows");
        if (!(rowsObj instanceof List<?> rows) || rows.isEmpty()) {
            Object seriesObj = data.get("series");
            if (seriesObj instanceof List<?> series && !series.isEmpty()) {
                List<String> columns = List.of("date", "value", "label");
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object item : series) {
                    if (item instanceof Map<?, ?> point) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("date", point.get("date") != null ? point.get("date") : point.get("period"));
                        row.put("value", point.get("value") != null ? point.get("value") : point.get("amount"));
                        row.put("label", point.get("label") != null ? point.get("label") : point.get("name"));
                        out.add(row);
                    }
                }
                return Map.of("columns", columns, "rows", out);
            }
            return Map.of("columns", List.of(), "rows", List.of());
        }
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> cleanRows = new ArrayList<>();
        for (Object raw : rows) {
            if (raw instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        String key = String.valueOf(entry.getKey());
                        row.put(key, entry.getValue());
                        if (!columns.contains(key)) {
                            columns.add(key);
                        }
                    }
                }
                cleanRows.add(row);
            }
        }
        return Map.of("columns", columns, "rows", cleanRows);
    }

    private static boolean widgetLocksSourceData(DashboardWidgetEntity widget) {
        Map<String, Object> config = widget.getConfig();
        if (config == null) {
            return false;
        }
        Object lock = config.get("lock_source_data");
        if (lock instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(lock));
    }

    private void requirePersonalDashboard(UserEntity user) {
        featureAccessService.requireFeature(user, "personal_dashboard");
        if (!FeatureAccessService.isSubscriber(user)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Tato funkce je dostupná pro předplatitele časopisu Bankovnictví.");
        }
        exportService.requireExportAccess(user);
    }
}
