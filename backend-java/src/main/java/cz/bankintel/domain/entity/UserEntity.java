package cz.bankintel.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users")
@Getter
@Setter
public class UserEntity {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String name;

    private String company;
    private String phone;

    @Column(nullable = false)
    private String role = "viewer";

    @Column(name = "access_tier", nullable = false)
    private String accessTier = "free";

    @Column(name = "has_premium_access", nullable = false)
    private boolean hasPremiumAccess;

    @Column(name = "premium_access_granted_at")
    private Instant premiumAccessGrantedAt;

    @Column(name = "premium_access_source")
    private String premiumAccessSource;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = true;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "email_verification_token_hash")
    private String emailVerificationTokenHash;

    @Column(name = "email_verification_expires_at")
    private Instant emailVerificationExpiresAt;

    @Column(name = "password_reset_token_hash")
    private String passwordResetTokenHash;

    @Column(name = "password_reset_expires_at")
    private Instant passwordResetExpiresAt;

    @Column(name = "open_personal_dashboard_on_login", nullable = false)
    private boolean openPersonalDashboardOnLogin;

    @Column(name = "default_dashboard_page_id")
    private String defaultDashboardPageId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "admin_nav_order")
    private List<String> adminNavOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "user_nav_order")
    private List<String> userNavOrder;

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
