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
@Table(name = "magazine_pdf_links")
@Getter
@Setter
public class MagazinePdfLinkEntity {

    @Id
    private String id;

    @Column(name = "issue_id", nullable = false, length = 36)
    private String issueId;

    @Column(name = "magazine_id", nullable = false, length = 36)
    private String magazineId;

    @Column(nullable = false)
    private int page;

    @Column(nullable = false, length = 240)
    private String label;

    @Column(name = "anchor_text", nullable = false, length = 2000)
    private String anchorText = "";

    @Column(name = "link_kind", nullable = false, length = 16)
    private String linkKind = "text";

    @Column(name = "bbox_x0")
    private Double bboxX0;

    @Column(name = "bbox_y0")
    private Double bboxY0;

    @Column(name = "bbox_x1")
    private Double bboxX1;

    @Column(name = "bbox_y1")
    private Double bboxY1;

    @Column(name = "target_kind", nullable = false, length = 16)
    private String targetKind = "chart";

    @Column(name = "target_title", nullable = false, length = 240)
    private String targetTitle = "";

    @Column(name = "source_type", nullable = false, length = 80)
    private String sourceType = "";

    @Column(name = "set_id", nullable = false, length = 240)
    private String setId = "";

    @Column(name = "link_url", nullable = false, length = 4000)
    private String linkUrl = "";

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
