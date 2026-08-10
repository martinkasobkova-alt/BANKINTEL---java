package cz.bankintel.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "user_dashboard_widgets")
@Getter
@Setter
public class DashboardWidgetEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "page_id", nullable = false)
    private String pageId;

    @Column(name = "widget_type", nullable = false)
    private String widgetType;

    @Column(nullable = false)
    private String title = "";

    @Column(nullable = false)
    private String description = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> config = new HashMap<>();

    @Column(nullable = false)
    private String width = "full";

    @Column(name = "row_span")
    private Integer rowSpan;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_snapshot")
    private Map<String, Object> dataSnapshot;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "cache_key")
    private String cacheKey;

    @Column(name = "snapshot_status")
    private String snapshotStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
