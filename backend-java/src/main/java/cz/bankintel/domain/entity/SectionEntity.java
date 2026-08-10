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
@Table(name = "sections")
@Getter
@Setter
public class SectionEntity {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(name = "name_en")
    private String nameEn;

    @Column(nullable = false)
    private String icon = "Folder";

    @Column(nullable = false)
    private String subtitle = "";

    @Column(name = "subtitle_en")
    private String subtitleEn;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "default_chart_type", nullable = false)
    private String defaultChartType = "line";

    @Column(name = "default_chart_frequency")
    private String defaultChartFrequency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "section_pages", nullable = false)
    private List<Map<String, Object>> sectionPages = new ArrayList<>();

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
