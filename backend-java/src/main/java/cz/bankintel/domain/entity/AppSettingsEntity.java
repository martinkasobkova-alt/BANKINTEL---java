package cz.bankintel.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "app_settings")
@Getter
@Setter
public class AppSettingsEntity {

    @Id
    private String id;

    @Column(name = "subscriber_registration_code_hash")
    private String subscriberRegistrationCodeHash;

    @Column(name = "subscriber_code_updated_at")
    private Instant subscriberCodeUpdatedAt;

    @Column(name = "subscriber_code_updated_by")
    private String subscriberCodeUpdatedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings_json", nullable = false)
    private Map<String, Object> settingsJson = new HashMap<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
