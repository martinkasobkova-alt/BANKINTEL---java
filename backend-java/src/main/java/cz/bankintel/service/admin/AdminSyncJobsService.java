package cz.bankintel.service.admin;

import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.domain.entity.SyncLogEntity;
import cz.bankintel.repository.SourceRepository;
import cz.bankintel.repository.SyncLogRepository;
import cz.bankintel.service.sync.SyncService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
public class AdminSyncJobsService {

    private static final String MANUAL_RESET_MESSAGE = "Manually reset by admin";

    private final SourceRepository sourceRepository;
    private final SyncLogRepository syncLogRepository;
    private final SyncService syncService;

    @Transactional(readOnly = true)
    public Map<String, Object> listJobs() {
        List<Map<String, Object>> jobs = sourceRepository.findAllByOrderByCreatedAtDesc().stream()
                .sorted(Comparator.comparing(SourceEntity::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::toJob)
                .toList();
        return Map.of("jobs", jobs, "count", jobs.size());
    }

    @Transactional
    public Map<String, Object> resetJob(String jobId) {
        SourceEntity source = sourceRepository
                .findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source / job not found"));
        Instant finished = Instant.now();
        List<SyncLogEntity> running = syncLogRepository.findBySourceIdAndStatus(jobId, "running");
        for (SyncLogEntity log : running) {
            log.setStatus("error");
            log.setFinishedAt(finished);
            log.setMessage(MANUAL_RESET_MESSAGE);
            log.setReasonCode("upstream_error");
            log.setResponsePreview(MANUAL_RESET_MESSAGE.substring(0, Math.min(500, MANUAL_RESET_MESSAGE.length())));
            syncLogRepository.save(log);
        }
        source.setLastSyncStatus("error");
        source.setLastSyncAt(finished);
        source.setLastSyncFinishedAt(finished);
        source.setLastSyncMessage(MANUAL_RESET_MESSAGE);
        source.setLastSyncError(MANUAL_RESET_MESSAGE);
        source.setLastSyncReasonCode("upstream_error");
        source.setLastSyncResponsePreview(MANUAL_RESET_MESSAGE.substring(0, Math.min(500, MANUAL_RESET_MESSAGE.length())));
        source.setSyncState("sync_failed");
        sourceRepository.save(source);
        return Map.of("ok", true, "id", jobId, "last_sync_status", "error", "detail", MANUAL_RESET_MESSAGE);
    }

    @Transactional
    public Map<String, Object> runSyncNow(String jobId) {
        if (!sourceRepository.existsById(jobId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source / job not found");
        }
        Map<String, Object> result = syncService.runSync(jobId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("source_id", jobId);
        out.put("result", result);
        return out;
    }

    private Map<String, Object> toJob(SourceEntity source) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", source.getId());
        out.put("name", source.getName() != null ? source.getName() : source.getId());
        out.put("source_type", source.getSourceType());
        out.put("active", source.isActive());
        out.put("status", source.getLastSyncStatus());
        Instant started = source.getLastSyncStartedAt() != null ? source.getLastSyncStartedAt() : source.getLastSyncAt();
        out.put("started_at", started != null ? started.toString() : null);
        out.put("running_for_minutes", runningMinutes(started, source.getLastSyncStatus()));
        out.put("last_error", lastError(source));
        out.put("last_sync_finished_at", source.getLastSyncFinishedAt() != null ? source.getLastSyncFinishedAt().toString() : null);
        out.put("last_sync_log_id", source.getLastSyncLogId());
        out.put("refresh_interval_minutes", source.getRefreshIntervalMinutes());
        return out;
    }

    private static Double runningMinutes(Instant started, String status) {
        if (started == null || status == null || !"running".equalsIgnoreCase(status)) {
            return null;
        }
        double minutes = Duration.between(started, Instant.now()).toMillis() / 60000.0;
        return Math.round(Math.max(0.0, minutes) * 1000.0) / 1000.0;
    }

    private static String lastError(SourceEntity source) {
        String st = source.getLastSyncStatus() != null ? source.getLastSyncStatus().toLowerCase() : "";
        if (!List.of("error", "timeout", "partial").contains(st)) {
            return null;
        }
        String err = source.getLastSyncError() != null ? source.getLastSyncError().strip() : "";
        if (!err.isBlank()) {
            return err;
        }
        String msg = source.getLastSyncMessage() != null ? source.getLastSyncMessage().strip() : "";
        return msg.isBlank() ? null : msg;
    }
}
