package cz.bankintel.service.dashboard;

import cz.bankintel.domain.entity.DatasetEntity;
import cz.bankintel.domain.entity.SyncLogEntity;
import cz.bankintel.repository.DatasetRepository;
import cz.bankintel.repository.FormulaRepository;
import cz.bankintel.repository.RecordRepository;
import cz.bankintel.repository.SourceRepository;
import cz.bankintel.repository.SyncLogRepository;
import cz.bankintel.service.formula.FormulaService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final SourceRepository sourceRepository;
    private final DatasetRepository datasetRepository;
    private final RecordRepository recordRepository;
    private final FormulaRepository formulaRepository;
    private final SyncLogRepository syncLogRepository;
    private final FormulaService formulaService;

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        long totalSources = sourceRepository.count();
        long activeSources = sourceRepository.countByActiveTrue();
        long totalRecords = recordRepository.count();
        long totalDatasets = datasetRepository.count();
        long totalFormulas = formulaRepository.count();
        long sourcesErrors = sourceRepository.countByLastSyncStatus("error");

        List<Map<String, Object>> kpis = List.of(
                kpi("Active Sources", activeSources, "flat", "", totalSources + " total"),
                kpi("Datasets", totalDatasets, "flat", "", totalFormulas + " formulas"),
                kpi("Records Ingested", totalRecords, "up", "", "across all datasets"),
                kpi(
                        "Sync Errors",
                        sourcesErrors,
                        sourcesErrors == 0 ? "down" : "up",
                        "",
                        "connectors in error state"));

        double netTotal = 0.0;
        List<Map<String, Object>> netTrend = List.of();
        var netResult = formulaService.computeFormulaByName("Net Result", List.of("date"));
        if (netResult.containsKey("total")) {
            Object total = netResult.get("total");
            if (total instanceof Number number) {
                netTotal = number.doubleValue();
            }
        }
        if (netResult.get("rows") instanceof List<?> rows) {
            int from = Math.max(0, rows.size() - 30);
            netTrend = rows.subList(from, rows.size()).stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(DashboardService::objectMap)
                    .toList();
        }

        List<Map<String, Object>> distribution = new ArrayList<>();
        for (DatasetEntity dataset : datasetRepository.findAllByOrderByNameAsc()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", dataset.getName());
            item.put("count", dataset.getRecordCount());
            distribution.add(item);
        }

        List<Map<String, Object>> recentLogs = syncLogRepository
                .findAllByOrderByStartedAtDesc(PageRequest.of(0, 8))
                .stream()
                .map(this::toSyncLog)
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpis", kpis);
        out.put("net_result_total", netTotal);
        out.put("net_result_trend", netTrend);
        out.put("dataset_distribution", distribution);
        out.put("recent_sync_logs", recentLogs);
        return out;
    }

    private Map<String, Object> kpi(String label, long value, String trend, String unit, String hint) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", label);
        out.put("value", value);
        out.put("unit", unit);
        out.put("trend", trend);
        out.put("hint", hint);
        return out;
    }

    private Map<String, Object> toSyncLog(SyncLogEntity log) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", log.getId());
        out.put("source_id", log.getSourceId());
        out.put("source_name", log.getSourceName());
        out.put("status", log.getStatus());
        out.put("started_at", log.getStartedAt() != null ? log.getStartedAt().toString() : null);
        out.put("finished_at", log.getFinishedAt() != null ? log.getFinishedAt().toString() : null);
        out.put("records_ingested", log.getRecordsIngested());
        out.put("message", log.getMessage());
        out.put("http_status", log.getHttpStatus());
        out.put("duration_ms", log.getDurationMs());
        out.put("reason_code", log.getReasonCode());
        out.put("response_preview", log.getResponsePreview());
        return out;
    }

    private static Map<String, Object> objectMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }
}
