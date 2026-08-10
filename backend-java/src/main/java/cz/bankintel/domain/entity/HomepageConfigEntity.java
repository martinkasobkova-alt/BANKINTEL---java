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
@Table(name = "homepage_config")
@Getter
@Setter
public class HomepageConfigEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String title = "Exekutivní přehled";

    @Column(name = "title_en")
    private String titleEn;

    @Column(nullable = false)
    private String subtitle =
            "Vámi vybraná data z veřejných portálů · ARAD ČNB a další";

    @Column(name = "subtitle_en")
    private String subtitleEn;

    @Column(name = "default_chart_type", nullable = false)
    private String defaultChartType = "line";

    @Column(name = "default_chart_frequency")
    private String defaultChartFrequency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headline_kpis", nullable = false)
    private List<Map<String, Object>> headlineKpis = new ArrayList<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
