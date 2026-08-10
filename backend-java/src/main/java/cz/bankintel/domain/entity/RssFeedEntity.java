package cz.bankintel.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "rss_feeds")
@Getter
@Setter
public class RssFeedEntity {

    @Id
    private String id;

    @Column(name = "owner_user_id", length = 36)
    private String ownerUserId;

    @Column(nullable = false, length = 16)
    private String scope = "global";

    @Column(nullable = false, length = 500)
    private String name;

    @Column(nullable = false, length = 4000)
    private String url;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType = "rss";

    @Column(nullable = false, length = 200)
    private String category = "";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "refresh_interval_minutes", nullable = false)
    private int refreshIntervalMinutes = 60;

    @Column(name = "auto_translate", nullable = false)
    private boolean autoTranslate;

    @Column(name = "publish_to_articles", nullable = false)
    private boolean publishToArticles;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "last_sync_status", length = 32)
    private String lastSyncStatus;

    @Column(name = "last_sync_message", nullable = false, columnDefinition = "TEXT")
    private String lastSyncMessage = "";

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
