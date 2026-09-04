package cz.bankintel.service.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import cz.bankintel.domain.dto.ApiKeyDtos.ApiKeyCreateRequest;
import cz.bankintel.domain.entity.ApiKeyEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.ApiKeyRepository;
import cz.bankintel.util.IdGenerator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock private ApiKeyRepository apiKeyRepository;

    private ApiKeyService service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        service = new ApiKeyService(apiKeyRepository);
        user = new UserEntity();
        user.setId("user-1");
    }

    @Test
    void createReturnsTheRawKeyExactlyOnceAndHashesItForStorage() {
        when(apiKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.create(user, new ApiKeyCreateRequest("my key", null));

        String rawKey = (String) result.get("api_key");
        assertThat(rawKey).startsWith("bik_live_");
        assertThat((String) result.get("key_prefix")).isEqualTo(rawKey.substring(0, 16));
        assertThat(result.get("scopes")).isEqualTo(List.of(ApiKeyService.SCOPE_DASHBOARD_WRITE));

        var captor = org.mockito.ArgumentCaptor.forClass(ApiKeyEntity.class);
        org.mockito.Mockito.verify(apiKeyRepository).save(captor.capture());
        assertThat(captor.getValue().getKeyHash()).isEqualTo(IdGenerator.sha256Hex(rawKey));
    }

    @Test
    void listNeverExposesTheRawKey() {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setId("key-1");
        entity.setUserId("user-1");
        entity.setKeyHash("hash");
        entity.setKeyPrefix("bik_live_abc12345");
        entity.setLabel("prod");
        entity.setScopes(List.of(ApiKeyService.SCOPE_DASHBOARD_WRITE));
        when(apiKeyRepository.findByUserIdOrderByCreatedAtDesc("user-1")).thenReturn(List.of(entity));

        List<Map<String, Object>> result = service.list(user);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).doesNotContainKey("api_key").doesNotContainKey("key_hash");
        assertThat(result.get(0).get("key_prefix")).isEqualTo("bik_live_abc12345");
    }

    @Test
    void revokeIsIdempotentAndScopedToTheOwningUser() {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setId("key-1");
        entity.setUserId("user-1");
        when(apiKeyRepository.findByIdAndUserId("key-1", "user-1")).thenReturn(Optional.of(entity));
        when(apiKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.revoke(user, "key-1");

        assertThat(entity.getRevokedAt()).isNotNull();
    }

    @Test
    void revokeOfAnotherUsersKeyThrowsNotFound() {
        when(apiKeyRepository.findByIdAndUserId("key-1", "user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(user, "key-1")).isInstanceOf(ResponseStatusException.class);
    }
}
