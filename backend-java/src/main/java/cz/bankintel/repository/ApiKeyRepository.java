package cz.bankintel.repository;

import cz.bankintel.domain.entity.ApiKeyEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, String> {

    Optional<ApiKeyEntity> findByKeyHash(String keyHash);

    List<ApiKeyEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<ApiKeyEntity> findByIdAndUserId(String id, String userId);
}
