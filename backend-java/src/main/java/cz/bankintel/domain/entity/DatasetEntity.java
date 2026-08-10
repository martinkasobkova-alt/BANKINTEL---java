package cz.bankintel.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "datasets")
@Getter
@Setter
public class DatasetEntity {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "source_name")
    private String sourceName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> fields;

    @Column(name = "record_count", nullable = false)
    private int recordCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (fields == null) {
            fields = List.of();
        }
    }
}
