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
@Table(name = "formulas")
@Getter
@Setter
public class FormulaEntity {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String expression;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "group_by", nullable = false)
    private List<String> groupBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> datasets;

    @Column(nullable = false)
    private String description = "";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (groupBy == null) {
            groupBy = List.of("date");
        }
        if (datasets == null) {
            datasets = List.of();
        }
    }
}
