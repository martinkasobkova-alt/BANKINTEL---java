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
@Table(name = "user_dashboard_pages")
@Getter
@Setter
public class DashboardPageEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String slug;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_default", nullable = false)
    private boolean defaultPage;

    @Column(name = "access_mode", nullable = false)
    private String accessMode = "owner_only";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_user_ids", nullable = false)
    private List<String> allowedUserIds = new ArrayList<>();

    @Column(name = "share_token")
    private String shareToken;

    @Column(name = "share_enabled", nullable = false)
    private boolean shareEnabled;

    @Column(name = "allow_viewer_compare", nullable = false)
    private boolean allowViewerCompare;

    @Column(name = "allow_embed", nullable = false)
    private boolean allowEmbed;

    /** KPI dlaždice nad widgety stránky — stejný tvar jako HomepageConfigEntity.headlineKpis. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headline_kpis", nullable = false)
    private List<Map<String, Object>> headlineKpis = new ArrayList<>();

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
