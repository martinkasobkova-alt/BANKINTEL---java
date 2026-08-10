package cz.bankintel.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "user_uploaded_series")
@Getter
@Setter
public class UserUploadedSeriesEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "upload_id", nullable = false)
    private String uploadId;

    @Column(name = "company_id", length = 64)
    private String companyId;

    @Column(name = "dataset_id", nullable = false, length = 36)
    private String datasetId;

    @Column(nullable = false, length = 320)
    private String title;

    @Column(nullable = false)
    private String description = "";

    @Column(name = "metric_type", nullable = false, length = 64)
    private String metricType = "other";

    @Column(length = 64)
    private String unit;

    @Column(length = 32)
    private String currency;

    @Column(nullable = false, length = 16)
    private String frequency = "unknown";

    @Column(name = "sector_id", length = 64)
    private String sectorId;

    @Column(length = 64)
    private String geo;

    @Column(name = "detected_domain", length = 128)
    private String detectedDomain;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detected_domains", nullable = false)
    private List<String> detectedDomains = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> tags = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Map<String, Object>> observations = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> periods = new ArrayList<>();

    @Column(name = "mapping_confidence", nullable = false)
    private double mappingConfidence;

    @Column(name = "mapping_reason", nullable = false)
    private String mappingReason = "";

    @Column(name = "is_private", nullable = false)
    private boolean isPrivate = true;

    @Column(nullable = false, length = 16)
    private String priority = "high";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        ensureLists();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    private void ensureLists() {
        if (detectedDomains == null) {
            detectedDomains = new ArrayList<>();
        }
        if (tags == null) {
            tags = new ArrayList<>();
        }
        if (observations == null) {
            observations = new ArrayList<>();
        }
        if (periods == null) {
            periods = new ArrayList<>();
        }
    }
}
