package cz.bankintel.service.me;

import cz.bankintel.domain.dto.ApiKeyDtos.ApiKeyCreateRequest;
import cz.bankintel.domain.entity.ApiKeyEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.ApiKeyRepository;
import cz.bankintel.util.IdGenerator;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages API keys for {@code /api/connect/**} (see {@link cz.bankintel.security.ApiKeyAuthFilter}).
 * Key management itself is authenticated the normal way (a login JWT) — only the connector endpoints
 * these keys unlock are machine-authenticated.
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    /** The only scope this iteration checks; the column exists for future scopes to be additive. */
    public static final String SCOPE_DASHBOARD_WRITE = "dashboard:write";

    private static final Set<String> KNOWN_SCOPES = Set.of(SCOPE_DASHBOARD_WRITE);
    private static final String KEY_PREFIX = "bik_live_";
    private static final int DISPLAY_PREFIX_LENGTH = 16;

    private final ApiKeyRepository apiKeyRepository;

    @Transactional
    public Map<String, Object> create(UserEntity user, ApiKeyCreateRequest body) {
        List<String> scopes = body.scopes() != null && !body.scopes().isEmpty()
                ? body.scopes().stream().filter(KNOWN_SCOPES::contains).distinct().toList()
                : List.of(SCOPE_DASHBOARD_WRITE);
        if (scopes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatný rozsah oprávnění.");
        }
        String rawKey = KEY_PREFIX + IdGenerator.newToken();
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setId(IdGenerator.newId());
        entity.setUserId(user.getId());
        entity.setKeyHash(IdGenerator.sha256Hex(rawKey));
        entity.setKeyPrefix(rawKey.substring(0, DISPLAY_PREFIX_LENGTH));
        entity.setLabel(body.label() != null ? body.label().strip() : "");
        entity.setScopes(scopes);
        entity = apiKeyRepository.save(entity);

        Map<String, Object> out = serialize(entity);
        // The only time the raw key is ever returned — it cannot be recovered afterward.
        out.put("api_key", rawKey);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UserEntity user) {
        return apiKeyRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::serialize)
                .toList();
    }

    @Transactional
    public void revoke(UserEntity user, String id) {
        ApiKeyEntity entity = apiKeyRepository
                .findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Klíč nenalezen"));
        if (entity.getRevokedAt() == null) {
            entity.setRevokedAt(Instant.now());
            apiKeyRepository.save(entity);
        }
    }

    private Map<String, Object> serialize(ApiKeyEntity entity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entity.getId());
        out.put("key_prefix", entity.getKeyPrefix());
        out.put("label", entity.getLabel());
        out.put("scopes", entity.getScopes());
        out.put("created_at", instantToString(entity.getCreatedAt()));
        out.put("last_used_at", instantToString(entity.getLastUsedAt()));
        out.put("revoked_at", instantToString(entity.getRevokedAt()));
        return out;
    }

    private static String instantToString(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
