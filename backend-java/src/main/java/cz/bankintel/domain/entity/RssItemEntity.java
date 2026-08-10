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
@Table(name = "rss_items")
@Getter
@Setter
public class RssItemEntity {

    @Id
    private String id;

    @Column(name = "feed_id", nullable = false, length = 36)
    private String feedId;

    @Column(name = "owner_user_id", length = 36)
    private String ownerUserId;

    @Column(nullable = false, length = 2000)
    private String title = "";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary = "";

    @Column(nullable = false, length = 4000)
    private String link = "";

    @Column(nullable = false, length = 2000)
    private String guid = "";

    @Column(nullable = false, length = 500)
    private String author = "";

    @Column(name = "source_name", nullable = false, length = 500)
    private String sourceName = "";

    @Column(nullable = false, length = 200)
    private String category = "";

    @Column(name = "title_cs", length = 2000)
    private String titleCs;

    @Column(name = "summary_cs", columnDefinition = "TEXT")
    private String summaryCs;

    @Column(name = "draft_article_id", length = 36)
    private String draftArticleId;

    @Column(name = "published_at")
    private Instant publishedAt;

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
