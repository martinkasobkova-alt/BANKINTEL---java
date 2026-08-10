package cz.bankintel.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "arad_indicators")
@Getter
@Setter
public class AradIndicatorEntity {

    @Id
    private String id;

    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Column(name = "indicator_id", nullable = false)
    private String indicatorId;

    @Column(nullable = false)
    private String name = "";

    @Column(name = "frequency_code", nullable = false)
    private String frequencyCode = "";

    @Column(name = "frequency_name", nullable = false)
    private String frequencyName = "";

    @Column(nullable = false)
    private String unit = "";

    @Column(name = "unit_mult", nullable = false)
    private String unitMult = "";

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @PrePersist
    void onCreate() {
        if (fetchedAt == null) {
            fetchedAt = Instant.now();
        }
    }
}
