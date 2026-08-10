package cz.bankintel.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "feature_access_rules")
@Getter
@Setter
public class FeatureAccessRuleEntity {

    @Id
    @Column(name = "feature_key")
    private String featureKey;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String description = "";

    @Column(name = "access_level", nullable = false)
    private String accessLevel = "subscriber";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
