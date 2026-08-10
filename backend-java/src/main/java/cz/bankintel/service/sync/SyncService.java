package cz.bankintel.service.sync;

import cz.bankintel.connector.BaseConnector;
import cz.bankintel.connector.ConnectorFactory;
import cz.bankintel.connector.ConnectorFetchResult;
import cz.bankintel.domain.entity.DatasetEntity;
import cz.bankintel.domain.entity.RecordEntity;
import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.domain.entity.SyncLogEntity;
import cz.bankintel.repository.DatasetRepository;
import cz.bankintel.repository.RecordRepository;
import cz.bankintel.repository.SourceRepository;
import cz.bankintel.repository.SyncLogRepository;
import cz.bankintel.service.sources.SourceConnectorMapper;
import cz.bankintel.util.IdGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Synchronizace zdroje: konektor → normalizované řádky → upsert do {@code records}.
 *
 * <p>Port Python {@code services/sync_service.py}. Běží asynchronně po {@code POST /api/sources/{id}/sync}.
 */
@Service
@RequiredArgsConstructor
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final SourceRepository sourceRepository;
    private final SyncLogRepository syncLogRepository;
    private final DatasetRepository datasetRepository;
    private final RecordRepository recordRepository;
    private final ConnectorFactory connectorFactory;
    private final ObjectProvider<SyncService> self;

    @Transactional
    public Map<String, Object> queueSync(String sourceId) {
        SourceEntity source = sourceRepository
                .findById(sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found"));

        if ("running".equals(source.getLastSyncStatus())) {
            return Map.of("status", "already_running", "source_id", sourceId);
        }

        Instant now = Instant.now();
        source.setLastSyncStatus("running");
        source.setLastSyncAt(now);
        source.setSyncState("running");
        sourceRepository.save(source);

        CompletableFuture.runAsync(() -> {
            try {
                self.getObject().runSync(sourceId);
            } catch (Exception ex) {
                log.error("Background sync failed source_id={}", sourceId, ex);
            }
        });

        log.info("SYNC HTTP queued source_id={} source_type={}", sourceId, source.getSourceType());
        return Map.of("status", "queued", "source_id", sourceId);
    }

    @Transactional
    public Map<String, Object> runSync(String sourceId) {
        SourceEntity source = sourceRepository
                .findById(sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found"));

        String logId = IdGenerator.newId();
        Instant started = Instant.now();

        SyncLogEntity syncLog = new SyncLogEntity();
        syncLog.setId(logId);
        syncLog.setSourceId(sourceId);
        syncLog.setSourceName(source.getName());
        syncLog.setStatus("running");
        syncLog.setStartedAt(started);
        syncLog.setRecordsIngested(0);
        syncLog.setMessage("");
        syncLogRepository.save(syncLog);

        markSourceRunning(source, logId, started);
        sourceRepository.save(source);

        try {
            return executePipeline(source, sourceId, logId, started);
        } catch (Exception ex) {
            log.error("Sync failed source_id={}", sourceId, ex);
            return finalizeError(source, syncLog, started, ex.getMessage(), "upstream_error", previewText(ex.getMessage()));
        }
    }

    private Map<String, Object> executePipeline(
            SourceEntity source, String sourceId, String logId, Instant started) {
        String sourceType = ConnectorFactory.normalizeSourceType(source.getSourceType());

        if (!connectorFactory.isSupported(sourceType)) {
            String message = "unsupported source_type: " + sourceType;
            SyncLogEntity syncLog = syncLogRepository.findById(logId).orElseThrow();
            return finalizeError(source, syncLog, started, message, "upstream_error", message);
        }

        Map<String, Object> connectorSource = SourceConnectorMapper.toConnectorSource(source);
        BaseConnector connector = connectorFactory.get(sourceType);

        log.info(
                "SYNC START source_id={} source_type={} name={}",
                sourceId,
                sourceType,
                source.getName());

        ConnectorFetchResult fetchResult = connector.fetch(connectorSource);
        Object raw = fetchResult.raw();
        SyncLogEntity syncLog = syncLogRepository.findById(logId).orElseThrow();

        if (raw instanceof Map<?, ?> rawMap && rawMap.get("error") != null) {
            String message = connectorErrorMessage(rawMap, sourceType);
            String reason = reasonFromConnectorError(sourceType, fetchResult.httpStatus(), rawMap, message);
            return finalizeConnectorError(source, syncLog, started, fetchResult.httpStatus(), message, reason, previewFromRaw(rawMap));
        }

        List<Map<String, Object>> records = connector.parse(raw, connectorSource);
        if (records == null) {
            records = List.of();
        }

        log.info(
                "SYNC FETCH DONE source_id={} source_type={} http_status={} extracted_rows={}",
                sourceId,
                sourceType,
                fetchResult.httpStatus(),
                records.size());

        String datasetName = source.getDatasetName() != null && !source.getDatasetName().isBlank()
                ? source.getDatasetName()
                : source.getName();
        DatasetEntity dataset = datasetRepository
                .findByName(datasetName)
                .orElseGet(() -> createDataset(datasetName, sourceId, source.getName()));

        if (fetchResult.isSuccess() && records.isEmpty()) {
            long total = recordRepository.countByDatasetId(dataset.getId());
            String message =
                    "Stažení proběhlo bez chyby API, ale parsování nevrátilo řádek "
                            + "(prázdná sada dat, nekompatibilní struktura JSON nebo filtry bez výsledků).";
            return finalizePartial(source, syncLog, dataset, started, fetchResult.httpStatus(), message, "empty_response", previewFromRaw(raw), 0, total);
        }

        Set<String> allFields = new LinkedHashSet<>();
        int ingested = 0;
        Set<String> seenKeys = new HashSet<>();

        for (Map<String, Object> record : records) {
            if (record == null || record.isEmpty()) {
                continue;
            }
            allFields.addAll(record.keySet());
            String key = RecordKeyUtil.recordKey(record, sourceType);
            if (!seenKeys.add(key)) {
                continue;
            }
            if (recordRepository.findByDatasetIdAndDedupeKey(dataset.getId(), key).isEmpty()) {
                RecordEntity entity = new RecordEntity();
                entity.setId(IdGenerator.newId());
                entity.setDatasetId(dataset.getId());
                entity.setSourceId(sourceId);
                entity.setDedupeKey(key);
                entity.setData(record);
                recordRepository.save(entity);
                ingested++;
            }
        }

        long total = recordRepository.countByDatasetId(dataset.getId());
        dataset.setFields(new ArrayList<>(allFields));
        dataset.setRecordCount((int) Math.min(total, Integer.MAX_VALUE));
        datasetRepository.save(dataset);

        Instant finished = Instant.now();
        int durationMs = durationMs(started, finished);
        String message = "Ingested " + ingested + " new records (total " + total + ").";

        syncLog.setStatus("success");
        syncLog.setFinishedAt(finished);
        syncLog.setRecordsIngested(ingested);
        syncLog.setHttpStatus(fetchResult.httpStatus());
        syncLog.setMessage(message);
        syncLog.setDurationMs(durationMs);
        syncLog.setReasonCode(null);
        syncLog.setResponsePreview(null);
        syncLogRepository.save(syncLog);

        source.setLastSyncAt(finished);
        source.setLastSyncStartedAt(started);
        source.setLastSyncFinishedAt(finished);
        source.setLastSyncDurationMs(durationMs);
        source.setLastSyncStatus("success");
        source.setLastSyncMessage("");
        source.setLastSyncRecordsIngested(ingested);
        source.setLastSyncHttpStatus(fetchResult.httpStatus());
        source.setLastSyncError("");
        source.setLastSyncReasonCode(null);
        source.setLastSyncResponsePreview(null);
        source.setSyncState("synced");
        source.setSyncQueueState(null);
        source.setSyncRetryAfterSec(null);
        source.setSyncRetryAt(null);
        sourceRepository.save(source);

        log.info(
                "SYNC COMPLETE source_id={} source_type={} ingested={} total={}",
                sourceId,
                sourceType,
                ingested,
                total);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "success");
        out.put("ingested", ingested);
        out.put("total", total);
        out.put("log_id", logId);
        out.put("sync_state", "synced");
        return out;
    }

    private DatasetEntity createDataset(String datasetName, String sourceId, String sourceName) {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId(IdGenerator.newId());
        dataset.setName(datasetName);
        dataset.setSourceId(sourceId);
        dataset.setSourceName(sourceName);
        dataset.setFields(List.of());
        dataset.setRecordCount(0);
        return datasetRepository.save(dataset);
    }

    private Map<String, Object> finalizeConnectorError(
            SourceEntity source,
            SyncLogEntity syncLog,
            Instant started,
            int httpStatus,
            String message,
            String reason,
            String preview) {
        Instant finished = Instant.now();
        int durationMs = durationMs(started, finished);
        Integer httpForSource = httpStatus > 0 ? httpStatus : null;
        String msg = truncate(message, 650);

        syncLog.setStatus("error");
        syncLog.setFinishedAt(finished);
        syncLog.setRecordsIngested(0);
        syncLog.setHttpStatus(httpForSource);
        syncLog.setMessage(msg);
        syncLog.setDurationMs(durationMs);
        syncLog.setReasonCode(reason);
        syncLog.setResponsePreview(preview);
        syncLogRepository.save(syncLog);

        updateSourceFailure(source, started, finished, durationMs, "error", msg, reason, preview, httpForSource, null);
        sourceRepository.save(source);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "error");
        out.put("ingested", 0);
        out.put("total", 0);
        out.put("log_id", syncLog.getId());
        out.put("message", msg);
        out.put("sync_state", "sync_failed");
        return out;
    }

    private Map<String, Object> finalizePartial(
            SourceEntity source,
            SyncLogEntity syncLog,
            DatasetEntity dataset,
            Instant started,
            int httpStatus,
            String message,
            String reason,
            String preview,
            int ingested,
            long total) {
        Instant finished = Instant.now();
        int durationMs = durationMs(started, finished);
        String msg = truncate(message, 650);

        syncLog.setStatus("partial");
        syncLog.setFinishedAt(finished);
        syncLog.setRecordsIngested(ingested);
        syncLog.setHttpStatus(httpStatus);
        syncLog.setMessage(msg);
        syncLog.setDurationMs(durationMs);
        syncLog.setReasonCode(reason);
        syncLog.setResponsePreview(preview);
        syncLogRepository.save(syncLog);

        source.setLastSyncAt(finished);
        source.setLastSyncStartedAt(started);
        source.setLastSyncFinishedAt(finished);
        source.setLastSyncDurationMs(durationMs);
        source.setLastSyncStatus("partial");
        source.setLastSyncMessage(msg);
        source.setLastSyncRecordsIngested(ingested);
        source.setLastSyncHttpStatus(httpStatus);
        source.setLastSyncError("");
        source.setLastSyncReasonCode(reason);
        source.setLastSyncResponsePreview(preview);
        source.setSyncState("synced_empty");
        source.setSyncQueueState(null);
        source.setSyncRetryAfterSec(null);
        source.setSyncRetryAt(null);
        sourceRepository.save(source);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "partial");
        out.put("ingested", ingested);
        out.put("total", total);
        out.put("log_id", syncLog.getId());
        out.put("sync_state", "synced_empty");
        return out;
    }

    private Map<String, Object> finalizeError(
            SourceEntity source,
            SyncLogEntity syncLog,
            Instant started,
            String message,
            String reason,
            String preview) {
        Instant finished = Instant.now();
        int durationMs = durationMs(started, finished);
        String msg = truncate(message, 650);

        syncLog.setStatus("error");
        syncLog.setFinishedAt(finished);
        syncLog.setRecordsIngested(0);
        syncLog.setMessage(msg);
        syncLog.setDurationMs(durationMs);
        syncLog.setReasonCode(reason);
        syncLog.setResponsePreview(preview);
        syncLogRepository.save(syncLog);

        updateSourceFailure(source, started, finished, durationMs, "error", msg, reason, preview, null, null);
        sourceRepository.save(source);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "error");
        out.put("message", msg);
        out.put("log_id", syncLog.getId());
        out.put("sync_state", "sync_failed");
        return out;
    }

    private static void markSourceRunning(SourceEntity source, String logId, Instant started) {
        source.setLastSyncStatus("running");
        source.setLastSyncAt(started);
        source.setLastSyncStartedAt(started);
        source.setLastSyncFinishedAt(null);
        source.setLastSyncLogId(logId);
        source.setLastSyncMessage("");
        source.setLastSyncRecordsIngested(null);
        source.setLastSyncHttpStatus(null);
        source.setLastSyncError("");
        source.setLastSyncReasonCode(null);
        source.setLastSyncResponsePreview(null);
        source.setSyncState("running");
        source.setSyncQueueState(null);
        source.setSyncRetryAfterSec(null);
        source.setSyncRetryAt(null);
    }

    private static void updateSourceFailure(
            SourceEntity source,
            Instant started,
            Instant finished,
            int durationMs,
            String status,
            String message,
            String reason,
            String preview,
            Integer httpStatus,
            Integer recordsIngested) {
        source.setLastSyncAt(finished);
        source.setLastSyncStartedAt(started);
        source.setLastSyncFinishedAt(finished);
        source.setLastSyncDurationMs(durationMs);
        source.setLastSyncStatus(status);
        source.setLastSyncMessage(message);
        source.setLastSyncRecordsIngested(recordsIngested);
        source.setLastSyncHttpStatus(httpStatus);
        source.setLastSyncError(message);
        source.setLastSyncReasonCode(reason);
        source.setLastSyncResponsePreview(preview);
        source.setSyncState("sync_failed");
        source.setSyncQueueState(null);
        source.setSyncRetryAfterSec(null);
        source.setSyncRetryAt(null);
    }

    private static String connectorErrorMessage(Map<?, ?> raw, String sourceType) {
        Object err = raw.get("error");
        String errTail = err != null ? truncate(String.valueOf(err), 500) : "Connector error";
        Object detailCs = raw.get("detail_cs");
        if (detailCs != null && !String.valueOf(detailCs).isBlank()) {
            if ("imf".equals(sourceType)) {
                return truncate(String.valueOf(detailCs) + " [" + errTail + "]", 620);
            }
            return truncate(String.valueOf(detailCs), 520);
        }
        return errTail;
    }

    private static String reasonFromConnectorError(
            String sourceType, int httpStatus, Map<?, ?> raw, String messageTail) {
        Object errObj = raw.get("error");
        String err = errObj != null ? String.valueOf(errObj) : "";
        if ("upstream_timeout_or_network".equals(errObj)
                || (httpStatus == 0 && err.toLowerCase().contains("upstream"))) {
            return "timeout";
        }
        if (err.toLowerCase().contains("invalid_json") || err.toLowerCase().contains("invalid json")) {
            return "parser_error";
        }
        Object statusObj = raw.get("status");
        int hs = httpStatus > 0 ? httpStatus : (statusObj instanceof Number n ? n.intValue() : 0);
        if (hs == 429) {
            return "rate_limited";
        }
        return inferTerminalSyncReason(messageTail + " " + err, hs, sourceType);
    }

    private static String inferTerminalSyncReason(String message, int httpStatus, String sourceType) {
        if (httpStatus == 429) {
            return "rate_limited";
        }
        if (httpStatus == 404) {
            if ("imf".equalsIgnoreCase(sourceType)) {
                return "invalid_dataset_or_key";
            }
            if ("worldbank".equalsIgnoreCase(sourceType)) {
                return "invalid_indicator";
            }
            return "upstream_error";
        }
        String m = message != null ? message.toLowerCase() : "";
        if (m.contains("prázdn") || m.contains("0 řádků") || m.contains("nevrátilo řádek")) {
            return "empty_response";
        }
        if (m.contains("rate limit") || m.contains("omezuje počet dotazů")) {
            return "rate_limited";
        }
        if (m.contains("invalid json") || m.contains("invalid_json") || m.contains("kompatibilní strukturu")) {
            return "parser_error";
        }
        if (m.contains("timeout") || m.contains("vyprš")) {
            return "timeout";
        }
        return "upstream_error";
    }

    private static String previewFromRaw(Object raw) {
        if (raw == null) {
            return null;
        }
        return truncate(String.valueOf(raw), 500);
    }

    private static String previewText(String text) {
        return truncate(text, 500);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static int durationMs(Instant started, Instant finished) {
        return (int) Math.max(0, Duration.between(started, finished).toMillis());
    }
}
