package cz.bankintel.service.sync;

import cz.bankintel.domain.entity.SyncLogEntity;
import cz.bankintel.repository.SourceRepository;
import cz.bankintel.repository.SyncLogRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SyncQueryService {

    private final SyncLogRepository syncLogRepository;
    private final SourceRepository sourceRepository;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listLogs(int limit, String sourceId) {
        PageRequest page = PageRequest.of(0, limit);
        List<SyncLogEntity> logs = sourceId != null && !sourceId.isBlank()
                ? syncLogRepository.findBySourceIdOrderByStartedAtDesc(sourceId, page)
                : syncLogRepository.findAllByOrderByStartedAtDesc(page);
        return logs.stream().map(this::toPublic).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_sources", sourceRepository.count());
        out.put("active_sources", sourceRepository.countByActiveTrue());
        out.put("sources_with_errors", sourceRepository.countByLastSyncStatus("error"));
        out.put("sources_successful", sourceRepository.countByLastSyncStatus("success"));
        out.put("last_sync", syncLogRepository.findFirstByOrderByStartedAtDesc().map(this::toPublic).orElse(null));
        return out;
    }

    private Map<String, Object> toPublic(SyncLogEntity log) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", log.getId());
        out.put("source_id", log.getSourceId());
        out.put("source_name", log.getSourceName());
        out.put("status", log.getStatus());
        out.put("started_at", log.getStartedAt() != null ? log.getStartedAt().toString() : null);
        out.put("finished_at", log.getFinishedAt() != null ? log.getFinishedAt().toString() : null);
        out.put("records_ingested", log.getRecordsIngested());
        out.put("message", log.getMessage() != null ? log.getMessage() : "");
        out.put("http_status", log.getHttpStatus());
        return out;
    }
}
