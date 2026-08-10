package cz.bankintel.service.sources;

import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.repository.SourceRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SourceCatalogStubService {

    private final SourceRepository sourceRepository;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCatalogStubs() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (SourceEntity source : sourceRepository.findAllByOrderByCreatedAtDesc()) {
            items.add(toCatalogStub(source));
        }
        return items;
    }

    private static Map<String, Object> toCatalogStub(SourceEntity source) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", source.getId());
        out.put("name", source.getName());
        out.put("source_type", source.getSourceType());
        if (source.getLastSyncStatus() != null) {
            out.put("last_sync_status", source.getLastSyncStatus());
        }
        if (source.getLastSyncMessage() != null && !source.getLastSyncMessage().isBlank()) {
            out.put("last_sync_message", source.getLastSyncMessage());
        }
        if (source.getLastSyncReasonCode() != null) {
            out.put("last_sync_reason_code", source.getLastSyncReasonCode());
        }
        if (source.getSyncState() != null) {
            out.put("sync_state", source.getSyncState());
        }
        if (source.getSyncQueueState() != null) {
            out.put("sync_queue_state", source.getSyncQueueState());
        }
        if (source.getSyncRetryAfterSec() != null) {
            out.put("sync_retry_after_sec", source.getSyncRetryAfterSec());
        }
        if (source.getSyncRetryAt() != null) {
            out.put("sync_retry_at", source.getSyncRetryAt().toString());
        }

        Map<String, Object> config = source.getConnectorConfig() != null ? source.getConnectorConfig() : Map.of();
        Object queryParams = config.get("query_params");
        if (queryParams instanceof Map<?, ?> qp && !qp.isEmpty()) {
            out.put("query_params", qp);
        }
        for (String key :
                List.of("set_id", "fred_series_id", "ecb_flow", "ecb_series_key", "bis_dataflow", "bis_series_key")) {
            Object value = config.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                out.put(key, value);
            }
        }

        String st = source.getSourceType() != null ? source.getSourceType().trim() : "";
        String ep = source.getEndpoint() != null ? source.getEndpoint().trim() : "";
        if ("eurostat".equals(st) && !ep.isBlank() && !ep.contains("://")) {
            out.putIfAbsent("eurostat_dataset_code", ep.startsWith("/") ? ep.substring(1) : ep);
        }
        if ("csu".equals(st) && !ep.isBlank() && !ep.contains("://")) {
            String[] parts = ep.replaceAll("/+$", "").split("/");
            out.putIfAbsent("csu_selection_code", parts.length > 0 ? parts[parts.length - 1] : ep);
        }
        return out;
    }
}
