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
@Table(name = "user_uploads")
@Getter
@Setter
public class UserUploadEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "company_id", length = 64)
    private String companyId;

    @Column(name = "original_name", nullable = false, length = 512)
    private String originalName;

    @Column(length = 512)
    private String filename;

    @Column(name = "file_type", length = 32)
    private String fileType;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(nullable = false, length = 64)
    private String status = "uploaded";

    @Column(name = "stored_rel_path", nullable = false, length = 512)
    private String storedRelPath;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detected_tables", nullable = false)
    private List<Map<String, Object>> detectedTables = new ArrayList<>();

    @Column(name = "mapped_series_count", nullable = false)
    private int mappedSeriesCount;

    @Column(name = "extracted_text_preview", nullable = false)
    private String extractedTextPreview = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> errors = new ArrayList<>();

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
        if (detectedTables == null) {
            detectedTables = new ArrayList<>();
        }
        if (errors == null) {
            errors = new ArrayList<>();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
