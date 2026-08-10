package cz.bankintel.controller.export;

import cz.bankintel.domain.entity.FormulaEntity;
import cz.bankintel.repository.FormulaRepository;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.service.export.ExportService;
import cz.bankintel.service.formula.FormulaService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;
    private final FormulaService formulaService;
    private final FormulaRepository formulaRepository;
    private final CurrentUser currentUser;

    @GetMapping("/dataset/{datasetId}.xlsx")
    public ResponseEntity<byte[]> exportDatasetXlsx(
            @PathVariable String datasetId, @RequestParam(defaultValue = "5000") int limit) {
        exportService.requireExportAccess(currentUser.optionalUserEntity());
        exportService.ensureDatasetExists(datasetId);
        byte[] data = exportService.exportDatasetXlsx(datasetId, Math.min(Math.max(limit, 1), 20_000));
        return attachment(data, "dataset-" + datasetId + ".xlsx", spreadsheetType());
    }

    @GetMapping("/dataset/{datasetId}.pdf")
    public ResponseEntity<byte[]> exportDatasetPdf(
            @PathVariable String datasetId, @RequestParam(defaultValue = "500") int limit) {
        exportService.requireExportAccess(currentUser.optionalUserEntity());
        exportService.ensureDatasetExists(datasetId);
        byte[] data = exportService.exportDatasetPdf(datasetId, Math.min(Math.max(limit, 1), 2000));
        return attachment(data, "dataset-" + datasetId + ".pdf", MediaType.APPLICATION_PDF);
    }

    @PostMapping("/widget.xlsx")
    public ResponseEntity<byte[]> exportWidgetXlsx(@RequestBody Map<String, Object> payload) {
        exportService.requireExportAccess(currentUser.optionalUserEntity());
        byte[] data = exportService.exportWidgetXlsx(payload);
        String title = payload.get("title") != null ? String.valueOf(payload.get("title")) : "Export";
        String filename = exportService.safeFilename(
                        payload.get("filename") != null ? String.valueOf(payload.get("filename")) : title)
                + ".xlsx";
        return attachment(data, filename, spreadsheetType());
    }

    @PostMapping("/chart.xlsx")
    public ResponseEntity<byte[]> exportChartXlsx(@RequestBody Map<String, Object> payload) {
        exportService.requireExportAccess(currentUser.optionalUserEntity());
        byte[] data = exportService.exportChartXlsx(payload);
        String title = payload.get("title") != null ? String.valueOf(payload.get("title")) : "Chart export";
        String filename = exportService.safeFilename(
                        payload.get("filename") != null ? String.valueOf(payload.get("filename")) : title)
                + ".xlsx";
        return attachment(data, filename, spreadsheetType());
    }

    @PostMapping("/widget.pdf")
    public ResponseEntity<byte[]> exportWidgetPdf(@RequestBody Map<String, Object> payload) {
        exportService.requireExportAccess(currentUser.optionalUserEntity());
        byte[] data = exportService.exportWidgetPdf(payload);
        String title = payload.get("title") != null ? String.valueOf(payload.get("title")) : "Export";
        String filename = exportService.safeFilename(
                        payload.get("filename") != null ? String.valueOf(payload.get("filename")) : title)
                + ".pdf";
        return attachment(data, filename, MediaType.APPLICATION_PDF);
    }

    @GetMapping("/formula/{formulaId}.xlsx")
    public ResponseEntity<byte[]> exportFormulaXlsx(@PathVariable String formulaId) {
        exportService.requireExportAccess(currentUser.optionalUserEntity());
        FormulaEntity formula = formulaRepository
                .findById(formulaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Formula not found"));
        Map<String, Object> computed = formulaService.computeFormula(formula);
        byte[] data = exportService.exportFormulaXlsx(formula, computed);
        return attachment(data, "formula-" + exportService.safeFilename(formula.getName()) + ".xlsx", spreadsheetType());
    }

    @GetMapping("/formula/{formulaId}.pdf")
    public ResponseEntity<byte[]> exportFormulaPdf(@PathVariable String formulaId) {
        exportService.requireExportAccess(currentUser.optionalUserEntity());
        FormulaEntity formula = formulaRepository
                .findById(formulaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Formula not found"));
        Map<String, Object> computed = formulaService.computeFormula(formula);
        byte[] data = exportService.exportFormulaPdf(formula, computed);
        return attachment(data, "formula-" + exportService.safeFilename(formula.getName()) + ".pdf", MediaType.APPLICATION_PDF);
    }

    private static ResponseEntity<byte[]> attachment(byte[] data, String filename, MediaType mediaType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(data);
    }

    private static MediaType spreadsheetType() {
        return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }
}
