package cz.bankintel.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sources")
@Getter
@Setter
public class SourceEntity {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "base_url", nullable = false)
    private String baseUrl = "";

    @Column(nullable = false)
    private String endpoint = "";

    @Column(nullable = false)
    private String method = "GET";

    @Column(name = "auth_type", nullable = false)
    private String authType = "none";

    @Column(name = "refresh_interval_minutes", nullable = false)
    private int refreshIntervalMinutes = 60;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "dataset_name")
    private String datasetName;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_finished_at")
    private Instant lastSyncFinishedAt;

    @Column(name = "last_sync_duration_ms")
    private Integer lastSyncDurationMs;

    @Column(name = "last_sync_records_ingested")
    private Integer lastSyncRecordsIngested;

    @Column(name = "last_sync_http_status")
    private Integer lastSyncHttpStatus;

    @Column(name = "last_sync_error", nullable = false)
    private String lastSyncError = "";

    @Column(name = "last_sync_status")
    private String lastSyncStatus;

    @Column(name = "last_sync_message", nullable = false)
    private String lastSyncMessage = "";

    @Column(name = "last_sync_reason_code")
    private String lastSyncReasonCode;

    @Column(name = "last_sync_response_preview")
    private String lastSyncResponsePreview;

    @Column(name = "last_sync_log_id")
    private String lastSyncLogId;

    @Column(name = "sync_state")
    private String syncState;

    @Column(name = "sync_queue_state")
    private String syncQueueState;

    @Column(name = "sync_retry_after_sec")
    private Integer syncRetryAfterSec;

    @Column(name = "sync_retry_at")
    private Instant syncRetryAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "connector_config", nullable = false)
    private Map<String, Object> connectorConfig;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (connectorConfig == null) {
            connectorConfig = Map.of();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
