package cz.bankintel.security;

import cz.bankintel.config.BankIntelProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public static final String ACCESS_COOKIE = "access_token";
    public static final String REFRESH_COOKIE = "refresh_token";
    public static final String CSRF_COOKIE = "csrf_token";
    public static final String ALGORITHM = "HS256";

    private final BankIntelProperties properties;
    private final SecretKey key;

    public JwtService(BankIntelProperties properties) {
        this.properties = properties;
        this.key = deriveKey(properties.jwt().secret());
    }

    private static SecretKey deriveKey(String secret) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length >= 32) {
            return Keys.hmacShaKeyFor(raw);
        }
        try {
            return Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String createAccessToken(String userId, String email, String role) {
        Instant exp = Instant.now().plus(properties.jwt().accessTtlMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .claim("role", role)
                .claim("type", "access")
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    public String createRefreshToken(String userId) {
        Instant exp = Instant.now().plus(properties.jwt().refreshTtlDays(), ChronoUnit.DAYS);
        return Jwts.builder()
                .subject(userId)
                .claim("type", "refresh")
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload());
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public Optional<AuthenticatedUser> parseAccessToken(String token) {
        return parse(token)
                .filter(c -> "access".equals(c.get("type", String.class)))
                .map(c -> new AuthenticatedUser(c.getSubject(), c.get("email", String.class), c.get("role", String.class)));
    }

    public record AuthenticatedUser(String id, String email, String role) {}
}
