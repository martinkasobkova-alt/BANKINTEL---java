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
@Table(name = "magazine_issues")
@Getter
@Setter
public class MagazineIssueEntity {

    @Id
    private String id;

    @Column(name = "magazine_id", nullable = false, length = 36)
    private String magazineId;

    @Column(name = "issue_label", nullable = false, length = 120)
    private String issueLabel;

    @Column(nullable = false, length = 240)
    private String title = "";

    @Column(nullable = false, length = 4000)
    private String description = "";

    @Column(name = "cover_image_url", length = 2048)
    private String coverImageUrl;

    @Column(name = "published_at", nullable = false, length = 80)
    private String publishedAt = "";

    @Column(name = "original_name", nullable = false, length = 512)
    private String originalName = "issue.pdf";

    @Column(name = "stored_file_id", length = 36)
    private String storedFileId;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "ingest_status", nullable = false, length = 32)
    private String ingestStatus = "pending";

    @Column(name = "ingest_error", nullable = false, length = 500)
    private String ingestError = "";

    @Column(name = "page_count", nullable = false)
    private int pageCount;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "ingested_at")
    private Instant ingestedAt;

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
