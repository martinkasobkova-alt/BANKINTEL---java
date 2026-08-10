package cz.bankintel.repository;

import cz.bankintel.domain.entity.SourceEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRepository extends JpaRepository<SourceEntity, String> {

    List<SourceEntity> findAllByOrderByCreatedAtDesc();

    Optional<SourceEntity> findByName(String name);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, String id);

    long countByActiveTrue();

    long countByLastSyncStatus(String lastSyncStatus);

    List<SourceEntity> findBySourceTypeOrderByNameAsc(String sourceType);
}
