package cz.bankintel.controller.sources;

import cz.bankintel.domain.dto.SourceDtos.SourceCreateRequest;
import cz.bankintel.domain.dto.SourceDtos.SourceUpdateRequest;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.service.sources.SourceAdminFileUploadService;
import cz.bankintel.service.sources.SourceAradIndicatorService;
import cz.bankintel.service.sources.SourceCatalogStubService;
import cz.bankintel.service.sources.SourceConnectionTestService;
import cz.bankintel.service.sources.SourceFileMetaService;
import cz.bankintel.service.sources.SourceIndicatorCatalogService;
import cz.bankintel.service.sources.SourcePreviewService;
import cz.bankintel.service.sources.SourceService;
import cz.bankintel.service.sync.SyncService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/sources")
@RequiredArgsConstructor
public class SourcesController {

    private final AdminAccess adminAccess;
    private final SourceService sourceService;
    private final SyncService syncService;
    private final SourcePreviewService sourcePreviewService;
    private final SourceIndicatorCatalogService sourceIndicatorCatalogService;
    private final SourceConnectionTestService sourceConnectionTestService;
    private final SourceFileMetaService sourceFileMetaService;
    private final SourceAdminFileUploadService sourceAdminFileUploadService;
    private final SourceCatalogStubService sourceCatalogStubService;
    private final SourceAradIndicatorService sourceAradIndicatorService;

    @GetMapping("/types")
    public Map<String, Object> listTypes() {
        adminAccess.requireAdmin();
        return sourceService.listTypes();
    }

    @GetMapping("/catalog-stubs")
    public List<Map<String, Object>> catalogStubs() {
        return sourceCatalogStubService.listCatalogStubs();
    }

    @GetMapping("/file-meta")
    public Map<String, Object> fileMeta(
            @RequestParam String path,
            @RequestParam(required = false) String sheet,
            @RequestParam(name = "max_preview_rows", defaultValue = "200") int maxPreviewRows) {
        adminAccess.requireAdmin();
        return sourceFileMetaService.fileMeta(path, sheet, maxPreviewRows);
    }

    @PostMapping("/upload-file")
    public Map<String, Object> uploadFile(@RequestParam("file") MultipartFile file) {
        adminAccess.requireAdmin();
        return sourceAdminFileUploadService.uploadFile(file);
    }

    @PostMapping("/pdf-extract-preview")
    public Map<String, Object> pdfExtractPreview(@RequestBody Map<String, Object> body) {
        adminAccess.requireAdmin();
        String path = body.get("path") != null ? String.valueOf(body.get("path")) : "";
        @SuppressWarnings("unchecked")
        Map<String, Object> queryParams =
                body.get("query_params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return sourceAdminFileUploadService.pdfExtractPreview(path, queryParams);
    }

    @GetMapping({"", "/"})
    public List<Map<String, Object>> listSources() {
        adminAccess.requireAdmin();
        return sourceService.listSources();
    }

    @GetMapping("/{id}/indicator-catalog")
    public Map<String, Object> indicatorCatalog(@PathVariable String id) {
        return sourceIndicatorCatalogService.indicatorCatalog(id);
    }

    @GetMapping("/{id}/preview")
    public Map<String, Object> previewSource(
            @PathVariable String id,
            @RequestParam(defaultValue = "80") int limit,
            @RequestParam(name = "indicator_id", required = false) String indicatorId,
            @RequestParam(name = "indicator_ids", required = false) String indicatorIds,
            @RequestParam(name = "dimension_filters", required = false) String dimensionFilters) {
        return sourcePreviewService.preview(id, limit, indicatorId, indicatorIds, dimensionFilters);
    }

    @GetMapping("/{id}")
    public Map<String, Object> getSource(@PathVariable String id) {
        adminAccess.requireAdmin();
        return sourceService.getSource(id);
    }

    @PostMapping({"", "/"})
    public Map<String, Object> createSource(@Valid @RequestBody SourceCreateRequest request) {
        adminAccess.requireAdmin();
        return sourceService.createSource(request);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> updateSource(@PathVariable String id, @RequestBody SourceUpdateRequest request) {
        adminAccess.requireAdmin();
        return sourceService.updateSource(id, request);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteSource(@PathVariable String id) {
        adminAccess.requireAdmin();
        return sourceService.deleteSource(id);
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> testSource(@PathVariable String id) {
        adminAccess.requireAdmin();
        return sourceConnectionTestService.testConnection(id);
    }

    @PostMapping("/{id}/sync")
    public Map<String, Object> syncSource(@PathVariable String id) {
        adminAccess.requireAdmin();
        return syncService.queueSync(id);
    }

    @PostMapping("/{id}/sync-public")
    public Map<String, Object> syncSourcePublic(@PathVariable String id) {
        return syncService.queueSync(id);
    }

    @GetMapping("/{id}/arad/indicators")
    public List<Map<String, Object>> listAradIndicators(@PathVariable String id) {
        return sourceAradIndicatorService.listIndicators(id);
    }

    @PostMapping("/{id}/arad/refresh-indicators")
    public Map<String, Object> refreshAradIndicators(@PathVariable String id) {
        adminAccess.requireAdmin();
        return sourceAradIndicatorService.refreshIndicators(id);
    }
}
