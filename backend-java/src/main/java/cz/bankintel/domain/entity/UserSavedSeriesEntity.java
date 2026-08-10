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
@Table(name = "user_saved_series")
@Getter
@Setter
public class UserSavedSeriesEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, length = 200)
    private String source = "";

    @Column(name = "source_type", nullable = false, length = 80)
    private String sourceType = "";

    @Column(name = "source_series_id", nullable = false, length = 500)
    private String sourceSeriesId = "";

    @Column(name = "source_dataset_id", nullable = false, length = 120)
    private String sourceDatasetId = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolver_payload", nullable = false)
    private Map<String, Object> resolverPayload;

    @Column(nullable = false, length = 120)
    private String unit = "";

    @Column(nullable = false, length = 80)
    private String frequency = "";

    @Column(nullable = false, length = 200)
    private String area = "";

    @Column(nullable = false, length = 500)
    private String category = "";

    @Column(name = "start_period", nullable = false, length = 64)
    private String startPeriod = "";

    @Column(name = "end_period", nullable = false, length = 64)
    private String endPeriod = "";

    @Column(name = "last_period", nullable = false, length = 64)
    private String lastPeriod = "";

    @Column(name = "last_value")
    private Double lastValue;

    @Column(name = "point_count", nullable = false)
    private int pointCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_points", nullable = false)
    private List<Map<String, Object>> dataPoints = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> metadata;

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
        if (resolverPayload == null) {
            resolverPayload = Map.of();
        }
        if (dataPoints == null) {
            dataPoints = new ArrayList<>();
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
