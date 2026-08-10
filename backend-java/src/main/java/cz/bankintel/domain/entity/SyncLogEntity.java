package cz.bankintel.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "sync_logs")
@Getter
@Setter
public class SyncLogEntity {

    @Id
    private String id;

    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    @Column(nullable = false)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "records_ingested", nullable = false)
    private int recordsIngested;

    @Column(nullable = false)
    private String message = "";

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "response_preview")
    private String responsePreview;
}
