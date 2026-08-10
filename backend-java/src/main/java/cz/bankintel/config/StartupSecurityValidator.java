package cz.bankintel.config;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Fails fast on prod profile when insecure defaults or CORS/cookie config are detected. */
@Component
@Profile("prod")
@Order(0)
public class StartupSecurityValidator implements ApplicationRunner {

    static final String DEV_JWT_SECRET_DEFAULT = "bankintel-dev-jwt-secret-key-min-32-chars!!";

    private static final Logger log = LoggerFactory.getLogger(StartupSecurityValidator.class);

    private final BankIntelProperties properties;

    public StartupSecurityValidator(BankIntelProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        failIfDevJwtSecret();
        failIfDevSeedEnabled();
        failIfDevLogVerificationTokensEnabled();
        failIfCorsWildcardWithCredentials();
        failIfCookieSecureMissingInProd();
        failIfSameSiteNoneWithoutSecure();
        log.info("Production startup security validation passed");
    }

    private void failIfDevJwtSecret() {
        String secret = properties.jwt().secret();
        if (secret == null || secret.isBlank() || DEV_JWT_SECRET_DEFAULT.equals(secret)) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set to a non-default value in production (dev default is forbidden).");
        }
    }

    private void failIfDevSeedEnabled() {
        if (properties.dev().seed()) {
            throw new IllegalStateException("DEV_SEED must not be true in production.");
        }
    }

    private void failIfDevLogVerificationTokensEnabled() {
        if (properties.dev().logVerificationTokens()) {
            throw new IllegalStateException("DEV_LOG_VERIFICATION_TOKENS must not be true in production.");
        }
    }

    private void failIfCorsWildcardWithCredentials() {
        List<String> origins = properties.cors().originList();
        boolean wildcard = origins.stream().anyMatch(o -> "*".equals(o.strip()));
        if (wildcard) {
            throw new IllegalStateException(
                    "CORS cannot use wildcard '*' in production (especially not with credentials). "
                            + "Set explicit origins, e.g. https://example.cz,https://www.example.cz");
        }
        if (origins.isEmpty()) {
            throw new IllegalStateException(
                    "CORS_ORIGINS must be set in production to a comma-separated list of allowed origins (no '*').");
        }
    }

    private void failIfCookieSecureMissingInProd() {
        if (!properties.cookie().secure()) {
            throw new IllegalStateException("In production, COOKIE_SECURE must be true.");
        }
    }

    private void failIfSameSiteNoneWithoutSecure() {
        String sameSite = properties.cookie().sameSite();
        if (sameSite != null && "none".equals(sameSite.strip().toLowerCase(Locale.ROOT)) && !properties.cookie().secure()) {
            throw new IllegalStateException(
                    "When COOKIE_SAMESITE is 'none', COOKIE_SECURE must be true (browser requirement).");
        }
    }
}
