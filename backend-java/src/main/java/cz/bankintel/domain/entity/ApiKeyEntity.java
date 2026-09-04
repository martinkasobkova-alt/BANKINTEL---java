package cz.bankintel.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A machine-auth credential for {@code /api/connect/**} — see {@code ApiKeyAuthFilter}. */
@Entity
@Table(name = "api_keys")
@Getter
@Setter
public class ApiKeyEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    /** SHA-256 hex digest of the raw key. The raw key itself is never stored. */
    @Column(name = "key_hash", nullable = false)
    private String keyHash;

    /** First few characters of the raw key, kept in the clear so the owner can tell keys apart. */
    @Column(name = "key_prefix", nullable = false)
    private String keyPrefix;

    @Column(nullable = false)
    private String label = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> scopes = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
