package cz.bankintel.controller.me;

import cz.bankintel.domain.dto.AuthDtos.MeResponse;
import cz.bankintel.domain.dto.MeDtos;
import cz.bankintel.domain.dto.MeDtos.ChangePasswordRequest;
import cz.bankintel.domain.dto.MeDtos.DashboardPageCreateRequest;
import cz.bankintel.domain.dto.MeDtos.DashboardPagePatchRequest;
import cz.bankintel.domain.dto.MeDtos.DashboardWidgetCreateRequest;
import cz.bankintel.domain.dto.MeDtos.DashboardWidgetPatchRequest;
import cz.bankintel.domain.dto.MeDtos.NavOrderPutRequest;
import cz.bankintel.domain.dto.MeDtos.PreferencesPatchRequest;
import cz.bankintel.domain.dto.MeDtos.ProfilePatchRequest;
import cz.bankintel.domain.dto.MeDtos.ReorderPagesRequest;
import cz.bankintel.domain.dto.MeDtos.ReorderWidgetsRequest;
import cz.bankintel.domain.dto.MeDtos.RenderWidgetsRequest;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.service.me.MeComputedService;
import cz.bankintel.service.me.MeDashboardPageExportService;
import cz.bankintel.service.me.MeDashboardService;
import cz.bankintel.service.me.MeDashboardWidgetCatalogExportService;
import cz.bankintel.service.me.MeDashboardWidgetRenderService;
import cz.bankintel.service.me.MeUploadChartsService;
import cz.bankintel.service.me.PdfExtractService;
import cz.bankintel.service.me.MeUserUploadChartPreviewService;
import cz.bankintel.service.me.MeUserUploadPreviewService;
import cz.bankintel.service.me.MeUserUploadService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final CurrentUser currentUser;
    private final MeDashboardService meDashboardService;
    private final MeUserUploadService meUserUploadService;
    private final MeUserUploadPreviewService meUserUploadPreviewService;
    private final MeUserUploadChartPreviewService meUserUploadChartPreviewService;
    private final MeDashboardWidgetRenderService meDashboardWidgetRenderService;
    private final MeDashboardPageExportService meDashboardPageExportService;
    private final MeComputedService meComputedService;
    private final MeUploadChartsService meUploadChartsService;
    private final MeDashboardWidgetCatalogExportService meDashboardWidgetCatalogExportService;
    private final PdfExtractService pdfExtractService;

    @GetMapping("/dashboard/pages")
    public List<Map<String, Object>> listPages() {
        return meDashboardService.listPages(currentUser.requireUserEntity());
    }

    @PostMapping("/dashboard/pages")
    public Map<String, Object> createPage(@Valid @RequestBody DashboardPageCreateRequest body) {
        return meDashboardService.createPage(currentUser.requireUserEntity(), body);
    }

    @PatchMapping("/dashboard/pages/{pageId}")
    public Map<String, Object> patchPage(
            @PathVariable String pageId, @RequestBody DashboardPagePatchRequest body) {
        return meDashboardService.patchPage(currentUser.requireUserEntity(), pageId, body);
    }

    @DeleteMapping("/dashboard/pages/{pageId}")
    public Map<String, Boolean> deletePage(@PathVariable String pageId) {
        meDashboardService.deletePage(currentUser.requireUserEntity(), pageId);
        return MeDtos.okMap();
    }

    @PostMapping("/dashboard/pages/reorder")
    public Map<String, Boolean> reorderPages(@Valid @RequestBody ReorderPagesRequest body) {
        meDashboardService.reorderPages(currentUser.requireUserEntity(), body);
        return MeDtos.okMap();
    }

    @GetMapping("/dashboard/pages/{pageId}/widgets")
    public List<Map<String, Object>> listWidgets(@PathVariable String pageId) {
        return meDashboardService.listWidgets(currentUser.requireUserEntity(), pageId);
    }

    @PostMapping("/dashboard/pages/{pageId}/widgets")
    public Map<String, Object> createWidget(
            @PathVariable String pageId, @Valid @RequestBody DashboardWidgetCreateRequest body) {
        return meDashboardService.createWidget(currentUser.requireUserEntity(), pageId, body);
    }

    @PatchMapping("/dashboard/widgets/{widgetId}")
    public Map<String, Object> patchWidget(
            @PathVariable String widgetId, @RequestBody DashboardWidgetPatchRequest body) {
        return meDashboardService.patchWidget(currentUser.requireUserEntity(), widgetId, body);
    }

    @DeleteMapping("/dashboard/widgets/{widgetId}")
    public Map<String, Boolean> deleteWidget(@PathVariable String widgetId) {
        meDashboardService.deleteWidget(currentUser.requireUserEntity(), widgetId);
        return MeDtos.okMap();
    }

    @PostMapping("/dashboard/widgets/reorder")
    public Map<String, Boolean> reorderWidgets(@Valid @RequestBody ReorderWidgetsRequest body) {
        meDashboardService.reorderWidgets(currentUser.requireUserEntity(), body);
        return MeDtos.okMap();
    }

    @PostMapping("/dashboard/render-widget")
    public Map<String, Object> renderDashboardWidget(@RequestBody Map<String, Object> body) {
        String widgetId = body.get("id") != null ? String.valueOf(body.get("id")) : "";
        boolean forceRefresh = Boolean.TRUE.equals(body.get("force_refresh"));
        return meDashboardWidgetRenderService.renderWidget(currentUser.requireUserEntity(), widgetId, forceRefresh);
    }

    @PostMapping("/dashboard/render-widgets")
    public Map<String, Object> renderDashboardWidgets(@Valid @RequestBody RenderWidgetsRequest body) {
        return meDashboardWidgetRenderService.renderWidgets(
                currentUser.requireUserEntity(),
                body.ids(),
                body.forceRefreshIds() != null ? body.forceRefreshIds() : List.of());
    }

    @PostMapping("/extract-table-from-pdf")
    public Map<String, Object> extractTableFromPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "header_row", defaultValue = "1") int headerRow,
            @RequestParam(name = "table_index", defaultValue = "-1") int tableIndex) {
        return pdfExtractService.extractTableFromPdf(
                currentUser.requireUserEntity(), file, page, headerRow, tableIndex);
    }

    @PostMapping("/extract-chart-from-pdf")
    public Map<String, Object> extractChartFromPdf(
            @RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "1") int page) {
        return pdfExtractService.extractChartFromPdf(currentUser.requireUserEntity(), file, page);
    }

    @GetMapping("/dashboard/default")
    public Map<String, Object> defaultDashboard() {
        return meDashboardService.getDefaultDashboard(currentUser.requireUserEntity());
    }

    @GetMapping("/preferences")
    public MeDashboardService.PreferencesResponse preferences() {
        return meDashboardService.getPreferences(currentUser.requireUserEntity());
    }

    @PatchMapping("/preferences")
    public MeResponse patchPreferences(@RequestBody PreferencesPatchRequest body) {
        return meDashboardService.patchPreferences(currentUser.requireUserEntity(), body);
    }

    @PatchMapping("/profile")
    public MeResponse patchProfile(@RequestBody ProfilePatchRequest body) {
        return meDashboardService.patchProfile(currentUser.requireUserEntity(), body);
    }

    @PostMapping("/change-password")
    public Map<String, Boolean> changePassword(@Valid @RequestBody ChangePasswordRequest body) {
        meDashboardService.changePassword(currentUser.requireUserEntity(), body);
        return MeDtos.okMap();
    }

    @GetMapping("/admin-nav-order")
    public MeDashboardService.NavOrderResponse adminNavOrder() {
        return meDashboardService.getAdminNavOrder(currentUser.requireUserEntity());
    }

    @PutMapping("/admin-nav-order")
    public MeDashboardService.NavOrderResponse putAdminNavOrder(@Valid @RequestBody NavOrderPutRequest body) {
        return meDashboardService.putAdminNavOrder(currentUser.requireUserEntity(), body);
    }

    @GetMapping("/user-nav-order")
    public MeDashboardService.NavOrderResponse userNavOrder() {
        return meDashboardService.getUserNavOrder(currentUser.requireUserEntity());
    }

    @PutMapping("/user-nav-order")
    public MeDashboardService.NavOrderResponse putUserNavOrder(@Valid @RequestBody NavOrderPutRequest body) {
        return meDashboardService.putUserNavOrder(currentUser.requireUserEntity(), body);
    }

    @GetMapping("/uploads")
    public List<Map<String, Object>> listUploads() {
        return meUserUploadService.listUploads(currentUser.requireUserEntity());
    }

    @PostMapping("/uploads")
    public Map<String, Object> uploadFile(@RequestParam("file") MultipartFile file) {
        return meUserUploadService.upload(currentUser.requireUserEntity(), file);
    }

    @DeleteMapping("/uploads/{uploadId}")
    public Map<String, Object> deleteUpload(@PathVariable String uploadId) {
        return meUserUploadService.deleteUpload(currentUser.requireUserEntity(), uploadId);
    }

    @GetMapping("/uploads/{uploadId}/file")
    public ResponseEntity<Resource> downloadUpload(@PathVariable String uploadId) {
        return meUserUploadService.download(currentUser.requireUserEntity(), uploadId);
    }

    @GetMapping("/uploads/{uploadId}/preview")
    public Map<String, Object> previewUpload(
            @PathVariable String uploadId, @RequestParam(defaultValue = "8") int rows) {
        return meUserUploadPreviewService.preview(currentUser.requireUserEntity(), uploadId, rows);
    }

    @PostMapping("/uploads/{uploadId}/chart-preview")
    public Map<String, Object> chartPreviewUpload(
            @PathVariable String uploadId, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> config = body != null && body.get("config") instanceof Map<?, ?> cfg
                ? castMap(cfg)
                : Map.of();
        return meUserUploadChartPreviewService.chartPreview(
                currentUser.requireUserEntity(), uploadId, config);
    }

    @PostMapping("/dashboard/pages/{pageId}/export.xlsx")
    public ResponseEntity<Resource> exportDashboardPage(@PathVariable String pageId) {
        byte[] data = meDashboardPageExportService.exportPageXlsx(currentUser.requireUserEntity(), pageId);
        String filename = "dashboard.xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(new ByteArrayResource(data));
    }

    @PostMapping("/computed")
    public Map<String, Object> createComputed(@RequestBody Map<String, Object> payload) {
        return meComputedService.createComputed(currentUser.requireUserEntity(), payload);
    }

    @GetMapping("/upload-charts")
    public List<Map<String, Object>> listUploadCharts() {
        return meUploadChartsService.listUploadCharts(currentUser.requireUserEntity());
    }

    @PostMapping("/dashboard/widgets/{widgetId}/export-catalog")
    public ResponseEntity<byte[]> exportCatalogWidget(
            @PathVariable String widgetId, @RequestBody(required = false) Map<String, Object> payload) {
        return meDashboardWidgetCatalogExportService.exportCatalogWidget(
                currentUser.requireUserEntity(), widgetId, payload);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
