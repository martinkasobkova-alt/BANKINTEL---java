package cz.bankintel.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "computed_indicators")
@Getter
@Setter
public class ComputedIndicatorEntity {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String operation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "left_ref", nullable = false)
    private Map<String, Object> left;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "right_ref", nullable = false)
    private Map<String, Object> right;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Map<String, Object>> series;

    @Column(nullable = false)
    private String description = "";

    @Column(nullable = false)
    private String unit = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> options;

    @Column(name = "created_by_user_id")
    private String createdByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (left == null) {
            left = Map.of();
        }
        if (right == null) {
            right = Map.of();
        }
        if (series == null) {
            series = List.of();
        }
        if (options == null) {
            options = Map.of();
        }
    }
}
