package cz.bankintel.security;

import cz.bankintel.domain.entity.ApiKeyEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.ApiKeyRepository;
import cz.bankintel.repository.UserRepository;
import cz.bankintel.util.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Machine auth for {@code /api/connect/**} — the only path namespace this filter ever touches. A
 * request elsewhere carrying a {@code bik_...} bearer token is left unauthenticated rather than
 * accepted, so a key scoped to "push chart data" can never reach {@code /api/me/**} or anything else
 * the underlying user could otherwise do.
 *
 * <p>Sets the exact same {@link JwtService.AuthenticatedUser} principal shape
 * {@link JwtAuthFilter} does, so {@link CurrentUser} and everything built on it work unchanged for an
 * API-key-authenticated request — only the {@code /api/connect/**} controllers themselves need to
 * additionally check the key's scopes before acting.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "bik_";
    private static final String CONNECT_PATH_PREFIX = "/api/connect/";

    private final ApiKeyRepository apiKeyRepository;
    private final UserRepository userRepository;

    public ApiKeyAuthFilter(ApiKeyRepository apiKeyRepository, UserRepository userRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith(CONNECT_PATH_PREFIX)) {
            resolveApiKeyToken(request).flatMap(this::authenticate).ifPresent(authenticated -> {
                List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_" + authenticated.user().role().toUpperCase()));
                // A key's scopes become authorities too, so endpoints can gate with a plain
                // @PreAuthorize("hasAuthority('SCOPE_...')") instead of re-deriving scope from the
                // request by hand.
                for (String scope : authenticated.scopes()) {
                    authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
                }
                var auth = new UsernamePasswordAuthenticationToken(authenticated.user(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        filterChain.doFilter(request, response);
    }

    private Optional<AuthenticatedKey> authenticate(String rawKey) {
        ApiKeyEntity key = apiKeyRepository.findByKeyHash(IdGenerator.sha256Hex(rawKey)).orElse(null);
        if (key == null || key.getRevokedAt() != null) {
            return Optional.empty();
        }
        Optional<UserEntity> user = userRepository.findById(key.getUserId());
        if (user.isEmpty()) {
            return Optional.empty();
        }
        key.setLastUsedAt(Instant.now());
        apiKeyRepository.save(key);
        UserEntity u = user.get();
        List<String> scopes = key.getScopes() != null ? key.getScopes() : List.of();
        return Optional.of(new AuthenticatedKey(
                new JwtService.AuthenticatedUser(u.getId(), u.getEmail(), u.getRole()), scopes));
    }

    static Optional<String> resolveApiKeyToken(HttpServletRequest request) {
        Optional<String> bearer = JwtAuthFilter.readBearerToken(request);
        return bearer.filter(token -> token.startsWith(KEY_PREFIX));
    }

    private record AuthenticatedKey(JwtService.AuthenticatedUser user, List<String> scopes) {}
}
