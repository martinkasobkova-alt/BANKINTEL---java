package cz.bankintel.service.sources;

import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.util.CredentialMasker;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SourceMapper {

    private SourceMapper() {}

    public static Map<String, Object> toPublic(SourceEntity source) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", source.getId());
        out.put("name", source.getName());
        out.put("source_type", source.getSourceType());
        out.put("base_url", source.getBaseUrl() != null ? source.getBaseUrl() : "");
        out.put("endpoint", source.getEndpoint() != null ? source.getEndpoint() : "");
        out.put("method", source.getMethod() != null ? source.getMethod() : "GET");
        out.put("auth_type", source.getAuthType() != null ? source.getAuthType() : "none");
        out.put("credentials_masked", CredentialMasker.maskCredentials(ConnectorRegistry.credentialsFrom(source.getConnectorConfig())));
        out.put("headers", ConnectorRegistry.headersFrom(source.getConnectorConfig()));
        out.put("query_params", ConnectorRegistry.queryParamsFrom(source.getConnectorConfig()));
        out.put("refresh_interval_minutes", source.getRefreshIntervalMinutes());
        out.put("active", source.isActive());
        out.put("dataset_name", source.getDatasetName());
        out.put("last_sync_at", formatInstant(source.getLastSyncAt()));
        out.put("last_sync_started_at", formatInstant(source.getLastSyncStartedAt()));
        out.put("last_sync_finished_at", formatInstant(source.getLastSyncFinishedAt()));
        out.put("last_sync_duration_ms", source.getLastSyncDurationMs());
        out.put("last_sync_records_ingested", source.getLastSyncRecordsIngested());
        out.put("last_sync_http_status", source.getLastSyncHttpStatus());
        out.put("last_sync_error", source.getLastSyncError() != null ? source.getLastSyncError() : "");
        out.put("last_sync_status", source.getLastSyncStatus());
        out.put("last_sync_message", source.getLastSyncMessage() != null ? source.getLastSyncMessage() : "");
        out.put("last_sync_reason_code", source.getLastSyncReasonCode() != null ? source.getLastSyncReasonCode() : "");
        out.put("last_sync_response_preview", source.getLastSyncResponsePreview() != null ? source.getLastSyncResponsePreview() : "");
        out.put("created_at", formatInstant(source.getCreatedAt()));
        return out;
    }

    private static String formatInstant(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
